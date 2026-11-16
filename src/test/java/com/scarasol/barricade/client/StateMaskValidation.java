package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;

/** Test-only experiments. No layout snapshots, migrations, or game-world writes. */
final class StateMaskValidation {
    private StateMaskValidation() { }

    static void frozenBaselineRoundTrips(List<PileModel> models, Function<LayoutInput, LayoutResult> gpu)
            throws Exception {
        for (long seed : new long[]{7, 42, 100, 321}) {
            Set<BlockPos> domain = domain(3, 2, 2, new BlockPos(100000, 80, -250000));
            ArchivedPileRecipe base = baseline(1, seed, domain, "test", ArchivedTerrainSnapshot.EMPTY);
            var memory = MemoryFixture.create(false);
            ArchivedTerrainShapes shapes = new ArchivedTerrainShapes(memory);
            Function<ArchivedPileRecipe, ArchivedPileReplay.Prepared> prepare =
                    node -> shapes.prepare(node, node.active().isEmpty() ? List.of() : models);
            List<ArchivedPileReplay.ObjectPiece> original = ArchivedPileReplay.replay(
                    prepare.apply(base), List.of(), List.of(), gpu);
            assertFalse(original.isEmpty());
            List<ArchivedPileReplay.ObjectPiece> sequential = original;
            StateMaskPrototype snapshot = new StateMaskPrototype(base, Set.of());
            Set<BlockPos> remaining = new HashSet<>(domain);
            List<BlockPos> deleted = domain.stream().sorted().limit(4).toList();
            long id = 1;
            for (BlockPos pos : deleted) {
                remaining.remove(pos);
                ArchivedPileRecipe edit = new ArchivedPileRecipe(++id, seed, List.of(id - 1), remaining,
                        Set.of(), Set.of(), "test", ArchivedTerrainSnapshot.EMPTY,
                        List.of(new ArchivedPileRecipe.Damage(pos, true, "", ArchivedTerrainSnapshot.EMPTY)));
                sequential = ArchivedPileReplay.replay(prepare.apply(edit), List.of(sequential), List.of(),
                        ignored -> { throw new AssertionError("Destruction cannot invoke the GPU"); });
                snapshot = snapshot.remove(pos);
            }
            assertTrue(sequential.size() < original.size(), "The mask must actually destroy objects");
            assertFalse(sequential.isEmpty(), "Do not verify consistency using an empty pile");
            byte[] bytes = compressed(snapshot.save());
            StateMaskPrototype loaded = StateMaskPrototype.load(
                    NbtIo.readCompressed(new ByteArrayInputStream(bytes)));
            List<ArchivedPileReplay.ObjectPiece> restored = loaded.restore(prepare, gpu);
            assertSameWorldObjects(sequential, restored);
            assertEquals(4, loaded.removed().size());
            StateMaskPrototype repeated = loaded;
            for (int i = 0; i < 2000; i++) repeated = repeated.remove(deleted.get(i % deleted.size()));
            assertArrayEquals(bytes, compressed(repeated.save()),
                    "Repeated edits must not grow an operation log");
            StateMaskPrototype reordered = new StateMaskPrototype(base, Set.of());
            for (int i = deleted.size() - 1; i >= 0; i--) reordered = reordered.remove(deleted.get(i));
            assertArrayEquals(bytes, compressed(reordered.save()), "Mask storage must be order-independent");
            assertEquals(0, memory.chunkRequests(), "Restoration must not load real chunks");
            System.out.printf(Locale.ROOT,
                    "State-mask frozen seed=%d cells=%d original=%d survivors=%d mask_cells=%d bytes=%d exact_world_poses=true%n",
                    seed, domain.size(), original.size(), restored.size(), loaded.removed().size(), bytes.length);
        }
    }

