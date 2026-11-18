package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;

import com.github.argon4w.acceleratedrendering.core.backends.buffers.IServerBuffer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.renderers.IAcceleratedRenderer;
import com.github.argon4w.acceleratedrendering.core.buffers.memory.VertexLayout;
import com.github.argon4w.acceleratedrendering.core.meshes.ServerMesh;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.scarasol.barricade.recipe.EmbeddedBlock;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.bernie.geckolib.cache.object.*;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

class EmbeddedAnimationTest {
    static {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }
    private static final ResourceLocation TEXTURE = new ResourceLocation("barricade", "animation-test.png");
    private static final RenderType SOLID = new TestType(false);
    private static final RenderType TRANSPARENT = new TestType(true);

    @AfterEach void clear() { EmbeddedBuffers.clearGeometry(); }

    @Test
    void nativeGeckoRendererKeepsTheSameLiveEntityPartialTicksAndStableOuterPoseAcrossFrames() {
        var world = MemoryFixture.create(true);
        BlockPos pos = new BlockPos(1200, 80, -900);
        world.context(Map.of(pos, Blocks.CHEST.defaultBlockState()), Map.of());
        var entity = new AnimatedEntity(pos);
        entity.setLevel(world);
        var identity = new EmbeddedBlock(pos, 7, 1);
        var piece = identity.transform(identity.model(world));
        var bone = new GeoBone(null, "moving", false, 0d, false, false);
        bone.getCubes().add(cube());
        var model = new BakedGeoModel(List.of(bone), null);
        var renderer = new GeoBlockRenderer<AnimatedEntity>(new GeoModel<>() {
            @Override public ResourceLocation getModelResource(AnimatedEntity value) { return TEXTURE; }
            @Override public ResourceLocation getTextureResource(AnimatedEntity value) { return TEXTURE; }
            @Override public ResourceLocation getAnimationResource(AnimatedEntity value) { return TEXTURE; }
            @Override public BakedGeoModel getBakedModel(ResourceLocation resource) { return model; }
            @Override public void handleAnimations(AnimatedEntity value, long id, AnimationState<AnimatedEntity> state) {
                assertSame(entity, value);
                value.frames++;
                bone.setPosX((float) value.clock + state.getPartialTick());
            }
        }) {
            @Override public void updateAnimatedTextureFrame(AnimatedEntity value) { }
            @Override public boolean firePreRenderEvent(PoseStack pose, BakedGeoModel baked,
                    MultiBufferSource buffers, float tick, int light) { return true; }
            @Override public void firePostRenderEvent(PoseStack pose, BakedGeoModel baked,
                    MultiBufferSource buffers, float tick, int light) { }
        };
        var buffers = new EmbeddedBuffers();
        var nativePose = new PoseStack();
        nativePose.translate(2, 3, 4);
        var before = new Matrix4f(nativePose.last().pose());
        List<Vector3f> positions = new ArrayList<>();
        for (int frame = 0; frame < 3; frame++) {
            entity.clock = frame * 4;
            buffers.begin(world, ignored -> true, pos, nativePose.last(), type -> new Sink());
            BarricadeRenderer.drawEmbeddedEntity(renderer, entity, .75f, piece, nativePose, buffers);
            assertEquals(before, nativePose.last().pose());
            assertNull(renderer.getAnimatable());
            assertNull(CaptureBuffers.current());
            var out = new Sink();
            buffers.draw(false, type -> out);
            buffers.draw(true, type -> out);
            assertEquals(4, out.positions.size());
            positions.add(out.positions.get(0));
            assertEquals(frame + 1, entity.frames, "Stage playback never runs the animation again");
            entity.load(entity.saveWithoutMetadata());
            assertEquals(piece.rotation(), new EmbeddedBlock(pos, 7, frame + 2)
                    .transform(identity.model(world)).rotation());
        }
        assertNotEquals(positions.get(0), positions.get(1));
        assertEquals(positions.get(1).distance(positions.get(0)),
                positions.get(2).distance(positions.get(1)), .00001);
        assertFalse(entity.isRemoved());
        assertEquals(pos, entity.getBlockPos());
        assertEquals(0, world.chunkRequests());
        world.reset();
    }

