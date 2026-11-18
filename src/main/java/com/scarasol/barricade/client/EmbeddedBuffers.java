package com.scarasol.barricade.client;

import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.renderers.IAcceleratedRenderer;
import com.github.argon4w.acceleratedrendering.core.buffers.memory.VertexLayout;
import com.github.argon4w.acceleratedrendering.core.meshes.ServerMesh;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** One native BER evaluation, with reusable stage queues and shared immutable bone geometry. */
final class EmbeddedBuffers implements MultiBufferSource {
    private static final Map<RenderType, Map<VertexLayout, GeometryConsumer>> GEOMETRY = new IdentityHashMap<>();
    private final Map<RenderType, Stream> streams = new IdentityHashMap<>();
    private final List<Draw> draws = new ArrayList<>();
    private final Map<BlockPos, Integer> lights = new HashMap<>();
    private final Matrix4f inverse = new Matrix4f();
    private final Matrix3f inverseNormal = new Matrix3f();
    private final Vector3f point = new Vector3f(), normal = new Vector3f();
    private final PoseStack scratch = new PoseStack();
    private BlockAndTintGetter level;
    private Predicate<BlockPos> loaded;
    private BlockPos owner;
    private MultiBufferSource source;
    private int count;

    void begin(BlockAndTintGetter level, Predicate<BlockPos> loaded, BlockPos owner,
               PoseStack.Pose nativePose, MultiBufferSource source) {
        reset();
        this.level = level;
        this.loaded = loaded;
        this.owner = owner;
        this.source = source;
        nativePose.pose().invert(inverse);
        nativePose.normal().invert(inverseNormal);
    }

    void reset() {
        count = 0;
        lights.clear();
        streams.values().forEach(Stream::reset);
    }

    static void clearGeometry() {
        GEOMETRY.values().forEach(layouts -> layouts.values().forEach(cache -> {
            cache.meshes.values().forEach(MaterialMeshes::retire);
            cache.meshes.clear();
            cache.target = null;
        }));
        GEOMETRY.clear();
    }

    @Override public VertexConsumer getBuffer(RenderType type) {
        Stream stream = streams.computeIfAbsent(type, Stream::new);
        stream.target = source.getBuffer(type);
        return stream;
    }

    void draw(boolean transparent, MultiBufferSource buffers) {
        for (int index = 0; index < count; index++) {
            Draw draw = draws.get(index);
            if (RenderPasses.transparent(draw.type) != transparent) continue;
            RenderType type = transparent ? RenderPasses.particles(draw.type) : draw.type;
            scratch.last().pose().set(draw.pose);
            scratch.last().normal().set(draw.normal);
            for (StaticMesh.Batch surface : draw.mesh.surfaces()) {
                point.set(surface.lightPosition).mulPosition(draw.pose).mulPosition(inverse);
                normal.set(surface.lightNormal).mul(draw.normal).mul(inverseNormal);
                int light = light(point, normal);
                surface.draw(scratch.last(), ignored -> buffers.getBuffer(type),
                        StaticMesh.mergeLight(draw.light, light), draw.color, draw.overlay);
            }
        }
        for (Stream stream : streams.values()) {
            if (stream.size == 0 || RenderPasses.transparent(stream.type) != transparent) continue;
            RenderType type = transparent ? RenderPasses.particles(stream.type) : stream.type;
            VertexConsumer out = buffers.getBuffer(type);
            int polygon = stream.type.mode() == VertexFormat.Mode.QUADS ? 4
                    : stream.type.mode() == VertexFormat.Mode.TRIANGLES ? 3 : 1;
            for (int start = 0; start < stream.size; start += polygon) {
                int end = Math.min(start + polygon, stream.size);
                point.zero(); normal.zero();
                for (int vertex = start; vertex < end; vertex++) {
                    int offset = vertex * 8;
                    point.add(stream.data[offset], stream.data[offset + 1], stream.data[offset + 2]);
                    normal.add(stream.data[offset + 5], stream.data[offset + 6], stream.data[offset + 7]);
                }
                if (!normal.isFinite()) {
                    if (end - start < 3) continue;
                    int a = start * 8, b = a + 8, c = b + 8;
                    normal.set(stream.data[b] - stream.data[a], stream.data[b + 1] - stream.data[a + 1],
                            stream.data[b + 2] - stream.data[a + 2])
                            .cross(stream.data[c] - stream.data[a], stream.data[c + 1] - stream.data[a + 1],
                                    stream.data[c + 2] - stream.data[a + 2]);
                    if (!normal.isFinite() || normal.lengthSquared() == 0) continue;
                    normal.normalize();
                    for (int vertex = start; vertex < end; vertex++) {
                        int offset = vertex * 8;
                        stream.data[offset + 5] = normal.x;
                        stream.data[offset + 6] = normal.y;
                        stream.data[offset + 7] = normal.z;
                    }
                }
                point.div(end - start).mulPosition(inverse);
                normal.mul(inverseNormal);
                int light = light(point, normal);
                for (int vertex = start; vertex < end; vertex++) {
                    int offset = vertex * 8, attributes = vertex * 3;
                    int color = stream.attributes[attributes];
                    out.vertex(stream.data[offset], stream.data[offset + 1], stream.data[offset + 2])
                            .color(color >>> 16 & 255, color >>> 8 & 255, color & 255, color >>> 24)
                            .uv(stream.data[offset + 3], stream.data[offset + 4])
                            .overlayCoords(stream.attributes[attributes + 1])
                            .uv2(StaticMesh.mergeLight(stream.attributes[attributes + 2], light))
                            .normal(stream.data[offset + 5], stream.data[offset + 6], stream.data[offset + 7]).endVertex();
                }
            }
        }
    }

