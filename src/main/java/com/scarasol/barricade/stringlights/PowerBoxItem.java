package com.scarasol.barricade.stringlights;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

final class PowerBoxItem extends BlockItem {
    PowerBoxItem(Block block) { super(block, new Properties()); }

    @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("string_lights.tooltip.box.mount").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("string_lights.tooltip.box.connect").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("string_lights.tooltip.box.input", BoxEnergy.CAPACITY_FE).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("string_lights.tooltip.box.toggle").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("string_lights.tooltip.box.draw").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override public InteractionResult place(BlockPlaceContext context) {
        var mount = context.getPlayer() != null ? LightTools.boxMount(context.getPlayer()) : null;
        if (mount != null) {
            if (!context.getLevel().mayInteract(context.getPlayer(), mount.surface().relative(mount.face()))) {
                return InteractionResult.FAIL;
            }
            context = new MountContext(context, mount);
        }
        InteractionResult result = super.place(context);
        if (result.consumesAction() && mount != null && context.getLevel() instanceof ServerLevel level
                && level.getBlockEntity(context.getClickedPos()) instanceof PowerBoxEntity box) {
            LightWorld.get(level).placed(box, mount.id());
        }
        return result;
    }

    @Override protected boolean canPlace(BlockPlaceContext context, BlockState state) {
        return !context.getLevel().getBlockState(context.getClickedPos()).is(StringLights.BULB.get())
                && (context instanceof MountContext || LightTargets.supports(context.getLevel(),
                context.getClickedPos().relative(context.getClickedFace().getOpposite()),
                context.getClickedFace(), LightTargets.anchor(new net.minecraft.world.phys.BlockHitResult(
                        context.getClickLocation(), context.getClickedFace(),
                        context.getClickedPos().relative(context.getClickedFace().getOpposite()), false))))
                && super.canPlace(context, state);
    }

    private static final class MountContext extends BlockPlaceContext {
        private BlockPos install;

        MountContext(BlockPlaceContext original, LightView.Node mount) {
            super(original.getLevel(), original.getPlayer(), original.getHand(), original.getItemInHand(),
                    new BlockHitResult(mount.anchor().subtract(Vec3.atLowerCornerOf(mount.face().getNormal()).scale(1.0 / 16)),
                            mount.face(), mount.surface(), false));
            install = mount.surface().relative(mount.face());
        }

        @Override public BlockPos getClickedPos() { return install == null ? super.getClickedPos() : install; }
        @Override public boolean canPlace() { return getLevel().getBlockState(install).canBeReplaced(this); }
    }
}

