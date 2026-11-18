package com.scarasol.barricade.window;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;

public final class WindowBoardsBlock extends WindowSealBlock {
    public WindowBoardsBlock() {
        super(SealMaterial.WOOD, Properties.of().mapColor(MapColor.WOOD).strength(3, 3).sound(SoundType.WOOD));
    }
}
