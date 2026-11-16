package com.scarasol.barricade.window.client;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.window.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class WindowBoardsVisibilityTest {
    private static BoardGroup group(Direction facing, int coordinate, SealMaterial material, Set<BoardCell> shape) {
        return new BoardGroup(UUID.randomUUID(), 1, 42, new BoardPlane(facing, coordinate),
                shape, Set.of(), Set.of(), null, material);
    }
    private static BoardGroup damage(BoardGroup group, Set<BoardCell> damage) {
        return new BoardGroup(group.id(), group.revision() + 1, group.seed(), group.plane(),
                group.shape(), damage, group.supports(), group.remnant(), group.material());
    }
    @Test void unchangedFramesPerformNoMemberReadsForEitherMaterialOrAnyFacing() {
        Set<BoardCell> shape = new HashSet<>();
        for (int u = -8; u < 8; u++) for (int v = -8; v < 8; v++) shape.add(new BoardCell(u, v));
        for (Direction facing : Direction.values()) for (SealMaterial material : SealMaterial.values()) {
            var index = new WindowBoardsVisibility();
            BoardGroup group = group(facing, -17, material, shape);
            var state = index.track(group);
            var reads = new AtomicInteger();
            var hidden = state.hidden(pos -> { reads.incrementAndGet(); return true; });
            assertEquals(256, reads.get());
            assertTrue(hidden.isEmpty());
            assertFalse(state.dirty());
            for (int frame = 0; frame < 120; frame++)
                assertSame(hidden, state.hidden(pos -> { throw new AssertionError("Per-frame member scan"); }));
            state.update(damage(group, Set.of()));
            assertFalse(state.dirty(), "A revision-only packet does not change visibility");
        }
    }
    @Test void blockChangesInvalidateOnlyMembersOnTheMatchingPlane() {
        var index = new WindowBoardsVisibility();
        var shape = Set.of(new BoardCell(-1, 0), new BoardCell(0, 0));
        var group = group(Direction.NORTH, -1, SealMaterial.WOOD, shape);
        var first = index.track(group);
        var neighbor = index.track(group(Direction.NORTH, -2, SealMaterial.IRON, shape));
        var distant = index.track(group(Direction.NORTH, -1, SealMaterial.WOOD, Set.of(new BoardCell(32, 0))));
        first.hidden(pos -> true);
        neighbor.hidden(pos -> true);
        distant.hidden(pos -> true);
        index.blockChanged(new BlockPos(-1, 1, -1));
        assertFalse(first.dirty(), "An adjacent wall is not a member");
        BlockPos removed = group.plane().block(new BoardCell(-1, 0));
        index.blockChanged(removed);
        assertTrue(first.dirty());
        assertFalse(neighbor.dirty());
        assertFalse(distant.dirty());
        assertEquals(Set.of(new BoardCell(-1, 0)), first.hidden(pos -> !pos.equals(removed)));
        index.blockChanged(removed);
        assertTrue(first.hidden(pos -> true).isEmpty(), "Block arrival after a group packet restores its geometry");
    }
    @Test void chunkPacketsAndUnloadEventsRecheckOnlyTheirGroupsAndPreservePacketOrdering() {
        for (Direction facing : Direction.values()) for (SealMaterial material : SealMaterial.values()) {
            var index = new WindowBoardsVisibility();
            var group = group(facing, -17, material, Set.of(new BoardCell(-1, 0), new BoardCell(0, 0)));
            var state = index.track(group);
            var other = index.track(group(facing, -17, material, Set.of(new BoardCell(48, 48))));
            Set<BlockPos> present = new HashSet<>();
            assertEquals(group.shape(), state.hidden(present::contains), "Group metadata may arrive before chunks");
            other.hidden(pos -> true);
            BlockPos arrived = group.plane().block(new BoardCell(-1, 0));
            present.add(arrived);
            index.chunkChanged(new ChunkPos(arrived));
            assertTrue(state.dirty());
            assertFalse(other.dirty());
            Set<BoardCell> expected = new HashSet<>(group.shape());
            expected.remove(new BoardCell(-1, 0));
            assertEquals(expected, state.hidden(present::contains));
            index.chunkChanged(new ChunkPos(arrived));
            present.clear();
            assertEquals(group.shape(), state.hidden(present::contains),
                    "An unload callback can precede the chunk's actual removal");
            group.shape().forEach(cell -> present.add(group.plane().block(cell)));
            index.untrack(state);
            state = index.track(group);
            assertTrue(state.hidden(present::contains).isEmpty(), "Chunks may also arrive before metadata");
        }
    }
    @Test void damagePacketsInvalidateMasksWithoutWaitingForVanillaBlockPackets() {
        for (SealMaterial material : SealMaterial.values()) {
            var index = new WindowBoardsVisibility();
            var cell = new BoardCell(0, 0);
            var group = group(Direction.UP, 64, material, Set.of(cell, new BoardCell(1, 0)));
            var state = index.track(group);
            state.hidden(pos -> true);
            var damaged = damage(group, Set.of(cell));
            state.update(damaged);
            assertTrue(state.dirty());
            AtomicInteger reads = new AtomicInteger();
            assertEquals(Set.of(cell), state.hidden(pos -> { reads.incrementAndGet(); return true; }));
            assertEquals(1, reads.get(), "Damaged cells do not need block-state reads");
            state.update(damage(damaged, Set.of()));
            assertTrue(state.hidden(pos -> true).isEmpty(), "Maintenance restores the cleared damage mask");
        }
    }
    @Test void unchangedInvalidationsReuseTheMaskAndViewChangesRefreshAllTrackedStates() {
        var index = new WindowBoardsVisibility();
        var first = index.track(group(Direction.NORTH, 0, SealMaterial.WOOD, Set.of(new BoardCell(0, 0))));
        var other = index.track(group(Direction.DOWN, 64, SealMaterial.IRON, Set.of(new BoardCell(32, 0))));
        var mask = first.hidden(pos -> true);
        other.hidden(pos -> true);
        index.chunkChanged(new ChunkPos(0, 0));
        assertSame(mask, first.hidden(pos -> true), "An unchanged mask must not trigger a mesh rebuild");
        assertSame(mask, first.hidden(pos -> { throw new AssertionError("Resource reload needs no member rescan"); }));
        index.invalidateAll();
        assertTrue(first.dirty());
        assertTrue(other.dirty());
        assertEquals(Set.of(new BoardCell(0, 0)), first.hidden(pos -> false));
    }
    @Test void releasingOrResettingGroupsRemovesTheirChunkIndexEntries() {
        var index = new WindowBoardsVisibility();
        var group = group(Direction.NORTH, 0, SealMaterial.WOOD, Set.of(new BoardCell(0, 0)));
        var first = index.track(group);
        var replacement = index.track(group);
        first.hidden(pos -> true);
        replacement.hidden(pos -> true);
        index.untrack(first);
        index.blockChanged(BlockPos.ZERO);
        assertFalse(first.dirty());
        assertTrue(replacement.dirty(), "Untracking a replaced group must not discard its replacement");
        replacement.hidden(pos -> true);
        index.clear();
        index.chunkChanged(new ChunkPos(0, 0));
        index.blockChanged(BlockPos.ZERO);
        index.invalidateAll();
        assertFalse(replacement.dirty());
    }
}
