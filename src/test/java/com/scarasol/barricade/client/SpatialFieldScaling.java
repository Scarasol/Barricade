package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtIo;

/** A single full-reconstruction workload for cold reload and bulk fill, with per-region timings. */
final class SpatialFieldScaling {
    private SpatialFieldScaling() { }
    static void run(List<PileModel> defaults, Function<FieldBatch, int[]> gpu, GpuLayoutWorker worker) throws Exception {
        Map<String, List<PileModel>> pools = new LinkedHashMap<>(); pools.put("default", defaults);
        Path save = Path.of("run/saves/Test World/data/barricade_embedded_v1.dat");
        Path cache = Path.of("run/cache/barricade/material_samples.json");
        byte[] saved = Files.isRegularFile(save) ? Files.readAllBytes(save) : null;
        byte[] samples = Files.isRegularFile(cache) ? Files.readAllBytes(cache) : null;
        if (saved != null && samples != null)
            pools.put("recorded-full", StateMaskValidation.readCachedMaterials(
                    NbtIo.readCompressed(save.toFile()).getCompound("data"), cache).values().iterator().next());
        else System.out.println("REGION SIZE recorded-full fixture unavailable; still testing the default pool");
        long initialization = System.nanoTime();
        gpu.apply(PackingBatch.prepare(new PackingInput(1, 0, Set.of(new GridPos(0, 0, 0)), defaults,
                List.of(), List.of(), 4, false)));
        double initializedMs = (System.nanoTime() - initialization) / 1e6;
        int rounds = Integer.parseInt(System.getenv().getOrDefault("BARRICADE_SCALE_ROUNDS", "3"));
        String sizes = System.getenv().getOrDefault("BARRICADE_REGION_SIZES", "2,3,4,6,8");
        String skeletons = System.getenv().getOrDefault("BARRICADE_SKELETON_GRIDS",
                FieldRules.SKELETON_SIDE + ":" + FieldRules.SKELETON_OFFSET);
        int[] coordinates = Arrays.stream(System.getenv().getOrDefault("BARRICADE_REGION_ORIGIN", "-1,63,17")
                .split(",")).mapToInt(Integer::parseInt).toArray();
        if (coordinates.length != 3) throw new IllegalArgumentException("Benchmark origin needs three coordinates");
        BlockPos origin = new BlockPos(coordinates[0], coordinates[1], coordinates[2]);
        JsonArray rows = new JsonArray();
        for (var pool : pools.entrySet()) for (int round = 0; round < rounds; round++) {
            List<int[]> order = new ArrayList<>();
            for (String size : sizes.split(",")) for (String skeleton : skeletons.split(",")) {
                String[] grid = skeleton.split(":");
                order.add(new int[]{Integer.parseInt(size), Integer.parseInt(grid[0]), Integer.parseInt(grid[1])});
            }
            if ((round & 1) != 0) Collections.reverse(order);
            for (int[] grid : order) for (int[] size : new int[][]{{1, 1, 1}, {2, 3, 2}, {8, 4, 4}, {8, 8, 8}}) {
                int side = grid[0];
                Set<BlockPos> domain = domain(size[0], size[1], size[2], origin);
                var definition = new FieldDefinition(42, FieldDefinition.ALGORITHM, FieldDefinition.PARAMETERS, "scale-" + pool.getKey());
                SpatialField field = new SpatialField("minecraft:overworld", definition, pool.getValue(), gpu,
                        side, grid[1], grid[2]);
                JsonObject row = new JsonObject();
                row.addProperty("pool", pool.getKey()); row.addProperty("round", round + 1);
                row.addProperty("side", side); row.addProperty("cells", domain.size());
                row.addProperty("skeleton_side", grid[1]); row.addProperty("skeleton_offset", grid[2]);
                row.add("origin", new Gson().toJsonTree(coordinates));
                long device = worker.deviceNanos(), start = System.nanoTime();
                List<FieldObject> objects = query(worker, field, domain);
                double wall = (System.nanoTime() - start) / 1e6;
                row.addProperty("cold_ms", wall);
                row.addProperty("device_ms", (worker.deviceNanos() - device) / 1e6);
                row.addProperty("objects", objects.size());
                row.add("metrics", new Gson().toJsonTree(field.metrics()));
                JsonArray regions = new JsonArray();
                double[] perRegion = field.regionTimings().stream().mapToDouble(time ->
                        (time.preparationNanos() + time.batchNanos()) / 1e6).sorted().toArray();
                for (var time : field.regionTimings()) regions.add(new Gson().toJsonTree(time));
                row.add("regions", regions);
                row.addProperty("region_count", perRegion.length);
                row.addProperty("region_median_ms", percentile(perRegion, .5));
                row.addProperty("region_p95_ms", percentile(perRegion, .95));
                row.addProperty("region_max_ms", percentile(perRegion, 1));
                row.addProperty("coverage", coverage(42, domain, objects));
                if (side == FieldRules.REGION_SIDE && grid[1] == FieldRules.SKELETON_SIDE
                        && grid[2] == FieldRules.SKELETON_OFFSET && domain.size() == 512)
                    assertTrue(row.get("coverage").getAsDouble() >= .50, pool.getKey() + ": bulk coverage below 50%");
                if (side == FieldRules.REGION_SIDE) row.add("fine_boundary_coverage", boundaryCoverage(domain, objects, side));
                long jobs = worker.completed();
                assertEquals(objects, query(worker, field, domain));
                assertEquals(jobs, worker.completed(), "A repeated complete query must not resubmit GPU work");
                assertFalse(objects.isEmpty());
                if (side == FieldRules.REGION_SIDE && round == 0 && domain.size() == 128)
                    partitioned(worker, field, domain, objects);
                rows.add(row);
                write(rows, rounds, initializedMs);
                System.out.printf(Locale.ROOT,
                        "REGION SIZE %s round=%d side=%d skeleton=%d:%d cells=%d cold=%.2fms regions=%d regionP95=%.2fms max=%.2fms coverage=%.3f%n",
                        pool.getKey(), round + 1, side, grid[1], grid[2], domain.size(), wall, perRegion.length,
                        percentile(perRegion, .95), percentile(perRegion, 1), row.get("coverage").getAsDouble());
            }
        }
        if (saved != null) assertArrayEquals(saved, Files.readAllBytes(save));
        if (samples != null) assertArrayEquals(samples, Files.readAllBytes(cache));
    }
    private static void write(JsonArray rows, int rounds, double initializedMs) throws Exception {
        JsonObject report = new JsonObject();
        report.addProperty("scope", "Same full reconstruction for reload/fill; material samples present; empty layout caches; no rendering");
        report.addProperty("gpu_initialized_before_measurements", true);
        report.addProperty("region_timing_scope", "Per-region CPU preparation plus shared GPU batch latency; not exclusive or additive device times");
        report.addProperty("boundary_coverage_scope", "Independent uniform 5x5x5 point probe; face/edge/corner bins touch one/two/three internal fine-grid seams within 0.2 blocks; diagnostics excluded from cold time");
        report.addProperty("shader_initialization_and_warmup_ms", initializedMs);
        report.addProperty("rounds", rounds); report.addProperty("parameters", FieldDefinition.PARAMETERS);
        report.add("measurements", rows);
        Path output = Path.of("build/verification/spatial-field/region-sizing.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    private static double percentile(double[] values, double q) {
        return values.length == 0 ? 0 : values[Math.min(values.length - 1, (int) Math.ceil(values.length * q) - 1)];
    }
    static List<FieldObject> query(GpuLayoutWorker worker, SpatialField field, Set<BlockPos> domain) {
        return query(worker, field, domain, domain);
    }
    static List<FieldObject> query(GpuLayoutWorker worker, SpatialField field, Set<BlockPos> domain, Set<BlockPos> baseline) {
        try { return worker.prepare(() -> field.query(domain, baseline)).get(120, TimeUnit.SECONDS); }
        catch (Exception exception) { throw new AssertionError("Production regional packing failed", exception); }
    }
    private static void partitioned(GpuLayoutWorker worker, SpatialField field, Set<BlockPos> domain, List<FieldObject> expected) {
        TreeMap<FieldObject.Key, FieldObject> union = new TreeMap<>();
        Set<BlockPos> remaining = new HashSet<>(domain);
        while (!remaining.isEmpty()) {
            Set<BlockPos> batch = FieldViewCache.nextBatch(remaining, BlockPos.ZERO, 17);
            query(worker, field, batch, domain).forEach(object -> union.put(object.key(), object));
            remaining.removeAll(batch);
        }
        assertEquals(expected, List.copyOf(union.values()));
    }
    private static double coverage(long seed, Set<BlockPos> domain, List<FieldObject> objects) {
        BlockPos origin = domain.iterator().next();
        Set<GridPos> relative = new HashSet<>();
        domain.forEach(cell -> relative.add(new GridPos(cell.getX() - origin.getX(), cell.getY() - origin.getY(),
                cell.getZ() - origin.getZ())));
        var boxes = objects.stream().flatMap(object -> object.piece().colliders(object.owner().getX() - origin.getX(),
                object.owner().getY() - origin.getY(), object.owner().getZ() - origin.getZ()).stream()).toList();
        return LegacyPileGenerator.coverage(seed, relative, boxes);
    }
    private static JsonArray boundaryCoverage(Set<BlockPos> domain, List<FieldObject> objects, int side) {
        BlockPos origin = domain.stream().min(Comparator.naturalOrder()).orElseThrow();
        Map<BlockPos, List<OrientedBox>> occupied = new HashMap<>();
        for (FieldObject object : objects)
            for (OrientedBox box : object.piece().colliders(object.owner().getX() - origin.getX(),
                    object.owner().getY() - origin.getY(), object.owner().getZ() - origin.getZ()))
                for (int x = (int) Math.floor(box.minX()); x <= Math.floor(box.maxX()); x++)
                    for (int y = (int) Math.floor(box.minY()); y <= Math.floor(box.maxY()); y++)
                        for (int z = (int) Math.floor(box.minZ()); z <= Math.floor(box.maxZ()); z++)
                            occupied.computeIfAbsent(origin.offset(x, y, z), ignored -> new ArrayList<>()).add(box);
        int[] samples = new int[4], covered = new int[4];
        for (BlockPos cell : domain) {
            boolean[] seams = new boolean[6];
            int[] coordinate = {cell.getX(), cell.getY(), cell.getZ()};
            int[][] directions = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
            for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
                int[] direction = directions[axis];
                seams[axis * 2 + (sign > 0 ? 1 : 0)] = domain.contains(cell.offset(
                        direction[0] * sign, direction[1] * sign, direction[2] * sign))
                        && Math.floorDiv(coordinate[axis], side) != Math.floorDiv(coordinate[axis] + sign, side);
            }
            for (int x = 0; x < 5; x++) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) {
                int bin = (x == 0 && seams[0] || x == 4 && seams[1] ? 1 : 0)
                        + (y == 0 && seams[2] || y == 4 && seams[3] ? 1 : 0)
                        + (z == 0 && seams[4] || z == 4 && seams[5] ? 1 : 0);
                samples[bin]++;
                float px = cell.getX() - origin.getX() + (x + .5f) / 5;
                float py = cell.getY() - origin.getY() + (y + .5f) / 5;
                float pz = cell.getZ() - origin.getZ() + (z + .5f) / 5;
                for (OrientedBox box : occupied.getOrDefault(cell, List.of()))
                    if (box.contains(px, py, pz)) { covered[bin]++; break; }
            }
        }
        JsonArray result = new JsonArray();
        String[] names = {"away-from-internal-seams", "face", "edge", "corner"};
        for (int bin = 0; bin < names.length; bin++) {
            JsonObject row = new JsonObject();
            row.addProperty("zone", names[bin]); row.addProperty("samples", samples[bin]);
            if (samples[bin] != 0) row.addProperty("coverage", (double) covered[bin] / samples[bin]);
            result.add(row);
        }
        return result;
    }
    private static Set<BlockPos> domain(int x, int y, int z, BlockPos origin) {
        Set<BlockPos> result = new HashSet<>();
        for (int a = 0; a < x; a++) for (int b = 0; b < y; b++) for (int c = 0; c < z; c++) result.add(origin.offset(a, b, c));
        return Set.copyOf(result);
    }
}
