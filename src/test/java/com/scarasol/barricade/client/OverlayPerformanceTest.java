package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Fixed poses isolate display-filter cost from changing material samples and packing results. */
@EnabledIfEnvironmentVariable(named = "BARRICADE_OVERLAY_BENCHMARK", matches = "1")
class OverlayPerformanceTest {
    @Test void fixed512CellDisplayWorkload() throws Exception {
        var world = MemoryFixture.create(false);
        var definition = new FieldDefinition(42, FieldDefinition.ALGORITHM, FieldDefinition.PARAMETERS, "benchmark");
        var model = new PileModel("rod", "minecraft:oak_fence", 1.2f, .18f, .18f, 1,
                List.of(CollisionBox.CUBE));
        Set<BlockPos> domain = new HashSet<>();
        List<FieldObject> objects = new ArrayList<>();
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> blocks = new HashMap<>();
        int trial = 0;
        for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) for (int z = 0; z < 8; z++) {
            BlockPos cell = new BlockPos(x, y, z);
            domain.add(cell);
            for (int piece = 0; piece < 3; piece++)
                objects.add(new FieldObject(new FieldObject.Key("test", definition, "fixed",
                        new GridPos(x, y, z), 0, trial++), cell,
                        PilePiece.create(model, .5f, .25f + .25f * piece, .5f, 1,
                                new Quaternionf().rotationY(piece * .2f))));
        }
        for (int x = -1; x <= 8; x++) for (int z = -1; z <= 8; z++)
            blocks.put(new BlockPos(x, -1, z), Blocks.GRASS_BLOCK.defaultBlockState());
        for (int y = 0; y < 8; y++) for (int z = 0; z < 8; z++) {
            blocks.put(new BlockPos(-1, y, z), Blocks.GRASS_BLOCK.defaultBlockState());
            blocks.put(new BlockPos(8, y, z), Blocks.GRASS_BLOCK.defaultBlockState());
        }
        var region = new FieldRegion(new UUID(0, 1), 1, definition, domain, new SpatialMask(), Map.of());
        JsonArray rows = new JsonArray();
        for (String scenario : List.of("clear", "side-walls", "all-rejected")) {
            Set<BlockPos> scope = domain;
            List<FieldObject> candidates = objects;
            if (scenario.equals("all-rejected")) {
                scope = Set.of(BlockPos.ZERO);
                candidates = objects.stream().filter(object -> object.owner().equals(BlockPos.ZERO)).toList();
                region = new FieldRegion(new UUID(0, 1), 1, definition, scope, new SpatialMask(), Map.of());
                world.context(Map.of(BlockPos.ZERO.west(), Blocks.GRASS_BLOCK.defaultBlockState(),
                        BlockPos.ZERO.east(), Blocks.GRASS_BLOCK.defaultBlockState(),
                        BlockPos.ZERO.north(), Blocks.GRASS_BLOCK.defaultBlockState()), Map.of());
            } else {
                world.context(scenario.equals("clear") ? Map.of() : blocks, Map.of());
                region = new FieldRegion(new UUID(0, 1), 1, definition, scope, new SpatialMask(), Map.of());
            }
            double[] times = new double[9];
            int visible = 0;
            int repetitions = scenario.equals("all-rejected") ? 64 : 1;
            for (int round = -3; round < times.length; round++) {
                long start = System.nanoTime();
                for (int repeat = 0; repeat < repetitions; repeat++) {
                    var terrain = new TerrainShapes(world, ignored -> true, VisualMeshFixture::collisionProxy);
                    var overlay = new FieldOverlay(region, 1, candidates, List.of(region));
                    assertTrue(overlay.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
                    visible = overlay.visible().size();
                }
                if (round >= 0) times[round] = (System.nanoTime() - start) / 1e6 / repetitions;
            }
            Arrays.sort(times);
            JsonObject row = new JsonObject();
            row.addProperty("scenario", scenario);
            row.addProperty("cells", scope.size());
            row.addProperty("candidates", candidates.size());
            row.addProperty("visible", visible);
            row.addProperty("median_ms", times[times.length / 2]);
            row.addProperty("min_ms", times[0]);
            row.addProperty("max_ms", times[times.length - 1]);
            rows.add(row);
            System.out.printf(Locale.ROOT, "OVERLAY BENCH %s candidates=%d visible=%d median=%.3fms%n",
                    scenario, candidates.size(), visible, times[times.length / 2]);
        }
        Path output = Path.of("build/verification/visual-bounds-20261114",
                System.getenv().getOrDefault("BARRICADE_BENCH_LABEL", "current") + "-overlay.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(rows));
        assertEquals(0, world.chunkRequests());
    }
}
