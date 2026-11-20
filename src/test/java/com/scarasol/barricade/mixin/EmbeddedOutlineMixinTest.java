package com.scarasol.barricade.mixin;

import static org.junit.jupiter.api.Assertions.*;

import com.scarasol.barricade.block.EmbeddedCollision;
import com.scarasol.barricade.sampling.MemoryFixture;
import com.scarasol.barricade.sampling.MemoryLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.Test;

class EmbeddedOutlineMixinTest {
    @Test void onlyTheEmbeddedRodDrawsAOneBlockOutline() {
        var memory = MemoryFixture.create(true);
        var embedded = new BlockPos(11, -60, 8);
        class Level extends MemoryLevel implements EmbeddedCollision.LevelAccess {
            Level() {
                super(memory.registryAccess(), memory.dimensionTypeRegistration(), memory.enabledFeatures(),
                        memory.getRecipeManager(), true);
            }

            @Override public boolean barricade$isEmbeddedAt(BlockPos pos) {
                return embedded.equals(pos);
            }
        }
        var level = new Level();
        var hook = new EmbeddedOutlineMixin() { };
        var rod = Blocks.END_ROD.defaultBlockState().setValue(EndRodBlock.FACING, Direction.EAST);
        var context = CollisionContext.empty();
        var nativeOutline = rod.getShape(level, embedded.east(), context);
        assertEquals(new AABB(0, 0, 0, 1, 1, 1),
                hook.barricade$drawEmbeddedOutline(rod, level, embedded, context,
                        args -> fail("Embedded outline must use the barricade's one-block shape")).bounds());
        assertSame(nativeOutline, hook.barricade$drawEmbeddedOutline(rod, level, embedded.east(), context,
                args -> nativeOutline));
    }
}
