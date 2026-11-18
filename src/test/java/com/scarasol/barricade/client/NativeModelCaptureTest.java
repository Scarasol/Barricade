package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.scarasol.barricade.pile.ModelPart;
import com.scarasol.barricade.recipe.EmbeddedBlock;
import com.scarasol.barricade.sampling.MemoryFixture;
import com.tacz.guns.client.model.bedrock.BedrockCubeBox;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import software.bernie.geckolib.cache.object.*;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoBlockRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

class NativeModelCaptureTest {
    private static final ResourceLocation TEXTURE = new ResourceLocation("barricade", "native-capture.png");

    @Test
    void geckoLibNativeBlockRendererRetainsBoneTransformsTextureAndEmissiveLayer() {
        var root = new GeoBone(null, "root", false, 0d, false, false);
        var child = new GeoBone(root, "child", false, 0d, false, false);
        root.getChildBones().add(child);
        root.setPosX(4);
        child.setPosY(8);
        var quad = new GeoQuad(new GeoVertex[]{
                new GeoVertex(new Vector3f(0, 0, 0), .125f, .25f),
                new GeoVertex(new Vector3f(1, 0, 0), .375f, .25f),
                new GeoVertex(new Vector3f(1, 1, 0), .375f, .75f),
                new GeoVertex(new Vector3f(0, 1, 0), .125f, .75f)
        }, new Vector3f(0, 0, 1), Direction.SOUTH);
        child.getCubes().add(new GeoCube(new GeoQuad[]{quad}, Vec3.ZERO, Vec3.ZERO,
                new Vec3(1, 1, 1), 0, false));
        var model = new BakedGeoModel(List.of(root), null);
        var renderer = new HeadlessGeoRenderer(model);
        renderer.addRenderLayer(new GeoRenderLayer<>(renderer) {
            @Override public void render(PoseStack pose, HeadlessGeoEntity entity, BakedGeoModel baked,
                    RenderType type, MultiBufferSource buffers, VertexConsumer out, float tick,
                    int light, int overlay) {
                RenderType emissive = RenderType.eyes(TEXTURE);
                getRenderer().reRender(baked, pose, buffers, entity, emissive, buffers.getBuffer(emissive),
                        tick, 0xF000F0, overlay, 1, 1, 1, 1);
            }
        });
        var world = MemoryFixture.create(true);
        BlockPos pos = new BlockPos(-17, -60, -1);
        world.context(Map.of(pos, Blocks.CHEST.defaultBlockState()), Map.of());
        var embedded = new EmbeddedBlock(pos, 7, 1);
        var appearance = embedded.model(world);
        try (StaticMesh mesh = capture(appearance.parts(), (entity, tick, pose, buffers, light, overlay) -> {
            var geoEntity = new HeadlessGeoEntity(entity.getBlockPos(), entity.getBlockState());
            geoEntity.setLevel(entity.getLevel());
            renderer.render(geoEntity, tick, pose, buffers, light, overlay);
        })) {
            assertEquals(2, mesh.batches.size());
            assertSame(RenderType.entityTranslucent(TEXTURE), mesh.batches.get(0).material);
            assertSame(RenderType.eyes(TEXTURE), mesh.batches.get(1).material);
            assertEquals(4, mesh.batches.get(0).vertices.size());
            assertEquals(4, mesh.batches.get(1).vertices.size());
            var vertex = mesh.batches.get(0).vertices.get(0);
            assertEquals(.125f, vertex.u());
            assertEquals(.25f, vertex.v());
            assertEquals(255, vertex.alpha());
            assertEquals(0, vertex.light());
            assertEquals(0xF000F0, mesh.batches.get(1).vertices.get(0).light());
            assertEquals(1, mesh.bounds.getXsize(), .00001);
            assertEquals(1, mesh.bounds.getYsize(), .00001);
            assertEquals(.5, mesh.bounds.minY, .00001);
            assertNull(renderer.getAnimatable(), "The native renderer must complete its cleanup");
            assertTrue(BarricadeRenderer.transformed(pos, embedded.transform(appearance), mesh.bounds).getSize() > 0);
            assertFalse(world.getBlockEntity(pos).isRemoved());
            assertEquals(0, world.chunkRequests());
        }
    }

    @Test
    void taczNativeBedrockCubeRetainsGeometryColorsUvLightAndNormals() {
        var cube = new BedrockCubeBox(0, 0, 0, 0, 0, 16, 8, 4, 0, false, 64, 64);
        RenderType material = RenderType.entityCutout(TEXTURE);
        try (StaticMesh mesh = capture((entity, tick, pose, buffers, light, overlay) -> {
            assertSame(buffers, CaptureBuffers.current());
            pose.translate(.25, .5, .75);
            cube.compile(pose.last(), CaptureBuffers.current().getBuffer(material),
                    0xF000F0, overlay, .25f, .5f, .75f, .5f);
        })) {
            assertEquals(new AABB(.25, .5, .75, 1.25, 1, 1), mesh.bounds);
            assertEquals(1, mesh.batches.size());
            assertSame(material, mesh.batches.get(0).material);
            var vertices = mesh.batches.get(0).vertices;
            assertEquals(24, vertices.size());
            assertTrue(vertices.stream().allMatch(vertex -> vertex.red() == 63 && vertex.green() == 127
                    && vertex.blue() == 191 && vertex.alpha() == 127 && vertex.light() == 0xF000F0));
            assertEquals(6, vertices.stream().map(vertex -> new Vector3f(vertex.nx(), vertex.ny(), vertex.nz()))
                    .distinct().count());
            assertTrue(vertices.stream().anyMatch(vertex -> vertex.u() != 0 && vertex.v() != 0));
        }
    }

