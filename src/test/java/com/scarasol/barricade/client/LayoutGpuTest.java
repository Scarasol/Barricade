package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL43.*;
import com.github.argon4w.acceleratedrendering.core.backends.programs.*;
import com.scarasol.barricade.pile.*;
import net.minecraft.core.BlockPos;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.lwjgl.opengl.GL;

@EnabledIfEnvironmentVariable(named = "BARRICADE_GPU_TESTS", matches = "1")
class LayoutGpuTest {
    private static long window;
    private static ComputeProgram evaluateProgram, selectionProgram;
    private static ArchivedGpuLayoutWorker worker;
    private static long workerWindow;
    private static List<PileModel> models;
    @BeforeAll static void initialize() throws Exception {
        try (MaterialSampler sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            List<CompletableFuture<PileModel>> tasks = PileModels.palette().stream().map(model ->
                    sampler.request(new MaterialSampler.Key(model.blockState(), new CompoundTag(), Direction.NORTH))
                            .thenApply(sample -> sample.model(model.id(), model.weight()))).toList();
            while (tasks.stream().anyMatch(task -> !task.isDone())) sampler.tick();
            models = tasks.stream().map(CompletableFuture::join).toList();
        }
        assertTrue(glfwInit());
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(32, 32, "Barricade compute test", 0, 0);
        assertNotEquals(0, window); glfwMakeContextCurrent(window); GL.createCapabilities();
        assertTrue(GL.getCapabilities().GL_ARB_gpu_shader_int64);
        System.out.println("Layout GPU: " + glGetString(GL_RENDERER));
        String source;
        try (var stream = LayoutGpuTest.class.getResourceAsStream("/archived/layout.compute")) {
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        long shaderStart = System.nanoTime();
        evaluateProgram = compileProgram(source, 0);
        selectionProgram = compileProgram(source, 1);
        System.out.printf(Locale.ROOT, "Layout shader initial compile/link: %.3fms (driver cache may be warm)%n",
                (System.nanoTime() - shaderStart) / 1e6);
        workerWindow = glfwCreateWindow(1, 1, "Barricade worker test", 0, 0);
        assertNotEquals(0, workerWindow);
        worker = new ArchivedGpuLayoutWorker(workerWindow, source);
        long workerStart = System.nanoTime();
        while (!worker.ready() && !worker.failed()
                && System.nanoTime() - workerStart < java.util.concurrent.TimeUnit.SECONDS.toNanos(60))
            Thread.sleep(5);
        assertTrue(worker.ready(), "Initialize the shader before measuring layout jobs, as production does");
        System.out.printf(Locale.ROOT, "Independent GPU environment wait: %.3fms (separate from layout time)%n",
                (System.nanoTime() - workerStart) / 1e6);
    }
    @AfterAll static void close() throws Exception {
        if (worker != null) worker.stop().get(5, java.util.concurrent.TimeUnit.SECONDS);
        if (workerWindow != 0) glfwDestroyWindow(workerWindow);
        if (evaluateProgram != null) evaluateProgram.delete();
        if (selectionProgram != null) selectionProgram.delete();
        if (window != 0) { GL.setCapabilities(null); glfwDestroyWindow(window); }
        glfwTerminate();
    }
    static Set<GridPos> region(int x, int y, int z) {
        Set<GridPos> cells = new HashSet<>();
        for (int i = 0; i < x; i++) for (int j = 0; j < y; j++) for (int k = 0; k < z; k++)
            cells.add(new GridPos(i, j, k));
        return cells;
    }
    @Test void historyBatchingMatchesEverySequentialGpuResult() {
        Set<BlockPos> domain = Set.of(BlockPos.ZERO, BlockPos.ZERO.east());
        var palette = PaletteSpec.create(List.of(new PileModels.Candidate("oak", "minecraft:oak_planks", 1)), "batch-test");
        List<ArchivedPileRecipe> history = new ArrayList<>();
        history.add(new ArchivedPileRecipe(1, 42, List.of(), domain, domain, Set.of(), palette.id(),
                ArchivedTerrainSnapshot.EMPTY, List.of()));
        for (long id = 2; id <= 98; id++) {
            List<ArchivedPileRecipe.Damage> damage = id == 25
                    ? List.of(new ArchivedPileRecipe.Damage(BlockPos.ZERO, true, "", ArchivedTerrainSnapshot.EMPTY)) : List.of();
            history.add(new ArchivedPileRecipe(id, 42, List.of(id - 1), domain, Set.of(), Set.of(), palette.id(),
                    ArchivedTerrainSnapshot.EMPTY, damage));
        }
        CompoundTag payload = new CompoundTag();
        ArchivedRecipeArchive.write(payload, history);
        var palettes = new net.minecraft.nbt.ListTag();
        palettes.add(palette.save());
        payload.put("Palettes", palettes);
        var heads = new net.minecraft.nbt.ListTag();
        CompoundTag head = new CompoundTag();
        head.putUUID("Id", new UUID(1, 2));
        head.putLong("Head", 98);
        heads.add(head);
        payload.put("Heads", heads);
        ArchivedRecipeStore store = new ArchivedRecipeStore();
        store.accept(payload);

        ArchivedTerrainShapes terrain = new ArchivedTerrainShapes(MemoryFixture.create(false));
        java.util.function.Function<ArchivedPileRecipe, ArchivedPileReplay.Prepared> prepare = node ->
                terrain.prepare(node, node.active().isEmpty() ? List.of() : models);
        java.util.function.Function<LayoutInput, LayoutResult> generate = input -> {
            ArchivedBatchLayout plan = ArchivedBatchLayout.prepare(input);
            return plan.decode(gpu(plan, 4));
        };
        Map<Long, List<ArchivedPileReplay.ObjectPiece>> sequential = new LinkedHashMap<>();
        for (ArchivedPileRecipe node : history)
            sequential.put(node.id(), ArchivedPileReplay.replay(prepare.apply(node),
                    node.parents().stream().map(sequential::get).toList(), List.of(), generate));
        assertFalse(sequential.get(1L).isEmpty());
        assertTrue(sequential.get(98L).size() < sequential.get(1L).size());

        Map<Long, List<ArchivedPileReplay.ObjectPiece>> batched = new LinkedHashMap<>();
        int batches = 0;
        ArchivedReplayBatch batch;
        while ((batch = ArchivedReplayBatch.prepare(store, batched, new HashSet<>(), prepare, () -> 0)) != null) {
            var outcome = batch.replay(generate);
            if (outcome.error() != null) throw new AssertionError("Failed GPU recipe " + outcome.failed(), outcome.error());
            assertEquals(0, outcome.failed());
            batched.putAll(outcome.completed());
            batches++;
        }
        assertEquals(2, batches);
        assertEquals(sequential, batched, "All identities, transforms, materials and damage must remain identical");
    }
    private int[] gpu(ArchivedBatchLayout plan, int rounds) {
        return gpu(plan, rounds, 65_536, Integer.MAX_VALUE);
    }
    private int[] gpu(ArchivedBatchLayout plan, int rounds, int evaluationBatchSize, int ownerBatchSize) {
        if (rounds == 4) {
            if (evaluationBatchSize != 65_536 || ownerBatchSize != Integer.MAX_VALUE)
                throw new IllegalArgumentException("Worker uses production dispatch batching");
            try { return worker.submit(plan).get(30, java.util.concurrent.TimeUnit.SECONDS); }
            catch (Exception exception) { throw new AssertionError("Independent GPU worker failed", exception); }
        }
        try (ArchivedLayoutGpuJob job = new ArchivedLayoutGpuJob(plan, evaluateProgram, selectionProgram,
                evaluationBatchSize, ownerBatchSize)) {
            double gpuMs = 0, selectionApplyMs = 0;
            do {
                job.submit(rounds);
                glFinish(); // Deliberate synchronization only in this offscreen verification.
                assertTrue(job.poll());
                gpuMs += job.batchMillis(); selectionApplyMs += job.selectionApplyMillis();
            } while (!job.finished());
            assertEquals(GL_NO_ERROR, glGetError());
            if (rounds == 1) System.out.printf(Locale.ROOT,
                    "GPU device timing: total=%.3fms selection/apply=%.3fms%n", gpuMs, selectionApplyMs);
            return job.read();
        }
    }
    private static ComputeProgram compileProgram(String source, int stage) {
        int versionEnd = source.indexOf('\n');
        String specialized = source.substring(0, versionEnd + 1) + "#define BARRICADE_STAGE " + stage + "\n"
                + source.substring(versionEnd + 1);
        ComputeShader shader = new ComputeShader();
        ComputeProgram result = new ComputeProgram(BarrierFlags.getFlags(BarrierFlags.SHADER_STORAGE));
        try {
            shader.setShaderSource(specialized); shader.compileShader();
            assertTrue(shader.isCompiled(), shader.getInfoLog());
            result.attachShader(shader); result.linkProgram();
            assertTrue(result.isLinked(), result.getInfoLog());
            return result;
        } catch (RuntimeException | Error failure) {
            result.delete();
            throw failure;
        } finally { shader.delete(); }
    }
    private List<RegionPiece> verify(LegacyPileGenerator.Snapshot snapshot) {
        long legacyStart = System.nanoTime();
        LegacyPileGenerator.Result legacy = LegacyPileGenerator.generate(snapshot);
        int pass = 1;
        while (legacy.continueFilling() && pass < 12)
            legacy = LegacyPileGenerator.generate(new LegacyPileGenerator.Snapshot(snapshot.seed(), pass++, snapshot.domain(),
                    snapshot.models(), legacy.pieces(), snapshot.neighbors(), snapshot.obstacles(), snapshot.unloaded(),
                    legacy.work(), Set.of(), true));
        double legacyMs = (System.nanoTime() - legacyStart) / 1e6;
        double legacyCoverage = LegacyPileGenerator.coverage(snapshot.seed(), snapshot.active(),
                legacy.pieces().stream().flatMap(piece -> piece.colliders().stream()).toList());
        long start = System.nanoTime();
        LayoutInput gpuInput = new LayoutInput(snapshot.seed(), snapshot.generation(), snapshot.domain(),
                snapshot.models(), snapshot.existing(), snapshot.neighbors(), snapshot.obstacles(), snapshot.unloaded(),
                snapshot.active(), snapshot.repack(), snapshot.preserveExisting());
        ArchivedBatchLayout plan = ArchivedBatchLayout.prepare(gpuInput);
        double preparationMs = (System.nanoTime() - start) / 1e6, cpuMs = 0, gpuMs = 0;
        int[] cpu = null, gpu = null;
        LayoutResult result = null;
        int passes = 0;
        do {
            long before = System.nanoTime();
            cpu = TestBatchSolver.solve(plan);
            long solvedCpu = System.nanoTime();
            if (passes == 0)
                assertEquals(plan.decode(cpu), plan.decode(TestBatchSolver.solve(plan)), "Test reference solver must be repeatable");
            gpu = gpu(plan, 4);
            cpuMs += (solvedCpu - before) / 1e6; gpuMs += (System.nanoTime() - solvedCpu) / 1e6;
            assertEquals(0, gpu[3]); assertEquals(cpu[0], gpu[0]); assertEquals(cpu[4], gpu[4]);
            assertEquals(plan.decode(cpu), plan.decode(gpu), "CPU/GPU must agree on every object and transform");
            if (passes == 0) {
                assertEquals(plan.decode(gpu), plan.decode(gpu(plan, 1)), "Whole-region dispatch must match worker batching");
                assertEquals(plan.decode(gpu), plan.decode(gpu(plan, 1, 1024, 32)),
                        "Dispatch chunk size must not change conflict selection or the layout");
            }
            result = plan.decode(gpu);
            passes++;
            if (result.continueFilling()) {
                long prepareStart = System.nanoTime();
                plan = ArchivedBatchLayout.prepare(new LayoutInput(snapshot.seed(), passes, snapshot.domain(),
                        snapshot.models(), result.pieces(), snapshot.neighbors(), snapshot.obstacles(), snapshot.unloaded(),
                        result.work(), Set.of(), true));
                preparationMs += (System.nanoTime() - prepareStart) / 1e6;
            }
        } while (result.continueFilling());
        List<RegionPiece> pieces = result.pieces();
        assertFalse(pieces.isEmpty());
        assertTrue(pieces.stream().allMatch(piece -> piece.piece().scale() >= .95f && piece.piece().scale() <= 1.05f));
        System.out.printf(Locale.ROOT, "Batch layout cells=%d active=%d candidates=%d objects=%d coverage=%.3f prepare=%.3fms cpu=%.3fms gpu=%.3fms passes=%d%n",
                snapshot.domain().size(), snapshot.active().size(), plan.candidates.size(), pieces.size(),
                cpu[4] / (125.0 * plan.input[7]), preparationMs, cpuMs, gpuMs, passes);
        double newCoverage = LegacyPileGenerator.coverage(snapshot.seed(), snapshot.active(),
                pieces.stream().flatMap(piece -> piece.colliders().stream()).toList());
        System.out.printf(Locale.ROOT, "Legacy objects=%d coverage=%.3f time=%.3fms; batch independent coverage=%.3f final_gain=%d%n",
                legacy.pieces().size(), legacyCoverage, legacyMs, newCoverage, cpu[4] - plan.initial[4]);
        assertTrue(newCoverage >= .45, "GPU packing must not produce a sparse or blank pile");
        return pieces;
    }
    @Test void sampledMaterialsMatchOnCpuAndGpuAndPanelsRemainExposed() {
        Set<GridPos> domain = region(5, 2, 3);
        int beds = 0;
        for (long seed : new long[]{7, 42, 100, 321}) {
            List<RegionPiece> pieces = verify(new LegacyPileGenerator.Snapshot(seed, 0, domain, models,
                    List.of(), List.of(), List.of(), List.of()));
            var panels = pieces.stream().filter(piece -> piece.piece().model().panel()).toList();
            beds += (int) pieces.stream().filter(piece -> piece.piece().model().id().equals("red_bed")).count();
            assertFalse(panels.isEmpty(), "Full-size panels need opportunities on the outside");
            for (RegionPiece panel : panels)
                assertTrue(LegacyPileGenerator.panelVisibility(panel, domain, pieces, List.of()) >= 3f/9);
            for (int i=0; i<pieces.size(); i++) for (int j=0; j<i; j++)
                for (OrientedBox a : pieces.get(i).colliders()) for (OrientedBox b : pieces.get(j).colliders())
                    assertFalse(a.intersects(b, .012f), "Objects must not substantially interpenetrate");
        }
        assertTrue(beds > 0, "Full-size sampled beds must participate in packing");
    }
    @Test void cancellingAndReloadingInputsReleasesJobsWithoutLosingTheContext() throws Exception {
        ArchivedBatchLayout plan = ArchivedBatchLayout.prepare(new LayoutInput(91, 0, region(3, 2, 2), models,
                List.of(), List.of(), List.of(), List.of()));
        var cancelled = worker.submit(plan);
        worker.cancel();
        assertTrue(cancelled.isCompletedExceptionally());
        String source;
        try (var stream = getClass().getResourceAsStream("/archived/layout.compute")) {
            source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        worker.reload(source);
        var restored = worker.submit(plan).get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(plan.decode(TestBatchSolver.solve(plan)), plan.decode(restored));
        assertFalse(worker.failed());
    }
    @Test void productionFreshPilesKeepTheApprovedCpuLayoutWithEitherBackend() {
        Set<net.minecraft.core.BlockPos> domain = new HashSet<>();
        for (GridPos cell : region(5, 2, 3))
            domain.add(new net.minecraft.core.BlockPos(100000 + cell.x(), 80 + cell.y(), -250000 + cell.z()));
        for (long seed : new long[]{7, 42, 100, 321}) {
            ArchivedPileRecipe recipe = new ArchivedPileRecipe(1, seed, List.of(), domain, domain, Set.of(),
                    "test", ArchivedTerrainSnapshot.EMPTY, List.of());
            ArchivedPileReplay.Prepared prepared = new ArchivedPileReplay.Prepared(recipe, models, List.of(), List.of(), List.of());
            var reference = TestPileReplay.replay(prepared, List.of());
            var gpuResult = TestPileReplay.replay(prepared, List.of(), List.of(), this::generateWithTestGpu);
            assertEquals(reference, gpuResult);
        }
    }

    private LayoutResult generateWithTestGpu(LayoutInput input) {
        ArchivedBatchLayout plan = ArchivedBatchLayout.prepare(input);
        return plan.decode(gpu(plan, 4));
    }

    @Test void stateMaskFrozenBaselineRestoresDestructionWithoutAnEditLog() throws Exception {
        StateMaskValidation.frozenBaselineRoundTrips(models, this::generateWithTestGpu);
    }

    @Test void stateMaskGrowthAndMergeExposeTheCurrentAlgorithmsHistoryDependence() {
        StateMaskValidation.changingBaselineIsNotACompatibleShortcut(models, this::generateWithTestGpu);
    }

    @Test void stateMaskObstacleInputsMustBeFrozenToRestoreTheSameLayout() throws Exception {
        StateMaskValidation.obstacleChangesRequireFrozenInputs(models, this::generateWithTestGpu);
    }

    @Test void stateMaskRecordedHistoryBenchmarkUsesTheRealGpuAndCachedMaterials() throws Exception {
        StateMaskValidation.benchmarkRecordedHistory(this::generateWithTestGpu, worker::deviceNanos);
    }

    @Test void embeddedDestructionRefillsOnTheGpuWithoutMovingSurvivorsOrAnotherEmbeddedBlock() {
        BlockPos pos = new BlockPos(100000, 80, -250000);
        UUID region = new UUID(1, 2);
        PaletteSpec palette = PaletteSpec.create(List.of(
                new PileModels.Candidate("oak", "minecraft:oak_planks", 1)), "resources");
        PileModel small = new PileModel("small", "minecraft:oak_planks", .4f, .4f, .4f, 1, List.of(CollisionBox.CUBE));
        ArchivedPileData data = new ArchivedPileData();
        for (int x = 0; x < 5; x++) data.add(pos.east(x), region, 42);
        long initial = data.commit(region, palette, ArchivedTerrainSnapshot.EMPTY);
        var memory = MemoryFixture.create(false);
        var shapes = new ArchivedTerrainShapes(memory);
        var before = ArchivedPileReplay.replay(shapes.prepare(data.recipe(initial), List.of(small)),
                List.of(), List.of(), this::generateWithTestGpu);
        EmbeddedBlock removed = data.addEmbedded(pos), retained = data.addEmbedded(pos.east(2));
        WorldBlock first = new WorldBlock("minecraft:stone", new CompoundTag(), removed);
        WorldBlock second = new WorldBlock("minecraft:oak_slab[type=bottom,waterlogged=false]", new CompoundTag(), retained);
        ArchivedTerrainSnapshot embedded = new ArchivedTerrainSnapshot(Map.of(pos, first, pos.east(2), second), Set.of());
        data.damage(data.nearby(pos), new ArchivedPileRecipe.Damage(pos, false, first.state(), embedded));
        data.damage(data.nearby(pos.east(2)), new ArchivedPileRecipe.Damage(pos.east(2), false, second.state(), embedded));
        long carved = data.commit(region, palette, ArchivedTerrainSnapshot.EMPTY);
        var survivors = ArchivedPileReplay.replay(shapes.prepare(data.recipe(carved), List.of()), List.of(before),
                List.of(), input -> { throw new AssertionError("Embedding must not run generation"); });
        assertTrue(survivors.size() < before.size());
        assertTrue(data.removeEmbedded(pos));
        long refilled = data.commit(region, palette,
                new ArchivedTerrainSnapshot(Map.of(pos.east(2), second), Set.of()));
        var prepared = shapes.prepare(data.recipe(refilled), List.of(small));
        var gpuResult = ArchivedPileReplay.replay(prepared, List.of(survivors), List.of(), this::generateWithTestGpu);
        assertEquals(TestPileReplay.replay(prepared, List.of(survivors)), gpuResult);
        assertTrue(gpuResult.size() > survivors.size());
        for (var survivor : survivors)
            assertSame(survivor, gpuResult.stream().filter(object -> object.key().equals(survivor.key()))
                    .findFirst().orElseThrow());
        assertEquals(Map.of(pos.east(2), retained), data.recipe(refilled).embedded());
        BlockPos origin = ArchivedPileReplay.origin(data.recipe(refilled).domain());
        for (var object : gpuResult) for (var box : object.piece().colliders(
                object.owner().getX() - origin.getX(), object.owner().getY() - origin.getY(),
                object.owner().getZ() - origin.getZ()))
            assertTrue(prepared.obstacles().stream().noneMatch(obstacle -> box.intersects(obstacle, .012f)));
        assertEquals(0, memory.chunkRequests());
    }

    @Test void occupiedFurnitureRegionWithThirtyNewCellsMatchesAndPreservesSurvivors() {
        Set<GridPos> original = region(10, 2, 10), domain = region(10, 2, 12), active = new HashSet<>(domain);
        // Keep a non-rectangular 30-cell extension, as in the reported local edit.
        active.removeAll(original);
        for (GridPos cell : active.stream().sorted().limit(10).toList()) active.remove(cell);
        List<RegionPiece> existing = LegacyPileGenerator.generate(new LegacyPileGenerator.Snapshot(17, 0, original, models,
                List.of(), List.of(), List.of(), List.of())).pieces();
        List<OrientedBox> terrain = List.of(OrientedBox.axisAligned(-1,-1,-1,11,0,13));
        List<RegionPiece> result = verify(new LegacyPileGenerator.Snapshot(17, 0, domain, models, existing,
                List.of(), terrain, List.of(), active, Set.of(), true));
        assertTrue(result.containsAll(existing), "Incremental additions must not move surviving furniture");
    }
}