    @Test
    void nativeBoneCacheIsSharedAcrossInstancesWhileEachFrameKeepsItsOwnTransformAndAttributes() {
        var world = MemoryFixture.create(true);
        var actual = new AcceleratedSink();
        var first = new EmbeddedBuffers();
        var second = new EmbeddedBuffers();
        var renderer = new CachedBone();
        var pose = new PoseStack();
        for (int frame = 0; frame < 3; frame++) {
            for (int instance = 0; instance < 2; instance++) {
                var buffers = instance == 0 ? first : second;
                buffers.begin(world, ignored -> true, BlockPos.ZERO, pose.last(), type -> actual);
                var matrix = new Matrix4f().translation(frame, instance * 2, 0);
                ((IAcceleratedVertexConsumer) buffers.getBuffer(SOLID)).doRender(renderer, null,
                        matrix, new Matrix3f(), 0xA00060, 3 | 7 << 16, 0x7F4080FF);
                var output = new Sink();
                buffers.draw(true, type -> output);
                assertTrue(output.positions.isEmpty());
                buffers.draw(false, type -> output);
                assertEquals(4, output.positions.size());
                assertEquals(new Vector3f(frame, instance * 2, 0), output.positions.get(0));
                assertEquals(0x7F4080FF, output.colors.get(0));
                assertEquals(3 | 7 << 16, output.overlays.get(0));
                assertTrue(output.lights.stream().allMatch(light -> (light & 65535) >= 0x60));
            }
        }
        assertEquals(6, renderer.evaluations);
        assertEquals(1, renderer.builds, "Immutable bone geometry is recorded once, not per object or frame");
        assertTrue(actual.positions.isEmpty(), "Native mesh interception cannot submit duplicate geometry");
        world.reset();
    }

