package com.scarasol.barricade.window;

import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Optional NBT metadata stages topology changes without changing the packet envelope. */
public final class WindowBoardsUpdates {
    private static final int REMOVALS_PER_PART = 1024;
    private static final int MAX_PARTS = 65536;
    public record Changes(List<BoardGroup> groups, Set<UUID> removed) {
        public Changes {
            groups = List.copyOf(groups);
            removed = Set.copyOf(removed);
            Set<UUID> ids = new HashSet<>();
            for (BoardGroup group : groups) if (!ids.add(group.id()) || removed.contains(group.id()))
                throw new IllegalArgumentException("Conflicting window-board changes");
        }
    }
    private record Part(BoardGroup group, Set<UUID> removed) { }
    private UUID pending, completed;
    private int count;
    private final Map<Integer, Part> parts = new HashMap<>();
    public static List<WindowBoardsNetwork.Packet> synchronize(ResourceLocation dimension, UUID world,
                                                               Map<UUID, BoardGroup> previous, Map<UUID, BoardGroup> current) {
        Set<UUID> changed = new TreeSet<>(previous.keySet());
        changed.removeAll(current.keySet());
        current.forEach((id, group) -> {
            BoardGroup old = previous.get(id);
            if (old == null || old.revision() != group.revision()) changed.add(id);
        });
        Map<UUID, Set<UUID>> related = new HashMap<>();
        Map<BoardGroup.LayoutKey, UUID> sources = new HashMap<>();
        Map<net.minecraft.core.BlockPos, UUID> cells = new HashMap<>();
        for (UUID id : changed) related.put(id, new HashSet<>());
        for (UUID id : changed) {
            List<BoardGroup> versions = new ArrayList<>();
            if (previous.containsKey(id)) versions.add(previous.get(id));
            if (current.containsKey(id)) versions.add(current.get(id));
            for (BoardGroup group : versions) {
                relate(related, id, sources.putIfAbsent(group.layoutKey(), id));
                for (BoardCell cell : group.survivors())
                    relate(related, id, cells.putIfAbsent(group.plane().block(cell), id));
            }
        }
        List<WindowBoardsNetwork.Packet> result = new ArrayList<>();
        while (!changed.isEmpty()) {
            UUID first = changed.iterator().next();
            changed.remove(first);
            Set<UUID> transaction = new TreeSet<>(Set.of(first));
            Deque<UUID> pending = new ArrayDeque<>(List.of(first));
            while (!pending.isEmpty()) for (UUID neighbor : related.get(pending.removeFirst()))
                if (changed.remove(neighbor)) {
                    transaction.add(neighbor);
                    pending.addLast(neighbor);
                }
            List<BoardGroup> updates = new ArrayList<>();
            Set<UUID> removed = new HashSet<>();
            for (UUID id : transaction) {
                if (current.containsKey(id)) updates.add(current.get(id));
                else removed.add(id);
            }
            result.addAll(packets(dimension, world, updates, removed));
        }
        return List.copyOf(result);
    }
    private static void relate(Map<UUID, Set<UUID>> related, UUID id, UUID other) {
        if (other == null || id.equals(other)) return;
        related.get(id).add(other);
        related.get(other).add(id);
    }
    public static List<WindowBoardsNetwork.Packet> packets(ResourceLocation dimension, UUID world,
                                                          List<BoardGroup> groups, Set<UUID> removed) {
        new Changes(groups, removed);
        List<WindowBoardsNetwork.Packet> packets = new ArrayList<>();
        if (!groups.isEmpty()) {
            List<UUID> deletions = removed.stream().sorted().toList();
            int pages = (deletions.size() + REMOVALS_PER_PART - 1) / REMOVALS_PER_PART;
            int count = groups.size() + pages;
            if (count > MAX_PARTS) throw new IllegalArgumentException("Oversized window-board update");
            UUID batch = UUID.randomUUID();
            for (int i = 0; i < count; i++) {
                BoardGroup group = groups.get(i < groups.size() ? i : 0);
                CompoundTag metadata = group.save();
                if (count > 1) {
                    CompoundTag update = new CompoundTag();
                    update.putUUID("Id", batch);
                    update.putInt("Part", i);
                    update.putInt("Count", count);
                    if (i >= groups.size()) {
                        int first = (i - groups.size()) * REMOVALS_PER_PART;
                        List<UUID> page = deletions.subList(first, Math.min(first + REMOVALS_PER_PART, deletions.size()));
                        long[] packed = new long[page.size() * 2];
                        for (int j = 0; j < page.size(); j++) {
                            packed[j * 2] = page.get(j).getMostSignificantBits();
                            packed[j * 2 + 1] = page.get(j).getLeastSignificantBits();
                        }
                        update.putLongArray("Removed", packed);
                    }
                    metadata.put("Update", update);
                }
                packets.add(new WindowBoardsNetwork.Packet(dimension, world, false, group.id(), metadata));
            }
        }
        if (groups.isEmpty()) for (UUID id : new TreeSet<>(removed))
            packets.add(new WindowBoardsNetwork.Packet(dimension, world, false, id, new CompoundTag()));
        return List.copyOf(packets);
    }
    public Changes accept(WindowBoardsNetwork.Packet packet) {
        CompoundTag metadata = packet.group();
        if (metadata.isEmpty()) return new Changes(List.of(), Set.of(packet.id()));
        BoardGroup group = BoardGroup.load(metadata);
        if (!packet.id().equals(group.id())) throw new IllegalArgumentException("Window-board packet identity mismatch");
        if (!metadata.contains("Update")) return new Changes(List.of(group), Set.of());
        if (!metadata.contains("Update", Tag.TAG_COMPOUND))
            throw new IllegalArgumentException("Invalid window-board update");
        CompoundTag update = metadata.getCompound("Update");
        if (!update.hasUUID("Id") || !update.contains("Count", Tag.TAG_INT) || !update.contains("Part", Tag.TAG_INT))
            throw new IllegalArgumentException("Incomplete window-board update");
        UUID id = update.getUUID("Id");
        int size = update.getInt("Count"), index = update.getInt("Part");
        if (size < 1 || size > MAX_PARTS || index < 0 || index >= size)
            throw new IllegalArgumentException("Invalid window-board update counters");
        if (id.equals(completed)) return null;
        if (!id.equals(pending)) {
            parts.clear();
            pending = id;
            count = size;
        } else if (count != size) throw new IllegalArgumentException("Inconsistent window-board update size");
        Set<UUID> removed = new HashSet<>();
        if (update.contains("Removed")) {
            if (!update.contains("Removed", Tag.TAG_LONG_ARRAY))
                throw new IllegalArgumentException("Invalid window-board deletions");
            long[] values = update.getLongArray("Removed");
            if (values.length % 2 != 0 || values.length > REMOVALS_PER_PART * 2)
                throw new IllegalArgumentException("Oversized window-board deletions");
            for (int i = 0; i < values.length; i += 2)
                if (!removed.add(new UUID(values[i], values[i + 1])))
                    throw new IllegalArgumentException("Duplicate window-board deletion");
        }
        Part part = new Part(group, Set.copyOf(removed));
        Part previous = parts.putIfAbsent(index, part);
        if (previous != null && !previous.equals(part)) throw new IllegalArgumentException("Conflicting window-board update part");
        if (parts.size() < count) return null;
        Map<UUID, BoardGroup> changes = new LinkedHashMap<>();
        Set<UUID> deleted = new HashSet<>();
        for (int i = 0; i < count; i++) {
            Part current = parts.get(i);
            BoardGroup old = changes.putIfAbsent(current.group.id(), current.group);
            if (old != null && !old.equals(current.group))
                throw new IllegalArgumentException("Conflicting window-board update revisions");
            deleted.addAll(current.removed);
        }
        Changes result = new Changes(List.copyOf(changes.values()), deleted);
        completed = pending;
        pending = null;
        parts.clear();
        return result;
    }
    public void clear() {
        pending = completed = null;
        count = 0;
        parts.clear();
    }
}
