package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.recipe.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class PackingRankTest {
    private static final GridPos CELL = new GridPos(0, 0, 0);
    private static final PileModel LARGE = new PileModel("large", "minecraft:stone",
            1, 1, 1, 1, List.of(new CollisionBox(-.75f, -.75f, -.75f, 1.75f, 1.75f, 1.75f)));
    private static final PileModel SMALL = new PileModel("small", "minecraft:stone",
            .4f, .4f, .4f, 1, List.of(CollisionBox.CUBE));
    private static final Set<GridPos> ROW = Set.of(CELL, new GridPos(1, 0, 0), new GridPos(2, 0, 0));
    private static PackingBatch batch(PileModel model) {
        return PackingBatch.prepare(new PackingInput(42, 0, Set.of(CELL), List.of(model),
                List.of(), List.of(), 3, true));
    }
    @Test void impossibleBoundsAndFloorCollisionsStillRetainOneOrdinaryObject() {
        PackingBatch batch = batch(LARGE);
        int[] selected = FieldOracle.solve(batch);
        assertEquals(1, selected[0]);
        assertTrue(selected[24] > 0);
        assertEquals(1, batch.decode(selected).size());
        var piece = batch.decode(selected).get(0);
        var center = piece.piece().transformPoint(.5f, .5f, .5f);
        assertEquals(.5f, center.x, .002f);
        assertEquals(.5f, center.y, .002f);
        assertEquals(.5f, center.z, .002f);
        assertTrue(piece.colliders().stream().anyMatch(box -> box.minY() < 0),
                "Nonempty takes priority over the support floor as well as the partition bound");
        assertArrayEquals(selected, FieldOracle.solve(batch));
    }
    @Test void zeroCoverageAndZeroQuotasCannotVetoTheLastCandidate() {
        PackingBatch batch = batch(LARGE);
        Arrays.fill(batch.input, batch.input[15], batch.input[15] + 5, 0);
        Arrays.fill(batch.input, batch.input[17], batch.input[17] + 375, 100_000);
        int[] selected = FieldOracle.solve(batch);
        assertEquals(0, selected[4]);
        assertEquals(1, selected[0]);
        assertEquals(1, batch.decode(selected).size());
    }
    @Test void protectedCorridorsAreScoredRatherThanAnAbsoluteVeto() {
        PileModel small = new PileModel("small", "minecraft:stone", .4f, .4f, .4f, 1, List.of(CollisionBox.CUBE));
        PackingBatch batch = PackingBatch.prepare(new PackingInput(42, 0, Set.of(CELL),
                List.of(small), List.of(), List.of(), 3, true, Set.of(CELL),
                List.of(OrientedBox.axisAligned(-1, -1, -1, 2, 2, 2))));
        int[] selected = FieldOracle.solve(batch);
        assertEquals(1, selected[0]);
        assertTrue(selected[24] > 0);
    }
    @Test void missingPanelCorridorsCannotEraseTheLastPanelOrCreateGhostGuards() {
        PileModel panel = new PileModel("panel", "minecraft:oak_door", .2f, 1, 1, 1,
                List.of(new CollisionBox(0, 0, 0, 1, 2, 1)));
        PackingBatch batch = batch(panel);
        for (int id = 0; id < batch.size; id++) batch.input[batch.input[10] + id * PackingBatch.META + 6] = 0;
        int[] selected = FieldOracle.solve(batch);
        assertEquals(1, selected[0]);
        assertTrue(selected[24] > 0);
        assertTrue(batch.guards(selected).isEmpty(), "A retained panel must not reserve invalid exposure corridors");
        assertSame(panel, batch.decode(selected).get(0).piece().model());
    }
    @Test void successfulNormalSelectionIsByteForByteUnchangedByRankedSelection() {
        PileModel small = new PileModel("small", "minecraft:stone", .4f, .4f, .4f, 1, List.of(CollisionBox.CUBE));
        PackingBatch batch = batch(small);
        int[] selected = FieldOracle.solve(batch);
        assertTrue(selected[0] > 0);
        assertEquals(0, selected[24]);
        batch.input[21] = 0;
        assertArrayEquals(selected, FieldOracle.solve(batch));
    }
    @Test void rankingReusesNormalScratchAndOnlyAddsPerOwnerOutputSlots() {
        PackingInput input = new PackingInput(42, 0, ROW, List.of(SMALL), List.of(), List.of(), 3, true);
        PackingBatch ranked = PackingBatch.prepare(input, input.models(), 0, 1);
        PackingBatch ordinary = PackingBatch.prepare(input, input.models(), 0, 0);
        assertEquals(ordinary.candidates, ranked.candidates);
        assertTrue(ranked.size > ranked.input[7]);
        assertEquals(4 * ROW.size(), ranked.initial.length - ordinary.initial.length);
        assertEquals(4 * ROW.size(), ranked.resultWords() - ordinary.resultWords());
    }
    @Test void aThinCrossingCannotSuppressRankedObjectsInEveryUnownedCell() {
        PackingBatch batch = guardedRow(List.of(), .8f);
        for (int owner = 0; owner < batch.input[7]; owner++)
            assertEquals(25, batch.initial[batch.initial[12] + owner]);
        int[] selected = FieldOracle.solve(batch);
        assertEquals(3, selected[0]);
        assertEquals(3, selected[24]);
        assertEquals(ROW, batch.decode(selected).stream().map(RegionPiece::owner)
                .collect(java.util.stream.Collectors.toSet()));
        for (int owner = 0; owner < batch.input[7]; owner++)
            assertTrue(selected[selected[13] + owner] > 0);
        assertEquals(batch.decode(selected), batch.decode(batch.compactResult(selected)));
        assertArrayEquals(selected, FieldOracle.solve(batch));
    }
    @Test void preservedObjectsDoNotDisableRankedSelectionInOtherCells() {
        RegionPiece kept = PileGeometry.atCenter(SMALL, SMALL.collisionCenter(),
                new Vector3f(.5f, .5f, .5f), 1, new Quaternionf(), CELL);
        PackingBatch batch = guardedRow(List.of(kept), .8f);
        int[] selected = FieldOracle.solve(batch);
        assertEquals(2, selected[0]);
        assertEquals(2, selected[24]);
        var pieces = batch.decode(selected);
        assertEquals(3, pieces.size());
        assertEquals(kept, pieces.get(0));
        for (GridPos cell : ROW)
            assertEquals(1, pieces.stream().filter(piece -> piece.owner().equals(cell)).count());
    }
    @Test void sufficientExistingCoverageDoesNotCreateRedundantObjects() {
        PackingBatch batch = guardedRow(List.of(), .6f);
        assertEquals(0, batch.input[21], "Fully covered batches need no ranked GPU dispatches");
        for (int owner = 0; owner < batch.input[7]; owner++)
            assertEquals(50, batch.initial[batch.initial[12] + owner]);
        int[] selected = FieldOracle.solve(batch);
        assertEquals(0, selected[0]);
        assertEquals(0, selected[24]);
        assertTrue(batch.decode(selected).isEmpty(), "The neighboring body already fills each cell");
    }
    @Test void newRankedCoverageIsRecheckedBeforeFillingTheNextOwner() {
        PackingBatch batch = PackingBatch.prepare(new PackingInput(42, 0, ROW, List.of(LARGE),
                List.of(), List.of(), 3, true));
        int[] selected = FieldOracle.solve(batch);
        assertTrue(selected[24] > 0);
        assertTrue(selected[24] < ROW.size(), "A large retained body must count toward later cells");
        for (int owner = 0; owner < batch.input[7]; owner++)
            assertTrue(selected[selected[13] + owner] > 0 || selected[selected[12] + owner] >= 44);
    }
    @Test void rankedResultsAppendWithoutChangingAnyNormalPoseOrGuard() {
        GridPos gap = new GridPos(2, 0, 0);
        Set<GridPos> cells = Set.of(CELL, gap);
        PackingBatch batch = PackingBatch.prepare(new PackingInput(42, 0, cells, List.of(SMALL),
                List.of(), List.of(), 3, true, cells,
                List.of(OrientedBox.axisAligned(1.1f, -.5f, -.5f, 4, 1.5f, 1.5f))));
        batch.input[21] = 0;
        int[] ordinary = FieldOracle.solve(batch);
        assertTrue(ordinary[0] > 0);
        var normal = batch.decode(ordinary);
        assertTrue(normal.stream().noneMatch(piece -> piece.owner().equals(gap)));
        batch.input[21] = 1;
        batch.input[22] = batch.initial[4] + Math.max(1, batch.input[7] / 2);
        assertArrayEquals(ordinary, FieldOracle.solve(batch), "A progressing normal pass must finish before ranking");
        batch.input[22] = ordinary[4] + 1;
        int[] selected = FieldOracle.solve(batch);
        assertEquals(ordinary[0] + 1, selected[0]);
        assertEquals(1, selected[24]);
        assertArrayEquals(Arrays.copyOfRange(ordinary, ordinary[5], ordinary[25]),
                Arrays.copyOfRange(selected, selected[5], selected[25]));
        assertEquals(normal, batch.decode(selected).subList(0, normal.size()));
        assertEquals(batch.guards(ordinary), batch.guards(selected));
        assertEquals(gap, batch.decode(selected).get(normal.size()).owner());
    }
    @Test void multipleRankedPanelsDoNotReserveInvalidCorridors() {
        PileModel panel = new PileModel("panel", "minecraft:oak_door", .2f, 1, 1, 1,
                List.of(new CollisionBox(0, 0, 0, 1, 2, 1)));
        PackingBatch batch = PackingBatch.prepare(new PackingInput(42, 0, ROW, List.of(panel),
                List.of(), List.of(), 3, true));
        for (int id = 0; id < batch.size; id++) batch.input[batch.input[10] + id * PackingBatch.META + 6] = 0;
        int[] selected = FieldOracle.solve(batch);
        assertTrue(selected[24] > 1);
        assertTrue(batch.guards(selected).isEmpty());
        assertEquals(batch.guards(selected), batch.guards(batch.compactResult(selected)));
    }
    private static PackingBatch guardedRow(List<RegionPiece> kept, float bottom) {
        return PackingBatch.prepare(new PackingInput(42, 0, ROW, List.of(SMALL), kept,
                List.of(OrientedBox.axisAligned(0, bottom, 0, 3, 1, 1)), 3, true, ROW,
                List.of(OrientedBox.axisAligned(-1, -1, -1, 4, 2, 2))));
    }
    @Test void higherPrioritiesCannotBeBoughtOffByAnyLowerPriorityScore() {
        PackingBatch batch = batch(LARGE);
        int[] state = batch.initial.clone();
        LayoutKernel kernel = new LayoutKernel(batch.input, state);
        int first = state[23], second = first + 3;
        for (int[] pair : new int[][]{
                {65536, 15 * 1024 + 512 + 3},
                {1024, 512 + 3},
                {512, 3},
                {1, 0}}) {
            state[first] = pair[0]; state[second] = pair[1];
            state[first + 1] = Integer.MIN_VALUE; state[second + 1] = 0;
            state[first + 2] = 0; state[second + 2] = Integer.MAX_VALUE;
            assertTrue(kernel.rankedBefore(0, 1));
            assertFalse(kernel.rankedBefore(1, 0));
        }
        state[first] = state[second] = 0;
        state[first + 1] = -1; state[second + 1] = -2;
        assertTrue(kernel.rankedBefore(0, 1), "Partition fit precedes quotas and visual quality");
        state[first + 1] = state[second + 1] = 0;
        state[first + 2] = 536870912; state[second + 2] = 536870911;
        assertTrue(kernel.rankedBefore(0, 1), "Quota compliance precedes visual quality");
        state[first + 2] = state[second + 2] = 0;
        assertTrue(kernel.rankedBefore(0, 1));
        assertFalse(kernel.rankedBefore(1, 0), "Ties use the stable candidate id");
    }
    @Test void largeBodiesAgreeAcrossPartialQueriesGrowthReloadAndDamageWithoutResurrection() {
        PileModel longModel = new PileModel("long", "minecraft:stone",
                1, 1, 1, 1, List.of(new CollisionBox(-2.1f, 0, 0, 3.1f, .3f, .3f)));
        PaletteSpec palette = PaletteSpec.create(List.of(new PileModels.Candidate("long", "minecraft:stone", 1)), "test");
        FieldDefinition definition = FieldDefinition.create(42, palette);
        var factory = (java.util.function.Supplier<SpatialField>) () ->
                new SpatialField("test", definition, List.of(longModel), FieldOracle::solve);
        BlockPos origin = new BlockPos(-7, -60, -4);
        Set<BlockPos> domain = Set.of(origin, origin.east(3), origin.south(3));
        SpatialField field = factory.get();
        var full = field.query(domain);
        assertFalse(full.isEmpty());
        SpatialField partial = factory.get();
        Map<FieldObject.Key, FieldObject> union = new TreeMap<>();
        for (BlockPos cell : domain.stream().sorted(Comparator.reverseOrder()).toList()) {
            var selected = partial.query(Set.of(cell), domain);
            assertEquals(full.stream().filter(object -> FieldVisibility.activated(object, Set.of(cell))).toList(), selected);
            selected.forEach(object -> union.put(object.key(), object));
        }
        assertEquals(full, List.copyOf(union.values()));
        assertEquals(full, factory.get().query(domain));
        SpatialMask mask = new SpatialMask();
        domain.forEach(mask::set);
        assertTrue(full.stream().allMatch(object -> FieldVisibility.damaged(object, mask)));
        long jobs = field.metrics().jobs();
        assertEquals(full, field.query(domain));
        assertEquals(jobs, field.metrics().jobs());
        Set<BlockPos> grown = new HashSet<>(domain);
        grown.add(origin.below());
        assertEquals(factory.get().query(grown), field.query(grown));
    }
}
