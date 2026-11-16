package com.scarasol.barricade.pile;

import com.scarasol.barricade.block.BarricadeBlock;
import com.scarasol.barricade.recipe.PaletteSpec;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Rediscover current block membership, not historical layouts or generated pieces. */
final class PileRecovery {
    private PileRecovery() { }

    static void rebuild(ChunkAccess chunk, PileData data, Function<String, PaletteSpec> resolve, RandomSource random) {
        for (BlockPos pos : data.cellsInChunk(chunk.getPos().toLong()))
            if (!(chunk.getBlockState(pos).getBlock() instanceof BarricadeBlock) && data.embeddedAt(pos) == null)
                data.remove(pos);

        var sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        PaletteSpec defaults = null;
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.getBlock() instanceof BarricadeBlock)) continue;
            int minY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (!(section.getBlockState(x, y, z).getBlock() instanceof BarricadeBlock)) continue;
                BlockPos pos = new BlockPos(minX + x, minY + y, minZ + z);
                if (data.contains(pos)) continue;
                if (defaults == null) defaults = resolve.apply(PileModels.DEFAULT_TAG);
                data.add(pos, UUID.randomUUID(), random.nextLong(), defaults);
            }
        }
    }
}
