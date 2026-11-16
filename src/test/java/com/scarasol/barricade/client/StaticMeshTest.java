package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class StaticMeshTest {
    @Test
    void playbackRetainsMaterialAttributesAndAppliesObjectTransformAndCurrentLight() {
        var vertex = new StaticMesh.Vertex(1, 0, 0, 12, 34, 56, 200, 0.25f, 0.75f,
                123 | 456 << 16, 224, 1, 0, 0);
        PoseStack pose = new PoseStack();
        pose.translate(4, 5, 6);
        pose.mulPose(new Quaternionf().rotationY((float) (Math.PI / 2)));
        pose.scale(2, 2, 2);
        Recorder out = new Recorder();
        vertex.write(out, pose.last(), 32 | 240 << 16);
        assertEquals(1, out.count);
        assertEquals(4, out.x, 0.00001);
        assertEquals(5, out.y, 0.00001);
        assertEquals(4, out.z, 0.00001);
        assertEquals(-1, out.nz, 0.00001);
        assertEquals(0, out.nx, 0.00001);
        assertArrayEquals(new int[]{12, 34, 56, 200}, out.color);
        assertEquals(0.25f, out.u);
        assertEquals(0.75f, out.v);
        assertEquals(123 | 456 << 16, out.overlay);
        assertEquals(224 | 240 << 16, out.light);
        vertex.write(out, pose.last(), 0);
        assertEquals(224, out.light, "Night playback preserves explicit emission without retaining daylight");
    }

    private static final class Recorder implements VertexConsumer {
        double x, y, z;
        float u, v, nx, ny, nz;
        int count, overlay, light;
        int[] color;
        @Override public VertexConsumer vertex(double x, double y, double z) {
            this.x = x; this.y = y; this.z = z; return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) {
            color = new int[]{r, g, b, a}; return this;
        }
        @Override public VertexConsumer uv(float u, float v) { this.u = u; this.v = v; return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { overlay = u | v << 16; return this; }
        @Override public VertexConsumer uv2(int u, int v) { light = u | v << 16; return this; }
        @Override public VertexConsumer normal(float x, float y, float z) {
            nx = x; ny = y; nz = z; return this;
        }
        @Override public void endVertex() { count++; }
        @Override public void defaultColor(int r, int g, int b, int a) { }
        @Override public void unsetDefaultColor() { }
    }
}