    @Test
    void rawOpaqueAndTransparentOutputIsDeferredToItsOwnStageAndFailuresRestoreThePose() {
        var world = MemoryFixture.create(true);
        var buffers = new EmbeddedBuffers();
        var pose = new PoseStack();
        var nativeOutput = new Sink();
        buffers.begin(world, ignored -> true, BlockPos.ZERO, pose.last(), type -> nativeOutput);
        quad(buffers.getBuffer(SOLID));
        quad(buffers.getBuffer(TRANSPARENT));
        assertTrue(nativeOutput.positions.isEmpty());
        var output = new Sink();
        buffers.draw(false, type -> {
            assertSame(SOLID, type);
            return output;
        });
        assertEquals(4, output.positions.size());
        buffers.draw(true, type -> output);
        assertEquals(8, output.positions.size());
        var identity = new EmbeddedBlock(BlockPos.ZERO, 1, 1);
        world.seed(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        var before = new Matrix4f(pose.last().pose());
        assertThrows(IllegalStateException.class, () -> BarricadeRenderer.drawEmbeddedEntity(
                (BlockEntity entity, float partial, PoseStack stack, MultiBufferSource sources, int light, int overlay) -> {
                    assertSame(buffers, CaptureBuffers.current());
                    assertFalse(CaptureBuffers.capturing());
                    throw new IllegalStateException("native failure");
                }, world.getBlockEntity(BlockPos.ZERO), .25f, identity.transform(identity.model(world)), pose, buffers));
        assertEquals(before, pose.last().pose());
        assertNull(CaptureBuffers.current());
        buffers.reset();
        output.positions.clear();
        buffers.draw(false, type -> output);
        buffers.draw(true, type -> output);
        assertTrue(output.positions.isEmpty(), "Invisible objects cannot replay a previous frame");
        world.reset();
    }

    @Test
    void zeroScaledBonesResumeWithoutRebuildingOrEmittingInvalidNormals() {
        var world = MemoryFixture.create(true);
        var actual = new AcceleratedSink();
        var buffers = new EmbeddedBuffers();
        var renderer = new CachedBone();
        for (float scale : new float[]{0, 1, 0, 2}) {
            var pose = new PoseStack();
            pose.scale(scale, scale, scale);
            buffers.begin(world, ignored -> true, BlockPos.ZERO, new PoseStack().last(), type -> actual);
            ((IAcceleratedVertexConsumer) buffers.getBuffer(SOLID)).doRender(renderer, null,
                    pose.last().pose(), pose.last().normal(), 0, 0, -1);
            var output = new Sink();
            buffers.draw(false, type -> output);
            assertEquals(scale == 0 ? 0 : 4, output.positions.size());
            assertTrue(output.normals.stream().allMatch(Vector3f::isFinite));
        }
        assertEquals(1, renderer.builds);
        var pose = new PoseStack();
        pose.scale(1, 1, 0);
        buffers.begin(world, ignored -> true, BlockPos.ZERO, new PoseStack().last(), type -> actual);
        ((IAcceleratedVertexConsumer) buffers.getBuffer(SOLID)).doRender(renderer, null,
                pose.last().pose(), pose.last().normal(), 0, 0, -1);
        var flat = new Sink();
        buffers.draw(false, type -> flat);
        assertEquals(4, flat.positions.size());
        assertTrue(flat.normals.stream().allMatch(Vector3f::isFinite));
        world.reset();
    }

    @Test
    void rawSingularFramesRepairVisibleFacesAndDiscardOnlyCollapsedFaces() {
        var world = MemoryFixture.create(true);
        var buffers = new EmbeddedBuffers();
        for (float scale : new float[]{0, 1, 0, 2}) {
            buffers.begin(world, ignored -> true, BlockPos.ZERO, new PoseStack().last(), type -> new Sink());
            VertexConsumer stream = buffers.getBuffer(SOLID);
            for (int corner = 0; corner < 4; corner++) {
                float x = corner == 1 || corner == 2 ? scale : 0, y = corner >= 2 ? scale : 0;
                stream.vertex(x, y, 0).color(255, 255, 255, 255).uv(0, 0)
                        .overlayCoords(0, 0).uv2(0, 0).normal(Float.NaN, Float.NaN, Float.NaN).endVertex();
            }
            var output = new Sink();
            buffers.draw(false, type -> output);
            assertEquals(scale == 0 ? 0 : 4, output.positions.size());
            assertTrue(output.normals.stream().allMatch(Vector3f::isFinite));
        }
        world.reset();
    }

    private static GeoCube cube() {
        var quad = new GeoQuad(new GeoVertex[]{
                new GeoVertex(new Vector3f(0, 0, 0), 0, 0),
                new GeoVertex(new Vector3f(1, 0, 0), 1, 0),
                new GeoVertex(new Vector3f(1, 1, 0), 1, 1),
                new GeoVertex(new Vector3f(0, 1, 0), 0, 1)
        }, new Vector3f(0, 0, 1), Direction.SOUTH);
        return new GeoCube(new GeoQuad[]{quad}, Vec3.ZERO, Vec3.ZERO, new Vec3(1, 1, 1), 0, false);
    }

    private static void quad(VertexConsumer out) {
        for (int corner = 0; corner < 4; corner++) {
            float x = corner == 1 || corner == 2 ? 1 : 0, y = corner >= 2 ? 1 : 0;
            out.vertex(x, y, 0).color(255, 255, 255, 255).uv(x, y)
                    .overlayCoords(0, 0).uv2(0, 0).normal(0, 0, 1).endVertex();
        }
    }

    private static final class AnimatedEntity extends BlockEntity implements GeoAnimatable {
        double clock;
        int frames;
        AnimatedEntity(BlockPos pos) { super(BlockEntityType.CHEST, pos, Blocks.CHEST.defaultBlockState()); }
        @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) { }
        @Override public AnimatableInstanceCache getAnimatableInstanceCache() {
            throw new AssertionError("Only the fixture's time source is replaced");
        }
        @Override public double getTick(Object entity) { return clock; }
    }

