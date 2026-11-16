package com.scarasol.barricade.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.pile.CollisionBox;
import com.scarasol.barricade.pile.ModelPart;
import com.scarasol.barricade.pile.PileModel;
import com.scarasol.barricade.sampling.MemoryLevel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.RenderTypeHelper;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Vector3f;

public final class MaterialMeshes {
    private static final int MAX_DEFINITIONS = 512;
    private static final int MAX_VERTICES = 65_536;
    private static final int MAX_CACHED_VERTICES = 1_000_000;
    private static final Map<List<ModelPart>, StaticMesh> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static final ArrayDeque<StaticMesh> RETIRED = new ArrayDeque<>();
    private static MemoryLevel memory;
    private static Level source;
    private static int generation;
    private static int cachedVertices;

    private MaterialMeshes() { }

    static int generation() { return generation; }

    static StaticMesh get(PileModel model, Level world) {
        return get(model.parts(), world);
    }

    public static CollisionBox bounds(List<ModelPart> parts) {
        StaticMesh mesh = get(parts, Minecraft.getInstance().level);
        if (mesh == null) throw new UnsupportedOperationException("Material has no captured appearance");
        return bounds(mesh);
    }

    static CollisionBox bounds(StaticMesh mesh) {
        AABB box = mesh.bounds;
        return new CollisionBox((float) box.minX, (float) box.minY, (float) box.minZ,
                (float) box.maxX, (float) box.maxY, (float) box.maxZ);
    }

    private static StaticMesh get(List<ModelPart> key, Level world) {
        if (source != world) {
            clear();
            source = world;
            memory = new MemoryLevel(world.registryAccess(), world.dimensionTypeRegistration(),
                    world.enabledFeatures(), world.getRecipeManager(), true);
        }
        if (CACHE.containsKey(key)) return CACHE.get(key);
        StaticMesh mesh = null;
        long start = System.nanoTime();
        try {
            mesh = capture(key);
            Barricade.LOGGER.debug("Captured static material {}: {} batches, {} ms", key.get(0).blockState(),
                    mesh.batches.size(), (System.nanoTime() - start) / 1_000_000.0);
        } catch (RuntimeException | LinkageError exception) {
            Barricade.LOGGER.warn("Cannot capture barricade appearance {}: {}", key.get(0).blockState(), exception.toString());
        } finally {
            memory.reset();
        }
        CACHE.put(key, mesh);
        if (mesh != null) cachedVertices += vertexCount(mesh);
        while (CACHE.size() > MAX_DEFINITIONS || cachedVertices > MAX_CACHED_VERTICES) {
            var oldest = CACHE.entrySet().iterator();
            StaticMesh evicted = oldest.next().getValue();
            if (evicted != null) {
                cachedVertices -= vertexCount(evicted);
                RETIRED.add(evicted);
            }
            oldest.remove();
        }
        return mesh;
    }

    static void clear() {
        CACHE.values().stream().filter(java.util.Objects::nonNull).forEach(RETIRED::add);
        CACHE.clear();
        cachedVertices = 0;
        if (memory != null) memory.reset();
        memory = null;
        source = null;
        generation++;
        BarricadeRenderer.invalidateBounds();
    }

    private static int vertexCount(StaticMesh mesh) {
        return mesh.batches.stream().mapToInt(batch -> batch.vertices.size()).sum();
    }

    // Release only after buffers referring to old meshes have been submitted.
    static void frameStarted() {
        while (!RETIRED.isEmpty()) RETIRED.removeFirst().close();
    }

    private static StaticMesh capture(List<ModelPart> parts) {
        Minecraft minecraft = Minecraft.getInstance();
        return capture(parts, memory, minecraft.getBlockRenderer(), minecraft.getBlockEntityRenderDispatcher(),
                type -> RenderTypeHelper.getEntityRenderType(type, false));
    }

