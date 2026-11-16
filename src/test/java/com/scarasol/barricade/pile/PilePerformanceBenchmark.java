package com.scarasol.barricade.pile;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.scarasol.barricade.recipe.ArchivedPileRecipe;
import com.scarasol.barricade.recipe.ArchivedPileReplay;
import com.scarasol.barricade.recipe.TestPileReplay;
import com.scarasol.barricade.recipe.ArchivedTerrainSnapshot;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

/** Explicit reproducible CPU/JFR benchmark; does not launch or modify a game world. */
public final class PilePerformanceBenchmark {
    private static final BlockPos ORIGIN = new BlockPos(100_000, 80, -250_000);
    private static final List<OrientedBox> FLOOR = floor();
    private PilePerformanceBenchmark() { }
    private static List<OrientedBox> floor() {
        List<OrientedBox> result = new ArrayList<>();
        for (int x = -1; x <= 20; x++) for (int z = -1; z <= 8; z++)
            result.add(OrientedBox.axisAligned(x, -1, z, x + 1, 0, z + 1));
        return List.copyOf(result);
    }
    public static void main(String[] args) throws Exception {
        MemoryFixture.create(false).reset();
        String label = System.getenv().getOrDefault("BARRICADE_BENCHMARK_LABEL", "current");
        Path folder = Path.of("build", "perf");
        Files.createDirectories(folder);
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        int rounds = Integer.parseInt(System.getenv().getOrDefault("BARRICADE_BENCHMARK_ROUNDS", "1"));
        if (rounds < 1 || rounds > 10) throw new IllegalArgumentException("Benchmark rounds must be 1..10");
        JsonArray timings = new JsonArray();
        try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.start();
            // Warm up after enabling profiling so its instrumentation is present during compilation.
            for (int i = 0; i < 3; i++) TestPileReplay.replay(prepared(i + 1, 600 + i, rectangle(6, 2, 4),
                    rectangle(6, 2, 4), Set.of()), List.of());
            for (int i = 0; i < 2; i++) TestPileReplay.replay(prepared(1, 900 + i, rectangle(20, 3, 8),
                    rectangle(20, 3, 8), Set.of()), List.of());
            Set<BlockPos> domain = rectangle(20, 3, 8);
            for (int round = 1; round <= rounds; round++) for (long seed : new long[]{17, 42, 100, 321}) {
                String suffix = rounds == 1 ? "" : "-round-" + round;
                var input = prepared(1, seed, domain, domain, Set.of());
                List<ArchivedPileReplay.ObjectPiece> base = measure("generate-" + seed + suffix, input, List.of(), timings, bean);
                BlockPos changed = ORIGIN.offset(20, 1, 4);
                Set<BlockPos> expanded = new HashSet<>(domain);
                expanded.add(changed);
                Set<BlockPos> patch = new HashSet<>();
                for (BlockPos pos : expanded)
                    if (Math.abs(pos.getX() - changed.getX()) <= 2 && Math.abs(pos.getY() - changed.getY()) <= 2
                            && Math.abs(pos.getZ() - changed.getZ()) <= 2) patch.add(pos);
                measure("local-edit-" + seed + suffix, prepared(2, seed ^ 999, expanded, patch, patch),
                        List.of(base), timings, bean);
            }
            recording.stop();
            recording.dump(folder.resolve(label + ".jfr"));
        }
        JsonObject report = new JsonObject();
        report.addProperty("label", label);
        report.addProperty("java", System.getProperty("java.version"));
        report.addProperty("warmup_small_regions", 3);
        report.addProperty("warmup_large_regions", 2);
        report.addProperty("rounds", rounds);
        report.add("cases", timings);
        Files.writeString(folder.resolve(label + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    private static List<ArchivedPileReplay.ObjectPiece> measure(String name, ArchivedPileReplay.Prepared input,
            List<List<ArchivedPileReplay.ObjectPiece>> parents, JsonArray report, com.sun.management.ThreadMXBean bean) throws Exception {
        long id = Thread.currentThread().getId(), allocated = bean.getThreadAllocatedBytes(id);
        long cpu = bean.getCurrentThreadCpuTime(), start = System.nanoTime();
        var output = TestPileReplay.replay(input, parents);
        double ms = (System.nanoTime() - start) / 1_000_000.0;
        double cpuMs = (bean.getCurrentThreadCpuTime() - cpu) / 1_000_000.0;
        double mib = (bean.getThreadAllocatedBytes(id) - allocated) / 1048576.0;
        String digest = digest(output);
        JsonObject row = new JsonObject();
        row.addProperty("name", name);
        row.addProperty("ms", ms);
        row.addProperty("cpu_ms", cpuMs);
        row.addProperty("allocated_mib", mib);
        row.addProperty("objects", output.size());
        row.addProperty("sha256", digest);
        report.add(row);
        System.out.printf(Locale.ROOT, "%s: %.2f ms, %.2f cpu ms, %.2f MiB allocated, %d objects, %s%n",
                name, ms, cpuMs, mib, output.size(), digest);
        return output;
    }
    private static ArchivedPileReplay.Prepared prepared(long id, long seed, Set<BlockPos> domain,
                                                Set<BlockPos> active, Set<BlockPos> repack) {
        var node = new ArchivedPileRecipe(id, seed, id == 1 ? List.of() : List.of(1L), domain, active, repack,
                "benchmark", ArchivedTerrainSnapshot.EMPTY, List.of());
        return new ArchivedPileReplay.Prepared(node, TestPalette.models(), FLOOR, List.of(), List.of());
    }
    private static Set<BlockPos> rectangle(int width, int height, int depth) {
        Set<BlockPos> result = new HashSet<>();
        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) for (int z = 0; z < depth; z++)
            result.add(ORIGIN.offset(x, y, z));
        return Set.copyOf(result);
    }
    private static String digest(List<ArchivedPileReplay.ObjectPiece> pieces) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream stream = new DataOutputStream(output)) {
            for (var piece : pieces) {
                stream.writeLong(piece.key().birth());
                stream.writeInt(piece.key().ordinal());
                stream.writeLong(piece.owner().asLong());
                CompoundTag tag = piece.piece().save(0);
                tag.put("Definition", piece.piece().model().save());
                NbtIo.write(tag, stream);
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(output.toByteArray()));
    }
}
