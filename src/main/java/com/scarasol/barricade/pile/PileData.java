package com.scarasol.barricade.pile;

import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.Barricade;
import java.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

/** Server current-state authority. Never samples, generates or records operations. */
public final class PileData extends SavedData {
    private static final String NAME = "barricade_embedded_v1";
    private static final int FORMAT = 3;
    private static final Map<ServerLevel, PileData> LOADED = new WeakHashMap<>();
    private final UUID world;
    private final Map<UUID, Region> regions = new HashMap<>();
    private final Map<BlockPos, UUID> cells = new HashMap<>();
    private final Map<Long, Map<UUID, Set<BlockPos>>> chunks = new HashMap<>();
    private final Map<String, PaletteSpec> palettes = new HashMap<>();
    private long nextEmbedded = 1, serial, sampleEpoch;
    private int embeddedCount;
    private static final class Region {
        final UUID id;
        FieldDefinition definition;
        final Set<BlockPos> domain = new HashSet<>();
        final SpatialMask mask = new SpatialMask();
        final Map<BlockPos, EmbeddedBlock> embedded = new HashMap<>();
        long revision = 1;
        Region(UUID id, FieldDefinition definition) { this.id = id; this.definition = definition; }
        FieldRegion snapshot() {
            Set<BlockPos> baseline = new HashSet<>(domain); baseline.addAll(mask.cells());
            return new FieldRegion(id, revision, definition, domain, mask, embedded, baseline);
        }
    }
    public PileData() { this(UUID.randomUUID()); }
    private PileData(UUID world) { this.world = world; }
    public static PileData get(ServerLevel level) {
        return LOADED.computeIfAbsent(level, source -> loadCurrent(source.getDataStorage(),
                DimensionType.getStorageFolder(source.dimension(), source.getServer().getWorldPath(LevelResource.ROOT))
                        .resolve("data").resolve(NAME + ".dat")));
    }
    static PileData loadCurrent(DimensionDataStorage storage, Path file) {
        return loadCurrent(storage, file, PileService::palette);
    }
    static PileData loadCurrent(DimensionDataStorage storage, Path file, Function<String, PaletteSpec> resolve) {
        PileData data;
        CompoundTag saved = null;
        if (Files.notExists(file)) data = new PileData();
        else {
            try {
                saved = storage.readTagFromDisk(NAME,
                        SharedConstants.getCurrentVersion().getDataVersion().getVersion()).getCompound("data");
                data = readCurrent(saved, resolve);
            } catch (IOException | RuntimeException exception) {
                Barricade.LOGGER.warn("Resetting unreadable barricade data: {}: {}", file, exception.toString());
                data = new PileData();
                if (saved != null)
                    for (Tag value : saved.getList("Regions", Tag.TAG_COMPOUND))
                        data.rebuildRegion((CompoundTag) value, saved, resolve);
                data.setDirty();
            }
        }
        storage.set(NAME, data);
        return data;
    }
    public static PileData loaded(ServerLevel level) {
        return LOADED.get(level);
    }
    static void clearLoaded() { LOADED.clear(); }
    public UUID world() { return world; }
    public long serial() { return serial; }
    public long sampleEpoch() { return sampleEpoch; }
    public int cellCount() { return cells.size(); }
    public int regionCount() { return regions.size(); }
    public boolean contains(BlockPos pos) { return cells.containsKey(pos); }
    Set<BlockPos> cellsInChunk(long chunk) {
        var members = chunks.get(chunk);
        if (members == null) return Set.of();
        Set<BlockPos> result = new HashSet<>();
        members.values().forEach(result::addAll);
        return result;
    }
    public boolean hasEmbedded() { return embeddedCount != 0; }
    public UUID regionAt(BlockPos pos) { return cells.get(pos); }
    public PaletteSpec palette(String id) { return palettes.get(id); }
    public FieldRegion snapshot(UUID id) { Region region = regions.get(id); return region == null ? null : region.snapshot(); }
    public FieldRegion snapshot(UUID id, Set<Long> visibleChunks, Set<Long> maskChunks) {
        Region region = regions.get(id);
        if (region == null) return null;
        Set<BlockPos> domain = new HashSet<>();
        region.domain.stream().filter(pos -> visibleChunks.contains(ChunkPos.asLong(pos))).forEach(domain::add);
        if (domain.isEmpty()) return null;
        Map<BlockPos, EmbeddedBlock> blocks = new HashMap<>();
        region.embedded.forEach((pos, block) -> { if (domain.contains(pos)) blocks.put(pos, block); });
        SpatialMask mask = region.mask.inChunks(maskChunks);
        Set<BlockPos> baseline = new HashSet<>(mask.cells());
        region.domain.stream().filter(pos -> maskChunks.contains(ChunkPos.asLong(pos))).forEach(baseline::add);
        return new FieldRegion(id, region.revision, region.definition, domain, mask, blocks, baseline);
    }
    public long revision(UUID id) { return regions.get(id).revision; }
    public Map<BlockPos, EmbeddedBlock> embedded(UUID id) {
        Region region = regions.get(id); return region == null ? Map.of() : Map.copyOf(region.embedded);
    }
    public EmbeddedBlock embeddedAt(BlockPos pos) {
        Region region = regions.get(cells.get(pos)); return region == null ? null : region.embedded.get(pos);
    }
    public void add(BlockPos pos, UUID identity, long seed) {
        add(pos, identity, seed, PaletteSpec.create(PileModels.candidates(), ""));
    }
    public void add(BlockPos position, UUID identity, long seed, PaletteSpec palette) {
        BlockPos pos = position.immutable();
        if (cells.containsKey(pos)) return;
        Set<UUID> adjacent = new TreeSet<>();
        for (Direction direction : Direction.values()) {
            UUID id = cells.get(pos.relative(direction));
            if (id != null && regions.get(id).definition.palette().equals(palette.id())) adjacent.add(id);
        }
        Region region = adjacent.stream().map(regions::get).min(
                Comparator.<Region>comparingInt(value -> -value.domain.size()).thenComparing(value -> value.id)).orElse(null);
        if (region == null) {
            if (regions.containsKey(identity)) throw new IllegalArgumentException("Duplicate region identity");
            region = new Region(identity, FieldDefinition.create(seed, palette));
            palettes.putIfAbsent(palette.id(), palette); regions.put(identity, region);
        }
        for (UUID id : adjacent) {
            Region other = regions.get(id);
            if (other == region) continue;
            region.mask.union(other.mask); region.embedded.putAll(other.embedded);
            for (BlockPos cell : other.domain) {
                unindex(cell, other.id); cells.put(cell, region.id); index(cell, region.id);
            }
            region.domain.addAll(other.domain); regions.remove(other.id);
        }
        region.domain.add(pos); region.mask.clear(pos);
        cells.put(pos, region.id); index(pos, region.id);
        changed(region); collectPalettes();
    }
    public void remove(BlockPos pos) {
        UUID id = cells.remove(pos); Region region = regions.get(id);
        if (region == null) return;
        unindex(pos, id); region.domain.remove(pos); region.mask.set(pos);
        if (region.embedded.remove(pos) != null) embeddedCount--;
        if (region.domain.isEmpty()) regions.remove(id);
        changed(region); collectPalettes();
    }
    public EmbeddedBlock addEmbedded(BlockPos position) {
        BlockPos pos = position.immutable(); Region region = regions.get(cells.get(pos));
        if (region == null || region.embedded.containsKey(pos)) return null;
        EmbeddedBlock block = new EmbeddedBlock(pos, nextEmbedded++, 1);
        region.embedded.put(pos, block); embeddedCount++; changed(region); return block;
    }
    public EmbeddedBlock updateEmbedded(BlockPos position) {
        Region region = regions.get(cells.get(position));
        EmbeddedBlock old = region == null ? null : region.embedded.get(position);
        if (old == null) return null;
        EmbeddedBlock block = new EmbeddedBlock(old.pos(), old.instance(), old.revision() + 1);
        region.embedded.put(block.pos(), block); changed(region); return block;
    }
    public boolean removeEmbedded(BlockPos position) {
        Region region = regions.get(cells.get(position));
        if (region == null || region.embedded.remove(position) == null) return false;
        embeddedCount--; changed(region); return true;
    }
    boolean cancelEmbedding(BlockPos position) { return removeEmbedded(position); }
    public Set<BlockPos> connectedComponent(BlockPos start) {
        UUID id = cells.get(start);
        if (id == null) return Set.of();
        Set<BlockPos> result = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        result.add(start.immutable()); queue.add(start.immutable());
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (id.equals(cells.get(next)) && result.add(next)) queue.addLast(next);
            }
        }
        return Set.copyOf(result);
    }
    public int regenerate(BlockPos pos, PaletteSpec palette, long seed, UUID identity) {
        Set<BlockPos> component = connectedComponent(pos);
        if (component.isEmpty()) return 0;
        Region old = regions.get(cells.get(pos));
        if (regions.containsKey(identity)) throw new IllegalArgumentException("Duplicate regeneration identity");
        Region replacement = new Region(identity, FieldDefinition.create(seed, palette));
        replacement.domain.addAll(component);
        for (BlockPos cell : component) {
            EmbeddedBlock block = old.embedded.remove(cell);
            if (block != null) replacement.embedded.put(cell, block);
            unindex(cell, old.id); cells.put(cell, identity); index(cell, identity);
        }
        old.domain.removeAll(component);
        if (old.domain.isEmpty()) regions.remove(old.id); else old.revision++;
        regions.put(identity, replacement); palettes.putIfAbsent(palette.id(), palette);
        changed(replacement); collectPalettes(); return component.size();
    }
    /** Explicit geometry version boundary, never an implicit rule change during growth. */
    public void resample(PaletteSpec palette) {
        resample(ignored -> palette);
    }
    public void resample(java.util.function.Function<PaletteSpec, PaletteSpec> resolve) {
        Map<UUID, PaletteSpec> refreshed = new HashMap<>();
        for (Region region : regions.values())
            refreshed.put(region.id, Objects.requireNonNull(resolve.apply(palettes.get(region.definition.palette()))));
        sampleEpoch++;
        for (Region region : regions.values()) {
            PaletteSpec palette = refreshed.get(region.id);
            palettes.putIfAbsent(palette.id(), palette);
            region.definition = FieldDefinition.create(region.definition.seed(), palette); region.revision++;
        }
        serial++; setDirty(); collectPalettes();
    }
    public Set<UUID> visible(Set<Long> watched) {
        Set<UUID> result = new TreeSet<>();
        for (long chunk : watched) {
            int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                var members = chunks.get(ChunkPos.asLong(x + dx, z + dz));
                if (members != null) result.addAll(members.keySet());
            }
        }
        return result;
    }
    private void changed(Region region) { region.revision++; serial++; setDirty(); }
    private void collectPalettes() {
        Set<String> used = new HashSet<>();
        regions.values().forEach(region -> used.add(region.definition.palette())); palettes.keySet().retainAll(used);
    }
    private void index(BlockPos pos, UUID id) {
        chunks.computeIfAbsent(ChunkPos.asLong(pos), ignored -> new HashMap<>())
                .computeIfAbsent(id, ignored -> new HashSet<>()).add(pos);
    }
    private void unindex(BlockPos pos, UUID id) {
        long key = ChunkPos.asLong(pos); var members = chunks.get(key);
        if (members == null) return;
        members.computeIfPresent(id, (ignored, positions) -> {
            positions.remove(pos); return positions.isEmpty() ? null : positions;
        });
        if (members.isEmpty()) chunks.remove(key);
    }
    @Override public CompoundTag save(CompoundTag tag) {
        tag.putUUID("World", world); tag.putInt("Format", FORMAT);
        tag.putLong("NextEmbedded", nextEmbedded); tag.putLong("Serial", serial); tag.putLong("Samples", sampleEpoch);
        ListTag rules = new ListTag(), groups = new ListTag();
        palettes.values().stream().sorted(Comparator.comparing(PaletteSpec::id)).forEach(rule -> rules.add(rule.save()));
        regions.values().stream().sorted(Comparator.comparing(region -> region.id)).forEach(region -> groups.add(region.snapshot().save()));
        tag.put("Palettes", rules); tag.put("Regions", groups); return tag;
    }
    public static PileData load(CompoundTag tag) {
        return readCurrent(tag, PileService::palette);
    }
    static PileData readCurrent(CompoundTag tag, Function<String, PaletteSpec> resolve) {
        if (tag.getInt("Format") != FORMAT)
            throw new IllegalArgumentException("Unsupported barricade data Format " + tag.getInt("Format")
                    + " (required " + FORMAT + ")");
        if (!tag.hasUUID("World")) throw new IllegalArgumentException("Missing barricade World identity");
        PileData data = new PileData(tag.getUUID("World"));
        data.nextEmbedded = tag.getLong("NextEmbedded"); data.serial = tag.getLong("Serial");
        data.sampleEpoch = tag.getLong("Samples");
        if (data.nextEmbedded <= 0 || data.serial < 0 || data.sampleEpoch < 0)
            throw new IllegalArgumentException("Invalid current barricade counters");
        for (Tag value : tag.getList("Palettes", Tag.TAG_COMPOUND)) {
            CompoundTag saved = (CompoundTag) value;
            try {
                PaletteSpec palette = PaletteSpec.load(saved);
                if (data.palettes.putIfAbsent(palette.id(), palette) != null)
                    throw new IllegalArgumentException("Duplicate palette");
            } catch (RuntimeException exception) {
                Barricade.LOGGER.warn("Discarding unreadable barricade palette {}: {}",
                        saved.getString("Id"), exception.toString());
                data.setDirty();
            }
        }
        Set<Long> instances = new HashSet<>();
        List<CompoundTag> reset = new ArrayList<>();
        for (Tag value : tag.getList("Regions", Tag.TAG_COMPOUND)) {
            CompoundTag saved = (CompoundTag) value;
            try {
                FieldRegion snapshot = FieldRegion.load(saved);
                if (!data.palettes.containsKey(snapshot.definition().palette())) throw new IllegalArgumentException("Missing palette");
                if (data.regions.containsKey(snapshot.id())) throw new IllegalArgumentException("Duplicate region");
                Set<Long> members = new HashSet<>();
                for (EmbeddedBlock block : snapshot.embedded().values())
                    if (block.instance() >= data.nextEmbedded || instances.contains(block.instance())
                            || !members.add(block.instance()))
                        throw new IllegalArgumentException("Invalid embedded instance");
                for (BlockPos pos : snapshot.domain())
                    if (data.cells.containsKey(pos)) throw new IllegalArgumentException("Overlapping regions");

                // Publish only a fully readable region; a rejected one must leave no partial indexes.
                Region region = new Region(snapshot.id(), snapshot.definition());
                region.revision = snapshot.revision(); region.domain.addAll(snapshot.domain());
                region.mask.union(snapshot.mask()); region.embedded.putAll(snapshot.embedded());
                data.regions.put(region.id, region); instances.addAll(members);
                data.embeddedCount += region.embedded.size();
                for (BlockPos pos : region.domain) {
                    data.cells.put(pos, region.id);
                    data.index(pos, region.id);
                }
            } catch (RuntimeException exception) {
                Barricade.LOGGER.warn("Rebuilding unreadable barricade region {}: {}",
                        saved.get("Id"), exception.toString());
                reset.add(saved);
                data.setDirty();
            }
        }
        for (CompoundTag saved : reset) data.rebuildRegion(saved, tag, resolve);
        if (data.isDirty()) data.collectPalettes();
        return data;
    }
    private void rebuildRegion(CompoundTag saved, CompoundTag root, Function<String, PaletteSpec> resolve) {
        Set<BlockPos> domain = new HashSet<>(RecipeCodec.positions(saved, "Domain"));
        domain.removeAll(cells.keySet());
        if (domain.isEmpty()) return;
        String paletteId = saved.getString("Palette");
        if (paletteId.isEmpty() && saved.contains("Head", Tag.TAG_ANY_NUMERIC))
            for (Tag value : root.getList("Recipes", Tag.TAG_COMPOUND)) {
                CompoundTag recipe = (CompoundTag) value;
                if (recipe.getLong("Id") == saved.getLong("Head")) {
                    paletteId = recipe.getString("Palette");
                    break;
                }
            }
        String source = saved.getString("SourceTag");
        if (source.isEmpty())
            for (Tag value : root.getList("Palettes", Tag.TAG_COMPOUND)) {
                CompoundTag palette = (CompoundTag) value;
                if (palette.getString("Id").equals(paletteId)) {
                    source = palette.getString("SourceTag");
                    break;
                }
            }
        PaletteSpec palette = resolve.apply(source.isEmpty() ? PileModels.DEFAULT_TAG : source);
        Region region = new Region(UUID.randomUUID(), FieldDefinition.create(saved.getLong("Seed"), palette));
        region.domain.addAll(domain);
        palettes.putIfAbsent(palette.id(), palette); regions.put(region.id, region);
        for (BlockPos pos : domain) {
            cells.put(pos, region.id); index(pos, region.id);
        }
        changed(region);
    }
}
