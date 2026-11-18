package com.scarasol.barricade.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

/** Immutable capture output in the object's original coordinates. */
final class StaticMesh implements AutoCloseable {
    record Vertex(float x, float y, float z, int red, int green, int blue, int alpha,
                  float u, float v, int overlay, int light, float nx, float ny, float nz) {
        void write(VertexConsumer out, PoseStack.Pose pose, int worldLight) {
            write(out, pose, worldLight, -1, -1);
        }

        void write(VertexConsumer out, PoseStack.Pose pose, int worldLight, int tint, int worldOverlay) {
            out.vertex(pose.pose(), x, y, z).color(red * (tint >>> 16 & 255) / 255,
                            green * (tint >>> 8 & 255) / 255, blue * (tint & 255) / 255,
                            alpha * (tint >>> 24 & 255) / 255).uv(u, v)
                    .overlayCoords(worldOverlay == -1 ? overlay : worldOverlay).uv2(mergeLight(light, worldLight))
                    .normal(pose.normal(), nx, ny, nz).endVertex();
        }
    }

    interface Acceleration extends AutoCloseable {
        boolean draw(VertexConsumer out, PoseStack.Pose pose, int light);
        default boolean draw(VertexConsumer out, PoseStack.Pose pose, int light, int color, int overlay) {
            return color == -1 && overlay == -1 && draw(out, pose, light);
        }
        @Override void close();
    }

    static final class Batch implements AutoCloseable {
        final RenderType material;
        final List<Vertex> vertices;
        final Vector3f lightPosition;
        final Vector3f lightNormal;
        private List<Batch> surfaces;
        private Acceleration acceleration;
        private boolean attempted;

        Batch(RenderType material, List<Vertex> vertices, Vector3f lightPosition) {
            this(material, vertices, lightPosition, new Vector3f());
        }

        private Batch(RenderType material, List<Vertex> vertices, Vector3f lightPosition, Vector3f lightNormal) {
            this.material = material;
            this.vertices = List.copyOf(vertices);
            this.lightPosition = new Vector3f(lightPosition);
            this.lightNormal = new Vector3f(lightNormal);
        }

        List<Batch> surfaces() {
            if (surfaces == null) surfaces = splitSurfaces();
            return surfaces;
        }

        private List<Batch> splitSurfaces() {
            if (material.mode() != VertexFormat.Mode.QUADS || vertices.size() % 4 != 0) return List.of(this);
            Map<Direction, List<Vertex>> groups = new EnumMap<>(Direction.class);
            for (int start = 0; start < vertices.size(); start += 4) {
                List<Vertex> quad = vertices.subList(start, start + 4);
                Vector3f normal = normal(quad);
                if (normal.lengthSquared() < 0.000001f) return List.of(this);
                Direction side = Direction.getNearest(normal.x, normal.y, normal.z);
                groups.computeIfAbsent(side, ignored -> new ArrayList<>()).addAll(quad);
            }
            List<Batch> result = new ArrayList<>(groups.size());
            for (List<Vertex> group : groups.values()) {
                Vector3f center = new Vector3f();
                group.forEach(vertex -> center.add(vertex.x, vertex.y, vertex.z));
                result.add(new Batch(material, group, center.div(group.size()), normal(group).normalize()));
            }
            return result;
        }

        private static Vector3f normal(List<Vertex> vertices) {
            Vector3f normal = new Vector3f();
            vertices.forEach(vertex -> normal.add(vertex.nx, vertex.ny, vertex.nz));
            if (normal.lengthSquared() < 0.000001f) {
                Vertex a = vertices.get(0), b = vertices.get(1), c = vertices.get(2);
                normal.set(b.x - a.x, b.y - a.y, b.z - a.z)
                        .cross(c.x - a.x, c.y - a.y, c.z - a.z);
            }
            return normal;
        }

        void draw(PoseStack.Pose pose, MultiBufferSource buffers, int light) {
            draw(pose, buffers, light, -1, -1);
        }

        void draw(PoseStack.Pose pose, MultiBufferSource buffers, int light, int color, int overlay) {
            VertexConsumer out = buffers.getBuffer(material);
            if (!attempted) {
                acceleration = AcceleratedRenderingBridge.create(this, out);
                attempted = acceleration != null;
            }
            if (acceleration != null && acceleration.draw(out, pose, light, color, overlay)) return;
            for (Vertex vertex : vertices) vertex.write(out, pose, light, color, overlay);
        }

        @Override public void close() {
            if (acceleration != null) acceleration.close();
            if (surfaces != null) surfaces.stream().filter(batch -> batch != this).forEach(Batch::close);
        }
    }

    final List<Batch> batches;
    final List<Batch> bakedBatches;
    final AABB bounds;
    private List<Batch> surfaces;
    private List<Batch> bakedSurfaces;

    StaticMesh(List<Batch> batches, AABB bounds) {
        this(batches, batches, bounds);
    }

    StaticMesh(List<Batch> batches, List<Batch> bakedBatches, AABB bounds) {
        this.batches = List.copyOf(batches);
        this.bakedBatches = List.copyOf(bakedBatches);
        this.bounds = bounds;
    }

    static int mergeLight(int captured, int world) {
        return Math.max(captured & 65535, world & 65535)
                | Math.max(captured >>> 16 & 65535, world >>> 16 & 65535) << 16;
    }

    List<Batch> surfaces() {
        if (surfaces == null) surfaces = batches.stream().flatMap(batch -> batch.surfaces().stream()).toList();
        return surfaces;
    }

    List<Batch> bakedSurfaces() {
        if (bakedSurfaces == null)
            bakedSurfaces = bakedBatches.stream().flatMap(batch -> batch.surfaces().stream()).toList();
        return bakedSurfaces;
    }

    @Override public void close() {
        batches.forEach(Batch::close);
    }
}
