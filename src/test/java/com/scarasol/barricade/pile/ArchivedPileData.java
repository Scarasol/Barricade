package com.scarasol.barricade.pile;

import com.scarasol.barricade.recipe.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/** Server authority: topology and reproducible inputs only. No sampled geometry. */
public final class ArchivedPileData extends SavedData {
    private static final String NAME = "barricade_embedded_v1";
    private static final int FORMAT = 1;
    private static final Map<ServerLevel, ArchivedPileData> LOADED = new WeakHashMap<>();
    private final UUID world;
    private final Map<UUID, Region> regions = new HashMap<>();
    private final Map<BlockPos, UUID> cells = new HashMap<>();
    private final Map<Long, Map<UUID, Integer>> chunks = new HashMap<>();
    private final Map<Long, ArchivedPileRecipe> recipes = new HashMap<>();
    private final Map<String, PaletteSpec> palettes = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private long nextRecipe = 1, nextEmbedded = 1, serial, sampleEpoch;
    private int embeddedCount;

    public ArchivedPileData() { this(UUID.randomUUID()); }
    private ArchivedPileData(UUID world) { this.world = world; }
    public static ArchivedPileData get(ServerLevel level) {
        return LOADED.computeIfAbsent(level, source ->
                source.getDataStorage().computeIfAbsent(ArchivedPileData::load, ArchivedPileData::new, NAME));
    }
    public static ArchivedPileData loaded(ServerLevel level) { return LOADED.get(level); }
    static void clearLoaded() { LOADED.clear(); }
    public UUID world() { return world; }
    public long serial() { return serial; }
    public long sampleEpoch() { return sampleEpoch; }
    public void resample() { sampleEpoch++; changed(); }
    public int cellCount() { return cells.size(); }
    public int regionCount() { return regions.size(); }
    public int recipeCount() { return recipes.size(); }
    public int queuedCount() { return pending.size(); }
    public boolean contains(BlockPos pos) { return cells.containsKey(pos); }
    public boolean hasEmbedded() { return embeddedCount != 0; }
    public UUID regionAt(BlockPos pos) { return cells.get(pos); }
    public EmbeddedBlock embeddedAt(BlockPos pos) {
        UUID id = cells.get(pos);
        Region region = id == null ? null : regions.get(id);
        return region == null ? null : region.embedded.get(pos);
    }
    public Map<BlockPos, EmbeddedBlock> embedded(UUID id) {
        Region region = regions.get(id);
        return region == null ? Map.of() : Map.copyOf(region.embedded);
    }
    public ArchivedPileRecipe recipe(long id) { return recipes.get(id); }
    public PaletteSpec palette(String id) { return palettes.get(id); }
    public long head(UUID id) { return regions.get(id).head; }

    private static final class Region {
        final UUID id;
        final long seed;
        final Set<BlockPos> domain = new HashSet<>(), active = new HashSet<>(), repack = new HashSet<>();
        final Map<BlockPos, EmbeddedBlock> embedded = new HashMap<>();
        final Set<Long> parents = new TreeSet<>();
        final List<ArchivedPileRecipe.Damage> damage = new ArrayList<>();
        long head;
        int delay;
        boolean reset;
        Region(UUID id, long seed) { this.id = id; this.seed = seed; }
    }

