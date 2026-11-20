package com.scarasol.barricade.stringlights;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

final class LightWireItem extends Item {
    LightWireItem() { super(new Properties()); }

    @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("string_lights.tooltip.wire.start").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("string_lights.tooltip.wire.end").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("string_lights.tooltip.wire.range",
                (int) Math.sqrt(LightCurve.MAX_DISTANCE_SQUARED)).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("string_lights.tooltip.wire.cancel").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("string_lights.tooltip.wire.repair").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("string_lights.tooltip.wire.cut").withStyle(ChatFormatting.GRAY));
    }
}
