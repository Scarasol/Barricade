package com.scarasol.barricade.mixin.tacz;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.barricade.stringlights.LightProjectiles;
import com.tacz.guns.api.event.server.AmmoHitBlockEvent;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.TacHitResult;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = EntityKineticBullet.class, remap = false)
abstract class TaczLightsMixin {
    @Shadow private boolean explosion;
    @Unique private final Set<LightProjectiles.Hit> stringLights$attempted = new HashSet<>();

    @WrapOperation(method = "onBulletTick()V", at = @At(value = "INVOKE",
            target = "Lcom/tacz/guns/util/block/BlockRayTrace;rayTraceBlocks(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult stringLights$trace(Level level, ClipContext ray, Operation<BlockHitResult> original) {
        stringLights$attempted.clear();
        BlockHitResult terminal = original.call(level, ray);
        return explosion ? LightProjectiles.first((EntityKineticBullet) (Object) this,
                ray.getFrom(), terminal.getLocation(), terminal) : terminal;
    }

    @WrapOperation(method = "onBulletTick()V", at = @At(value = "INVOKE",
            target = "Lcom/tacz/guns/entity/EntityKineticBullet;onHitEntity(Lcom/tacz/guns/util/TacHitResult;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V"))
    private void stringLights$beforeEntity(EntityKineticBullet shot, TacHitResult hit, Vec3 from, Vec3 end,
                                           Operation<Void> original) {
        if (!explosion) stringLights$pass(shot, from, hit.getLocation());
        original.call(shot, hit, from, end);
    }

    @WrapOperation(method = "onBulletTick()V", at = @At(value = "INVOKE",
            target = "Lcom/tacz/guns/entity/EntityKineticBullet;onHitBlock(Lnet/minecraft/world/phys/BlockHitResult;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)V"))
    private void stringLights$beforeBlock(EntityKineticBullet shot, BlockHitResult hit, Vec3 from, Vec3 end,
                                          Operation<Void> original) {
        if (!explosion) stringLights$pass(shot, from, end);
        original.call(shot, hit, from, end);
    }

    @Unique private void stringLights$pass(EntityKineticBullet shot, Vec3 from, Vec3 end) {
        LightProjectiles.pass(shot, from, end, hit -> !MinecraftForge.EVENT_BUS.post(
                new AmmoHitBlockEvent(shot.level(), hit, shot.level().getBlockState(hit.getBlockPos()), shot)),
                stringLights$attempted);
    }
}