    @Test
    void embeddedNativeCaptureRetainsPlacedEntityDataAndTransformsTheCompleteVisualBounds() {
        BlockPos pos = new BlockPos(-17, -60, -1);
        var world = MemoryFixture.create(true);
        var data = new CompoundTag();
        data.putString("CustomName", "{\"text\":\"native embedding\"}");
        world.context(Map.of(pos, Blocks.CHEST.defaultBlockState()), Map.of(pos, data));
        var source = world.getBlockEntity(pos);
        var before = source.saveWithoutMetadata();
        var embedded = new EmbeddedBlock(pos, 7, 1);
        var model = embedded.model(world);
        var cube = new BedrockCubeBox(0, 0, 0, 0, 0, 16, 64, 8, 0, false, 64, 64);
        try (StaticMesh mesh = capture(model.parts(), (entity, tick, pose, buffers, light, overlay) -> {
            assertNotSame(source, entity, "Capture must not change the live block entity");
            assertEquals(before, entity.saveWithoutMetadata());
            assertSame(buffers, CaptureBuffers.current());
            cube.compile(pose.last(), CaptureBuffers.current().getBuffer(RenderType.entityCutout(TEXTURE)),
                    light, overlay, 1, 1, 1, 1);
        })) {
            assertEquals(new AABB(0, 0, 0, 1, 4, .5), mesh.bounds);
            var bounds = BarricadeRenderer.transformed(pos, embedded.transform(model), mesh.bounds);
            var piece = embedded.transform(model);
            for (var batch : mesh.batches) for (var vertex : batch.vertices) {
                var transformed = piece.transformPoint(vertex.x(), vertex.y(), vertex.z());
                assertTrue(bounds.inflate(.00001).contains(pos.getX() + transformed.x,
                        pos.getY() + transformed.y, pos.getZ() + transformed.z));
            }
            assertEquals(before, source.saveWithoutMetadata());
            assertFalse(source.isRemoved());
            assertEquals(0, world.chunkRequests());
        }
    }

    @Test
    void zeroScaledGeckoBoneDoesNotDiscardTheVisibleBodyOrExpandItsBounds() {
        var root = new GeoBone(null, "body", false, 0d, false, false);
        root.getCubes().add(geoQuad());
        var model = new BakedGeoModel(List.of(root), null);
        var renderer = new HeadlessGeoRenderer(model);
        AABB expected;
        try (StaticMesh mesh = captureGeo(renderer)) {
            expected = mesh.bounds;
        }
        var hidden = new GeoBone(root, "deploying_part", false, 0d, false, false);
        hidden.getCubes().add(geoQuad());
        hidden.setPosX(128);
        hidden.setScaleX(0);
        hidden.setScaleY(0);
        hidden.setScaleZ(0);
        root.getChildBones().add(hidden);
        try (StaticMesh mesh = captureGeo(renderer)) {
            assertEquals(4, mesh.batches.stream().mapToInt(batch -> batch.vertices.size()).sum());
            assertEquals(expected, mesh.bounds, "Collapsed geometry must not enlarge the visible envelope");
            assertTrue(mesh.batches.stream().flatMap(batch -> batch.vertices.stream())
                    .allMatch(vertex -> Float.isFinite(vertex.nx() + vertex.ny() + vertex.nz())));
        }
    }

    @Test
    void flattenedQuadRecoversItsNormalAndPreservesOtherVertexAttributes() {
        RenderType material = RenderType.entityCutout(TEXTURE);
        try (StaticMesh mesh = capture((entity, tick, pose, buffers, light, overlay) -> {
            pose.scale(1, 1, 0);
            var out = buffers.getBuffer(material);
            for (int corner = 0; corner < 4; corner++) {
                float x = corner == 1 || corner == 2 ? 1 : 0;
                float y = corner >= 2 ? 1 : 0;
                out.vertex(pose.last().pose(), x, y, 0).color(31, 63, 127, 191).uv(x, y)
                        .overlayCoords(3, 7).uv2(11, 13).normal(pose.last().normal(), 0, 0, 1).endVertex();
            }
        })) {
            assertEquals(4, mesh.batches.get(0).vertices.size());
            assertEquals(new AABB(0, 0, 0, 1, 1, 0), mesh.bounds);
            for (var vertex : mesh.batches.get(0).vertices) {
                assertEquals(0, vertex.nx(), .00001f);
                assertEquals(0, vertex.ny(), .00001f);
                assertEquals(1, vertex.nz(), .00001f);
                assertEquals(31, vertex.red());
                assertEquals(63, vertex.green());
                assertEquals(127, vertex.blue());
                assertEquals(191, vertex.alpha());
                assertEquals(vertex.x(), vertex.u());
                assertEquals(vertex.y(), vertex.v());
                assertEquals(3 | 7 << 16, vertex.overlay());
                assertEquals(11 | 13 << 16, vertex.light());
            }
        }
    }