    static void changingBaselineIsNotACompatibleShortcut(List<PileModel> models,
            Function<LayoutInput, LayoutResult> gpu) {
        ArchivedTerrainShapes shapes = new ArchivedTerrainShapes(MemoryFixture.create(false));
        Function<ArchivedPileRecipe, ArchivedPileReplay.Prepared> prepare =
                node -> shapes.prepare(node, node.active().isEmpty() ? List.of() : models);
        int growthMismatches = 0, mergeMismatches = 0;
        for (long seed : new long[]{7, 42, 100, 321}) {
            Set<BlockPos> small = domain(2, 2, 2, BlockPos.ZERO);
            Set<BlockPos> expanded = domain(3, 2, 2, BlockPos.ZERO);
            ArchivedPileRecipe first = baseline(1, seed, small, "test", ArchivedTerrainSnapshot.EMPTY);
            var before = ArchivedPileReplay.replay(prepare.apply(first), List.of(), List.of(), gpu);
            Set<BlockPos> active = new HashSet<>(expanded);
            active.removeAll(small);
            ArchivedPileRecipe addition = new ArchivedPileRecipe(2, seed, List.of(1L), expanded, active, Set.of(),
                    "test", ArchivedTerrainSnapshot.EMPTY, List.of());
            var increment = ArchivedPileReplay.replay(prepare.apply(addition), List.of(before), List.of(), gpu);
            var direct = new StateMaskPrototype(baseline(2, seed, expanded, "test", ArchivedTerrainSnapshot.EMPTY),
                    Set.of()).restore(prepare, gpu);
            assertTrue(increment.containsAll(before), "This fixture must preserve the previous objects");
            long retained = before.stream().filter(old -> hasSamePose(direct, old)).count();
            if (retained != before.size()) growthMismatches++;
            System.out.printf(Locale.ROOT,
                    "State-mask growth seed=%d previous=%d incremental=%d direct=%d old_poses_kept_by_direct=%d%n",
                    seed, before.size(), increment.size(), direct.size(), retained);

            Set<BlockPos> left = domain(2, 1, 1, BlockPos.ZERO);
            Set<BlockPos> right = domain(2, 1, 1, new BlockPos(3, 0, 0));
            Set<BlockPos> merged = domain(5, 1, 1, BlockPos.ZERO);
            var leftPieces = ArchivedPileReplay.replay(
                    prepare.apply(baseline(1, seed + 1, left, "test", ArchivedTerrainSnapshot.EMPTY)),
                    List.of(), List.of(), gpu);
            var rightPieces = ArchivedPileReplay.replay(
                    prepare.apply(baseline(2, seed + 2, right, "test", ArchivedTerrainSnapshot.EMPTY)),
                    List.of(), List.of(), gpu);
            ArchivedPileRecipe join = new ArchivedPileRecipe(3, seed, List.of(1L, 2L), merged,
                    Set.of(new BlockPos(2, 0, 0)), Set.of(), "test", ArchivedTerrainSnapshot.EMPTY, List.of());
            var incrementalMerge = ArchivedPileReplay.replay(prepare.apply(join),
                    List.of(leftPieces, rightPieces), List.of(), gpu);
            var directMerge = new StateMaskPrototype(
                    baseline(3, seed, merged, "test", ArchivedTerrainSnapshot.EMPTY), Set.of()).restore(prepare, gpu);
            List<ArchivedPileReplay.ObjectPiece> parents = new ArrayList<>(leftPieces);
            parents.addAll(rightPieces);
            assertTrue(incrementalMerge.containsAll(parents));
            long kept = parents.stream().filter(old -> hasSamePose(directMerge, old)).count();
            if (kept != parents.size()) mergeMismatches++;
            System.out.printf(Locale.ROOT,
                    "State-mask merge seed=%d parent_objects=%d incremental=%d direct=%d old_poses_kept_by_direct=%d%n",
                    seed, parents.size(), incrementalMerge.size(), directMerge.size(), kept);
        }
        assertEquals(4, growthMismatches,
                "Current generation cannot be claimed history-independent after growth");
        assertEquals(4, mergeMismatches,
                "A merged region seed alone does not reconstruct the original parent objects");
    }

