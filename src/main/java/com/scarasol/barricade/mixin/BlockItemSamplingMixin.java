package com.scarasol.barricade.mixin;

import com.scarasol.barricade.sampling.MemoryLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
abstract class BlockItemSamplingMixin {
    @Inject(method = "updateCustomBlockEntityTag(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)Z",
            at = @At("HEAD"), cancellable = true)
    private static void barricade$memoryItemData(Level level, Player player, BlockPos pos, ItemStack stack,
                                                CallbackInfoReturnable<Boolean> result) {
        if (level instanceof MemoryLevel memory) result.setReturnValue(memory.applyItemData(pos, player, stack));
    }
}
