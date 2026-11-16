package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.scarasol.barricade.pile.ModelPart;
import com.scarasol.barricade.pile.CollisionBox;
import com.scarasol.barricade.sampling.MaterialSampler;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.RenderTypeGroup;
import net.minecraftforge.client.model.data.ModelData;
import org.junit.jupiter.api.Test;

class MaterialMeshesTest {
    private static final ResourceLocation TEXTURE = new ResourceLocation("barricade", "capture-test.png");

    @Test
    void samplerStoresOneWholeTableEnvelopeRatherThanSeparateLegs() {
        var memory = MemoryFixture.create(false);
        try (var mesh = VisualMeshFixture.boxes(new CollisionBox(0, .85f, 0, 1, 1, 1),
                new CollisionBox(0, 0, 0, .1f, .85f, .1f),
                new CollisionBox(.9f, 0, 0, 1, .85f, .1f),
                new CollisionBox(0, 0, .9f, .1f, .85f, 1),
                new CollisionBox(.9f, 0, .9f, 1, .85f, 1));
             var sampler = new MaterialSampler(memory, null, "", parts -> MaterialMeshes.bounds(mesh))) {
            var task = sampler.request(new MaterialSampler.Key("minecraft:oak_planks",
                    new net.minecraft.nbt.CompoundTag(), net.minecraft.core.Direction.NORTH));
            while (!task.isDone()) sampler.tick();
            assertEquals(List.of(CollisionBox.CUBE), task.join().boxes());
            assertEquals(.5f, task.join().model("table", 1).pivotY());
        }
    }

    @Test
    void visualPostBoundsDoNotIncludeItsExtraCollisionHeight() {
        try (var mesh = VisualMeshFixture.boxes(new CollisionBox(.375f, 0, .375f, .625f, 1, .625f))) {
            assertEquals(new CollisionBox(.375f, 0, .375f, .625f, 1, .625f), MaterialMeshes.bounds(mesh));
        }
    }

    @Test
    void emptyAuxiliaryRendererDoesNotDiscardTheBakedModel() {
        var memory = MemoryFixture.create(true);
        AtomicInteger calls = new AtomicInteger();
        var renderer = entities((entity, tick, pose, buffers, light, overlay) -> calls.incrementAndGet());
        try (StaticMesh mesh = MaterialMeshes.capture(
                List.of(new ModelPart("minecraft:barrel", 0, 0, 0)), memory, blocks(), renderer,
                type -> RenderType.entitySolid(TEXTURE))) {
            assertEquals(1, calls.get(), "The optional BER must still be invoked");
            assertEquals(4, vertices(mesh));
            assertEquals(1, mesh.bounds.maxX);
            assertEquals(1, mesh.bounds.maxY);
            assertEquals(0, memory.chunkRequests());
        } finally {
            memory.reset();
        }
    }

    @Test
    void multipartModelRetainsBothBodiesWhenOnlyOnePartHasExtraGeometry() {
        var memory = MemoryFixture.create(true);
        var renderer = entities((entity, tick, pose, buffers, light, overlay) -> {
            if (entity.getBlockPos().getX() == 0) {
                quad(buffers.getBuffer(RenderType.entityCutout(TEXTURE)), pose);
            }
        });
        try (StaticMesh mesh = MaterialMeshes.capture(List.of(
                new ModelPart("minecraft:barrel", 0, 0, 0),
                new ModelPart("minecraft:barrel", 1, 0, 0)), memory, blocks(), renderer,
                type -> RenderType.entitySolid(TEXTURE))) {
            assertEquals(12, vertices(mesh));
            assertEquals(2, mesh.bounds.maxX);
            assertEquals(1, mesh.batches.stream().filter(batch -> batch.lightPosition.x == 1.5f).count());
        } finally {
            memory.reset();
        }
    }