    static void obstacleChangesRequireFrozenInputs(List<PileModel> models,
            Function<LayoutInput, LayoutResult> gpu) throws Exception {
        Set<BlockPos> domain = domain(3, 2, 2, BlockPos.ZERO);
        BlockPos pillar = new BlockPos(1, 0, 0);
        ArchivedTerrainSnapshot blocked = new ArchivedTerrainSnapshot(
                Map.of(pillar, new WorldBlock("minecraft:stone", new CompoundTag(),
                        new EmbeddedBlock(pillar, 1, 1))), Set.of());
        ArchivedTerrainShapes shapes = new ArchivedTerrainShapes(MemoryFixture.create(false));
        Function<ArchivedPileRecipe, ArchivedPileReplay.Prepared> prepare =
                node -> shapes.prepare(node, node.active().isEmpty() ? List.of() : models);
        StateMaskPrototype empty = new StateMaskPrototype(
                baseline(1, 42, domain, "test", ArchivedTerrainSnapshot.EMPTY), Set.of());
        StateMaskPrototype occupied = new StateMaskPrototype(
                baseline(1, 42, domain, "test", blocked), Set.of());
        var free = empty.restore(prepare, gpu);
        var constrained = occupied.restore(prepare, gpu);
        assertNotEquals(free, constrained, "Current obstacle geometry participates in generation");
        StateMaskPrototype reloaded = StateMaskPrototype.load(
                NbtIo.readCompressed(new ByteArrayInputStream(compressed(occupied.save()))));
        assertEquals(constrained, reloaded.restore(prepare, gpu), "Captured inputs must round-trip");
        System.out.printf(Locale.ROOT, "State-mask obstacles: free=%d occupied=%d frozen_restore_exact=true%n",
                free.size(), constrained.size());
    }

