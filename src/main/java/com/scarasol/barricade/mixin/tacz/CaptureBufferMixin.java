package com.scarasol.barricade.mixin.tacz;

import com.scarasol.barricade.client.CaptureBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.tacz.guns.util.RenderHelper", remap = false)
public abstract class CaptureBufferMixin {
    @Inject(method = "getBufferSource", at = @At("HEAD"), cancellable = true)
    private static void barricade$captureBuffer(ItemDisplayContext context,
                                                CallbackInfoReturnable<MultiBufferSource> callback) {
        MultiBufferSource capture = CaptureBuffers.current();
        if (capture != null) callback.setReturnValue(capture);
    }
}
