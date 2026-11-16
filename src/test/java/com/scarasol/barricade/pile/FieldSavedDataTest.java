package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import com.scarasol.barricade.recipe.FieldDefinition;
import com.scarasol.barricade.recipe.PaletteSpec;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.nio.file.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FieldSavedDataTest {
    private static final UUID A = new UUID(1, 2), B = new UUID(3, 4), C = new UUID(5, 6);
    private static final BlockPos P = new BlockPos(0, 80, 0), Q = P.east(32), R = P.east(64);
    private static final PaletteSpec RULES = PaletteSpec.create(List.of(
            new PileModels.Candidate("stone", "minecraft:stone", 1)), "test");
    @TempDir Path directory;

    @BeforeEach void initializeMinecraft() { MemoryFixture.create(false); }

    private Path file() { return directory.resolve("barricade_embedded_v1.dat"); }
    private DimensionDataStorage storage() {
        return new DimensionDataStorage(directory.toFile(), DataFixers.getDataFixer());
    }
    private PileData read(DimensionDataStorage storage) {
        return PileData.loadCurrent(storage, file(), ignored -> RULES);
    }
    private void write(CompoundTag data) throws Exception {
        CompoundTag wrapper = new CompoundTag();
        wrapper.put("data", data);
        wrapper.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        NbtIo.writeCompressed(wrapper, file().toFile());
    }
    private PileData threeRegions() {
        PileData data = new PileData();
        data.add(P, A, 42, RULES);
        data.add(P.east(), A, 42, RULES);
        data.addEmbedded(P);
        data.remove(P.east());
        data.add(Q, B, 43, RULES);
        data.addEmbedded(Q);
        data.add(R, C, 44, RULES);
        data.addEmbedded(R);
        return data;
    }

    @Test void regionSavesAndScopedSyncKeepOnlyGenerationDamageAndEmbeddingInputs() {
        var data = threeRegions();
        var saved = data.save(new CompoundTag());
        assertEquals(saved, PileData.load(saved).save(new CompoundTag()));
        for (Tag entry : saved.getList("Regions", Tag.TAG_COMPOUND))
            assertFalse(((CompoundTag) entry).contains("PlacementTerrain"));
        var region = data.snapshot(A, Set.of(ChunkPos.asLong(P)), Set.of(ChunkPos.asLong(P)));
        assertFalse(region.save().contains("PlacementTerrain"));
        assertTrue(region.mask().contains(P.east()));
        assertTrue(region.baseline().contains(P.east()));
        assertNotNull(region.embedded().get(P));
    }

    @Test void unsupportedFormatRegistersFreshDirtyDataAndNormalPlacementCanContinue() throws Exception {
        CompoundTag old = threeRegions().save(new CompoundTag());
        old.putInt("Format", 1);
        write(old);
        var storage = storage();
        PileData reset = read(storage);
        assertTrue(reset.isDirty());
        assertEquals(3, reset.regionCount());
        assertEquals(3, reset.cellCount());
        assertFalse(reset.hasEmbedded());
        assertNotEquals(old.getUUID("World"), reset.world());
        reset.add(Q.east(), B, 99, RULES);
        storage.save();
        var restored = read(storage());
        assertEquals(reset.save(new CompoundTag()), restored.save(new CompoundTag()));
        assertFalse(restored.isDirty());
    }

    @Test void corruptCompressedFileIsReplacedByNormalSaving() throws Exception {
        Files.write(file(), new byte[]{1, 2, 3});
        var storage = storage();
        PileData reset = read(storage);
        assertTrue(reset.isDirty());
        assertEquals(0, reset.regionCount());
        storage.save();
        assertEquals(reset.save(new CompoundTag()), NbtIo.readCompressed(file().toFile()).getCompound("data"));
        assertFalse(read(storage()).isDirty());
    }

    @Test void malformedGlobalIdentityOrCountersAlsoResetTheStore() throws Exception {
        for (String field : List.of("World", "NextEmbedded", "Serial", "Samples")) {
            CompoundTag saved = threeRegions().save(new CompoundTag());
            if (field.equals("World")) saved.putString(field, "not-a-uuid");
            else saved.putLong(field, -1);
            write(saved);
            var storage = storage();
            PileData reset = read(storage);
            assertTrue(reset.isDirty(), field);
            assertEquals(3, reset.cellCount(), field);
            assertFalse(reset.hasEmbedded(), field);
            storage.save();
            assertFalse(read(storage()).isDirty(), field);
        }
    }

    @Test void obsoleteAlgorithmRebuildsOnlyItsRegionAndPersistsCurrentInputs() throws Exception {
        PileData original = threeRegions();
        CompoundTag saved = original.save(new CompoundTag());
        saved.getList("Regions", Tag.TAG_COMPOUND).getCompound(1).putInt("Algorithm", FieldDefinition.ALGORITHM - 1);
        write(saved);
        var storage = storage();
        PileData restored = read(storage);
        assertTrue(restored.isDirty());
        assertEquals(original.world(), restored.world());
        assertEquals(original.snapshot(A), restored.snapshot(A));
        assertEquals(original.snapshot(C), restored.snapshot(C));
        assertNull(restored.snapshot(B));
        assertTrue(restored.contains(Q));
        assertNull(restored.embeddedAt(Q));
        UUID rebuilt = restored.regionAt(Q);
        assertNotEquals(B, rebuilt);
        assertEquals(FieldDefinition.ALGORITHM, restored.snapshot(rebuilt).definition().algorithm());
        assertEquals(RULES.id(), restored.snapshot(rebuilt).definition().palette());
        assertTrue(restored.snapshot(rebuilt).mask().isEmpty());
        assertEquals(3, restored.cellCount());
        assertEquals(3, restored.regionCount());
        assertEquals(Set.of(A, C, rebuilt), restored.visible(Set.of(ChunkPos.asLong(Q))));
        storage.save();
        PileData reloaded = read(storage());
        assertEquals(restored.save(new CompoundTag()), reloaded.save(new CompoundTag()));
        assertFalse(reloaded.isDirty());
        reloaded.add(Q.east(), B, 99, RULES);
        assertEquals(rebuilt, reloaded.regionAt(Q.east()));
        assertNotNull(reloaded.addEmbedded(Q));
    }

    @Test void overlappingRejectedRegionLeavesNoPartialCellOrChunkIndexes() {
        PileData original = threeRegions();
        CompoundTag saved = original.save(new CompoundTag());
        CompoundTag bad = saved.getList("Regions", Tag.TAG_COMPOUND).getCompound(1);
        bad.putLongArray("Domain", new long[]{P.asLong(), Q.asLong()});
        bad.putLongArray("Baseline", new long[]{P.asLong(), Q.asLong()});
        PileData restored = PileData.readCurrent(saved, ignored -> RULES);
        assertTrue(restored.isDirty());
        assertEquals(original.snapshot(A), restored.snapshot(A));
        assertEquals(original.snapshot(C), restored.snapshot(C));
        assertTrue(restored.contains(Q));
        assertNull(restored.snapshot(B));
        assertEquals(Set.of(A, C, restored.regionAt(Q)), restored.visible(Set.of(ChunkPos.asLong(Q))));
        assertEquals(Set.of(Q), restored.cellsInChunk(ChunkPos.asLong(Q)));
        assertEquals(3, restored.regionCount());
    }

    @Test void rejectedEmbeddedInstancesDoNotReserveIdsOrIncreaseTheEmbeddedCount() {
        PileData original = threeRegions();
        original.add(Q.east(), B, 43, RULES);
        original.addEmbedded(Q.east());
        CompoundTag saved = original.save(new CompoundTag());
        ListTag regions = saved.getList("Regions", Tag.TAG_COMPOUND);
        ListTag members = regions.getCompound(1).getList("Embedded", Tag.TAG_COMPOUND);
        long duplicate = members.getCompound(0).getLong("Instance");
        members.getCompound(1).putLong("Instance", duplicate);
        regions.getCompound(2).getList("Embedded", Tag.TAG_COMPOUND).getCompound(0).putLong("Instance", duplicate);
        PileData restored = PileData.readCurrent(saved, ignored -> RULES);
        assertNull(restored.snapshot(B));
        assertTrue(restored.contains(Q));
        assertTrue(restored.contains(Q.east()));
        assertNull(restored.embeddedAt(Q));
        assertNull(restored.embeddedAt(Q.east()));
        assertEquals(duplicate, restored.embeddedAt(R).instance());
        assertTrue(restored.hasEmbedded());
        assertTrue(restored.removeEmbedded(P));
        assertTrue(restored.removeEmbedded(R));
        assertFalse(restored.hasEmbedded());
    }

    @Test void unreadablePaletteRebuildsOnlyDependentRegionsWithCurrentRules() throws Exception {
        PileData original = threeRegions();
        var other = PaletteSpec.create(List.of(
                new PileModels.Candidate("glass", "minecraft:glass", 1)), "other");
        original.regenerate(Q, other, 100, new UUID(7, 8));
        CompoundTag saved = original.save(new CompoundTag());
        for (Tag entry : saved.getList("Palettes", Tag.TAG_COMPOUND)) {
            CompoundTag palette = (CompoundTag) entry;
            if (palette.getString("Id").equals(other.id())) {
                CompoundTag fallback = new CompoundTag();
                fallback.put("Fallback", new CompoundTag());
                palette.put("Fallback", fallback);
            }
        }
        write(saved);
        var storage = storage();
        PileData restored = read(storage);
        assertTrue(restored.isDirty());
        assertEquals(original.snapshot(A), restored.snapshot(A));
        assertEquals(original.snapshot(C), restored.snapshot(C));
        assertEquals(RULES, restored.palette(RULES.id()));
        assertNull(restored.palette(other.id()));
        assertTrue(restored.contains(Q));
        assertEquals(RULES.id(), restored.snapshot(restored.regionAt(Q)).definition().palette());
        storage.save();
        assertFalse(read(storage()).isDirty());
    }

    @Test void duplicateRegionOrPaletteDoesNotOverwriteTheValidEntry() {
        PileData original = threeRegions();
        CompoundTag saved = original.save(new CompoundTag());
        ListTag regions = saved.getList("Regions", Tag.TAG_COMPOUND);
        CompoundTag duplicate = regions.getCompound(0).copy();
        duplicate.putLong("Revision", 100);
        regions.add(duplicate);
        ListTag palettes = saved.getList("Palettes", Tag.TAG_COMPOUND);
        duplicate = palettes.getCompound(0).copy();
        duplicate.putString("SourceTag", "test:duplicate");
        palettes.add(duplicate);
        PileData restored = PileData.readCurrent(saved, ignored -> RULES);
        assertTrue(restored.isDirty());
        assertEquals(original.save(new CompoundTag()), restored.save(new CompoundTag()));
    }

    @Test void missingDataIsCreatedAndValidCurrentDataRoundTripsThroughTheRealStorage() throws Exception {
        var storage = storage();
        PileData data = read(storage);
        data.add(P, A, 42, RULES);
        storage.save();
        assertTrue(Files.isRegularFile(file()));
        var restored = read(storage());
        assertEquals(data.save(new CompoundTag()), restored.save(new CompoundTag()));
        assertFalse(restored.isDirty());
    }

    @Test void serverServiceHasNoDataFailureDisconnectPath() throws Exception {
        var node = new org.objectweb.asm.tree.ClassNode();
        try (var input = getClass().getResourceAsStream("/com/scarasol/barricade/pile/PileService.class")) {
            assertNotNull(input);
            new org.objectweb.asm.ClassReader(input).accept(node, 0);
        }
        for (var method : node.methods) for (var instruction : method.instructions)
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call)
                assertNotEquals("disconnect", call.name, method.name);
    }
}
