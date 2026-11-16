package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Optional read-only local-world diagnosis, not a production tolerance implementation. */
@EnabledIfEnvironmentVariable(named = "BARRICADE_GAP_DIAGNOSIS", matches = "1")
class FencePenetrationDiagnosisTest {
    private static final Path SAVE = Path.of("run/saves/Test World (1)/data/barricade_embedded_v1.dat");
    private static final Path CACHE = Path.of("run/cache/barricade/material_samples.json");
    private static final float[] TOLERANCES = {
            0, 1f / 128, 1f / 64, 1f / 32, 3f / 64, 1f / 16, 3f / 32,
            1f / 8, 3f / 16, 225f / 1024, 1f / 4
    };
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private record PieceDepth(String model, float collision, float visible, float localInset,
                              float collisionFloor, float collisionSide, float visibleFloor, float visibleSide) { }
    private record Scene(BlockPos cell, long seed, String pattern, Direction orientation,
                         int current, int floorOnlyCurrent, int sidesOnlyCurrent, List<PieceDepth> pieces) { }

    @Test void measureThreeGrassNeighborsWithoutChangingGeneration() throws Exception {
        var memory = MemoryFixture.create(false);
        byte[] saved = Files.readAllBytes(SAVE), cached = Files.readAllBytes(CACHE);
        CompoundTag data = NbtIo.readCompressed(new ByteArrayInputStream(saved)).getCompound("data");
        PaletteSpec palette = data.getList("Palettes", Tag.TAG_COMPOUND).stream()
                .map(value -> PaletteSpec.load((CompoundTag) value))
                .filter(value -> value.tag().equals("forge:fences/wooden")).findFirst().orElseThrow();
        var models = StateMaskValidation.readCachedMaterials(data, CACHE).get(palette.id());
        Map<String, PileModel> visibleModels = new HashMap<>();
        for (PileModel model : models) {
            var visual = visualModel(model);
            visibleModels.put(model.id(), visual);
            System.out.printf("FENCE DEPTH material=%s collision=%s visual_elements=%s%n",
                    model.id(), model.boxes(), visual.boxes());
        }
        List<Scene> scenes = new ArrayList<>();
        List<BlockPos> origins = List.of(new BlockPos(-11, -60, 8), new BlockPos(-18, -60, 9),
                new BlockPos(12, -59, -1), new BlockPos(11, -60, -3),
                new BlockPos(-26, -60, -18), new BlockPos(6, -60, -7));
        try (var gpu = new GenerationGapDiagnosisTest.GpuSession()) {
            for (BlockPos cell : origins)
                for (long seed : new long[]{42, -4025746975994481983L, -3061543087357994095L}) {
                    var field = new SpatialField("minecraft:overworld", FieldDefinition.create(seed, palette), models, gpu::solve);
                    List<FieldObject> objects = field.query(Set.of(cell));
                    assertFalse(objects.isEmpty());
                    for (String pattern : new String[]{"three_sides", "corner"})
                      for (Direction opening : SIDES) {
                        Map<BlockPos, BlockState> blocks = new HashMap<>();
                        for (BlockPos pos : BlockPos.betweenClosed(cell.offset(-3, -1, -3), cell.offset(3, -1, 3)))
                            blocks.put(pos.immutable(), Blocks.GRASS_BLOCK.defaultBlockState());
                        if (pattern.equals("three_sides")) {
                            for (Direction side : SIDES)
                                if (side != opening) blocks.put(cell.relative(side), Blocks.GRASS_BLOCK.defaultBlockState());
                        } else {
                            blocks.put(cell.relative(opening), Blocks.GRASS_BLOCK.defaultBlockState());
                            blocks.put(cell.relative(opening.getClockWise()), Blocks.GRASS_BLOCK.defaultBlockState());
                            blocks.put(cell.relative(opening).relative(opening.getClockWise()), Blocks.GRASS_BLOCK.defaultBlockState());
                        }
                        Map<BlockPos, BlockState> floorBlocks = new HashMap<>(), sideBlocks = new HashMap<>();
                        blocks.forEach((pos, state) -> (pos.getY() < cell.getY() ? floorBlocks : sideBlocks).put(pos, state));
                        memory.context(floorBlocks, Map.of());
                        var floorView = new TerrainShapes(memory, ignored -> true)
                                .view(Set.of(cell), Map.of());
                        int floorOnly = (int) objects.stream().filter(floorView::visible).count();
                        memory.context(sideBlocks, Map.of());
                        var sideView = new TerrainShapes(memory, ignored -> true)
                                .view(Set.of(cell), Map.of());
                        int sidesOnly = (int) objects.stream().filter(sideView::visible).count();
                        memory.context(blocks, Map.of());
                        var view = new TerrainShapes(memory, ignored -> true)
                                .view(Set.of(cell), Map.of());
                        int current = (int) objects.stream().filter(view::visible).count();
                        List<OrientedBox> grass = blocks.keySet().stream().map(pos -> OrientedBox.axisAligned(
                                pos.getX() - cell.getX(), pos.getY() - cell.getY(), pos.getZ() - cell.getZ(),
                                pos.getX() - cell.getX() + 1, pos.getY() - cell.getY() + 1,
                                pos.getZ() - cell.getZ() + 1)).toList();
                        List<OrientedBox> floor = grass.stream().filter(box -> box.maxY() <= 0).toList();
                        List<OrientedBox> sides = grass.stream().filter(box -> box.minY() >= 0).toList();
                        List<PieceDepth> measured = new ArrayList<>();
                        for (FieldObject object : objects) {
                            PilePiece piece = object.piece();
                            PilePiece visual = new PilePiece(visibleModels.get(piece.model().id()),
                                    piece.x(), piece.y(), piece.z(), piece.scale(), piece.qx(), piece.qy(), piece.qz(), piece.qw());
                            int dx = object.owner().getX() - cell.getX(), dy = object.owner().getY() - cell.getY(),
                                    dz = object.owner().getZ() - cell.getZ();
                            List<OrientedBox> collision = piece.colliders(dx, dy, dz);
                            List<OrientedBox> visible = visual.colliders(dx, dy, dz);
                            float collisionDepth = requiredDepth(collision, grass);
                            float visualDepth = requiredDepth(visible, grass);
                            assertTrue(visualDepth <= collisionDepth + .00001f, "Visible post must be inside its collider");
                            measured.add(new PieceDepth(piece.model().id(), collisionDepth, visualDepth,
                                    minimumLocalInset(object, cell, grass), requiredDepth(collision, floor),
                                    requiredDepth(collision, sides), requiredDepth(visible, floor), requiredDepth(visible, sides)));
                        }
                        Scene scene = new Scene(cell, seed, pattern, opening, current, floorOnly, sidesOnly, List.copyOf(measured));
                        scenes.add(scene);
                        if (current == 0) System.out.printf(
                                "FENCE DEPTH empty cell=%s seed=%d pattern=%s orientation=%s objects=%d first_collision=%.6f all_collision=%.6f first_visual=%.6f all_visual=%.6f pieces=%s%n",
                                cell, seed, pattern, opening, objects.size(), first(scene, false), all(scene, false),
                                first(scene, true), all(scene, true), measured);
                    }
                }
        }
        List<Scene> empty = scenes.stream().filter(scene -> scene.current == 0).toList();
        assertFalse(empty.isEmpty(), "Three full grass neighbors must reproduce the reported blank rendering");
        JsonObject report = new JsonObject();
        report.addProperty("scope", "Test-only three-side and corner/diagonal grass neighbors; no production or world changes");
        report.addProperty("measurement", "Per-contact SAT minimum translation depth, world-block units; maximum across contacts");
        report.addProperty("visual_source", "Minecraft 1.20.1 blockstate/model JSON elements, not collider-derived meshes");
        report.addProperty("cases", scenes.size());
        report.addProperty("currently_empty", empty.size());
        report.addProperty("empty_with_floor_only", scenes.stream().filter(scene -> scene.floorOnlyCurrent == 0).count());
        report.addProperty("empty_with_sides_only", scenes.stream().filter(scene -> scene.sidesOnlyCurrent == 0).count());
        report.add("scenes", new Gson().toJsonTree(scenes));
        JsonArray sweep = new JsonArray();
        for (float tolerance : TOLERANCES) {
            int colliderCases = 0, visualCases = 0, colliderPieces = 0, visualPieces = 0, localInsetCases = 0;
            int allCollider = 0, allVisual = 0;
            for (Scene scene : empty) {
                int c = 0, v = 0, local = 0;
                for (PieceDepth piece : scene.pieces) {
                    if (piece.collision <= tolerance + .000001f) c++;
                    if (piece.visible <= tolerance + .000001f) v++;
                    if (piece.localInset <= tolerance + .000001f) local++;
                }
                if (c > 0) colliderCases++;
                if (v > 0) visualCases++;
                if (local > 0) localInsetCases++;
                if (c == scene.pieces.size()) allCollider++;
                if (v == scene.pieces.size()) allVisual++;
                colliderPieces += c; visualPieces += v;
            }
            JsonObject row = new JsonObject();
            row.addProperty("tolerance", tolerance);
            row.addProperty("collision_nonempty", colliderCases);
            row.addProperty("visual_nonempty", visualCases);
            row.addProperty("collision_all_restored", allCollider);
            row.addProperty("visual_all_restored", allVisual);
            row.addProperty("collision_pieces", colliderPieces);
            row.addProperty("visual_pieces", visualPieces);
            row.addProperty("existing_obb_inset_nonempty", localInsetCases);
            sweep.add(row);
            System.out.printf("FENCE TOLERANCE value=%.6f empty_cases=%d collision_nonempty=%d visual_nonempty=%d collision_all=%d visual_all=%d collision_pieces=%d visual_pieces=%d existing_inset_nonempty=%d%n",
                    tolerance, empty.size(), colliderCases, visualCases, allCollider, allVisual,
                    colliderPieces, visualPieces, localInsetCases);
        }
        report.add("sweep", sweep);
        report.add("thresholds", thresholds(empty));
        JsonObject patterns = new JsonObject();
        for (String pattern : new String[]{"three_sides", "corner"}) {
            List<Scene> selected = empty.stream().filter(scene -> scene.pattern.equals(pattern)).toList();
            JsonObject summary = new JsonObject();
            summary.addProperty("cases", scenes.stream().filter(scene -> scene.pattern.equals(pattern)).count());
            summary.addProperty("currently_empty", selected.size());
            summary.add("thresholds", thresholds(selected));
            JsonArray rows = new JsonArray();
            for (float tolerance : TOLERANCES) {
                JsonObject row = new JsonObject();
                long collision = selected.stream().filter(scene -> first(scene, false) <= tolerance + .000001f).count();
                long visible = selected.stream().filter(scene -> first(scene, true) <= tolerance + .000001f).count();
                long allVisible = selected.stream().filter(scene -> all(scene, true) <= tolerance + .000001f).count();
                row.addProperty("tolerance", tolerance);
                row.addProperty("collision_nonempty", collision);
                row.addProperty("visual_nonempty", visible);
                row.addProperty("visual_all_restored", allVisible);
                rows.add(row);
                System.out.printf("FENCE PATTERN pattern=%s value=%.6f empty_cases=%d collision_nonempty=%d visual_nonempty=%d visual_all=%d%n",
                        pattern, tolerance, selected.size(), collision, visible, allVisible);
            }
            summary.add("sweep", rows);
            patterns.add(pattern, summary);
        }
        report.add("patterns", patterns);
        Path output = Path.of("build/verification/fence-contact-tolerance-20261114/report.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        assertArrayEquals(saved, Files.readAllBytes(SAVE));
        assertArrayEquals(cached, Files.readAllBytes(CACHE));
        assertEquals(0, memory.chunkRequests());
        System.out.printf("FENCE DEPTH TOTAL cases=%d currently_empty=%d report=%s%n", scenes.size(), empty.size(), output);
    }

    private static JsonObject thresholds(List<Scene> scenes) {
        JsonObject result = new JsonObject();
        for (boolean visual : new boolean[]{false, true})
            for (boolean complete : new boolean[]{false, true}) {
                double[] values = scenes.stream().mapToDouble(scene -> complete ? all(scene, visual) : first(scene, visual))
                        .sorted().toArray();
                JsonObject row = new JsonObject();
                row.addProperty("min", values[0]);
                row.addProperty("median", values[values.length / 2]);
                row.addProperty("max", values[values.length - 1]);
                String label = (visual ? "visual_" : "collision_") + (complete ? "all" : "first");
                result.add(label, row);
                System.out.printf("FENCE DEPTH THRESHOLD mode=%s min=%.6f median=%.6f max=%.6f%n",
                        label, values[0], values[values.length / 2], values[values.length - 1]);
            }
        return result;
    }

    private static float first(Scene scene, boolean visual) {
        return (float) scene.pieces.stream().mapToDouble(piece -> visual ? piece.visible : piece.collision).min().orElseThrow();
    }
    private static float all(Scene scene, boolean visual) {
        return (float) scene.pieces.stream().mapToDouble(piece -> visual ? piece.visible : piece.collision).max().orElseThrow();
    }

    private static float minimumLocalInset(FieldObject object, BlockPos origin, List<OrientedBox> grass) {
        var localCenter = object.piece().model().collisionCenter();
        Vector3f center = object.piece().transformPoint(localCenter.x, localCenter.y, localCenter.z)
                .add(object.owner().getX() - origin.getX(), object.owner().getY() - origin.getY(),
                        object.owner().getZ() - origin.getZ());
        if (grass.stream().anyMatch(box -> box.contains(center.x, center.y, center.z))) return Float.POSITIVE_INFINITY;
        List<OrientedBox> boxes = object.piece().colliders(object.owner().getX() - origin.getX(),
                object.owner().getY() - origin.getY(), object.owner().getZ() - origin.getZ());
        if (!overlapsAtInset(boxes, grass, 0)) return 0;
        float low = 0, high = 1;
        for (int step = 0; step < 22; step++) {
            float middle = (low + high) * .5f;
            if (overlapsAtInset(boxes, grass, middle)) low = middle; else high = middle;
        }
        return high;
    }
    private static boolean overlapsAtInset(List<OrientedBox> boxes, List<OrientedBox> grass, float inset) {
        for (OrientedBox box : boxes) for (OrientedBox block : grass)
            if (box.intersects(block, inset)) return true;
        return false;
    }

    private static float requiredDepth(List<OrientedBox> boxes, List<OrientedBox> obstacles) {
        float result = 0;
        for (OrientedBox box : boxes) for (OrientedBox obstacle : obstacles)
            result = Math.max(result, penetration(box, obstacle));
        return result;
    }

    private static float penetration(OrientedBox a, OrientedBox b) {
        if (a.maxX() <= b.minX() || a.minX() >= b.maxX() || a.maxY() <= b.minY() || a.minY() >= b.maxY()
                || a.maxZ() <= b.minZ() || a.minZ() >= b.maxZ()) return 0;
        List<Vector3f> axes = new ArrayList<>();
        Vector3f[] first = axes(a), second = axes(b);
        axes.addAll(Arrays.asList(first)); axes.addAll(Arrays.asList(second));
        for (Vector3f x : first) for (Vector3f y : second) {
            Vector3f cross = new Vector3f(x).cross(y);
            if (cross.lengthSquared() > 1e-10f) axes.add(cross.normalize());
        }
        Vector3f zero = new Vector3f();
        float result = Float.POSITIVE_INFINITY;
        for (Vector3f axis : axes) {
            Vector3f reverse = new Vector3f(axis).negate();
            float minA = a.minimumProjection(axis, zero), maxA = -a.minimumProjection(reverse, zero);
            float minB = b.minimumProjection(axis, zero), maxB = -b.minimumProjection(reverse, zero);
            float depth = Math.min(maxA - minB, maxB - minA);
            if (depth <= 0) return 0;
            result = Math.min(result, depth);
        }
        return result;
    }
    private static Vector3f[] axes(OrientedBox box) {
        Vector3f center = box.point(0, 0, 0);
        return new Vector3f[]{box.point(1, 0, 0).sub(center).normalize(),
                box.point(0, 1, 0).sub(center).normalize(), box.point(0, 0, 1).sub(center).normalize()};
    }

    @Test void penetrationUsesWorldDistanceIncludingRotatedAndContainedBoxes() {
        OrientedBox cube = OrientedBox.axisAligned(0, 0, 0, 1, 1, 1);
        assertEquals(1f / 32, penetration(OrientedBox.axisAligned(1 - 1f / 32, .25f, .25f, 1.25f, .75f, .75f), cube), 1e-6f);
        assertEquals(.6f, penetration(OrientedBox.axisAligned(.4f, .4f, .4f, .6f, .6f, .6f), cube), 1e-6f);
        var rotated = new OrientedBox(new Vector3f(1.1f, .5f, .5f), new Vector3f(.2f),
                new org.joml.Quaternionf().rotationY((float) Math.PI / 4));
        assertEquals((float) Math.sqrt(.08) - .1f, penetration(rotated, cube), .000001f);
        assertEquals(0, penetration(OrientedBox.axisAligned(1, 0, 0, 2, 1, 1), cube));
    }

    private static PileModel visualModel(PileModel model) throws Exception {
        List<CollisionBox> boxes = new ArrayList<>();
        for (ModelPart part : model.parts()) {
            BlockState state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), part.blockState(), false).blockState();
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            JsonObject blockstate = resource("assets/" + id.getNamespace() + "/blockstates/" + id.getPath() + ".json");
            for (JsonElement entry : blockstate.getAsJsonArray("multipart")) {
                JsonObject variant = entry.getAsJsonObject();
                if (variant.has("when") && !matches(variant.getAsJsonObject("when"), state)) continue;
                JsonObject apply = variant.getAsJsonObject("apply");
                assertFalse(apply.has("x") || apply.has("y"), "Do not omit model rotation in this fixture");
                for (JsonElement element : elements(apply.get("model").getAsString())) {
                    JsonObject shape = element.getAsJsonObject();
                    assertFalse(shape.has("rotation"), "Do not approximate rotated model elements");
                    JsonArray from = shape.getAsJsonArray("from"), to = shape.getAsJsonArray("to");
                    boxes.add(new CollisionBox(from.get(0).getAsFloat() / 16 + part.x(),
                            from.get(1).getAsFloat() / 16 + part.y(), from.get(2).getAsFloat() / 16 + part.z(),
                            to.get(0).getAsFloat() / 16 + part.x(), to.get(1).getAsFloat() / 16 + part.y(),
                            to.get(2).getAsFloat() / 16 + part.z()));
                }
            }
        }
        assertFalse(boxes.isEmpty());
        return new PileModel(model.id(), model.parts(), model.sizeX(), model.sizeY(), model.sizeZ(), model.weight(),
                boxes, model.pivotX(), model.pivotY(), model.pivotZ());
    }
    private static boolean matches(JsonObject condition, BlockState state) {
        for (var entry : condition.entrySet()) {
            if (entry.getKey().equals("OR"))
                return entry.getValue().getAsJsonArray().asList().stream().anyMatch(value -> matches(value.getAsJsonObject(), state));
            var property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            assertNotNull(property);
            if (Arrays.stream(entry.getValue().getAsString().split("\\|"))
                    .noneMatch(value -> property.getValue(value).map(expected -> state.getValue(property).equals(expected)).orElse(false)))
                return false;
        }
        return true;
    }
    private static JsonArray elements(String model) throws IOException {
        Set<String> seen = new HashSet<>();
        while (seen.add(model)) {
            ResourceLocation id = new ResourceLocation(model);
            JsonObject value = resource("assets/" + id.getNamespace() + "/models/" + id.getPath() + ".json");
            if (value.has("elements")) return value.getAsJsonArray("elements");
            model = value.get("parent").getAsString();
        }
        throw new IOException("Cyclic model parent");
    }
    private static JsonObject resource(String path) throws IOException {
        try (InputStream input = FencePenetrationDiagnosisTest.class.getResourceAsStream("/" + path)) {
            if (input != null)
                return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Path client = Path.of("D:/Library/.gradle/caches/forge_gradle/minecraft_repo/versions/1.20.1/client.jar");
        try (ZipFile zip = new ZipFile(client.toFile())) {
            var entry = zip.getEntry(path);
            assertNotNull(entry, "Missing vanilla model resource: " + path);
            try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }
}
