package com.scarasol.barricade.mixin;

import com.scarasol.barricade.client.BarricadeRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class EmbeddedBlockEntityUpdateMixin {
    @Inject(method = "handleBlockEntityData(Lnet/minecraft/network/protocol/game/ClientboundBlockEntityDataPacket;)V",
            at = @At("TAIL"))
    private void barricade$refreshEmbeddedBlockEntity(ClientboundBlockEntityDataPacket packet, CallbackInfo callback) {
        BarricadeRenderer.invalidateEmbedded(packet.getPos());
    }
}
