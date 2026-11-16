package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "BARRICADE_GPU_TESTS", matches = "1")
class SpatialFieldGpuTest {
    private static long window;
    private static GpuLayoutWorker worker;
    private static List<PileModel> models;
    private static final PaletteSpec PALETTE = PaletteSpec.create(List.of(
            new PileModels.Candidate("test", "minecraft:oak_planks", 1)), "test");
    @BeforeAll static void initialize() throws Exception {
        MemoryFixture.create(false).reset();
        try (MaterialSampler sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            var future = sampler.prepare(PileModels.palette().stream().map(model ->
                    new PileModels.Candidate(model.id(), model.blockState(), model.weight())).toList(), 1);
            while (!future.isDone()) sampler.tick();
            models = future.join();
        }
        assertTrue(glfwInit()); glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(1, 1, "Spatial field offscreen", 0, 0); assertNotEquals(0, window);
        String shader;
        try (var stream = SpatialFieldGpuTest.class.getResourceAsStream("/assets/barricade/shaders/packing.compute")) {
            shader = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        worker = new GpuLayoutWorker(window, shader);
    }
    @AfterAll static void close() throws Exception {
        if (worker != null) worker.stop().get(10, TimeUnit.SECONDS);
        if (window != 0) glfwDestroyWindow(window);
        glfwTerminate();
    }
    private static int[] gpu(FieldBatch batch) {
        try { return worker.submit(batch).get(60, TimeUnit.SECONDS); }
        catch (Exception exception) { throw new AssertionError("Required production field GPU failed", exception); }
    }
    private static PackingBatch packing(int count) {
        Set<GridPos> cells = new HashSet<>();
        for (int i = 0; i < count; i++) cells.add(new GridPos(i % 8, i / 64, i / 8 % 8));
        return PackingBatch.prepare(new PackingInput(42 + count, 0, cells, models, List.of(), List.of(), 8, false));
    }
    @Test void currentPackingAgreesWithTheOracleBeforeAndAfterQueuedCancellation() throws Exception {
        PackingBatch batch = packing(12);
        int[] expected = batch.compactResult(FieldOracle.solve(batch));
        assertArrayEquals(expected, gpu(batch));
        CountDownLatch started = new CountDownLatch(1), resume = new CountDownLatch(1);
        var preparation = worker.prepare(() -> {
            started.countDown(); assertTrue(resume.await(10, TimeUnit.SECONDS)); return null;
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            var cancelled = worker.submit(batch);
            worker.cancel();
            assertTrue(preparation.isCompletedExceptionally());
            assertTrue(cancelled.isCompletedExceptionally());
        } finally {
            resume.countDown();
        }
        assertArrayEquals(expected, gpu(batch));
    }
    @Test void persistentPackingBuffersResetAcrossSizesReloadsAndDispatchBoundaries() throws Exception {
        boolean crossedDispatchBoundary = false;
        for (int size : new int[]{1, 12, 128, 512, 3}) {
            PackingBatch batch = packing(size);
            for (int phase = 0; phase < 5; phase++) {
                int first = batch.input[batch.input[16] + phase * batch.input[7] * 2];
                int last = batch.input[batch.input[16] + (phase + 1) * batch.input[7] * 2 - 1];
                crossedDispatchBoundary |= last - first > 16_384;
            }
            int[] expected = batch.compactResult(FieldOracle.solve(batch));
            assertArrayEquals(expected, gpu(batch), "Buffer resize for " + size + " cells");
            assertArrayEquals(expected, gpu(batch), "Repeated input must reset all state");
        }
        assertTrue(crossedDispatchBoundary, "Exercise more than one evaluation dispatch for a single phase");
        String source;
        try (var stream = SpatialFieldGpuTest.class.getResourceAsStream("/assets/barricade/shaders/packing.compute")) {
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        worker.reload(source + "\n");
        PackingBatch afterReload = packing(3);
        assertArrayEquals(afterReload.compactResult(FieldOracle.solve(afterReload)), gpu(afterReload));
        worker.reload(source);
        assertArrayEquals(afterReload.compactResult(FieldOracle.solve(afterReload)), gpu(afterReload));
        assertFalse(worker.failed());
    }
    @Test void owningThreadPreparationSupportsNestedGpuJobsAndCancelsWithoutPoisoningNextQuery() throws Exception {
        PackingBatch batch = packing(3);
        int[] expected = batch.compactResult(FieldOracle.solve(batch));
        assertArrayEquals(expected, worker.prepare(() -> gpu(batch)).get(10, TimeUnit.SECONDS));
        CountDownLatch started = new CountDownLatch(1), resume = new CountDownLatch(1);
        var cancelled = worker.prepare(() -> {
            started.countDown(); assertTrue(resume.await(10, TimeUnit.SECONDS));
            return gpu(batch);
        });
        try { assertTrue(started.await(10, TimeUnit.SECONDS)); worker.cancel(); }
        finally { resume.countDown(); }
        assertTrue(cancelled.isCompletedExceptionally());
        assertArrayEquals(expected, worker.prepare(() -> gpu(batch)).get(10, TimeUnit.SECONDS));
        var failed = worker.prepare(() -> { throw new IllegalArgumentException("Invalid test field"); });
        assertThrows(ExecutionException.class, () -> failed.get(10, TimeUnit.SECONDS));
        assertArrayEquals(expected, worker.prepare(() -> gpu(batch)).get(10, TimeUnit.SECONDS));
        assertFalse(worker.failed());
    }
    @Test void independentRegionGroupsAgreeWithSeparateJobsAndTheIntegerOracle() {
        List<PackingBatch> plans = new ArrayList<>();
        for (int count : new int[]{1, 8, 70}) {
            Set<GridPos> cells = new HashSet<>();
            for (int i = 0; i < count; i++) cells.add(new GridPos(i % 5, i / 25, (i / 5) % 5));
            plans.add(PackingBatch.prepare(new PackingInput(42 + count, 0, cells, models, List.of(), List.of(), 6, false)));
        }
        GridPos active = new GridPos(0, 0, 0);
        PileModel cube = new PileModel("cube", "minecraft:oak_planks", .5f, .5f, .5f, 1, List.of(CollisionBox.CUBE));
        plans.add(PackingBatch.prepare(new PackingInput(42, 0, Set.of(active, new GridPos(0, -1, 0)),
                List.of(cube), List.of(), List.of(), 2, false, Set.of(active))));
        Set<GridPos> closed = new HashSet<>();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++)
            closed.add(new GridPos(x, y, z));
        PackingBatch firstClosed = PackingBatch.prepare(new PackingInput(19, 0, closed, models, List.of(), List.of(), 2, true));
        plans.add(firstClosed);
        plans.add(PackingBatch.prepare(new PackingInput(19, 1, closed, models,
                firstClosed.decode(gpu(firstClosed)), List.of(), 2, true)));
        PackingBatch guarded = PackingBatch.prepare(new PackingInput(42, 0, Set.of(active),
                List.of(cube), List.of(), List.of(), 2, true, Set.of(active),
                List.of(OrientedBox.axisAligned(-.5f, -.5f, -.5f, 1.5f, 1.5f, 1.5f))));
        plans.add(guarded);
        assertEquals(0, guarded.initial[4], "A protected sight corridor must not count as body coverage");
        PackingGroup group = new PackingGroup(plans);
        List<int[]> combined = group.split(gpu(group));
        PackingGroup one = new PackingGroup(List.of(plans.get(2)));
        assertPoses(plans.get(2).decode(gpu(plans.get(2))),
                plans.get(2).decode(one.split(gpu(one)).get(0)), "single grouped view");
        for (int i = 0; i < plans.size(); i++) {
            PackingBatch plan = plans.get(i);
            int[] reference = FieldOracle.solve(plan), selected = gpu(plan);
            assertEquals(plan.resultWords(), selected.length);
            assertTrue(selected.length < plan.initial.length);
            assertArrayEquals(plan.compactResult(reference), selected, "Complete compact result including guards and owner coverage");
            var expected = plan.decode(reference);
            var separate = plan.decode(selected);
            if (plans.get(i) == guarded) assertTrue(separate.isEmpty(), "Bodies must respect external skeleton corridors");
            else assertFalse(separate.isEmpty(), "Region " + i + " must retain valid active-center candidates");
            assertPoses(expected, separate, "oracle, region " + i);
            assertPoses(separate, plans.get(i).decode(combined.get(i)), "group isolation, region " + i);
        }
        List<PackingBatch> reverse = new ArrayList<>(plans); Collections.reverse(reverse);
        PackingGroup reversed = new PackingGroup(reverse);
        List<int[]> output = reversed.split(gpu(reversed));
        for (int i = 0; i < plans.size(); i++)
            assertPoses(plans.get(i).decode(combined.get(i)),
                    plans.get(i).decode(output.get(plans.size() - 1 - i)), "reversed buffer ranges " + i);
        assertFalse(worker.failed());
    }
    private static void assertPoses(List<RegionPiece> expected, List<RegionPiece> actual, String context) {
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++)
            if (!expected.get(i).equals(actual.get(i)))
                fail(context + ": first different object " + i + ", counts " + expected.size() + "/" + actual.size()
                        + ", expected " + expected.get(i) + ", actual " + actual.get(i));
        assertEquals(expected.size(), actual.size(), context);
    }
    @Test void coldDefaultFieldUsesTheProductionPathAndReproducesTheFinalGeometryAfterGrowth() {
        var definition = FieldDefinition.create(42, PALETTE);
        SpatialField field = new SpatialField("minecraft:overworld", definition, models, batch -> {
            int[] selected = gpu(batch);
            if (batch instanceof PackingBatch packing)
                assertEquals(packing.decode(FieldOracle.solve(batch)), packing.decode(selected));
            else if (batch instanceof PackingGroup group) {
                var expected = group.split(FieldOracle.solve(group));
                var actual = group.split(selected);
                for (int i = 0; i < group.plans.size(); i++)
                    assertPoses(group.plans.get(i).decode(expected.get(i)), group.plans.get(i).decode(actual.get(i)), "field group " + i);
            } else assertArrayEquals(FieldOracle.solve(batch), selected);
            return selected;
        });
        long start = System.nanoTime();
        var first = field.query(Set.of(BlockPos.ZERO));
        double coldMs = (System.nanoTime() - start) / 1e6;
        assertFalse(first.isEmpty());
        start = System.nanoTime();
        var expanded = field.query(Set.of(BlockPos.ZERO, BlockPos.ZERO.east()));
        assertEquals(expanded, new SpatialField("minecraft:overworld", definition, models, SpatialFieldGpuTest::gpu)
                .query(Set.of(BlockPos.ZERO, BlockPos.ZERO.east())));
        System.out.printf(Locale.ROOT, "SPATIAL FIELD default cold=%.3fms growth=%.3fms objects=%d/%d metrics=%s device=%.3fms dispatches=%d%n",
                coldMs, (System.nanoTime() - start) / 1e6, first.size(), expanded.size(), field.metrics(),
                worker.deviceNanos() / 1e6, worker.dispatches());
    }
    @Test void nativeEmbeddingRemovalRestoresTheExactPreviousFieldWithoutSolvingOrUndoingDamage() {
        var definition = FieldDefinition.create(91, PALETTE);
        PileModel small = new PileModel("small", "minecraft:oak_planks", .4f, .4f, .4f, 1, List.of(CollisionBox.CUBE));
        SpatialField field = new SpatialField("test", definition, List.of(small), SpatialFieldGpuTest::gpu);
        BlockPos pos = new BlockPos(100000, 80, -250000);
        Set<BlockPos> domain = Set.of(pos, pos.east(), pos.east(2));
        var base = field.query(domain);
        long jobs = field.metrics().jobs(), gpuJobs = worker.completed();
        UUID id = new UUID(1, 2);
        var memory = MemoryFixture.create(false);
        TerrainShapes terrain = new TerrainShapes(memory, ignored -> true, VisualMeshFixture::collisionProxy);
        FieldRegion before = new FieldRegion(id, 1, definition, domain, new SpatialMask(), Map.of());
        List<FieldObject> displayed = display(before, base, terrain);
        assertEquals(base, displayed);
        EmbeddedBlock embedded = new EmbeddedBlock(pos.east(), 7, 1);
        memory.context(Map.of(pos.east(), Blocks.STONE.defaultBlockState()), Map.of());
        terrain.clear();
        FieldRegion during = new FieldRegion(id, 2, definition, domain, new SpatialMask(), Map.of(pos.east(), embedded));
        var covered = display(during, base, terrain);
        assertTrue(covered.size() < displayed.size());
        EmbeddedBlock revised = new EmbeddedBlock(pos.east(), embedded.instance(), 2);
        memory.context(Map.of(pos.east(), Blocks.OAK_SLAB.defaultBlockState()), Map.of());
        terrain.clear();
        display(new FieldRegion(id, 3, definition, domain, new SpatialMask(), Map.of(pos.east(), revised)), base, terrain);
        memory.reset(); terrain.clear();
        var restored = display(new FieldRegion(id, 4, definition, domain, new SpatialMask(), Map.of()), base, terrain);
        assertEquals(displayed, restored, "Removing E must restore every original undamaged identity and transform");
        SpatialMask mask = new SpatialMask(); mask.set(pos);
        terrain.clear();
        var damaged = display(new FieldRegion(id, 5, definition, domain, mask, Map.of()), base, terrain);
        assertTrue(damaged.size() < restored.size());
        memory.context(Map.of(pos.east(), Blocks.STONE.defaultBlockState()), Map.of()); terrain.clear();
        display(new FieldRegion(id, 6, definition, domain, mask, Map.of(pos.east(), embedded)), base, terrain);
        memory.reset(); terrain.clear();
        assertEquals(damaged, display(new FieldRegion(id, 7, definition, domain, mask, Map.of()), base, terrain));
        assertEquals(jobs, field.metrics().jobs()); assertEquals(gpuJobs, worker.completed());
        assertEquals(0, memory.chunkRequests());
    }
    @Test void realLayersMatchReversedCellQueriesAndMaterialOrderAcrossFarNegativeChunkSeams() {
        var definition = FieldDefinition.create(7, PALETTE);
        BlockPos origin = new BlockPos(-17, 80, -16000001);
        Set<BlockPos> domain = Set.of(origin, origin.east(), origin.east(2), origin.south(), origin.south(2),
                origin.above(), origin.above(2));
        var complete = new SpatialField("minecraft:overworld", definition, models, SpatialFieldGpuTest::gpu).query(domain);
        List<PileModel> reversed = new ArrayList<>(models); Collections.reverse(reversed);
        SpatialField incremental = new SpatialField("minecraft:overworld", definition, reversed, SpatialFieldGpuTest::gpu);
        Map<FieldObject.Key, FieldObject> union = new TreeMap<>();
        for (BlockPos cell : domain.stream().sorted(Comparator.reverseOrder()).toList())
            for (FieldObject object : incremental.query(Set.of(cell), domain)) {
                FieldObject previous = union.putIfAbsent(object.key(), object);
                if (previous != null) assertEquals(previous, object);
            }
        assertEquals(complete, List.copyOf(union.values()));
        assertEquals(complete, incremental.query(domain));
    }
    private static List<FieldObject> display(FieldRegion region, List<FieldObject> base, TerrainShapes terrain) {
        FieldOverlay overlay = new FieldOverlay(region, region.revision(), base, List.of(region));
        assertTrue(overlay.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        return overlay.visible();
    }
    @Test void negativeHeightGroundedPileRetainsItsGeneratedObjectsThroughTheDisplayPipeline() throws Exception {
        var definition = FieldDefinition.create(3882473527322147738L, PALETTE);
        Set<BlockPos> domain = Set.of(new BlockPos(-3, -60, 5), new BlockPos(-2, -60, 5),
                new BlockPos(-3, -60, 6), new BlockPos(-2, -60, 6));
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> ground = new HashMap<>();
        for (int x = -8; x <= 3; x++) for (int z = 0; z <= 11; z++)
            ground.put(new BlockPos(x, -61, z), Blocks.GRASS_BLOCK.defaultBlockState());
        var memory = MemoryFixture.create(false);
        memory.context(ground, Map.of());
        TerrainShapes terrain = new TerrainShapes(memory, ignored -> true);
        List<List<PileModel>> pools = new ArrayList<>(List.of(models));
        var save = java.nio.file.Path.of("run/saves/Test World/data/barricade_embedded_v1.dat");
        var cache = java.nio.file.Path.of("run/cache/barricade/material_samples.json");
        if (java.nio.file.Files.isRegularFile(save) && java.nio.file.Files.isRegularFile(cache))
            pools.addAll(StateMaskValidation.readCachedMaterials(
                    net.minecraft.nbt.NbtIo.readCompressed(save.toFile()).getCompound("data"), cache).values());
        for (var pool : pools) {
            SpatialField field = new SpatialField("minecraft:overworld", definition, pool, SpatialFieldGpuTest::gpu);
            var base = field.query(domain);
            assertFalse(base.isEmpty(), "The grounded field must generate objects");
            var region = new FieldRegion(new UUID(1, 2), 1, definition, domain, new SpatialMask(), Map.of());
            var visible = display(region, base, terrain);
            System.out.printf("GROUNDED FIELD pool=%d generated=%d visible=%d%n", pool.size(), base.size(), visible.size());
            assertEquals(base, visible, "Ground contact must not hide a closed-body field");
            try {
                BarricadeRenderer.replace(region.id(), visible);
                assertEquals(base.size(), BarricadeRenderer.counters().objects());
            } finally { BarricadeRenderer.clear(); }
        }
        assertEquals(0, memory.chunkRequests());
    }
    @Test void nativeWallsUseToleranceOrTheLeastPenetratingCandidateAndRemovalRestoresTheField() {
        for (BlockPos origin : List.of(new BlockPos(-3, -60, 5), new BlockPos(-1, 63, 17),
                new BlockPos(-6, 66, 18))) {
            List<Set<BlockPos>> domains = List.of(Set.of(origin),
                    Set.of(origin, origin.east(), origin.south()),
                    Set.of(origin, origin.east(), origin.above(), origin.above().east()));
            for (Set<BlockPos> domain : domains) {
                var definition = FieldDefinition.create(42, PALETTE);
                SpatialField field = new SpatialField("minecraft:overworld", definition, models, SpatialFieldGpuTest::gpu);
                var base = SpatialFieldScaling.query(worker, field, domain);
                assertFalse(base.isEmpty(), "A small enclosed domain must not be empty: " + domain);
                Map<BlockPos, net.minecraft.world.level.block.state.BlockState> shell = new HashMap<>();
                for (BlockPos cell : domain)
                    for (BlockPos near : BlockPos.betweenClosed(cell.offset(-1, -1, -1), cell.offset(1, 1, 1)))
                        if (!domain.contains(near)) shell.put(near.immutable(), Blocks.STONE.defaultBlockState());
                var memory = MemoryFixture.create(false);
                memory.context(shell, Map.of());
                var region = new FieldRegion(new UUID(1, 2), 1, definition, domain, new SpatialMask(), Map.of());
                Map<BlockPos, List<OrientedBox>> obstacles = new HashMap<>();
                shell.keySet().forEach(pos -> obstacles.put(pos, List.of(OrientedBox.axisAligned(0, 0, 0, 1, 1, 1))));
                TerrainShapes terrain = new TerrainShapes(memory, ignored -> true);
                assertScoredSelection(base, display(region, base, terrain), obstacles);
                long jobs = worker.completed();
                memory.reset(); terrain.clear();
                assertEquals(base, display(region, base, terrain), "Removing walls restores, not regenerates, the field");
                assertEquals(jobs, worker.completed());
                assertEquals(0, memory.chunkRequests());
            }
        }
    }
    @Test void recordedProductionComparisonAndDensityEvidence() throws Exception {
        SpatialFieldEvidence.run(models, SpatialFieldGpuTest::gpu, worker);
    }
    @Test void smallFieldsKeepNaturalPosesAcrossSeedsGridSeamsAndTheReportedScreenshot() throws Exception {
        SpatialFieldNaturalness.run(models, SpatialFieldGpuTest::gpu, worker);
    }
    @Test void coldProductionFieldsCompareRegionSizesThrough512Cells() throws Exception {
        SpatialFieldScaling.run(models, SpatialFieldGpuTest::gpu, worker);
    }
    @Test void actualGpuAllowsTaggedFieldInterpenetrationAndReproducesColdLayouts() {
        TaggedFieldChecks.verify(SpatialFieldGpuTest::gpu);
        var defaults = PaletteSpec.create(List.of(new PileModels.Candidate("a", "minecraft:stone", 1)), "test");
        var leftDefinition = FieldDefinition.create(7501765099988357541L,
                PaletteSpec.create(defaults.candidates(), "test", "example:a", defaults));
        var rightDefinition = FieldDefinition.create(42,
                PaletteSpec.create(defaults.candidates(), "test", "example:b", defaults));
        BlockPos leftCell = BlockPos.ZERO, rightCell = leftCell.east();
        Set<BlockPos> own = Set.of(rightCell), foreign = Set.of(leftCell);
        var cube = new PileModel("cube", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
        var objects = new SpatialField("test", rightDefinition, List.of(cube), SpatialFieldGpuTest::checkedPacking)
                .query(own, own, foreign);
        assertFalse(objects.isEmpty(), "The original seed-42 adjacent-tag singleton must generate objects");
        var left = new FieldRegion(new UUID(1, 2), 1, leftDefinition, foreign, new SpatialMask(), Map.of());
        var right = new FieldRegion(new UUID(3, 4), 1, rightDefinition, own, new SpatialMask(), Map.of());
        var memory = MemoryFixture.create(false);
        memory.context(Map.of(rightCell.below(), Blocks.GRASS_BLOCK.defaultBlockState(),
                leftCell.below(), Blocks.GRASS_BLOCK.defaultBlockState(),
                leftCell, Blocks.STONE.defaultBlockState()), Map.of());
        FieldOverlay overlay = new FieldOverlay(right, 1, objects, List.of(left, right));
        assertTrue(overlay.advance(new TerrainShapes(memory, ignored -> true), Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(objects, overlay.visible(), "Ground and a foreign logical cell must not hide the restored singleton");
        assertEquals(0, memory.chunkRequests());
        System.out.printf("TAG OVERLAP original_failure generated=%d visible=%d gpu_oracle_parity=true%n",
                objects.size(), overlay.visible().size());
        assertFalse(worker.failed());
    }
    private static int[] checkedPacking(FieldBatch batch) {
        int[] expected = FieldOracle.solve(batch), actual = gpu(batch);
        if (batch instanceof PackingBatch plan) assertArrayEquals(plan.compactResult(expected), actual);
        else if (batch instanceof PackingGroup group) {
            var reference = group.split(expected);
            var selected = group.split(actual);
            for (int i = 0; i < group.plans.size(); i++)
                assertArrayEquals(group.plans.get(i).compactResult(reference.get(i)), selected.get(i));
        } else fail("Required production packing batch");
        return actual;
    }
    @Test void neighboringFineRegionsKeepPermittedOverlapsAndAgreeWithTheOracle() {
        Set<BlockPos> domain = new HashSet<>();
        BlockPos.betweenClosed(1, 1, 1, 2, 2, 2).forEach(cell -> domain.add(cell.immutable()));
        PileModel cube = new PileModel("cube", "minecraft:stone", .9f, .9f, .9f, 1, List.of(CollisionBox.CUBE));
        var definition = new FieldDefinition(42, FieldDefinition.ALGORITHM, FieldDefinition.PARAMETERS, "overlap-allowed");
        var objects = new SpatialField("test", definition, List.of(cube), SpatialFieldGpuTest::checkedPacking, 2).query(domain);
        assertFalse(objects.isEmpty());
        int overlaps = 0;
        for (int i = 0; i < objects.size(); i++) for (int j = 0; j < i; j++) {
            FieldObject a = objects.get(i), b = objects.get(j);
            if (a.key().slot().equals(b.key().slot())) continue;
            var boxes = a.piece().colliders(0, 0, 0);
            var others = b.piece().colliders(b.owner().getX() - a.owner().getX(),
                    b.owner().getY() - a.owner().getY(), b.owner().getZ() - a.owner().getZ());
            if (boxes.stream().anyMatch(box -> others.stream().anyMatch(other -> box.intersects(other, .005f)))) overlaps++;
        }
        assertTrue(overlaps > 0, "The GPU must not reintroduce a cross-region no-overlap rule");
    }
    @Test void bothLayersActuallyOverhangOwnershipCornersWithoutChangingNaturalSizes() {
        int[] escaped = new int[2];
        List<PileModel> fillers = models.stream().filter(model -> !model.bulk() && !model.elongated()).toList();
        for (List<PileModel> pool : List.of(models, fillers)) for (long seed : new long[]{7, 42})
            for (BlockPos cell : BlockPos.betweenClosed(-7, -7, -7, -6, -6, -6)) {
                Set<BlockPos> domain = Set.of(cell.immutable());
                var definition = FieldDefinition.create(seed, PALETTE);
                var objects = new SpatialField("minecraft:overworld", definition, pool,
                        SpatialFieldGpuTest::checkedPacking).query(domain);
                assertFalse(objects.isEmpty());
                for (FieldObject object : objects) {
                    assertTrue(object.piece().scale() >= .95f && object.piece().scale() <= 1.05f);
                    int layer = object.key().geometry().startsWith("skeleton:") ? 0 : 1;
                    int side = layer == 0 ? FieldRules.SKELETON_SIDE : FieldRules.REGION_SIDE;
                    GridPos origin = object.key().slot();
                    for (OrientedBox box : object.piece().colliders(object.owner().getX() - origin.x(),
                            object.owner().getY() - origin.y(), object.owner().getZ() - origin.z())) {
                        float allowance = 225f / 1024 + .003f;
                        assertTrue(box.minX() >= -allowance && box.minY() >= -allowance && box.minZ() >= -allowance);
                        assertTrue(box.maxX() <= side + allowance && box.maxY() <= side + allowance
                                && box.maxZ() <= side + allowance);
                        if (box.minX() < -.01f || box.minZ() < -.01f || box.maxX() > side + .01f
                                || box.maxY() > side + .01f || box.maxZ() > side + .01f) escaped[layer]++;
                    }
                }
            }
        assertTrue(escaped[0] > 0, "The coarse ownership cube must not cancel exterior overhang");
        assertTrue(escaped[1] > 0, "The fine ownership cube must not cancel exterior overhang");
    }
    @Test void reportedCrossTagFenceCellUsesFreeAirAndReproducesColdLayouts() throws Exception {
        var save = java.nio.file.Path.of("run/saves/Test World (1)/data/barricade_embedded_v1.dat");
        var cache = java.nio.file.Path.of("run/cache/barricade/material_samples.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(save)
                && java.nio.file.Files.isRegularFile(cache));
        byte[] saved = java.nio.file.Files.readAllBytes(save), samples = java.nio.file.Files.readAllBytes(cache);
        var data = net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(saved)).getCompound("data");
        var materials = StateMaskValidation.readCachedMaterials(data, cache);
        List<FieldRegion> regions = new ArrayList<>();
        for (var value : data.getList("Regions", 10)) regions.add(FieldRegion.load((CompoundTag) value));
        BlockPos missing = new BlockPos(12, -59, -1);
        var target = regions.stream().filter(region -> region.domain().contains(missing)
                && region.definition().seed() == -4025746975994481983L).findFirst();
        org.junit.jupiter.api.Assumptions.assumeTrue(target.isPresent(), "Optional reported-world fixture is absent");
        for (FieldRegion region : regions) {
            Set<BlockPos> baseline = new HashSet<>(), foreign = new HashSet<>(), own = new HashSet<>();
            for (FieldRegion other : regions) {
                if (other.definition().equals(region.definition())) baseline.addAll(other.baseline());
                if (!other.definition().palette().equals(region.definition().palette())) foreign.addAll(other.baseline());
                else own.addAll(other.domain());
            }
            foreign.removeAll(own);
            var pool = materials.get(region.definition().palette());
            SpatialField field = new SpatialField("minecraft:overworld", region.definition(), pool, SpatialFieldGpuTest::checkedPacking);
            var objects = worker.prepare(() -> field.query(region.domain(), baseline, foreign)).get(60, TimeUnit.SECONDS);
            if (region.id().equals(target.orElseThrow().id())) {
                assertVisibleFenceCells(region.domain(), objects, "reported mixed-tag singleton");
                assertEquals(objects, worker.prepare(() -> new SpatialField("minecraft:overworld", region.definition(),
                        pool, SpatialFieldGpuTest::checkedPacking).query(region.domain(), baseline, foreign)).get(60, TimeUnit.SECONDS));
                System.out.printf("OVERHANG BOUNDS reported=%s objects=%d candidates=%d jobs=%d%n",
                        missing, objects.size(), field.metrics().prepared(), field.metrics().jobs());
            }
        }
        assertArrayEquals(saved, java.nio.file.Files.readAllBytes(save));
        assertArrayEquals(samples, java.nio.file.Files.readAllBytes(cache));
    }
    @Test void reportedDefaultTagGapKeepsBodiesWhenOnlyTheirOverhangHitsSavedDamage() throws Exception {
        var snapshot = java.nio.file.Path.of("build/verification/default-gap-20261112/fixture/fields.dat");
        var save = java.nio.file.Files.isRegularFile(snapshot) ? snapshot
                : java.nio.file.Path.of("run/saves/Test World (1)/data/barricade_embedded_v1.dat");
        var cache = java.nio.file.Path.of("run/cache/barricade/material_samples.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(save)
                && java.nio.file.Files.isRegularFile(cache), "Optional reported-world fixture is absent");
        byte[] saved = java.nio.file.Files.readAllBytes(save), samples = java.nio.file.Files.readAllBytes(cache);
        var data = net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(saved)).getCompound("data");
        List<FieldRegion> regions = new ArrayList<>();
        for (var value : data.getList("Regions", 10)) regions.add(FieldRegion.load((CompoundTag) value));
        var target = regions.stream().filter(region -> region.id().equals(UUID.fromString("c479f910-b7bf-4821-b80a-12900a593142"))
                && region.definition().seed() == -8530157715089968831L).findFirst();
        org.junit.jupiter.api.Assumptions.assumeTrue(target.isPresent(), "Optional original screenshot definition is absent");
        FieldRegion region = target.orElseThrow();
        var pool = StateMaskValidation.readCachedMaterials(data, cache).get(region.definition().palette());
        Set<BlockPos> baseline = new HashSet<>();
        for (FieldRegion other : regions)
            if (other.definition().equals(region.definition())) baseline.addAll(other.baseline());
        SpatialField field = new SpatialField("minecraft:overworld", region.definition(), pool, SpatialFieldGpuTest::checkedPacking);
        var base = worker.prepare(() -> field.query(region.domain(), baseline)).get(60, TimeUnit.SECONDS);
        assertEquals(22, base.size(), "Damage policy must not change the generated layout");
        BlockPos gap = new BlockPos(7, -59, -5);
        var expected = base.stream().filter(object -> FieldVisibility.hit(object, FieldVisibility.cube(gap))).toList();
        assertEquals(3, expected.size());
        SpatialMask mask = region.mask();
        assertEquals(Set.of(new BlockPos(7, -60, -5), new BlockPos(7, -58, -6)), mask.cells());
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> ground = new HashMap<>();
        for (BlockPos cell : region.domain())
            for (BlockPos near : BlockPos.betweenClosed(cell.offset(-3, -1, -3), cell.offset(3, -1, 3)))
                if (near.getY() == -61) ground.put(near.immutable(), Blocks.GRASS_BLOCK.defaultBlockState());
        var memory = MemoryFixture.create(false); memory.context(ground, Map.of());
        TerrainShapes terrain = new TerrainShapes(memory, ignored -> true);
        long jobs = field.metrics().jobs(), gpuJobs = worker.completed();
        var visible = display(region, base, terrain);
        assertEquals(expected, visible.stream().filter(object -> FieldVisibility.hit(object, FieldVisibility.cube(gap))).toList());
        assertEquals(visible, display(FieldRegion.load(region.save()), base, terrain), "Saved damage must round-trip without clearing it");
        assertEquals(mask, region.mask());
        assertEquals(jobs, field.metrics().jobs());
        assertEquals(gpuJobs, worker.completed(), "Restoring center-only visibility must not submit replacement geometry");
        assertEquals(0, memory.chunkRequests());
        assertArrayEquals(saved, java.nio.file.Files.readAllBytes(save));
        assertArrayEquals(samples, java.nio.file.Files.readAllBytes(cache));
        System.out.printf("BODY CENTER screenshot generated=%d visible=%d gap_generated=%d gap_visible=%d damage_records=%d overlay_gpu_jobs=0 gpu_oracle_parity=true%n",
                base.size(), visible.size(), expected.size(),
                visible.stream().filter(object -> FieldVisibility.hit(object, FieldVisibility.cube(gap))).count(), mask.size());
        assertFalse(worker.failed());
    }
    @Test void reconstructedFurnaceCollisionProxyRejectsDeepOverlapAndRestoresExactOriginalLayout() throws Exception {
        var snapshot = java.nio.file.Path.of("build/verification/embedded-overlap-20261112/fixture/fields.dat");
        var save = java.nio.file.Files.isRegularFile(snapshot) ? snapshot
                : java.nio.file.Path.of("run/saves/Test World (1)/data/barricade_embedded_v1.dat");
        var cache = java.nio.file.Path.of("run/cache/barricade/material_samples.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(save)
                && java.nio.file.Files.isRegularFile(cache), "Optional reported-world fixture is absent");
        byte[] saved = java.nio.file.Files.readAllBytes(save), samples = java.nio.file.Files.readAllBytes(cache);
        var data = net.minecraft.nbt.NbtIo.readCompressed(new java.io.ByteArrayInputStream(saved)).getCompound("data");
        List<FieldRegion> regions = new ArrayList<>();
        for (var value : data.getList("Regions", 10)) regions.add(FieldRegion.load((CompoundTag) value));
        var target = regions.stream().filter(region -> region.id().equals(UUID.fromString("2a419d33-6ed2-4b67-9f0f-4f2db837a63d"))
                && region.definition().seed() == -3677411117422479405L && region.domain().size() == 4).findFirst();
        org.junit.jupiter.api.Assumptions.assumeTrue(target.isPresent(), "Optional original furniture definition is absent");
        FieldRegion region = target.orElseThrow();
        assertTrue(region.embedded().isEmpty(), "The captured baseline is after the reported furnace was removed");
        var pool = StateMaskValidation.readCachedMaterials(data, cache).get(region.definition().palette());
        Set<BlockPos> baseline = new HashSet<>();
        for (FieldRegion other : regions)
            if (other.definition().equals(region.definition())) baseline.addAll(other.baseline());
        var field = new SpatialField("minecraft:overworld", region.definition(), pool, SpatialFieldGpuTest::checkedPacking);
        var base = worker.prepare(() -> field.query(region.domain(), baseline)).get(60, TimeUnit.SECONDS);
        assertEquals(12, base.size());
        var memory = MemoryFixture.create(false);
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> states = new HashMap<>();
        for (BlockPos cell : region.domain())
            for (BlockPos near : BlockPos.betweenClosed(cell.offset(-3, -1, -3), cell.offset(3, -1, 3)))
                if (near.getY() == -61) states.put(near.immutable(), Blocks.GRASS_BLOCK.defaultBlockState());
        memory.context(states, Map.of());
        TerrainShapes terrain = new TerrainShapes(memory, ignored -> true, VisualMeshFixture::collisionProxy);
        var original = display(region, base, terrain);
        assertEquals(11, original.size(), "Existing center-based damage must remain effective");
        long jobs = field.metrics().jobs(), gpuJobs = worker.completed();
        BlockPos pos = new BlockPos(3, -60, 14);
        var embedded = new EmbeddedBlock(pos, 24, 1);
        states.put(pos, Blocks.FURNACE.defaultBlockState()); memory.context(states, Map.of()); terrain.clear();
        var during = new FieldRegion(region.id(), region.revision(), region.definition(), region.domain(),
                region.mask(), Map.of(pos, embedded), region.baseline());
        var visible = display(during, base, terrain);
        Map<BlockPos, List<OrientedBox>> obstacles = new HashMap<>();
        obstacles.put(pos, embedded.transform(embedded.model(memory)).colliders(0, 0, 0));
        assertScoredSelection(original, visible, obstacles);
        assertFalse(visible.isEmpty(), "Embedding must not erase the whole pile");
        assertTrue(visible.size() < original.size(), "Obvious intersections must actually be filtered");
        assertEquals(visible, display(FieldRegion.load(during.save()), base, terrain));
        states.remove(pos); memory.context(states, Map.of()); terrain.clear();
        assertEquals(original, display(region, base, terrain), "Unembedding must restore every original undamaged identity and pose");
        assertEquals(region.mask(), during.mask());
        assertEquals(jobs, field.metrics().jobs());
        assertEquals(gpuJobs, worker.completed(), "Embedding and restoration must not request replacement geometry");
        assertEquals(0, memory.chunkRequests());
        assertArrayEquals(saved, java.nio.file.Files.readAllBytes(save));
        assertArrayEquals(samples, java.nio.file.Files.readAllBytes(cache));
        System.out.printf("EMBEDDED VISUAL collision_proxy generated=%d before=%d embedded=%d hidden=%d restored=%d overlay_gpu_jobs=0 gpu_oracle_parity=true%n",
                base.size(), original.size(), visible.size(), original.size() - visible.size(), original.size());
        assertFalse(worker.failed());
    }
    private static void assertScoredSelection(List<FieldObject> candidates, List<FieldObject> actual,
                                              Map<BlockPos, List<OrientedBox>> obstacles) {
        Map<FieldObject, Float> depths = new HashMap<>();
        Map<BlockPos, List<FieldObject>> groups = new HashMap<>();
        for (FieldObject object : candidates) {
            float depth = 0;
            var boxes = object.piece().colliders(0, 0, 0);
            for (var obstacle : obstacles.entrySet())
                for (OrientedBox box : boxes) for (OrientedBox other : obstacle.getValue())
                    depth = Math.max(depth, box.penetration(other,
                            object.owner().getX() - obstacle.getKey().getX(),
                            object.owner().getY() - obstacle.getKey().getY(),
                            object.owner().getZ() - obstacle.getKey().getZ()));
            depths.put(object, depth);
            groups.computeIfAbsent(object.owner(), ignored -> new ArrayList<>()).add(object);
        }
        Set<FieldObject> expected = new HashSet<>();
        for (var group : groups.values()) {
            var passing = group.stream().filter(object -> depths.get(object) <= 1f / 32 + .000001f).toList();
            if (!passing.isEmpty()) expected.addAll(passing);
            else expected.add(group.stream().min(Comparator.comparingDouble((FieldObject object) -> depths.get(object))
                    .thenComparing(FieldObject::key)).orElseThrow());
        }
        assertEquals(expected, Set.copyOf(actual), "Keep every passing candidate, or exactly the least penetrating one per owner");
        assertEquals(expected.size(), actual.size(), "Fallback must not duplicate an identity");
    }

    @Test void sampledFenceOnlyFieldsKeepVisibleGeometryInEveryUndamagedCell() {
        List<PileModels.Candidate> candidates = java.util.stream.Stream.of(Blocks.OAK_FENCE, Blocks.SPRUCE_FENCE,
                Blocks.BIRCH_FENCE, Blocks.JUNGLE_FENCE, Blocks.ACACIA_FENCE, Blocks.DARK_OAK_FENCE,
                Blocks.MANGROVE_FENCE, Blocks.CHERRY_FENCE, Blocks.BAMBOO_FENCE, Blocks.CRIMSON_FENCE, Blocks.WARPED_FENCE)
                .map(block -> {
                    String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
                    return block == Blocks.OAK_FENCE ? new PileModels.Candidate("oak_post", id, 2)
                            : new PileModels.Candidate(id, id, 1);
                }).toList();
        try (MaterialSampler sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            var prepared = sampler.prepare(candidates, 1);
            while (!prepared.isDone()) sampler.tick();
            List<PileModel> pool = prepared.join();
            assertEquals(candidates.size(), pool.size());
            java.util.function.Function<FieldBatch, int[]> checked = batch -> {
                int[] expected = FieldOracle.solve(batch), actual = gpu(batch);
                if (batch instanceof PackingBatch plan) assertArrayEquals(plan.compactResult(expected), actual);
                else if (batch instanceof PackingGroup group) {
                    var references = group.split(expected);
                    var selected = group.split(actual);
                    for (int i = 0; i < group.plans.size(); i++)
                        assertArrayEquals(group.plans.get(i).compactResult(references.get(i)), selected.get(i));
                } else fail("Fence reconstruction must use the production packing pipeline");
                return actual;
            };
            BlockPos reported = new BlockPos(11, -60, -3);
            var definition = FieldDefinition.create(-3061543087357994095L, PALETTE);
            Set<BlockPos> row = Set.of(reported, reported.east(), reported.east(2));
            SpatialField recorded = new SpatialField("minecraft:overworld", definition, pool, checked);
            var objects = recorded.query(row);
            assertVisibleFenceCells(row, objects, "reported row");
            System.out.printf("FENCE FIX seed=%d missing_cell=%s now_owned=%d row_objects=%d%n",
                    definition.seed(), reported, objects.stream().filter(object -> object.owner().equals(reported)).count(),
                    objects.size());
            assertEquals(objects, new SpatialField("minecraft:overworld", definition, pool, checked).query(row));
            for (BlockPos cell : row)
                assertEquals(objects.stream().filter(object -> FieldVisibility.activated(object, Set.of(cell))).toList(),
                        recorded.query(Set.of(cell), row));
            int cases = 1;
            List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
            for (long seed : new long[]{7, 42, 100})
                for (BlockPos location : BlockPos.betweenClosed(-1, -61, -1, 1, -59, 1)) {
                    BlockPos origin = location.immutable();
                    var selection = FieldDefinition.create(seed, PALETTE);
                    for (Set<BlockPos> domain : List.of(Set.of(origin), Set.of(origin, origin.east()),
                            Set.of(origin, origin.east(), origin.south(), origin.east().south()))) {
                        var result = new SpatialField("minecraft:overworld", selection, pool, checked).query(domain);
                        checks.add(() -> assertVisibleFenceCells(domain, result, "seed=" + seed + ", domain=" + domain));
                        cases++;
                    }
                }
            for (long seed : new long[]{7, 42, 100}) {
                Set<BlockPos> domain = new HashSet<>();
                BlockPos.betweenClosed(0, -60, 0, 2, -60, 2).forEach(cell -> domain.add(cell.immutable()));
                var result = new SpatialField("minecraft:overworld", FieldDefinition.create(seed, PALETTE), pool, checked).query(domain);
                checks.add(() -> assertVisibleFenceCells(domain, result, "nine-cell interior seed=" + seed));
                cases++;
            }
            System.out.printf("FENCE FIX verified_cases=%d sampled_materials=%d gpu_oracle_parity=true%n", cases, pool.size());
            assertAll(checks);
            assertThreeGrassNeighborsRemainVisible(pool, checked);
        }
    }
    private static void assertThreeGrassNeighborsRemainVisible(List<PileModel> pool,
            java.util.function.Function<FieldBatch, int[]> checked) {
        assertTrue(pool.stream().allMatch(model -> model.boxes().size() == 1 && model.span().y == 1));
        var memory = MemoryFixture.create(false);
        List<BlockPos> origins = List.of(new BlockPos(-11, -60, 8), new BlockPos(-18, -60, 9),
                new BlockPos(12, -59, -1), new BlockPos(11, -60, -3),
                new BlockPos(-26, -60, -18), new BlockPos(6, -60, -7));
        int scenes = 0, fallbacks = 0;
        for (BlockPos cell : origins)
            for (long seed : new long[]{42, -4025746975994481983L, -3061543087357994095L}) {
                var definition = FieldDefinition.create(seed, PALETTE);
                var objects = new SpatialField("minecraft:overworld", definition, pool, checked).query(Set.of(cell));
                assertFalse(objects.isEmpty());
                assertTrue(objects.stream().allMatch(object -> object.owner().equals(cell)));
                for (boolean corner : new boolean[]{false, true})
                    for (var direction : new net.minecraft.core.Direction[]{net.minecraft.core.Direction.NORTH,
                            net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST}) {
                        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
                        for (var pos : BlockPos.betweenClosed(cell.offset(-3, -1, -3), cell.offset(3, -1, 3)))
                            blocks.put(pos.immutable(), Blocks.GRASS_BLOCK.defaultBlockState());
                        if (corner) {
                            blocks.put(cell.relative(direction), Blocks.GRASS_BLOCK.defaultBlockState());
                            blocks.put(cell.relative(direction.getClockWise()), Blocks.GRASS_BLOCK.defaultBlockState());
                            blocks.put(cell.relative(direction).relative(direction.getClockWise()), Blocks.GRASS_BLOCK.defaultBlockState());
                        } else for (var side : new net.minecraft.core.Direction[]{net.minecraft.core.Direction.NORTH,
                                net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.WEST})
                            if (side != direction) blocks.put(cell.relative(side), Blocks.GRASS_BLOCK.defaultBlockState());
                        memory.context(blocks, Map.of());
                        var terrain = new TerrainShapes(memory, ignored -> true);
                        var region = new FieldRegion(new UUID(1, 2), 1, definition, Set.of(cell), new SpatialMask(), Map.of());
                        var view = terrain.view(region.baseline(), Map.of());
                        var passed = objects.stream().filter(view::visible).toList();
                        var visible = display(region, objects, terrain);
                        assertFalse(visible.isEmpty(), "Three grass neighbors must not erase an undamaged fence cell");
                        if (passed.isEmpty()) {
                            fallbacks++;
                            assertEquals(1, visible.size());
                            double minimum = objects.stream().mapToDouble(view::penetration).min().orElseThrow();
                            assertEquals(minimum, view.penetration(visible.get(0)), .000001);
                        } else assertEquals(passed, visible, "A passing candidate must not enable additional failed candidates");
                        scenes++;
                    }
            }
        assertEquals(144, scenes);
        assertTrue(fallbacks > 0);
        assertEquals(0, memory.chunkRequests());
        System.out.printf("FENCE GRASS scenes=%d visible=%d minimum_penetration_fallbacks=%d gpu_oracle_parity=true%n",
                scenes, scenes, fallbacks);
    }
    private static void assertVisibleFenceCells(Set<BlockPos> domain, List<FieldObject> objects, String context) {
        assertFalse(objects.isEmpty(), "Fence field must not be empty: " + context);
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> ground = new HashMap<>();
        for (BlockPos cell : domain) if (!domain.contains(cell.below()))
            ground.put(cell.below(), Blocks.GRASS_BLOCK.defaultBlockState());
        var memory = MemoryFixture.create(false);
        memory.context(ground, Map.of());
        var region = new FieldRegion(new UUID(1, 2), 1, objects.get(0).key().definition(), domain, new SpatialMask(), Map.of());
        var visible = display(region, objects, new TerrainShapes(memory, ignored -> true));
        assertEquals(objects, visible, "Ground must not suppress fence geometry: " + context);
        for (BlockPos cell : domain)
            assertTrue(visible.stream().anyMatch(object -> {
                var piece = object.piece();
                var center = piece.transformPoint(.5f, .5f, .5f).add(object.owner().getX() - cell.getX(),
                        object.owner().getY() - cell.getY(), object.owner().getZ() - cell.getZ());
                // Isolated fence posts render one block high, not their 1.5-block collision height.
                var half = new org.joml.Vector3f(.125f, .5f, .125f)
                        .mul(piece.model().sizeX(), piece.model().sizeY(), piece.model().sizeZ()).mul(piece.scale());
                return new OrientedBox(center, half, piece.rotation()).intersects(OrientedBox.axisAligned(0, 0, 0, 1, 1, 1), .001f);
            }), "No visible fence geometry: " + context + ", cell=" + cell + ", domain=" + domain);
        assertEquals(0, memory.chunkRequests());
    }
}
