package com.scarasol.barricade.mixin;

import com.scarasol.barricade.block.EmbeddedCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(PathNavigationRegion.class)
abstract class EmbeddedNavigationRegionMixin implements EmbeddedCollision.LevelAccess {
    @Shadow @Final protected Level level;

    @Override public boolean barricade$isEmbeddedAt(BlockPos pos) {
        return EmbeddedCollision.isEmbeddedAt(level, pos);
    }
}
