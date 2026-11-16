package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.recipe.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class CurrentFieldStateTest {
    private static final UUID A = new UUID(1, 2), B = new UUID(3, 4);
    private static final BlockPos P = new BlockPos(-17, 80, 1000000);
    private static final PaletteSpec RULES = PaletteSpec.create(List.of(
            new PileModels.Candidate("oak", "minecraft:oak_planks", 1)), "test");

    @Test void growthFreezesDefinitionAndDamageIsOnlySpatialState() {
        PileData data = new PileData();
        data.add(P, A, 42, RULES);
        var definition = data.snapshot(A).definition();
        data.add(P.east(), B, 99, RULES);
        assertEquals(definition, data.snapshot(A).definition());
        data.remove(P.east());
        assertTrue(data.snapshot(A).mask().contains(P.east()));
        CompoundTag once = data.save(new CompoundTag());
        for (int i = 0; i < 2000; i++) {
            data.add(P.east(), B, 99, RULES);
            data.remove(P.east());
        }
        CompoundTag repeated = data.save(new CompoundTag());
        once.remove("Serial");
        repeated.remove("Serial");
        for (Tag tag : once.getList("Regions", Tag.TAG_COMPOUND)) ((CompoundTag) tag).remove("Revision");
        for (Tag tag : repeated.getList("Regions", Tag.TAG_COMPOUND)) ((CompoundTag) tag).remove("Revision");
        assertEquals(once, repeated);
        assertEquals(data.save(new CompoundTag()), PileData.load(data.save(new CompoundTag())).save(new CompoundTag()));
    }
    @Test void mergeChoosesLargestThenFullUuidAndPreservesEmbeddedAndMask() {
        for (boolean reverse : List.of(false, true)) {
            PileData data = new PileData();
            if (reverse) data.add(P.east(2), B, 7, RULES);
            data.add(P, A, 42, RULES);
            if (!reverse) data.add(P.east(2), B, 7, RULES);
            var embedded = data.addEmbedded(P.east(2));
            data.add(P.east(), new UUID(9, 9), 3, RULES);
            assertEquals(42, data.snapshot(A).definition().seed());
            assertEquals(embedded, data.embeddedAt(P.east(2)));
            assertEquals(1, data.regionCount());
        }
        PileData data = new PileData();
        data.add(P, A, 42, RULES);
        data.add(P.east(3), B, 7, RULES);
        data.add(P.east(2), B, 7, RULES);
        data.add(P.east(), A, 42, RULES);
        assertEquals(B, data.regionAt(P));
        assertEquals(7, data.snapshot(B).definition().seed());
    }
    @Test void regenerationSeparatesOnlySelectedComponentAndEmbeddingNeverDamages() {
        PileData data = new PileData();
        for (int x = 0; x < 3; x++) data.add(P.east(x), A, 42, RULES);
        var definition = data.snapshot(A).definition();
        data.addEmbedded(P); data.updateEmbedded(P); data.removeEmbedded(P);
        assertEquals(definition, data.snapshot(A).definition());
        assertTrue(data.snapshot(A).mask().isEmpty());
        var embedded = data.addEmbedded(P);
        data.remove(P.east());
        assertEquals(1, data.regenerate(P, RULES, 99, B));
        assertEquals(embedded, data.embeddedAt(P));
        assertEquals(99, data.snapshot(B).definition().seed());
        assertTrue(data.snapshot(B).mask().isEmpty());
        assertEquals(42, data.snapshot(A).definition().seed());
        assertTrue(data.snapshot(A).mask().contains(P.east()));
    }
    @Test void sparseSectionsHandleNegativeBoundariesAndRejectOldFormats() {
        SpatialMask mask = new SpatialMask();
        for (BlockPos pos : List.of(P, P.east(), new BlockPos(-1, -1, -1), BlockPos.ZERO)) mask.set(pos);
        assertEquals(mask, SpatialMask.load(mask.save()));
        assertTrue(mask.clear(P));
        assertFalse(mask.contains(P));
        CompoundTag old = new PileData().save(new CompoundTag());
        assertEquals(3, old.getInt("Format"));
        old.putInt("Format", 1);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> PileData.load(old)).getMessage().contains("required 3"));
    }
    @Test void baselineOccupancySurvivesDamageAndIncludesScopedDependencyCellsWithoutDisplayingThem() {
        PileData data = new PileData();
        for (int x = 0; x < 40; x++) data.add(new BlockPos(x, 80, 0), A, 42, RULES);
        Set<BlockPos> original = data.snapshot(A).baseline();
        data.remove(new BlockPos(17, 80, 0));
        assertEquals(original, data.snapshot(A).baseline());
        var loaded = PileData.load(data.save(new CompoundTag()));
        assertEquals(original, loaded.snapshot(A).baseline());
        var scoped = loaded.snapshot(A, Set.of(net.minecraft.world.level.ChunkPos.asLong(0, 0)),
                Set.of(net.minecraft.world.level.ChunkPos.asLong(0, 0), net.minecraft.world.level.ChunkPos.asLong(1, 0)));
        assertEquals(16, scoped.domain().size());
        assertEquals(32, scoped.baseline().size());
        assertTrue(scoped.baseline().contains(new BlockPos(17, 80, 0)));
        assertEquals(scoped, FieldRegion.load(scoped.save()));
        Set<BlockPos> before = loaded.snapshot(A).baseline();
        loaded.addEmbedded(new BlockPos(2, 80, 0));
        loaded.updateEmbedded(new BlockPos(2, 80, 0));
        loaded.removeEmbedded(new BlockPos(2, 80, 0));
        assertEquals(before, loaded.snapshot(A).baseline());
    }
}
