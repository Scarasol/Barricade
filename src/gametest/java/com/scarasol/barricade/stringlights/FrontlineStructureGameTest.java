package com.scarasol.barricade.stringlights;

import com.scarasol.barricade.pile.PileData;
import com.scarasol.barricade.window.WindowBoardsData;
import java.util.HashSet;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("barricade")
@PrefixGameTestTemplate(false)
public final class FrontlineStructureGameTest {
    @GameTest(template = "frontline_empty", timeoutTicks = 120)
    public static void restoresCoverScene(GameTestHelper helper) {
        var level = helper.getLevel();
        var template = level.getStructureManager().get(new ResourceLocation("barricade", "frontline")).orElseThrow();
        helper.assertTrue(template.getSize().equals(new Vec3i(31, 10, 35)), "Cover template dimensions");
        var origin = helper.absolutePos(new BlockPos(1, 2, 1));
        var loadPos = origin.below();
        level.setBlockAndUpdate(loadPos, Blocks.STRUCTURE_BLOCK.defaultBlockState());
        var loader = (StructureBlockEntity) level.getBlockEntity(loadPos);
        loader.setMode(StructureMode.LOAD);
        loader.setStructureName("barricade:frontline");
        loader.setStructurePos(BlockPos.ZERO.above());
        loader.setIgnoreEntities(false);
        helper.assertTrue(loader.loadStructure(level, false), "Structure block loaded the cover");
        var bounds = new AABB(origin, origin.offset(template.getSize()));
        helper.assertTrue(level.getEntitiesOfClass(Zombie.class, bounds).size() == 2, "Two zombies");
        helper.assertTrue(level.getEntitiesOfClass(ArmorStand.class, bounds,
                stand -> stand.getMainHandItem().is(Items.BOW)).size() == 1, "Archer holding a bow");
        helper.runAfterDelay(8, () -> {
            var boxPos = origin.offset(11, 4, 25);
            var box = (PowerBoxEntity) level.getBlockEntity(boxPos);
            var source = LightWorld.get(level).graph.box(boxPos);
            helper.assertTrue(box != null && source != null && source.edges.size() == 1, "Wired feeder");
            var span = source.edges.iterator().next();
            helper.assertTrue(span.broken() == 0 && box.demand() == span.curve.bulbs().size() && box.supplying(),
                    "All cover lamps receive power");
            for (var bulb : span.curve.bulbs())
                helper.assertTrue(level.getBlockState(bulb.cell()).is(StringLights.BULB.get()), "Cover lamp cell");
            var seals = WindowBoardsData.get(level);
            var groups = new HashSet<UUID>();
            for (var p : new BlockPos[]{new BlockPos(11, 2, 25), new BlockPos(11, 2, 22), new BlockPos(12, 2, 22)})
                groups.add(seals.activeAt(origin.offset(p)));
            helper.assertTrue(!groups.contains(null) && groups.size() == 3, "Three independent window installations");
            var piles = PileData.get(level);
            for (int x = 11; x <= 19; x++)
                helper.assertTrue(piles.contains(origin.offset(x, 1, 26)), "Continuous barricade");
            helper.succeed();
        });
    }
}
