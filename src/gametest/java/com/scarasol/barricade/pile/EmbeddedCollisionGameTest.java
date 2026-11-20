package com.scarasol.barricade.pile;

import com.scarasol.barricade.Barricade;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("barricade")
@PrefixGameTestTemplate(false)
public final class EmbeddedCollisionGameTest {
    @GameTest(template = "frontline_empty")
    public static void embeddedEastFacingEndRodBlocksTheCellAndTheHalfBlockAbove(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos host = helper.absolutePos(new BlockPos(1, 2, 1)), ordinary = host.east(3);
        var rod = Blocks.END_ROD.defaultBlockState().setValue(EndRodBlock.FACING, Direction.EAST);
        level.setBlockAndUpdate(host, Barricade.BARRICADE.get().defaultBlockState());
        var embedded = PileData.get(level).addEmbedded(host);
        helper.assertTrue(embedded != null, "The host must have registered embedding metadata");
        try (var ignored = PileService.suspendTracking()) {
            level.setBlockAndUpdate(host, rod);
        }
        level.setBlockAndUpdate(ordinary, rod);
        var bounds = new AABB(0, 0, 0, 1, 1.5, 1);
        helper.assertTrue(rod.getCollisionShape(level, host).bounds().equals(bounds), "Cached collision overload");
        helper.assertTrue(rod.getCollisionShape(level, host, CollisionContext.empty()).bounds().equals(bounds),
                "Contextual collision overload");
        var upperCorner = new AABB(.1, 1.125, .1, .3, 1.375, .3);
        helper.assertTrue(!level.noCollision(upperCorner.move(host)), "Upper half must reach the entity collision scan");
        helper.assertTrue(level.noCollision(upperCorner.move(ordinary)), "Ordinary end rod must not gain collision");
        helper.assertTrue(level.noCollision(upperCorner.move(host.above())), "No collision above 1.5 blocks");
        var navigation = new PathNavigationRegion(level, host.offset(-2, -2, -2), host.offset(2, 2, 2));
        helper.assertTrue(!navigation.noCollision(upperCorner.move(host)), "Navigation has the same collision");
        helper.assertTrue(rod.getCollisionShape(level, ordinary).bounds().equals(rod.getShape(level, host).bounds()),
                "Ordinary collision and the embedded selection outline must still match the native end rod");
        helper.succeed();
    }
}
