package com.scarasol.barricade.client;

import com.scarasol.barricade.mixin.*;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;

final class RenderPasses extends RenderType {
    private static final Map<RenderType, RenderType> PARTICLES = new IdentityHashMap<>();
    private RenderPasses(RenderType original) {
        super("barricade_particles", original.format(), original.mode(), original.bufferSize(),
                original.affectsCrumbling(), original.mode() == com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, () -> {
                    original.setupRenderState();
                    var target = Minecraft.getInstance().levelRenderer.getParticlesTarget();
                    if (Minecraft.useShaderTransparency() && target != null) target.bindWrite(false);
                }, () -> {
                    original.clearRenderState();
                    Minecraft.getInstance().getMainRenderTarget().bindWrite(false);
                });
    }
    static boolean transparent(RenderType type) {
        if (type instanceof CompositeTypeAccess composite) {
            var state = (CompositeStateAccess) (Object) composite.barricade$state();
            return state.barricade$transparency() != NO_TRANSPARENCY;
        }
        return type instanceof RenderTypeAccess access && access.barricade$sort();
    }
    static RenderType particles(RenderType type) { return PARTICLES.computeIfAbsent(type, RenderPasses::new); }
    static void clear() { PARTICLES.clear(); }
}
