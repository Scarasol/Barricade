package com.scarasol.barricade.window;

import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Frozen source cuts and exclusive ownership of integer length units, not sibling damage. */
public record BoardRemnant(BoardGroup.LayoutKey source, Set<BoardCell> cuts, Map<Long, Long> wood) {
    public static final int MAX_PLANKS = 2048;
    public BoardRemnant {
        cuts = Set.copyOf(cuts);
        wood = Map.copyOf(wood);
        if (source == null || !source.shape().containsAll(cuts) || wood.size() > MAX_PLANKS
                || wood.values().stream().anyMatch(bits -> bits == 0))
            throw new IllegalArgumentException("Invalid window-board ownership");
    }
    CompoundTag save(Map<BoardGroup.LayoutKey, Integer> sources) {
        CompoundTag tag = new CompoundTag();
        if (sources == null) tag.put("Source", source.save());
        else tag.putInt("SourceIndex", Objects.requireNonNull(sources.get(source)));
        tag.putLongArray("Cuts", BoardGroup.pack(cuts));
        long[] entries = new long[wood.size() * 2];
        int index = 0;
        for (var entry : new TreeMap<>(wood).entrySet()) {
            entries[index++] = entry.getKey();
            entries[index++] = entry.getValue();
        }
        tag.putLongArray("Wood", entries);
        return tag;
    }
    static BoardRemnant load(CompoundTag group, List<BoardGroup.LayoutKey> sources) {
        if (!group.contains("Remnant", Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Invalid window-board remnant");
        CompoundTag tag = group.getCompound("Remnant");
        BoardGroup.LayoutKey source;
        if (tag.contains("Source", Tag.TAG_COMPOUND) && !tag.contains("SourceIndex"))
            source = BoardGroup.LayoutKey.load(tag.getCompound("Source"));
        else if (tag.contains("SourceIndex", Tag.TAG_INT) && !tag.contains("Source")) {
            int index = tag.getInt("SourceIndex");
            if (index < 0 || index >= sources.size()) throw new IllegalArgumentException("Invalid window-board source index");
            source = sources.get(index);
        } else throw new IllegalArgumentException("Missing window-board source");
        if (!tag.contains("Wood", Tag.TAG_LONG_ARRAY))
            throw new IllegalArgumentException("Missing window-board ownership");
        long[] entries = tag.getLongArray("Wood");
        if (entries.length % 2 != 0 || entries.length > MAX_PLANKS * 2)
            throw new IllegalArgumentException("Oversized window-board ownership");
        Map<Long, Long> wood = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2)
            if (wood.putIfAbsent(entries[i], entries[i + 1]) != null)
                throw new IllegalArgumentException("Duplicate window-board plank ownership");
        return new BoardRemnant(source, BoardGroup.unpack(tag, "Cuts", BoardGroup.MAX_CELLS), wood);
    }
}
