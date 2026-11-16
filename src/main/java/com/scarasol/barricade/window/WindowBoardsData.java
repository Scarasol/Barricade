package com.scarasol.barricade.window;

import com.scarasol.barricade.Barricade;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

public final class WindowBoardsData extends SavedData {
    public static final String NAME = "barricade_window_boards_v1";
    private static final int FORMAT = 1;
    private static final Map<ServerLevel, WindowBoardsData> LOADED = new WeakHashMap<>();
    private final UUID world;
    private final Map<UUID, BoardGroup> groups = new HashMap<>();
    private final Map<BlockPos, UUID> active = new HashMap<>();
    private final Map<Long, Map<UUID, Integer>> chunks = new HashMap<>();
    private final Map<BoardGroup.LayoutKey, Integer> sourceUsers = new HashMap<>();
    private final Map<BoardGroup.LayoutKey, BoardLayout.Layout> layouts = new HashMap<>();
    private long serial;
    public record Placement(UUID id, boolean geometryChanged) { }
    public record Observation(Direction facing, SealMaterial material) {
        public Observation { Objects.requireNonNull(facing); Objects.requireNonNull(material); }
    }
    public WindowBoardsData() { this(UUID.randomUUID()); }
    private WindowBoardsData(UUID world) { this.world = world; }
    public static WindowBoardsData get(ServerLevel level) {
        return LOADED.computeIfAbsent(level, key -> {
            Path file = DimensionType.getStorageFolder(key.dimension(),
                    key.getServer().getWorldPath(LevelResource.ROOT)).resolve("data").resolve(NAME + ".dat");
            return open(key.getDataStorage(), file);
        });
    }
    public static WindowBoardsData open(DimensionDataStorage storage, Path file) {
        WindowBoardsData result;
        if (Files.notExists(file)) result = new WindowBoardsData();
        else try {
            result = load(storage.readTagFromDisk(NAME,
                    SharedConstants.getCurrentVersion().getDataVersion().getVersion()).getCompound("data"));
        } catch (IOException | RuntimeException exception) {
            Barricade.LOGGER.warn("Resetting unreadable window-seal region data: {}: {}", file, exception.toString());
            result = new WindowBoardsData();
            result.setDirty();
        }
        storage.set(NAME, result);
        return result;
    }
    static void clearLoaded() { LOADED.clear(); }
    public UUID world() { return world; }
    public long serial() { return serial; }
    public BoardGroup group(UUID id) { return groups.get(id); }
    public UUID activeAt(BlockPos pos) { return active.get(pos); }
    public Collection<BoardGroup> groups() { return List.copyOf(groups.values()); }
    public Set<BlockPos> activeInChunk(long chunk) {
        Set<BlockPos> result = new HashSet<>();
        for (UUID id : chunks.getOrDefault(chunk, Map.of()).keySet()) {
            BoardGroup group = groups.get(id);
            for (BoardCell cell : group.shape()) {
                BlockPos pos = group.plane().block(cell);
                if (group.alive(cell) && ChunkPos.asLong(pos) == chunk) result.add(pos);
            }
        }
        return result;
    }
    public Set<UUID> visible(Set<Long> watched) {
        Set<UUID> result = new TreeSet<>();
        for (long chunk : watched) {
            int x = ChunkPos.getX(chunk), z = ChunkPos.getZ(chunk);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                result.addAll(chunks.getOrDefault(ChunkPos.asLong(x + dx, z + dz), Map.of()).keySet());
        }
        return result;
    }
    public Placement place(BlockPos pos, Direction facing, UUID identity, long seed) {
        return place(pos, facing, identity, seed, SealMaterial.WOOD);
    }
    public Placement place(BlockPos pos, Direction facing, UUID identity, long seed, SealMaterial material) {
        Objects.requireNonNull(material);
        BoardPlane plane = BoardPlane.at(pos, facing);
        BoardCell cell = plane.cell(pos);
        UUID previous = active.get(pos);
        if (previous != null) {
            BoardGroup old = groups.get(previous);
            if (old.plane().equals(plane) && old.material() == material) return new Placement(old.id(), false);
            remove(pos);
        }
        List<BoardGroup> candidates = adjacent(plane, cell, material).stream().map(groups::get)
                .sorted(Comparator.<BoardGroup>comparingInt(group -> -group.survivors().size())
                        .thenComparing(BoardGroup::id)).toList();
        Set<BoardCell> shape = new HashSet<>(Set.of(cell)), supports = new HashSet<>();
        for (BoardGroup candidate : candidates) shape.addAll(candidate.survivors());
        List<BoardGroup> merged = candidates;
        if (!BoardGroup.fits(shape)) {
            shape = new HashSet<>(Set.of(cell));
            merged = List.of();
        }
        BoardGroup selected = merged.isEmpty() ? null : merged.get(0);
        UUID id = selected == null ? identity : selected.id();
        if (selected == null && groups.containsKey(id)) throw new IllegalArgumentException("Duplicate window-board identity");
        for (BoardGroup old : merged) supports.addAll(old.supports());
        supports = nearbySupports(supports, shape);
        BoardGroup maintained = new BoardGroup(id, selected == null ? 1 : selected.revision() + 1,
                selected == null ? seed : selected.seed(), plane, shape, Set.of(), supports, null, material);
        for (BoardGroup old : merged) eraseIndex(groups.remove(old.id()));
        put(maintained);
        return new Placement(id, true);
    }
    private Set<UUID> adjacent(BoardPlane plane, BoardCell cell, SealMaterial material) {
        Set<UUID> result = new HashSet<>();
        for (BoardCell neighbor : neighbors(cell)) {
            UUID id = active.get(plane.block(neighbor));
            if (id != null && groups.get(id).plane().equals(plane) && groups.get(id).material() == material) result.add(id);
        }
        return result;
    }
    private static List<BoardCell> neighbors(BoardCell cell) {
        return List.of(cell.offset(-1, 0), cell.offset(1, 0), cell.offset(0, -1), cell.offset(0, 1));
    }
    static List<Set<BoardCell>> components(Set<BoardCell> cells) {
        Set<BoardCell> remaining = new TreeSet<>(cells);
        List<Set<BoardCell>> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            BoardCell first = remaining.iterator().next();
            remaining.remove(first);
            Set<BoardCell> component = new HashSet<>(Set.of(first));
            Deque<BoardCell> pending = new ArrayDeque<>(List.of(first));
            while (!pending.isEmpty()) for (BoardCell neighbor : neighbors(pending.removeFirst()))
                if (remaining.remove(neighbor)) {
                    component.add(neighbor);
                    pending.addLast(neighbor);
                }
            result.add(Set.copyOf(component));
        }
        result.sort(Comparator.<Set<BoardCell>>comparingInt(cellsInGroup -> -cellsInGroup.size())
                .thenComparing(cellsInGroup -> Collections.min(cellsInGroup)));
        return List.copyOf(result);
    }
    private static Set<BoardCell> nearbySupports(Set<BoardCell> supports, Set<BoardCell> shape) {
        Set<BoardCell> result = new HashSet<>();
        for (BoardCell cell : shape) for (int u = -1; u <= 1; u++) for (int v = -1; v <= 1; v++) {
            BoardCell neighbor = cell.offset(u, v);
            if (supports.contains(neighbor) && !shape.contains(neighbor)) result.add(neighbor);
        }
        return Set.copyOf(result);
    }
    private void split(BoardGroup damaged, List<Set<BoardCell>> components) {
        BoardGroup.LayoutKey key = damaged.layoutKey();
        BoardLayout.Layout layout = layouts.computeIfAbsent(key, BoardLayout::generate);
        List<BoardRemnant> remnants = BoardLayout.partition(damaged, layout, components);
        List<BoardGroup> children = new ArrayList<>();
        Set<UUID> allocated = new HashSet<>(Set.of(damaged.id()));
        for (int i = 0; i < components.size(); i++) {
            Set<BoardCell> shape = components.get(i);
            UUID id = damaged.id();
            if (i != 0) {
                int attempt = 0;
                do {
                    byte[] bytes = ByteBuffer.allocate(40).putLong(damaged.id().getMostSignificantBits())
                            .putLong(damaged.id().getLeastSignificantBits()).putLong(damaged.revision())
                            .putLong(Collections.min(shape).packed()).putLong(attempt++).array();
                    id = UUID.nameUUIDFromBytes(bytes);
                } while (groups.containsKey(id) || !allocated.add(id));
            }
            children.add(new BoardGroup(id, i == 0 ? damaged.revision() : 1, damaged.seed(), damaged.plane(),
                    shape, Set.of(), nearbySupports(damaged.supports(), shape), remnants.get(i), damaged.material()));
        }
        eraseIndex(groups.remove(damaged.id()));
        for (BoardGroup child : children) put(child);
        layouts.put(key, layout);
    }
    /** Loaded observations only; null denotes a non-board block. */
    void reconcileRemovals(Map<BlockPos, Direction> observed) {
        Map<BlockPos, Observation> seals = new LinkedHashMap<>();
        observed.forEach((pos, facing) -> seals.put(pos, facing == null ? null : new Observation(facing, SealMaterial.WOOD)));
        reconcileSeals(seals);
    }
    void reconcileSeals(Map<BlockPos, Observation> observed) {
        observed.forEach((pos, seal) -> {
            UUID id = active.get(pos);
            if (id != null && (seal == null || groups.get(id).plane().facing() != seal.facing()
                    || groups.get(id).material() != seal.material())) remove(pos);
        });
    }
    public void supports(UUID id, Set<BoardCell> supports) {
        BoardGroup old = groups.get(id);
        if (old == null || old.supports().equals(supports)) return;
        put(new BoardGroup(id, old.revision() + 1, old.seed(), old.plane(), old.shape(), old.damage(), supports,
                old.remnant(), old.material()));
    }
    public void remove(BlockPos pos) {
        UUID id = active.get(pos);
        if (id == null) return;
        BoardGroup old = groups.get(id);
        Set<BoardCell> mask = new HashSet<>(old.damage());
        mask.add(old.plane().cell(pos));
        if (mask.size() == old.shape().size()) delete(old);
        else {
            BoardGroup damaged = new BoardGroup(id, old.revision() + 1, old.seed(), old.plane(),
                    old.shape(), mask, old.supports(), old.remnant(), old.material());
            List<Set<BoardCell>> components = components(damaged.survivors());
            if (components.size() > 1) split(damaged, components);
            else put(damaged);
        }
    }
    private void delete(BoardGroup group) {
        eraseIndex(groups.remove(group.id()));
        serial++;
        setDirty();
    }
    private void put(BoardGroup group) {
        BoardGroup old = groups.put(group.id(), group);
        BoardLayout.Layout retained = layouts.isEmpty() ? null : layouts.get(group.layoutKey());
        if (old != null) eraseIndex(old);
        index(group);
        if (retained != null) layouts.put(retained.key(), retained);
        serial++;
        setDirty();
    }
    private void index(BoardGroup group) {
        sourceUsers.merge(group.layoutKey(), 1, Integer::sum);
        for (BoardCell cell : group.shape()) {
            if (!group.alive(cell)) continue;
            BlockPos pos = group.plane().block(cell);
            if (active.putIfAbsent(pos, group.id()) != null)
                throw new IllegalArgumentException("Overlapping active window-board cells");
            chunks.computeIfAbsent(ChunkPos.asLong(pos), ignored -> new HashMap<>()).merge(group.id(), 1, Integer::sum);
        }
    }
    private void eraseIndex(BoardGroup group) {
        BoardGroup.LayoutKey source = group.layoutKey();
        sourceUsers.computeIfPresent(source, (key, count) -> count == 1 ? null : count - 1);
        if (!sourceUsers.containsKey(source)) layouts.remove(source);
        for (BoardCell cell : group.shape()) {
            if (!group.alive(cell)) continue;
            BlockPos pos = group.plane().block(cell);
            active.remove(pos, group.id());
            long key = ChunkPos.asLong(pos);
            Map<UUID, Integer> bucket = chunks.get(key);
            if (bucket != null) {
                bucket.computeIfPresent(group.id(), (id, count) -> count == 1 ? null : count - 1);
                if (bucket.isEmpty()) chunks.remove(key);
            }
        }
    }
    @Override public CompoundTag save(CompoundTag tag) {
        tag.putInt("Format", FORMAT);
        tag.putUUID("World", world);
        tag.putLong("Serial", serial);
        List<BoardGroup> ordered = groups.values().stream().sorted(Comparator.comparing(BoardGroup::id)).toList();
        Map<BoardGroup.LayoutKey, Integer> sources = new LinkedHashMap<>();
        for (BoardGroup group : ordered) if (group.remnant() != null)
            sources.computeIfAbsent(group.layoutKey(), ignored -> sources.size());
        if (!sources.isEmpty()) {
            ListTag shared = new ListTag();
            sources.keySet().forEach(source -> shared.add(source.save()));
            tag.put("Sources", shared);
        } else tag.remove("Sources");
        ListTag list = new ListTag();
        ordered.forEach(group -> list.add(group.save(sources)));
        tag.put("Groups", list);
        return tag;
    }
    public static WindowBoardsData load(CompoundTag tag) {
        if (tag.getInt("Format") != FORMAT || !tag.hasUUID("World") || !tag.contains("Groups", Tag.TAG_LIST)
                || !tag.contains("Serial", Tag.TAG_LONG) || tag.getLong("Serial") < 0)
            throw new IllegalArgumentException("Unsupported or incomplete window-board data");
        ListTag list = (ListTag) tag.get("Groups");
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND)
            throw new IllegalArgumentException("Invalid window-board group list");
        WindowBoardsData result = new WindowBoardsData(tag.getUUID("World"));
        List<BoardGroup.LayoutKey> sources = new ArrayList<>();
        if (tag.contains("Sources")) {
            if (!tag.contains("Sources", Tag.TAG_LIST))
                throw new IllegalArgumentException("Invalid window-board sources");
            ListTag shared = (ListTag) tag.get("Sources");
            if (!shared.isEmpty() && shared.getElementType() != Tag.TAG_COMPOUND)
                throw new IllegalArgumentException("Invalid window-board source list");
            for (Tag source : shared) sources.add(BoardGroup.LayoutKey.load((CompoundTag) source));
        }
        for (Tag entry : list) {
            BoardGroup group = BoardGroup.load((CompoundTag) entry, sources);
            if (result.groups.putIfAbsent(group.id(), group) != null)
                throw new IllegalArgumentException("Duplicate window-board group");
            if (group.remnant() != null) BoardLayout.validateOwnership(group,
                    result.layouts.computeIfAbsent(group.layoutKey(), BoardLayout::generate));
            result.index(group);
        }
        result.serial = tag.getLong("Serial");
        return result;
    }
}
