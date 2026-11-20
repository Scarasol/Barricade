package com.scarasol.barricade.mixin.superbwarfare;

import com.atsuishio.superbwarfare.api.event.ProjectileHitEvent.HitBlock;
import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.barricade.stringlights.LightProjectiles;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ProjectileEntity.class)
abstract class SuperbLightsMixin {
    @Unique private final Set<LightProjectiles.Hit> stringLights$attempted = new HashSet<>();
    @Unique private Vec3 stringLights$start;
    @Unique private Vec3 stringLights$end;

    // The first BlockHitResult local is the solid hit; the second is the untouched fluid hit.
    @ModifyVariable(method = "tick()V", at = @At("STORE"), ordinal = 0)
    private BlockHitResult stringLights$trace(BlockHitResult block) {
        var shot = (ProjectileEntity) (Object) this;
        stringLights$attempted.clear();
        stringLights$start = shot.position();
        stringLights$end = block == null ? stringLights$start.add(shot.getDeltaMovement()) : block.getLocation();
        return shot.getExplosionDamage() > 0
                ? LightProjectiles.first(shot, stringLights$start, stringLights$end, block) : block;
    }

    @WrapOperation(method = "tick()V", at = @At(value = "INVOKE",
            target = "Lcom/atsuishio/superbwarfare/entity/projectile/ProjectileEntity;onHit(Lnet/minecraft/world/phys/HitResult;)V"),
            require = 2)
    private void stringLights$beforeImpact(ProjectileEntity shot, HitResult hit, Operation<Void> original) {
        if (shot.getExplosionDamage() <= 0 && stringLights$start != null) {
            stringLights$pass(shot, hit == null ? stringLights$end : hit.getLocation());
        }
        original.call(shot, hit);
    }

    @WrapOperation(method = "tick()V", at = @At(value = "INVOKE",
            target = "Lcom/atsuishio/superbwarfare/entity/projectile/ProjectileEntity;onHitWater(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/BlockHitResult;)V",
            remap = false))
    private void stringLights$beforeWater(ProjectileEntity shot, Vec3 location, BlockHitResult water, Operation<Void> original) {
        if (!shot.isRemoved() && shot.getExplosionDamage() <= 0 && stringLights$start != null) {
            stringLights$pass(shot, stringLights$end);
        }
        original.call(shot, location, water);
    }

    @WrapOperation(method = "onHitBlock(Lnet/minecraft/world/phys/BlockHitResult;)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;onProjectileHit(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/entity/projectile/Projectile;)V"))
    private void stringLights$accepted(BlockState state, Level level, BlockState hitState, BlockHitResult hit,
                                      Projectile shot, Operation<Void> original) {
        original.call(state, level, hitState, hit, shot);
        LightProjectiles.accepted(shot, hit);
    }

    @Unique private void stringLights$pass(ProjectileEntity shot, Vec3 end) {
        LightProjectiles.pass(shot, stringLights$start, end, hit -> {
            BlockPos pos = hit.getBlockPos();
            return !MinecraftForge.EVENT_BUS.post(new HitBlock(pos, shot.level().getBlockState(pos),
                    hit.getDirection(), shot.getOwner(), shot, hit.getLocation()));
        }, stringLights$attempted);
    }
}
