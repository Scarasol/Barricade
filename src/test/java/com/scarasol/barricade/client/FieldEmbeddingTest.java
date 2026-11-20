package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import com.scarasol.barricade.sampling.MemoryLevel;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class FieldEmbeddingTest {
    private static final FieldDefinition DEFINITION = FieldDefinition.create(42,
            PaletteSpec.create(List.of(new PileModels.Candidate("cube", "minecraft:stone", 1)), "test"));
    private static PileModel cube(float size) {
        return new PileModel("cube", "minecraft:stone", size, size, size, 1, List.of(CollisionBox.CUBE));
    }
    private static FieldObject object(BlockPos origin, BlockPos owner, int id, PileModel model,
                                      Vector3f center, Quaternionf rotation) {
        return new FieldObject(new FieldObject.Key("test", DEFINITION, "geometry", new GridPos(0, 0, 0), 2, id), owner,
                PilePiece.create(model, center.x + (origin.getX() - owner.getX()),
                        center.y + (origin.getY() - owner.getY()), center.z + (origin.getZ() - owner.getZ()), 1, rotation));
    }
    private static FieldRegion region(long id, Set<BlockPos> cells, Map<BlockPos, EmbeddedBlock> embedded) {
        return new FieldRegion(new UUID(1, id), 1, DEFINITION, cells, new SpatialMask(), embedded);
    }
    private static List<FieldObject> display(FieldRegion region, List<FieldObject> objects, MemoryLevel memory,
                                             Collection<FieldRegion> regions) {
        return display(region, objects, memory, regions, VisualMeshFixture::collisionProxy);
    }
    private static List<FieldObject> display(FieldRegion region, List<FieldObject> objects, MemoryLevel memory,
                                             Collection<FieldRegion> regions, Function<PileModel, StaticMesh> meshes) {
        FieldOverlay overlay = new FieldOverlay(region, 1, objects, regions);
        assertTrue(overlay.advance(new TerrainShapes(memory, ignored -> true, meshes),
                Integer.MAX_VALUE, Long.MAX_VALUE));
        return overlay.visible();
    }

    @Test void aLargeSingleBlockEmbeddingFiltersOtherRegionsAndRemovalRestoresTheSameObjects() {
        BlockPos pos = new BlockPos(-17, -60, -1);
        var memory = MemoryFixture.create(false);
        memory.context(Map.of(pos, Blocks.FURNACE.defaultBlockState()), Map.of());
        var embedded = new EmbeddedBlock(pos, 7, 1);
        var model = embedded.model(memory);
        assertEquals(1, model.parts().size());
        var visual = embedded.transform(model);
        var center = visual.transformPoint(.5f, 6.5f, .5f);
        var clearCenter = visual.transformPoint(2, 6.5f, .5f);
        BlockPos owner = BlockPos.containing(pos.getX() + (double) center.x,
                pos.getY() + (double) center.y, pos.getZ() + (double) center.z);
        var inside = object(pos, owner, 0, cube(.1f), center, visual.rotation());
        var clear = object(pos, owner, 1, cube(.1f), clearCenter, visual.rotation());
        Set<BlockPos> domain = new HashSet<>(FieldVisibility.cells(inside));
        domain.addAll(FieldVisibility.cells(clear));
        assertFalse(domain.contains(pos), "The source and embedded host must be separate regions");
        var source = region(1, domain, Map.of());
        var host = region(2, Set.of(pos), Map.of(pos, embedded));
        var base = List.of(inside, clear);
        try (var mesh = VisualMeshFixture.box(new AABB(0, 0, 0, 1, 8, 1))) {
            AtomicInteger captures = new AtomicInteger();
            var terrain = new TerrainShapes(memory, ignored -> true, captured -> {
                if (!captured.id().startsWith("embedded:")) return VisualMeshFixture.collisionProxy(captured);
                captures.incrementAndGet();
                return mesh;
            });
            var during = new FieldOverlay(source, 1, base, List.of(source, host));
            assertTrue(during.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
            assertEquals(List.of(clear), during.visible());
            var alone = new FieldOverlay(source, 1, List.of(inside), List.of(source, host));
            assertTrue(alone.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
            assertTrue(alone.visible().isEmpty(), "An embedded conflict must not be restored as a fallback");
            var restored = new FieldOverlay(source, 2, base, List.of(source, region(2, Set.of(pos), Map.of())));
            assertTrue(restored.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
            assertEquals(base, restored.visible());
            assertSame(inside, restored.visible().get(0));
            assertSame(clear, restored.visible().get(1));
            assertTrue(source.mask().isEmpty());
            assertTrue(host.mask().isEmpty());
            assertEquals(1, captures.get());
            assertEquals(0, memory.chunkRequests());
        }
    }

    @Test void spaceBetweenEmbeddedLegsRemainsVisible() {
        BlockPos pos = new BlockPos(-17, -60, -1);
        var memory = MemoryFixture.create(false);
        memory.context(Map.of(pos, Blocks.FURNACE.defaultBlockState()), Map.of());
        var embedded = new EmbeddedBlock(pos, 7, 1);
        var visual = embedded.transform(embedded.model(memory));
        var small = object(pos, pos.east(), 0, cube(.2f), visual.transformPoint(.5f, .5f, .5f), visual.rotation());
        var clear = object(pos, pos.east(), 1, cube(.2f), new Vector3f(2.5f, .5f, .5f), new Quaternionf());
        var crossesTop = object(pos, pos.east(), 2, cube(.2f), visual.transformPoint(.5f, .85f, .5f), visual.rotation());
        var during = region(1, Set.of(pos, pos.east(2)), Map.of(pos, embedded));
        try (StaticMesh frame = VisualMeshFixture.boxes(
                new CollisionBox(0, .85f, 0, 1, 1, 1),
                new CollisionBox(0, 0, 0, .1f, .85f, .1f),
                new CollisionBox(.9f, 0, .9f, 1, .85f, 1))) {
            Function<PileModel, StaticMesh> meshes = model -> model.id().startsWith("embedded:")
                    ? frame : VisualMeshFixture.collisionProxy(model);
            assertTrue(FieldVisibility.centerInside(small,
                    new FieldVisibility.Shape(pos, visual.colliders(0, 0, 0), true)));
            assertEquals(List.of(small, clear),
                    display(during, List.of(small, clear, crossesTop), memory, List.of(during), meshes));
            assertTrue(display(during, List.of(crossesTop), memory, List.of(during), meshes).isEmpty());
            assertEquals(0, memory.chunkRequests());
        }
    }

    @Test void aPilePieceAlsoKeepsItsEmptySpaceAroundAnEmbeddedObject() {
        BlockPos pos = BlockPos.ZERO;
        var memory = MemoryFixture.create(false);
        memory.context(Map.of(pos, Blocks.FURNACE.defaultBlockState()), Map.of());
        var embedded = new EmbeddedBlock(pos, 7, 1);
        var visual = embedded.transform(embedded.model(memory));
        var furniture = object(pos, pos, 0, cube(1), visual.transformPoint(.5f, .5f, .5f), visual.rotation());
        var clear = object(pos, pos, 1, cube(.2f), new Vector3f(2.5f, .5f, .5f), new Quaternionf());
        var during = region(1, Set.of(pos, pos.east(2)), Map.of(pos, embedded));
        try (StaticMesh frame = VisualMeshFixture.boxes(
                new CollisionBox(0, .85f, 0, 1, 1, 1),
                new CollisionBox(0, 0, 0, .1f, .85f, .1f),
                new CollisionBox(.9f, 0, .9f, 1, .85f, 1));
             StaticMesh small = VisualMeshFixture.boxes(new CollisionBox(.3f, .3f, .3f, .7f, .7f, .7f))) {
            AtomicInteger captures = new AtomicInteger();
            Function<PileModel, StaticMesh> meshes = model -> {
                captures.incrementAndGet();
                return model.id().startsWith("embedded:") ? small : frame;
            };
            assertEquals(List.of(furniture, clear),
                    display(during, List.of(furniture, clear), memory, List.of(during), meshes));
            assertTrue(captures.get() > 1, "The overlay obtains both meshes to confirm the overlap");
            var before = region(1, during.domain(), Map.of());
            captures.set(0);
            assertEquals(List.of(furniture), display(before, List.of(furniture), memory, List.of(before), meshes));
            assertEquals(0, captures.get(), "Ordinary and cross-tag display must not pay for mesh intersection");
            assertEquals(0, memory.chunkRequests());
        }
    }

    @Test void anEmbeddedEnvelopeSpanningTwoByTwoCellsOnlyHidesTheIntersectingPiece() {
        BlockPos pos = new BlockPos(-17, -60, -1);
        var memory = MemoryFixture.create(false);
        memory.context(Map.of(pos, Blocks.FURNACE.defaultBlockState()), Map.of());
        var embedded = new EmbeddedBlock(pos, 7, 1);
        var pose = embedded.transform(embedded.model(memory));
        var localCenter = pose.rotation().conjugate().transform(new Vector3f(.5f)
                .sub(pose.x(), pose.y(), pose.z())).div(pose.scale()).add(.5f, .5f, .5f);
        var cells = List.of(pos, pos.east(), pos.south(), pos.east().south());
        List<FieldObject> pieces = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            BlockPos owner = cells.get(i);
            pieces.add(object(owner, owner, i, cube(.2f), new Vector3f(.5f), new Quaternionf()));
        }
        var during = region(1, Set.copyOf(cells), Map.of(pos, embedded));
        var before = region(1, during.domain(), Map.of());
        try (var mesh = VisualMeshFixture.boxes(
                new CollisionBox(localCenter.x - .18f, localCenter.y - .18f, localCenter.z - .18f,
                        localCenter.x + .18f, localCenter.y + .18f, localCenter.z + .18f),
                new CollisionBox(-1.5f, -1.5f, -1.5f, -1.3f, -1.3f, -1.3f),
                new CollisionBox(2.3f, 2.3f, 2.3f, 2.5f, 2.5f, 2.5f));
             var filledEnvelope = VisualMeshFixture.box(mesh.bounds)) {
            assertTrue(display(during, pieces, memory, List.of(during), model -> model.id().startsWith("embedded:")
                    ? filledEnvelope : VisualMeshFixture.collisionProxy(model)).isEmpty(),
                    "Treating the same envelope as solid would hide all four pieces");
            Function<PileModel, StaticMesh> meshes = model -> model.id().startsWith("embedded:")
                    ? mesh : VisualMeshFixture.collisionProxy(model);
            assertEquals(pieces.subList(1, 4), display(during, pieces, memory, List.of(during), meshes));
            assertEquals(pieces, display(before, pieces, memory, List.of(before), meshes));
            assertTrue(during.mask().isEmpty());

            var loaded = new AtomicBoolean(false);
            var terrain = new TerrainShapes(memory, cell -> !cell.equals(pos) || loaded.get(), meshes);
            try {
                BarricadeRenderer.replace(before.id(), pieces);
                var unloaded = new FieldOverlay(during, 1, pieces, List.of(during));
                assertTrue(unloaded.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
                BarricadeRenderer.replace(during.id(), unloaded.visible());
                assertEquals(4, BarricadeRenderer.counters().objects(), "An unloaded anchor is not an obstacle");

                loaded.set(true);
                memory.reset();
                terrain.invalidateEmbedded();
                var awaitingState = new FieldOverlay(during, 2, pieces, List.of(during));
                assertTrue(awaitingState.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
                BarricadeRenderer.replace(during.id(), awaitingState.visible());
                assertEquals(4, BarricadeRenderer.counters().objects(), "Metadata can precede the native state");

                memory.context(Map.of(pos, Blocks.FURNACE.defaultBlockState()), Map.of());
                terrain.invalidateEmbedded();
                var synchronizedState = new FieldOverlay(during, 3, pieces, List.of(during));
                assertTrue(synchronizedState.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
                assertEquals(pieces.subList(1, 4), synchronizedState.visible());
                BarricadeRenderer.replace(during.id(), synchronizedState.visible());
                assertEquals(3, BarricadeRenderer.counters().objects());
                BarricadeRenderer.replace(before.id(), display(before, pieces, memory, List.of(before), meshes));
                assertEquals(4, BarricadeRenderer.counters().objects());
            } finally {
                BarricadeRenderer.remove(before.id());
            }
        }
    }

    @Test void failedEmbeddedCaptureUsesTheNativeBlockThatIsActuallyStillVisible() {
        BlockPos pos = BlockPos.ZERO;
        var memory = MemoryFixture.create(false);
        memory.context(Map.of(pos, Blocks.FURNACE.defaultBlockState()), Map.of());
        var embedded = new EmbeddedBlock(pos, 7, 1);
        var inside = object(pos, pos.east(), 0, cube(.2f), new Vector3f(.5f), new Quaternionf());
        var outside = object(pos, pos.east(), 1, cube(.2f), new Vector3f(1.2f, .5f, .5f), new Quaternionf());
        var during = region(1, Set.of(pos, pos.east()), Map.of(pos, embedded));
        assertEquals(List.of(outside), display(during, List.of(inside, outside), memory, List.of(during),
                model -> model.id().startsWith("embedded:") ? null : VisualMeshFixture.collisionProxy(model)));
        assertEquals(0, memory.chunkRequests());
    }
    @Test void embeddingKeepsShallowContactButRejectsDeepCrossingAndRemovalRestoresExactObjects() {
        for (BlockPos pos : List.of(BlockPos.ZERO, new BlockPos(-17, -60, -1))) {
            var memory = MemoryFixture.create(false);
            memory.context(Map.of(pos, Blocks.FURNACE.defaultBlockState()), Map.of());
            var embedded = new EmbeddedBlock(pos, 7, 1);
            var visual = embedded.transform(embedded.model(memory));
            Set<BlockPos> domain = new HashSet<>();
            BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(2, 2, 2)).forEach(cell -> domain.add(cell.immutable()));
            var inside = object(pos, pos, 0, cube(.3f), visual.transformPoint(.5f, .5f, .5f), visual.rotation());
            var protruding = object(pos, pos, 1, cube(.3f), visual.transformPoint(1.05f, .5f, .5f), visual.rotation());
            var shallow = object(pos, pos, 3, cube(.3f),
                    visual.transformPoint(1 + (.15f - .02f) / .98f, .5f, .5f), visual.rotation());
            var far = object(pos, pos.east(2), 2, cube(.3f), new Vector3f(2.5f, .5f, .5f), new Quaternionf());
            var shape = new FieldVisibility.Shape(pos, visual.colliders(0, 0, 0), true);
            assertTrue(FieldVisibility.hit(protruding, shape), "The body must genuinely intersect the embedded collider");
            var base = List.of(inside, protruding, shallow, far);
            var before = region(1, domain, Map.of());
            assertEquals(base, display(before, base, memory, List.of(before)));
            var during = region(1, domain, Map.of(pos, embedded));
            assertEquals(List.of(shallow, far), display(during, base, memory, List.of(during)));
            var loaded = FieldRegion.load(during.save());
            assertEquals(List.of(shallow, far), display(loaded, base, memory, List.of(loaded)));
            memory.reset();
            assertEquals(base, display(before, base, memory, List.of(before)), "Unembedding restores identities and transforms, not replacements");
            assertTrue(before.mask().isEmpty());
            assertTrue(during.mask().isEmpty());
            assertEquals(0, memory.chunkRequests());
        }
    }
    @Test void aThinEmbeddedShapeOnlyCoversCentersInsideItsTransformedSolidPart() {
        BlockPos pos = new BlockPos(-17, -60, -1);
        var memory = MemoryFixture.create(false);
        memory.context(Map.of(pos, Blocks.OAK_SLAB.defaultBlockState()), Map.of());
        var embedded = new EmbeddedBlock(pos, 11, 1);
        var visual = embedded.transform(embedded.model(memory));
        var solid = object(pos, pos, 0, cube(.1f), visual.transformPoint(.5f, .25f, .5f), visual.rotation());
        var empty = object(pos, pos, 1, cube(.1f), visual.transformPoint(.5f, .85f, .5f), visual.rotation());
        Set<BlockPos> domain = new HashSet<>();
        BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1)).forEach(cell -> domain.add(cell.immutable()));
        var during = region(1, domain, Map.of(pos, embedded));
        assertEquals(List.of(empty), display(during, List.of(solid, empty), memory, List.of(during)));
        assertEquals(0, memory.chunkRequests());
    }
    @Test void initialTerrainUsesOneMinimumFallbackAndOnlyReloadCanReadLaterNeighbors() {
        BlockPos pos = new BlockPos(-17, -60, -1);
        var memory = MemoryFixture.create(false);
        memory.context(Map.of(pos, Blocks.FURNACE.defaultBlockState()), Map.of());
        var protruding = object(pos, pos.east(), 0, cube(.4f), new Vector3f(1.1f, .5f, .5f), new Quaternionf());
        var deeper = object(pos, pos.east(), 1, cube(.4f), new Vector3f(1.02f, .5f, .5f), new Quaternionf());
        var clear = object(pos, pos.east(), 2, cube(.4f), new Vector3f(1.5f, .5f, .5f), new Quaternionf());
        var source = region(1, Set.of(pos.east()), Map.of());
        var terrain = new TerrainShapes(memory, ignored -> true);
        var first = new FieldOverlay(source, 1, List.of(protruding, deeper), List.of(source));
        assertTrue(first.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(List.of(protruding), first.visible());
        var second = new FieldOverlay(source, 2, List.of(protruding, deeper, clear), List.of(source));
        assertTrue(second.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(List.of(clear), second.visible());
        memory.reset();
        terrain.invalidateEmbedded();
        var unchanged = new FieldOverlay(source, 3, List.of(protruding, deeper, clear), List.of(source));
        assertTrue(unchanged.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(List.of(clear), unchanged.visible());
        var restored = FieldRegion.load(source.save());
        assertEquals(List.of(protruding, deeper, clear),
                display(restored, List.of(protruding, deeper, clear), memory, List.of(restored)));
        memory.context(Map.of(pos, Blocks.FURNACE.defaultBlockState()), Map.of());
        assertEquals(List.of(clear), display(restored, List.of(protruding, deeper, clear), memory, List.of(restored)));
        assertEquals(0, memory.chunkRequests());
    }
    @Test void anotherRegionEmbeddingUsesTheSamePenetrationRankingAndRestoresExactObjects() {
        BlockPos pos = new BlockPos(-17, -60, -1);
        var memory = MemoryFixture.create(false);
        memory.context(Map.of(pos, Blocks.FURNACE.defaultBlockState()), Map.of());
        var embedded = new EmbeddedBlock(pos, 7, 1);
        var visual = embedded.transform(embedded.model(memory));
        var object = object(pos, pos.east(), 0, cube(1.4f), visual.transformPoint(.5f, .5f, .5f), new Quaternionf());
        var shifted = object(pos, pos.east(), 1, cube(1.4f),
                visual.transformPoint(.5f, .5f, .5f).add(.6f, 0, 0), new Quaternionf());
        var clear = object(pos, pos.east(), 2, cube(.2f), new Vector3f(1.8f, .5f, .5f), new Quaternionf());
        var source = region(1, Set.of(pos.east()), Map.of());
        var host = region(2, Set.of(pos), Map.of(pos, embedded));
        assertTrue(FieldVisibility.activated(object, source.domain()));
        var view = new TerrainShapes(memory, ignored -> true, VisualMeshFixture::collisionProxy)
                .view(Set.of(pos, pos.east()), Map.of(pos, embedded));
        assertTrue(view.penetration(shifted) < view.penetration(object));
        assertTrue(view.penetration(shifted) > 1f / 32);
        assertTrue(display(source, List.of(object, shifted), memory, List.of(source, host)).isEmpty(),
                "A least-overlapping body must not be restored through the embedded model");
        assertEquals(List.of(clear), display(source, List.of(object, shifted, clear), memory, List.of(source, host)));
        var restoredHost = region(2, Set.of(pos), Map.of());
        assertEquals(List.of(object, shifted, clear),
                display(source, List.of(object, shifted, clear), memory, List.of(source, restoredHost)));
        assertEquals(0, memory.chunkRequests());
    }
}