    @Test
    void entityOnlyAssemblyMayRenderFromJustOnePart() {
        var memory = MemoryFixture.create(true);
        AtomicInteger calls = new AtomicInteger();
        var renderer = entities((entity, tick, pose, buffers, light, overlay) -> {
            calls.incrementAndGet();
            if (entity.getBlockPos().getX() == 0) {
                quad(buffers.getBuffer(RenderType.entityCutout(TEXTURE)), pose);
            }
        });
        try (StaticMesh mesh = MaterialMeshes.capture(List.of(
                new ModelPart("minecraft:chest", 0, 0, 0),
                new ModelPart("minecraft:chest", 1, 0, 0)), memory, blocks(), renderer,
                type -> RenderType.entitySolid(TEXTURE))) {
            assertEquals(2, calls.get());
            assertEquals(4, vertices(mesh));
        } finally {
            memory.reset();
        }
    }

    @Test
    void modelAndAuxiliaryRendererOutputsAreBothCaptured() {
        var memory = MemoryFixture.create(true);
        var renderer = entities((entity, tick, pose, buffers, light, overlay) ->
                quad(buffers.getBuffer(RenderType.entityCutout(TEXTURE)), pose));
        try (StaticMesh mesh = MaterialMeshes.capture(
                List.of(new ModelPart("minecraft:barrel", 0, 0, 0)), memory, blocks(), renderer,
                type -> RenderType.entitySolid(TEXTURE))) {
            assertEquals(8, vertices(mesh));
            assertEquals(2, mesh.batches.size());
        } finally {
            memory.reset();
        }
    }

    @Test
    void anEntirelyEmptyAppearanceStillFails() {
        var memory = MemoryFixture.create(true);
        try {
            var error = assertThrows(UnsupportedOperationException.class, () -> MaterialMeshes.capture(
                    List.of(new ModelPart("minecraft:chest", 0, 0, 0)), memory, blocks(),
                    entities((entity, tick, pose, buffers, light, overlay) -> { }),
                    type -> RenderType.entitySolid(TEXTURE)));
            assertEquals("Empty appearance", error.getMessage());
        } finally {
            memory.reset();
        }
    }

    private static int vertices(StaticMesh mesh) {
        return mesh.batches.stream().mapToInt(batch -> batch.vertices.size()).sum();
    }

    private static BlockRenderDispatcher blocks() {
        BlockColors colors = new BlockColors();
        BakedModel model = new SimpleBakedModel(List.of(), Map.of(), false, true, true,
                null, ItemTransforms.NO_TRANSFORMS, ItemOverrides.EMPTY,
                new RenderTypeGroup(RenderType.solid(), RenderType.entitySolid(TEXTURE),
                        RenderType.entitySolid(TEXTURE)));
        ModelBlockRenderer renderer = new ModelBlockRenderer(colors) {
            @Override
            public void tesselateWithoutAO(BlockAndTintGetter world, BakedModel baked, BlockState state,
                    BlockPos pos, PoseStack pose, VertexConsumer out, boolean checkSides, RandomSource random,
                    long seed, int overlay, ModelData data, RenderType type) {
                quad(out, pose);
            }
        };
        return new BlockRenderDispatcher(null, null, colors) {
            @Override public BakedModel getBlockModel(BlockState state) { return model; }
            @Override public ModelBlockRenderer getModelRenderer() { return renderer; }
        };
    }

    private static BlockEntityRenderDispatcher entities(BlockEntityRenderer<BlockEntity> renderer) {
        return new BlockEntityRenderDispatcher(null, null, () -> null, () -> null, () -> null) {
            @Override
            @SuppressWarnings("unchecked")
            public <E extends BlockEntity> BlockEntityRenderer<E> getRenderer(E entity) {
                return (BlockEntityRenderer<E>) renderer;
            }
        };
    }

    private static void quad(VertexConsumer out, PoseStack pose) {
        for (int corner = 0; corner < 4; corner++) {
            float x = corner == 1 || corner == 2 ? 1 : 0;
            float y = corner >= 2 ? 1 : 0;
            out.vertex(pose.last().pose(), x, y, 0).color(255, 255, 255, 255)
                    .uv(x, y).overlayCoords(0, 0).uv2(0, 0).normal(0, 0, 1).endVertex();
        }
    }
}
