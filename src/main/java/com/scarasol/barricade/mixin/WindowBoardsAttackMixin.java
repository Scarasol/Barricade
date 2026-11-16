package com.scarasol.barricade.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.barricade.window.WindowBoardsAttack;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
abstract class WindowBoardsAttackMixin {
    @WrapOperation(method = "handleKeybinds()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;startAttack()Z"), require = 1, allow = 1)
    boolean barricade$windowAttack(Minecraft game, Operation<Boolean> original) {
        HitResult selection = game.hitResult;
        HitResult target = WindowBoardsAttack.pick(game.player, game.getCameraEntity(), selection);
        if (target == selection) return original.call(game);
        game.hitResult = target;
        try {
            return original.call(game);
        } finally {
            game.hitResult = selection;
        }
    }

    @WrapOperation(method = "handleKeybinds()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/Minecraft;continueAttack(Z)V"), require = 1, allow = 1)
    void barricade$windowContinueAttack(Minecraft game, boolean down, Operation<Void> original) {
        if (!down) {
            original.call(game, false);
            return;
        }
        HitResult selection = game.hitResult;
        HitResult target = WindowBoardsAttack.pick(game.player, game.getCameraEntity(), selection);
        if (target == selection) {
            original.call(game, true);
            return;
        }
        game.hitResult = target;
        try {
            original.call(game, true);
        } finally {
            game.hitResult = selection;
        }
    }
}
