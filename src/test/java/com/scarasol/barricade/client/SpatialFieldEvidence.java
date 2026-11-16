package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import org.joml.Vector3f;
import static org.lwjgl.glfw.GLFW.*;

/** Reproducible, read-only comparison with the backed-up production kernel, never a runtime backend. */
final class SpatialFieldEvidence {
    private static final Path SAVE = Path.of("run/saves/Test World/data/barricade_embedded_v1.dat");
    private static final Path CACHE = Path.of("run/cache/barricade/material_samples.json");
    private SpatialFieldEvidence() { }
    private record Fixture(UUID id, long seed, ArchivedPileRecipe head, SpatialMask mask) { }
    private record Reading(double wallMs, double cpuPreparationMs, double deviceMs, long jobs, long dispatches,
                           long vertices, long edges, long prepared, long cacheHits, long peakGpuBufferBytes,
                           int objects, long renderEntriesCreated, double renderPublishMs) { }
    private record Cold(Reading reading, List<SpatialField> fields, List<List<FieldObject>> objects) { }

    static void run(List<PileModel> defaults, Function<FieldBatch, int[]> gpu, GpuLayoutWorker worker) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(SAVE) && Files.isRegularFile(CACHE));
        byte[] beforeSave = Files.readAllBytes(SAVE), beforeCache = Files.readAllBytes(CACHE);
        long load = System.nanoTime();
        CompoundTag saved = NbtIo.readCompressed(new ByteArrayInputStream(beforeSave)).getCompound("data");
        List<ArchivedPileRecipe> history = ArchivedRecipeArchive.read(saved).stream()
                .sorted(Comparator.comparingLong(ArchivedPileRecipe::id)).toList();
        Map<Long, ArchivedPileRecipe> nodes = new HashMap<>();
        history.forEach(node -> nodes.put(node.id(), node));
        List<Fixture> fixtures = new ArrayList<>();
        for (Tag value : saved.getList("Regions", Tag.TAG_COMPOUND)) {
            CompoundTag region = (CompoundTag) value;
            ArchivedPileRecipe head = nodes.get(region.getLong("Head"));
            SpatialMask mask = new SpatialMask();
            Set<Long> ancestors = new HashSet<>();
            ArrayDeque<Long> queue = new ArrayDeque<>(List.of(head.id()));
            while (!queue.isEmpty()) {
                long id = queue.removeFirst();
                if (!ancestors.add(id)) continue;
                ArchivedPileRecipe node = nodes.get(id); queue.addAll(node.prerequisites());
                for (var damage : node.damage()) if (damage.fullCell() && !head.domain().contains(damage.pos())) mask.set(damage.pos());
            }
            fixtures.add(new Fixture(region.getUUID("Id"), region.getLong("Seed"), head, mask));
        }
        fixtures.sort(Comparator.comparing(Fixture::id));
        double archiveReadMs = (System.nanoTime() - load) / 1e6;
        long cacheStart = System.nanoTime();
        Map<String, List<PileModel>> materials = StateMaskValidation.readCachedMaterials(saved, CACHE);
        double cacheReadMs = (System.nanoTime() - cacheStart) / 1e6;
        String oldSource;
        try (var stream = SpatialFieldEvidence.class.getResourceAsStream("/archived/layout.compute")) {
            oldSource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        long oldWindow = glfwCreateWindow(1, 1, "Archived production benchmark", 0, 0);
        assertNotEquals(0, oldWindow);
        ArchivedGpuLayoutWorker oldWorker = new ArchivedGpuLayoutWorker(oldWindow, oldSource);
        try {
            Function<LayoutInput, LayoutResult> oldGpu = input -> {
                ArchivedBatchLayout plan = ArchivedBatchLayout.prepare(input);
                try { return plan.decode(oldWorker.submit(plan).get(60, TimeUnit.SECONDS)); }
                catch (Exception exception) { throw new AssertionError("Backed-up production GPU failed", exception); }
            };
            JsonObject report = new JsonObject();
            report.addProperty("scope", "Offscreen production field + archived production replay; not game startup or model capture/upload");
            report.addProperty("algorithm", FieldDefinition.ALGORITHM); report.addProperty("parameters", FieldDefinition.PARAMETERS);
            report.addProperty("archive_read_ms", archiveReadMs); report.addProperty("material_json_read_ms", cacheReadMs);
            report.addProperty("history_records", history.size());
            report.addProperty("generation_records", history.stream().filter(node -> !node.active().isEmpty()).count());
            report.addProperty("samples", materials.values().stream().mapToInt(List::size).sum());
            JsonArray rounds = new JsonArray();
            for (int round = 0; round < 3; round++) {
                Reading old;
                Cold cold;
                if ((round & 1) == 0) {
                    old = replay(history, fixtures, materials, oldGpu, oldWorker::deviceNanos);
                    cold = cold(fixtures, materials, gpu, worker);
                } else {
                    cold = cold(fixtures, materials, gpu, worker);
                    old = replay(history, fixtures, materials, oldGpu, oldWorker::deviceNanos);
                }
                JsonObject row = new JsonObject();
                Gson gson = new Gson();
                row.add("archived_replay", gson.toJsonTree(old)); row.add("cold_field", gson.toJsonTree(cold.reading));
                row.add("warm_growth", gson.toJsonTree(growth(fixtures, cold, worker)));
                row.add("overlay_only", gson.toJsonTree(overlay(fixtures, cold, worker)));
                rounds.add(row);
                System.out.printf(Locale.ROOT, "FIELD EVIDENCE round=%d old=%.3fms newCold=%.3fms jobs=%d/%d candidates=%d objects=%d/%d%n",
                        round + 1, old.wallMs, cold.reading.wallMs, old.jobs, cold.reading.jobs,
                        cold.reading.prepared, old.objects, cold.reading.objects);
                BarricadeRenderer.clear();
            }
            report.add("rounds", rounds);
            JsonArray density = new JsonArray();
            density("default", defaults, gpu, oldGpu, density);
            density("recorded-full", materials.values().iterator().next(), gpu, oldGpu, density);
            report.add("density", density);
            report.addProperty("mean_coverage_ratio", density.asList().stream().mapToDouble(value -> {
                JsonObject row = value.getAsJsonObject();
                return row.get("coverage").getAsDouble() / row.get("old_coverage").getAsDouble();
            }).average().orElse(0));
            Path output = Path.of("build/verification/spatial-field/evidence.json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } finally {
            System.out.println("FIELD EVIDENCE stopping archived GPU");
            oldWorker.stop().get(30, TimeUnit.SECONDS);
            System.out.println("FIELD EVIDENCE destroying archived context");
            glfwDestroyWindow(oldWindow);
            System.out.println("FIELD EVIDENCE archived context released");
            assertArrayEquals(beforeSave, Files.readAllBytes(SAVE)); assertArrayEquals(beforeCache, Files.readAllBytes(CACHE));
        }
    }
    private static Reading replay(List<ArchivedPileRecipe> history, List<Fixture> fixtures,
            Map<String, List<PileModel>> materials, Function<LayoutInput, LayoutResult> gpu, LongSupplier device) {
        ArchivedTerrainShapes shapes = new ArchivedTerrainShapes(MemoryFixture.create(false));
        Map<Long, List<ArchivedPileReplay.ObjectPiece>> results = new HashMap<>();
        long before = device.getAsLong(), start = System.nanoTime();
        long[] jobs = {0};
        for (ArchivedPileRecipe node : history) {
            var prepared = StateMaskValidation.prepareRecorded(shapes, node, materials.get(node.palette()));
            results.put(node.id(), ArchivedPileReplay.replay(prepared, node.parents().stream().map(results::get).toList(),
                    node.neighbors().stream().map(results::get).toList(), input -> { jobs[0]++; return gpu.apply(input); }));
        }
        return new Reading((System.nanoTime() - start) / 1e6, -1, (device.getAsLong() - before) / 1e6, jobs[0], -1,
                -1, -1, -1, 0, -1, fixtures.stream().mapToInt(fixture -> results.get(fixture.head.id()).size()).sum(), -1, -1);
    }
    private static Cold cold(List<Fixture> fixtures, Map<String, List<PileModel>> materials,
                             Function<FieldBatch, int[]> gpu, GpuLayoutWorker worker) {
        long before = worker.deviceNanos(), dispatches = worker.dispatches(), start = System.nanoTime();
        List<SpatialField> fields = new ArrayList<>();
        List<List<FieldObject>> objects = new ArrayList<>();
        long prepared = 0, jobs = 0, vertices = 0, edges = 0, hits = 0, peak = 0, cpu = 0;
        for (Fixture fixture : fixtures) {
            FieldDefinition definition = new FieldDefinition(fixture.seed, FieldDefinition.ALGORITHM,
                    FieldDefinition.PARAMETERS, fixture.head.palette());
            SpatialField field = new SpatialField("minecraft:overworld", definition, materials.get(fixture.head.palette()), gpu);
            Set<BlockPos> baseline = new HashSet<>(fixture.head.domain()); baseline.addAll(fixture.mask.cells());
            var base = SpatialFieldScaling.query(worker, field, fixture.head.domain(), baseline);
            var visible = base.stream().filter(object -> !FieldVisibility.damaged(object, fixture.mask)).toList();
            fields.add(field); objects.add(visible);
            var metrics = field.metrics();
            prepared += metrics.prepared(); jobs += metrics.jobs(); vertices += metrics.vertices();
            edges += metrics.edges(); hits += metrics.cacheHits(); peak = Math.max(peak, metrics.peakBufferBytes());
            cpu += metrics.prepareNanos();
        }
        double wall = (System.nanoTime() - start) / 1e6;
        long beforeEntries = BarricadeRenderer.counters().created(), publish = System.nanoTime();
        for (int i = 0; i < fixtures.size(); i++) BarricadeRenderer.replace(fixtures.get(i).id, objects.get(i));
        double publishMs = (System.nanoTime() - publish) / 1e6;
        return new Cold(new Reading(wall, cpu / 1e6, (worker.deviceNanos() - before) / 1e6, jobs,
                worker.dispatches() - dispatches, vertices, edges, prepared, hits, peak,
                objects.stream().mapToInt(List::size).sum(), BarricadeRenderer.counters().created() - beforeEntries, publishMs), fields, objects);
    }
    private static Reading growth(List<Fixture> fixtures, Cold cold, GpuLayoutWorker worker) {
        long before = worker.deviceNanos(), dispatches = worker.dispatches(), jobsBefore = worker.completed();
        long start = System.nanoTime(), created = BarricadeRenderer.counters().created(), publishNanos = 0, newObjects = 0, cpu = 0;
        for (int i = 0; i < fixtures.size(); i++) {
            Fixture fixture = fixtures.get(i);
            BlockPos cell = fixture.head.domain().stream().min(Comparator.naturalOrder()).orElseThrow().west();
            assertFalse(fixture.head.domain().contains(cell));
            Set<BlockPos> grown = new HashSet<>(fixture.head.domain()); grown.add(cell);
            Set<BlockPos> baseline = new HashSet<>(grown); baseline.addAll(fixture.mask.cells());
            long prepare = cold.fields.get(i).metrics().prepareNanos();
            var merged = SpatialFieldScaling.query(worker, cold.fields.get(i), grown, baseline).stream()
                    .filter(object -> !FieldVisibility.damaged(object, fixture.mask)).toList();
            cpu += cold.fields.get(i).metrics().prepareNanos() - prepare;
            Set<FieldObject.Key> oldKeys = new HashSet<>();
            cold.objects.get(i).forEach(object -> oldKeys.add(object.key()));
            newObjects += merged.stream().filter(object -> !oldKeys.contains(object.key())).count();
            long publish = System.nanoTime(); BarricadeRenderer.replace(fixture.id, merged);
            publishNanos += System.nanoTime() - publish;
        }
        assertEquals(newObjects, BarricadeRenderer.counters().created() - created, "Only changed region identities may create new Entries");
        return new Reading((System.nanoTime() - start) / 1e6, cpu / 1e6, (worker.deviceNanos() - before) / 1e6,
                worker.completed() - jobsBefore, worker.dispatches() - dispatches, -1, -1, -1, -1, -1,
                (int) newObjects, BarricadeRenderer.counters().created() - created, publishNanos / 1e6);
    }
    private static Reading overlay(List<Fixture> fixtures, Cold cold, GpuLayoutWorker worker) {
        long start = System.nanoTime(), jobs = worker.completed(), device = worker.deviceNanos(), dispatches = worker.dispatches();
        int survivors = 0;
        for (int i = 0; i < fixtures.size(); i++) {
            Fixture fixture = fixtures.get(i);
            SpatialMask mask = new SpatialMask(fixture.mask);
            mask.set(fixture.head.domain().stream().min(Comparator.naturalOrder()).orElseThrow());
            survivors += cold.objects.get(i).stream().filter(object -> !FieldVisibility.damaged(object, mask)).count();
        }
        assertEquals(jobs, worker.completed()); assertEquals(device, worker.deviceNanos());
        return new Reading((System.nanoTime() - start) / 1e6, 0, 0, 0, worker.dispatches() - dispatches,
                0, 0, 0, 0, 0, survivors, 0, -1);
    }
    private static void density(String pool, List<PileModel> models, Function<FieldBatch, int[]> gpu,
                                Function<LayoutInput, LayoutResult> oldGpu, JsonArray output) {
        double totalCoverage = 0;
        int count = 0;
        Map<String, Set<BlockPos>> cases = new LinkedHashMap<>();
        cases.put("single", domain(1, 1, 1)); cases.put("wall-1x6", domain(6, 1, 1));
        Set<BlockPos> l = new HashSet<>(domain(4, 1, 1));
        for (int z = 1; z < 4; z++) l.add(new BlockPos(0, 0, z));
        cases.put("L", Set.copyOf(l)); cases.put("stack-2x2x3", domain(2, 3, 2));
        for (long seed : new long[]{7, 42, 100, 321}) {
            FieldDefinition definition = new FieldDefinition(seed, FieldDefinition.ALGORITHM, FieldDefinition.PARAMETERS, "density-" + pool);
            for (var entry : cases.entrySet()) {
                SpatialField field = new SpatialField("minecraft:overworld", definition, models, gpu);
                var pieces = field.query(entry.getValue());
                Set<GridPos> relative = new HashSet<>();
                entry.getValue().forEach(pos -> relative.add(new GridPos(pos.getX(), pos.getY(), pos.getZ())));
                LayoutResult old;
                int pass = 0; List<RegionPiece> previous = List.of(); Set<GridPos> active = relative;
                do {
                    old = oldGpu.apply(new LayoutInput(seed, pass++, relative, models, previous, List.of(), List.of(), List.of(),
                            active, Set.of(), true));
                    previous = old.pieces(); active = old.work();
                } while (old.continueFilling());
                List<OrientedBox> boxes = pieces.stream().flatMap(object -> object.piece().colliders(object.owner().getX(),
                        object.owner().getY(), object.owner().getZ()).stream()).toList();
                JsonObject row = new JsonObject();
                row.addProperty("pool", pool); row.addProperty("seed", seed); row.addProperty("case", entry.getKey());
                row.addProperty("objects", pieces.size()); row.addProperty("old_objects", old.pieces().size());
                double coverage = LegacyPileGenerator.coverage(seed, relative, boxes);
                totalCoverage += coverage;
                count++;
                row.addProperty("coverage", coverage);
                row.addProperty("old_coverage", LegacyPileGenerator.coverage(seed, relative,
                        old.pieces().stream().flatMap(piece -> piece.colliders().stream()).toList()));
                row.addProperty("large_objects", pieces.stream().filter(object -> object.piece().model().elongated()).count());
                row.addProperty("panels", pieces.stream().filter(object -> object.piece().model().panel()).count());
                row.addProperty("empty_cells", entry.getValue().stream().filter(cell ->
                        pieces.stream().noneMatch(object -> FieldVisibility.activated(object, Set.of(cell)))).count());
                row.addProperty("substantial_intersections", intersections(pieces));
                row.addProperty("outside_body_samples", outside(pieces, entry.getValue()));
                row.addProperty("panel_exposure", exposure(pieces));
                assertFalse(pieces.isEmpty(), pool + "/" + seed + "/" + entry.getKey());
                assertTrue(coverage >= .10, pool + "/" + seed + "/" + entry.getKey() + " coverage=" + coverage);
                assertEquals(0, intersections(pieces));
                assertTrue(pieces.stream().allMatch(object -> object.piece().scale() >= .95f && object.piece().scale() <= 1.05f));
                output.add(row);
                System.out.printf(Locale.ROOT, "FIELD DENSITY %s seed=%d %s objects=%d old=%d coverage=%.3f empty=%s%n",
                        pool, seed, entry.getKey(), pieces.size(), old.pieces().size(), coverage, row.get("empty_cells"));
            }
        }
        assertTrue(totalCoverage / count >= .50, pool + " mean coverage=" + totalCoverage / count);
    }
    private static int intersections(List<FieldObject> objects) {
        int count = 0;
        for (int i = 0; i < objects.size(); i++) for (int j = 0; j < i; j++) {
            FieldObject a = objects.get(i), b = objects.get(j);
            var boxes = b.piece().colliders(b.owner().getX() - a.owner().getX(), b.owner().getY() - a.owner().getY(),
                    b.owner().getZ() - a.owner().getZ());
            if (a.piece().colliders(0, 0, 0).stream().anyMatch(box -> boxes.stream().anyMatch(other -> box.intersects(other, .012f)))) count++;
        }
        return count;
    }
    private static double outside(List<FieldObject> objects, Set<BlockPos> domain) {
        int out = 0, all = 0;
        for (FieldObject object : objects) for (OrientedBox box : object.piece().colliders(0, 0, 0))
            for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
                Vector3f p = box.point(x * .8f, y * .8f, z * .8f);
                if (!domain.contains(object.owner().offset((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z)))) out++;
                all++;
            }
        return all == 0 ? 0 : (double) out / all;
    }
    private static double exposure(List<FieldObject> objects) {
        double visible = 0; int panels = 0;
        for (FieldObject panel : objects) {
            if (!panel.piece().model().panel()) continue;
            panels++; var model = panel.piece().model(); Vector3f span = model.span();
            int thin = span.x < span.y && span.x < span.z ? 0 : span.y < span.z ? 1 : 2;
            int u = (thin + 1) % 3, v = (thin + 2) % 3, best = 0;
            for (int side : new int[]{-1, 1}) {
                int clear = 0;
                for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                    Vector3f p = model.collisionCenter(), min = model.collisionMin(), max = model.collisionMax();
                    p.setComponent(thin, side < 0 ? min.get(thin) - .02f : max.get(thin) + .02f);
                    p.setComponent(u, p.get(u) + a * (max.get(u) - min.get(u)) * .3f);
                    p.setComponent(v, p.get(v) + b * (max.get(v) - min.get(v)) * .3f);
                    p = panel.piece().transformPoint(p.x, p.y, p.z);
                    boolean blocked = false;
                    for (FieldObject other : objects) {
                        if (other == panel) continue;
                        int dx = other.owner().getX() - panel.owner().getX(), dy = other.owner().getY() - panel.owner().getY(),
                                dz = other.owner().getZ() - panel.owner().getZ();
                        for (OrientedBox box : other.piece().colliders(dx, dy, dz))
                            if (box.contains(p.x, p.y, p.z)) { blocked = true; break; }
                        if (blocked) break;
                    }
                    if (!blocked) clear++;
                }
                best = Math.max(best, clear);
            }
            visible += best / 9.0;
        }
        return panels == 0 ? -1 : visible / panels;
    }
    private static Set<BlockPos> domain(int x, int y, int z) {
        Set<BlockPos> result = new HashSet<>();
        for (int a = 0; a < x; a++) for (int b = 0; b < y; b++) for (int c = 0; c < z; c++) result.add(new BlockPos(a, b, c));
        return Set.copyOf(result);
    }
}