    static void benchmarkRecordedHistory(Function<LayoutInput, LayoutResult> gpu,
            java.util.function.LongSupplier deviceNanos) throws Exception {
        Path saveFile = Path.of("run/saves/Test World/data/barricade_embedded_v1.dat");
        Path cacheFile = Path.of("run/cache/barricade/material_samples.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(saveFile)
                && Files.isRegularFile(cacheFile), "Optional local archive and cache fixtures");
        byte[] originalSave = Files.readAllBytes(saveFile), originalCache = Files.readAllBytes(cacheFile);
        long loadStart = System.nanoTime();
        CompoundTag tag = NbtIo.readCompressed(new ByteArrayInputStream(originalSave)).getCompound("data");
        List<ArchivedPileRecipe> records = ArchivedRecipeArchive.read(tag).stream()
                .sorted(Comparator.comparingLong(ArchivedPileRecipe::id)).toList();
        Map<Long, ArchivedPileRecipe> recipes = new HashMap<>();
        records.forEach(node -> recipes.put(node.id(), node));
        List<ArchivedPileRecipe> heads = new ArrayList<>();
        for (Tag value : tag.getList("Regions", Tag.TAG_COMPOUND))
            heads.add(recipes.get(((CompoundTag) value).getLong("Head")));
        heads.sort(Comparator.comparingLong(ArchivedPileRecipe::id));
        double archiveReadMs = (System.nanoTime() - loadStart) / 1e6;
        Map<String, List<PileModel>> materials = readCachedMaterials(tag, cacheFile);
        List<StateMaskPrototype> direct = new ArrayList<>();
        for (ArchivedPileRecipe head : heads) {
            assertTrue(head.embedded().isEmpty(), "Do not silently omit real embedded fixtures");
            Set<Long> ancestors = ancestors(head.id(), recipes);
            ArchivedPileRecipe environment = records.stream().filter(node ->
                    ancestors.contains(node.id()) && !node.active().isEmpty())
                    .max(Comparator.comparingLong(ArchivedPileRecipe::id)).orElseThrow();
            Set<BlockPos> deleted = new HashSet<>();
            for (long id : ancestors) for (ArchivedPileRecipe.Damage damage : recipes.get(id).damage())
                if (damage.fullCell() && !head.domain().contains(damage.pos())) deleted.add(damage.pos());
            direct.add(new StateMaskPrototype(baseline(head.id(), head.seed(), head.domain(),
                    head.palette(), environment.terrain()), deleted));
        }
        Set<Long> required = new HashSet<>();
        heads.forEach(head -> required.addAll(ancestors(head.id(), recipes)));
        List<ArchivedPileRecipe> history = records.stream().filter(node -> required.contains(node.id())).toList();
        ArchivedTerrainShapes terrain = new ArchivedTerrainShapes(MemoryFixture.create(false));
        Function<ArchivedPileRecipe, ArchivedPileReplay.Prepared> prepare = node ->
                prepareRecorded(terrain, node, node.active().isEmpty() ? List.of() : materials.get(node.palette()));
        System.out.printf(Locale.ROOT,
                "State-mask benchmark fixture: regions=%d cells=%s records=%d generating=%d read=%.3fms; latest captured environments, not a live world%n",
                heads.size(), heads.stream().map(node -> node.domain().size()).toList(), history.size(),
                history.stream().filter(node -> !node.active().isEmpty()).count(), archiveReadMs);
        CompoundTag stateMetadata = new CompoundTag();
        stateMetadata.put("Palettes", tag.get("Palettes").copy());
        ListTag states = new ListTag();
        direct.forEach(state -> states.add(state.save()));
        stateMetadata.put("States", states);
        int prototypeBytes = compressed(stateMetadata).length;
        System.out.printf(Locale.ROOT,
                "State-mask prototype metadata: compressed=%d bytes mask_cells=%s; not a complete production save format%n",
                prototypeBytes, direct.stream().map(state -> state.removed().size()).toList());
        List<Measurement> historyTimes = new ArrayList<>(), stateTimes = new ArrayList<>();
        JsonArray trials = new JsonArray();
        for (int trial = 0; trial < 3; trial++) {
            Measurement old, fresh;
            if (trial % 2 == 0) {
                old = measureHistory(history, heads, prepare, gpu, deviceNanos);
                fresh = measureState(direct, prepare, gpu, deviceNanos);
            } else {
                fresh = measureState(direct, prepare, gpu, deviceNanos);
                old = measureHistory(history, heads, prepare, gpu, deviceNanos);
            }
            historyTimes.add(old);
            stateTimes.add(fresh);
            JsonObject sample = new JsonObject();
            sample.addProperty("trial", trial + 1);
            sample.addProperty("history_ms", old.wallMs);
            sample.addProperty("direct_ms", fresh.wallMs);
            sample.addProperty("history_device_ms", old.deviceMs);
            sample.addProperty("direct_device_ms", fresh.deviceMs);
            sample.addProperty("history_gpu_jobs", old.jobs);
            sample.addProperty("direct_gpu_jobs", fresh.jobs);
            JsonArray regions = new JsonArray();
            for (int index = 0; index < heads.size(); index++) {
                List<ArchivedPileReplay.ObjectPiece> previous = old.piles.get(index), current = fresh.piles.get(index);
                long unchanged = previous.stream().filter(piece -> hasSamePose(current, piece)).count();
                JsonObject region = new JsonObject();
                region.addProperty("head", heads.get(index).id());
                region.addProperty("history_objects", previous.size());
                region.addProperty("direct_objects", current.size());
                region.addProperty("unchanged_world_poses", unchanged);
                regions.add(region);
                System.out.printf(Locale.ROOT,
                        "State-mask recorded region trial=%d head=%d history_objects=%d direct_objects=%d unchanged_world_poses=%d%n",
                        trial + 1, heads.get(index).id(), previous.size(), current.size(), unchanged);
            }
            sample.add("regions", regions);
            trials.add(sample);
            System.out.printf(Locale.ROOT,
                    "State-mask benchmark trial=%d history=%.3fms history_device=%.3fms history_jobs=%d history_objects=%d direct=%.3fms direct_device=%.3fms direct_jobs=%d direct_objects=%d ratio=%.2f%n",
                    trial + 1, old.wallMs, old.deviceMs, old.jobs, old.objects,
                    fresh.wallMs, fresh.deviceMs, fresh.jobs, fresh.objects, old.wallMs / fresh.wallMs);
        }
        System.out.printf(Locale.ROOT,
                "State-mask benchmark medians: history=%.3fms direct=%.3fms; outputs intentionally differ, no tick waits or material resampling%n",
                median(historyTimes), median(stateTimes));
        assertArrayEquals(originalSave, Files.readAllBytes(saveFile));
        assertArrayEquals(originalCache, Files.readAllBytes(cacheFile));
        JsonObject report = new JsonObject();
        report.addProperty("scope", "test-only frozen-baseline prototype, not production integration");
        report.addProperty("environment", "latest recorded generation snapshots; not live chunk data");
        report.addProperty("layout_cache", false);
        report.addProperty("tick_waits", false);
        report.addProperty("material_resampling", false);
        report.addProperty("initialization_in_layout_times", false);
        report.addProperty("history_records", history.size());
        report.addProperty("generating_records", history.stream().filter(node -> !node.active().isEmpty()).count());
        report.addProperty("archive_read_ms", archiveReadMs);
        report.addProperty("prototype_metadata_bytes", prototypeBytes);
        report.addProperty("history_median_ms", median(historyTimes));
        report.addProperty("direct_median_ms", median(stateTimes));
        report.add("trials", trials);
        Path output = Path.of("build/verification/state-mask/benchmark.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

    private record Measurement(double wallMs, double deviceMs, int jobs, int objects,
                               List<List<ArchivedPileReplay.ObjectPiece>> piles) { }

    private static Measurement measureHistory(List<ArchivedPileRecipe> history, List<ArchivedPileRecipe> heads,
            Function<ArchivedPileRecipe, ArchivedPileReplay.Prepared> prepare, Function<LayoutInput, LayoutResult> gpu,
            java.util.function.LongSupplier deviceNanos) {
        int[] jobs = {0};
        Function<LayoutInput, LayoutResult> counted = input -> { jobs[0]++; return gpu.apply(input); };
        long start = System.nanoTime(), before = deviceNanos.getAsLong();
        Map<Long, List<ArchivedPileReplay.ObjectPiece>> results = new HashMap<>();
        for (ArchivedPileRecipe node : history)
            results.put(node.id(), ArchivedPileReplay.replay(prepare.apply(node),
                    node.parents().stream().map(results::get).toList(),
                    node.neighbors().stream().map(results::get).toList(), counted));
        return new Measurement((System.nanoTime() - start) / 1e6,
                (deviceNanos.getAsLong() - before) / 1e6, jobs[0],
                heads.stream().mapToInt(head -> results.get(head.id()).size()).sum(),
                heads.stream().map(head -> results.get(head.id())).toList());
    }

    private static Measurement measureState(List<StateMaskPrototype> states,
            Function<ArchivedPileRecipe, ArchivedPileReplay.Prepared> prepare, Function<LayoutInput, LayoutResult> gpu,
            java.util.function.LongSupplier deviceNanos) {
        int[] jobs = {0};
        Function<LayoutInput, LayoutResult> counted = input -> { jobs[0]++; return gpu.apply(input); };
        long start = System.nanoTime(), before = deviceNanos.getAsLong();
        int objects = 0;
        List<List<ArchivedPileReplay.ObjectPiece>> piles = new ArrayList<>();
        for (StateMaskPrototype state : states) {
            var pieces = state.restore(prepare, counted);
            assertFalse(pieces.isEmpty());
            objects += pieces.size();
            piles.add(pieces);
        }
        return new Measurement((System.nanoTime() - start) / 1e6,
                (deviceNanos.getAsLong() - before) / 1e6, jobs[0], objects, List.copyOf(piles));
    }

    static Map<String, List<PileModel>> readCachedMaterials(CompoundTag tag, Path file)
            throws Exception {
        long start = System.nanoTime();
        var groups = JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                .getAsJsonObject("environments");
        assertEquals(1, groups.size(), "This local fixture must have an unambiguous material scope");
        String scope = groups.keySet().iterator().next();
        Map<String, List<PileModel>> models = new HashMap<>();
        int unsupported = 0;
        try (SampleCache cache = new SampleCache(file)) {
            for (Tag value : tag.getList("Palettes", Tag.TAG_COMPOUND)) {
                PaletteSpec palette = PaletteSpec.load((CompoundTag) value);
                List<PileModel> selected = new ArrayList<>();
                for (PileModels.Candidate candidate : palette.candidates()) {
                    if (candidate.explicit() != null) { selected.add(candidate.explicit()); continue; }
                    var key = new MaterialSampler.Key(candidate.state(), new CompoundTag(), Direction.NORTH);
                    SampleCache.Entry entry = cache.get(scope, key);
                    assertNotNull(entry, "Missing recorded material sample: " + candidate.state());
                    if (entry.sample() != null) selected.add(entry.sample().model(candidate.id(), candidate.weight()));
                    else unsupported++;
                }
                assertFalse(selected.isEmpty());
                models.put(palette.id(), List.copyOf(selected));
            }
        }
        System.out.printf(Locale.ROOT, "State-mask material cache: supported=%s unsupported=%d read=%.3fms; no placement callbacks%n",
                models.values().stream().map(List::size).toList(), unsupported, (System.nanoTime() - start) / 1e6);
        return Map.copyOf(models);
    }

    static ArchivedPileReplay.Prepared prepareRecorded(ArchivedTerrainShapes shapes, ArchivedPileRecipe node,
            List<PileModel> models) {
        // Standalone tests lack the Forge block registry. These recorded terrain states only use
        // vanilla colliders plus barricades, which production explicitly skips as obstacles.
        List<ArchivedPileRecipe.Damage> damage = node.damage().stream().map(edit -> new ArchivedPileRecipe.Damage(
                edit.pos(), edit.fullCell(), edit.state(), withoutBarricades(edit.context()))).toList();
        ArchivedPileRecipe input = new ArchivedPileRecipe(node.id(), node.seed(), node.parents(), node.domain(), node.active(),
                node.repack(), node.palette(), withoutBarricades(node.terrain()), damage,
                node.neighbors(), node.embedded());
        ArchivedPileReplay.Prepared prepared = shapes.prepare(input, models);
        return new ArchivedPileReplay.Prepared(node, models, prepared.obstacles(), prepared.unknown(), prepared.damage());
    }

    private static ArchivedTerrainSnapshot withoutBarricades(ArchivedTerrainSnapshot input) {
        Map<BlockPos, WorldBlock> blocks = new HashMap<>();
        input.blocks().forEach((pos, block) -> {
            if (!block.state().equals("barricade:barricade")) blocks.put(pos, block);
            else assertNull(block.embedded(), "A sampled embedded collider cannot be omitted");
        });
        return new ArchivedTerrainSnapshot(blocks, input.unknown());
    }

    private static Set<Long> ancestors(long head, Map<Long, ArchivedPileRecipe> recipes) {
        Set<Long> found = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>(List.of(head));
        while (!queue.isEmpty()) {
            long id = queue.removeFirst();
            if (found.add(id)) queue.addAll(recipes.get(id).prerequisites());
        }
        return found;
    }

    private static ArchivedPileRecipe baseline(long id, long seed, Set<BlockPos> domain,
            String palette, ArchivedTerrainSnapshot terrain) {
        return new ArchivedPileRecipe(id, seed, List.of(), domain, domain, Set.of(), palette, terrain, List.of());
    }

    private static Set<BlockPos> domain(int width, int height, int depth, BlockPos origin) {
        Set<BlockPos> cells = new HashSet<>();
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++)
            for (int z = 0; z < depth; z++) cells.add(origin.offset(x, y, z));
        return Set.copyOf(cells);
    }

    private static byte[] compressed(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, bytes);
        return bytes.toByteArray();
    }

