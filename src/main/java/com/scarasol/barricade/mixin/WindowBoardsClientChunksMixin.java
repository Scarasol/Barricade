package com.scarasol.barricade.mixin;

import com.scarasol.barricade.window.client.WindowBoardsClient;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
abstract class WindowBoardsClientChunksMixin {
    @Shadow @Final private ClientLevel level;

    // View changes can hide retained chunks without unloading them.
    @Inject(method = {"updateViewCenter", "updateViewRadius"}, at = @At("TAIL"))
    private void barricade$windowChunkViewChanged(CallbackInfo callback) {
        WindowBoardsClient.chunksChanged(level);
    }
}
