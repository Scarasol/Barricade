package com.scarasol.barricade.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.barricade.stringlights.LightProjectiles;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.ProjectileImpactEvent.ImpactResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractArrow.class)
abstract class ArrowLightsMixin {
    @Unique private final Set<LightProjectiles.Hit> stringLights$attempted = new HashSet<>();

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult stringLights$trace(Level level, ClipContext ray, Operation<BlockHitResult> original) {
        stringLights$attempted.clear();
        BlockHitResult terminal = original.call(level, ray);
        if ((Object) this instanceof ThrownTrident trident && !trident.isNoPhysics()) {
            return LightProjectiles.first(trident, ray.getFrom(), terminal.getLocation(), terminal);
        }
        return terminal;
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/AbstractArrow;findHitEntity(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/EntityHitResult;"))
    private EntityHitResult stringLights$beforeNextImpact(AbstractArrow arrow, Vec3 from, Vec3 end,
                                                          Operation<EntityHitResult> original) {
        EntityHitResult entity = original.call(arrow, from, end);
        if (!(arrow instanceof ThrownTrident) && !arrow.isNoPhysics()) {
            LightProjectiles.pass(arrow, from, entity == null ? end : entity.getLocation(),
                    hit -> ForgeEventFactory.onProjectileImpactResult(arrow, hit) == ImpactResult.DEFAULT,
                    stringLights$attempted);
        }
        return entity;
    }
}