    private int light(Vector3f point, Vector3f normal) {
        BlockPos pos = SurfaceLight.position(owner, point, normal,
                cell -> loaded.test(cell) && level.getBlockState(cell).isSolidRender(level, cell));
        return lights.computeIfAbsent(pos, cell -> loaded.test(cell) ? LevelRenderer.getLightColor(level, cell) : 0);
    }

    private void add(StaticMesh mesh, RenderType type, Matrix4f pose, Matrix3f normal,
                     int light, int overlay, int color) {
        if (count == draws.size()) draws.add(new Draw());
        Draw draw = draws.get(count);
        draw.mesh = mesh; draw.type = type;
        draw.pose.set(pose); draw.normal.set(normal);
        if (!draw.normal.isFinite()) {
            // Zero-scaled animation bones have no inverse normal matrix, but flat faces can still be visible.
            draw.normal.set(pose).cofactor();
            if (!draw.normal.isFinite() || draw.normal.getScale(this.normal).lengthSquared() == 0) return;
        }
        count++;
        draw.light = light; draw.overlay = overlay; draw.color = color;
    }

    private static final class Draw {
        StaticMesh mesh;
        RenderType type;
        final Matrix4f pose = new Matrix4f();
        final Matrix3f normal = new Matrix3f();
        int light, overlay, color;
    }