    private static void assertSameWorldObjects(List<ArchivedPileReplay.ObjectPiece> expected,
            List<ArchivedPileReplay.ObjectPiece> actual) {
        assertEquals(expected.stream().map(ArchivedPileReplay.ObjectPiece::key).collect(java.util.stream.Collectors.toSet()),
                actual.stream().map(ArchivedPileReplay.ObjectPiece::key).collect(java.util.stream.Collectors.toSet()));
        for (var piece : expected) {
            var restored = actual.stream().filter(other -> other.key().equals(piece.key())).findFirst().orElseThrow();
            assertEquals(piece.piece().model(), restored.piece().model());
            assertTrue(samePose(piece, restored), "World position, scale or rotation changed");
        }
    }

    private static boolean hasSamePose(List<ArchivedPileReplay.ObjectPiece> pieces, ArchivedPileReplay.ObjectPiece old) {
        return pieces.stream().anyMatch(piece -> samePose(old, piece));
    }

    private static boolean samePose(ArchivedPileReplay.ObjectPiece a, ArchivedPileReplay.ObjectPiece b) {
        PilePiece x = a.piece(), y = b.piece();
        return x.model().equals(y.model()) && x.scale() == y.scale()
                && x.qx() == y.qx() && x.qy() == y.qy() && x.qz() == y.qz() && x.qw() == y.qw()
                && Math.abs((a.owner().getX() + (double) x.x()) - (b.owner().getX() + (double) y.x())) < .00001
                && Math.abs((a.owner().getY() + (double) x.y()) - (b.owner().getY() + (double) y.y())) < .00001
                && Math.abs((a.owner().getZ() + (double) x.z()) - (b.owner().getZ() + (double) y.z())) < .00001;
    }

    private static double median(List<Measurement> times) {
        return times.stream().mapToDouble(Measurement::wallMs).sorted().toArray()[times.size() / 2];
    }
}
