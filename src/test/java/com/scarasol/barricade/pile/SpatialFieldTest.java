package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.recipe.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class SpatialFieldTest {
    private static final PaletteSpec PALETTE = PaletteSpec.create(List.of(
            new PileModels.Candidate("small", "minecraft:oak_planks", 1)), "test");
    private static final List<PileModel> MODELS = List.of(new PileModel("small", "minecraft:oak_planks",
            .45f, .35f, .4f, 1, List.of(CollisionBox.CUBE)));
    private static SpatialField field(long seed) {
        return new SpatialField("minecraft:overworld", FieldDefinition.create(seed, PALETTE), MODELS, FieldOracle::solve);
    }
    @Test void productionQueriesHaveTheSameIdentityAndPoseAcrossFinalGeometryPartitions() {
        BlockPos origin = new BlockPos(-17, 4, 10000000);
        Set<BlockPos> small = Set.of(origin), wall = new HashSet<>();
        for (int x = 0; x < 5; x++) wall.add(origin.east(x));
        SpatialField sequential = field(42);
        var first = sequential.query(small, wall);
        assertFalse(first.isEmpty());
        Map<FieldObject.Key, FieldObject> whole = index(field(42).query(wall));
        assertTrue(whole.values().containsAll(first));
        Map<FieldObject.Key, FieldObject> incremental = new TreeMap<>();
        List<BlockPos> reverse = wall.stream().sorted(Comparator.reverseOrder()).toList();
        for (BlockPos cell : reverse) for (FieldObject object : sequential.query(Set.of(cell), wall))
            incremental.put(object.key(), object);
        assertEquals(whole, incremental, "Complete objects, not just random numbers, must agree");
        assertEquals(whole, index(sequential.query(wall)));
    }
    @Test void oneCellMatchesTheSameCellInsideACompleteBaselineAndCachesIgnoreDisplayPartitions() {
        SpatialField a = field(7), b = field(7);
        BlockPos cell = new BlockPos(-16, -1, 17);
        Set<BlockPos> targets = new HashSet<>();
        for (int x = -2; x <= 2; x++) targets.add(cell.east(x));
        var one = a.query(Set.of(cell), targets);
        var many = b.query(targets).stream().filter(object -> FieldVisibility.activated(object, Set.of(cell))).toList();
        assertEquals(one, many);
        long jobs = a.metrics().jobs();
        assertEquals(one, a.query(Set.of(cell), targets));
        assertEquals(jobs, a.metrics().jobs());
    }
    @Test void overlayDamageAndEmbeddingNeverSolveAndRemovalRestoresOnlyUndamagedObjects() {
        AtomicInteger jobs = new AtomicInteger();
        SpatialField field = new SpatialField("test", FieldDefinition.create(91, PALETTE), MODELS,
                batch -> { jobs.incrementAndGet(); return FieldOracle.solve(batch); });
        Set<BlockPos> domain = Set.of(BlockPos.ZERO, BlockPos.ZERO.east());
        var base = field.query(domain);
        int solved = jobs.get();
        SpatialMask mask = new SpatialMask(); mask.set(BlockPos.ZERO);
        var surviving = base.stream().filter(object -> !FieldVisibility.damaged(object, mask)).toList();
        var embed = FieldVisibility.cube(BlockPos.ZERO.east());
        var covered = surviving.stream().filter(object -> !FieldVisibility.hit(object, embed)).toList();
        assertTrue(covered.size() < surviving.size());
        assertTrue(surviving.size() < base.size());
        assertEquals(surviving, base.stream().filter(object -> !FieldVisibility.damaged(object, mask)).toList());
        assertEquals(solved, jobs.get());
        assertEquals(index(base), index(field.query(domain)));
        assertEquals(solved, jobs.get());
    }
    @Test void damageUsesBodyCenterRatherThanOverhangOrStorageOwner() {
        var key = new FieldObject.Key("test", FieldDefinition.create(1, PALETTE), "geometry", new GridPos(0, 0, 0), 4, 0);
        PileModel cube = new PileModel("cube", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
        FieldObject edge = new FieldObject(key, BlockPos.ZERO,
                PilePiece.create(cube, 1.46f, .5f, .5f, 1, new Quaternionf()));
        FieldObject body = new FieldObject(key, BlockPos.ZERO,
                PilePiece.create(cube, 1.30f, .5f, .5f, 1, new Quaternionf()));
        SpatialMask mask = new SpatialMask(); mask.set(BlockPos.ZERO);
        assertFalse(FieldVisibility.damaged(edge, mask));
        assertTrue(FieldVisibility.hit(body, FieldVisibility.cube(BlockPos.ZERO)));
        assertFalse(FieldVisibility.damaged(body, mask), "An overhanging body must survive damage outside its center cell");
        mask.clear(BlockPos.ZERO); mask.set(BlockPos.ZERO.east());
        assertTrue(FieldVisibility.damaged(edge, mask));
        assertTrue(FieldVisibility.damaged(body, mask), "Damage follows the actual body center, not storage ownership");
        assertTrue(FieldVisibility.activated(edge, Set.of(BlockPos.ZERO.east())));
        assertEquals(BlockPos.ZERO, edge.owner());
    }
    @Test void damageKeepsOverhangsOnEveryFaceAtNegativeAndDistantCoordinates() {
        var key = new FieldObject.Key("test", FieldDefinition.create(1, PALETTE), "geometry", new GridPos(0, 0, 0), 2, 0);
        var cube = new PileModel("cube", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
        for (BlockPos owner : List.of(BlockPos.ZERO, new BlockPos(-17, -60, -1), new BlockPos(16000000, 80, -16000000)))
            for (Direction direction : Direction.values()) {
                var object = new FieldObject(key, owner, PilePiece.create(cube,
                        .5f + direction.getStepX() * .4f, .5f + direction.getStepY() * .4f,
                        .5f + direction.getStepZ() * .4f, 1, new Quaternionf().rotationYXZ(.2f, -.1f, .15f)));
                BlockPos neighbor = owner.relative(direction);
                SpatialMask mask = new SpatialMask(); mask.set(neighbor);
                assertTrue(FieldVisibility.hit(object, FieldVisibility.cube(neighbor)), "The fixture must actually cross the face");
                assertFalse(FieldVisibility.damaged(object, mask), owner + "/" + direction);
                assertFalse(FieldVisibility.damaged(object, SpatialMask.load(mask.save())));
                mask.set(owner);
                assertTrue(FieldVisibility.damaged(object, mask));
            }
    }
    @Test void damageTransformsTheCollisionCenterIncludingPivotScaleAndRotation() {
        var key = new FieldObject.Key("test", FieldDefinition.create(1, PALETTE), "geometry", new GridPos(0, 0, 0), 2, 0);
        var shifted = new PileModel("shifted", "minecraft:stone", 1, 1, 1, 1,
                List.of(new CollisionBox(1, 0, 0, 2, 1, 1)));
        BlockPos owner = new BlockPos(-32, -64, -17);
        var object = new FieldObject(key, owner, PilePiece.create(shifted, .5f, .5f, .5f,
                .95f, new Quaternionf().rotationY((float) Math.PI)));
        assertEquals(-.45f, object.piece().transformPoint(1.5f, .5f, .5f).x, .0001f);
        SpatialMask mask = new SpatialMask(); mask.set(owner);
        assertFalse(FieldVisibility.damaged(object, mask), "The pivot/storage cell does not contain the body center");
        mask.clear(owner); mask.set(owner.west());
        assertTrue(FieldVisibility.damaged(object, mask));
    }
    @Test void damageUsesHalfOpenCenterCellsAcrossNegativeBoundaries() {
        var key = new FieldObject.Key("test", FieldDefinition.create(1, PALETTE), "geometry", new GridPos(0, 0, 0), 2, 0);
        var cube = new PileModel("cube", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
        BlockPos owner = new BlockPos(-16, -64, 32);
        float[] positions = {-1.0001f, -1, -.0001f, 0, .9999f, 1};
        int[] cells = {-2, -1, -1, 0, 0, 1};
        for (int axis = 0; axis < 3; axis++) for (int i = 0; i < positions.length; i++) {
            Vector3f center = new Vector3f(.5f).setComponent(axis, positions[i]);
            var object = new FieldObject(key, owner,
                    PilePiece.create(cube, center.x, center.y, center.z, 1, new Quaternionf()));
            BlockPos bodyCell = owner.offset(axis == 0 ? cells[i] : 0, axis == 1 ? cells[i] : 0, axis == 2 ? cells[i] : 0);
            SpatialMask mask = new SpatialMask(); mask.set(bodyCell);
            assertTrue(FieldVisibility.damaged(object, mask), "axis=" + axis + ", center=" + positions[i]);
            mask.clear(bodyCell);
            mask.set(bodyCell.offset(axis == 0 ? -1 : 0, axis == 1 ? -1 : 0, axis == 2 ? -1 : 0));
            assertFalse(FieldVisibility.damaged(object, mask), "Only the half-open center cell may remove the object");
        }
    }
    @Test void fieldNamespacesUseFullDefinitionAndMaterialGeometry() {
        var a = field(1).query(Set.of(BlockPos.ZERO));
        var b = field(2).query(Set.of(BlockPos.ZERO));
        assertNotEquals(index(a), index(b));
        var key = a.get(0).key();
        var collision = new FieldObject.Key(key.dimension(), key.definition(), key.geometry(), key.slot(), key.layer(), key.trial() + 1);
        assertNotEquals(key, collision);
        assertNotEquals(0, key.compareTo(collision));
    }
    @Test void droppingTemporaryPreparationKeepsCompleteAnswersAndExactFutureGrowth() {
        SpatialField field = field(42);
        Set<BlockPos> firstDomain = Set.of(BlockPos.ZERO), grown = Set.of(BlockPos.ZERO, BlockPos.ZERO.east());
        var first = field.query(firstDomain);
        long jobs = field.metrics().jobs();
        assertTrue(field.metrics().prepared() > 0);
        field.releasePreparationCache();
        assertEquals(0, field.metrics().cachedPoses());
        assertEquals(first, field.query(firstDomain));
        assertEquals(jobs, field.metrics().jobs(), "Releasing workspaces must not discard complete selected slots");
        var expanded = field.query(grown);
        assertEquals(index(field(42).query(grown)), index(expanded));
    }
    @Test void deletionPreservesBaselineAcrossColdReloadAndGrowthHasFiniteLayerDependencies() {
        Set<BlockPos> baseline = Set.of(BlockPos.ZERO, BlockPos.ZERO.east(), new BlockPos(80, 0, 0));
        SpatialField field = field(42);
        var original = field.query(baseline);
        var distant = original.stream().filter(object -> object.owner().getX() > 40).toList();
        Set<BlockPos> remaining = Set.of(BlockPos.ZERO.east(), new BlockPos(80, 0, 0));
        long jobs = field.metrics().jobs();
        var deleted = field.query(remaining, baseline);
        assertEquals(jobs, field.metrics().jobs(), "Deletion must only change overlays");
        assertEquals(deleted, field(42).query(remaining, baseline), "Cold reload must not repack the damaged hole");
        Set<BlockPos> grown = new HashSet<>(baseline); grown.add(BlockPos.ZERO.south());
        var changed = field.query(grown);
        assertEquals(distant, changed.stream().filter(object -> object.owner().getX() > 40).toList());
        assertEquals(field(42).query(grown), changed);
    }
    @Test void scopedBaselinesCoverEverySkeletonGeometryInputAtShiftedNegativeBoundaries() {
        int side = FieldRules.REGION_SIDE, coarseSide = FieldRules.SKELETON_SIDE, offset = FieldRules.SKELETON_OFFSET;
        assertEquals((2 * coarseSide + FieldRules.MAX_MODEL_RADIUS + side - 1) / side, FieldRules.DEPENDENCY_RADIUS);
        for (int target = -24; target <= 24; target++) {
            int fine = Math.floorDiv(target, side), origin = fine * side;
            int lo = Math.floorDiv(origin - 1 + offset, coarseSide);
            int hi = Math.floorDiv(origin + side + offset, coarseSide);
            for (int parent = lo; parent <= hi; parent++)
                for (int cell = (parent - 1) * coarseSide - offset; cell < (parent + 2) * coarseSide - offset; cell++)
                    assertTrue(Math.abs(Math.floorDiv(cell, side) - fine) <= FieldRules.DEPENDENCY_RADIUS);
        }
        Set<BlockPos> domain = Set.of(new BlockPos(-5, -1, 3), new BlockPos(-4, -1, 3),
                new BlockPos(-5, 0, 4), new BlockPos(-4, 0, 4), new BlockPos(24, 0, 4));
        SpatialField field = field(42);
        field.query(domain);
        Set<BlockPos> grown = new HashSet<>(domain); grown.add(new BlockPos(-5, 0, 3));
        assertEquals(field(42).query(grown), field.query(grown));
    }
    @Test void offlineGridComparisonsKeepPartialQueriesAndGrowthIndependentOfRequestOrder() {
        BlockPos origin = new BlockPos(-5, -1, 3);
        Set<BlockPos> domain = Set.of(origin, origin.east(), origin.above(), origin.south());
        for (int[] grid : new int[][]{{4, 2}, {4, 1}, {6, 1}, {8, 4}}) {
            var definition = FieldDefinition.create(42, PALETTE);
            SpatialField whole = new SpatialField("test", definition, MODELS, FieldOracle::solve, 4, grid[0], grid[1]);
            SpatialField partial = new SpatialField("test", definition, MODELS, FieldOracle::solve, 4, grid[0], grid[1]);
            Map<FieldObject.Key, FieldObject> union = new TreeMap<>();
            for (BlockPos cell : domain.stream().sorted(Comparator.reverseOrder()).toList())
                partial.query(Set.of(cell), domain).forEach(object -> union.put(object.key(), object));
            assertEquals(index(whole.query(domain)), union);
            Set<BlockPos> grown = new HashSet<>(domain); grown.add(origin.below());
            assertEquals(whole.query(grown), partial.query(grown));
            assertEquals(new SpatialField("test", definition, MODELS, FieldOracle::solve, 4, grid[0], grid[1])
                    .query(grown), whole.query(grown));
        }
    }
    private static Map<FieldObject.Key, FieldObject> index(List<FieldObject> objects) {
        Map<FieldObject.Key, FieldObject> result = new TreeMap<>();
        objects.forEach(object -> assertNull(result.put(object.key(), object)));
        return result;
    }
}
