package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtIo;

/** Small production fields must not buy density by becoming a grid of upright whole cubes. */
final class SpatialFieldNaturalness {
    private SpatialFieldNaturalness() { }

    static void run(List<PileModel> defaults, Function<FieldBatch, int[]> gpu, GpuLayoutWorker worker) throws Exception {
        Map<String, List<PileModel>> pools = new LinkedHashMap<>();
        pools.put("default", defaults);
        Path save = Path.of("run/saves/Test World/data/barricade_embedded_v1.dat");
        Path cache = Path.of("run/cache/barricade/material_samples.json");
        byte[] saved = Files.isRegularFile(save) ? Files.readAllBytes(save) : null;
        byte[] samples = Files.isRegularFile(cache) ? Files.readAllBytes(cache) : null;
        if (saved != null && samples != null)
            pools.put("recorded-full", StateMaskValidation.readCachedMaterials(
                    NbtIo.readCompressed(save.toFile()).getCompound("data"), cache).values().iterator().next());
        else System.out.println("SMALL FIELD recorded-full fixture unavailable; still testing the default pool");
        JsonArray rows = new JsonArray();
        List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
        for (var pool : pools.entrySet()) {
            double tiltSum = 0, coverageSum = 0;
            int cases = 0, allAligned = 0;
            for (BlockPos origin : List.of(new BlockPos(-3, -60, 5), new BlockPos(-1, 63, 17), BlockPos.ZERO))
                for (long seed : new long[]{7, 42, 100, 321, 7501765099988357541L})
                    for (int[] size : new int[][]{{1, 1, 1}, {2, 1, 1}, {2, 1, 2}, {2, 2, 2}}) {
                        Set<BlockPos> domain = new HashSet<>();
                        for (int x = 0; x < size[0]; x++) for (int y = 0; y < size[1]; y++)
                            for (int z = 0; z < size[2]; z++) domain.add(origin.offset(x, y, z));
                        var definition = FieldDefinition.create(seed, PaletteSpec.create(List.of(
                                new PileModels.Candidate("test", "minecraft:oak_planks", 1)), "test"));
                        SpatialField field = new SpatialField("minecraft:overworld", definition, pool.getValue(), gpu);
                        long start = System.nanoTime();
                        var objects = SpatialFieldScaling.query(worker, field, domain);
                        double ms = (System.nanoTime() - start) / 1e6;
                        long tilted = objects.stream().filter(object ->
                                LegacyPileGenerator.axisAlignment(object.piece().rotation()) < .985f).count();
                        double fraction = objects.isEmpty() ? 0 : (double) tilted / objects.size();
                        Set<GridPos> relative = new HashSet<>();
                        domain.forEach(cell -> relative.add(new GridPos(cell.getX() - origin.getX(),
                                cell.getY() - origin.getY(), cell.getZ() - origin.getZ())));
                        double coverage = LegacyPileGenerator.coverage(seed, relative,
                                objects.stream().flatMap(object -> object.piece().colliders(
                                        object.owner().getX() - origin.getX(), object.owner().getY() - origin.getY(),
                                        object.owner().getZ() - origin.getZ()).stream()).toList());
                        JsonObject row = new JsonObject();
                        row.addProperty("pool", pool.getKey()); row.addProperty("seed", seed);
                        row.add("origin", new Gson().toJsonTree(new int[]{origin.getX(), origin.getY(), origin.getZ()}));
                        row.add("size", new Gson().toJsonTree(size));
                        row.addProperty("objects", objects.size()); row.addProperty("tilted", tilted);
                        row.addProperty("tilted_fraction", fraction); row.addProperty("coverage", coverage);
                        row.addProperty("cold_ms", ms);
                        row.addProperty("bulk", objects.stream().filter(object -> object.piece().model().bulk()).count());
                        row.addProperty("skeleton", objects.stream().filter(object ->
                                object.key().geometry().startsWith("skeleton:")).count());
                        rows.add(row);
                        tiltSum += fraction; coverageSum += coverage; cases++;
                        if (tilted == 0) allAligned++;
                        checks.add(() -> assertFalse(objects.isEmpty(), row::toString));
                        checks.add(() -> assertTrue(objects.stream().allMatch(object ->
                                object.piece().scale() >= .95f && object.piece().scale() <= 1.05f), row::toString));
                        if (seed == 7501765099988357541L && origin.equals(new BlockPos(-3, -60, 5))
                                && Arrays.equals(size, new int[]{2, 1, 2})) {
                            System.out.println("SCREENSHOT NATURALNESS " + row);
                            checks.add(() -> assertTrue(fraction >= .6, "Screenshot remains an upright grid: " + row));
                        }
                    }
            double meanTilt = tiltSum / cases, meanCoverage = coverageSum / cases;
            System.out.printf(Locale.ROOT, "SMALL FIELD %s cases=%d meanTilt=%.4f allAligned=%d meanCoverage=%.4f%n",
                    pool.getKey(), cases, meanTilt, allAligned, meanCoverage);
            checks.add(() -> assertTrue(meanTilt >= .6, pool.getKey() + ": small fields tilt=" + meanTilt));
            checks.add(() -> assertTrue(meanCoverage >= .5, pool.getKey() + ": small fields coverage=" + meanCoverage));
        }
        Path output = Path.of("build/verification/spatial-field/small-naturalness.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(rows));
        if (saved != null) assertArrayEquals(saved, Files.readAllBytes(save));
        if (samples != null) assertArrayEquals(samples, Files.readAllBytes(cache));
        assertAll(checks);
    }
}