    public void add(BlockPos position, UUID identity, long seed) {
        BlockPos pos = position.immutable();
        if (cells.containsKey(pos)) return;
        Set<UUID> adjacent = new TreeSet<>();
        for (Direction direction : Direction.values()) {
            UUID id = cells.get(pos.relative(direction));
            if (id != null) adjacent.add(id);
        }
        Region region;
        if (adjacent.isEmpty()) {
            region = new Region(identity, seed);
            regions.put(identity, region);
        } else {
            region = regions.get(adjacent.iterator().next());
            for (UUID id : adjacent) {
                Region other = regions.get(id);
                if (other == region) continue;
                if (other.head > 0 && !other.reset) region.parents.add(other.head);
                region.parents.addAll(other.parents);
                region.active.addAll(other.active);
                region.repack.addAll(other.repack);
                region.embedded.putAll(other.embedded);
                region.damage.addAll(other.damage);
                for (BlockPos cell : other.domain) {
                    unindex(cell, other.id);
                    cells.put(cell, region.id);
                    index(cell, region.id);
                }
                region.domain.addAll(other.domain);
                regions.remove(other.id);
                pending.remove(other.id);
            }
        }
        region.domain.add(pos);
        cells.put(pos, region.id);
        index(pos, region.id);
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
            if (region.domain.contains(near)) {
                region.active.add(near.immutable());
                region.repack.add(near.immutable());
            }
        }
        region.delay = 8;
        pending.add(region.id);
        changed();
    }

    public void remove(BlockPos pos) {
        UUID id = cells.remove(pos);
        if (id == null) return;
        Region region = regions.get(id);
        unindex(pos, id);
        region.domain.remove(pos);
        if (region.embedded.remove(pos) != null) embeddedCount--;
        region.active.remove(pos);
        region.repack.remove(pos);
        region.damage.add(new ArchivedPileRecipe.Damage(pos, true, "", ArchivedTerrainSnapshot.EMPTY));
        region.delay = 0;
        pending.add(id);
        if (region.domain.isEmpty()) {
            regions.remove(id);
            pending.remove(id);
            collectHistory();
        }
        changed();
    }

    public EmbeddedBlock addEmbedded(BlockPos position) {
        BlockPos pos = position.immutable();
        UUID id = cells.get(pos);
        Region region = id == null ? null : regions.get(id);
        if (region == null || region.embedded.containsKey(pos)) return null;
        EmbeddedBlock embedded = new EmbeddedBlock(pos, nextEmbedded++, 1);
        region.embedded.put(pos, embedded);
        embeddedCount++;
        embeddedChanged(region);
        return embedded;
    }

    public EmbeddedBlock updateEmbedded(BlockPos position) {
        BlockPos pos = position.immutable();
        UUID id = cells.get(pos);
        Region region = id == null ? null : regions.get(id);
        EmbeddedBlock previous = region == null ? null : region.embedded.get(pos);
        if (previous == null) return null;
        EmbeddedBlock updated = new EmbeddedBlock(pos, previous.instance(), previous.revision() + 1);
        region.embedded.put(pos, updated);
        embeddedChanged(region);
        return updated;
    }

    public boolean removeEmbedded(BlockPos position) {
        return removeEmbedded(position, true);
    }

    boolean cancelEmbedding(BlockPos position) {
        return removeEmbedded(position, false);
    }

    private boolean removeEmbedded(BlockPos position, boolean refill) {
        BlockPos pos = position.immutable();
        UUID id = cells.get(pos);
        Region region = id == null ? null : regions.get(id);
        if (region == null || region.embedded.remove(pos) == null) return false;
        embeddedCount--;
        if (refill) {
            // Refill the same neighborhood that embedding can carve, without repacking survivors.
            for (BlockPos near : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
                UUID owner = cells.get(near);
                Region affected = owner == null ? null : regions.get(owner);
                if (affected == null || affected.embedded.containsKey(near)) continue;
                affected.active.add(near.immutable());
                affected.delay = 0;
                pending.add(owner);
            }
        }
        embeddedChanged(region);
        return true;
    }

    private void embeddedChanged(Region region) {
        region.delay = 0;
        pending.add(region.id);
        changed();
    }

    public Set<UUID> nearby(BlockPos pos) {
        Set<UUID> result = new TreeSet<>();
        if (cells.isEmpty()) return result;
        boolean nearby = false;
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            nearby |= chunks.containsKey(ChunkPos.asLong((pos.getX() >> 4) + x, (pos.getZ() >> 4) + z));
        if (!nearby) return result;
        for (BlockPos near : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
            UUID id = cells.get(near);
            if (id != null) result.add(id);
        }
        return result;
    }

    public void damage(Set<UUID> affected, ArchivedPileRecipe.Damage damage) {
        for (UUID id : affected) {
            Region region = regions.get(id);
            if (region == null) continue;
            region.damage.add(damage);
            region.delay = 0;
            pending.add(id);
        }
        if (!affected.isEmpty()) changed();
    }

    public Set<BlockPos> connectedComponent(BlockPos start) {
        if (!cells.containsKey(start)) return Set.of();
        Set<BlockPos> result = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        result.add(start.immutable());
        queue.add(start.immutable());
        while (!queue.isEmpty()) {
            BlockPos pos = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (cells.containsKey(next) && result.add(next)) queue.addLast(next);
            }
        }
        return result;
    }

    public int regenerate(BlockPos pos) {
        Set<BlockPos> component = connectedComponent(pos);
        if (component.isEmpty()) return 0;
        Region region = regions.get(cells.get(pos));
        region.active.addAll(component);
        region.repack.addAll(component);
        if (component.equals(region.domain)) {
            region.reset = true;
            region.parents.clear();
            region.damage.clear();
        }
        region.delay = 8;
        pending.add(region.id);
        changed();
        return component.size();
    }

    public void tick(ServerLevel level, PaletteSpec palette) {
        for (UUID id : pending.stream().sorted().toList()) {
            Region region = regions.get(id);
            if (region.delay-- > 0) continue;
            commit(id, palette, region.active.isEmpty() ? ArchivedTerrainSnapshot.EMPTY
                    : ArchivedTerrainSnapshot.capture(level, region.domain));
        }
    }

    /** Preserve placement batches before recording a later destructive edit. */
    public void flush(ServerLevel level, PaletteSpec palette, Set<UUID> affected) {
        flush(level, palette, affected, Map.of());
    }

    public void flush(ServerLevel level, PaletteSpec palette, Set<UUID> affected,
                      Map<BlockPos, WorldBlock> previous) {
        for (UUID id : affected.stream().sorted().toList()) {
            Region region = regions.get(id);
            if (region != null && !region.active.isEmpty() && pending.contains(id)) commit(id, palette,
                    ArchivedTerrainSnapshot.capture(level, region.domain, previous));
        }
    }

    public long commit(UUID id, PaletteSpec palette, ArchivedTerrainSnapshot terrain) {
        Region region = regions.get(id);
        if (region == null || !pending.remove(id)) return 0;
        Set<Long> parents = new TreeSet<>(region.parents);
        if (region.head > 0 && !region.reset) parents.add(region.head);
        long nodeId = nextRecipe++;
        Set<Long> neighbors = new TreeSet<>();
        for (BlockPos pos : region.active) {
            for (BlockPos near : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
                UUID neighbor = cells.get(near);
                if (neighbor != null && !neighbor.equals(id)) {
                    long head = regions.get(neighbor).head;
                    if (head > 0) neighbors.add(head);
                }
            }
        }
        ArchivedPileRecipe node = new ArchivedPileRecipe(nodeId, region.seed ^ nodeId * 0x9E3779B97F4A7C15L,
                List.copyOf(parents), region.domain, region.active, region.repack, palette.id(), terrain, region.damage,
                List.copyOf(neighbors), region.embedded);
        recipes.put(nodeId, node);
        palettes.putIfAbsent(palette.id(), palette);
        region.head = nodeId;
        region.parents.clear();
        region.active.clear();
        region.repack.clear();
        region.damage.clear();
        boolean collect = region.reset;
        region.reset = false;
        changed();
        if (collect) collectHistory();
        return nodeId;
    }

    public Set<UUID> visible(Set<Long> watched) {
        Set<UUID> visible = new TreeSet<>();
        for (long chunk : watched) {
            int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                var members = chunks.get(ChunkPos.asLong(x + dx, z + dz));
                if (members != null) visible.addAll(members.keySet());
            }
        }
        visible.removeIf(id -> regions.get(id).head == 0);
        return visible;
    }

    public Set<Long> dependencies(Collection<Long> heads) {
        Set<Long> result = new TreeSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>(heads);
        while (!queue.isEmpty()) {
            long id = queue.removeFirst();
            if (result.add(id)) {
                ArchivedPileRecipe node = recipes.get(id);
                if (node == null) throw new IllegalStateException("Missing recipe " + id);
                queue.addAll(node.prerequisites());
            }
        }
        return result;
    }

    private void collectHistory() {
        Set<Long> roots = new HashSet<>();
        regions.values().forEach(region -> {
            if (region.head > 0) roots.add(region.head);
            roots.addAll(region.parents);
        });
        Set<Long> live = dependencies(roots);
        recipes.keySet().retainAll(live);
        Set<String> used = new HashSet<>();
        recipes.values().forEach(node -> used.add(node.palette()));
        palettes.keySet().retainAll(used);
    }
    private void changed() { serial++; setDirty(); }
    private void index(BlockPos pos, UUID id) {
        chunks.computeIfAbsent(ChunkPos.asLong(pos), key -> new HashMap<>()).merge(id, 1, Integer::sum);
    }
    private void unindex(BlockPos pos, UUID id) {
        long key = ChunkPos.asLong(pos);
        var members = chunks.get(key);
        if (members == null) return;
        Integer count = members.get(id);
        if (count != null && count <= 1) members.remove(id);
        else if (count != null) members.put(id, count - 1);
        if (members.isEmpty()) chunks.remove(key);
    }

    @Override public CompoundTag save(CompoundTag tag) {
        tag.putUUID("World", world);
        tag.putLong("Next", nextRecipe);
        tag.putLong("NextEmbedded", nextEmbedded);
        tag.putLong("Serial", serial);
        tag.putLong("Samples", sampleEpoch);
        tag.putInt("Format", FORMAT);
        ListTag rules = new ListTag(), groups = new ListTag();
        ArchivedRecipeArchive.write(tag, recipes.values());
        palettes.values().stream().sorted(Comparator.comparing(PaletteSpec::id)).forEach(rule -> rules.add(rule.save()));
        regions.values().stream().sorted(Comparator.comparing(region -> region.id)).forEach(region -> {
            CompoundTag group = new CompoundTag();
            group.putUUID("Id", region.id);
            group.putLong("Seed", region.seed);
            group.putLong("Head", region.head);
            group.putBoolean("Pending", pending.contains(region.id));
            group.putBoolean("Reset", region.reset);
            RecipeCodec.positions(group, "Domain", region.domain);
            RecipeCodec.positions(group, "Active", region.active);
            RecipeCodec.positions(group, "Repack", region.repack);
            ListTag embedded = new ListTag();
            region.embedded.values().stream().sorted(Comparator.comparing(EmbeddedBlock::pos))
                    .forEach(block -> embedded.add(block.save()));
            group.put("Embedded", embedded);
            group.putLongArray("Parents", region.parents.stream().mapToLong(Long::longValue).toArray());
            ListTag damage = new ListTag();
            region.damage.forEach(edit -> damage.add(edit.save()));
            group.put("Damage", damage);
            groups.add(group);
        });
        tag.put("Palettes", rules);
        tag.put("Regions", groups);
        return tag;
    }

    public static ArchivedPileData load(CompoundTag tag) {
        if (!tag.hasUUID("World") || tag.getInt("Format") != FORMAT)
            throw new IllegalArgumentException("Unsupported barricade data format");
        ArchivedPileData data = new ArchivedPileData(tag.getUUID("World"));
        data.nextRecipe = tag.getLong("Next");
        data.nextEmbedded = tag.getLong("NextEmbedded");
        data.serial = tag.getLong("Serial");
        data.sampleEpoch = tag.getLong("Samples");
        for (ArchivedPileRecipe node : ArchivedRecipeArchive.read(tag)) {
            data.recipes.put(node.id(), node);
        }
        for (Tag value : tag.getList("Palettes", Tag.TAG_COMPOUND)) {
            PaletteSpec palette = PaletteSpec.load((CompoundTag) value);
            data.palettes.put(palette.id(), palette);
        }
        for (Tag value : tag.getList("Regions", Tag.TAG_COMPOUND)) {
            CompoundTag group = (CompoundTag) value;
            Region region = new Region(group.getUUID("Id"), group.getLong("Seed"));
            region.head = group.getLong("Head");
            region.reset = group.getBoolean("Reset");
            region.domain.addAll(RecipeCodec.positions(group, "Domain"));
            region.active.addAll(RecipeCodec.positions(group, "Active"));
            region.repack.addAll(RecipeCodec.positions(group, "Repack"));
            for (Tag embeddedTag : group.getList("Embedded", Tag.TAG_COMPOUND)) {
                EmbeddedBlock embedded = EmbeddedBlock.load((CompoundTag) embeddedTag);
                BlockPos pos = embedded.pos();
                if (!region.domain.contains(pos) || embedded.instance() >= data.nextEmbedded
                        || region.embedded.put(pos, embedded) != null)
                    throw new IllegalArgumentException("Invalid embedded barricade member");
                data.embeddedCount++;
            }
            for (long parent : group.getLongArray("Parents")) region.parents.add(parent);
            for (Tag edit : group.getList("Damage", Tag.TAG_COMPOUND))
                region.damage.add(ArchivedPileRecipe.Damage.load((CompoundTag) edit));
            data.regions.put(region.id, region);
            region.domain.forEach(pos -> {
                if (data.cells.put(pos, region.id) != null) throw new IllegalArgumentException("Overlapping regions");
                data.index(pos, region.id);
            });
            if (group.getBoolean("Pending")) data.pending.add(region.id);
        }
        data.dependencies(data.regions.values().stream().filter(region -> region.head > 0)
                .map(region -> region.head).toList());
        for (ArchivedPileRecipe node : data.recipes.values()) {
            if (!data.palettes.containsKey(node.palette()) || node.id() >= data.nextRecipe)
                throw new IllegalArgumentException("Invalid saved recipe references");
        }
        if (data.nextRecipe <= 0 || data.nextEmbedded <= 0)
            throw new IllegalArgumentException("Invalid barricade sequence");
        return data;
    }
}
