package com.scarasol.barricade.pile;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;

/** Sparse current damage, one 4096-bit section per occupied section coordinate. */
public final class SpatialMask {
    private final Map<GridPos, BitSet> sections = new TreeMap<>();
    public SpatialMask() { }
    public SpatialMask(SpatialMask other) { other.sections.forEach((key, bits) -> sections.put(key, (BitSet) bits.clone())); }
    private static GridPos section(BlockPos p) {
        return new GridPos(Math.floorDiv(p.getX(), 16), Math.floorDiv(p.getY(), 16), Math.floorDiv(p.getZ(), 16));
    }
    private static int bit(BlockPos p) { return (p.getX() & 15) << 8 | (p.getY() & 15) << 4 | p.getZ() & 15; }
    public boolean contains(BlockPos p) {
        BitSet bits = sections.get(section(p));
        return bits != null && bits.get(bit(p));
    }
    public boolean set(BlockPos p) {
        BitSet bits = sections.computeIfAbsent(section(p), ignored -> new BitSet(4096));
        boolean changed = !bits.get(bit(p));
        bits.set(bit(p)); return changed;
    }
    public boolean clear(BlockPos p) {
        GridPos key = section(p); BitSet bits = sections.get(key);
        if (bits == null || !bits.get(bit(p))) return false;
        bits.clear(bit(p));
        if (bits.isEmpty()) sections.remove(key);
        return true;
    }
    public void union(SpatialMask other) {
        other.sections.forEach((key, bits) -> sections.computeIfAbsent(key, ignored -> new BitSet(4096)).or(bits));
    }
    public boolean isEmpty() { return sections.isEmpty(); }
    public int size() { return sections.values().stream().mapToInt(BitSet::cardinality).sum(); }
    public Set<BlockPos> cells() {
        Set<BlockPos> result = new HashSet<>();
        sections.forEach((section, bits) -> {
            for (int bit = bits.nextSetBit(0); bit >= 0; bit = bits.nextSetBit(bit + 1))
                result.add(new BlockPos(section.x() * 16 + (bit >> 8), section.y() * 16 + ((bit >> 4) & 15),
                        section.z() * 16 + (bit & 15)));
        });
        return Set.copyOf(result);
    }
    public SpatialMask inChunks(Set<Long> chunks) {
        SpatialMask result = new SpatialMask();
        sections.forEach((key, bits) -> {
            if (chunks.contains(net.minecraft.world.level.ChunkPos.asLong(key.x(), key.z())))
                result.sections.put(key, (BitSet) bits.clone());
        });
        return result;
    }
    public ListTag save() {
        ListTag result = new ListTag();
        sections.forEach((key, bits) -> {
            CompoundTag tag = new CompoundTag();
            tag.putInt("X", key.x()); tag.putInt("Y", key.y()); tag.putInt("Z", key.z());
            tag.putLongArray("Bits", bits.toLongArray()); result.add(tag);
        });
        return result;
    }
    public static SpatialMask load(ListTag tags) {
        SpatialMask result = new SpatialMask();
        for (Tag value : tags) {
            CompoundTag tag = (CompoundTag) value;
            GridPos key = new GridPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
            long[] words = tag.getLongArray("Bits");
            if (words.length == 0 || words.length > 64) throw new IllegalArgumentException("Invalid damage section");
            BitSet bits = BitSet.valueOf(words);
            if (bits.isEmpty() || result.sections.put(key, bits) != null)
                throw new IllegalArgumentException("Duplicate/empty damage section");
        }
        return result;
    }
    @Override public boolean equals(Object object) { return object instanceof SpatialMask mask && sections.equals(mask.sections); }
    @Override public int hashCode() { return sections.hashCode(); }
}
