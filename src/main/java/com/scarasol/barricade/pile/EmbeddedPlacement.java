package com.scarasol.barricade.pile;

import com.scarasol.barricade.Barricade;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;

final class EmbeddedPlacement {
    private EmbeddedPlacement() { }

    static InteractionResult place(ServerLevel level, Player player, net.minecraft.world.InteractionHand hand,
                                   net.minecraft.world.phys.BlockHitResult hit, BlockItem item) {
        BlockPos target = hit.getBlockPos().immutable();
        ItemStack stack = player.getItemInHand(hand);
        if (!player.mayBuild() || !player.mayUseItemAt(target, hit.getDirection(), stack)) return InteractionResult.FAIL;
        if (!player.getAbilities().mayBuild && !stack.hasAdventureModePlaceTagForBlock(
                level.registryAccess().registryOrThrow(Registries.BLOCK), new BlockInWorld(level, target, false)))
            return InteractionResult.FAIL;
        if (level.captureBlockSnapshots || !level.capturedBlockSnapshots.isEmpty()) return InteractionResult.FAIL;

        ItemStack beforeStack = stack.copy();
        int beforeCount = stack.getCount();
        CompoundTag beforeTag = stack.getTag() == null ? null : stack.getTag().copy();
        PileData data = PileData.get(level);
        if (data.addEmbedded(target) == null) return InteractionResult.FAIL;
        boolean committed = false;
        try {
        boolean oldCapture = level.captureBlockSnapshots;
        int snapshotStart = level.capturedBlockSnapshots.size();
        InteractionResult result;
        List<BlockSnapshot> snapshots;
        level.captureBlockSnapshots = true;
        try (PileService.TrackingScope ignored = PileService.suspendTracking()) {
            try {
                result = item.place(new TargetContext(level, player, hand, stack, hit, target));
            } catch (RuntimeException | Error failure) {
                level.captureBlockSnapshots = oldCapture;
                rollbackCaptured(level, snapshotStart);
                restoreStack(stack, beforeCount, beforeTag);
                throw failure;
            }
        } finally {
            level.captureBlockSnapshots = oldCapture;
        }

        snapshots = takeCaptured(level, snapshotStart);
        if (!result.consumesAction()) {
            restoreStack(stack, beforeCount, beforeTag);
            rollback(level, snapshots);
            return InteractionResult.FAIL;
        }
        if (stack.isEmpty()) ForgeEventFactory.onPlayerDestroyItem(player, beforeStack, hand);
        int placedCount = stack.getCount();
        CompoundTag placedTag = stack.getTag() == null ? null : stack.getTag().copy();
        restoreStack(stack, beforeCount, beforeTag);
        Set<BlockPos> changedPositions = new HashSet<>();
        snapshots.forEach(snapshot -> changedPositions.add(snapshot.getPos().immutable()));
        if (!changedPositions.equals(Set.of(target))) {
            rollback(level, snapshots);
            return InteractionResult.FAIL;
        }

        boolean canceled = snapshots.size() == 1
                ? ForgeEventFactory.onBlockPlace(player, snapshots.get(0), hit.getDirection())
                : ForgeEventFactory.onMultiBlockPlace(player, snapshots, hit.getDirection());
        if (canceled) {
            rollback(level, snapshots);
            return InteractionResult.FAIL;
        }

        BlockState placed = level.getBlockState(target);
        if (placed.isAir() || placed.is(Barricade.BARRICADE.get())) {
            rollback(level, snapshots);
            return InteractionResult.FAIL;
        }
        restoreStack(stack, placedCount, placedTag);
        notifyPlaced(level, snapshots);
        player.awardStat(Stats.ITEM_USED.get(item));
        committed = true;
        return result;
        } finally {
            if (!committed) data.cancelEmbedding(target);
        }
    }

    private static List<BlockSnapshot> takeCaptured(Level level, int start) {
        List<BlockSnapshot> snapshots = new ArrayList<>(level.capturedBlockSnapshots.subList(start,
                level.capturedBlockSnapshots.size()));
        level.capturedBlockSnapshots.subList(start, level.capturedBlockSnapshots.size()).clear();
        return snapshots;
    }

    private static void rollbackCaptured(Level level, int start) {
        rollback(level, takeCaptured(level, start));
    }

    private static void rollback(Level level, List<BlockSnapshot> snapshots) {
        try (PileService.TrackingScope ignored = PileService.suspendTracking()) {
            for (int index = snapshots.size() - 1; index >= 0; index--) {
                level.restoringBlockSnapshots = true;
                try {
                    snapshots.get(index).restore(true, false);
                } finally {
                    level.restoringBlockSnapshots = false;
                }
            }
        }
    }

    private static void restoreStack(ItemStack stack, int count, CompoundTag tag) {
        stack.setCount(count);
        stack.setTag(tag == null ? null : tag.copy());
    }

    private static void notifyPlaced(ServerLevel level, List<BlockSnapshot> snapshots) {
        try (PileService.TrackingScope ignored = PileService.suspendTracking()) {
            for (BlockSnapshot snapshot : snapshots) {
                BlockPos pos = snapshot.getPos();
                BlockState oldState = snapshot.getReplacedBlock();
                BlockState newState = level.getBlockState(pos);
                newState.onPlace(level, pos, oldState, false);
                level.markAndNotifyBlock(pos, level.getChunkAt(pos), oldState, newState, snapshot.getFlag(), 512);
            }
        }
    }

    private static final class TargetContext extends BlockPlaceContext {
        private final BlockPos target;

        TargetContext(Level level, Player player, net.minecraft.world.InteractionHand hand, ItemStack stack,
                      net.minecraft.world.phys.BlockHitResult hit, BlockPos target) {
            super(level, player, hand, stack, hit);
            this.target = target.immutable();
            this.replaceClicked = true;
        }

        @Override public BlockPos getClickedPos() { return target; }
        @Override public boolean canPlace() { return true; }
    }
}
