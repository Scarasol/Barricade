package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;

import com.github.argon4w.acceleratedrendering.core.backends.buffers.IServerBuffer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.renderers.IAcceleratedRenderer;
import com.github.argon4w.acceleratedrendering.core.buffers.memory.VertexLayout;
import com.github.argon4w.acceleratedrendering.core.meshes.ServerMesh;
import com.github.argon4w.acceleratedrendering.core.meshes.collectors.SimpleMeshCollector;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AcceleratedRenderingBridgeTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void preparationDispatchesToTheActualConsumerBeforeReadingItsLayout() {
        var actual = new UnsupportedConsumer() {
            @Override public VertexLayout getLayout() {
                layouts++;
                throw new UnsupportedOperationException("Test consumer has no mesh layout");
            }
        };
        var outer = new DispatchConsumer(actual);
        var acceleration = assertDoesNotThrow(() -> AcceleratedRenderingBridge.create(batch(), outer));
        assertEquals(1, outer.dispatches, "The outer BufferBuilder only supports doRender");
        assertEquals(1, actual.layouts, "Mesh preparation must use the callback's consumer");
        assertNotNull(acceleration);
        assertFalse(acceleration.draw(outer, new PoseStack().last(), 0));
        acceleration.close();
    }

    @Test
    void unsupportedAccelerationDefaultsReturnThePlaybackFallback() {
        var acceleration = assertDoesNotThrow(
                () -> AcceleratedRenderingBridge.create(batch(), new UnsupportedConsumer()));
        assertNotNull(acceleration);
        assertFalse(acceleration.draw(new PlainConsumer(), new PoseStack().last(), 0));
        acceleration.close();
    }

    @Test
    void ordinaryOrTemporarilyInactiveConsumersCanBeRetriedLater() {
        assertNull(AcceleratedRenderingBridge.create(batch(), new PlainConsumer()));
        assertNull(AcceleratedRenderingBridge.create(batch(), new UnsupportedConsumer() {
            @Override public boolean isAccelerated() { return false; }
        }));
    }

    @Test
    void cachedMeshDispatchesTransformsLightAndSubmissionOnEveryDraw() {
        var actual = new MeshConsumer();
        var outer = new DispatchConsumer(actual);
        var buffer = new TestBuffer();
        var mesh = new ServerMesh(0, 123, 4, 0, false, buffer);
        var acceleration = new AcceleratedRenderingBridge.CachedMesh(mesh, actual.layout, 224, 123);
        PoseStack pose = new PoseStack();
        pose.translate(4, 5, 6);
        pose.scale(2, 2, 2);

        assertTrue(acceleration.draw(outer, pose.last(), 32 | 240 << 16));
        assertEquals(1, outer.dispatches);
        assertSame(mesh, actual.submitted);
        assertEquals(pose.last().pose(), actual.pose);
        assertEquals(pose.last().normal(), actual.normal);
        assertEquals(224 | 240 << 16, actual.light);
        assertEquals(123, actual.overlay);
        assertEquals(-1, actual.color);
        assertEquals(1, actual.ends);
        assertFalse(actual.transforming);

        assertTrue(acceleration.draw(outer, new PoseStack().last(), 0));
        assertEquals(2, outer.dispatches);
        assertEquals(2, actual.submissions);
        assertSame(mesh, actual.submitted, "Subsequent frames reuse the same mesh");
        assertEquals(224, actual.light);
        acceleration.close();
        acceleration.close();
        assertEquals(1, buffer.deletions);
        assertFalse(acceleration.draw(outer, pose.last(), 0));
    }

    @Test
    void temporaryPipelineChangesFallBackWithoutDiscardingTheMesh() {
        var actual = new MeshConsumer();
        var buffer = new TestBuffer();
        var acceleration = new AcceleratedRenderingBridge.CachedMesh(
                new ServerMesh(0, 124, 4, 0, false, buffer), actual.layout, 0, 0);
        var pose = new PoseStack().last();
        assertFalse(acceleration.draw(new PlainConsumer(), pose, 0));
        assertFalse(acceleration.draw(new UnsupportedConsumer() {
            @Override public boolean isAccelerated() { return false; }
        }, pose, 0));
        var outer = new DispatchConsumer(new MeshConsumer());
        assertFalse(acceleration.draw(outer, pose, 0), "Different layouts use vertex playback");
        outer.actual = actual;
        assertTrue(acceleration.draw(outer, pose, 0), "A matching pipeline can reuse the mesh later");
        acceleration.close();
    }

    @Test
    void failedSubmissionRestoresTransformAndStopsRepeatedExceptions() {
        var actual = new MeshConsumer() {
            @Override public void addServerMesh(ServerMesh mesh, int color, int light, int overlay) {
                throw new UnsupportedOperationException("Test submission failure");
            }
        };
        var outer = new DispatchConsumer(actual);
        var buffer = new TestBuffer();
        var acceleration = new AcceleratedRenderingBridge.CachedMesh(
                new ServerMesh(0, 125, 4, 0, false, buffer), actual.layout, 0, 0);
        var pose = new PoseStack().last();
        assertFalse(assertDoesNotThrow(() -> acceleration.draw(outer, pose, 0)));
        assertFalse(actual.transforming);
        assertEquals(1, actual.ends);
        assertFalse(acceleration.draw(outer, pose, 0));
        assertEquals(1, outer.dispatches);
        assertEquals(0, buffer.deletions, "Buffers are retained until cache retirement");
        acceleration.close();
        assertEquals(1, buffer.deletions);
    }

    @Test
    void missingAccelerationStatusAlsoFallsBack() {
        class DefaultConsumer extends PlainConsumer implements IAcceleratedVertexConsumer { }
        assertNotNull(assertDoesNotThrow(() -> AcceleratedRenderingBridge.create(batch(), new DefaultConsumer())));
    }

    @Test
    void actualCollectorEncodesEveryVertexWithoutChangingItsCoordinates() {
        var layout = new VertexLayout(DefaultVertexFormat.NEW_ENTITY);
        for (int count : new int[]{4, 24, 32, 72, 144}) {
            var collector = new SimpleMeshCollector(layout);
            try {
                var pose = new PoseStack().last();
                for (int index = 0; index < count; index++) {
                    encodedVertex(index).write(collector, pose, 0);
                }
                collector.flush();
                assertEquals(count, collector.getVertexCount());
                try (var data = collector.getBuffer().build()) {
                    assertNotNull(data);
                    var bytes = data.byteBuffer();
                    assertEquals(count * layout.getSize(), bytes.remaining());
                    for (int index = 0; index < count; index++) {
                        var expected = encodedVertex(index);
                        int offset = (int) (index * layout.getSize());
                        assertEquals(expected.x(), bytes.getFloat(offset), 0, "x of vertex " + index);
                        assertEquals(expected.y(), bytes.getFloat(offset + 4), 0, "y of vertex " + index);
                        assertEquals(expected.z(), bytes.getFloat(offset + 8), 0, "z of vertex " + index);
                    }
                }
            } finally {
                collector.getBuffer().close();
            }
        }
    }

    private static StaticMesh.Vertex encodedVertex(int index) {
        return new StaticMesh.Vertex(index % 7 * 0.125f, index % 11 * 0.0625f, index % 13 * -0.03125f,
                32, 64, 128, 255, 0.25f, 0.75f, 0, 0, 0, 1, 0);
    }

    private static StaticMesh.Batch batch() {
        var vertex = new StaticMesh.Vertex(0, 0, 0, 255, 255, 255, 255,
                0, 0, 0, 0, 0, 1, 0);
        return new StaticMesh.Batch(
                RenderType.entitySolid(new ResourceLocation("barricade", "test.png")),
                List.of(vertex, vertex, vertex, vertex), new Vector3f());
    }

    private static class UnsupportedConsumer extends PlainConsumer implements IAcceleratedVertexConsumer {
        int layouts;
        @Override public boolean isAccelerated() { return true; }
    }

    // Mirrors AR's BufferBuilder mixin: mesh operations are only available in doRender.
    private static final class DispatchConsumer extends UnsupportedConsumer {
        VertexConsumer actual;
        int dispatches;

        DispatchConsumer(VertexConsumer actual) { this.actual = actual; }

        @Override public <T> void doRender(IAcceleratedRenderer<T> renderer, T context,
                                          Matrix4f pose, Matrix3f normal, int color, int light, int overlay) {
            dispatches++;
            renderer.render(actual, context, pose, normal, color, light, overlay);
        }
    }

    private static class MeshConsumer extends UnsupportedConsumer {
        final VertexLayout layout = new VertexLayout(DefaultVertexFormat.NEW_ENTITY);
        Matrix4f pose;
        Matrix3f normal;
        ServerMesh submitted;
        int color, light, overlay, ends, submissions;
        boolean transforming;

        @Override public VertexLayout getLayout() { return layout; }
        @Override public void beginTransform(Matrix4f pose, Matrix3f normal) {
            assertFalse(transforming);
            transforming = true;
            this.pose = new Matrix4f(pose);
            this.normal = new Matrix3f(normal);
        }
        @Override public void endTransform() { transforming = false; ends++; }
        @Override public void addServerMesh(ServerMesh mesh, int color, int light, int overlay) {
            assertTrue(transforming);
            submitted = mesh;
            submissions++;
            this.color = color;
            this.light = light;
            this.overlay = overlay;
        }
    }

    private static final class TestBuffer implements IServerBuffer {
        int deletions;
        @Override public int getBufferHandle() { return 0; }
        @Override public void delete() { deletions++; }
        @Override public void bind(int target) { fail("No OpenGL calls in this regression test"); }
        @Override public void bindBase(int target, int index) { fail("No OpenGL calls in this regression test"); }
        @Override public void bindRange(int target, int index, long offset, long size) {
            fail("No OpenGL calls in this regression test");
        }
        @Override public void data(long offset, long size, long address) {
            fail("No OpenGL calls in this regression test");
        }
    }

    private static class PlainConsumer implements VertexConsumer {
        @Override public VertexConsumer vertex(double x, double y, double z) { return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() { }
        @Override public void defaultColor(int r, int g, int b, int a) { }
        @Override public void unsetDefaultColor() { }
    }
}
