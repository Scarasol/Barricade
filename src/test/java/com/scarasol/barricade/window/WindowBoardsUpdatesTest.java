package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class WindowBoardsUpdatesTest {
    private static final UUID WORLD = new UUID(1, 2);
    private static final ResourceLocation DIMENSION = new ResourceLocation("minecraft", "overworld");
    private static BoardGroup group(int index) {
        return new BoardGroup(new UUID(42, index), 3, 42, new BoardPlane(Direction.NORTH, 0),
                Set.of(new BoardCell(index, 0)), Set.of(), Set.of());
    }
    @Test void multiTickChangesRemainInvisibleUntilEveryPartArrivesAndThenCommitOnce() {
        List<BoardGroup> groups = new ArrayList<>();
        for (int i = 0; i < 12; i++) groups.add(group(i));
        Set<UUID> removed = Set.of(new UUID(99, 1), new UUID(99, 2));
        var packets = WindowBoardsUpdates.packets(DIMENSION, WORLD, groups, removed);
        WindowBoardsUpdates updates = new WindowBoardsUpdates();
        for (int i = 0; i < 12; i++) assertNull(updates.accept(roundTrip(packets.get(i))));
        WindowBoardsUpdates.Changes committed = updates.accept(roundTrip(packets.get(12)));
        assertEquals(groups, committed.groups());
        assertEquals(removed, committed.removed());
        assertNull(updates.accept(packets.get(0)), "Duplicate delivery cannot replay a committed batch");
        assertEquals(13, packets.size(), "Transaction deletions must not be sent twice");
    }
    @Test void splitAndMergeDoNotExposeDuplicateCellsBetweenPackets() {
        var shape = BoardLayoutTest.rectangle(0, 0, 2, 2);
        BoardGroup original = new BoardGroup(new UUID(42, 1), 3, 42, new BoardPlane(Direction.NORTH, 0),
                shape, Set.of(new BoardCell(1, 0)), BoardLayoutTest.supports(shape));
        BoardGroup broken = new BoardGroup(original.id(), 4, 42, original.plane(), shape,
                Set.of(new BoardCell(1, 0), new BoardCell(0, 1)), original.supports());
        var components = WindowBoardsData.components(broken.survivors());
        var remnants = BoardLayout.partition(broken, BoardLayout.generate(broken), components);
        List<BoardGroup> children = new ArrayList<>();
        for (int i = 0; i < 2; i++) children.add(new BoardGroup(new UUID(42, i + 1), i == 0 ? 4 : 1,
                42, original.plane(), components.get(i), Set.of(), Set.of(), remnants.get(i)));
        Map<UUID, BoardGroup> visible = new HashMap<>(Map.of(original.id(), original));
        WindowBoardsUpdates updates = new WindowBoardsUpdates();
        var split = WindowBoardsUpdates.packets(DIMENSION, WORLD, children, Set.of());
        assertNull(updates.accept(split.get(0)));
        assertEquals(Map.of(original.id(), original), visible);
        apply(visible, updates.accept(split.get(1)));
        assertEquals(2, visible.size());
        assertExclusive(visible.values());
        BoardGroup merged = new BoardGroup(original.id(), 5, 42, original.plane(),
                Set.of(new BoardCell(0, 0), new BoardCell(1, 0), new BoardCell(1, 1)), Set.of(), Set.of());
        var merge = WindowBoardsUpdates.packets(DIMENSION, WORLD, List.of(merged), Set.of(children.get(1).id()));
        assertNull(updates.accept(merge.get(0)));
        assertEquals(2, visible.size());
        apply(visible, updates.accept(merge.get(1)));
        assertEquals(Map.of(merged.id(), merged), visible);
        assertEquals(2, merge.size());
    }
    @Test void largeDeletionSetsUseBoundedPagesAndDoNotDuplicateTheAnchorGroup() {
        Set<UUID> removed = new HashSet<>();
        for (int i = 0; i < 3000; i++) removed.add(new UUID(99, i));
        var packets = WindowBoardsUpdates.packets(DIMENSION, WORLD, List.of(group(0)), removed);
        assertEquals(4, packets.size());
        WindowBoardsUpdates updates = new WindowBoardsUpdates();
        for (int i = 0; i < 3; i++) assertNull(updates.accept(roundTrip(packets.get(i))));
        var committed = updates.accept(roundTrip(packets.get(3)));
        assertEquals(List.of(group(0)), committed.groups());
        assertEquals(removed, committed.removed());
    }
    @Test void reorderedPartsAndIdempotentDuplicatesStillProduceOneCompleteChangeSet() {
        var packets = WindowBoardsUpdates.packets(DIMENSION, WORLD, List.of(group(0), group(1), group(2)), Set.of());
        WindowBoardsUpdates updates = new WindowBoardsUpdates();
        assertNull(updates.accept(packets.get(2)));
        assertNull(updates.accept(packets.get(0)));
        assertNull(updates.accept(packets.get(0)));
        var committed = updates.accept(packets.get(1));
        assertEquals(List.of(group(0), group(1), group(2)), committed.groups());
        assertNull(updates.accept(packets.get(1)));
    }
    @Test void resetDropsIncompleteStagingAndIndependentSingleUpdatesRemainImmediate() {
        var packets = WindowBoardsUpdates.packets(DIMENSION, WORLD, List.of(group(0), group(1)), Set.of());
        WindowBoardsUpdates updates = new WindowBoardsUpdates();
        assertNull(updates.accept(packets.get(0)));
        updates.clear();
        assertNull(updates.accept(packets.get(1)));
        updates.clear();
        var single = WindowBoardsUpdates.packets(DIMENSION, WORLD, List.of(group(2)), Set.of());
        assertEquals(List.of(group(2)), updates.accept(single.get(0)).groups());
        var removed = WindowBoardsUpdates.packets(DIMENSION, WORLD, List.of(), Set.of(group(2).id()));
        assertEquals(Set.of(group(2).id()), updates.accept(removed.get(0)).removed());
    }
    @Test void invalidCountersIdentityAndConflictingPartsAreRejected() {
        var packets = WindowBoardsUpdates.packets(DIMENSION, WORLD, List.of(group(0), group(1)), Set.of());
        CompoundTag invalid = packets.get(0).group().copy();
        invalid.getCompound("Update").putInt("Part", -1);
        WindowBoardsUpdates updates = new WindowBoardsUpdates();
        assertThrows(IllegalArgumentException.class, () -> updates.accept(
                new WindowBoardsNetwork.Packet(DIMENSION, WORLD, false, group(0).id(), invalid)));
        assertThrows(IllegalArgumentException.class, () -> updates.accept(
                new WindowBoardsNetwork.Packet(DIMENSION, WORLD, false, group(1).id(), group(0).save())));
        assertNull(updates.accept(packets.get(0)));
        CompoundTag conflict = packets.get(0).group().copy();
        conflict.putLong("Revision", 99);
        assertThrows(IllegalArgumentException.class, () -> updates.accept(
                new WindowBoardsNetwork.Packet(DIMENSION, WORLD, false, group(0).id(), conflict)));
    }
    @Test void unrelatedInitialWindowsStreamImmediatelyInsteadOfWaitingForOneWorldSizedBatch() {
        Map<UUID, BoardGroup> current = new HashMap<>();
        for (int i = 0; i < 100; i++) current.put(group(i).id(), group(i));
        var packets = WindowBoardsUpdates.synchronize(DIMENSION, WORLD, Map.of(), current);
        assertEquals(100, packets.size());
        WindowBoardsUpdates updates = new WindowBoardsUpdates();
        for (var packet : packets) {
            assertFalse(packet.group().contains("Update"));
            assertEquals(1, updates.accept(packet).groups().size());
        }
        assertTrue(WindowBoardsUpdates.synchronize(DIMENSION, WORLD, current, current).isEmpty());
    }
    @Test void topologyTransactionsUseBothOldAndNewMembershipAndKeepUnrelatedWindowsIndependent() {
        BoardGroup first = group(0), second = group(2), unrelated = group(9);
        BoardGroup merged = new BoardGroup(first.id(), 4, 42, first.plane(),
                BoardLayoutTest.rectangle(0, 0, 3, 1), Set.of(), Set.of());
        var packets = WindowBoardsUpdates.synchronize(DIMENSION, WORLD,
                Map.of(first.id(), first, second.id(), second),
                Map.of(merged.id(), merged, unrelated.id(), unrelated));
        WindowBoardsUpdates updates = new WindowBoardsUpdates();
        int commits = 0;
        for (var packet : packets) {
            var change = updates.accept(packet);
            if (change == null || change.groups().isEmpty()) continue;
            commits++;
            if (change.groups().contains(merged)) assertEquals(Set.of(second.id()), change.removed());
            else assertEquals(List.of(unrelated), change.groups());
        }
        assertEquals(2, commits);
    }
    private static void apply(Map<UUID, BoardGroup> visible, WindowBoardsUpdates.Changes changes) {
        changes.removed().forEach(visible::remove);
        changes.groups().forEach(group -> visible.put(group.id(), group));
    }
    private static void assertExclusive(Collection<BoardGroup> groups) {
        Set<BoardCell> cells = new HashSet<>();
        for (BoardGroup group : groups) for (BoardCell cell : group.survivors())
            assertTrue(cells.add(cell), "A live cell cannot belong to two rendered regions");
    }
    private static WindowBoardsNetwork.Packet roundTrip(WindowBoardsNetwork.Packet packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            packet.write(buffer);
            assertTrue(buffer.readableBytes() < 128 * 1024);
            var decoded = WindowBoardsNetwork.Packet.read(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally { buffer.release(); }
    }
}
