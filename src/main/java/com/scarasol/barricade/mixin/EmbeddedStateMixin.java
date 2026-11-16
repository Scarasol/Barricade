package com.scarasol.barricade.mixin;

import com.scarasol.barricade.block.BarricadeBlock;
import com.scarasol.barricade.pile.PileData;
import com.scarasol.barricade.pile.PileService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
abstract class EmbeddedStateMixin {
    // Native embedded blocks do not dispatch BarricadeBlock callbacks. Observe committed changes only.
    @Inject(method = "onBlockStateChange(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V",
            at = @At("TAIL"))
    private void barricade$embeddedChanged(BlockPos pos, BlockState previous, BlockState state, CallbackInfo callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (previous == state || level.captureBlockSnapshots || level.restoringBlockSnapshots) return;
        if (previous.getBlock() instanceof BarricadeBlock barricade && !state.is(barricade)) {
            if (!PileService.trackingSuspended()) barricade.removed(level, pos, previous);
            return;
        }
        PileData data = PileData.loaded(level);
        if (data == null || !data.hasEmbedded() || data.embeddedAt(pos) == null) return;
        if (PileService.trackingSuspended() || level.getBlockState(pos) != state) return;
        PileService.embeddedChanged(level, data, pos, state);
    }
}
