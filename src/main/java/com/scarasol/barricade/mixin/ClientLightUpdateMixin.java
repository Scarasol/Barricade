package com.scarasol.barricade.mixin;

import com.scarasol.barricade.stringlights.client.LightClient;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
abstract class ClientLightUpdateMixin {
    @Shadow @Final private ClientLevel level;

    @Inject(method = "onLightUpdate", at = @At("TAIL"))
    private void barricade$refreshLightSamples(LightLayer layer, SectionPos section, CallbackInfo ci) {
        LightClient.lightChanged(level, section.chunk().toLong());
    }
}
