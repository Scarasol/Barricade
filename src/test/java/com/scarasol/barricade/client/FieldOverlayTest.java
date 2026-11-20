package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class FieldOverlayTest {
    private static final FieldDefinition DEFINITION =
            new FieldDefinition(42, FieldDefinition.ALGORITHM, FieldDefinition.PARAMETERS, "test");
    private static final PileModel MODEL =
            new PileModel("box", "minecraft:oak_planks", .4f, .4f, .4f, 1, List.of(CollisionBox.CUBE));

    private static FieldObject object(BlockPos cell, int trial, float penetration) {
        return new FieldObject(new FieldObject.Key("test", DEFINITION, "fixed",
                new GridPos(cell.getX(), cell.getY(), cell.getZ()), 0, trial), cell,
                PilePiece.create(MODEL, .8f + penetration, .5f, .5f, 1, new Quaternionf()));
    }

    private static FieldRegion region(Set<BlockPos> domain, SpatialMask mask) {
        return new FieldRegion(new UUID(0, 1), 1, DEFINITION, domain, mask, Map.of());
    }

    @Test void tickUsesTimeInsteadOf64CandidatesAndKeepsTheSelectionAtomic() {
        var world = MemoryFixture.create(false);
        var terrain = new TerrainShapes(world, ignored -> true);
        var region = region(Set.of(BlockPos.ZERO), new SpatialMask());
        List<FieldObject> objects = new ArrayList<>();
        for (int id = 0; id < 200; id++) objects.add(object(BlockPos.ZERO, id, -.4f));
        var overlay = new FieldOverlay(region, 1, objects, List.of(region));
        assertFalse(overlay.advanceForTick(terrain, 0, () -> 2_000_000));
        assertThrows(IllegalStateException.class, overlay::visible);
        AtomicLong time = new AtomicLong(0);
        assertFalse(overlay.advanceForTick(terrain, 0, () -> time.getAndAdd(20_000)));
        assertThrows(IllegalStateException.class, overlay::visible);
        assertTrue(overlay.advanceForTick(terrain, time.get(), time::getAndIncrement));
        assertEquals(objects, overlay.visible());

        var cheap = new FieldOverlay(region, 1, objects, List.of(region));
        assertTrue(cheap.advanceForTick(terrain, 0, () -> 0));
        assertEquals(objects, cheap.visible());
    }

    @Test void tickResumesFallbackWithoutExposingUnfinishedOwners() {
        var world = MemoryFixture.create(false);
        BlockPos first = BlockPos.ZERO, second = first.south(4);
        world.context(Map.of(first.east(), Blocks.STONE.defaultBlockState(),
                second.east(), Blocks.STONE.defaultBlockState()), Map.of());
        var terrain = new TerrainShapes(world, ignored -> true);
        var region = region(Set.of(first, second), new SpatialMask());
        var worst = object(first, 0, .14f);
        var best = object(first, 1, .06f);
        var other = object(second, 0, .08f);
        var overlay = new FieldOverlay(region, 1, List.of(worst, other, best), List.of(region));
        AtomicLong time = new AtomicLong(0);
        assertFalse(overlay.advanceForTick(terrain, 0, () -> time.getAndAdd(500_000)),
                "Three candidates and only the first fallback fit");
        assertThrows(IllegalStateException.class, overlay::visible);
        assertTrue(overlay.advanceForTick(terrain, time.get(), time::getAndIncrement));
        assertEquals(Set.of(best, other), Set.copyOf(overlay.visible()));
        assertEquals(2, overlay.visible().size());
    }

    @Test void tickDoesNotSuppressAnObjectWithoutEvidenceOfAnEmbeddedConflict() {
        var world = MemoryFixture.create(false);
        BlockPos cell = BlockPos.ZERO, host = cell.east();
        world.context(Map.of(host, Blocks.STONE.defaultBlockState()), Map.of());
        var source = region(Set.of(cell), new SpatialMask());
        var embedded = new FieldRegion(new UUID(0, 2), 1, DEFINITION, Set.of(host), new SpatialMask(),
                Map.of(host, new EmbeddedBlock(host, 7, 1)));
        var object = object(cell, 0, -.4f);
        var loaded = new AtomicBoolean(false);
        var terrain = new TerrainShapes(world, pos -> !pos.equals(host) || loaded.get(), ignored -> null);
        var updating = new FieldOverlay(source, 1, List.of(object), List.of(source, embedded));
        assertTrue(updating.advanceForTick(terrain, 0, () -> 0));
        assertEquals(List.of(object), updating.visible(), "An unavailable anchor cannot establish a conflict");
        loaded.set(true);
        terrain.invalidateEmbedded();
        var refreshed = new FieldOverlay(source, 2, List.of(object), List.of(source, embedded));
        assertTrue(refreshed.advanceForTick(terrain, 0, () -> 0));
        assertEquals(List.of(object), refreshed.visible());
        assertEquals(0, world.chunkRequests());
    }

    @Test void oneThirtySecondIsAllowedAndRejectedCandidatesStayHiddenWhenOnePasses() {
        var world = MemoryFixture.create(false);
        world.context(Map.of(BlockPos.ZERO.east(), Blocks.GRASS_BLOCK.defaultBlockState()), Map.of());
        var terrain = new TerrainShapes(world, ignored -> true);
        var region = region(Set.of(BlockPos.ZERO), new SpatialMask());
        var exact = object(BlockPos.ZERO, 0, 1f / 32);
        var shallow = object(BlockPos.ZERO, 1, .02f);
        var rejected = object(BlockPos.ZERO, 2, .04f);
        var view = terrain.view(region.baseline(), Map.of());
        assertEquals(1f / 32, view.penetration(exact), .000001f);
        assertTrue(view.visible(exact));
        assertFalse(view.visible(rejected));
        for (var order : List.of(List.of(rejected, exact, shallow), List.of(exact, shallow, rejected))) {
            var overlay = new FieldOverlay(region, 1, order, List.of(region));
            while (!overlay.advance(terrain, 1, Long.MAX_VALUE)) { }
            assertEquals(List.of(exact, shallow), overlay.visible());
            assertTrue(overlay.advance(terrain, 1, Long.MAX_VALUE));
            assertEquals(2, overlay.visible().size(), "Repeated completion must not append a fallback twice");
        }
    }

    @Test void allRejectedUsesOneMinimumPerOwningCellRatherThanOneForTheWholeRegion() {
        var world = MemoryFixture.create(false);
        var second = new BlockPos(0, 0, 2);
        world.context(Map.of(BlockPos.ZERO.east(), Blocks.GRASS_BLOCK.defaultBlockState(),
                second.east(), Blocks.GRASS_BLOCK.defaultBlockState()), Map.of());
        var terrain = new TerrainShapes(world, ignored -> true);
        var region = region(Set.of(BlockPos.ZERO, second), new SpatialMask());
        var worst = object(BlockPos.ZERO, 0, .14f);
        var best = object(BlockPos.ZERO, 1, .06f);
        var other = object(second, 0, .08f);
        var overlay = new FieldOverlay(region, 1, List.of(worst, other, best), List.of(region));
        while (!overlay.advance(terrain, 1, Long.MAX_VALUE)) { }
        assertEquals(Set.of(best, other), Set.copyOf(overlay.visible()));
        assertEquals(2, overlay.visible().size());
        assertEquals(0, world.chunkRequests());
    }

    @Test void aBodyExtendingUpwardDoesNotCreateAnExtraFallbackForTheSameOwningCell() {
        var world = MemoryFixture.create(false);
        world.context(Map.of(BlockPos.ZERO.east(), Blocks.GRASS_BLOCK.defaultBlockState(),
                BlockPos.ZERO.east().above(), Blocks.GRASS_BLOCK.defaultBlockState()), Map.of());
        var terrain = new TerrainShapes(world, ignored -> true);
        var region = region(Set.of(BlockPos.ZERO), new SpatialMask());
        var passed = object(BlockPos.ZERO, 0, .02f);
        var template = object(BlockPos.ZERO, 1, .1f);
        var extending = new FieldObject(template.key(), template.owner(), template.piece().translated(0, .52f, 0));
        var overlay = new FieldOverlay(region, 1, List.of(passed, extending), List.of(region));
        assertTrue(overlay.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(List.of(passed), overlay.visible());
    }

    @Test void fallbackDoesNotResurrectDamageAndUnrelatedUnknownChunksCannotHidePlacedObjects() {
        var world = MemoryFixture.create(false);
        world.context(Map.of(BlockPos.ZERO.east(), Blocks.GRASS_BLOCK.defaultBlockState()), Map.of());
        var mask = new SpatialMask();
        mask.set(BlockPos.ZERO);
        var damaged = region(Set.of(BlockPos.ZERO), mask);
        var candidate = object(BlockPos.ZERO, 0, .1f);
        var overlay = new FieldOverlay(damaged, 1, List.of(candidate), List.of(damaged));
        assertTrue(overlay.advance(new TerrainShapes(world, ignored -> true), 1, Long.MAX_VALUE));
        assertTrue(overlay.visible().isEmpty());
        var intact = region(Set.of(BlockPos.ZERO), new SpatialMask());
        overlay = new FieldOverlay(intact, 1, List.of(candidate), List.of(intact));
        assertTrue(overlay.advance(new TerrainShapes(world, pos -> pos.getX() < 3), 1, Long.MAX_VALUE));
        assertEquals(List.of(candidate), overlay.visible());
    }

    @Test void equalScoresChooseTheSameIdentityRegardlessOfCandidateOrder() {
        var world = MemoryFixture.create(false);
        world.context(Map.of(BlockPos.ZERO.east(), Blocks.GRASS_BLOCK.defaultBlockState()), Map.of());
        var terrain = new TerrainShapes(world, ignored -> true);
        var region = region(Set.of(BlockPos.ZERO), new SpatialMask());
        var first = object(BlockPos.ZERO, 0, .1f);
        var second = object(BlockPos.ZERO, 1, .1f);
        for (var order : List.of(List.of(first, second), List.of(second, first))) {
            var overlay = new FieldOverlay(region, 1, order, List.of(region));
            assertTrue(overlay.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
            assertEquals(List.of(first), overlay.visible());
        }
    }

    @Test void logicalNeighborsAndDamageAreNotOrdinaryTerrainEvenAfterColdReload() {
        BlockPos cell = BlockPos.ZERO, host = cell.east();
        var world = MemoryFixture.create(false);
        var source = region(Set.of(cell), new SpatialMask());
        var close = object(cell, 0, .1f);
        var clear = object(cell, 1, -.4f);
        var objects = List.of(close, clear);
        var hostRegion = new FieldRegion(new UUID(0, 2), 1, DEFINITION, Set.of(host), new SpatialMask(), Map.of());
        var terrain = new TerrainShapes(world, ignored -> true, VisualMeshFixture::collisionProxy);
        for (var state : List.of(Blocks.STONE.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState(),
                Blocks.AIR.defaultBlockState())) {
            world.context(Map.of(host, state), Map.of()); terrain.clear();
            var restored = FieldRegion.load(source.save());
            var overlay = new FieldOverlay(restored, 1, objects, List.of(restored, hostRegion));
            assertTrue(overlay.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
            assertEquals(objects, overlay.visible(), "Unregistered neighbors must not alter a placed layout");
        }
        world.context(Map.of(host, Blocks.STONE.defaultBlockState()), Map.of()); terrain.clear();
        var embedded = new EmbeddedBlock(host, 7, 1);
        var during = new FieldRegion(hostRegion.id(), 2, DEFINITION, Set.of(host),
                new SpatialMask(), Map.of(host, embedded));
        var overlay = new FieldOverlay(source, 1, objects, List.of(source, during));
        assertTrue(overlay.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(List.of(clear), overlay.visible(), "Registered embedding still suppresses deep crossings");
        terrain.clear();
        overlay = new FieldOverlay(source, 2, objects, List.of(source, hostRegion));
        assertTrue(overlay.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(objects, overlay.visible(), "Removing embedding restores the originals even while the native block remains");
        assertEquals(0, world.chunkRequests());
    }

    @Test void ordinaryNeighborsStayFixedDuringOneLoadButMayChangeAfterReload() {
        BlockPos cell = BlockPos.ZERO, neighbor = cell.east();
        var world = MemoryFixture.create(false);
        world.context(Map.of(neighbor, Blocks.STONE.defaultBlockState()), Map.of());
        var source = region(Set.of(cell), new SpatialMask());
        var close = object(cell, 0, .1f);
        var clear = object(cell, 1, -.4f);
        var objects = List.of(close, clear);
        var terrain = new TerrainShapes(world, ignored -> true);
        var first = new FieldOverlay(source, 1, objects, List.of(source));
        assertTrue(first.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(List.of(clear), first.visible());

        world.reset(); terrain.invalidateEmbedded();
        var again = new FieldOverlay(source, 2, objects, List.of(source));
        assertTrue(again.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(first.visible(), again.visible(), "Ordinary changes must not re-filter an existing layout");

        var restored = FieldRegion.load(source.save());
        var cold = new FieldOverlay(restored, 3, objects, List.of(restored));
        var coldTerrain = new TerrainShapes(world, ignored -> true);
        assertTrue(cold.advance(coldTerrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(objects, cold.visible(), "Reload uses current neighbors, not persisted contact boxes");

        world.context(Map.of(neighbor, Blocks.STONE.defaultBlockState()), Map.of());
        coldTerrain.invalidateEmbedded();
        var placed = new FieldOverlay(restored, 4, objects, List.of(restored));
        assertTrue(placed.advance(coldTerrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(objects, placed.visible(), "Later ordinary placement must not suppress existing objects");
    }

    @Test void replacingABarricadeWithGrassOnlyAppliesItsDamageNotNewTerrainSuppression() {
        BlockPos cell = BlockPos.ZERO, former = cell.east();
        var world = MemoryFixture.create(false);
        var close = object(cell, 0, .1f);
        var clear = object(cell, 1, -.4f);
        var removed = object(former, 2, -.4f);
        var mask = new SpatialMask();
        mask.set(former);
        var source = region(Set.of(cell), mask);
        world.context(Map.of(former, Blocks.GRASS_BLOCK.defaultBlockState()), Map.of());
        var restored = FieldRegion.load(source.save());
        var overlay = new FieldOverlay(restored, 1, List.of(close, clear, removed), List.of(restored));
        assertTrue(overlay.advance(new TerrainShapes(world, ignored -> true), Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(List.of(close, clear), overlay.visible());
        assertTrue(restored.mask().contains(former));
        assertEquals(0, world.chunkRequests());
    }

    @Test void ordinaryTerrainConvertedToABarricadeStopsBeingAnOrdinaryObstacle() {
        BlockPos cell = BlockPos.ZERO, neighbor = cell.east();
        var world = MemoryFixture.create(false);
        world.context(Map.of(neighbor, Blocks.GRASS_BLOCK.defaultBlockState()), Map.of());
        var source = region(Set.of(cell), new SpatialMask());
        var close = object(cell, 0, .1f);
        var clear = object(cell, 1, -.4f);
        var objects = List.of(close, clear);
        var terrain = new TerrainShapes(world, ignored -> true);
        var first = new FieldOverlay(source, 1, objects, List.of(source));
        assertTrue(first.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(List.of(clear), first.visible());
        var host = new FieldRegion(new UUID(0, 2), 2,
                new FieldDefinition(43, FieldDefinition.ALGORITHM, FieldDefinition.PARAMETERS, "foreign"),
                Set.of(neighbor), new SpatialMask(), Map.of());
        terrain.invalidateEmbedded();
        var grown = new FieldOverlay(source, 2, objects, List.of(source, host));
        assertTrue(grown.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(objects, grown.visible(), "A newly logical cell is not an ordinary obstacle, including cross-tag cells");
        terrain.invalidateEmbedded();
        var replacedByGrass = new FieldOverlay(source, 3, objects, List.of(source));
        assertTrue(replacedByGrass.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(objects, replacedByGrass.visible(), "Deleting the whole neighboring region cannot revive its old terrain obstacle");
    }

    @Test void aRankedObjectSurvivesTerrainFilteringButNotDamageOrColdReloadedDamage() {
        var model = new PileModel("large", "minecraft:stone", 1, 1, 1, 1,
                List.of(new CollisionBox(-.75f, -.75f, -.75f, 1.75f, 1.75f, 1.75f)));
        BlockPos cell = new BlockPos(-7, -60, -4);
        var field = new SpatialField("test", DEFINITION, List.of(model), FieldOracle::solve);
        var base = field.query(Set.of(cell));
        assertEquals(1, base.size());
        long jobs = field.metrics().jobs();
        var world = MemoryFixture.create(false);
        world.context(Map.of(cell.below(), Blocks.STONE.defaultBlockState()), Map.of());
        var terrain = new TerrainShapes(world, ignored -> true);
        var intact = region(Set.of(cell), new SpatialMask());
        var overlay = new FieldOverlay(intact, 1, base, List.of(intact));
        assertTrue(overlay.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(base, overlay.visible(), "Even a deep floor crossing must not erase the sole generated body");
        SpatialMask mask = new SpatialMask();
        mask.set(cell);
        var damaged = FieldRegion.load(region(Set.of(cell), mask).save());
        overlay = new FieldOverlay(damaged, 2, base, List.of(damaged));
        assertTrue(overlay.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertTrue(overlay.visible().isEmpty());
        assertEquals(base, field.query(Set.of(cell)));
        assertEquals(jobs, field.metrics().jobs());
        var cold = new SpatialField("test", DEFINITION, List.of(model), FieldOracle::solve);
        overlay = new FieldOverlay(damaged, 3, cold.query(damaged.domain(), damaged.baseline()), List.of(damaged));
        assertTrue(overlay.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertTrue(overlay.visible().isEmpty(), "Reload must not replace the destroyed ranked object");
        assertEquals(0, world.chunkRequests());
    }
    @Test void perCellRankedObjectsDoNotResurrectAfterPartialDamageOrReload() {
        var model = new PileModel("long", "minecraft:stone", 1, 1, 1, 1,
                List.of(new CollisionBox(-1.5f, 0, 0, 2.5f, .12f, .12f)));
        Set<BlockPos> cells = Set.of(BlockPos.ZERO, BlockPos.ZERO.east(), BlockPos.ZERO.east(2));
        var field = new SpatialField("test", DEFINITION, List.of(model), FieldOracle::solve);
        var base = field.query(cells);
        for (BlockPos cell : cells)
            assertTrue(base.stream().anyMatch(object -> object.owner().equals(cell)));
        var world = MemoryFixture.create(false);
        world.context(Map.of(), Map.of());
        var terrain = new TerrainShapes(world, ignored -> true);
        var mask = new SpatialMask();
        mask.set(BlockPos.ZERO.east());
        var damaged = FieldRegion.load(region(cells, mask).save());
        var expected = base.stream().filter(object -> !FieldVisibility.damaged(object, mask)).toList();
        assertTrue(expected.size() < base.size());
        assertFalse(expected.isEmpty());
        long jobs = field.metrics().jobs();
        for (var layout : List.of(base,
                new SpatialField("test", DEFINITION, List.of(model), FieldOracle::solve)
                        .query(damaged.domain(), damaged.baseline()))) {
            var overlay = new FieldOverlay(damaged, 1, layout, List.of(damaged));
            while (!overlay.advance(terrain, 1, Long.MAX_VALUE)) { }
            assertEquals(expected, overlay.visible());
        }
        assertEquals(base, field.query(cells));
        assertEquals(jobs, field.metrics().jobs());
        assertEquals(0, world.chunkRequests());
    }
}
