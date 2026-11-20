package com.scarasol.barricade.block;

import com.scarasol.barricade.pile.PileData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class EmbeddedCollision {
    private EmbeddedCollision() { }

    public interface LevelAccess {
        boolean barricade$isEmbeddedAt(BlockPos pos);
    }

    public static boolean isEmbeddedAt(BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel server) {
            PileData data = PileData.loaded(server);
            return data != null && data.hasEmbedded() && data.embeddedAt(pos) != null;
        }
        return level instanceof LevelAccess access && access.barricade$isEmbeddedAt(pos);
    }

    public static VoxelShape includeBarricade(VoxelShape embedded) {
        return Shapes.or(embedded, BarricadeBlock.COLLISION_SHAPE);
    }
}