    static StaticMesh capture(List<ModelPart> parts, MemoryLevel memory, BlockRenderDispatcher dispatcher,
                              BlockEntityRenderDispatcher entityDispatcher,
                              UnaryOperator<RenderType> entityRenderType) {
        BlockPos origin = new BlockPos(0, 8, 0);
        for (ModelPart part : parts) {
            BlockPos pos = origin.offset(Math.round(part.x()), Math.round(part.y()), Math.round(part.z()));
            try {
                var state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),
                        part.blockState(), false).blockState();
                memory.seed(pos, state);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
                throw new IllegalArgumentException("Missing state " + part.blockState(), exception);
            }
        }
        for (ModelPart part : parts) {
            BlockPos pos = origin.offset(Math.round(part.x()), Math.round(part.y()), Math.round(part.z()));
            BlockEntity entity = memory.getBlockEntity(pos);
            if (entity != null && !part.blockEntityData().isEmpty()) {
                CompoundTag data = part.blockEntityData();
                data.putInt("x", pos.getX());
                data.putInt("y", pos.getY());
                data.putInt("z", pos.getZ());
                entity.load(data);
            }
        }
        Capture capture = new Capture();
        for (ModelPart part : parts) {
            BlockPos pos = origin.offset(Math.round(part.x()), Math.round(part.y()), Math.round(part.z()));
            var state = memory.getBlockState(pos);
            BlockEntity entity = memory.getBlockEntity(pos);
            PoseStack pose = new PoseStack();
            pose.translate(part.x(), part.y(), part.z());
            capture.beginPart(new Vector3f(part.x() + 0.5f, part.y() + 0.5f, part.z() + 0.5f));
            if (state.getRenderShape() == RenderShape.MODEL) {
                var model = dispatcher.getBlockModel(state);
                ModelData data = model.getModelData(memory, pos, state,
                        entity == null ? ModelData.EMPTY : entity.getModelData());
                pose.pushPose();
                var offset = state.getOffset(memory, pos);
                pose.translate(offset.x, offset.y, offset.z);
                for (RenderType type : model.getRenderTypes(state, RandomSource.create(42), data)) {
                    dispatcher.getModelRenderer().tesselateWithoutAO(memory, model, state, pos, pose,
                            capture.getBuffer(entityRenderType.apply(type)), true,
                            RandomSource.create(42), state.getSeed(pos), OverlayTexture.NO_OVERLAY, data, type);
                }
                pose.popPose();
            }
            if (entity != null) {
                var renderer = entityDispatcher.getRenderer(entity);
                if (renderer != null) {
                    // Auxiliary effects and non-owning assembly parts may legitimately emit nothing.
                    renderer.render(entity, 0, pose, capture, 0, OverlayTexture.NO_OVERLAY);
                } else if (state.getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED) {
                    throw new UnsupportedOperationException("No registered block entity renderer");
                }
            }
            capture.endPart();
        }
        if (capture.count == 0) throw new UnsupportedOperationException("Empty appearance");
        return new StaticMesh(capture.batches, capture.bounds);
    }

    private static final class Capture implements MultiBufferSource {
        private final List<StaticMesh.Batch> batches = new ArrayList<>();
        private final Map<RenderType, Recorder> consumers = new LinkedHashMap<>();
        private Vector3f center;
        private AABB bounds;
        private int count;

        void beginPart(Vector3f center) { this.center = center; }
        void endPart() {
            consumers.forEach((type, recorder) -> {
                if (!recorder.vertices.isEmpty()) {
                    if (type.mode() == com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS
                            && recorder.vertices.size() % 4 != 0) {
                        throw new UnsupportedOperationException("Incomplete quad output");
                    }
                    batches.add(new StaticMesh.Batch(type, recorder.vertices, center));
                }
            });
            consumers.clear();
        }

        @Override public VertexConsumer getBuffer(RenderType type) {
            return consumers.computeIfAbsent(type, ignored -> new Recorder());
        }

        private final class Recorder implements VertexConsumer {
            private final List<StaticMesh.Vertex> vertices = new ArrayList<>();
            private float x, y, z, u, v, nx, ny, nz;
            private int red = 255, green = 255, blue = 255, alpha = 255;
            private int overlay = OverlayTexture.NO_OVERLAY, light;
            private boolean defaultColor;

            @Override public VertexConsumer vertex(double x, double y, double z) {
                this.x = (float) x; this.y = (float) y; this.z = (float) z; return this;
            }
            @Override public VertexConsumer color(int r, int g, int b, int a) {
                if (!defaultColor) { red = r; green = g; blue = b; alpha = a; }
                return this;
            }
            @Override public VertexConsumer uv(float u, float v) { this.u = u; this.v = v; return this; }
            @Override public VertexConsumer overlayCoords(int u, int v) { overlay = u | v << 16; return this; }
            @Override public VertexConsumer uv2(int u, int v) { light = u | v << 16; return this; }
            @Override public VertexConsumer normal(float x, float y, float z) {
                nx = x; ny = y; nz = z; return this;
            }
            @Override public void endVertex() {
                if (++count > MAX_VERTICES || !Float.isFinite(x + y + z + u + v + nx + ny + nz)
                        || Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) > 16) {
                    throw new UnsupportedOperationException("Appearance exceeds vertex/coordinate budget");
                }
                vertices.add(new StaticMesh.Vertex(x, y, z, red, green, blue, alpha,
                        u, v, overlay, light, nx, ny, nz));
                AABB point = new AABB(x, y, z, x, y, z);
                bounds = bounds == null ? point : bounds.minmax(point);
            }
            @Override public void defaultColor(int r, int g, int b, int a) {
                red = r; green = g; blue = b; alpha = a; defaultColor = true;
            }
            @Override public void unsetDefaultColor() { defaultColor = false; }
        }
    }
}
