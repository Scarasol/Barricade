package com.scarasol.barricade.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.scarasol.barricade.stringlights.LightStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StructureTemplate.class)
abstract class LightStructureMixin {
    @Unique private CompoundTag stringLights$layout;

    @Inject(method = "fillFromWorld", at = @At("TAIL"))
    private void stringLights$capture(Level level, BlockPos origin, Vec3i size, boolean entities, Block ignored,
                                     CallbackInfo callback) {
        if (size.getX() < 1 || size.getY() < 1 || size.getZ() < 1) return;
        stringLights$layout = level instanceof ServerLevel server ? LightStructures.capture(server, origin, size, ignored) : null;
    }

    @WrapOperation(method = "fillFromWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState stringLights$omitDerivedBulbs(Level level, BlockPos pos, Operation<BlockState> original) {
        return LightStructures.templateState(original.call(level, pos));
    }

    @Inject(method = "save", at = @At("TAIL"))
    private void stringLights$save(CompoundTag tag, CallbackInfoReturnable<CompoundTag> callback) {
        LightStructures.write(callback.getReturnValue(), stringLights$layout);
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void stringLights$load(HolderGetter<Block> blocks, CompoundTag tag, CallbackInfo callback) {
        stringLights$layout = LightStructures.read(tag);
    }

    @Inject(method = "placeInWorld", at = @At("RETURN"))
    private void stringLights$place(ServerLevelAccessor level, BlockPos origin, BlockPos pivot,
                                    StructurePlaceSettings settings, RandomSource random, int flags,
                                    CallbackInfoReturnable<Boolean> callback) {
        if (stringLights$layout != null && callback.getReturnValueZ() && level instanceof ServerLevel server) {
            LightStructures.place(server, origin, settings, ((StructureTemplate) (Object) this).getBoundingBox(settings, origin),
                    stringLights$layout);
        }
    }
}
