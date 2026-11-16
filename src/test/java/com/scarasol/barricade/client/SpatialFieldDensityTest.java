package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.FieldDefinition;
import com.scarasol.barricade.recipe.FieldRules;
import com.scarasol.barricade.sampling.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Fast rule-tuning evidence; the separate real-GPU suite verifies the production selector. */
class SpatialFieldDensityTest {
    @Test void fixedRulesProvideDensityAcrossDefaultAndRecordedMaterials() throws Exception {
        MemoryFixture.create(false).reset();
        Map<String, List<PileModel>> pools = new LinkedHashMap<>();
        try (MaterialSampler sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            var future = sampler.prepare(PileModels.palette().stream().map(model ->
                    new PileModels.Candidate(model.id(), model.blockState(), model.weight())).toList(), 1);
            while (!future.isDone()) sampler.tick();
            pools.put("default", future.join());
        }
        Path save = Path.of("run/saves/Test World/data/barricade_embedded_v1.dat");
        Path cache = Path.of("run/cache/barricade/material_samples.json");
        if (Files.isRegularFile(save) && Files.isRegularFile(cache))
            pools.put("recorded-full", StateMaskValidation.readCachedMaterials(
                    NbtIo.readCompressed(save.toFile()).getCompound("data"), cache).values().iterator().next());
        Map<String, Set<BlockPos>> cases = new LinkedHashMap<>();
        cases.put("single", domain(1, 1, 1));
        cases.put("wall-1x6", domain(6, 1, 1));
        Set<BlockPos> l = new HashSet<>(domain(4, 1, 1));
        for (int z = 1; z < 4; z++) l.add(new BlockPos(0, 0, z));
        cases.put("L", Set.copyOf(l));
        cases.put("stack-2x2x3", domain(2, 3, 2));
        Set<BlockPos> union = new HashSet<>();
        cases.values().forEach(union::addAll);
        JsonArray rows = new JsonArray();
        JsonArray diagnostics = new JsonArray();
        List<Executable> checks = new ArrayList<>();
        for (var pool : pools.entrySet()) {
            double sum = 0;
            for (long seed : new long[]{7, 42, 100, 321}) {
                var definition = new FieldDefinition(seed, FieldDefinition.ALGORITHM,
                        FieldDefinition.PARAMETERS, "density-" + pool.getKey());
                for (var entry : cases.entrySet()) {
                    SpatialField field = new SpatialField("minecraft:overworld", definition, pool.getValue(), FieldOracle::solve);
                    long start = System.nanoTime();
                    var pieces = field.query(entry.getValue());
                    double coldMs = (System.nanoTime() - start) / 1e6;
                    Set<GridPos> relative = new HashSet<>();
                    entry.getValue().forEach(pos -> relative.add(new GridPos(pos.getX(), pos.getY(), pos.getZ())));
                    var boxes = pieces.stream().flatMap(object -> object.piece().colliders(object.owner().getX(),
                            object.owner().getY(), object.owner().getZ()).stream()).toList();
                    double coverage = LegacyPileGenerator.coverage(seed, relative, boxes);
                    sum += coverage;
                    JsonObject row = new JsonObject();
                    row.addProperty("pool", pool.getKey()); row.addProperty("seed", seed);
                    row.addProperty("case", entry.getKey()); row.addProperty("coverage", coverage);
                    row.addProperty("objects", pieces.size()); row.addProperty("case_cold_oracle_ms", coldMs);
                    row.addProperty("prepared", field.metrics().prepared());
                    JsonArray layers = new JsonArray();
                    for (int layer = 0; layer < FieldRules.LAYERS; layer++) {
                        int current = layer;
                        var selected = pieces.stream().filter(object -> object.key().layer() == current).toList();
                        JsonObject detail = new JsonObject();
                        detail.addProperty("layer", layer);
                        detail.addProperty("objects", selected.size());
                        detail.addProperty("body_volume", selected.stream().mapToDouble(object ->
                                object.piece().model().volume() * Math.pow(object.piece().scale(), 3)).sum());
                        layers.add(detail);
                    }
                    row.add("layers", layers);
                    rows.add(row);
                    checks.add(() -> assertFalse(pieces.isEmpty()));
                    checks.add(() -> assertTrue(coverage >= .10, pool.getKey() + "/" + seed + "/" + entry.getKey()));
                    System.out.printf(Locale.ROOT, "DENSITY PROBE %s seed=%d %s coverage=%.3f objects=%d cold=%.1fms%n",
                            pool.getKey(), seed, entry.getKey(), coverage, pieces.size(), coldMs);
                }
            }
            System.out.printf(Locale.ROOT, "DENSITY MEAN %s %.4f%n", pool.getKey(), sum / 16);
            double mean = sum / 16;
            checks.add(() -> assertTrue(mean >= .50, pool.getKey() + ": mean coverage must reach 50% before performance acceptance"));
        }
        Path output = Path.of("build/verification/spatial-field/density-probe.json");
        Files.createDirectories(output.getParent());
        JsonObject report = new JsonObject();
        report.addProperty("scope", "Production candidate preparation with test-only CPU selector oracle; not GPU timings");
        report.addProperty("algorithm", FieldDefinition.ALGORITHM);
        report.addProperty("parameters", FieldDefinition.PARAMETERS);
        report.add("density", rows);
        report.add("diagnostics", diagnostics);
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        assertAll(checks);
    }
    private static Set<BlockPos> domain(int x, int y, int z) {
        Set<BlockPos> result = new HashSet<>();
        for (int a = 0; a < x; a++) for (int b = 0; b < y; b++) for (int c = 0; c < z; c++)
            result.add(new BlockPos(a, b, c));
        return Set.copyOf(result);
    }
}
