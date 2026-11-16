package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.recipe.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class TaggedFieldTest {
    @Test void aSmallOnlyPoolDoesNotNeedOrBorrowSkeletonMaterials() {
        var palette = PaletteSpec.create(List.of(new PileModels.Candidate("small", "minecraft:stone", 1)), "test");
        var small = new PileModel("small", "minecraft:stone", .3f, .3f, .3f, 1, List.of(CollisionBox.CUBE));
        assertFalse(small.bulk()); assertFalse(small.elongated());
        var output = new SpatialField("test", FieldDefinition.create(42, palette),
                List.of(small), FieldOracle::solve).query(Set.of(BlockPos.ZERO));
        assertFalse(output.isEmpty());
        assertTrue(output.stream().allMatch(object -> object.piece().model().id().equals("small")));
    }
    @Test void adjacentTagsAllowInterpenetrationAndColdResultsRepeatable() {
        TaggedFieldChecks.verify(FieldOracle::solve);
    }
    @Test void fixedSkeletonBodiesCountAsOccupiedSpaceButNeverBecomeFineOutput() {
        var model = new PileModel("cube", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
        var plan = PackingBatch.prepare(new PackingInput(42, 0, Set.of(new GridPos(0, 0, 0)),
                List.of(model), List.of(), List.of(OrientedBox.axisAligned(0, 0, 0, 1, 1, 1)),
                3, true, Set.of(new GridPos(0, 0, 0)), List.of()));
        assertTrue(plan.initial[4] > 0);
        assertTrue(plan.decode(FieldOracle.solve(plan)).isEmpty());
    }
    @Test void foreignCellChangesPreserveCachedGeometryAndPartialQueryIdentities() {
        var palette = PaletteSpec.create(List.of(new PileModels.Candidate("a", "minecraft:stone", 1)), "test");
        var definition = FieldDefinition.create(42, palette);
        var models = List.of(new PileModel("cube", "minecraft:stone", .45f, .4f, .45f, 1, List.of(CollisionBox.CUBE)));
        BlockPos origin = new BlockPos(-5, -1, 3), far = new BlockPos(1000, 0, 0);
        Set<BlockPos> cells = Set.of(origin, origin.east(), far), walls = Set.of(origin.south(), origin.east().south());
        SpatialField field = new SpatialField("test", definition, models, FieldOracle::solve);
        var initial = field.query(cells);
        long jobs = field.metrics().jobs();
        var adjacent = field.query(cells, cells, walls);
        assertEquals(initial, adjacent);
        assertEquals(jobs, field.metrics().jobs());
        assertEquals(initial, field.query(cells, cells, Set.of(origin.above(), far.east())));
        assertEquals(jobs, field.metrics().jobs(), "Foreign growth must not schedule another solve");
        var cold = new SpatialField("test", definition, models, FieldOracle::solve);
        TreeMap<FieldObject.Key, FieldObject> partial = new TreeMap<>();
        for (BlockPos cell : cells.stream().sorted(Comparator.reverseOrder()).toList())
            cold.query(Set.of(cell), cells, walls).forEach(object -> partial.put(object.key(), object));
        assertEquals(initial, List.copyOf(partial.values()));
        assertEquals(initial, field.query(cells));
    }
}
