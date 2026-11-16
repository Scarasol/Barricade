package com.scarasol.barricade.pile;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.event.level.PistonEvent;

final class PistonGuard {
    private PistonGuard() { }

    static boolean blocks(PistonEvent.Pre event, PileData data) {
        if (event.getPistonMoveType().isExtend) {
            PistonStructureResolver structure = event.getStructureHelper();
            if (structure == null || !structure.resolve()) return false;
            for (BlockPos pos : structure.getToPush())
                if (data.embeddedAt(pos) != null || data.embeddedAt(pos.relative(structure.getPushDirection())) != null)
                    return true;
            for (BlockPos pos : structure.getToDestroy())
                if (data.embeddedAt(pos) != null) return true;
            return false;
        }
        if (data.embeddedAt(event.getFaceOffsetPos()) != null) return true;
        if (!event.getState().is(Blocks.STICKY_PISTON)) return false;
        return blocksPull((Level) event.getLevel(), event.getPos(), event.getDirection(), data);
    }

    private static boolean blocksPull(Level level, BlockPos piston, Direction facing, PileData data) {
        Direction movement = facing.getOpposite();
        BlockPos head = piston.relative(facing), start = piston.relative(facing, 2);
        var first = level.getBlockState(start);
        if (first.isAir() || !PistonBaseBlock.isPushable(first, level, start, movement, false, facing)
                || (first.getPistonPushReaction() != PushReaction.NORMAL
                && !first.is(Blocks.PISTON) && !first.is(Blocks.STICKY_PISTON))) return false;

        // Pre fires before the head is removed, so the normal resolver can stop before
        // visiting sticky side branches. Check pull dependencies with that head excluded.
        Set<BlockPos> moving = new HashSet<>();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        moving.add(start);
        pending.add(start);
        boolean blocked = false;
        while (!pending.isEmpty()) {
            BlockPos pos = pending.removeFirst();
            var state = level.getBlockState(pos);
            BlockPos target = pos.relative(movement);
            blocked |= data.embeddedAt(pos) != null || data.embeddedAt(target) != null;
            if (!target.equals(head) && !moving.contains(target)) {
                var next = level.getBlockState(target);
                if (!next.isAir()) {
                    if (!PistonBaseBlock.isPushable(next, level, target, movement, true, movement)) return false;
                    if (next.getPistonPushReaction() != PushReaction.DESTROY && moving.add(target)) pending.add(target);
                }
            }
            if (state.isStickyBlock()) for (Direction side : Direction.values()) {
                BlockPos neighbor = pos.relative(side);
                if (neighbor.equals(head) || neighbor.equals(piston) || moving.contains(neighbor)) continue;
                var adjacent = level.getBlockState(neighbor);
                if (!adjacent.isAir() && state.canStickTo(adjacent) && adjacent.canStickTo(state)
                        && PistonBaseBlock.isPushable(adjacent, level, neighbor, movement, false, side)
                        && moving.add(neighbor)) pending.add(neighbor);
            }
            if (moving.size() > PistonStructureResolver.MAX_PUSH_DEPTH) return false;
        }
        return blocked;
    }
}
