package com.scarasol.barricade.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.scarasol.barricade.client.BarricadeRenderer;
import com.scarasol.barricade.sampling.MemoryLevel;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.Direction;
import net.minecraftforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockRenderDispatcher.class)
abstract class EmbeddedRenderMixin {
    // Forge adds this overload, so it has no vanilla obfuscation mapping.
    @Inject(method = "renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;Lnet/minecraftforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void barricade$suppressEmbeddedBlock(BlockState state, BlockPos pos, BlockAndTintGetter level,
                                                 PoseStack pose, VertexConsumer consumer, boolean checkSides,
                                                 RandomSource random, ModelData data, RenderType renderType,
                                                 CallbackInfo callback) {
        if (BarricadeRenderer.suppressOriginal(pos)) callback.cancel();
    }
}

@Mixin(Block.class)
abstract class EmbeddedFaceMixin {
    @Inject(method = "shouldRenderFace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void barricade$showFaceBesideEmbedded(BlockState state, BlockGetter level, BlockPos pos,
                                                         Direction side, BlockPos neighbor,
                                                         CallbackInfoReturnable<Boolean> callback) {
        if (!(level instanceof MemoryLevel) && BarricadeRenderer.suppressOriginal(neighbor))
            callback.setReturnValue(true);
    }
}

@Mixin(BlockEntityRenderDispatcher.class)
abstract class EmbeddedBlockEntityRenderMixin {
    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("HEAD"), cancellable = true)
    private void barricade$suppressEmbeddedEntity(BlockEntity entity, float partialTick, PoseStack pose,
                                                  net.minecraft.client.renderer.MultiBufferSource buffers,
                                                  CallbackInfo callback) {
        if (BarricadeRenderer.suppressOriginal(entity.getBlockPos())) callback.cancel();
    }
}
