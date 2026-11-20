package com.scarasol.barricade.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.barricade.block.EmbeddedCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockCollisions.class)
abstract class EmbeddedBlockCollisionsMixin {
    @Shadow @Final private CollisionGetter collisionGetter;
    @Shadow @Final private BlockPos.MutableBlockPos pos;

    // A state such as an end rod is normally skipped at the edge of the scan.
    @WrapOperation(method = "computeNext()Ljava/lang/Object;", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;hasLargeCollisionShape()Z"))
    private boolean barricade$checkEmbeddedBoundary(BlockState state, Operation<Boolean> original) {
        return original.call(state) || (!state.isAir() && EmbeddedCollision.isEmbeddedAt(collisionGetter, pos));
    }
}
