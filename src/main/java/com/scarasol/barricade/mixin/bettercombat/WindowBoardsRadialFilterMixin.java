package com.scarasol.barricade.mixin.bettercombat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.barricade.compat.bettercombat.BetterCombatCompat;
import com.scarasol.barricade.window.WindowBoardsAttack;
import net.bettercombat.client.collision.TargetFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TargetFinder.RadialFilter.class, remap = false)
abstract class WindowBoardsRadialFilterMixin {
    @Unique private boolean barricade$attackThroughSeals;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1, allow = 1)
    private void barricade$resolveAttackHand(CallbackInfo callback) {
        var hand = BetterCombatCompat.currentAttack(Minecraft.getInstance().player);
        barricade$attackThroughSeals = hand != null && BetterCombatCompat.canAttackThrough(hand);
    }

    @WrapOperation(method = "lambda$filter$0", at = @At(value = "INVOKE",
            target = "Lnet/bettercombat/client/collision/TargetFinder$RadialFilter;rayContainsNoObstacle(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Z"),
            require = 2, allow = 2)
    private boolean barricade$skipSeals(Vec3 from, Vec3 to, Operation<Boolean> original) {
        if (!barricade$attackThroughSeals) return original.call(from, to);
        return WindowBoardsAttack.clip(Minecraft.getInstance().player, from, to, ClipContext.Block.COLLIDER)
                .getType() != HitResult.Type.BLOCK;
    }
}
