package com.scarasol.barricade.mixin.simplebedrockmodel;

import com.scarasol.barricade.client.CaptureBuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Pseudo
@Mixin(targets = "com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedGeometryChunkRenderer",
        remap = false)
public abstract class CaptureCullingMixin {
    @ModifyVariable(method = "renderQuadChunk(Lcom/github/mcmodderanchor/simplebedrockmodel/v2/common/model/baked/"
            + "BakedGeometryChunk;Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFFZ)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private boolean barricade$captureAllFaces(boolean disableCull) {
        return disableCull || CaptureBuffers.capturing();
    }
}