    @Test
    void entirelyCollapsedGeometryStillHasNoAppearance() {
        var root = new GeoBone(null, "hidden", false, 0d, false, false);
        root.getCubes().add(geoQuad());
        root.setScaleX(0);
        root.setScaleY(0);
        root.setScaleZ(0);
        var renderer = new HeadlessGeoRenderer(new BakedGeoModel(List.of(root), null));
        var error = assertThrows(UnsupportedOperationException.class, () -> captureGeo(renderer));
        assertEquals("Empty appearance", error.getMessage());
    }

    private static GeoCube geoQuad() {
        var quad = new GeoQuad(new GeoVertex[]{
                new GeoVertex(new Vector3f(0, 0, 0), 0, 0),
                new GeoVertex(new Vector3f(1, 0, 0), 1, 0),
                new GeoVertex(new Vector3f(1, 1, 0), 1, 1),
                new GeoVertex(new Vector3f(0, 1, 0), 0, 1)
        }, new Vector3f(0, 0, 1), Direction.SOUTH);
        return new GeoCube(new GeoQuad[]{quad}, Vec3.ZERO, Vec3.ZERO, new Vec3(1, 1, 1), 0, false);
    }

    private static StaticMesh captureGeo(HeadlessGeoRenderer renderer) {
        return capture((entity, tick, pose, buffers, light, overlay) -> {
            var geoEntity = new HeadlessGeoEntity(entity.getBlockPos(), entity.getBlockState());
            geoEntity.setLevel(entity.getLevel());
            renderer.render(geoEntity, tick, pose, buffers, light, overlay);
        });
    }

    static StaticMesh capture(BlockEntityRenderer<BlockEntity> renderer) {
        return capture(List.of(new ModelPart("minecraft:chest", 0, 0, 0)), renderer);
    }

    static StaticMesh capture(List<ModelPart> parts, BlockEntityRenderer<BlockEntity> renderer) {
        var memory = MemoryFixture.create(true);
        var entities = new BlockEntityRenderDispatcher(null, null, () -> null, () -> null, () -> null) {
            @Override @SuppressWarnings("unchecked")
            public <T extends BlockEntity> BlockEntityRenderer<T> getRenderer(T entity) {
                return (BlockEntityRenderer<T>) renderer;
            }
        };
        try {
            return MaterialMeshes.capture(parts,
                    memory, new BlockRenderDispatcher(null, null, null), entities, type -> type);
        } finally {
            assertNull(CaptureBuffers.current());
            assertEquals(0, memory.chunkRequests());
            memory.reset();
        }
    }

    private static final class HeadlessGeoEntity extends BlockEntity implements GeoAnimatable {
        HeadlessGeoEntity(BlockPos pos, BlockState state) { super(BlockEntityType.CHEST, pos, state); }
        @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) { }
        @Override public AnimatableInstanceCache getAnimatableInstanceCache() {
            throw new AssertionError("The headless fixture does not run an animation clock");
        }
        @Override public double getTick(Object entity) { return 0; }
    }

    /** Only client clocks, texture uploads, and event dispatch are replaced in this headless probe. */
    private static final class HeadlessGeoRenderer extends GeoBlockRenderer<HeadlessGeoEntity> {
        HeadlessGeoRenderer(BakedGeoModel baked) {
            super(new GeoModel<>() {
                @Override public ResourceLocation getModelResource(HeadlessGeoEntity entity) { return TEXTURE; }
                @Override public ResourceLocation getTextureResource(HeadlessGeoEntity entity) { return TEXTURE; }
                @Override public ResourceLocation getAnimationResource(HeadlessGeoEntity entity) { return TEXTURE; }
                @Override public BakedGeoModel getBakedModel(ResourceLocation location) { return baked; }
                @Override public RenderType getRenderType(HeadlessGeoEntity entity, ResourceLocation texture) {
                    return RenderType.entityTranslucent(texture);
                }
                @Override public void handleAnimations(HeadlessGeoEntity entity, long id,
                        AnimationState<HeadlessGeoEntity> state) {
                    assertEquals(0, state.getPartialTick());
                }
            });
        }
        @Override public void updateAnimatedTextureFrame(HeadlessGeoEntity entity) { }
        @Override public boolean firePreRenderEvent(PoseStack pose, BakedGeoModel model,
                MultiBufferSource buffers, float tick, int light) { return true; }
        @Override public void firePostRenderEvent(PoseStack pose, BakedGeoModel model,
                MultiBufferSource buffers, float tick, int light) { }
    }
}
