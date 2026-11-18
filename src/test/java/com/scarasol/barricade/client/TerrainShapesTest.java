package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class TerrainShapesTest {
    @Test void embeddedTableUsesOneWholeEnvelopeIncludingTheSpaceBetweenItsLegs() {
        var world = MemoryFixture.create(false);
        world.context(Map.of(BlockPos.ZERO, Blocks.OAK_PLANKS.defaultBlockState()), Map.of());
        var embedded = new EmbeddedBlock(BlockPos.ZERO, 7, 1);
        var pose = embedded.transform(embedded.model(world));
        var center = pose.transformPoint(.5f, .4f, .5f);
        var small = new PileModel("small", "minecraft:stone", .1f, .1f, .1f, 1, List.of(CollisionBox.CUBE));
        var template = object(BlockPos.ZERO);
        var candidate = new FieldObject(template.key(), BlockPos.ZERO,
                PilePiece.create(small, center.x, center.y, center.z, 1, pose.rotation()));
        try (var table = VisualMeshFixture.boxes(new CollisionBox(0, .85f, 0, 1, 1, 1),
                new CollisionBox(0, 0, 0, .1f, .85f, .1f),
                new CollisionBox(.9f, 0, .9f, 1, .85f, 1))) {
            var terrain = new TerrainShapes(world, ignored -> true, ignored -> table);
            assertFalse(terrain.view(Set.of(BlockPos.ZERO), Map.of(BlockPos.ZERO, embedded)).visible(candidate));
        }
    }

    @Test void singleBlockVisualCoverageReachesBeyondTheAnchorNeighborhoodAndReusesItsCapture() {
        for (BlockPos pos : List.of(BlockPos.ZERO, new BlockPos(-17, -60, -1), new BlockPos(15, 15, 15))) {
            var world = MemoryFixture.create(false);
            world.context(Map.of(pos, Blocks.FURNACE.defaultBlockState()), Map.of());
            var embedded = new EmbeddedBlock(pos, 7, 1);
            var model = embedded.model(world);
            assertEquals(1, model.parts().size());
            var pose = embedded.transform(model);
            var center = pose.transformPoint(.5f, 6.5f, .5f);
            var outside = pose.transformPoint(2, 6.5f, .5f);
            BlockPos owner = BlockPos.containing(pos.getX() + (double) center.x,
                    pos.getY() + (double) center.y, pos.getZ() + (double) center.z);
            var small = new PileModel("small", "minecraft:stone", .1f, .1f, .1f, 1, List.of(CollisionBox.CUBE));
            var template = object(owner);
            var inside = new FieldObject(template.key(), owner, PilePiece.create(small,
                    pos.getX() - owner.getX() + center.x, pos.getY() - owner.getY() + center.y,
                    pos.getZ() - owner.getZ() + center.z, 1, pose.rotation()));
            var clear = new FieldObject(template.key(), owner, PilePiece.create(small,
                    pos.getX() - owner.getX() + outside.x, pos.getY() - owner.getY() + outside.y,
                    pos.getZ() - owner.getZ() + outside.z, 1, pose.rotation()));
            for (BlockPos cell : FieldVisibility.cells(inside))
                assertTrue(Math.abs(cell.getX() - pos.getX()) > 1 || Math.abs(cell.getY() - pos.getY()) > 1
                        || Math.abs(cell.getZ() - pos.getZ()) > 1, "The old anchor search must miss this model end");
            var blocks = Map.of(pos, embedded);
            var logical = Set.of(pos, owner);
            AtomicInteger captures = new AtomicInteger();
            try (var tall = VisualMeshFixture.box(new AABB(0, 0, 0, 1, 8, 1));
                 var shortModel = VisualMeshFixture.boxes(CollisionBox.CUBE)) {
                var terrain = new TerrainShapes(world, ignored -> true,
                        ignored -> captures.incrementAndGet() == 1 ? tall : shortModel);
                var view = terrain.view(logical, blocks);
                assertFalse(view.visible(inside));
                assertTrue(view.visible(clear), "The broad-phase AABB must not replace the rotated envelope");
                for (int i = 0; i < 100; i++) assertFalse(view.visible(inside));
                assertFalse(terrain.view(logical, blocks).visible(inside));
                assertEquals(1, captures.get(), "Overlay views share the existing refresh-local shape cache");
                assertTrue(terrain.view(logical, Map.of()).visible(inside), "Removed embeddings leave no suppression");
                BlockPos pending = pos.east(128);
                assertTrue(terrain.view(logical, Map.of(pending, new EmbeddedBlock(pending, 8, 1))).visible(inside),
                        "An unrelated pending anchor must not retain a removed model's indexed coverage");
                terrain.invalidateEmbedded();
                assertTrue(terrain.view(logical, blocks).visible(inside), "State refresh must replace the old bounds");
                assertEquals(2, captures.get());
                assertEquals(0, world.chunkRequests());
            }
        }
    }

    private static FieldObject object(BlockPos pos) {
        PileModel model = new PileModel("cube", "minecraft:stone", .4f, .4f, .4f, 1, List.of(CollisionBox.CUBE));
        var definition = new FieldDefinition(1, FieldDefinition.ALGORITHM, FieldDefinition.PARAMETERS, "test");
        return new FieldObject(new FieldObject.Key("test", definition, "geometry",
                new GridPos(pos.getX(), pos.getY(), pos.getZ()), 4, 0), pos,
                PilePiece.create(model, .5f, .5f, .5f, 1, new Quaternionf()));
    }
    @Test void ordinaryContactsReadOnlyTheAdjacentRingOnceAndUnknownOrdinaryChunksCannotHideObjects() {
        var world = MemoryFixture.create(false);
        AtomicInteger reads = new AtomicInteger();
        TerrainShapes terrain = new TerrainShapes(world, pos -> {
            assertTrue(Math.abs(pos.getX()) <= 1 && Math.abs(pos.getY()) <= 1 && Math.abs(pos.getZ()) <= 1);
            reads.incrementAndGet(); return true;
        });
        var view = terrain.view(Set.of(BlockPos.ZERO), Map.of());
        FieldObject object = object(BlockPos.ZERO);
        assertTrue(view.visible(object));
        for (int i = 0; i < 100; i++) assertTrue(view.visible(object));
        assertEquals(26, reads.get());
        TerrainShapes unknown = new TerrainShapes(world, pos -> false);
        assertTrue(unknown.view(Set.of(BlockPos.ZERO), Map.of()).visible(object));
        assertFalse(unknown.view(Set.of(BlockPos.ZERO), Map.of(BlockPos.ZERO,
                new EmbeddedBlock(BlockPos.ZERO, 7, 1))).visible(object));
        assertEquals(0, world.chunkRequests());
    }
    @Test void newOverlayScopesAndWorldInvalidationCannotReuseOldSuppression() {
        var world = MemoryFixture.create(false);
        world.context(Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState()), Map.of());
        TerrainShapes terrain = new TerrainShapes(world, ignored -> true, VisualMeshFixture::collisionProxy);
        FieldObject object = object(BlockPos.ZERO);
        assertTrue(terrain.view(Set.of(BlockPos.ZERO), Map.of()).visible(object));
        world.reset(); terrain.clear();
        assertTrue(terrain.view(Set.of(BlockPos.ZERO), Map.of()).visible(object));
        world.context(Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState()), Map.of()); terrain.clear();
        var embedded = Map.of(BlockPos.ZERO, new EmbeddedBlock(BlockPos.ZERO, 7, 1));
        assertFalse(terrain.view(Set.of(BlockPos.ZERO), embedded).visible(object));
        world.reset(); terrain.clear();
        assertTrue(terrain.view(Set.of(BlockPos.ZERO), Map.of()).visible(object));
        assertEquals(0, world.chunkRequests());
    }

    @Test void aNeighborsChangedFenceShapeOnlyTakesEffectOnTheNextLoad() {
        var world = MemoryFixture.create(false);
        BlockPos cell = BlockPos.ZERO, neighbor = cell.east();
        world.context(Map.of(neighbor, Blocks.OAK_FENCE.defaultBlockState()), Map.of());
        var template = object(cell);
        var small = new PileModel("small", "minecraft:stone", .1f, .1f, .1f, 1, List.of(CollisionBox.CUBE));
        var candidate = new FieldObject(template.key(), cell,
                PilePiece.create(small, 1.1f, .5f, .5f, 1, new Quaternionf()));
        var terrain = new TerrainShapes(world, ignored -> true);
        assertTrue(terrain.view(Set.of(cell), Map.of()).visible(candidate));
        world.context(Map.of(neighbor, Blocks.OAK_FENCE.defaultBlockState().setValue(FenceBlock.WEST, true)), Map.of());
        terrain.invalidateEmbedded();
        assertTrue(terrain.view(Set.of(cell), Map.of()).visible(candidate));
        assertFalse(new TerrainShapes(world, ignored -> true).view(Set.of(cell), Map.of()).visible(candidate));
        assertEquals(0, world.chunkRequests());
    }

    @Test void releasedScopeDoesNotReuseTheConditionsOfAnEarlierPlacement() {
        var world = MemoryFixture.create(false);
        var template = object(BlockPos.ZERO);
        var candidate = new FieldObject(template.key(), template.owner(), template.piece().translated(.6f, 0, 0));
        var terrain = new TerrainShapes(world, ignored -> true);
        var logical = Set.of(BlockPos.ZERO);
        assertTrue(terrain.view(logical, Map.of()).visible(candidate));
        terrain.synchronize(Map.of(), Map.of());
        world.context(Map.of(BlockPos.ZERO.east(), Blocks.STONE.defaultBlockState()), Map.of());
        assertFalse(terrain.view(logical, Map.of()).visible(candidate));
    }

    @Test void replacingADestroyedCellReadsNewInitialConditionsWithoutResamplingItsNeighbors() {
        var world = MemoryFixture.create(false);
        BlockPos cell = BlockPos.ZERO, neighbor = cell.east();
        var template = object(cell);
        var candidate = new FieldObject(template.key(), cell, template.piece().translated(.6f, 0, 0));
        var terrain = new TerrainShapes(world, ignored -> true);
        assertTrue(terrain.view(Set.of(cell), Map.of()).visible(candidate));
        var mask = new SpatialMask(); mask.set(cell);
        var damaged = new FieldRegion(new UUID(0, 1), 2, template.key().definition(), Set.of(cell.west()),
                mask, Map.of());
        var replaced = new FieldRegion(damaged.id(), 3, damaged.definition(), Set.of(cell, cell.west()),
                new SpatialMask(), Map.of());
        world.context(Map.of(neighbor, Blocks.STONE.defaultBlockState()), Map.of());
        terrain.synchronize(Map.of(damaged.id(), damaged), Map.of(replaced.id(), replaced)); terrain.invalidateEmbedded();
        assertFalse(terrain.view(replaced.baseline(), Map.of()).visible(candidate));
    }

    @Test void replacingADamagedCellWithAnotherTagDoesNotMixOrRepeatedlyResetTheirInitialConditions() {
        var world = MemoryFixture.create(false);
        BlockPos cell = BlockPos.ZERO;
        var template = object(cell);
        var old = new FieldObject(template.key(), cell, template.piece().translated(.6f, 0, 0));
        var terrain = new TerrainShapes(world, ignored -> true);
        assertTrue(terrain.view(Set.of(cell), Map.of()).visible(old));

        var definition = new FieldDefinition(2, FieldDefinition.ALGORITHM, FieldDefinition.PARAMETERS, "foreign");
        var key = template.key();
        var replacement = new FieldObject(new FieldObject.Key(key.dimension(), definition, key.geometry(),
                key.slot(), key.layer(), key.trial()), cell, old.piece());
        world.context(Map.of(cell.east(), Blocks.STONE.defaultBlockState()), Map.of());
        terrain.invalidateEmbedded();
        assertFalse(terrain.view(Set.of(cell), Map.of()).visible(replacement));
        var mask = new SpatialMask(); mask.set(cell);
        var damaged = new FieldRegion(new UUID(0, 1), 2, key.definition(), Set.of(cell.west()), mask, Map.of());
        var placed = new FieldRegion(new UUID(0, 2), 1, definition, Set.of(cell), new SpatialMask(), Map.of());
        var regions = Map.of(damaged.id(), damaged, placed.id(), placed);
        world.reset();
        terrain.synchronize(regions, regions); terrain.invalidateEmbedded();
        var view = terrain.view(Set.of(cell, cell.west()), Map.of());
        assertTrue(view.visible(old));
        assertFalse(view.visible(replacement), "Unrelated updates cannot re-read the new tag's ordinary neighbors");
        assertEquals(0, world.chunkRequests());
    }

    @Test void initialDisplayOf512CellsScansOnceWithoutChunkRequestsOrRescanningOnEmbeddingRefresh() {
        var world = MemoryFixture.create(false);
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> ground = new HashMap<>();
        for (int x = -1; x <= 8; x++) for (int z = -1; z <= 8; z++)
            ground.put(new BlockPos(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState());
        world.context(ground, Map.of());
        Set<BlockPos> logical = new HashSet<>();
        List<FieldObject> objects = new ArrayList<>();
        for (int x = 0; x < 8; x++) for (int y = 1; y <= 8; y++) for (int z = 0; z < 8; z++) {
            BlockPos cell = new BlockPos(x, y, z);
            logical.add(cell); objects.add(object(cell));
        }
        AtomicInteger reads = new AtomicInteger();
        var terrain = new TerrainShapes(world, ignored -> { reads.incrementAndGet(); return true; });
        var view = terrain.view(logical, Map.of());
        long start = System.nanoTime();
        objects.forEach(candidate -> assertTrue(view.visible(candidate)));
        double initialMs = (System.nanoTime() - start) / 1e6;
        int scanned = reads.get();
        assertTrue(scanned <= 512 * 26);
        terrain.invalidateEmbedded();
        var refreshed = terrain.view(logical, Map.of());
        start = System.nanoTime();
        objects.forEach(candidate -> assertTrue(refreshed.visible(candidate)));
        double refreshMs = (System.nanoTime() - start) / 1e6;
        assertEquals(scanned, reads.get());
        assertEquals(0, world.chunkRequests());
        System.out.printf(Locale.ROOT, "LOAD-LOCAL CONTACTS 512 cells: initial=%.3f ms refresh=%.3f ms neighbor_reads=%d refresh_reads=0 saved_boxes=0%n",
                initialMs, refreshMs, scanned);
    }
}
