package com.scarasol.barricade.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.scarasol.barricade.block.BarricadeBlock;
import com.scarasol.barricade.block.EmbeddedCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class EmbeddedCollisionMixin {
    @Shadow public abstract Block getBlock();
    @Shadow public abstract boolean isAir();

    // The no-context overload reads the shared state cache before deciding whether to delegate.
    @ModifyExpressionValue(
            method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At(value = "FIELD", target = "Lnet/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase$Cache;collisionShape:Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape barricade$includeCachedHost(VoxelShape shape, BlockGetter level, BlockPos pos) {
        return barricade$includeHost(shape, level, pos);
    }

    @ModifyReturnValue(
            method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            at = @At("RETURN"))
    private VoxelShape barricade$includeContextualHost(VoxelShape shape, BlockGetter level, BlockPos pos,
                                                      CollisionContext context) {
        return barricade$includeHost(shape, level, pos);
    }

    @Unique
    private VoxelShape barricade$includeHost(VoxelShape shape, BlockGetter level, BlockPos pos) {
        return !isAir() && !(getBlock() instanceof BarricadeBlock) && EmbeddedCollision.isEmbeddedAt(level, pos)
                ? EmbeddedCollision.includeBarricade(shape) : shape;
    }
}
