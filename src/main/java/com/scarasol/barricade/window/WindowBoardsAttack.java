package com.scarasol.barricade.window;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Retargets native left-click attacks without changing selection, use rays or movement. */
public final class WindowBoardsAttack {
    private WindowBoardsAttack() {}

    public static HitResult pick(Player player, Entity camera, HitResult original) {
        if (camera != player || !(original instanceof BlockHitResult block)
                || block.getType() != HitResult.Type.BLOCK
                || !player.getMainHandItem().is(WindowSealBlock.ATTACK_THROUGH_ITEMS)
                || !(player.level().getBlockState(block.getBlockPos()).getBlock() instanceof WindowSealBlock))
            return original;

        double reach = player.getEntityReach();
        double limit = reach * reach;
        Vec3 from = player.getEyePosition(1);
        if (from.distanceToSqr(original.getLocation()) >= limit) return original;
        Vec3 view = player.getViewVector(1).scale(reach);
        Vec3 to = from.add(view);
        BlockHitResult obstruction = clip(player, from, to, ClipContext.Block.OUTLINE);
        if (obstruction.getType() != HitResult.Type.MISS)
            limit = from.distanceToSqr(obstruction.getLocation());
        EntityHitResult target = ProjectileUtil.getEntityHitResult(player, from, to,
                player.getBoundingBox().expandTowards(view).inflate(1),
                entity -> !entity.isSpectator() && entity.isPickable(), limit);
        // Keep ordinary block breaking when there is no reachable entity before the next obstruction.
        return target != null && from.distanceToSqr(target.getLocation()) < limit ? target : original;
    }

    public static BlockHitResult clip(Player player, Vec3 from, Vec3 to, ClipContext.Block mode) {
        return player.level().clip(new ClipContext(
                from, to, mode, ClipContext.Fluid.NONE, player) {
            @Override public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
                return state.getBlock() instanceof WindowSealBlock ? Shapes.empty() : super.getBlockShape(state, level, pos);
            }
        });
    }
}
