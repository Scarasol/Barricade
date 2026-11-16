package com.scarasol.barricade.mixin.bettercombat;

import com.scarasol.barricade.compat.bettercombat.BetterCombatCompat;
import com.scarasol.barricade.window.WindowBoardsAttack;
import net.bettercombat.client.BetterCombatClient;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WindowBoardsAttack.class, remap = false)
abstract class WindowBoardsBetterCombatAttackMixin {
    @Inject(method = "pick", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void barricade$betterCombatPick(Player player, Entity camera, HitResult original,
                                                  CallbackInfoReturnable<HitResult> callback) {
        if (!BetterCombatClient.ENABLED || camera != player
                || !(original instanceof BlockHitResult) || original.getType() != HitResult.Type.BLOCK)
            return;
        var hand = BetterCombatCompat.currentAttack(player);
        if (hand != null) callback.setReturnValue(BetterCombatCompat.pick(player, original, hand));
    }
}
