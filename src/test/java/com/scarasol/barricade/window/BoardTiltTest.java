package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BoardTiltTest {
    @BeforeAll static void bootstrap() { MemoryFixture.create(false); }

    @Test void horizontalRowsHaveDeterministicBellShapedTiltWithRareLargerAnglesAndWholeIntegerSolids() {
        int total = 0, small = 0, large = 0, positive = 0, negative = 0;
        for (int size : new int[]{2, 3, 16}) for (long seed = 0; seed < 24; seed++) {
            Set<BoardCell> shape = BoardLayoutTest.rectangle(-3, -5, size, size);
            BoardGroup group = group(shape, seed);
            var layout = BoardLayout.generate(group);
            var mains = layout.planks().stream().filter(plank -> !plank.brace()).toList();
            if (size == 16) assertTrue(mains.stream().map(BoardLayout.Plank::direction).distinct().count() > 1);
            assertEquals(layout.planks().size(), layout.planks().stream().map(BoardLayout.Plank::id).distinct().count(),
                    "Unique plank IDs: size=" + size + ", seed=" + seed);
            for (var main : mains) {
                double slope = main.direction().v() / main.direction().u();
                double angle = Math.toDegrees(Math.atan(slope));
                assertTrue(Math.abs(angle) <= 15.000001);
                total++;
                if (Math.abs(angle) <= 5) small++;
                if (Math.abs(angle) >= 6) large++;
                if (angle > 0) positive++;
                if (angle < 0) negative++;
                assertEquals(0, main.depth());
                assertEquals(2, main.thickness());
            }
            assertEquals(layout, BoardLayout.generate(BoardGroup.load(group.save())));
            for (var fragment : BoardLayout.visible(layout, group, Set.of())) {
                try { BoardLayoutTest.assertCuboid(BoardLayoutTest.plank(layout, fragment), fragment); }
                catch (AssertionError error) { throw new AssertionError("size=" + size + ", seed=" + seed
                        + ", plank=" + BoardLayoutTest.plank(layout, fragment), error); }
            }
        }
        assertTrue(small > total * 0.45, small + "/" + total);
        assertTrue(large > total * 0.001 && large < total * 0.30, large + "/" + total);
        assertTrue(positive > total * 0.30 && negative > total * 0.30);
        System.out.println("Window-board tilt sample: total=" + total + ", within 5 degrees=" + small
                + ", at least 6 degrees=" + large);
    }

    @Test void shortRowsOccasionallyReachFifteenDegreesWhileLongInteriorRowsHaveSmallerTails() {
        int shortTotal = 0, shortLarge = 0, maximum = 0, longTotal = 0;
        for (long seed = 0; seed < 512; seed++) {
            var shortLayout = BoardLayout.generate(group(BoardLayoutTest.rectangle(0, 0, 2, 2), seed));
            for (var plank : shortLayout.planks()) if (!plank.brace()) {
                double angle = Math.abs(Math.toDegrees(Math.atan2(plank.direction().v(), plank.direction().u())));
                shortTotal++;
                if (angle >= 10) shortLarge++;
                if (angle >= 14.99999) maximum++;
            }
            if (seed >= 32) continue;
            var longLayout = BoardLayout.generate(group(BoardLayoutTest.rectangle(0, 0, 16, 16), seed));
            for (var plank : longLayout.planks()) if (!plank.brace() && plank.start().v() >= 64
                    && plank.start().v() <= 192) {
                longTotal++;
                assertTrue(Math.abs(Math.toDegrees(Math.atan2(plank.direction().v(), plank.direction().u()))) <= 9.000001);
            }
        }
        assertTrue(longTotal > 100);
        assertTrue(maximum > 0 && maximum < shortTotal * 0.02, maximum + "/" + shortTotal);
        assertTrue(shortLarge > shortTotal * 0.01 && shortLarge < shortTotal * 0.15, shortLarge + "/" + shortTotal);
        System.out.println("Short-row tilt tails: total=" + shortTotal + ", at least 10 degrees=" + shortLarge
                + ", 15 degrees=" + maximum);
    }

    @Test void integerLengthVariationKeepsSupportedEndNailsOnTheWallAndVariesLongBoardLengths() {
        Set<Integer> lengths = new HashSet<>();
        Set<Integer> leftEnds = new HashSet<>(), rightEnds = new HashSet<>();
        int anchored = 0;
        for (long seed = 0; seed < 64; seed++) {
            var layout = BoardLayout.generate(group(BoardLayoutTest.rectangle(0, 0, 2, 2), seed));
            for (var plank : layout.planks()) if (!plank.brace()) {
                double endU = plank.start().u() + plank.direction().u() * plank.length();
                if (plank.start().u() >= 0 || endU <= 32) continue;
                leftEnds.add((int) Math.round(plank.start().u()));
                rightEnds.add((int) Math.round(endU));
                assertTrue(plank.start().u() >= -6.1 && endU <= 38.1);
                for (var nail : plank.nails()) {
                    var point = BoardStructureTest.point(plank, nail);
                    assertFalse(layout.key().shape().contains(new BoardCell((int) Math.floor(point.u() / 16),
                            (int) Math.floor(point.v() / 16))), "A boundary nail must reach the wall");
                }
                anchored++;
            }
            var longLayout = BoardLayout.generate(group(BoardLayoutTest.rectangle(0, 0, 16, 1), seed));
            for (var plank : longLayout.planks()) if (!plank.brace() && plank.length() > 256) lengths.add(plank.length());
        }
        assertTrue(anchored > 50);
        assertTrue(leftEnds.size() >= 3 && rightEnds.size() >= 3);
        assertTrue(lengths.size() >= 5 && lengths.stream().allMatch(length -> length > 64 && length <= BoardLayout.MAX_LENGTH));
    }

    @Test void spansUnderFourBlocksStayWholeRatherThanCreatingExtraInternalEnds() {
        for (int size : new int[]{2, 3}) for (long seed = 0; seed < 128; seed++) {
            var shape = BoardLayoutTest.rectangle(0, 0, size, size);
            var supports = BoardLayoutTest.supports(shape);
            var layout = BoardLayout.generate(group(shape, seed));
            for (var plank : layout.planks()) if (!plank.brace()) {
                var end = plank.start().add(plank.direction().scale(plank.length()));
                for (var point : List.of(plank.start(), end))
                    assertTrue(supports.contains(new BoardCell((int) Math.floor(point.u() / 16),
                            (int) Math.floor(point.v() / 16))), "Artificial internal end: " + plank);
            }
        }
    }

    @Test void localWindowsUsuallyKeepBothTiltDirectionsAndTheSeedDoesNotAddASharedSlope() {
        int mixed = 0, positive = 0, negative = 0;
        for (long seed = 0; seed < 128; seed++) {
            var layout = BoardLayout.generate(group(BoardLayoutTest.rectangle(0, 0, 3, 3), seed));
            boolean up = false, down = false;
            for (var plank : layout.planks()) if (!plank.brace()) {
                if (plank.direction().v() > 0) { up = true; positive++; }
                if (plank.direction().v() < 0) { down = true; negative++; }
            }
            if (up && down) mixed++;
        }
        System.out.println("Local tilt balance: mixed windows=" + mixed + "/128, positive=" + positive + ", negative=" + negative);
        assertTrue(mixed >= 116);
        assertTrue(Math.abs(positive - negative) < (positive + negative) * 0.10);
    }

    @Test void layoutsAndOwnershipSurviveLoadingDamageAndSplittingUntilActualMaintenance() {
        var shape = BoardLayoutTest.rectangle(0, 0, 7, 1);
        BoardGroup original = group(shape, 42);
        var layout = BoardLayout.generate(original);
        CompoundTag saved = new WindowBoardsData().save(new CompoundTag());
        ListTag list = new ListTag();
        list.add(original.save());
        saved.put("Groups", list);
        WindowBoardsData data = WindowBoardsData.load(saved);
        assertEquals(saved, data.save(new CompoundTag()));
        assertFalse(data.place(original.plane().block(new BoardCell(0, 0)), Direction.NORTH, new UUID(2, 3), 99)
                .geometryChanged());
        data.remove(original.plane().block(new BoardCell(3, 0)));
        assertEquals(2, data.groups().size());
        for (BoardGroup child : data.groups()) assertEquals(original.layoutKey(), child.layoutKey());
        CompoundTag split = data.save(new CompoundTag());
        assertEquals(1, split.getList("Sources", Tag.TAG_COMPOUND).size());
        data = WindowBoardsData.load(split);
        assertEquals(split, data.save(new CompoundTag()));
        BoardGroup far = data.group(data.activeAt(original.plane().block(new BoardCell(6, 0))));
        var farMesh = BoardLayout.visible(layout, far, Set.of());
        data.remove(original.plane().block(new BoardCell(0, 0)));
        data.supports(original.id(), Set.of());
        UUID maintained = data.place(original.plane().block(new BoardCell(-1, 0)), Direction.NORTH, new UUID(2, 3), 99).id();
        assertNull(data.group(maintained).remnant());
        assertEquals(far, data.group(far.id()));
        assertEquals(farMesh, BoardLayout.visible(layout, data.group(far.id()), Set.of()));
    }

    @Test void layoutInputsPersistInGroupsSharedSourcesAndPackets() {
        var shape = BoardLayoutTest.rectangle(0, 0, 2, 2);
        BoardGroup original = group(shape, 19);
        BoardGroup damaged = new BoardGroup(original.id(), 2, original.seed(), original.plane(), shape,
                Set.of(new BoardCell(1, 0), new BoardCell(0, 1)), original.supports());
        var components = WindowBoardsData.components(damaged.survivors());
        var remnants = BoardLayout.partition(damaged, BoardLayout.generate(damaged), components);
        List<BoardGroup> children = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            var child = new BoardGroup(new UUID(5, i + 1), 3, damaged.seed(), damaged.plane(),
                    components.get(i), Set.of(), Set.of(), remnants.get(i));
            children.add(child);
            assertEquals(child, BoardGroup.load(child.save()));
            assertEquals(1, child.save().getInt("Algorithm"));
        }
        var packets = WindowBoardsUpdates.packets(new net.minecraft.resources.ResourceLocation("minecraft", "overworld"),
                new UUID(2, 3), children, Set.of());
        WindowBoardsUpdates receiver = new WindowBoardsUpdates();
        assertNull(receiver.accept(packets.get(0)));
        assertEquals(new WindowBoardsUpdates.Changes(children, Set.of()), receiver.accept(packets.get(1)));
    }

    private static BoardGroup group(Set<BoardCell> shape, long seed) {
        return new BoardGroup(new UUID(1, 2), 1, seed, new BoardPlane(Direction.NORTH, 0),
                shape, Set.of(), BoardLayoutTest.supports(shape));
    }
}
