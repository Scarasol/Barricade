package com.scarasol.barricade.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.barricade.window.WindowSealBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractArrow.class)
abstract class WindowBoardsArrowMixin extends Projectile {
    protected WindowBoardsArrowMixin(EntityType<? extends Projectile> type, Level level) {
        super(type, level);
    }

    // Arrow embedding checks omit the entity context used by their flight ray casts.
    @WrapOperation(method = "tick()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"),
            require = 1, allow = 1)
    VoxelShape barricade$windowBoardsEmbedding(BlockState state, BlockGetter level, BlockPos pos,
                                               Operation<VoxelShape> original) {
        VoxelShape shape = original.call(state, level, pos);
        if (state.getBlock() instanceof WindowSealBlock && getType().is(WindowSealBlock.PASS_THROUGH_ENTITY_TYPES))
            return Shapes.empty();
        return shape;
    }
}
