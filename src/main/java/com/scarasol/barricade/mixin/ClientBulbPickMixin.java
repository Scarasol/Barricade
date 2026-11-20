package com.scarasol.barricade.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.barricade.stringlights.LightTargets;
import com.scarasol.barricade.stringlights.client.LightClient;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
abstract class ClientBulbPickMixin {
    @WrapOperation(method = "pick(F)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;pick(DFZ)Lnet/minecraft/world/phys/HitResult;"))
    private HitResult stringLights$includeBulbOverhang(Entity camera, double reach, float partialTick,
                                                      boolean fluids, Operation<HitResult> original) {
        HitResult result = original.call(camera, reach, partialTick, fluids);
        return LightClient.hasInstallations() && result instanceof BlockHitResult block
                ? LightTargets.closerBulb(camera.level(), camera.getEyePosition(partialTick), block) : result;
    }
}
