package com.scarasol.barricade.mixin;

import com.scarasol.barricade.stringlights.LightProjectiles;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
abstract class ProjectileImpactMixin {
    @Inject(method = "onHitBlock", at = @At("RETURN"))
    private void stringLights$accepted(BlockHitResult hit, CallbackInfo callback) {
        LightProjectiles.accepted((Projectile) (Object) this, hit);
    }
}
