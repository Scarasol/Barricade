package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.renderers.IAcceleratedRenderer;
import com.github.argon4w.acceleratedrendering.core.buffers.memory.VertexLayout;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.scarasol.barricade.window.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WindowBoardsMeshTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void bakingPreservesAllSixPlanesTintNormalsAndBothAtlasTextures() {
        try (var wood = new Sprite(0); var iron = new Sprite(32)) {
            for (Direction facing : Direction.values()) {
                BoardPlane plane = new BoardPlane(facing, -17);
                BlockPos origin = plane.block(new BoardCell(-2, -3));
                var faces = List.of(face(false), face(true));
                var fragments = List.of(new BoardLayout.Fragment(1, new BoardCell(-2, -3), faces));
                try (var mesh = new WindowBoardsMesh(plane, origin, fragments, wood, iron)) {
                    assertEquals(1, mesh.cells.size());
                    var batch = mesh.cells.get(0).batch();
                    assertSame(WindowBoardsMesh.MATERIAL, batch.material);
                    assertEquals(8, batch.vertices.size());
                    Vec3 normal = plane.normal(0, 0, 1);
                    float shade = (float) (0.8 + Math.max(0, normal.y) * 0.2
                            - Math.max(0, -normal.y) * 0.25 - Math.abs(normal.x) * 0.08);
                    for (int i = 0; i < 8; i++) {
                        var source = faces.get(i / 4).vertices().get(i % 4);
                        var vertex = batch.vertices.get(i);
                        var sprite = i < 4 ? wood : iron;
                        Vec3 point = plane.point(source.u(), source.v(), source.depth());
                        assertEquals((float) (point.x - origin.getX()), vertex.x());
                        assertEquals((float) (point.y - origin.getY()), vertex.y());
                        assertEquals((float) (point.z - origin.getZ()), vertex.z());
                        assertEquals(sprite.getU(source.textureU() * 16), vertex.u());
                        assertEquals(sprite.getV(source.textureV() * 16), vertex.v());
                        assertEquals(Math.round(192 * shade), vertex.red());
                        assertEquals(Math.round(176 * shade), vertex.green());
                        assertEquals(Math.round(160 * shade), vertex.blue());
                        assertEquals(OverlayTexture.NO_OVERLAY, vertex.overlay());
                        assertEquals(0, vertex.light(), "Lighting must be supplied at draw time");
                        assertEquals((float) normal.x, vertex.nx());
                        assertEquals((float) normal.y, vertex.ny());
                        assertEquals((float) normal.z, vertex.nz());
                    }
                }
            }
        }
    }

    @Test void standardPlaybackUsesCurrentLightPerCellAndCurrentPoseWithoutRebaking() {
        try (var wood = new Sprite(0); var iron = new Sprite(32); var mesh = mesh(wood, iron)) {
            var out = new Consumer();
            PoseStack pose = new PoseStack();
            pose.translate(4, 5, 6);
            Set<BlockPos> sampled = new HashSet<>();
            mesh.draw(pose.last(), ignored -> out, pos -> {
                sampled.add(pos);
                return pos.getX() == 0 ? 32 : 64;
            });
            assertEquals(Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0)), sampled);
            assertEquals(List.of(32, 32, 32, 32, 64, 64, 64, 64), out.lights);
            assertEquals(4, out.firstX);
            assertEquals(5, out.firstY);
            assertEquals(6 + 15 / 16.0, out.firstZ);
            mesh.draw(new PoseStack().last(), ignored -> out, pos -> 240);
            assertEquals(16, out.vertices);
            assertEquals(List.of(240, 240, 240, 240, 240, 240, 240, 240), out.lights.subList(8, 16));
        }
    }

    @Test void ironJointOffsetsSurviveFloatMeshBakingOnEveryPlane() {
        try (var metal = new Sprite(0); var weld = new Sprite(32)) {
            var shape = Set.of(new BoardCell(-2, -3), new BoardCell(-1, -3),
                    new BoardCell(-2, -2), new BoardCell(-1, -2));
            for (Direction facing : Direction.values()) {
                var plane = new BoardPlane(facing, -17);
                var origin = plane.block(new BoardCell(-2, -3));
                var layout = BoardLayout.generate(new BoardGroup.LayoutKey(42, plane,
                        shape, Set.of(), SealMaterial.IRON));
                var fragments = BoardLayout.visible(layout, Set.of());
                Map<BoardCell, List<BoardLayout.Vertex>> sources = new TreeMap<>();
                for (var fragment : fragments) for (var face : fragment.faces())
                    sources.computeIfAbsent(fragment.owner(), ignored -> new ArrayList<>()).addAll(face.vertices());
                try (var mesh = new WindowBoardsMesh(plane, origin, fragments, metal, weld)) {
                    for (var cell : mesh.cells) {
                        var expected = sources.get(plane.cell(cell.pos()));
                        var actual = cell.batch().vertices;
                        assertEquals(expected.size(), actual.size());
                        for (int i = 0; i < expected.size(); i++) {
                            var source = expected.get(i);
                            var point = plane.point(source.u(), source.v(), source.depth());
                            var vertex = actual.get(i);
                            assertEquals((float) (point.x - origin.getX()), vertex.x());
                            assertEquals((float) (point.y - origin.getY()), vertex.y());
                            assertEquals((float) (point.z - origin.getZ()), vertex.z());
                            double depth = switch (facing.getAxis()) {
                                case X -> vertex.x(); case Y -> vertex.y(); case Z -> vertex.z();
                            };
                            if (facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE) depth = 1 - depth;
                            assertEquals(source.depth(), depth * 16, 1e-6,
                                    "Subpixel translations must survive the actual float vertex path");
                        }
                    }
                }
            }
        }
    }

    @Test void acceleratedConsumersUseTheExistingBridgeAndUnsupportedMeshesFallBack() {
        try (var wood = new Sprite(0); var iron = new Sprite(32); var mesh = mesh(wood, iron)) {
            var out = new DispatchConsumer();
            mesh.draw(new PoseStack().last(), ignored -> out, pos -> 128);
            assertEquals(2, out.dispatches);
            assertEquals(8, out.vertices, "Unsupported AR layouts retain normal playback");
        }
    }

    @Test void retiringAMeshReleasesEachAccelerationOnceAndCannotDrawAgain() throws Exception {
        try (var wood = new Sprite(0); var iron = new Sprite(32)) {
            var mesh = mesh(wood, iron);
            AtomicInteger releases = new AtomicInteger();
            var field = StaticMesh.Batch.class.getDeclaredField("acceleration");
            field.setAccessible(true);
            for (var cell : mesh.cells) field.set(cell.batch(), new StaticMesh.Acceleration() {
                @Override public boolean draw(VertexConsumer out, PoseStack.Pose pose, int light) { return true; }
                @Override public void close() { releases.incrementAndGet(); }
            });
            mesh.close();
            mesh.close();
            assertEquals(2, releases.get());
            mesh.draw(new PoseStack().last(), ignored -> { throw new AssertionError("Retired mesh"); },
                    pos -> { throw new AssertionError("Retired mesh"); });
        }
    }

    private static WindowBoardsMesh mesh(TextureAtlasSprite wood, TextureAtlasSprite iron) {
        return new WindowBoardsMesh(new BoardPlane(Direction.NORTH, 0), BlockPos.ZERO, List.of(
                new BoardLayout.Fragment(1, new BoardCell(0, 0), List.of(face(false))),
                new BoardLayout.Fragment(2, new BoardCell(1, 0), List.of(face(true)))), wood, iron);
    }

    private static BoardLayout.Face face(boolean nail) {
        return new BoardLayout.Face(List.of(new BoardLayout.Vertex(0, 0, 1, 0, 0),
                new BoardLayout.Vertex(16, 0, 1, 1, 0), new BoardLayout.Vertex(16, 4, 1, 1, 0.25f),
                new BoardLayout.Vertex(0, 4, 1, 0, 0.25f)), new Vec3(0, 0, 1), nail, 0xc0b0a0);
    }

    private static final class Sprite extends TextureAtlasSprite implements AutoCloseable {
        Sprite(int x) {
            super(TextureAtlas.LOCATION_BLOCKS, new SpriteContents(new ResourceLocation("barricade", "test"),
                    new FrameSize(16, 16), new NativeImage(16, 16, false), AnimationMetadataSection.EMPTY, null),
                    64, 64, x, 16);
        }
        @Override public void close() { contents().close(); }
    }

    private static class Consumer implements VertexConsumer {
        int vertices;
        double firstX, firstY, firstZ;
        final List<Integer> lights = new ArrayList<>();
        @Override public VertexConsumer vertex(double x, double y, double z) {
            if (vertices == 0) { firstX = x; firstY = y; firstZ = z; }
            return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { lights.add(u | v << 16); return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() { vertices++; }
        @Override public void defaultColor(int r, int g, int b, int a) { }
        @Override public void unsetDefaultColor() { }
    }

    private static final class DispatchConsumer extends Consumer implements IAcceleratedVertexConsumer {
        int dispatches;
        @Override public boolean isAccelerated() { return true; }
        @Override public VertexLayout getLayout() { throw new UnsupportedOperationException("No GPU in this test"); }
        @Override public <T> void doRender(IAcceleratedRenderer<T> renderer, T context,
                                          Matrix4f pose, Matrix3f normal, int color, int light, int overlay) {
            dispatches++;
            renderer.render(this, context, pose, normal, color, light, overlay);
        }
    }
}
