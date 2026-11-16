package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Area;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.scarasol.barricade.sampling.MemoryFixture;

class BoardPartitionTest {
    @BeforeAll static void bootstrap() { MemoryFixture.create(false); }
    private static UUID identity(int value) { return new UUID(123, value); }
    private static List<BoardLayout.Fragment> visible(BoardGroup group) {
        return BoardLayout.visible(BoardLayout.generate(group), group, Set.of());
    }
    private static WindowBoardsData rectangle(BoardPlane plane, int width, int height, long seed) {
        WindowBoardsData data = new WindowBoardsData();
        UUID id = null;
        int serial = 1;
        for (int v = 0; v < height; v++) for (int u = 0; u < width; u++)
            id = data.place(plane.block(new BoardCell(u, v)), plane.facing(), identity(serial++), seed).id();
        data.supports(id, BoardLayoutTest.supports(data.group(id).shape()));
        return data;
    }
    @Test void oppositeCornersSplitInAllSixPlanesWithSharedCutsButIndependentMembership() {
        BoardCell a = new BoardCell(0, 0), b = new BoardCell(1, 0), c = new BoardCell(0, 1), d = new BoardCell(1, 1);
        for (Direction facing : Direction.values()) for (long seed = 0; seed < 12; seed++) {
            BoardPlane plane = new BoardPlane(facing, 17);
            WindowBoardsData data = rectangle(plane, 2, 2, seed);
            UUID originalId = data.activeAt(plane.block(a));
            BoardGroup original = data.group(originalId);
            data.remove(plane.block(b));
            assertEquals(1, data.groups().size());
            assertNull(data.group(originalId).remnant());
            data.remove(plane.block(c));
            assertEquals(2, data.groups().size());
            BoardGroup left = data.group(data.activeAt(plane.block(a)));
            BoardGroup right = data.group(data.activeAt(plane.block(d)));
            assertEquals(originalId, left.id(), "Coordinate ordering breaks equal-size ties");
            assertNotEquals(left.id(), right.id());
            assertEquals(Set.of(a), left.shape());
            assertEquals(Set.of(d), right.shape());
            assertFalse(left.alive(d));
            assertFalse(right.alive(a));
            for (BoardGroup child : data.groups()) {
                assertEquals(original.layoutKey(), child.layoutKey());
                assertEquals(Set.of(b, c), child.remnant().cuts());
                assertTrue(child.damage().isEmpty(), "Inherited cuts are not this region's new damage");
                assertEquals(child, BoardGroup.load(child.save()));
                BoardLayout.validateOwnership(child, BoardLayout.generate(child));
            }
            assertPartition(BoardLayout.generate(original), Set.of(b, c), data.groups());
            CompoundTag saved = data.save(new CompoundTag());
            assertEquals(1, saved.getInt("Format"));
            assertEquals(1, saved.getList("Sources", Tag.TAG_COMPOUND).size());
            for (Tag entry : saved.getList("Groups", Tag.TAG_COMPOUND)) {
                CompoundTag remnant = ((CompoundTag) entry).getCompound("Remnant");
                assertTrue(remnant.contains("SourceIndex", Tag.TAG_INT));
                assertFalse(remnant.contains("Source"), "Do not duplicate the frozen source per component");
            }
            WindowBoardsData loaded = WindowBoardsData.load(saved);
            assertEquals(saved, loaded.save(new CompoundTag()));
            List<BoardGroup> children = List.copyOf(loaded.groups());
            assertSame(children.get(0).remnant().source(), children.get(1).remnant().source());
            for (BoardGroup child : data.groups()) assertEquals(visible(child), visible(loaded.group(child.id())));
        }
    }
    @Test void maintenanceAndDestructionOfOneCornerNeverTouchTheOtherCorner() {
        BoardPlane plane = new BoardPlane(Direction.NORTH, 0);
        WindowBoardsData data = rectangle(plane, 2, 2, 42);
        BoardCell a = new BoardCell(0, 0), b = new BoardCell(1, 0), c = new BoardCell(0, 1), d = new BoardCell(1, 1);
        data.remove(plane.block(b));
        data.remove(plane.block(c));
        BoardGroup other = data.group(data.activeAt(plane.block(d)));
        var mesh = visible(other);
        UUID maintainedId = data.place(plane.block(a.offset(-1, 0)), plane.facing(), identity(99), 999).id();
        BoardGroup maintained = data.group(maintainedId);
        assertNull(maintained.remnant());
        assertTrue(maintained.damage().isEmpty());
        assertEquals(Set.of(a, a.offset(-1, 0)), maintained.shape());
        assertEquals(other, data.group(other.id()));
        assertEquals(mesh, visible(data.group(other.id())));
        data = WindowBoardsData.load(data.save(new CompoundTag()));
        data.remove(plane.block(a));
        data.remove(plane.block(a.offset(-1, 0)));
        assertNull(data.group(maintainedId));
        assertEquals(other, data.group(other.id()));
        assertEquals(mesh, visible(data.group(other.id())));
        assertEquals(1, data.save(new CompoundTag()).getList("Sources", Tag.TAG_COMPOUND).size());
        data.remove(plane.block(d));
        assertTrue(data.groups().isEmpty());
        assertFalse(data.save(new CompoundTag()).contains("Sources"));
        assertTrue(data.activeInChunk(net.minecraft.world.level.ChunkPos.asLong(0, 0)).isEmpty());
        assertEquals(identity(100), data.place(plane.block(a), plane.facing(), identity(100), 123).id());
        assertNull(data.group(identity(100)).remnant());
        assertEquals(123, data.group(identity(100)).seed());
    }
    @Test void replacingEitherDiagonalHoleMergesExactlyTheTwoLiveCornersAndDropsOldHistory() {
        for (BoardCell repaired : List.of(new BoardCell(1, 0), new BoardCell(0, 1))) {
            BoardPlane plane = new BoardPlane(Direction.UP, 64);
            WindowBoardsData data = rectangle(plane, 2, 2, 42);
            data.remove(plane.block(new BoardCell(1, 0)));
            data.remove(plane.block(new BoardCell(0, 1)));
            Set<UUID> old = new HashSet<>();
            data.groups().forEach(group -> old.add(group.id()));
            var placement = data.place(plane.block(repaired), plane.facing(), identity(99), 999);
            assertTrue(old.contains(placement.id()));
            assertEquals(1, data.groups().size());
            BoardGroup merged = data.group(placement.id());
            assertEquals(Set.of(new BoardCell(0, 0), new BoardCell(1, 1), repaired), merged.shape());
            assertNull(merged.remnant());
            assertTrue(merged.damage().isEmpty());
            assertFalse(data.save(new CompoundTag()).contains("Sources"));
        }
    }
    @Test void repeatedSplitsFreezeNewCutsLocallyWithoutAnAncestryChainOrSiblingChanges() {
        BoardPlane plane = new BoardPlane(Direction.SOUTH, -7);
        WindowBoardsData data = rectangle(plane, 7, 1, 42);
        BoardGroup source = data.groups().iterator().next();
        data.remove(plane.block(new BoardCell(3, 0)));
        BoardGroup far = data.group(data.activeAt(plane.block(new BoardCell(6, 0))));
        var farMesh = visible(far);
        data.remove(plane.block(new BoardCell(1, 0)));
        assertEquals(3, data.groups().size());
        assertEquals(far, data.group(far.id()));
        assertEquals(farMesh, visible(data.group(far.id())));
        for (int u : new int[]{0, 2}) {
            BoardGroup child = data.group(data.activeAt(plane.block(new BoardCell(u, 0))));
            assertEquals(source.layoutKey(), child.layoutKey());
            assertEquals(Set.of(new BoardCell(1, 0), new BoardCell(3, 0)), child.remnant().cuts());
            assertEquals(Set.of(new BoardCell(u, 0)), child.shape());
        }
        assertPartition(BoardLayout.generate(source), Set.of(new BoardCell(1, 0), new BoardCell(3, 0)), data.groups());
        assertEquals(1, data.save(new CompoundTag()).getList("Sources", Tag.TAG_COMPOUND).size());
        CompoundTag saved = data.save(new CompoundTag());
        assertEquals(saved, WindowBoardsData.load(saved).save(new CompoundTag()));
    }
    @Test void placingInAnIsolatedHistoricalHoleCannotMaintainOrResurrectASurvivingIsland() {
        BoardPlane plane = new BoardPlane(Direction.NORTH, 0);
        WindowBoardsData data = rectangle(plane, 5, 1, 42);
        for (int u : new int[]{1, 2, 3, 4}) data.remove(plane.block(new BoardCell(u, 0)));
        BoardGroup survivor = data.group(data.activeAt(plane.block(new BoardCell(0, 0))));
        var mesh = visible(survivor);
        var placement = data.place(plane.block(new BoardCell(2, 0)), plane.facing(), identity(99), 999);
        assertEquals(identity(99), placement.id());
        assertEquals(survivor, data.group(survivor.id()));
        assertEquals(mesh, visible(data.group(survivor.id())));
        assertEquals(2, data.groups().size());
        assertEquals(999, data.group(placement.id()).seed());
    }
    @Test void fullCapacityCheckerboardRetiresAllRegionsAndSharedSourcesWithoutGhosts() {
        BoardPlane plane = new BoardPlane(Direction.DOWN, 64);
        WindowBoardsData data = rectangle(plane, 16, 16, 42);
        for (int v = 0; v < 16; v++) for (int u = 0; u < 16; u++)
            if ((u + v) % 2 == 0) data.remove(plane.block(new BoardCell(u, v)));
        assertEquals(128, data.groups().size());
        assertTrue(data.groups().stream().allMatch(group -> group.survivors().size() == 1));
        CompoundTag saved = data.save(new CompoundTag());
        assertEquals(1, saved.getList("Sources", Tag.TAG_COMPOUND).size());
        data = WindowBoardsData.load(saved);
        for (int v = 0; v < 16; v++) for (int u = 0; u < 16; u++) {
            if ((u + v) % 2 == 0) continue;
            UUID id = data.activeAt(plane.block(new BoardCell(u, v)));
            data.remove(plane.block(new BoardCell(u, v)));
            assertNull(data.group(id));
        }
        assertTrue(data.groups().isEmpty());
        assertFalse(data.save(new CompoundTag()).contains("Sources"));
        assertTrue(data.visible(Set.of(net.minecraft.world.level.ChunkPos.asLong(0, 0))).isEmpty());
    }
    @Test void invalidOwnershipAndReferencesAreRejectedInsteadOfBeingSilentlyReset() {
        BoardPlane plane = new BoardPlane(Direction.NORTH, 0);
        WindowBoardsData data = rectangle(plane, 2, 2, 42);
        data.remove(plane.block(new BoardCell(1, 0)));
        data.remove(plane.block(new BoardCell(0, 1)));
        CompoundTag saved = data.save(new CompoundTag());
        saved.getList("Groups", Tag.TAG_COMPOUND).getCompound(0).getCompound("Remnant").putInt("SourceIndex", 99);
        assertThrows(IllegalArgumentException.class, () -> WindowBoardsData.load(saved));
        CompoundTag corrupt = data.save(new CompoundTag());
        corrupt.getList("Groups", Tag.TAG_COMPOUND).getCompound(0).getCompound("Remnant")
                .putLongArray("Wood", new long[]{Long.MIN_VALUE, 1});
        assertThrows(IllegalArgumentException.class, () -> WindowBoardsData.load(corrupt));
    }
    @Test void aDiagonalAcrossASupportedCornerGetsExclusiveIntegerOwnershipWithoutLosingABoundaryNail() {
        BoardCell a = new BoardCell(0, 0), b = new BoardCell(1, 0), c = new BoardCell(0, 1), d = new BoardCell(1, 1);
        BoardGroup original = new BoardGroup(identity(1), 2, 42, new BoardPlane(Direction.NORTH, 0),
                Set.of(a, c, d), Set.of(c), Set.of(b));
        var plank = new BoardLayout.Plank(1, new BoardLayout.Point(12, 8), new BoardLayout.Point(0.6, 0.8),
                20, 3, 2, 0, false, 0xc0b6a4, 0,
                List.of(new BoardLayout.Nail(2, a), new BoardLayout.Nail(9, d), new BoardLayout.Nail(18, d)), List.of(a, d));
        var layout = new BoardLayout.Layout(original.layoutKey(), List.of(plank));
        var partitions = BoardLayout.partition(original, layout, List.of(Set.of(a), Set.of(d)));
        BoardGroup left = new BoardGroup(identity(1), 3, 42, original.plane(), Set.of(a), Set.of(), Set.of(b), partitions.get(0));
        BoardGroup right = new BoardGroup(identity(2), 1, 42, original.plane(), Set.of(d), Set.of(), Set.of(b), partitions.get(1));
        long first = left.remnant().wood().get(1L), second = right.remnant().wood().get(1L);
        assertEquals(0, first & second);
        assertEquals((1L << 20) - 1, first | second);
        assertPartition(layout, Set.of(c), List.of(left, right));
        assertEquals(0, first & (1L << 9));
        assertNotEquals(0, second & (1L << 9));
    }
    @Test void randomizedIrregularSplitsAtNegativeCoordinatesPreserveEachUnaffectedIslandAndEveryVisibleSolid() {
        for (long seed = 0; seed < 16; seed++) {
            Set<BoardCell> shape = BoardLayoutTest.rectangle(-3, -4, 6, 6);
            BoardGroup original = new BoardGroup(identity(1), 1, seed, new BoardPlane(Direction.NORTH, -17),
                    shape, Set.of(), BoardLayoutTest.supports(shape));
            CompoundTag saved = new WindowBoardsData().save(new CompoundTag());
            ListTag entries = new ListTag();
            entries.add(original.save());
            saved.put("Groups", entries);
            WindowBoardsData data = WindowBoardsData.load(saved);
            var layout = BoardLayout.generate(original);
            List<BoardCell> order = new ArrayList<>(new TreeSet<>(shape));
            Collections.shuffle(order, new Random(seed));
            for (BoardCell cell : order) {
                BoardGroup before = data.group(data.activeAt(original.plane().block(cell)));
                Map<UUID, BoardGroup> others = new HashMap<>();
                for (BoardGroup group : data.groups()) if (!group.id().equals(before.id())) others.put(group.id(), group);
                Set<BoardCell> damage = new HashSet<>(before.damage());
                damage.add(cell);
                BoardGroup damaged = new BoardGroup(before.id(), before.revision() + 1, before.seed(), before.plane(),
                        before.shape(), damage, before.supports(), before.remnant());
                var expected = BoardLayout.visible(layout, damaged, Set.of());
                data.remove(original.plane().block(cell));
                var changed = data.groups().stream().filter(group -> !Collections.disjoint(group.survivors(), before.survivors()))
                        .flatMap(group -> BoardLayout.visible(layout, group, Set.of()).stream()).toList();
                assertFragments(layout, expected, changed);
                for (BoardGroup other : others.values()) assertEquals(other, data.group(other.id()));
                for (BoardGroup group : data.groups()) assertEquals(1, WindowBoardsData.components(group.survivors()).size());
            }
            assertTrue(data.groups().isEmpty());
            assertFalse(data.save(new CompoundTag()).contains("Sources"));
        }
    }
    @Test void temporarilyUnloadedRemnantCellsUseOnlyATransientMaskAndRestoreExactly() {
        BoardPlane plane = new BoardPlane(Direction.EAST, 0);
        WindowBoardsData data = rectangle(plane, 7, 1, 42);
        data.remove(plane.block(new BoardCell(3, 0)));
        BoardGroup group = data.group(data.activeAt(plane.block(new BoardCell(0, 0))));
        var layout = BoardLayout.generate(group);
        var intact = BoardLayout.visible(layout, group, Set.of());
        CompoundTag persisted = data.save(new CompoundTag());
        assertFalse(intact.isEmpty());
        assertTrue(BoardLayout.visible(layout, group, group.survivors()).isEmpty());
        assertEquals(intact, BoardLayout.visible(layout, group, Set.of()));
        assertEquals(persisted, data.save(new CompoundTag()));
        for (BoardCell cell : group.survivors())
            assertFalse(data.place(plane.block(cell), plane.facing(), identity(99), 999).geometryChanged());
        assertEquals(persisted, data.save(new CompoundTag()));
    }
    private static void assertPartition(BoardLayout.Layout layout, Set<BoardCell> damage, Collection<BoardGroup> groups) {
        var expected = BoardLayout.visible(layout, damage);
        var actual = groups.stream().flatMap(group -> BoardLayout.visible(layout, group, Set.of()).stream()).toList();
        assertFragments(layout, expected, actual);
    }
    private static void assertFragments(BoardLayout.Layout layout, List<BoardLayout.Fragment> expected,
                                         List<BoardLayout.Fragment> actual) {
        Map<Long, Area> remaining = new HashMap<>();
        for (var fragment : expected) remaining.computeIfAbsent(fragment.plankId(), ignored -> new Area())
                .add(BoardLayoutTest.footprint(fragment));
        Map<Long, Area> drawn = new HashMap<>();
        for (var fragment : actual) {
            BoardLayoutTest.assertCuboid(BoardLayoutTest.plank(layout, fragment), fragment);
            Area footprint = BoardLayoutTest.footprint(fragment);
            Area previous = drawn.computeIfAbsent(fragment.plankId(), ignored -> new Area());
            Area duplicate = new Area(previous);
            duplicate.intersect(footprint);
            assertEquals(0, BoardLayoutTest.area(duplicate), 0.00001, "No duplicate wood across regions");
            previous.add(footprint);
            var plank = BoardLayoutTest.plank(layout, fragment);
            for (var face : fragment.faces()) if (!face.nail() && face.normal().z() > 0.9)
                for (var vertex : face.vertices()) {
                    double along = (vertex.u() - plank.start().u()) * plank.direction().u()
                            + (vertex.v() - plank.start().v()) * plank.direction().v();
                    double phase = along / 16 - Math.floor(along / 16);
                    if (phase < 0.00001 || phase > 0.99999)
                        assertTrue(vertex.textureU() < 0.00001 || vertex.textureU() > 0.99999);
                    else assertEquals(phase, vertex.textureU(), 0.00001);
                }
        }
        assertEquals(remaining.keySet(), drawn.keySet());
        remaining.forEach((id, footprint) -> {
            footprint.exclusiveOr(drawn.get(id));
            assertEquals(0, BoardLayoutTest.area(footprint), 0.00001, "No geometry lost or moved at split");
        });
        List<BoardLayout.Face> expectedNails = expected.stream().flatMap(fragment -> fragment.faces().stream())
                .filter(BoardLayout.Face::nail).toList();
        List<BoardLayout.Face> actualNails = actual.stream().flatMap(fragment -> fragment.faces().stream())
                .filter(BoardLayout.Face::nail).toList();
        assertEquals(expectedNails.size(), actualNails.size());
        assertEquals(new HashSet<>(expectedNails), new HashSet<>(actualNails), "No nail loss or duplication at split");
    }
}
