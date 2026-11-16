package com.scarasol.barricade.mixin;

import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.renderer.RenderType$CompositeRenderType")
public interface CompositeTypeAccess {
    @Accessor("state") RenderType.CompositeState barricade$state();
}
