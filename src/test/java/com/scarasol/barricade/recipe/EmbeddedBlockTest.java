package com.scarasol.barricade.recipe;

import static org.junit.jupiter.api.Assertions.*;

import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

class EmbeddedBlockTest {
    private static final BlockPos POS = new BlockPos(-17, -60, -1);

    @Test void embeddedCaptureUsesTheRealPlacedEntityDataAndDoesNotMutateIt() {
        var world = MemoryFixture.create(true);
        var data = new CompoundTag();
        data.putString("CustomName", "{\"text\":\"embedded native model\"}");
        world.context(Map.of(POS, Blocks.CHEST.defaultBlockState()), Map.of(POS, data));
        var entity = world.getBlockEntity(POS);
        var before = entity.saveWithoutMetadata();
        var embedded = new EmbeddedBlock(POS, 7, 1);
        var model = embedded.model(world);
        assertEquals(before, model.parts().get(0).blockEntityData());
        assertEquals(before, entity.saveWithoutMetadata());
        assertSame(world, entity.getLevel());
        assertEquals(POS, entity.getBlockPos());
        assertEquals(0, world.chunkRequests());
    }
}
