package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class FieldViewCacheTest {
    @Test void nearestQueryBatchesAreBoundedCompleteAndIndependentOfInputIterationOrder() {
        Set<BlockPos> remaining = new HashSet<>();
        for (int x = -10; x < 10; x++) for (int z = -10; z < 10; z++) remaining.add(new BlockPos(x, 80, z));
        BlockPos focus = new BlockPos(-7, 80, 5);
        Set<BlockPos> first = FieldViewCache.nextBatch(remaining, focus, 64);
        assertEquals(64, first.size()); assertTrue(first.contains(focus));
        assertEquals(first, FieldViewCache.nextBatch(new TreeSet<>(remaining), focus, 64));
        for (BlockPos a : first) for (BlockPos b : remaining)
            if (!first.contains(b)) assertTrue(a.distSqr(focus) <= b.distSqr(focus));
        Set<BlockPos> visited = new HashSet<>();
        while (!remaining.isEmpty()) {
            Set<BlockPos> batch = FieldViewCache.nextBatch(remaining, focus, 64);
            assertTrue(batch.size() <= 64);
            batch.forEach(cell -> assertTrue(visited.add(cell)));
            remaining.removeAll(batch);
        }
        assertEquals(400, visited.size());
    }
    @Test void movingScopeEvictsOldCellsButKeepsNearbyOverhangsAndNeverMarksMissingCellsReady() {
        var rules = PaletteSpec.create(List.of(new PileModels.Candidate("a", "minecraft:stone", 1)), "test");
        var definition = FieldDefinition.create(42, rules);
        var model = new PileModel("a", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
        BlockPos old = new BlockPos(-10000, 80, 10000), here = new BlockPos(-16, 80, 16);
        FieldObject overhang = object(here.west(), definition, model), distant = object(old, definition, model);
        FieldViewCache cache = new FieldViewCache();
        cache.accept(Set.of(old, here), List.of(distant, overhang));
        cache.retain(Set.of(here));
        assertEquals(1, cache.queriedCells());
        assertEquals(List.of(overhang), List.copyOf(cache.objects()));
        assertTrue(cache.ready(Set.of(here)));
        assertFalse(cache.ready(Set.of(here, here.east())));
        assertEquals(Set.of(here.east()), cache.missing(Set.of(here, here.east())));
        cache.accept(Set.of(here.east()), List.of(overhang));
        assertSame(overhang, cache.objects().iterator().next());
        cache.retain(Set.of());
        assertEquals(0, cache.queriedCells());
        assertTrue(cache.objects().isEmpty());
    }
    @Test void geometricChangesInvalidateBoundedRegionsButDamageAndEmbeddingDoNotInvalidateTheBaseline() {
        var rules = PaletteSpec.create(List.of(new PileModels.Candidate("a", "minecraft:stone", 1)), "test");
        var definition = FieldDefinition.create(42, rules);
        var model = new PileModel("a", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
        BlockPos here = BlockPos.ZERO, far = new BlockPos(1000, 0, 0);
        var nearby = object(here, definition, model);
        var distant = object(far, definition, model);
        FieldViewCache view = new FieldViewCache();
        view.synchronizeBaseline(Set.of(here, far));
        view.accept(Set.of(here, far), List.of(nearby, distant));
        view.synchronizeBaseline(Set.of(here, far));
        assertTrue(view.ready(Set.of(here, far)));
        view.synchronizeBaseline(Set.of(here, here.east(), far));
        assertFalse(view.ready(Set.of(here)));
        assertTrue(view.ready(Set.of(far)));
        assertSame(distant, view.objects().iterator().next());
        assertEquals(1, view.objects().size());
    }
    private static FieldObject object(BlockPos cell, FieldDefinition definition, PileModel model) {
        return new FieldObject(new FieldObject.Key("test", definition, "g",
                new GridPos(cell.getX(), cell.getY(), cell.getZ()), 2, 0), cell,
                PilePiece.create(model, .5f, .5f, .5f, 1, new Quaternionf()));
    }
}
