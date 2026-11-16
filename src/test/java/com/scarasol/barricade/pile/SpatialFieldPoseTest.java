package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.recipe.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class SpatialFieldPoseTest {
    private static final List<PileModel> MODELS = List.of(
            new PileModel("small", "minecraft:oak_planks", .4f, .3f, .4f, 1, List.of(CollisionBox.CUBE)),
            new PileModel("compound", "minecraft:stone", .8f, .8f, .8f, 1, List.of(
                    new CollisionBox(0, 0, 0, .2f, 1, 1), new CollisionBox(.2f, 0, 0, 1, .2f, 1))),
            new PileModel("panel", "minecraft:oak_door", .2f, 1, 1, 1,
                    List.of(new CollisionBox(0, 0, 0, 1, 2, 1))));
    private static SpatialField field(int side) {
        return new SpatialField("test", new FieldDefinition(42, FieldDefinition.ALGORITHM,
                FieldDefinition.PARAMETERS, "pose-tests"), MODELS, FieldOracle::solve, side);
    }
    @Test void naturalCompoundPosesRespectBothLayersLimitedOverhangBounds() {
        Set<BlockPos> domain = Set.of(new BlockPos(2, 0, 2), new BlockPos(3, 0, 2),
                new BlockPos(2, 0, 3), new BlockPos(3, 0, 3));
        var result = field(3).query(domain);
        assertFalse(result.isEmpty());
        for (FieldObject object : result) {
            assertTrue(object.piece().scale() >= .95f && object.piece().scale() <= 1.05f);
            assertSame(MODELS.stream().filter(model -> model.id().equals(object.piece().model().id())).findFirst().orElseThrow(),
                    object.piece().model());
            var origin = object.key().slot();
            int side = object.key().geometry().startsWith("skeleton:") ? FieldRules.SKELETON_SIDE : 3;
            float margin = 225f / 1024 + .003f;
            for (OrientedBox box : object.piece().colliders(object.owner().getX() - origin.x(),
                    object.owner().getY() - origin.y(), object.owner().getZ() - origin.z())) {
                assertTrue(box.minX() >= -margin && box.minY() >= -margin && box.minZ() >= -margin);
                assertTrue(box.maxX() <= side + margin && box.maxY() <= side + margin && box.maxZ() <= side + margin);
            }
        }
    }
    @Test void independentInteriorsDoNotReserveNeighborCellsOrSharedAir() {
        Set<BlockPos> domain = new HashSet<>();
        for (int x = 1; x <= 2; x++) for (int y = 1; y <= 2; y++) for (int z = 1; z <= 2; z++)
            domain.add(new BlockPos(x, y, z));
        PileModel cube = new PileModel("cube", "minecraft:stone", .9f, .9f, .9f, 1, List.of(CollisionBox.CUBE));
        var definition = new FieldDefinition(42, FieldDefinition.ALGORITHM, FieldDefinition.PARAMETERS, "overlap-allowed");
        int overlaps = 0;
        for (Set<BlockPos> cells : List.of(domain, Set.of(new BlockPos(1, 1, 1), new BlockPos(2, 1, 2)),
                Set.of(new BlockPos(1, 1, 1), new BlockPos(2, 2, 2)))) {
            var objects = new SpatialField("test", definition, List.of(cube), batch -> {
                List<PackingBatch> plans = batch instanceof PackingGroup group ? group.plans : List.of((PackingBatch) batch);
                for (PackingBatch plan : plans) for (int i = 0; i < plan.initial[1]; i++)
                    assertNotEquals(1, plan.initial[plan.initial[6] + i * PackingBatch.ENTRY + 3],
                            "A palette without panels must not gain neighbor-cell or shared-air guards");
                return FieldOracle.solve(batch);
            }, 2).query(cells);
            assertFalse(objects.isEmpty());
            overlaps += intersections(objects);
        }
        assertTrue(overlaps > 0, "Permitted inter-region overlaps must not be filtered out");
    }
    @Test void skeletonAndInteriorsKeepPartialQueriesStableAcrossFineFacesEdgesAndCorners() {
        Set<BlockPos> domain = new HashSet<>();
        for (int x = -1; x <= 0; x++) for (int y = -1; y <= 0; y++) for (int z = -1; z <= 0; z++)
            domain.add(new BlockPos(x, y, z));
        List<PileModel> models = List.of(MODELS.get(0),
                new PileModel("bulk", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE)));
        var definition = new FieldDefinition(42, FieldDefinition.ALGORITHM, FieldDefinition.PARAMETERS, "layer-seams");
        var objects = new SpatialField("test", definition, models, FieldOracle::solve).query(domain);
        assertTrue(objects.stream().anyMatch(object -> object.key().geometry().startsWith("skeleton:")));
        assertTrue(objects.stream().anyMatch(object -> object.key().geometry().startsWith("interior:")));
        SpatialField partial = new SpatialField("test", definition, models, FieldOracle::solve);
        Map<FieldObject.Key, FieldObject> union = new TreeMap<>();
        for (BlockPos cell : domain.stream().sorted(Comparator.reverseOrder()).toList())
            partial.query(Set.of(cell), domain).forEach(object -> union.put(object.key(), object));
        assertEquals(objects, List.copyOf(union.values()));
    }
    @Test void acceptedPanelGuardsUseTheExactCandidateCorridorsAndAcceptedTranslation() {
        Set<GridPos> domain = Set.of(new GridPos(0, 0, 0), new GridPos(1, 0, 0),
                new GridPos(2, 0, 0), new GridPos(3, 0, 0));
        PackingBatch plan = PackingBatch.prepare(new PackingInput(42, 0, domain,
                List.of(MODELS.get(2)), List.of(), List.of(), 8, false));
        int candidate = -1;
        for (int id = 0; id < plan.size; id++)
            if (plan.input[plan.input[10] + id * PackingBatch.META + 6] == 3) { candidate = id; break; }
        assertTrue(candidate >= 0, "The fixture needs a declared panel with three real outside corridors");
        int m = plan.input[10] + candidate * PackingBatch.META;
        int[] state = plan.initial.clone();
        state[0] = 1;
        state[state[5]] = candidate;
        int[] shift = {1024, -512, 256};
        for (int axis = 0; axis < 3; axis++) state[state[5] + axis + 1] = plan.input[m + 8 + axis] + shift[axis];
        var guards = plan.guards(state);
        assertEquals(3, guards.size());
        for (int i = 0; i < guards.size(); i++) {
            int start = plan.input[12] + (plan.input[m + 5] + i) * PackingBatch.BOX;
            int[] expected = Arrays.copyOfRange(plan.input, start, start + PackingBatch.BOX);
            for (int axis = 0; axis < 3; axis++) expected[axis] += shift[axis];
            int[] actual = new int[PackingBatch.BOX];
            FixedGeometry.encode(guards.get(i), actual, 0);
            assertArrayEquals(expected, actual);
        }
        assertTrue(plan.guards(plan.initial).isEmpty(), "Rejected declarations must not become ghost obstacles");
    }
    @Test void boundedInitialCoverageMatchesEveryUnboundedProbeForCompoundBodiesAndGuards() {
        Set<GridPos> domain = new HashSet<>();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++)
            domain.add(new GridPos(x, y, z));
        Random random = new Random(137);
        for (int seed = 0; seed < 24; seed++) {
            List<OrientedBox> neighbors = new ArrayList<>();
            for (int i = 0; i < 12; i++)
                neighbors.add(new OrientedBox(new Vector3f(random.nextFloat() * 4 - 1,
                        random.nextFloat() * 4 - 1, random.nextFloat() * 4 - 1),
                        new Vector3f(.01f + random.nextFloat() * .6f,
                                .01f + random.nextFloat() * .6f, .01f + random.nextFloat() * .6f),
                        new Quaternionf().rotationYXZ(random.nextFloat() * 6,
                                random.nextFloat() * 6, random.nextFloat() * 6)));
            RegionPiece kept = new RegionPiece(new GridPos(0, 0, 0),
                    PilePiece.create(MODELS.get(1), .5f, .5f, .5f, 1, new Quaternionf()));
            PackingBatch plan = PackingBatch.prepare(new PackingInput(seed, 1, domain, MODELS,
                    List.of(kept), neighbors, 2, true, domain,
                    List.of(OrientedBox.axisAligned(0, 0, 0, 2, 2, 2))));
            int[] a = plan.input, s = plan.initial;
            int total = 0;
            for (int owner = 0; owner < a[7]; owner++) {
                int covered = 0;
                for (int sample = 0; sample < 125; sample++) {
                    int p = a[17] + owner * 375 + sample * 3;
                    boolean hit = false;
                    for (int entry = 0; entry < s[1] && !hit; entry++) {
                        int e = s[6] + entry * PackingBatch.ENTRY;
                        if (s[e + 3] == 1) continue;
                        for (int box = 0; box < s[e + 1] && !hit; box++)
                            hit = FixedGeometry.contains(a, a[12] + (s[e] + box) * PackingBatch.BOX, 0,
                                    a[p], a[p + 1], a[p + 2]);
                    }
                    assertEquals(hit, (s[s[11] + owner * 4 + sample / 32] & (1 << (sample % 32))) != 0);
                    if (hit) covered++;
                }
                assertEquals(covered, s[s[12] + owner]);
                total += covered;
            }
            assertEquals(total, s[4]);
        }
    }
    @Test void closedRegionProjectionSeatsWholeBodiesOnWallsAndRejectsOversizedModels() {
        assertTrue(FieldRules.FINGERPRINT.contains(":trials=" + String.join(",",
                Arrays.stream(PackingBatch.TRIALS).mapToObj(Integer::toString).toList())));
        assertTrue(FieldRules.FINGERPRINT.contains(":rounds=" + String.join(",",
                Arrays.stream(PackingBatch.ROUNDS).mapToObj(Integer::toString).toList())));
        Set<GridPos> domain = new HashSet<>();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++)
            domain.add(new GridPos(x, y, z));
        PileModel cube = new PileModel("cube", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
        PackingBatch plan = PackingBatch.prepare(new PackingInput(19, 0, domain, List.of(cube),
                List.of(), List.of(), 2, true));
        var pieces = plan.decode(FieldOracle.solve(plan));
        assertFalse(pieces.isEmpty());
        int wallContacts = 0;
        for (RegionPiece piece : pieces) {
            assertTrue(piece.piece().scale() >= .95f && piece.piece().scale() <= 1.05f);
            for (OrientedBox box : piece.colliders()) {
                int[] fixed = FixedGeometry.encode(box);
                for (int axis : new int[]{0, 2}) {
                    int radius = FixedGeometry.extent(fixed, 0, axis);
                    if (Math.abs(fixed[axis] - radius) <= 1 || Math.abs(fixed[axis] + radius - 2048) <= 1)
                        wallContacts++;
                }
            }
        }
        assertTrue(wallContacts >= 2, "Projection must recover actual side-wall contact opportunities");
        PileModel large = new PileModel("large", "minecraft:stone", 1.5f, 1.5f, 1.5f, 1,
                List.of(new CollisionBox(0, 0, 0, 2, 2, 2)));
        PackingBatch oversized = PackingBatch.prepare(new PackingInput(19, 0, domain, List.of(large),
                List.of(), List.of(), 2, true));
        assertTrue(oversized.decode(FieldOracle.solve(oversized)).isEmpty(), "An infeasible model must not be shrunk or cropped");
    }
    @Test void logicalExteriorAllowsLimitedSideAndTopOverhangButKeepsItsSupportFloor() {
        GridPos cell = new GridPos(1, 1, 1);
        PileModel cube = new PileModel("cube", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
        int tilted = 0, overhanging = 0;
        for (long seed : new long[]{7, 42, 100, 321, 7501765099988357541L}) {
            PackingBatch plan = PackingBatch.prepare(new PackingInput(seed, 0, Set.of(cell), List.of(cube),
                    List.of(), List.of(), 3, true), List.of(cube, MODELS.get(0)));
            assertEquals(1, plan.initial[1], "Only the support floor is a domain obstacle");
            assertEquals(0, plan.initial[4], "A support floor must not add coverage");
            var pieces = plan.decode(FieldOracle.solve(plan));
            assertFalse(pieces.isEmpty());
            for (RegionPiece piece : pieces) {
                if (PileGeometry.axisAlignment(piece.piece().rotation()) < .985f) tilted++;
                for (OrientedBox box : piece.colliders()) {
                    assertTrue(box.minY() >= .999f, "Logical overhang must not permit sinking below the support plane");
                    assertTrue(box.minX() >= 1 - 225f / 1024 - .003f && box.maxX() <= 2 + 225f / 1024 + .003f);
                    assertTrue(box.minZ() >= 1 - 225f / 1024 - .003f && box.maxZ() <= 2 + 225f / 1024 + .003f);
                    assertTrue(box.maxY() <= 2 + 225f / 1024 + .003f);
                    if (box.minX() < .99f || box.maxX() > 2.01f || box.minZ() < .99f
                            || box.maxZ() > 2.01f || box.maxY() > 2.01f) overhanging++;
                }
            }
        }
        assertTrue(tilted >= 3, "Full-size cubes must have usable non-axis-aligned proposals");
        assertTrue(overhanging >= 3, "Closing the logical shape again would freeze small piles into a grid");
    }
    @Test void bottomSupportIncludesTheMissingCellButNotAnInternalOwnershipPlane() {
        GridPos cell = new GridPos(0, 0, 0), below = new GridPos(0, -1, 0);
        List<PileModel> models = List.of(MODELS.get(0));
        PackingInput exposed = new PackingInput(42, 0, Set.of(cell), models,
                List.of(), List.of(), 2, true, Set.of(cell));
        PackingBatch supported = PackingBatch.prepare(exposed, models, 225);
        assertEquals(1, supported.initial[1], "The logical bottom needs its existing support floor");
        int entry = supported.initial[6], box = supported.input[12] + supported.initial[entry] * PackingBatch.BOX;
        assertEquals(3, supported.initial[entry + 3]);
        assertEquals(0, supported.input[box + 1] + FixedGeometry.extent(supported.input, box, 1));
        assertEquals(0, supported.initial[4], "The floor must not count as occupied interior");
        var pieces = supported.decode(FieldOracle.solve(supported));
        assertFalse(pieces.isEmpty());
        for (RegionPiece piece : pieces) for (OrientedBox collider : piece.colliders())
            assertTrue(collider.minY() >= -.003f, "An exposed logical bottom must support the pile");

        PackingInput continuous = new PackingInput(42, 0, Set.of(cell, below), models,
                List.of(), List.of(), 2, true, Set.of(cell));
        PackingBatch internal = PackingBatch.prepare(continuous, models, 225);
        assertEquals(0, internal.initial[1], "A real logical cell below must not gain an ownership floor");

        PackingBatch original = PackingBatch.prepare(exposed, models);
        PackingBatch zeroMargin = PackingBatch.prepare(exposed, models, 0);
        assertEquals(0, zeroMargin.initial[1], "The margin-zero API keeps its original floor enumeration");
        assertArrayEquals(original.input, zeroMargin.input);
        assertArrayEquals(original.initial, zeroMargin.initial);
    }
    @Test void confinedSlenderProposalsFitCornerCellsWithoutShrinkingTheCollider() {
        for (Vector3f span : List.of(new Vector3f(.25f, 1.5f, .25f),
                new Vector3f(1.5f, .25f, .25f), new Vector3f(.25f, .25f, 1.5f)))
            for (long seed : new long[]{7, 42, 100})
                for (int trial = 0; trial < PackingBatch.TRIALS[4]; trial += 4) {
                    Quaternionf rotation = PileGeometry.confinedOrientation(span, seed, trial);
                    assertEquals(rotation, PileGeometry.confinedOrientation(span, seed, trial));
                    OrientedBox box = new OrientedBox(new Vector3f(), new Vector3f(span).mul(.95f / 2), rotation);
                    assertTrue(box.maxX() - box.minX() < 1 + 225f / 1024);
                    assertTrue(box.maxY() - box.minY() < 1);
                    assertTrue(box.maxZ() - box.minZ() < 1 + 225f / 1024);
                }
        Vector3f cube = new Vector3f(1, 1, 1), slab = new Vector3f(1, .5f, 1);
        for (int trial = 0; trial < 64; trial += 4) {
            assertEquals(PileGeometry.confinedOrientation(42, trial), PileGeometry.confinedOrientation(cube, 42, trial));
            assertEquals(PileGeometry.confinedOrientation(42, trial), PileGeometry.confinedOrientation(slab, 42, trial));
        }
    }
    @Test void fenceSizedCollidersGenerateAtTheReportedCornerWithoutChangingOtherMaterials() {
        PileModel fence = new PileModel("fence", "minecraft:oak_fence", 1, 1, 1, 1,
                List.of(new CollisionBox(.375f, 0, .375f, .625f, 1.5f, .625f)));
        var definition = new FieldDefinition(-3061543087357994095L, FieldDefinition.ALGORITHM,
                FieldDefinition.PARAMETERS, "fence-regression");
        BlockPos origin = new BlockPos(11, -60, -3);
        Set<BlockPos> domain = Set.of(origin, origin.east(), origin.east(2));
        var objects = new SpatialField("minecraft:overworld", definition, List.of(fence), FieldOracle::solve).query(domain);
        for (BlockPos cell : domain)
            assertTrue(objects.stream().anyMatch(object -> object.owner().equals(cell)), "Empty fence owner: " + cell);
        assertEquals(objects, new SpatialField("minecraft:overworld", definition, List.of(fence), FieldOracle::solve).query(domain));
    }
    private static int intersections(List<FieldObject> objects) {
        int count = 0;
        for (int i = 0; i < objects.size(); i++) for (int j = 0; j < i; j++) {
            FieldObject a = objects.get(i), b = objects.get(j);
            if (a.key().slot().equals(b.key().slot())) continue;
            var left = a.piece().colliders(0, 0, 0);
            var right = b.piece().colliders(b.owner().getX() - a.owner().getX(),
                    b.owner().getY() - a.owner().getY(), b.owner().getZ() - a.owner().getZ());
            for (OrientedBox x : left) for (OrientedBox y : right) {
                int[] encoded = new int[FixedGeometry.STRIDE * 2];
                FixedGeometry.encode(x, encoded, 0); FixedGeometry.encode(y, encoded, FixedGeometry.STRIDE);
                if (FixedGeometry.intersects(encoded, 0, 0, FixedGeometry.STRIDE, 0, -5)) count++;
            }
        }
        return count;
    }
    @Test void haloGeometryMustNotDrainCandidateCentersOutOfTheirActiveRegion() {
        GridPos active = new GridPos(0, 0, 0);
        PileModel cube = new PileModel("cube", "minecraft:oak_planks", .5f, .5f, .5f, 1, List.of(CollisionBox.CUBE));
        PackingBatch plan = PackingBatch.prepare(new PackingInput(42, 0, Set.of(active, new GridPos(0, -1, 0)),
                List.of(cube), List.of(), List.of(), 2, false, Set.of(active)));
        var pieces = plan.decode(FieldOracle.solve(plan));
        assertFalse(pieces.isEmpty(), "Settling must stop at the active region, not reject every candidate in its halo");
        assertTrue(pieces.stream().allMatch(piece -> piece.owner().equals(active)));
    }
    @Test void smallerScratchGridPreservesTheReusedPanelAndElongatedCandidatesAndAcceptedPoses() {
        Set<GridPos> domain = Set.of(new GridPos(0, 0, 0), new GridPos(1, 0, 0),
                new GridPos(0, -1, 0), new GridPos(1, -1, 0));
        Set<GridPos> active = Set.of(new GridPos(0, 0, 0), new GridPos(1, 0, 0));
        List<PileModel> large = List.of(MODELS.get(2),
                new PileModel("bar", "minecraft:stone", 1.6f, .5f, .5f, 1, List.of(CollisionBox.CUBE)));
        List<RegionPiece> previous = List.of();
        for (int pass = 0; pass < 3; pass++) {
            var old = ArchivedBatchLayout.prepare(new LayoutInput(42, pass, domain, large, previous,
                    List.of(), List.of(), List.of(), active, Set.of(), true));
            old.input[18] = 1; old.input[19] = 2 * 1024; old.input[20] = Math.round(FieldRules.OVERHANG * 1024);
            var current = PackingBatch.prepare(new PackingInput(42, pass, domain, large, previous,
                    List.of(), 2, false, active));
            assertEquals(old.candidates, current.candidates);
            assertTrue(current.input[6] < old.input[6]);
            var expected = old.decode(TestBatchSolver.solve(old)).pieces();
            previous = current.decode(FieldOracle.solve(current));
            assertEquals(expected, previous, "Scratch grid dimensions must not alter pass " + pass);
        }
    }
    @Test void fullInitialQuotasOmitOnlyUnusableGeometryAndPreserveLaterCandidatesAndSelectedPoses() {
        Set<GridPos> domain = new HashSet<>();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++)
            domain.add(new GridPos(x, y, z));
        PileModel bar = new PileModel("bar", "minecraft:stone", 1.6f, .4f, .4f, 1, List.of(CollisionBox.CUBE));
        PileModel bulk = new PileModel("bulk", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
        List<PileModel> models = List.of(MODELS.get(0), MODELS.get(2), bar, bulk);
        List<RegionPiece> kept = List.of(
                new RegionPiece(new GridPos(0, 0, 0), PilePiece.create(MODELS.get(2), .25f, 1, .5f, 1, new Quaternionf())),
                new RegionPiece(new GridPos(1, 0, 0), PilePiece.create(bar, 0, .25f, 1.5f, 1, new Quaternionf())),
                new RegionPiece(new GridPos(1, 1, 1), PilePiece.create(bulk, .5f, .5f, .5f, 1, new Quaternionf())),
                new RegionPiece(new GridPos(2, 1, 1), PilePiece.create(bulk, .5f, .5f, .5f, 1, new Quaternionf())));
        for (int seed : new int[]{7, 42, 100}) {
            var reference = ArchivedBatchLayout.prepareWithAxisProposals(new LayoutInput(seed, 1, domain, models, kept,
                    List.of(), List.of(), List.of(), domain, Set.of(), true));
            reference.input[18] = 1; reference.input[19] = 3 * 1024; reference.input[20] = 0;
            var current = PackingBatch.prepare(new PackingInput(seed, 1, domain, models, kept, List.of(), 3, true));
            assertEquals(reference.candidates.stream().filter(piece -> !piece.piece().model().elongated()
                    && !piece.piece().model().bulk()).toList(), current.candidates);
            for (int phase = 0; phase < 3; phase++)
                for (int owner = 0; owner < current.input[7]; owner++) {
                    int range = current.input[16] + (phase * current.input[7] + owner) * 2;
                    assertEquals(current.input[range], current.input[range + 1]);
                }
            var expected = reference.decode(TestBatchSolver.solve(reference.input, reference.initial, PackingBatch.ROUNDS)).pieces();
            assertEquals(expected, current.decode(FieldOracle.solve(current)));
            assertTrue(current.input.length < reference.input.length);
        }
    }
    @Test void mixedMaterialsRemainExactAcrossFarPartitionsAndPreparationCacheRelease() {
        BlockPos origin = new BlockPos(-17, -1, -16000001);
        Set<BlockPos> domain = Set.of(origin, origin.east(), origin.east(2), origin.south(), origin.above());
        List<FieldObject> whole = field(3).query(domain);
        SpatialField incremental = field(3);
        Map<FieldObject.Key, FieldObject> union = new TreeMap<>();
        for (BlockPos cell : domain.stream().sorted(Comparator.reverseOrder()).toList()) {
            for (FieldObject object : incremental.query(Set.of(cell), domain)) {
                FieldObject previous = union.putIfAbsent(object.key(), object);
                if (previous != null) assertEquals(previous, object);
            }
            incremental.releasePreparationCache();
        }
        assertFalse(whole.isEmpty());
        assertEquals(whole, List.copyOf(union.values()));
        assertEquals(whole, incremental.query(domain));
    }
}