    private static final class CachedBone implements IAcceleratedRenderer<Void> {
        final Map<VertexConsumer, ServerMesh> cache = new IdentityHashMap<>();
        int evaluations, builds;
        @Override public void render(VertexConsumer out, Void context, Matrix4f pose, Matrix3f normal,
                                     int light, int overlay, int color) {
            evaluations++;
            var accelerated = (IAcceleratedVertexConsumer) out;
            accelerated.beginTransform(pose, normal);
            ServerMesh mesh = cache.get(out);
            if (mesh == null) {
                builds++;
                quad(accelerated.decorate(new Sink()));
                mesh = new ServerMesh(0, 7, 4, 0, false, new TestBuffer());
                cache.put(out, mesh);
            }
            mesh.write(accelerated, color, light, overlay);
            accelerated.endTransform();
        }
    }

    private static class Sink implements VertexConsumer {
        final List<Vector3f> positions = new ArrayList<>();
        final List<Vector3f> normals = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>(), overlays = new ArrayList<>(), lights = new ArrayList<>();
        float x, y, z, nx, ny, nz;
        int color, overlay, light;
        @Override public VertexConsumer vertex(double x, double y, double z) {
            this.x = (float) x; this.y = (float) y; this.z = (float) z; return this;
        }
        @Override public VertexConsumer color(int r, int g, int b, int a) {
            color = a << 24 | r << 16 | g << 8 | b; return this;
        }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { overlay = u | v << 16; return this; }
        @Override public VertexConsumer uv2(int u, int v) { light = u | v << 16; return this; }
        @Override public VertexConsumer normal(float x, float y, float z) {
            nx = x; ny = y; nz = z; return this;
        }
        @Override public void endVertex() {
            positions.add(new Vector3f(x, y, z)); normals.add(new Vector3f(nx, ny, nz));
            colors.add(color); overlays.add(overlay); lights.add(light);
        }
        @Override public void defaultColor(int r, int g, int b, int a) { color(r, g, b, a); }
        @Override public void unsetDefaultColor() { }
    }

    private static final class AcceleratedSink extends Sink implements IAcceleratedVertexConsumer {
        final VertexLayout layout = new VertexLayout(DefaultVertexFormat.NEW_ENTITY);
        @Override public boolean isAccelerated() { return true; }
        @Override public VertexLayout getLayout() { return layout; }
        @Override public VertexConsumer decorate(VertexConsumer out) { return out; }
        @Override public <T> void doRender(IAcceleratedRenderer<T> renderer, T context,
                Matrix4f pose, Matrix3f normal, int light, int overlay, int color) {
            renderer.render(this, context, pose, normal, light, overlay, color);
        }
    }

    private static final class TestBuffer implements IServerBuffer {
        @Override public int getBufferHandle() { return 0; }
        @Override public void delete() { fail("The native library owns its mesh buffer"); }
        @Override public void bind(int target) { fail(); }
        @Override public void bindBase(int target, int index) { fail(); }
        @Override public void bindRange(int target, int index, long offset, long size) { fail(); }
        @Override public void data(long offset, long size, long address) { fail(); }
    }

    private static final class TestType extends RenderType
            implements com.scarasol.barricade.mixin.RenderTypeAccess {
        final boolean transparent;
        TestType(boolean transparent) {
            super("animation_test", DefaultVertexFormat.NEW_ENTITY,
                    com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 256, false, transparent, () -> { }, () -> { });
            this.transparent = transparent;
        }
        @Override public boolean barricade$sort() { return transparent; }
    }
}
