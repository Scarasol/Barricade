package com.scarasol.barricade.window;

import java.util.*;
import java.util.stream.Collectors;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

public final class BoardGroup {
    public static final int ALGORITHM = 1;
    public static final int MAX_CELLS = 256;
    public static final int MAX_SPAN = 16;
    private final UUID id;
    private final long revision, seed;
    private final BoardPlane plane;
    private final Set<BoardCell> shape, damage, supports;
    private final BoardRemnant remnant;
    private final SealMaterial material;
    private final LayoutKey layoutKey;
    public BoardGroup(UUID id, long revision, long seed, BoardPlane plane, Set<BoardCell> shape,
                      Set<BoardCell> damage, Set<BoardCell> supports) {
        this(id, revision, seed, plane, shape, damage, supports, null, SealMaterial.WOOD);
    }
    public BoardGroup(UUID id, long revision, long seed, BoardPlane plane, Set<BoardCell> shape,
                      Set<BoardCell> damage, Set<BoardCell> supports, BoardRemnant remnant) {
        this(id, revision, seed, plane, shape, damage, supports, remnant, SealMaterial.WOOD);
    }
    public BoardGroup(UUID id, long revision, long seed, BoardPlane plane, Set<BoardCell> shape,
                      Set<BoardCell> damage, Set<BoardCell> supports, BoardRemnant remnant, SealMaterial material) {
        this.id = id;
        this.revision = revision;
        this.seed = seed;
        this.plane = plane;
        this.shape = Set.copyOf(shape);
        this.damage = Set.copyOf(damage);
        this.supports = Set.copyOf(supports);
        this.remnant = remnant;
        this.material = material;
        if (id == null || plane == null || material == null || revision < 1 || !this.shape.containsAll(this.damage))
            throw new IllegalArgumentException("Invalid window-board group");
        if (remnant == null) layoutKey = new LayoutKey(seed, plane, this.shape, this.supports, material);
        else {
            validateShape(this.shape, this.supports);
            layoutKey = remnant.source();
            if (seed != layoutKey.seed() || !plane.equals(layoutKey.plane()) || material != layoutKey.material()
                    || !layoutKey.shape().containsAll(this.shape) || !Collections.disjoint(this.shape, remnant.cuts()))
                throw new IllegalArgumentException("Invalid window-board remnant members");
        }
    }
    public UUID id() { return id; }
    public long revision() { return revision; }
    public long seed() { return seed; }
    public BoardPlane plane() { return plane; }
    public Set<BoardCell> shape() { return shape; }
    public Set<BoardCell> damage() { return damage; }
    public Set<BoardCell> supports() { return supports; }
    public BoardRemnant remnant() { return remnant; }
    public SealMaterial material() { return material; }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof BoardGroup group && revision == group.revision && seed == group.seed
                && id.equals(group.id) && plane.equals(group.plane) && shape.equals(group.shape)
                && damage.equals(group.damage) && supports.equals(group.supports)
                && Objects.equals(remnant, group.remnant) && material == group.material;
    }
    @Override public int hashCode() {
        int hash = id.hashCode();
        hash = 31 * hash + Long.hashCode(revision);
        hash = 31 * hash + Long.hashCode(seed);
        hash = 31 * hash + plane.hashCode();
        hash = 31 * hash + shape.hashCode();
        hash = 31 * hash + damage.hashCode();
        hash = 31 * hash + supports.hashCode();
        hash = 31 * hash + Objects.hashCode(remnant);
        return 31 * hash + material.hashCode();
    }
    @Override public String toString() {
        return "BoardGroup[id=" + id + ", revision=" + revision + ", seed=" + seed + ", plane=" + plane
                + ", shape=" + shape + ", damage=" + damage + ", supports=" + supports
                + ", remnant=" + remnant + ", material=" + material + "]";
    }
    private static void validateShape(Set<BoardCell> shape, Set<BoardCell> supports) {
        if (!fits(shape) || supports.size() > MAX_CELLS * 8)
            throw new IllegalArgumentException("Invalid window-board shape");
        for (BoardCell cell : supports) {
            if (shape.contains(cell)) throw new IllegalArgumentException("Invalid window-board shape");
            boolean adjacent = false;
            neighbors: for (int u = -1; u <= 1; u++) for (int v = -1; v <= 1; v++) {
                if (shape.contains(cell.offset(u, v))) {
                    adjacent = true;
                    break neighbors;
                }
            }
            if (!adjacent) throw new IllegalArgumentException("Distant window-board support");
        }
    }
    public static boolean fits(Set<BoardCell> cells) {
        if (cells.isEmpty() || cells.size() > MAX_CELLS) return false;
        long minU = Integer.MAX_VALUE, minV = Integer.MAX_VALUE;
        long maxU = Integer.MIN_VALUE, maxV = Integer.MIN_VALUE;
        for (BoardCell cell : cells) {
            minU = Math.min(minU, cell.u());
            maxU = Math.max(maxU, cell.u());
            minV = Math.min(minV, cell.v());
            maxV = Math.max(maxV, cell.v());
        }
        return maxU - minU < MAX_SPAN && maxV - minV < MAX_SPAN;
    }
    public record LayoutKey(long seed, BoardPlane plane, Set<BoardCell> shape, Set<BoardCell> supports,
                            SealMaterial material) {
        public LayoutKey(long seed, BoardPlane plane, Set<BoardCell> shape, Set<BoardCell> supports) {
            this(seed, plane, shape, supports, SealMaterial.WOOD);
        }
        public LayoutKey {
            shape = Set.copyOf(shape);
            supports = Set.copyOf(supports);
            if (plane == null || material == null) throw new IllegalArgumentException("Missing window-seal plane or material");
            validateShape(shape, supports);
        }
        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Algorithm", ALGORITHM);
            tag.putString("Material", material.name());
            tag.putLong("Seed", seed);
            tag.putString("Facing", plane.facing().getName());
            tag.putInt("Plane", plane.coordinate());
            tag.putLongArray("Shape", pack(shape));
            tag.putLongArray("Supports", pack(supports));
            return tag;
        }
        static LayoutKey load(CompoundTag tag) {
            Direction facing = Direction.byName(tag.getString("Facing"));
            if (tag.getInt("Algorithm") != ALGORITHM || facing == null
                    || !tag.contains("Shape", Tag.TAG_LONG_ARRAY) || !tag.contains("Supports", Tag.TAG_LONG_ARRAY))
                throw new IllegalArgumentException("Invalid window-board source");
            return new LayoutKey(tag.getLong("Seed"), new BoardPlane(facing, tag.getInt("Plane")),
                    unpack(tag, "Shape", MAX_CELLS), unpack(tag, "Supports", MAX_CELLS * 8),
                    SealMaterial.valueOf(tag.getString("Material")));
        }
    }
    public LayoutKey layoutKey() { return layoutKey; }
    public boolean alive(BoardCell cell) { return shape.contains(cell) && !damage.contains(cell); }
    public Set<BoardCell> survivors() {
        Set<BoardCell> result = new HashSet<>(shape);
        result.removeAll(damage);
        return Set.copyOf(result);
    }
    public Set<BoardCell> layoutDamage() {
        if (remnant == null) return damage;
        Set<BoardCell> result = new HashSet<>(remnant.cuts());
        result.addAll(damage);
        return Set.copyOf(result);
    }
    public CompoundTag save() {
        return save(null);
    }
    CompoundTag save(Map<LayoutKey, Integer> sources) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Algorithm", ALGORITHM);
        tag.putString("Material", material.name());
        tag.putUUID("Id", id);
        tag.putLong("Revision", revision);
        tag.putLong("Seed", seed);
        tag.putString("Facing", plane.facing().getName());
        tag.putInt("Plane", plane.coordinate());
        tag.putLongArray("Shape", pack(shape));
        tag.putLongArray("Damage", pack(damage));
        tag.putLongArray("Supports", pack(supports));
        if (remnant != null) tag.put("Remnant", remnant.save(sources));
        return tag;
    }
    public static BoardGroup load(CompoundTag tag) {
        return load(tag, List.of());
    }
    static BoardGroup load(CompoundTag tag, List<LayoutKey> sources) {
        Direction facing = Direction.byName(tag.getString("Facing"));
        if (tag.getInt("Algorithm") != ALGORITHM || facing == null
                || !tag.hasUUID("Id") || !tag.contains("Shape", Tag.TAG_LONG_ARRAY)
                || !tag.contains("Damage", Tag.TAG_LONG_ARRAY) || !tag.contains("Supports", Tag.TAG_LONG_ARRAY))
            throw new IllegalArgumentException("Unsupported or incomplete window-board group");
        return new BoardGroup(tag.getUUID("Id"), tag.getLong("Revision"), tag.getLong("Seed"),
                new BoardPlane(facing, tag.getInt("Plane")), unpack(tag, "Shape", MAX_CELLS),
                unpack(tag, "Damage", MAX_CELLS), unpack(tag, "Supports", MAX_CELLS * 8),
                tag.contains("Remnant") ? BoardRemnant.load(tag, sources) : null,
                SealMaterial.valueOf(tag.getString("Material")));
    }
    static long[] pack(Set<BoardCell> cells) {
        return cells.stream().sorted().mapToLong(BoardCell::packed).toArray();
    }
    static Set<BoardCell> unpack(CompoundTag tag, String key, int maximum) {
        if (!tag.contains(key, Tag.TAG_LONG_ARRAY)) throw new IllegalArgumentException("Missing window-board " + key);
        long[] values = tag.getLongArray(key);
        if (values.length > maximum) throw new IllegalArgumentException("Oversized window-board " + key);
        Set<BoardCell> cells = Arrays.stream(values).mapToObj(BoardCell::unpack).collect(Collectors.toSet());
        if (cells.size() != values.length) throw new IllegalArgumentException("Duplicate window-board cell");
        return cells;
    }
}
