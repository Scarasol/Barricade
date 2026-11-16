package com.scarasol.barricade.client;

import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.renderers.IAcceleratedRenderer;
import com.github.argon4w.acceleratedrendering.core.buffers.memory.VertexLayout;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.github.argon4w.acceleratedrendering.core.meshes.MeshBuffer;
import com.github.argon4w.acceleratedrendering.core.meshes.ServerMesh;
import com.github.argon4w.acceleratedrendering.core.meshes.collectors.SimpleMeshCollector;
import com.scarasol.barricade.Barricade;
import java.util.HashSet;
import java.util.Set;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

final class AcceleratedRenderingBridge {
    private static final Set<String> REPORTED = new HashSet<>();
    private static final StaticMesh.Acceleration FALLBACK = new StaticMesh.Acceleration() {
        @Override public boolean draw(VertexConsumer out, PoseStack.Pose pose, int light) { return false; }
        @Override public void close() { }
    };
    private AcceleratedRenderingBridge() {
    }

    static StaticMesh.Acceleration create(StaticMesh.Batch batch, VertexConsumer consumer) {
        if (batch.material.mode() != VertexFormat.Mode.QUADS || batch.vertices.isEmpty()) return FALLBACK;
        int light = batch.vertices.get(0).light(), overlay = batch.vertices.get(0).overlay();
        // AR applies instance light/overlay to the whole mesh. Nonuniform special output
        // retains its exact attributes through standard playback.
        if (batch.vertices.stream().anyMatch(vertex -> vertex.light() != light || vertex.overlay() != overlay)) {
            return FALLBACK;
        }
        StaticMesh.Acceleration[] prepared = {FALLBACK};
        try {
            if (!(consumer instanceof IAcceleratedVertexConsumer accelerated) || !accelerated.isAccelerated()) {
                return null;
            }
            PoseStack identity = new PoseStack();
            // AR's outer BufferBuilder only dispatches; mesh operations belong to the callback's consumer.
            accelerated.doRender((actual, ignored, pose, normal, color, worldLight, worldOverlay) -> {
                if (actual instanceof IAcceleratedVertexConsumer target && target.isAccelerated()) {
                    prepared[0] = prepare(batch, target, light, overlay);
                }
            }, batch, identity.last().pose(), identity.last().normal(), -1, 0, overlay);
            return prepared[0];
        } catch (RuntimeException exception) {
            prepared[0].close();
            Barricade.LOGGER.warn("AR mesh preparation failed for {}: {}", batch.material, exception.toString());
            return FALLBACK;
        }
    }

    private static StaticMesh.Acceleration prepare(StaticMesh.Batch batch, IAcceleratedVertexConsumer target,
                                                   int light, int overlay) {
        VertexLayout layout = target.getLayout();
        var collector = new SimpleMeshCollector(layout);
        MeshBuffer buffer = null;
        try {
            PoseStack identity = new PoseStack();
            for (StaticMesh.Vertex vertex : batch.vertices) vertex.write(collector, identity.last(), 0);
            collector.flush();
            try (var data = collector.getBuffer().build()) {
                if (data == null) return FALLBACK;
                buffer = new MeshBuffer(64);
                var bytes = data.byteBuffer();
                int offset = (int) (buffer.append(MemoryUtil.memAddress(bytes), bytes.remaining()) / layout.getSize());
                // AR 1.0.14's sparse path corrupts varying offsets across separate mesh buffers.
                // Dense GPU uploads keep our independently owned buffers out of that path.
                ServerMesh mesh = new ServerMesh(0, ServerMesh.Builder.COUNTER++, batch.vertices.size(),
                        offset, true, buffer);
                if (REPORTED.add(batch.material.toString())) {
                    Barricade.LOGGER.info("Static material using AR server mesh: {} ({} vertices)",
                            batch.material, batch.vertices.size());
                }
                return new CachedMesh(mesh, layout, light, overlay);
            }
        } catch (RuntimeException exception) {
            if (buffer != null) buffer.delete();
            throw exception;
        } finally {
            collector.getBuffer().close();
        }
    }

    static final class CachedMesh implements StaticMesh.Acceleration, IAcceleratedRenderer<Void> {
        private final ServerMesh mesh;
        private final VertexLayout layout;
        private final int light;
        private final int overlay;
        private boolean drawn;
        private boolean disabled;
        private boolean closed;

        CachedMesh(ServerMesh mesh, VertexLayout layout, int light, int overlay) {
            this.mesh = mesh;
            this.layout = layout;
            this.light = light;
            this.overlay = overlay;
        }

        @Override public boolean draw(VertexConsumer out, PoseStack.Pose pose, int worldLight) {
            if (disabled || closed) return false;
            drawn = false;
            try {
                if (!(out instanceof IAcceleratedVertexConsumer target) || !target.isAccelerated()) return false;
                target.doRender(this, null, pose.pose(), pose.normal(), -1,
                        StaticMesh.mergeLight(light, worldLight), overlay);
                return drawn;
            } catch (RuntimeException exception) {
                disabled = true;
                Barricade.LOGGER.warn("AR mesh submission failed for {}: {}", mesh.meshId(), exception.toString());
                return false;
            }
        }

        @Override public void render(VertexConsumer out, Void ignored, Matrix4f pose, Matrix3f normal,
                                     int color, int worldLight, int worldOverlay) {
            if (!(out instanceof IAcceleratedVertexConsumer target) || !target.isAccelerated()
                    || target.getLayout() != layout) return;
            target.beginTransform(pose, normal);
            try {
                mesh.write(target, color, worldLight, worldOverlay);
                drawn = true;
            } finally {
                target.endTransform();
            }
        }

        @Override public void close() {
            if (!closed) {
                closed = true;
                mesh.meshBuffer().delete();
            }
        }
    }
}
