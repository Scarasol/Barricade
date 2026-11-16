package com.scarasol.barricade.mixin;

import com.scarasol.barricade.window.client.WindowBoardsClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
abstract class WindowBoardsClientLevelMixin {
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void barricade$windowBlockChanged(BlockPos pos, BlockState previous, BlockState state, CallbackInfo callback) {
        if (previous != state) WindowBoardsClient.blockChanged((ClientLevel) (Object) this, pos);
    }
    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void barricade$windowChunkLoaded(ChunkPos pos, CallbackInfo callback) {
        WindowBoardsClient.chunkChanged((ClientLevel) (Object) this, pos);
    }
    @Inject(method = "unload", at = @At("TAIL"))
    private void barricade$windowChunkUnloaded(LevelChunk chunk, CallbackInfo callback) {
        WindowBoardsClient.chunkChanged((ClientLevel) (Object) this, chunk.getPos());
    }
}
