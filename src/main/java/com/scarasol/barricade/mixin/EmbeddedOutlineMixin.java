package com.scarasol.barricade.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.barricade.block.EmbeddedCollision;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
abstract class EmbeddedOutlineMixin {
    // Match the barricade's one-block outline, not its taller entity collision.
    @WrapOperation(method = "renderHitOutline", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"),
            require = 1, allow = 1)
    VoxelShape barricade$drawEmbeddedOutline(BlockState state, BlockGetter level, BlockPos pos,
                                             CollisionContext context, Operation<VoxelShape> original) {
        return EmbeddedCollision.isEmbeddedAt(level, pos)
                ? Shapes.block() : original.call(state, level, pos, context);
    }
}
