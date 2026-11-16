package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import com.scarasol.barricade.window.*;
import java.awt.geom.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Optional local diagnosis. Every world/region file is opened read-only. */
@EnabledIfEnvironmentVariable(named = "BARRICADE_GAP_DIAGNOSIS", matches = "1")
class GenerationGapDiagnosisTest {
    private static final Path WORLD = Path.of("run/saves/Test World (1)");

    @Test void fenceTagBetweenSavedWindowBoards() throws Exception {
        var memory = MemoryFixture.create(false);
        CompoundTag data = read(WORLD.resolve("data/barricade_embedded_v1.dat")).getCompound("data");
        PaletteSpec fences = data.getList("Palettes", Tag.TAG_COMPOUND).stream()
                .map(value -> PaletteSpec.load((CompoundTag) value))
                .filter(palette -> palette.tag().equals("forge:fences/wooden")).findFirst().orElseThrow();
        var models = StateMaskValidation.readCachedMaterials(data,
                Path.of("run/cache/barricade/material_samples.json")).get(fences.id());
        WindowBoardsData boards = WindowBoardsData.load(
                read(WORLD.resolve("data/barricade_window_boards_v1.dat")).getCompound("data"));
        int cases = 0, erased = 0;
        try (GpuSession gpu = new GpuSession()) {
            for (BoardGroup group : boards.groups()) {
                if (group.plane().facing() != Direction.UP) continue;
                Set<BoardCell> targets = new TreeSet<>(group.shape());
                for (BoardCell target : targets) {
                    BlockPos cell = group.plane().block(target);
                    Map<BlockPos, BlockState> ground = new HashMap<>();
                    for (BlockPos pos : BlockPos.betweenClosed(cell.offset(-4, -1, -4), cell.offset(4, -1, 4)))
                        ground.put(pos.immutable(), net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
                    memory.context(ground, Map.of());
                    TerrainShapes terrain = new TerrainShapes(memory, ignored -> true);
                    Map<BlockPos, FieldVisibility.Shape> boardShapes = new TreeMap<>();
                    for (BoardCell neighbor : group.survivors()) {
                        if (neighbor.equals(target)) continue;
                        BlockPos pos = group.plane().block(neighbor);
                        var shape = windowShape(pos, group.plane().facing());
                        boardShapes.put(pos, shape);
                    }
                    var view = terrain.view(Set.of(cell), Map.of());
                    for (long seed : new long[]{42, -4025746975994481983L, -3061543087357994095L}) {
                        var definition = FieldDefinition.create(seed, fences);
                        var field = new SpatialField("minecraft:overworld", definition, models, gpu::solve);
                        List<FieldObject> generated = field.query(Set.of(cell));
                        assertFalse(generated.isEmpty());
                        List<FieldObject> visible = generated.stream().filter(view::visible).filter(object ->
                                boardShapes.values().stream().allMatch(shape -> {
                                    int dx = object.owner().getX() - shape.pos().getX();
                                    int dy = object.owner().getY() - shape.pos().getY();
                                    int dz = object.owner().getZ() - shape.pos().getZ();
                                    for (OrientedBox box : object.piece().colliders(dx, dy, dz))
                                        for (OrientedBox other : shape.boxes())
                                            if (box.penetration(other, 0, 0, 0) > TerrainShapes.TOLERANCE + 1e-6f) return false;
                                    return true;
                                })).toList();
                        cases++;
                        if (!visible.isEmpty()) continue;
                        erased++;
                        System.out.printf("GAP REPRO cell=%s group=%s seed=%d generated=%d visible=%d board_neighbors=%d%n",
                                cell, group.id(), seed, generated.size(), visible.size(), boardShapes.size());
                        for (FieldObject object : generated)
                            System.out.printf("GAP REPRO hidden_model=%s owner=%s hit_boards=%s%n",
                                    object.piece().model().id(), object.owner(),
                                    boardShapes.entrySet().stream().filter(entry -> FieldVisibility.hit(object, entry.getValue()))
                                            .map(Map.Entry::getKey).toList());
                        TerrainShapes noBoards = new TerrainShapes(memory, ignored -> true);
                        assertEquals(generated, generated.stream().filter(noBoards.view(
                                Set.of(cell), Map.of())::visible).toList(),
                                "Remove only window-board colliders, without regenerating objects");
                    }
                }
            }
            System.out.printf("GAP BETWEEN_BOARDS cases=%d fully_hidden=%d real_gpu=%s parity_jobs=%d%n",
                    cases, erased, gpu.worker != null, gpu.jobs);
        }
        assertTrue(erased > 0, "The reported surrounding-board geometry did not reproduce total suppression");
        assertEquals(0, memory.chunkRequests());
    }

    @Test void reconstructBarricadeGenerationAndOverlay() throws Exception {
        var memory = MemoryFixture.create(false);
        CompoundTag data = read(WORLD.resolve("data/barricade_embedded_v1.dat")).getCompound("data");
        List<FieldRegion> regions = new ArrayList<>();
        for (Tag value : data.getList("Regions", Tag.TAG_COMPOUND))
            regions.add(FieldRegion.load((CompoundTag) value));
        Path cache = Path.of("run/cache/barricade/material_samples.json");
        byte[] samples = Files.readAllBytes(cache);
        var pools = StateMaskValidation.readCachedMaterials(data, cache);
        var chunks = new SavedChunks(WORLD);
        List<FieldVisibility.Shape> windows = new ArrayList<>();
        Map<BlockPos, BlockState> terrain = new HashMap<>();
        Set<String> unsupported = new TreeSet<>();
        for (FieldRegion region : regions)
            for (BlockPos cell : region.domain())
                for (BlockPos near : BlockPos.betweenClosed(cell.offset(-5, -5, -5), cell.offset(5, 5, 5))) {
                    BlockPos pos = near.immutable();
                    if (terrain.containsKey(pos)) continue;
                    CompoundTag state = chunks.block(pos);
                    String name = state.getString("Name");
                    if (name.equals("barricade:barricade")) continue;
                    if (name.equals("barricade:window_boards")) {
                        windows.add(windowShape(pos, Direction.byName(state.getCompound("Properties").getString("facing"))));
                    } else {
                        if (!name.startsWith("minecraft:")) unsupported.add(name);
                        terrain.put(pos, NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), state));
                    }
                }
        System.out.printf("GAP native_unsupported=%s%n", unsupported);
        assertTrue(unsupported.isEmpty(), "Do not silently omit modded terrain");
        memory.context(terrain, Map.of());
        TerrainShapes shapes = new TerrainShapes(memory, ignored -> true);
        int generatedTotal = 0, visibleTotal = 0, cellsTotal = 0;
        List<BlockPos> empty = new ArrayList<>();
        try (GpuSession gpu = new GpuSession()) {
          for (FieldRegion region : regions.stream().sorted(Comparator.comparing(FieldRegion::id)).toList()) {
            Set<BlockPos> baseline = new HashSet<>();
            for (FieldRegion other : regions)
                if (other.definition().equals(region.definition())) baseline.addAll(other.baseline());
            var models = pools.get(region.definition().palette()).stream()
                    .filter(model -> model.span().length() * 1.05f / 2 <= 5).toList();
            var field = new SpatialField("minecraft:overworld", region.definition(), models, gpu::solve);
            List<FieldObject> generated = field.query(region.domain(), baseline);
            var overlay = new FieldOverlay(region, 1, generated, regions);
            assertTrue(overlay.advance(shapes, Integer.MAX_VALUE, Long.MAX_VALUE));
            assertTrue(region.embedded().isEmpty(), "Embedded rendering needs a captured mesh fixture");
            List<FieldObject> visible = overlay.visible().stream()
                    .filter(object -> windows.stream().noneMatch(shape -> FieldVisibility.hit(object, shape))).toList();
            System.out.printf("GAP rebuilt_region=%s models=%d generated=%d damaged=%d visible=%d%n", region.id(),
                    models.size(), generated.size(),
                    generated.stream().filter(object -> FieldVisibility.damaged(object, region.mask())).count(), visible.size());
            generatedTotal += generated.size();
            visibleTotal += visible.size();
            cellsTotal += region.domain().size();
            for (BlockPos pos : region.domain().stream().sorted().toList()) {
                long before = generated.stream().filter(object -> FieldVisibility.hit(object, FieldVisibility.cube(pos))).count();
                long after = visible.stream().filter(object -> FieldVisibility.hit(object, FieldVisibility.cube(pos))).count();
                if (after == 0 || before == 0) {
                    empty.add(pos);
                    System.out.printf("GAP EMPTY_BARRICADE cell=%s generated_hits=%d visible_hits=%d%n", pos, before, after);
                }
            }
          }
          System.out.printf("GAP TOTAL regions=%d cells=%d generated=%d visible=%d empty=%d real_gpu=%s parity_jobs=%d%n",
                  regions.size(), cellsTotal, generatedTotal, visibleTotal, empty.size(), gpu.worker != null, gpu.jobs);
        }
        assertTrue(empty.isEmpty(), "Current saved barricade gaps: " + empty);
        assertEquals(0, memory.chunkRequests());
        assertArrayEquals(samples, Files.readAllBytes(cache));
    }

    static final class GpuSession implements AutoCloseable {
        private long window;
        private GpuLayoutWorker worker;
        private int jobs;
        GpuSession() throws IOException {
            if (!"1".equals(System.getenv("BARRICADE_GPU_TESTS"))) return;
            assertTrue(glfwInit());
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(1, 1, "Read-only saved field diagnosis", 0, 0);
            assertNotEquals(0, window);
            try (var stream = getClass().getResourceAsStream("/assets/barricade/shaders/packing.compute")) {
                worker = new GpuLayoutWorker(window, new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        int[] solve(FieldBatch batch) {
            int[] reference = FieldOracle.solve(batch);
            if (worker == null) return reference;
            try {
                int[] selected = worker.submit(batch).get(60, TimeUnit.SECONDS);
                if (batch instanceof PackingBatch plan) assertArrayEquals(plan.compactResult(reference), selected);
                else if (batch instanceof PackingGroup group) {
                    var expected = group.split(reference);
                    var actual = group.split(selected);
                    for (int i = 0; i < group.plans.size(); i++)
                        assertArrayEquals(group.plans.get(i).compactResult(expected.get(i)), actual.get(i));
                } else fail("Unexpected production batch type");
                jobs++;
                return selected;
            } catch (Exception failure) { throw new AssertionError("Real GPU diagnosis failed", failure); }
        }
        @Override public void close() throws Exception {
            if (worker != null) worker.stop().get(10, TimeUnit.SECONDS);
            if (window != 0) {
                glfwDestroyWindow(window);
                glfwTerminate();
            }
        }
    }

    private static FieldVisibility.Shape windowShape(BlockPos pos, Direction facing) {
        // Standalone JUnit has a frozen block registry; mirror the six production slabs exactly.
        OrientedBox box = switch (facing) {
            case NORTH -> OrientedBox.axisAligned(0, 0, .625f, 1, 1, 1);
            case SOUTH -> OrientedBox.axisAligned(0, 0, 0, 1, 1, .375f);
            case WEST -> OrientedBox.axisAligned(.625f, 0, 0, 1, 1, 1);
            case EAST -> OrientedBox.axisAligned(0, 0, 0, .375f, 1, 1);
            case DOWN -> OrientedBox.axisAligned(0, .625f, 0, 1, 1, 1);
            case UP -> OrientedBox.axisAligned(0, 0, 0, 1, .375f, 1);
        };
        return new FieldVisibility.Shape(pos, List.of(box), true);
    }

    @Test void inspectSavedStateWithoutChangingIt() throws Exception {
        MemoryFixture.create(false).reset();
        CompoundTag level = read(WORLD.resolve("level.dat")).getCompound("Data");
        CompoundTag player = level.getCompound("Player");
        ListTag position = player.getList("Pos", Tag.TAG_DOUBLE);
        ListTag rotation = player.getList("Rotation", Tag.TAG_FLOAT);
        Vec3 feet = new Vec3(position.getDouble(0), position.getDouble(1), position.getDouble(2));
        Vec3 eye = feet.add(0, 1.62, 0);
        Vec3 end = eye.add(Vec3.directionFromRotation(rotation.getFloat(1), rotation.getFloat(0)).scale(8));
        System.out.printf("GAP player=%s rotation=%s eye=%s ray_end=%s%n", feet, rotation, eye, end);
        CompoundTag data = read(WORLD.resolve("data/barricade_embedded_v1.dat")).getCompound("data");
        PileData loaded = PileData.load(data);
        List<FieldRegion> regions = new ArrayList<>();
        for (Tag value : data.getList("Regions", Tag.TAG_COMPOUND))
            regions.add(FieldRegion.load((CompoundTag) value));
        Map<String, PaletteSpec> palettes = new HashMap<>();
        for (Tag value : data.getList("Palettes", Tag.TAG_COMPOUND)) {
            PaletteSpec palette = PaletteSpec.load((CompoundTag) value);
            palettes.put(palette.id(), palette);
            System.out.printf("GAP palette=%s source=%s candidate_count=%d%n", palette.id(), palette.tag(),
                    palette.candidates().size());
        }
        System.out.printf("GAP metadata regions=%d cells=%d sample_epoch=%d serial=%d%n",
                loaded.regionCount(), loaded.cellCount(), loaded.sampleEpoch(), loaded.serial());
        SavedChunks chunks = new SavedChunks(WORLD);
        for (FieldRegion region : regions.stream().sorted(Comparator.comparingDouble(value ->
                value.domain().stream().mapToDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(feet))
                        .min().orElseThrow())).toList()) {
            double distance = region.domain().stream().mapToDouble(pos -> Vec3.atCenterOf(pos).distanceTo(feet))
                    .min().orElseThrow();
            if (distance > 24) continue;
            System.out.printf("GAP region=%s distance=%.2f seed=%d palette=%s domain=%s mask=%s embedded=%s%n",
                    region.id(), distance, region.definition().seed(), region.definition().palette(),
                    region.domain().stream().sorted().toList(), region.mask().cells().stream().sorted().toList(),
                    region.embedded().keySet());
            for (BlockPos pos : region.domain().stream().sorted().toList()) {
                var hit = new AABB(pos).clip(eye, end);
                System.out.printf("GAP cell=%s native=%s ray_distance=%s%n", pos, chunks.block(pos),
                        hit.map(value -> String.format(Locale.ROOT, "%.3f", value.distanceTo(eye))).orElse("-"));
            }
        }
        Path boardsFile = WORLD.resolve("data/barricade_window_boards_v1.dat");
        if (Files.isRegularFile(boardsFile)) {
            WindowBoardsData boards = WindowBoardsData.load(read(boardsFile).getCompound("data"));
            int boardCells = 0;
            double minimumCoverage = 256;
            for (BoardGroup group : boards.groups()) {
                List<BlockPos> cells = group.survivors().stream().map(group.plane()::block).sorted().toList();
                if (cells.stream().noneMatch(pos -> Vec3.atCenterOf(pos).distanceTo(feet) < 24)) continue;
                System.out.printf("GAP window_group=%s facing=%s cells=%s damage=%s remnant=%s%n",
                        group.id(), group.plane().facing(), cells, group.damage(), group.remnant() != null);
                BoardLayout.Layout layout = BoardLayout.generate(group.layoutKey());
                Set<BoardCell> hidden = new HashSet<>(group.damage());
                for (BoardCell cell : group.survivors()) {
                    CompoundTag state = chunks.block(group.plane().block(cell));
                    if (!state.getString("Name").equals("barricade:window_boards")
                            || !state.getCompound("Properties").getString("facing").equals(group.plane().facing().getName()))
                        hidden.add(cell);
                }
                List<BoardLayout.Fragment> visible = BoardLayout.visible(layout, group, hidden);
                Area generatedArea = footprint(BoardLayout.visible(layout, Set.of()));
                Area visibleArea = footprint(visible);
                System.out.printf("GAP window_layout=%s seed=%d planks=%d fragments=%d hidden=%s%n",
                        group.id(), group.seed(), layout.planks().size(), visible.size(), hidden);
                for (BoardCell cell : group.survivors().stream().sorted().toList()) {
                    double cellCoverage = coverage(visibleArea, cell);
                    minimumCoverage = Math.min(minimumCoverage, cellCoverage);
                    boardCells++;
                    assertTrue(cellCoverage > .00001, "Empty saved window-board cell: " + group.plane().block(cell));
                    System.out.printf("GAP window_cell=%s native=%s generated_planks=%d area_before=%.5f area_after=%.5f%n",
                            group.plane().block(cell), chunks.block(group.plane().block(cell)),
                            layout.planks().stream().filter(plank -> plank.cells().contains(cell)).count(),
                            coverage(generatedArea, cell), cellCoverage);
                }
            }
            System.out.printf("GAP WINDOW_TOTAL groups=%d cells=%d minimum_cell_wood_pct=%.3f%n",
                    boards.groups().size(), boardCells, minimumCoverage / 256 * 100);
            for (BlockPos mutable : BlockPos.betweenClosed(BlockPos.containing(feet).offset(-26, -1, -26),
                    BlockPos.containing(feet).offset(26, 5, 26))) {
                BlockPos pos = mutable.immutable();
                String block = chunks.block(pos).getString("Name");
                if (block.equals("barricade:barricade") && !loaded.contains(pos)
                        || block.equals("barricade:window_boards") && boards.activeAt(pos) == null)
                    System.out.printf("GAP ORPHAN cell=%s native=%s%n", pos, chunks.block(pos));
            }
        }
        System.out.printf("GAP ray_blocks=%n");
        Set<BlockPos> rayCells = new LinkedHashSet<>();
        for (int i = 0; i <= 160; i++) rayCells.add(BlockPos.containing(eye.lerp(end, i / 160.0)));
        for (BlockPos pos : rayCells)
            System.out.printf("GAP ray_cell=%s native=%s%n", pos, chunks.block(pos));
    }

    private static CompoundTag read(Path path) throws IOException {
        return NbtIo.readCompressed(new ByteArrayInputStream(Files.readAllBytes(path)));
    }

    private static Area footprint(List<BoardLayout.Fragment> fragments) {
        Area result = new Area();
        for (var fragment : fragments) for (var face : fragment.faces())
            if (!face.nail() && face.normal().z > .9) {
                Path2D path = new Path2D.Double();
                for (int i = 0; i < face.vertices().size(); i++) {
                    var vertex = face.vertices().get(i);
                    if (i == 0) path.moveTo(vertex.u(), vertex.v());
                    else path.lineTo(vertex.u(), vertex.v());
                }
                path.closePath();
                result.add(new Area(path));
            }
        return result;
    }

    private static double coverage(Area source, BoardCell cell) {
        Area clipped = new Area(source);
        clipped.intersect(new Area(new Rectangle2D.Double(cell.u() * 16, cell.v() * 16, 16, 16)));
        double result = 0, firstX = 0, firstY = 0, lastX = 0, lastY = 0;
        double[] point = new double[6];
        for (PathIterator path = clipped.getPathIterator(null); !path.isDone(); path.next()) {
            switch (path.currentSegment(point)) {
                case PathIterator.SEG_MOVETO -> { firstX = lastX = point[0]; firstY = lastY = point[1]; }
                case PathIterator.SEG_LINETO -> {
                    result += lastX * point[1] - point[0] * lastY;
                    lastX = point[0]; lastY = point[1];
                }
                case PathIterator.SEG_CLOSE -> result += lastX * firstY - firstX * lastY;
                default -> fail("Non-polygonal board");
            }
        }
        return Math.abs(result) / 2;
    }

    private static final class SavedChunks {
        private final Path world;
        private final Map<Long, CompoundTag> chunks = new HashMap<>();
        SavedChunks(Path world) { this.world = world; }
        CompoundTag block(BlockPos pos) throws IOException {
            int cx = Math.floorDiv(pos.getX(), 16), cz = Math.floorDiv(pos.getZ(), 16);
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            CompoundTag chunk = chunks.get(key);
            if (chunk == null) {
                chunk = chunk(cx, cz);
                chunks.put(key, chunk);
            }
            for (Tag value : chunk.getList("sections", Tag.TAG_COMPOUND)) {
                CompoundTag section = (CompoundTag) value;
                if (section.getByte("Y") != Math.floorDiv(pos.getY(), 16)) continue;
                CompoundTag states = section.getCompound("block_states");
                ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
                if (palette.isEmpty()) return air();
                if (palette.size() == 1) return palette.getCompound(0);
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                int index = Math.floorMod(pos.getY(), 16) * 256 + Math.floorMod(pos.getZ(), 16) * 16
                        + Math.floorMod(pos.getX(), 16);
                long[] packed = states.getLongArray("data");
                int perLong = 64 / bits;
                return palette.getCompound((int) (packed[index / perLong] >>> ((index % perLong) * bits)
                        & ((1L << bits) - 1)));
            }
            return air();
        }
        private static CompoundTag air() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Name", "minecraft:air");
            return tag;
        }
        private CompoundTag chunk(int cx, int cz) throws IOException {
            Path file = world.resolve("region/r." + Math.floorDiv(cx, 32) + "." + Math.floorDiv(cz, 32) + ".mca");
            if (!Files.isRegularFile(file)) return new CompoundTag();
            try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
                input.seek(4L * (Math.floorMod(cx, 32) + Math.floorMod(cz, 32) * 32));
                int location = input.readInt();
                if (location == 0) return new CompoundTag();
                input.seek((long) (location >>> 8) * 4096);
                int length = input.readInt();
                int compression = input.readUnsignedByte();
                assertTrue(length > 1 && length < 16 * 1024 * 1024, "Invalid region chunk length");
                byte[] bytes = new byte[length - 1];
                input.readFully(bytes);
                InputStream raw = new ByteArrayInputStream(bytes);
                InputStream decoded = switch (compression) {
                    case 1 -> new GZIPInputStream(raw);
                    case 2 -> new InflaterInputStream(raw);
                    case 3 -> raw;
                    default -> throw new IOException("Unsupported chunk compression: " + compression);
                };
                try (DataInputStream data = new DataInputStream(decoded)) { return NbtIo.read(data); }
            }
        }
    }
}