    private final class Stream extends ForwardConsumer implements IAcceleratedVertexConsumer {
        final RenderType type;
        float[] data = new float[256 * 8];
        int[] attributes = new int[256 * 3];
        int size, color = -1, overlay, light;
        float x, y, z, u, v, nx, ny, nz;
        boolean defaultColor;
        Stream(RenderType type) { this.type = type; }
        void reset() { size = 0; defaultColor = false; color = -1; }
        @Override public boolean isAccelerated() {
            return target instanceof IAcceleratedVertexConsumer accelerated && accelerated.isAccelerated();
        }
        @Override public <T> void doRender(IAcceleratedRenderer<T> renderer, T context, Matrix4f pose,
                                           Matrix3f normal, int light, int overlay, int color) {
            ((IAcceleratedVertexConsumer) target).doRender((actual, ignored, matrix, normals, l, o, c) -> {
                var accelerated = (IAcceleratedVertexConsumer) actual;
                var cache = GEOMETRY.computeIfAbsent(type, key -> new IdentityHashMap<>())
                        .computeIfAbsent(accelerated.getLayout(), key -> new GeometryConsumer(type));
                cache.target = actual;
                cache.destination = EmbeddedBuffers.this;
                try {
                    renderer.render(cache, context, matrix, normals, l, o, c);
                } finally {
                    cache.destination = null;
                    cache.pending = null;
                }
            }, context, pose, normal, light, overlay, color);
        }
        @Override public VertexConsumer vertex(double x, double y, double z) {
            this.x = (float) x; this.y = (float) y; this.z = (float) z; return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) {
            if (!defaultColor) color = a << 24 | r << 16 | g << 8 | b;
            return this;
        }
        @Override public VertexConsumer uv(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { overlay = u | v << 16; return this; }
        @Override public VertexConsumer uv2(int u, int v) { light = u | v << 16; return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { nx = x; ny = y; nz = z; return this; }
        @Override public void endVertex() {
            if ((size + 1) * 8 > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
                attributes = Arrays.copyOf(attributes, attributes.length * 2);
            }
            int offset = size * 8, attribute = size++ * 3;
            data[offset] = x; data[offset + 1] = y; data[offset + 2] = z;
            data[offset + 3] = u; data[offset + 4] = v;
            data[offset + 5] = nx; data[offset + 6] = ny; data[offset + 7] = nz;
            attributes[attribute] = color; attributes[attribute + 1] = overlay; attributes[attribute + 2] = light;
        }
        @Override public void defaultColor(int r, int g, int b, int a) {
            color = a << 24 | r << 16 | g << 8 | b; defaultColor = true;
        }
        @Override public void unsetDefaultColor() { defaultColor = false; }
    }

    /** Native libraries still own their animation and mesh caches; record local geometry only on a cache miss. */
    private static final class GeometryConsumer extends ForwardConsumer implements IAcceleratedVertexConsumer {
        final RenderType type;
        final Map<Object, StaticMesh> meshes = new IdentityHashMap<>();
        final Matrix4f pose = new Matrix4f();
        final Matrix3f normal = new Matrix3f();
        MaterialMeshes.Geometry pending;
        EmbeddedBuffers destination;
        GeometryConsumer(RenderType type) { this.type = type; }
        private IAcceleratedVertexConsumer accelerated() { return (IAcceleratedVertexConsumer) target; }
        @Override public boolean isAccelerated() { return true; }
        @Override public VertexLayout getLayout() { return accelerated().getLayout(); }
        @Override public RenderType getRenderType() { return type; }
        @Override public int getPolygonSize() { return accelerated().getPolygonSize(); }
        @Override public NativeImage downloadTexture() { return accelerated().downloadTexture(); }
        @Override public void beginTransform(Matrix4f pose, Matrix3f normal) {
            this.pose.set(pose); this.normal.set(normal);
        }
        @Override public void endTransform() { }
        @Override public VertexConsumer decorate(VertexConsumer collector) {
            pending = new MaterialMeshes.Geometry(type);
            VertexConsumer recording = pending.consumer;
            return accelerated().decorate(new ForwardConsumer() {
                { target = collector; }
                @Override public VertexConsumer vertex(double x, double y, double z) {
                    recording.vertex(x, y, z); return super.vertex(x, y, z);
                }
                @Override public VertexConsumer color(int r, int g, int b, int a) {
                    recording.color(r, g, b, a); return super.color(r, g, b, a);
                }
                @Override public VertexConsumer uv(float u, float v) {
                    recording.uv(u, v); return super.uv(u, v);
                }
                @Override public VertexConsumer overlayCoords(int u, int v) {
                    recording.overlayCoords(u, v); return super.overlayCoords(u, v);
                }
                @Override public VertexConsumer uv2(int u, int v) {
                    recording.uv2(u, v); return super.uv2(u, v);
                }
                @Override public VertexConsumer normal(float x, float y, float z) {
                    recording.normal(x, y, z); return super.normal(x, y, z);
                }
                @Override public void endVertex() { recording.endVertex(); super.endVertex(); }
                @Override public void defaultColor(int r, int g, int b, int a) {
                    recording.defaultColor(r, g, b, a); super.defaultColor(r, g, b, a);
                }
                @Override public void unsetDefaultColor() {
                    recording.unsetDefaultColor(); super.unsetDefaultColor();
                }
            });
        }
        @Override public void addServerMesh(ServerMesh mesh, int color, int light, int overlay) {
            submit(mesh, color, light, overlay);
        }
        @Override public void addClientMesh(ByteBuffer bytes, int size, int color, int light, int overlay) {
            submit(bytes, color, light, overlay);
        }
        private void submit(Object key, int color, int light, int overlay) {
            StaticMesh mesh = meshes.get(key);
            if (pending != null) {
                StaticMesh captured = pending.finish();
                pending = null;
                if (mesh == null) { mesh = captured; meshes.put(key, mesh); }
                else captured.close();
            }
            if (mesh == null) throw new IllegalStateException("Native mesh submitted without local geometry");
            destination.add(mesh, type, pose, normal, light, overlay, color);
        }
    }

    private static class ForwardConsumer implements VertexConsumer {
        VertexConsumer target;
        @Override public VertexConsumer vertex(double x, double y, double z) { target.vertex(x, y, z); return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { target.color(r, g, b, a); return this; }
        @Override public VertexConsumer uv(float u, float v) { target.uv(u, v); return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { target.overlayCoords(u, v); return this; }
        @Override public VertexConsumer uv2(int u, int v) { target.uv2(u, v); return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { target.normal(x, y, z); return this; }
        @Override public void endVertex() { target.endVertex(); }
        @Override public void defaultColor(int r, int g, int b, int a) { target.defaultColor(r, g, b, a); }
        @Override public void unsetDefaultColor() { target.unsetDefaultColor(); }
    }
}
