package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.awt.geom.Area;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WindowBarsDataTest {
    @BeforeAll static void bootstrap() { MemoryFixture.create(false); }
    private static UUID id(int value) { return new UUID(999, value); }
    private static List<BoardLayout.Fragment> visible(BoardGroup group) {
        return BoardLayout.visible(BoardLayout.generate(group), group, Set.of());
    }
    @Test void neighboringMaterialsStaySeparateAndReplacingTheSameFacingChangesMaterial() {
        for (Direction facing : Direction.values()) {
            var data = new WindowBoardsData();
            var plane = new BoardPlane(facing, 7);
            BlockPos a = plane.block(new BoardCell(0, 0)), b = plane.block(new BoardCell(1, 0));
            data.place(a, facing, id(1), 42);
            data.place(b, facing, id(2), 42, SealMaterial.IRON);
            assertEquals(2, data.groups().size());
            assertNotEquals(data.group(id(1)).layoutKey().material(), data.group(id(2)).layoutKey().material());
            data.reconcileSeals(Map.of(a, new WindowBoardsData.Observation(facing, SealMaterial.IRON)));
            assertNull(data.activeAt(a));
            data.place(a, facing, id(3), 99, SealMaterial.IRON);
            assertEquals(1, data.groups().size());
            assertEquals(SealMaterial.IRON, data.groups().iterator().next().material());
            assertEquals(2, data.groups().iterator().next().shape().size());
        }
    }
    @Test void splitRepairSaveAndFinalDeletionUseTheSameRulesInEveryPlane() {
        for (Direction facing : Direction.values()) for (long seed = 0; seed < 8; seed++) {
            var data = new WindowBoardsData();
            var plane = new BoardPlane(facing, 7);
            for (int v = 0; v < 2; v++) for (int u = 0; u < 2; u++)
                data.place(plane.block(new BoardCell(u, v)), facing, id(1 + u + v * 2), seed, SealMaterial.IRON);
            data.remove(plane.block(new BoardCell(1, 0)));
            data.remove(plane.block(new BoardCell(0, 1)));
            assertEquals(2, data.groups().size());
            var saved = data.save(new CompoundTag());
            var loaded = WindowBoardsData.load(saved);
            assertEquals(saved, loaded.save(new CompoundTag()));
            for (var group : data.groups()) {
                assertEquals(SealMaterial.IRON, group.material());
                assertEquals(SealMaterial.IRON, group.layoutKey().material());
                assertEquals(group, BoardGroup.load(group.save()), "Network group NBT must preserve material and ownership");
                assertEquals(visible(group), visible(loaded.group(group.id())));
                BoardLayout.validateOwnership(group, BoardLayout.generate(group));
            }
            var sibling = loaded.group(loaded.activeAt(plane.block(new BoardCell(1, 1))));
            var mesh = visible(sibling);
            loaded.place(plane.block(new BoardCell(-1, 0)), facing, id(9), 999, SealMaterial.IRON);
            assertEquals(sibling, loaded.group(sibling.id()));
            assertEquals(mesh, visible(loaded.group(sibling.id())));
            var repaired = loaded.group(loaded.activeAt(plane.block(new BoardCell(0, 0))));
            assertNull(repaired.remnant());
            assertTrue(repaired.damage().isEmpty());
            for (var group : List.copyOf(loaded.groups())) for (var cell : group.survivors())
                loaded.remove(plane.block(cell));
            assertTrue(loaded.groups().isEmpty());
            assertFalse(loaded.save(new CompoundTag()).contains("Sources"));
        }
    }
    @Test void materialIsPartOfTheCacheIdentityAndInvalidSavedMaterialsAreRejected() {
        var shape = Set.of(new BoardCell(0, 0));
        var plane = new BoardPlane(Direction.NORTH, 0);
        var wood = new BoardGroup.LayoutKey(42, plane, shape, Set.of());
        var iron = new BoardGroup.LayoutKey(42, plane, shape, Set.of(), SealMaterial.IRON);
        assertNotEquals(wood, iron);
        assertNotEquals(BoardLayout.generate(wood), BoardLayout.generate(iron));
        var group = new BoardGroup(id(1), 1, 42, plane, shape, Set.of(), Set.of(), null, SealMaterial.IRON);
        var saved = group.save();
        saved.putString("Material", "UNKNOWN");
        assertThrows(IllegalArgumentException.class, () -> BoardGroup.load(saved));
        saved.putInt("Material", 1);
        assertThrows(IllegalArgumentException.class, () -> BoardGroup.load(saved));
        saved.putString("Material", "");
        assertThrows(IllegalArgumentException.class, () -> BoardGroup.load(saved));
        saved.remove("Material");
        assertThrows(IllegalArgumentException.class, () -> BoardGroup.load(saved));
    }
    @Test void fullCapacityCheckerboardKeepsExclusiveGeometryAndRetiresEverySource() {
        var data = new WindowBoardsData();
        var plane = new BoardPlane(Direction.DOWN, 64);
        Set<BoardCell> cuts = new HashSet<>();
        for (int v = -8; v < 8; v++) for (int u = -8; u < 8; u++)
            data.place(plane.block(new BoardCell(u, v)), plane.facing(), id((v + 8) * 16 + u + 9), 42, SealMaterial.IRON);
        var source = data.groups().iterator().next();
        var layout = BoardLayout.generate(source);
        for (int v = -8; v < 8; v++) for (int u = -8; u < 8; u++) if ((u + v) % 2 == 0) {
            var cell = new BoardCell(u, v);
            cuts.add(cell);
            data.remove(plane.block(cell));
        }
        assertEquals(128, data.groups().size());
        var expected = BoardLayout.visible(layout, cuts);
        var actual = data.groups().stream().flatMap(group -> BoardLayout.visible(layout, group, Set.of()).stream()).toList();
        Map<Long, Area> footprints = new HashMap<>();
        expected.forEach(fragment -> footprints.computeIfAbsent(fragment.plankId(), ignored -> new Area())
                .add(BoardLayoutTest.footprint(fragment)));
        Map<Long, Area> drawn = new HashMap<>();
        for (var fragment : actual) {
            Area area = BoardLayoutTest.footprint(fragment);
            Area previous = drawn.computeIfAbsent(fragment.plankId(), ignored -> new Area());
            Area duplicate = new Area(previous);
            duplicate.intersect(area);
            assertEquals(0, BoardLayoutTest.area(duplicate), 1e-5);
            previous.add(area);
        }
        assertEquals(footprints.keySet(), drawn.keySet());
        footprints.forEach((plank, area) -> {
            area.exclusiveOr(drawn.get(plank));
            assertEquals(0, BoardLayoutTest.area(area), 1e-5, "Splits must not move or lose metal");
        });
        var expectedWelds = expected.stream().flatMap(fragment -> fragment.faces().stream()).filter(BoardLayout.Face::nail).toList();
        var actualWelds = actual.stream().flatMap(fragment -> fragment.faces().stream()).filter(BoardLayout.Face::nail).toList();
        assertEquals(expectedWelds.size(), actualWelds.size());
        assertEquals(new HashSet<>(expectedWelds), new HashSet<>(actualWelds));
        var loaded = WindowBoardsData.load(data.save(new CompoundTag()));
        for (var group : loaded.groups()) for (var cell : group.survivors()) loaded.remove(plane.block(cell));
        assertTrue(loaded.groups().isEmpty());
        assertFalse(loaded.save(new CompoundTag()).contains("Sources"));
    }
    @Test void changingMaterialCommitsAsOneNetworkTransactionWithoutDuplicateCells() {
        var plane = new BoardPlane(Direction.NORTH, 0);
        var shape = Set.of(new BoardCell(0, 0));
        var wood = new BoardGroup(id(1), 1, 42, plane, shape, Set.of(), Set.of());
        var iron = new BoardGroup(id(2), 1, 99, plane, shape, Set.of(), Set.of(), null, SealMaterial.IRON);
        var packets = WindowBoardsUpdates.synchronize(new ResourceLocation("minecraft", "overworld"), id(99),
                Map.of(wood.id(), wood), Map.of(iron.id(), iron));
        var updates = new WindowBoardsUpdates();
        WindowBoardsUpdates.Changes committed = null;
        for (int i = 0; i < packets.size(); i++) {
            var buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                packets.get(i).write(buffer);
                committed = updates.accept(WindowBoardsNetwork.Packet.read(buffer));
                assertEquals(0, buffer.readableBytes());
                if (i != packets.size() - 1) assertNull(committed);
            } finally { buffer.release(); }
        }
        assertNotNull(committed);
        assertEquals(List.of(iron), committed.groups());
        assertEquals(Set.of(wood.id()), committed.removed());
    }
}
