package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

import com.scarasol.barricade.pile.FieldBatch;
import com.scarasol.barricade.pile.FieldOracle;
import com.scarasol.barricade.pile.PackingBatch;
import com.scarasol.barricade.pile.PackingGroup;
import com.scarasol.barricade.pile.PileModel;
import com.scarasol.barricade.pile.SpatialField;
import com.scarasol.barricade.recipe.FieldRegion;
import com.scarasol.barricade.recipe.PaletteSpec;
import com.scarasol.barricade.sampling.MemoryFixture;
import com.scarasol.barricade.sampling.MaterialSampler;
import com.scarasol.barricade.sampling.SampleCache;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class NativeModelSceneTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "BARRICADE_NATIVE_SCENE", matches = ".+")
    void recordedNativePaletteReportsGenerationAndDisplayWithoutChangingTheWorld() throws Exception {
        Path file = Path.of(System.getenv("BARRICADE_NATIVE_SCENE"));
        Path cache = Path.of(System.getenv("BARRICADE_NATIVE_SAMPLES"));
        String scope = System.getenv("BARRICADE_NATIVE_SCOPE");
        assertNotNull(scope, "Select the recorded material environment explicitly");
        byte[] saved = Files.readAllBytes(file), samples = Files.readAllBytes(cache);
        MemoryFixture.create(false).reset();
        try (var recorded = new SampleCache(cache); var solver = new Solver()) {
            CompoundTag data = NbtIo.readCompressed(new ByteArrayInputStream(saved)).getCompound("data");
            var palettes = new HashMap<String, PaletteSpec>();
            for (Tag value : data.getList("Palettes", Tag.TAG_COMPOUND)) {
                var palette = PaletteSpec.load((CompoundTag) value);
                palettes.put(palette.id(), palette);
            }
            int scenes = 0;
            for (Tag value : data.getList("Regions", Tag.TAG_COMPOUND)) {
                FieldRegion region = FieldRegion.load((CompoundTag) value);
                var palette = palettes.get(region.definition().palette());
                if (!palette.tag().equals("barricade:native_model_test")) continue;
                scenes++;
                var models = new ArrayList<PileModel>();
                for (var candidate : palette.candidates()) {
                    var entry = recorded.get(scope, new MaterialSampler.Key(
                            candidate.state(), new CompoundTag(), Direction.NORTH));
                    assertNotNull(entry, "Missing recorded native material: " + candidate.state());
                    if (entry.sample() != null) models.add(entry.sample().model(candidate.id(), candidate.weight()));
                }
                System.out.printf("NATIVE SCENE seed=%d domain=%s baseline=%s mask=%s%n",
                        region.definition().seed(), region.domain(), region.baseline(), region.mask().cells());
                models.forEach(model -> System.out.printf("NATIVE MATERIAL id=%s span=%s%n", model.id(), model.span()));
                var field = new SpatialField("minecraft:overworld", region.definition(), models, solver::solve);
                var objects = field.query(region.domain(), region.baseline());
                System.out.printf("NATIVE SCENE generated=%d%n", objects.size());
                for (BlockPos cell : region.domain().stream().sorted().toList())
                    System.out.printf("NATIVE CELL pos=%s owned=%d%n", cell,
                            objects.stream().filter(object -> object.owner().equals(cell)).count());
                assertFalse(objects.isEmpty(), "The native model scene must never have an empty base layout");
                var ground = new HashMap<BlockPos, BlockState>();
                for (BlockPos cell : region.domain())
                    ground.put(cell.below(), Blocks.GRASS_BLOCK.defaultBlockState());
                var memory = MemoryFixture.create(false);
                memory.context(ground, java.util.Map.of());
                var terrain = new TerrainShapes(memory, ignored -> true);
                var overlay = new FieldOverlay(region, 0, objects, List.of(region));
                assertTrue(overlay.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
                System.out.printf("NATIVE SCENE visible=%d%n", overlay.visible().size());
                if (!objects.isEmpty())
                    assertFalse(overlay.visible().isEmpty(), "The display selection must retain generated objects");
                try {
                    BarricadeRenderer.replace(region.id(), overlay.visible());
                    assertEquals(overlay.visible().size(), BarricadeRenderer.counters().objects());
                } finally {
                    BarricadeRenderer.clear();
                }
                assertEquals(0, memory.chunkRequests());
                if (solver.worker != null) {
                    measure("native", region, models, solver);
                    measure("all-invalid", region, List.of(new PileModel("large", "minecraft:stone", 1, 1, 1, 1,
                            List.of(new com.scarasol.barricade.pile.CollisionBox(
                                    -.75f, -.75f, -.75f, 1.75f, 1.75f, 1.75f)))), solver);
                }
                var shifted = region.domain().stream().map(pos -> pos.offset(1, 0, 2))
                        .collect(java.util.stream.Collectors.toSet());
                var comparison = new SpatialField("minecraft:overworld", region.definition(), models, solver::solve);
                System.out.printf("NATIVE SHIFTED SAME-SIZE generated=%d%n", comparison.query(shifted).size());
            }
            assertTrue(scenes > 0, "The supplied scene must contain the native-model test tag");
        } finally {
            assertArrayEquals(saved, Files.readAllBytes(file), "Never modify the recorded world");
            assertArrayEquals(samples, Files.readAllBytes(cache), "Never modify the recorded material cache");
        }
    }

    private static void measure(String label, FieldRegion region, List<PileModel> models, Solver solver) throws Exception {
        for (int round = 0; round < 3; round++) {
            var field = new SpatialField("minecraft:overworld", region.definition(), models, solver::submit);
            long device = solver.worker.deviceNanos(), start = System.nanoTime();
            var objects = solver.worker.prepare(() -> field.query(region.domain(), region.baseline()))
                    .get(60, TimeUnit.SECONDS);
            double cold = (System.nanoTime() - start) / 1e6;
            assertFalse(objects.isEmpty());
            long jobs = solver.worker.completed();
            assertEquals(objects, solver.worker.prepare(() -> field.query(region.domain(), region.baseline()))
                    .get(60, TimeUnit.SECONDS));
            assertEquals(jobs, solver.worker.completed(), "Warm native queries must not resubmit selection");
            System.out.printf(java.util.Locale.ROOT,
                    "NATIVE PRODUCTION %s round=%d cells=%d objects=%d cold=%.3fms device=%.3fms peak=%.4fMiB%n",
                    label, round + 1, region.domain().size(), objects.size(), cold,
                    (solver.worker.deviceNanos() - device) / 1e6, field.metrics().peakBufferBytes() / 1048576d);
        }
    }

    private static final class Solver implements AutoCloseable {
        private final long window;
        private final GpuLayoutWorker worker;
        Solver() throws Exception {
            if (!"1".equals(System.getenv("BARRICADE_GPU_TESTS"))) {
                window = 0; worker = null;
                return;
            }
            assertTrue(glfwInit());
            glfwDefaultWindowHints(); glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4); glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(1, 1, "Native model scene probe", 0, 0);
            assertNotEquals(0, window);
            try (var stream = getClass().getResourceAsStream("/assets/barricade/shaders/packing.compute")) {
                worker = new GpuLayoutWorker(window, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        int[] solve(FieldBatch batch) {
            int[] expected = FieldOracle.solve(batch);
            if (batch instanceof PackingGroup group) {
                var states = group.split(expected);
                for (int index = 0; index < group.plans.size(); index++)
                    report(group.plans.get(index), states.get(index));
            } else {
                report((PackingBatch) batch, expected);
            }
            if (worker == null) return expected;
            int[] actual = submit(batch);
            if (batch instanceof PackingGroup group) {
                var cpu = group.split(expected); var gpu = group.split(actual);
                for (int index = 0; index < group.plans.size(); index++)
                    assertArrayEquals(group.plans.get(index).compactResult(cpu.get(index)), gpu.get(index));
            } else {
                assertArrayEquals(((PackingBatch) batch).compactResult(expected), actual);
            }
            return actual;
        }
        int[] submit(FieldBatch batch) {
            try {
                return worker.submit(batch).get(60, TimeUnit.SECONDS);
            } catch (Exception error) {
                throw new AssertionError("Native scene GPU reproduction failed", error);
            }
        }
        private static void report(PackingBatch plan, int[] result) {
            int[] phases = new int[5];
            for (int id = 0; id < plan.size; id++)
                phases[plan.input[plan.input[10] + id * PackingBatch.META + 3]]++;
            String owners = java.util.stream.IntStream.range(0, plan.input[7])
                    .mapToObj(owner -> {
                        int p = plan.input[9] + owner * 3;
                        return "(" + plan.input[p] + "," + plan.input[p + 1] + "," + plan.input[p + 2] + ")";
                    }).collect(java.util.stream.Collectors.joining(","));
            System.out.printf("NATIVE BATCH side=%d owners=[%s] candidates=%s selected=%d%n",
                    plan.input[19] / 1024, owners, java.util.Arrays.toString(phases), result[0]);
            if (plan.input[21] != 0 && result[24] != 0)
                for (int owner = 0; owner < plan.input[7]; owner++)
                    assertTrue(result[result[13] + owner] > 0 || result[result[12] + owner] >= 44,
                            "Every cell must own an object or already meet the coverage threshold");
            if (result[0] != 0) return;
            int[] state;
            int originalOverhang = plan.input[20];
            // Isolate this bound in the test oracle, then restore before the production GPU job.
            try {
                plan.input[20] = 4096;
                state = FieldOracle.solve(plan);
            } finally {
                plan.input[20] = originalOverhang;
            }
            System.out.printf("NATIVE COUNTERFACTUAL region-bound-only-relaxed selected=%d%n", state[0]);
            var decoded = plan.decode(state);
            for (int index = plan.preserved.size(); index < decoded.size(); index++) {
                var piece = decoded.get(index);
                var boxes = piece.colliders();
                System.out.printf("NATIVE COUNTERFACTUAL accepted=%s owner=%s bounds=(%.4f,%.4f,%.4f)->(%.4f,%.4f,%.4f)"
                                + " original-region=[%.4f,%.4f]%n",
                        piece.piece().model().id(), piece.owner(),
                        boxes.stream().mapToDouble(box -> box.minX()).min().orElseThrow(),
                        boxes.stream().mapToDouble(box -> box.minY()).min().orElseThrow(),
                        boxes.stream().mapToDouble(box -> box.minZ()).min().orElseThrow(),
                        boxes.stream().mapToDouble(box -> box.maxX()).max().orElseThrow(),
                        boxes.stream().mapToDouble(box -> box.maxY()).max().orElseThrow(),
                        boxes.stream().mapToDouble(box -> box.maxZ()).max().orElseThrow(),
                        -plan.input[20] / 1024f, (plan.input[19] + plan.input[20]) / 1024f);
            }
        }
        @Override public void close() throws Exception {
            if (worker != null) worker.stop().get(10, TimeUnit.SECONDS);
            if (window != 0) {
                glfwDestroyWindow(window);
                glfwTerminate();
            }
        }
    }
}
