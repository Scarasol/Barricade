package com.scarasol.barricade.window;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;

public final class WindowBarsBlock extends WindowSealBlock {
    public WindowBarsBlock() {
        super(SealMaterial.IRON, Properties.of().mapColor(MapColor.METAL).strength(6, 6).sound(SoundType.METAL)
                .requiresCorrectToolForDrops());
    }
}
