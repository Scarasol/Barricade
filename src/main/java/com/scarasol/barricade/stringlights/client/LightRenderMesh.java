package com.scarasol.barricade.stringlights.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.scarasol.barricade.stringlights.LightCable;
import com.scarasol.barricade.stringlights.LightCurve;
import com.scarasol.barricade.stringlights.LightView;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

final class LightRenderMesh {
    static final RenderType MATERIAL = RenderType.entityCutout(
            new ResourceLocation("barricade", "textures/block/string_light_palette.png"));
    private static final BufferBuilder BUILDER = new BufferBuilder(2048);
    private static final double RADIUS = LightCurve.CABLE_WIDTH / 2;
    private final ClientLevel level;
    private final ChunkPos chunk;
    private final double x, z;

    private LightRenderMesh(ClientLevel level, ChunkPos chunk) {
        this.level = level;
        this.chunk = chunk;
        x = chunk.getMinBlockX();
        z = chunk.getMinBlockZ();
    }

    static VertexBuffer cable(ClientLevel level, ChunkPos pos, LightView view) {
        var mesh = new LightRenderMesh(level, pos);
        BUILDER.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        for (var span : view.spans().values()) mesh.cable(span.cable());
        return upload();
    }

    static VertexBuffer fixtures(ClientLevel level, ChunkPos pos, LightView view) {
        var mesh = new LightRenderMesh(level, pos);
        BUILDER.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        for (var node : view.nodes().values()) mesh.hanger(node);
        return upload();
    }

    static VertexBuffer bulbs(ClientLevel level, ChunkPos pos, LightView view) {
        var mesh = new LightRenderMesh(level, pos);
        BUILDER.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        for (var span : view.spans().values()) {
            for (int i = 0; i < span.curve().bulbs().size(); i++) {
                var bulb = span.curve().bulbs().get(i);
                if (pos.toLong() == ChunkPos.asLong(bulb.cell())) mesh.bulb(bulb, span.broken(i), span.lit(i), i);
            }
        }
        return upload();
    }

    private void cable(LightCable cable) {
        LightCurve curve = cable.curve();
        Vec3 chord = curve.end().subtract(curve.start());
        Vec3 across = chord.x == 0 && chord.z == 0 ? new Vec3(1, 0, 0) : new Vec3(chord.z, 0, -chord.x).normalize();
        for (var piece : curve.pieces()) {
            if (chunk.toLong() != ChunkPos.asLong(piece.cell())) continue;
            int light = ambient(piece.cell());
            cable.walk(piece, (from, to, a, b) -> {
                Vec3[] past = ring(cable, from, a, across);
                Vec3[] next = ring(cable, to, b, across);
                for (int side = 0; side < 4; side++) {
                    int j = (side + 1) % 4;
                    quad(past[j], past[side], next[side], next[j], side == 1 ? 1 : 0, light, 255);
                }
            });
        }
    }

    private static Vec3[] ring(LightCable cable, double t, Vec3 center, Vec3 across) {
        var tangent = cable.point(Math.min(1, t + 1e-4)).subtract(cable.point(Math.max(0, t - 1e-4))).scale(1e4).normalize();
        Vec3 side = across.subtract(tangent.scale(across.dot(tangent))).normalize();
        if (side.lengthSqr() == 0) side = new Vec3(-across.z, 0, across.x);
        Vec3 right = side.scale(RADIUS), up = side.cross(tangent).normalize().scale(RADIUS);
        return new Vec3[] {center.add(right).add(up), center.subtract(right).add(up),
                center.subtract(right).subtract(up), center.add(right).subtract(up)};
    }

    private void hanger(LightView.Node node) {
        Vec3 p = node.anchor();
        BlockPos cell = BlockPos.containing(p);
        if (ChunkPos.asLong(cell) != chunk.toLong()) return;
        if (node.box() != null) return;
        double half = 1.0 / 16;
        AABB eye = new AABB(p, p).inflate(half);
        box(eye, 2, ambient(cell), node.supported() ? 255 : 202);
    }

    private void bulb(LightCurve.Bulb bulb, boolean broken, boolean lit, int index) {
        int ambient = ambient(bulb.cell());
        int color = 235 + (index % 3) * 10;
        box(bulb.socket(), 2, ambient, color);
        if (broken) return;
        box(bulb.glass(), lit ? 4 : 3, lit ? LightTexture.FULL_BRIGHT : ambient, color);
        box(bulb.tip(), lit ? 5 : 3, lit ? LightTexture.FULL_BRIGHT : ambient, 255);
    }

    private int ambient(BlockPos pos) {
        return level.hasChunkAt(pos) ? LevelRenderer.getLightColor(level, pos) : 0;
    }

    private void box(AABB b, int tile, int light, int color) {
        Vec3 a = new Vec3(b.minX, b.minY, b.minZ), c = new Vec3(b.maxX, b.minY, b.minZ);
        Vec3 d = new Vec3(b.maxX, b.maxY, b.minZ), e = new Vec3(b.minX, b.maxY, b.minZ);
        Vec3 f = new Vec3(b.minX, b.minY, b.maxZ), g = new Vec3(b.maxX, b.minY, b.maxZ);
        Vec3 h = new Vec3(b.maxX, b.maxY, b.maxZ), i = new Vec3(b.minX, b.maxY, b.maxZ);
        quad(c, a, e, d, tile, light, color);
        quad(f, g, h, i, tile, light, color);
        quad(a, f, i, e, tile, light, color);
        quad(g, c, d, h, tile, light, color);
        quad(a, c, g, f, tile, light, color);
        quad(d, e, i, h, tile, light, color);
    }

    private void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, int tile, int light, int color) {
        var normal = b.subtract(a).cross(c.subtract(a)).normalize();
        float u = (tile * 2) / 16f;
        if (tile < 2) {
            // A solid palette sample avoids compressing a pattern onto the half-pixel cable.
            vertex(a, u, 0, normal, light, color);
            vertex(b, u, 0, normal, light, color);
            vertex(c, u, 0, normal, light, color);
            vertex(d, u, 0, normal, light, color);
            return;
        }
        float end = (tile * 2 + 2) / 16f;
        vertex(a, u, 2 / 16f, normal, light, color);
        vertex(b, end, 2 / 16f, normal, light, color);
        vertex(c, end, 0, normal, light, color);
        vertex(d, u, 0, normal, light, color);
    }

    private void vertex(Vec3 point, float u, float v, Vec3 n, int light, int color) {
        BUILDER.vertex(point.x - x, point.y, point.z - z).color(color, color, color, 255).uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal((float) n.x, (float) n.y, (float) n.z).endVertex();
    }

    private static VertexBuffer upload() {
        var data = BUILDER.endOrDiscardIfEmpty();
        if (data == null) return null;
        var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(data);
        VertexBuffer.unbind();
        return buffer;
    }

    static void draw(VertexBuffer mesh, Matrix4f modelView, Matrix4f projection) {
        if (mesh == null) return;
        mesh.bind();
        mesh.drawWithShader(modelView, projection, RenderSystem.getShader());
    }
}
