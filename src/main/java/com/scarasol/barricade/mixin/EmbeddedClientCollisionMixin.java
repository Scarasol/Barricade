package com.scarasol.barricade.mixin;

import com.scarasol.barricade.block.EmbeddedCollision;
import com.scarasol.barricade.client.BarricadeClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientLevel.class)
abstract class EmbeddedClientCollisionMixin implements EmbeddedCollision.LevelAccess {
    @Override public boolean barricade$isEmbeddedAt(BlockPos pos) {
        return BarricadeClient.isEmbeddedAt((ClientLevel) (Object) this, pos);
    }
}
