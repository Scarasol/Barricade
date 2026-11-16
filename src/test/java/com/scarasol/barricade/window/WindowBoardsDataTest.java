package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class WindowBoardsDataTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { MemoryFixture.create(false); }
    private static UUID identity(int value) { return new UUID(0, value); }
    @Test void missingMaterialResetsTheStoreAndAllowsBothKindsOfPlacement() throws Exception {
        CompoundTag group = new CompoundTag();
        group.putInt("Algorithm", BoardGroup.ALGORITHM);
        group.putUUID("Id", identity(1));
        group.putLong("Revision", 1);
        group.putLong("Seed", 42);
        group.putString("Facing", "north");
        group.putInt("Plane", 0);
        group.putLongArray("Shape", new long[]{new BoardCell(0, 0).packed()});
        group.putLongArray("Damage", new long[0]);
        group.putLongArray("Supports", new long[0]);
        ListTag groups = new ListTag();
        groups.add(group);
        CompoundTag data = new CompoundTag();
        data.putInt("Format", 1);
        data.putUUID("World", identity(99));
        data.putLong("Serial", 1);
        data.put("Groups", groups);
        CompoundTag root = new CompoundTag();
        root.put("data", data);
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        Path file = directory.resolve(WindowBoardsData.NAME + ".dat");
        NbtIo.writeCompressed(root, file.toFile());
        byte[] before = Files.readAllBytes(file);
        var storage = new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer());
        var opened = WindowBoardsData.open(storage, file);
        assertTrue(opened.groups().isEmpty());
        assertTrue(opened.isDirty(), "Unreadable data must be replaced on the next save");
        assertNotEquals(identity(99), opened.world());
        assertNull(opened.activeAt(BlockPos.ZERO));
        var wood = opened.place(BlockPos.ZERO, Direction.NORTH, identity(2), 77);
        var iron = opened.place(BlockPos.ZERO.above(), Direction.NORTH, identity(3), 99, SealMaterial.IRON);
        assertEquals(SealMaterial.WOOD, opened.group(wood.id()).material());
        assertEquals(SealMaterial.IRON, opened.group(iron.id()).material());
        assertEquals(2, opened.groups().size());
        storage.save();
        assertFalse(Arrays.equals(before, Files.readAllBytes(file)));
        var reloaded = WindowBoardsData.open(new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer()), file);
        assertEquals(opened.save(new CompoundTag()), reloaded.save(new CompoundTag()));
    }
    @Test void bothMaterialsUseTheSameExplicitFieldsInGroupsSplitSourcesAndNetwork() {
        for (SealMaterial material : SealMaterial.values()) {
            var data = new WindowBoardsData();
            for (int v = 0; v < 2; v++) for (int u = 0; u < 2; u++)
                data.place(new BlockPos(u, v, 0), Direction.NORTH, identity(1 + u + v * 2), 42, material);
            data.remove(new BlockPos(1, 0, 0));
            data.remove(new BlockPos(0, 1, 0));
            CompoundTag saved = data.save(new CompoundTag());
            for (Tag source : saved.getList("Sources", Tag.TAG_COMPOUND))
                assertEquals(material.name(), ((CompoundTag) source).getString("Material"));
            for (Tag group : saved.getList("Groups", Tag.TAG_COMPOUND))
                assertEquals(material.name(), ((CompoundTag) group).getString("Material"));
            var loaded = WindowBoardsData.load(saved);
            assertEquals(saved, loaded.save(new CompoundTag()));
            assertEquals(2, loaded.groups().size());
            for (var group : loaded.groups()) {
                CompoundTag packet = group.save();
                assertEquals(material.name(), packet.getString("Material"));
                assertEquals(material.name(), packet.getCompound("Remnant").getCompound("Source").getString("Material"));
                assertEquals(group, BoardGroup.load(packet));
                assertEquals(BoardLayout.visible(BoardLayout.generate(group), group, Set.of()),
                        BoardLayout.visible(BoardLayout.generate(data.group(group.id())), data.group(group.id()), Set.of()));
            }
        }
    }
    @Test
    @EnabledIfEnvironmentVariable(named = "BARRICADE_WINDOW_DATA_FIXTURE", matches = ".+")
    void unreadableStoreResetsInATemporaryCopyAndAllowsBothMaterialsWithoutChangingTheWorld() throws Exception {
        Path original = Path.of(System.getenv("BARRICADE_WINDOW_DATA_FIXTURE"));
        byte[] before = Files.readAllBytes(original);
        Path copy = directory.resolve(WindowBoardsData.NAME + ".dat");
        Files.copy(original, copy);
        CompoundTag wrapper = NbtIo.readCompressed(copy.toFile());
        CompoundTag unreadable = wrapper.getCompound("data");
        unreadable.putInt("Format", -1);
        NbtIo.writeCompressed(wrapper, copy.toFile());
        assertThrows(IllegalArgumentException.class, () -> WindowBoardsData.load(unreadable));
        var storage = new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer());
        var data = WindowBoardsData.open(storage, copy);
        assertTrue(data.groups().isEmpty());
        assertTrue(data.isDirty());
        var wood = data.place(new BlockPos(0, 4096, 0), Direction.NORTH, identity(10001), 42);
        var iron = data.place(new BlockPos(0, 4096, 2), Direction.NORTH, identity(10002), 99, SealMaterial.IRON);
        assertEquals(SealMaterial.WOOD, data.group(wood.id()).material());
        assertEquals(SealMaterial.IRON, data.group(iron.id()).material());
        assertEquals(2, data.groups().size());
        storage.save();
        assertArrayEquals(before, Files.readAllBytes(original), "Never modify the real world fixture");
        var reloaded = WindowBoardsData.open(new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer()), copy);
        assertEquals(data.save(new CompoundTag()), reloaded.save(new CompoundTag()));
        System.out.println("Window fixture temporary copy: unreadable data reset; wood and iron placement both accepted");
    }
    @Test void partialDamagePreservesGeometryButCompleteDestructionDeletesAllGroupInformation() {
        WindowBoardsData data = new WindowBoardsData();
        UUID id = data.place(BlockPos.ZERO, Direction.NORTH, identity(1), 42).id();
        data.place(BlockPos.ZERO.east(), Direction.NORTH, identity(2), 99);
        BoardGroup original = data.group(id);
        data.remove(BlockPos.ZERO);
        assertEquals(original.layoutKey(), data.group(id).layoutKey());
        assertEquals(BoardLayout.generate(original), BoardLayout.generate(data.group(id)));
        data = WindowBoardsData.load(data.save(new CompoundTag()));
        assertEquals(original.layoutKey(), data.group(id).layoutKey());
        long serial = data.serial();
        data.remove(BlockPos.ZERO.east());
        assertTrue(data.serial() > serial);
        assertNull(data.group(id));
        assertTrue(data.groups().isEmpty());
        assertNull(data.activeAt(BlockPos.ZERO));
        assertNull(data.activeAt(BlockPos.ZERO.east()));
        assertTrue(data.activeInChunk(ChunkPos.asLong(0, 0)).isEmpty());
        assertTrue(data.visible(Set.of(ChunkPos.asLong(0, 0))).isEmpty());
        data = WindowBoardsData.load(data.save(new CompoundTag()));
        assertNull(data.group(id));
        assertTrue(data.groups().isEmpty());
        var replacement = data.place(BlockPos.ZERO, Direction.NORTH, identity(3), 123);
        assertTrue(replacement.geometryChanged());
        assertEquals(identity(3), replacement.id());
        assertEquals(123, data.group(replacement.id()).seed());
        assertEquals(Set.of(new BoardCell(0, 0)), data.group(replacement.id()).shape());
        assertTrue(data.group(replacement.id()).damage().isEmpty());
        assertEquals(identity(4), data.place(BlockPos.ZERO.east(2), Direction.NORTH, identity(4), 456).id(),
                "No destroyed baseline may reconnect distant new boards");
    }
    @Test void growthAndMergingRebaseSurvivorsClearDamageAndKeepTheSelectedSeed() {
        WindowBoardsData data = new WindowBoardsData();
        UUID a = data.place(BlockPos.ZERO, Direction.NORTH, identity(1), 42).id();
        data.place(BlockPos.ZERO.above(), Direction.NORTH, identity(2), 77);
        UUID b = data.place(BlockPos.ZERO.east(2), Direction.NORTH, identity(3), 123).id();
        data.remove(BlockPos.ZERO.above());
        data.place(BlockPos.ZERO.east(), Direction.NORTH, identity(4), 456);
        assertNull(data.group(b));
        assertEquals(42, data.group(a).seed());
        assertEquals(Set.of(new BoardCell(0, 0), new BoardCell(1, 0), new BoardCell(2, 0)), data.group(a).shape());
        assertTrue(data.group(a).damage().isEmpty());
        assertNull(data.activeAt(BlockPos.ZERO.above()));
        assertEquals(a, data.activeAt(BlockPos.ZERO.east(2)));
    }
    @Test void fillingOneHoleMaintainsTheGroupWithoutResurrectingOtherHoles() {
        WindowBoardsData data = new WindowBoardsData();
        UUID id = data.place(BlockPos.ZERO, Direction.NORTH, identity(1), 42).id();
        data.place(BlockPos.ZERO.east(), Direction.NORTH, identity(2), 99);
        data.place(BlockPos.ZERO.east(2), Direction.NORTH, identity(3), 99);
        data.remove(BlockPos.ZERO);
        data.remove(BlockPos.ZERO.east());
        var repair = data.place(BlockPos.ZERO.east(), Direction.NORTH, identity(4), 123);
        assertTrue(repair.geometryChanged());
        assertEquals(id, repair.id());
        assertEquals(42, data.group(id).seed());
        assertEquals(Set.of(new BoardCell(1, 0), new BoardCell(2, 0)), data.group(id).shape());
        assertTrue(data.group(id).damage().isEmpty());
        assertNull(data.activeAt(BlockPos.ZERO));
        assertEquals(Set.of(BlockPos.ZERO.east(), BlockPos.ZERO.east(2)), data.activeInChunk(ChunkPos.asLong(0, 0)));
        assertEquals(data.group(id), WindowBoardsData.load(data.save(new CompoundTag())).group(id));
    }
    @Test void expandingAWindowPreservesItsUnrepairedCenterAsAnOriginalHole() {
        WindowBoardsData data = new WindowBoardsData();
        UUID id = null;
        int serial = 1;
        for (int v = 0; v < 3; v++) for (int u = 0; u < 3; u++)
            id = data.place(new BlockPos(u, v, 0), Direction.NORTH, identity(serial++), 42).id();
        data.remove(new BlockPos(1, 1, 0));
        data.place(new BlockPos(3, 1, 0), Direction.NORTH, identity(serial), 99);
        BoardGroup maintained = data.group(id);
        assertTrue(maintained.damage().isEmpty());
        assertEquals(9, maintained.shape().size());
        assertFalse(maintained.shape().contains(new BoardCell(1, 1)));
        assertNull(data.activeAt(new BlockPos(1, 1, 0)));
        assertEquals(id, data.activeAt(new BlockPos(3, 1, 0)));
        var layout = BoardLayout.generate(maintained);
        assertFalse(layout.planks().isEmpty());
        assertEquals(layout, BoardLayout.generate(BoardGroup.load(maintained.save())));
    }
    @Test void destroyedNeighborsCannotConnectANewPlacementThroughHistoricalCells() {
        WindowBoardsData data = new WindowBoardsData();
        UUID original = data.place(BlockPos.ZERO, Direction.NORTH, identity(1), 42).id();
        data.place(BlockPos.ZERO.east(), Direction.NORTH, identity(2), 42);
        data.place(BlockPos.ZERO.east(2), Direction.NORTH, identity(3), 42);
        data.remove(BlockPos.ZERO.east());
        data.remove(BlockPos.ZERO.east(2));
        BoardGroup damaged = data.group(original);
        UUID separate = data.place(BlockPos.ZERO.east(3), Direction.NORTH, identity(4), 99).id();
        assertNotEquals(original, separate);
        assertEquals(damaged, data.group(original));
        assertEquals(2, data.groups().size());
        data.place(BlockPos.ZERO.east(), Direction.NORTH, identity(5), 99);
        data.place(BlockPos.ZERO.east(2), Direction.NORTH, identity(6), 99);
        assertNull(data.group(separate));
        assertEquals(original, data.activeAt(BlockPos.ZERO.east(3)));
        assertTrue(data.group(original).damage().isEmpty());
    }
    @Test void anExistingLiveObservationNeverCountsAsMaintenance() {
        WindowBoardsData data = new WindowBoardsData();
        UUID id = data.place(BlockPos.ZERO, Direction.NORTH, identity(1), 42).id();
        data.place(BlockPos.ZERO.east(), Direction.NORTH, identity(2), 99);
        data.remove(BlockPos.ZERO.east());
        CompoundTag before = data.save(new CompoundTag());
        data.reconcileRemovals(Map.of(BlockPos.ZERO, Direction.NORTH));
        var observation = data.place(BlockPos.ZERO, Direction.NORTH, identity(3), 123);
        assertEquals(id, observation.id());
        assertFalse(observation.geometryChanged());
        assertEquals(before, data.save(new CompoundTag()));
    }
    @Test void allObservedRemovalsAreAppliedBeforeMaintenanceInTheSameBatch() {
        WindowBoardsData data = new WindowBoardsData();
        UUID id = data.place(BlockPos.ZERO, Direction.NORTH, identity(1), 42).id();
        data.place(BlockPos.ZERO.east(), Direction.NORTH, identity(2), 99);
        Map<BlockPos, Direction> observed = new LinkedHashMap<>();
        observed.put(BlockPos.ZERO.west(), Direction.NORTH);
        observed.put(BlockPos.ZERO.east(), null);
        data.reconcileRemovals(observed);
        data.place(BlockPos.ZERO.west(), Direction.NORTH, identity(3), 123);
        assertEquals(Set.of(new BoardCell(-1, 0), new BoardCell(0, 0)), data.group(id).shape());
        assertTrue(data.group(id).damage().isEmpty());
        assertNull(data.activeAt(BlockPos.ZERO.east()));
        assertEquals(id, data.activeAt(BlockPos.ZERO.west()));
    }
    @Test void destroyingTheLastCellBeforeANearbyAdditionStartsANewIdentityAndSeed() {
        WindowBoardsData data = new WindowBoardsData();
        UUID old = data.place(BlockPos.ZERO, Direction.NORTH, identity(1), 42).id();
        Map<BlockPos, Direction> observed = new LinkedHashMap<>();
        observed.put(BlockPos.ZERO.east(), Direction.NORTH);
        observed.put(BlockPos.ZERO, null);
        data.reconcileRemovals(observed);
        var placement = data.place(BlockPos.ZERO.east(), Direction.NORTH, identity(2), 99);
        assertNull(data.group(old));
        assertEquals(identity(2), placement.id());
        assertEquals(99, data.group(placement.id()).seed());
        assertEquals(Set.of(new BoardCell(1, 0)), data.group(placement.id()).shape());
    }
    @Test void breakingAndReplacingTheSameCellBeforeReconciliationStartsAFreshGroup() {
        WindowBoardsData data = new WindowBoardsData();
        UUID old = data.place(BlockPos.ZERO, Direction.NORTH, identity(1), 42).id();
        data.remove(BlockPos.ZERO);
        // The final tick snapshot alone cannot reveal the intervening committed removal.
        data.reconcileRemovals(Map.of(BlockPos.ZERO, Direction.NORTH));
        var replaced = data.place(BlockPos.ZERO, Direction.NORTH, identity(2), 99);
        assertNull(data.group(old));
        assertEquals(identity(2), replaced.id());
        assertEquals(99, data.group(replaced.id()).seed());
    }
    @Test void replacingALiveCellBeforeReconciliationStillMaintainsTheSurvivingGroup() {
        WindowBoardsData data = new WindowBoardsData();
        UUID id = data.place(BlockPos.ZERO, Direction.NORTH, identity(1), 42).id();
        data.place(BlockPos.ZERO.east(), Direction.NORTH, identity(2), 42);
        data.place(BlockPos.ZERO.east(2), Direction.NORTH, identity(3), 42);
        data.remove(BlockPos.ZERO);
        data.remove(BlockPos.ZERO.east());
        data.reconcileRemovals(Map.of(BlockPos.ZERO.east(), Direction.NORTH));
        var replaced = data.place(BlockPos.ZERO.east(), Direction.NORTH, identity(4), 99);
        assertEquals(id, replaced.id());
        assertTrue(replaced.geometryChanged());
        assertTrue(data.group(id).damage().isEmpty());
        assertEquals(Set.of(new BoardCell(1, 0), new BoardCell(2, 0)), data.group(id).shape());
        assertNull(data.activeAt(BlockPos.ZERO));
    }
    @Test void shrinkingTheHistoricalFootprintReleasesCapacityForActualGrowth() {
        WindowBoardsData data = new WindowBoardsData();
        UUID id = null;
        for (int u = 0; u < 16; u++)
            id = data.place(new BlockPos(u, 0, 0), Direction.NORTH, identity(u + 1), 42).id();
        for (int u = 0; u < 15; u++) data.remove(new BlockPos(u, 0, 0));
        var added = data.place(new BlockPos(16, 0, 0), Direction.NORTH, identity(17), 99);
        assertEquals(id, added.id());
        assertEquals(Set.of(new BoardCell(15, 0), new BoardCell(16, 0)), data.group(id).shape());
        assertTrue(data.group(id).damage().isEmpty());
        assertEquals(1, data.groups().size());
        assertEquals(1, data.activeInChunk(ChunkPos.asLong(0, 0)).size());
        assertEquals(1, data.activeInChunk(ChunkPos.asLong(1, 0)).size());
    }
    @Test void anOversizedBridgeNeverPartiallyMaintainsEitherNeighbor() {
        WindowBoardsData data = new WindowBoardsData();
        UUID first = null, second = null;
        for (int u = 0; u < 32; u++) {
            UUID id = data.place(new BlockPos(u, 0, 0), Direction.NORTH, identity(u + 1), 42 + u).id();
            if (u == 0) first = id;
            if (u == 16) second = id;
        }
        BoardGroup untouched = data.group(second);
        data.remove(new BlockPos(15, 0, 0));
        BoardGroup damaged = data.group(first);
        var repair = data.place(new BlockPos(15, 0, 0), Direction.NORTH, identity(33), 999);
        assertEquals(identity(33), repair.id());
        assertEquals(identity(33), data.activeAt(new BlockPos(15, 0, 0)));
        assertEquals(damaged, data.group(first));
        assertEquals(untouched, data.group(second));
        assertEquals(Set.of(new BoardCell(15, 0)), data.group(repair.id()).shape());
        assertEquals(3, data.groups().size());
    }
    @Test void maintenancePrunesFrozenSupportsThatAreNowDistantFromAllSurvivors() {
        WindowBoardsData data = new WindowBoardsData();
        UUID id = null;
        for (int u = 0; u < 5; u++)
            id = data.place(new BlockPos(u, 0, 0), Direction.NORTH, identity(u + 1), 42).id();
        data.supports(id, BoardLayoutTest.supports(data.group(id).shape()));
        assertTrue(data.group(id).supports().contains(new BoardCell(-1, 0)));
        for (int u = 0; u < 4; u++) data.remove(new BlockPos(u, 0, 0));
        data.place(new BlockPos(5, 0, 0), Direction.NORTH, identity(6), 99);
        assertEquals(Set.of(new BoardCell(4, 0), new BoardCell(5, 0)), data.group(id).shape());
        assertFalse(data.group(id).supports().contains(new BoardCell(-1, 0)));
        assertTrue(data.group(id).supports().contains(new BoardCell(4, 1)));
        assertEquals(data.group(id), BoardGroup.load(data.group(id).save()));
    }
    @Test void mergeIdentityUsesSurvivingSizeRatherThanTheDestroyedHistoricalFootprint() {
        WindowBoardsData data = new WindowBoardsData();
        UUID old = null;
        for (int u = 0; u < 5; u++)
            old = data.place(new BlockPos(u, 0, 0), Direction.NORTH, identity(u + 1), 42).id();
        for (int u = 0; u < 4; u++) data.remove(new BlockPos(u, 0, 0));
        UUID larger = data.place(new BlockPos(6, 0, 0), Direction.NORTH, identity(6), 99).id();
        data.place(new BlockPos(7, 0, 0), Direction.NORTH, identity(7), 123);
        var bridge = data.place(new BlockPos(5, 0, 0), Direction.NORTH, identity(8), 999);
        assertEquals(larger, bridge.id());
        assertNull(data.group(old));
        assertEquals(99, data.group(larger).seed());
        assertEquals(BoardLayoutTest.rectangle(4, 0, 4, 1), data.group(larger).shape());
        assertTrue(data.group(larger).damage().isEmpty());
    }
    @Test void mixedPlacementDamageFacingChangesAndReloadsNeverCreateGhostCellsOrDeadGroups() {
        WindowBoardsData data = new WindowBoardsData();
        Map<BlockPos, Direction> actual = new HashMap<>();
        Random random = new Random(42);
        for (int step = 0; step < 600; step++) {
            BlockPos pos = new BlockPos(random.nextInt(7) - 3, random.nextInt(7) - 3, 0);
            if (random.nextInt(3) == 0) {
                UUID old = data.activeAt(pos);
                BoardGroup before = old == null ? null : data.group(old);
                data.remove(pos);
                actual.remove(pos);
                if (old != null && data.group(old) != null) assertEquals(before.layoutKey(), data.group(old).layoutKey());
            } else {
                Direction facing = Direction.values()[random.nextInt(6)];
                var placed = data.place(pos, facing, identity(step + 1), random.nextLong());
                actual.put(pos, facing);
                if (placed.geometryChanged()) assertTrue(data.group(placed.id()).damage().isEmpty());
            }
            if (step % 25 == 0) {
                CompoundTag saved = data.save(new CompoundTag());
                data = WindowBoardsData.load(saved);
                assertEquals(saved, data.save(new CompoundTag()));
            }
            int count = 0;
            for (BoardGroup group : data.groups()) {
                assertTrue(group.damage().size() < group.shape().size());
                for (BoardCell cell : group.shape()) if (group.alive(cell)) {
                    BlockPos block = group.plane().block(cell);
                    assertEquals(group.plane().facing(), actual.get(block));
                    assertEquals(group.id(), data.activeAt(block));
                    count++;
                }
            }
            assertEquals(actual.size(), count);
        }
        for (BlockPos pos : actual.keySet()) data.remove(pos);
        assertTrue(data.groups().isEmpty());
        assertTrue(WindowBoardsData.load(data.save(new CompoundTag())).groups().isEmpty());
    }
    @Test void orientationAndPlaneSeparateAdjacentWindowsAndAllowSkylightGrowth() {
        WindowBoardsData data = new WindowBoardsData();
        UUID up = data.place(BlockPos.ZERO, Direction.UP, identity(1), 1).id();
        assertEquals(up, data.place(BlockPos.ZERO.east(), Direction.UP, identity(2), 2).id());
        assertEquals(up, data.place(BlockPos.ZERO.south(), Direction.UP, identity(3), 3).id());
        UUID down = data.place(BlockPos.ZERO.west(), Direction.DOWN, identity(4), 4).id();
        UUID nextPlane = data.place(BlockPos.ZERO.above(), Direction.UP, identity(5), 5).id();
        assertNotEquals(up, down);
        assertNotEquals(up, nextPlane);
        data.place(BlockPos.ZERO, Direction.NORTH, identity(6), 6);
        assertTrue(data.group(up).layoutDamage().contains(new BoardCell(0, 0)));
        assertNotEquals(data.activeAt(BlockPos.ZERO.east()), data.activeAt(BlockPos.ZERO.south()));
    }
    @Test void oversizedGrowthStartsAnotherGroupRatherThanBreakingPlacementOrExistingData() {
        WindowBoardsData data = new WindowBoardsData();
        UUID first = null;
        for (int x = 0; x <= BoardGroup.MAX_SPAN; x++) {
            UUID id = data.place(new BlockPos(x, 0, 0), Direction.NORTH, identity(x + 1), 42).id();
            if (first == null) first = id;
            if (x == BoardGroup.MAX_SPAN) assertNotEquals(first, id);
        }
        assertEquals(2, data.groups().size());
        assertEquals(BoardGroup.MAX_SPAN, data.group(first).shape().size());
        assertEquals(data.save(new CompoundTag()), WindowBoardsData.load(data.save(new CompoundTag())).save(new CompoundTag()));
    }
    @Test void watchVisibilityIncludesOverhangAcrossChunkEdgesAndRetiresFullyDamagedGroups() {
        WindowBoardsData data = new WindowBoardsData();
        BlockPos pos = new BlockPos(15, 64, 15);
        UUID id = data.place(pos, Direction.NORTH, identity(1), 42).id();
        assertTrue(data.visible(Set.of(ChunkPos.asLong(1, 0))).contains(id));
        assertEquals(Set.of(pos), data.activeInChunk(ChunkPos.asLong(0, 0)));
        data.remove(pos);
        assertTrue(data.visible(Set.of(ChunkPos.asLong(0, 0))).isEmpty());
        assertNull(data.group(id));
    }
    @Test void validStoresRoundTripAndCorruptFilesAreReplacedWithFreshData() throws Exception {
        Path file = directory.resolve(WindowBoardsData.NAME + ".dat");
        DimensionDataStorage storage = new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer());
        WindowBoardsData data = WindowBoardsData.open(storage, file);
        data.place(BlockPos.ZERO, Direction.UP, identity(1), 42);
        storage.save();
        assertEquals(data.save(new CompoundTag()), WindowBoardsData.open(
                new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer()), file).save(new CompoundTag()));
        byte[] corrupt = {1, 2, 3, 4};
        Files.write(file, corrupt);
        DimensionDataStorage replacement = new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer());
        var cleared = WindowBoardsData.open(replacement, file);
        assertTrue(cleared.groups().isEmpty());
        assertTrue(cleared.isDirty());
        assertNotEquals(data.world(), cleared.world());
        replacement.save();
        assertEquals(cleared.save(new CompoundTag()), NbtIo.readCompressed(file.toFile()).getCompound("data"));
    }
    @Test void missingAndUnknownFormatsAreClearedAndSavedUsingTheCurrentFormat() throws Exception {
        Path file = directory.resolve(WindowBoardsData.NAME + ".dat");
        for (boolean missing : new boolean[]{true, false}) {
            CompoundTag wrapper = new CompoundTag(), saved = new WindowBoardsData().save(new CompoundTag());
            if (missing) saved.remove("Format");
            else saved.putInt("Format", 999);
            wrapper.put("data", saved);
            wrapper.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
            NbtIo.writeCompressed(wrapper, file.toFile());
            DimensionDataStorage storage = new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer());
            var cleared = WindowBoardsData.open(storage, file);
            assertTrue(cleared.groups().isEmpty());
            assertTrue(cleared.isDirty());
            storage.save();
            assertEquals(cleared.save(new CompoundTag()), NbtIo.readCompressed(file.toFile()).getCompound("data"));
        }
        assertThrows(IllegalArgumentException.class, () -> WindowBoardsData.load(new CompoundTag()));
    }
    @Test void invalidListTypesAndCountersCannotSilentlyLoadAnEmptyStore() {
        CompoundTag saved = new WindowBoardsData().save(new CompoundTag());
        ListTag invalid = new ListTag();
        invalid.add(IntTag.valueOf(1));
        saved.put("Groups", invalid);
        assertThrows(IllegalArgumentException.class, () -> WindowBoardsData.load(saved));
        CompoundTag negative = new WindowBoardsData().save(new CompoundTag());
        negative.putLong("Serial", -1);
        assertThrows(IllegalArgumentException.class, () -> WindowBoardsData.load(negative));
    }
    @Test void aSoleRemainingSourceKeepsItsAnalysisDuringDamageAndReleasesItAtFinalDeletion() throws Exception {
        WindowBoardsData data = new WindowBoardsData();
        for (int u = 0; u < 7; u++) data.place(new BlockPos(u, 0, 0), Direction.NORTH, identity(u + 1), 42);
        data.remove(new BlockPos(3, 0, 0));
        var field = WindowBoardsData.class.getDeclaredField("layouts");
        field.setAccessible(true);
        Map<?, ?> layouts = (Map<?, ?>) field.get(data);
        assertEquals(1, layouts.size());
        Object analysis = layouts.values().iterator().next();
        for (int u : new int[]{6, 5, 4, 0, 1}) {
            data.remove(new BlockPos(u, 0, 0));
            assertEquals(1, layouts.size());
            assertSame(analysis, layouts.values().iterator().next());
        }
        data.remove(new BlockPos(2, 0, 0));
        assertTrue(layouts.isEmpty());
    }
}
