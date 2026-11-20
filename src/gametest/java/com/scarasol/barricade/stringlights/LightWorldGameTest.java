package com.scarasol.barricade.stringlights;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickItem;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("barricade")
@PrefixGameTestTemplate(false)
public final class LightWorldGameTest {
    private static final String TEMPLATE = "string_lights_empty";
    private LightWorldGameTest() { }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void installedLightsConsumeFeAndLightTheirActualCell(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = LightWorld.get(level);
        BlockPos boxPos = helper.absolutePos(new BlockPos(3, 4, 4));
        level.setBlockAndUpdate(boxPos, StringLights.BOX.get().defaultBlockState()
                .setValue(PowerBoxBlock.FACE, Direction.SOUTH));
        var box = (PowerBoxEntity) level.getBlockEntity(boxPos);
        var fe = box.getCapability(ForgeCapabilities.ENERGY, null).orElseThrow(AssertionError::new);
        for (var side : Direction.values()) {
            helper.assertTrue(fe == box.getCapability(ForgeCapabilities.ENERGY, side).orElse(null), "All FE faces share one storage");
        }
        helper.assertTrue(fe.getEnergyStored() == 0 && !box.getBlockState().getValue(PowerBoxBlock.ON), "New box is empty and off");
        helper.assertTrue(fe.receiveEnergy(1010, true) == 1000 && fe.getEnergyStored() == 0, "FE simulation does not charge");
        fe.receiveEnergy(20, false);
        helper.runAfterDelay(2, () -> {
            var start = world.graph.box(boxPos);
            helper.assertTrue(start != null, "Box registered itself");
            var end = node(helper, 7, 4, 4);
            var curve = new LightCurve(start.anchor, end.anchor);
            helper.assertTrue(world.join(builder(helper), start, end, curve), "Single span placement succeeded");
            BlockPos cell = curve.bulbs().get(0).cell();
            // GameTest markers and nearby test fixtures can emit their own block light.
            for (Direction face : Direction.values()) level.setBlockAndUpdate(cell.relative(face), Blocks.STONE.defaultBlockState());
            helper.assertTrue(level.getBlockState(cell).is(StringLights.BULB.get()), "Lamp is a real block");
            helper.assertTrue(!level.getBlockState(cell).getValue(BulbBlock.LIT), "Off lamps do not generate light");
            level.setBlockAndUpdate(boxPos, box.getBlockState().setValue(PowerBoxBlock.ON, true));
            helper.runAfterDelay(23, () -> {
                helper.assertTrue(fe.getEnergyStored() == 18, "Each working tick cost one twentieth of an FE");
                helper.assertTrue(level.getBrightness(LightLayer.BLOCK, cell) == 15, "Lamp produces real level-15 block light");
                level.setBlockAndUpdate(boxPos, box.getBlockState().setValue(PowerBoxBlock.ON, false));
                helper.succeedWhen(() -> {
                    helper.assertTrue(level.getBlockState(cell).is(StringLights.BULB.get()), "Switching off preserves the bulb");
                    helper.assertFalse(level.getBlockState(cell).getValue(BulbBlock.LIT), "The preserved bulb was switched off");
                    helper.assertTrue(fe.getEnergyStored() == 18, "Off box has no idle draw");
                    int brightness = level.getBrightness(LightLayer.BLOCK, cell);
                    helper.assertTrue(brightness == 0, "Stopped lamp retracts world light, found " + brightness);
                });
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void commandBoxPowersLightsWithoutSpendingFe(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = LightWorld.get(level);
        BlockPos boxPos = helper.absolutePos(new BlockPos(3, 4, 4));
        String coords = boxPos.getX() + " " + boxPos.getY() + " " + boxPos.getZ();
        var server = level.getServer();
        var commands = server.getCommands();
        var source = server.createCommandSourceStack().withLevel(level).withSuppressedOutput();
        helper.assertTrue(commands.performPrefixedCommand(source, "setblock " + coords
                + " barricade:string_light_box[facing=south]{infiniteEnergy:1b}") == 1, "Command placed the infinite box");
        var box = (PowerBoxEntity) level.getBlockEntity(boxPos);
        var fe = box.getCapability(ForgeCapabilities.ENERGY).orElseThrow(AssertionError::new);
        helper.assertTrue(box.saveWithoutMetadata().getBoolean("infiniteEnergy"), "The flag is persisted");
        helper.assertFalse(box.getUpdateTag().contains("infiniteEnergy"), "The flag is not synced to the client");
        helper.runAfterDelay(2, () -> {
            var start = world.graph.box(boxPos);
            var end = node(helper, 7, 4, 4);
            var curve = new LightCurve(start.anchor, end.anchor);
            helper.assertTrue(world.join(builder(helper), start, end, curve), "Single span placement succeeded");
            BlockPos cell = curve.bulbs().get(0).cell();
            helper.runAfterDelay(3, () -> {
                helper.assertFalse(level.getBlockState(cell).getValue(BulbBlock.LIT), "An off box cannot power lights");
                level.setBlockAndUpdate(boxPos, box.getBlockState().setValue(PowerBoxBlock.ON, true));
                helper.runAfterDelay(4, () -> {
                    helper.assertTrue(level.getBlockState(cell).getValue(BulbBlock.LIT) && fe.getEnergyStored() == 0,
                            "An enabled infinite box powers the lamp while empty");
                    helper.assertFalse(fe.canExtract(), "The box cannot export energy");
                    fe.receiveEnergy(20, false);
                    helper.runAfterDelay(22, () -> {
                        helper.assertTrue(fe.getEnergyStored() == 20, "Inexhaustible supply preserves stored energy");
                        helper.assertTrue(commands.performPrefixedCommand(source,
                                "data modify block " + coords + " infiniteEnergy set value 0b") == 1, "Command disabled the flag");
                        helper.runAfterDelay(22, () -> {
                            helper.assertTrue(level.getBlockState(cell).getValue(BulbBlock.LIT) && fe.getEnergyStored() == 18,
                                    "Disabling the flag restores normal FE consumption");
                            helper.succeed();
                        });
                    });
                });
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void arrowContinuesToWallButSnowballFinishesItsNormalHit(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = LightWorld.get(level);
        var a = node(helper, 3, 4, 5);
        var b = node(helper, 7, 4, 5);
        var c = node(helper, 3, 4, 8);
        var d = node(helper, 7, 4, 8);
        world.graph.add(a);
        world.graph.add(c);
        world.spatial.add(a);
        world.spatial.add(c);
        var player = builder(helper);
        helper.assertTrue(world.join(player, a, b, new LightCurve(a.anchor, b.anchor)), "First span placed");
        helper.assertTrue(world.join(player, c, d, new LightCurve(c.anchor, d.anchor)), "Second span placed");
        var first = a.edges.iterator().next();
        var second = c.edges.iterator().next();
        Vec3 p = first.curve.bulbs().get(0).glass().getCenter();
        Vec3 q = second.curve.bulbs().get(0).glass().getCenter();
        BlockPos wall = BlockPos.containing(p.add(0, 0, 1.5));
        level.setBlock(wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        var arrow = new Arrow(level, p.x, p.y, p.z - 1.35);
        arrow.setDeltaMovement(0, 0, 4);
        level.addFreshEntity(arrow);
        var snowball = new Snowball(level, q.x, q.y, q.z + 1.25);
        snowball.setDeltaMovement(0, 0, -3);
        level.addFreshEntity(snowball);
        helper.runAfterDelay(4, () -> {
            helper.assertTrue(first.broken(0), "Arrow hit broke the first lamp");
            helper.assertTrue(second.broken(0), "Snowball hit broke the second lamp");
            helper.assertTrue(!arrow.isRemoved() && arrow.getZ() > p.z && arrow.getZ() <= wall.getZ() + 0.02,
                    "Arrow survived and embedded in the wall behind the lamp");
            helper.assertTrue(snowball.isRemoved(), "Snowball ended at its normal lamp impact");
            helper.assertTrue(world.graph.span(first.id) == first && world.graph.span(second.id) == second,
                    "Destroying lamps never cuts either span");
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void glassSelectionAndPlayerArrowShareTheExpandedTarget(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = LightWorld.get(level);
        var player = builder(helper);
        var grazed = install(helper, world, player, 5);
        var socketOnly = install(helper, world, player, 9);
        var behindWall = install(helper, world, player, 12);
        var bulb = grazed.curve.bulbs().get(0);
        var target = bulb.glass().getCenter().add(1.0 / 16, 0, 0);
        var from = target.add(0, -0.1, -1.5);
        var to = from.add(0, 0, 3);
        var hit = LightTargets.closerBulb(level, from, BlockHitResult.miss(to, Direction.NORTH, BlockPos.containing(to)));
        helper.assertTrue(hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(bulb.cell()),
                "The same grazing path can select the lamp");
        level.setBlockAndUpdate(BlockPos.containing(target).south(2), Blocks.STONE.defaultBlockState());
        var first = launchLikeBow(player, target.add(0, 0, -1.5));
        // The vanilla arrow starts 0.1 blocks below the player's eye. Aim high enough that
        // this one passes through the socket, rather than the glass below it.
        var socket = socketOnly.curve.bulbs().get(0).socket().getCenter();
        var second = launchLikeBow(player, socket.add(0, 0.1, -1.5));
        var shielded = behindWall.curve.bulbs().get(0).glass().getCenter();
        level.setBlockAndUpdate(BlockPos.containing(shielded).north(), Blocks.STONE.defaultBlockState());
        launchLikeBow(player, shielded.add(0, 0.1, -1.6));
        helper.runAfterDelay(4, () -> {
            helper.assertTrue(grazed.broken(0), "A player arrow grazing the glass ought to break it");
            helper.assertFalse(socketOnly.broken(0), "Passing through the socket alone must leave the glass intact");
            helper.assertFalse(behindWall.broken(0), "A nearer wall must shield the glass from the arrow");
            helper.assertTrue(!first.isRemoved() && !second.isRemoved(), "Neither arrow was consumed by a lamp");
            helper.succeed();
        });
    }

    private static Arrow launchLikeBow(ServerPlayer shooter, Vec3 eye) {
        shooter.setPos(eye.x, eye.y - shooter.getEyeHeight(), eye.z);
        var arrow = new Arrow(shooter.level(), shooter);
        // Match a full bow draw, removing only the random spread for reproducibility.
        arrow.shootFromRotation(shooter, 0, 0, 0, 3, 0);
        shooter.level().addFreshEntity(arrow);
        return arrow;
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void wireClickRepairsTheVisibleSocket(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = LightWorld.get(level);
        var player = builder(helper);
        var span = install(helper, world, player, 5);
        var other = install(helper, world, player, 8);
        var bulb = span.curve.bulbs().get(0);
        helper.assertTrue(world.damage(new LightSpatial.Lamp(span, 0), true), "The first bulb was broken");
        helper.assertTrue(world.damage(new LightSpatial.Lamp(other, 0), true), "The other bulb was broken");
        helper.assertFalse(level.getBlockState(bulb.cell()).is(StringLights.BULB.get()),
                "The empty socket does not occupy a block");

        Vec3 socket = bulb.socket().getCenter();
        Vec3 eye = socket.add(0, 0, -2);
        player.moveTo(eye.x, eye.y - player.getEyeHeight(), eye.z, 0, 0);
        BlockPos wall = BlockPos.containing(socket).north();
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        helper.assertTrue(LightTargets.pick(player, LightTargets.ray(player), LightTargets.Tool.WIRE) == null,
                "An intervening wall hides the repair target");
        level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());

        var target = LightTargets.pick(player, LightTargets.ray(player), LightTargets.Tool.WIRE);
        helper.assertTrue(target != null && span.id.equals(target.span()) && target.bulb() == 0,
                "Aiming at the socket selects its missing bulb");
        int before = LightTools.count(player);
        LightTools.useItem(new RightClickItem(player, InteractionHand.MAIN_HAND));
        helper.assertFalse(span.broken(0), "Clicking the socket repaired the bulb");
        helper.assertTrue(other.broken(0), "The other bulb was not repaired");
        helper.assertTrue(LightTools.count(player) == before - 1, "Repair consumed exactly one wire");
        helper.assertTrue(LightTargets.pick(player, LightTargets.ray(player), LightTargets.Tool.WIRE) == null,
                "The repaired bulb is no longer a repair target");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void cancelledBuildRollsBackAndWaterSurvivesTheLastBulb(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = LightWorld.get(level);
        var player = builder(helper);
        var a = node(helper, 3, 4, 5);
        var b = node(helper, 7, 4, 5);
        world.graph.add(a);
        world.spatial.add(a);
        var curve = new LightCurve(a.anchor, b.anchor);
        BlockPos lamp = curve.bulbs().get(0).cell();
        level.setBlockAndUpdate(lamp, Blocks.WATER.defaultBlockState());
        var cancel = new CancelPlacement(lamp);
        var bus = net.minecraftforge.common.MinecraftForge.EVENT_BUS;
        bus.register(cancel);
        try {
            helper.assertFalse(world.join(player, a, b, curve), "Protection rejected the proposed placement");
        } finally {
            bus.unregister(cancel);
        }
        helper.assertTrue(a.edges.isEmpty() && world.graph.node(b.id) == null, "Cancelled build installed no span or hanger");
        helper.assertTrue(level.getBlockState(lamp).is(Blocks.WATER), "Rollback preserved the original water");
        helper.assertTrue(world.join(player, a, b, curve), "Allowed placement succeeded after cancellation");
        helper.assertTrue(level.getBlockState(lamp).getValue(BulbBlock.WATER), "Installed bulb retained water");
        var span = a.edges.iterator().next();
        helper.assertTrue(world.cut(span) == curve.cost(), "Undamaged span returned its exact material cost");
        helper.assertTrue(level.getBlockState(lamp).is(Blocks.WATER), "Removing the last bulb preserved water");
        helper.succeed();
    }

    public static final class CancelPlacement {
        private final BlockPos pos;
        CancelPlacement(BlockPos pos) { this.pos = pos; }

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void protect(net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event) {
            if (event.getPos().equals(pos)) event.setCanceled(true);
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void bothGunAdaptersBreakGlassWithoutEndingOrdinaryBullets(GameTestHelper helper) {
        var world = LightWorld.get(helper.getLevel());
        var player = builder(helper);
        LightGraph.Span tacSpan = install(helper, world, player, 4);
        LightGraph.Span superbSpan = install(helper, world, player, 8);
        Vec3 tacGlass = tacSpan.curve.bulbs().get(0).glass().getCenter().add(1.0 / 8, 0, 0);
        Vec3 superbGlass = superbSpan.curve.bulbs().get(0).glass().getCenter().add(1.0 / 8, 0, 0);

        var tac = new TaczProbe(helper.getLevel());
        tac.setPos(tacGlass.x, tacGlass.y, tacGlass.z - 1.25);
        tac.setDeltaMovement(0, 0, 3);
        tac.bulletTick();
        helper.assertTrue(tacSpan.broken(0) && !tac.isRemoved(), "TACZ grazed the glass without losing the bullet");

        var superb = new com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity(helper.getLevel());
        superb.setPos(superbGlass.x, superbGlass.y, superbGlass.z - 1.25);
        superb.setDeltaMovement(0, 0, 3);
        superb.tick();
        helper.assertTrue(superbSpan.broken(0) && !superb.isRemoved(),
                "Superb Warfare grazed the glass without losing the bullet");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void cancelledHitscanDoesNotBreakGlassAndConfirmedRayDoes(GameTestHelper helper) {
        var world = LightWorld.get(helper.getLevel());
        var player = builder(helper);
        LightGraph.Span span = install(helper, world, player, 5);
        Vec3 glass = span.curve.bulbs().get(0).glass().getCenter();
        Vec3 from = glass.add(0, 0, -1.5), to = glass.add(0, 0, 1.5);
        helper.assertTrue(LightProjectiles.hitscan(player, from, to, hit -> false) == 0 && !span.broken(0),
                "Rejected source event left lamp intact");
        helper.assertTrue(LightProjectiles.hitscan(player, from, to, hit -> true) == 1 && span.broken(0),
                "Accepted source ray broke one lamp");
        helper.assertTrue(LightProjectiles.hitscan(player, from, to, hit -> true) == 0, "One lamp cannot break twice");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void sharedCellUsesIndependentSourcesAndSurvivesOneBrokenBulb(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = LightWorld.get(level);
        var player = builder(helper);
        var first = install(helper, world, player, 5);
        var second = install(helper, world, player, 5);
        BlockPos a = helper.absolutePos(new BlockPos(3, 4, 5));
        BlockPos b = helper.absolutePos(new BlockPos(7, 4, 5));
        var boxA = placeBox(helper, a, Direction.UP);
        var boxB = placeBox(helper, b, Direction.UP);
        world.placed(boxA, first.a.id);
        world.placed(boxB, second.b.id);
        boxA.getCapability(ForgeCapabilities.ENERGY).ifPresent(fe -> fe.receiveEnergy(100, false));
        boxB.getCapability(ForgeCapabilities.ENERGY).ifPresent(fe -> fe.receiveEnergy(100, false));
        level.setBlockAndUpdate(a, boxA.getBlockState().setValue(PowerBoxBlock.ON, true));
        level.setBlockAndUpdate(b, boxB.getBlockState().setValue(PowerBoxBlock.ON, true));
        BlockPos cell = first.curve.bulbs().get(0).cell();
        helper.runAfterDelay(7, () -> {
            helper.assertTrue(a.equals(first.a.source()) && b.equals(second.a.source()), "The same cell did not merge the sources");
            helper.assertTrue(boxA.demand() == 1 && boxB.demand() == 1, "Each source billed its own bulb");
            helper.assertTrue(level.getBrightness(LightLayer.BLOCK, cell) == 15, "The shared cell emitted light");
            helper.assertTrue(world.damage(new LightSpatial.Lamp(first, 0), true), "One identified bulb broke");
            helper.assertTrue(!second.broken(0) && level.getBlockState(cell).getValue(BulbBlock.LIT),
                    "The other source kept the shared cell lit");
            level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
            helper.runAfterDelay(6, () -> {
                helper.assertTrue(first.broken(0) && second.broken(0), "Replacing the block broke every remaining bulb in the cell");
                helper.assertTrue(world.graph.span(first.id) == first && world.graph.span(second.id) == second,
                        "Replacing bulbs does not cut either span");
                helper.assertTrue(level.getBlockState(cell).is(Blocks.STONE), "Deferred lighting kept the replacing block");
                helper.assertTrue(!world.repair(player, new LightSpatial.Lamp(second, 0)), "Repair would not displace the replacement");
                helper.assertTrue(boxA.demand() == 0 && boxB.demand() == 0, "Neither source billed the broken bulbs");
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void structuresCopyCompleteLightsWithIndependentWiring(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = LightWorld.get(level);
        var player = builder(helper);
        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos boxPos = origin.offset(1, 3, 2);
        var box = placeBox(helper, boxPos, Direction.EAST);
        helper.runAfterDelay(3, () -> {
            var source = world.graph.box(boxPos);
            var end = node(helper, 12, 5, 4);
            var curve = new LightCurve(source.anchor, end.anchor);
            helper.assertTrue(world.join(player, source, end, curve), "The source span was installed");
            var old = source.edges.iterator().next();
            helper.assertTrue(old.curve.bulbs().size() == 2, "The source has two bulbs");
            world.damage(new LightSpatial.Lamp(old, 0), true);
            box.getCapability(ForgeCapabilities.ENERGY).ifPresent(fe -> fe.receiveEnergy(120, false));
            var boxTag = box.saveWithoutMetadata();
            boxTag.putBoolean("infiniteEnergy", true);
            box.load(boxTag);

            String name = "barricade:game_test/string_lights_copy";
            BlockPos savePos = origin.below();
            level.setBlockAndUpdate(savePos, Blocks.STRUCTURE_BLOCK.defaultBlockState());
            var saver = (StructureBlockEntity) level.getBlockEntity(savePos);
            saver.setMode(StructureMode.SAVE);
            saver.setStructureName(name);
            saver.setStructurePos(BlockPos.ZERO.above());
            saver.setStructureSize(new Vec3i(13, 5, 5));
            saver.setIgnoreEntities(true);
            helper.assertTrue(saver.saveStructure(true), "The structure block saved the template to disk");
            saver.unloadStructure();

            var partial = new StructureTemplate();
            partial.fillFromWorld(level, origin, new Vec3i(6, 5, 5), false, Blocks.STRUCTURE_VOID);
            helper.assertFalse(partial.save(new net.minecraft.nbt.CompoundTag()).contains("barricade:string_lights"),
                    "A partial span is not exported");
            BlockPos target = helper.absolutePos(new BlockPos(22, 2, 20));
            BlockPos loadPos = target.below();
            level.setBlockAndUpdate(loadPos, Blocks.STRUCTURE_BLOCK.defaultBlockState());
            var loader = (StructureBlockEntity) level.getBlockEntity(loadPos);
            loader.setMode(StructureMode.LOAD);
            loader.setStructureName(name);
            loader.setStructurePos(BlockPos.ZERO.above());
            loader.setMirror(Mirror.FRONT_BACK);
            loader.setRotation(Rotation.CLOCKWISE_90);
            var settings = new StructurePlaceSettings().setMirror(loader.getMirror()).setRotation(loader.getRotation());
            BlockPos copiedBoxPos = StructureTemplate.calculateRelativePosition(settings, boxPos.subtract(origin)).offset(target);
            helper.assertTrue(loader.loadStructure(level, false), "The structure block loaded the saved template");
            var copiedSource = world.graph.box(copiedBoxPos);
            helper.assertTrue(copiedSource != null && !copiedSource.id.equals(source.id)
                    && copiedSource.face == Direction.NORTH && copiedSource.edges.size() == 1,
                    "The transformed power box owns an independent span");
            var copy = copiedSource.edges.iterator().next();
            int sourceBulb = copy.curve.start().equals(copiedSource.anchor) ? 0 : 1;
            helper.assertTrue(!copy.id.equals(old.id) && copy.broken(sourceBulb) && !copy.broken(1 - sourceBulb),
                    "The damaged bulb stays at the same end relative to the box");
            var copiedBox = (PowerBoxEntity) level.getBlockEntity(copiedBoxPos);
            helper.assertTrue(copiedBox.displayedEnergy() == 120 && copiedBox.saveWithoutMetadata().getBoolean("infiniteEnergy")
                            && level.getBlockState(copy.curve.bulbs().get(1 - sourceBulb).cell()).is(StringLights.BULB.get()),
                    "Energy, infinite mode and the intact bulb were restored");
            helper.assertTrue(source.edges.contains(old) && old.broken(0) && boxPos.equals(source.source()),
                    "The original installation was not modified");
            helper.assertTrue(loader.loadStructure(level, false), "The structure block loaded the template again");
            var pastedAgain = world.graph.box(copiedBoxPos);
            helper.assertTrue(pastedAgain.edges.size() == 1 && world.graph.span(copy.id) == null,
                    "Pasting again replaces the copied span instead of doubling it");
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void mountingAtBEndsTheOriginalSpanAtTheBoxOutlet(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = LightWorld.get(level);
        var player = builder(helper);
        var span = install(helper, world, player, 5);
        var mount = span.a;
        var curve = span.curve;
        BlockPos pos = mount.surface.relative(mount.face);
        Vec3 outlet = PowerBoxBlock.outlet(pos, mount.face);
        world.damage(new LightSpatial.Lamp(span, 0), true);
        var box = placeBox(helper, pos, mount.face);
        world.placed(box, mount.id);
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(world.graph.box(pos) == mount && world.graph.span(span.id) == span,
                    "The box reused the original junction and span");
            boolean start = curve.start().equals(mount.anchor);
            helper.assertTrue(span.cable().point(start ? 0 : 1).equals(outlet),
                    "The wire ends at the standard outlet instead of the old hanger");
            double unchanged = start ? curve.startLead() : curve.endLead();
            helper.assertTrue(span.cable().point(unchanged).equals(curve.point(unchanged)) && span.broken(0),
                    "The lead rejoins the original curve before the lamp and preserves damage");
            var section = world.spatial.sections.get(LightSpatial.chunk(pos));
            var snapshot = LightView.read(LightView.write(world, section), (id, tag) -> LightView.readCurve(tag));
            helper.assertTrue(snapshot.spans().get(span.id).cable().point(start ? 0 : 1).equals(outlet),
                    "The client snapshot uses the same outlet");
            var restored = LightData.load(LightData.get(level).save(new net.minecraft.nbt.CompoundTag())).graph.span(span.id);
            helper.assertTrue(restored.cable().point(start ? 0 : 1).equals(outlet) && restored.broken(0),
                    "The outlet is derived from the saved attachment without moving any lamps");
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            helper.assertTrue(span.cable().point(start ? 0 : 1).equals(mount.anchor) && span.broken(0),
                    "Removing the box restores the original hanger and keeps the damage");
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void replacingSourceReusesItsOutletButNotItsEnergyOrDamage(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = LightWorld.get(level);
        var player = builder(helper);
        BlockPos pos = helper.absolutePos(new BlockPos(3, 4, 4));
        var old = placeBox(helper, pos, Direction.SOUTH);
        var oldCap = old.getCapability(ForgeCapabilities.ENERGY);
        oldCap.ifPresent(fe -> fe.receiveEnergy(250, false));
        helper.runAfterDelay(3, () -> {
            var outlet = world.graph.box(pos);
            var end = node(helper, 9, 4, 4);
            helper.assertTrue(world.join(player, outlet, end, new LightCurve(outlet.anchor, end.anchor)), "Span was installed");
            var span = outlet.edges.iterator().next();
            helper.assertTrue(world.damage(new LightSpatial.Lamp(span, 0), true), "One bulb broke before replacing the source");
            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            helper.assertTrue(!oldCap.isPresent() && outlet.source() == null, "Breaking the source invalidated FE and disconnected power");
            helper.assertTrue(world.graph.span(span.id) == span, "Breaking the source did not cut the span");
            var replacement = placeBox(helper, pos, Direction.SOUTH);
            helper.runAfterDelay(3, () -> {
                helper.assertTrue(world.graph.box(pos) == outlet, "The replacement attached to the same outlet");
                helper.assertTrue(replacement.displayedEnergy() == 0 && !replacement.getBlockState().getValue(PowerBoxBlock.ON),
                        "The replacement was empty and switched off");
                helper.assertTrue(span.broken(0) && span.refund() == span.curve.cost() - 1, "The replacement preserved damage and refund");
                var saved = LightData.get(level).save(new net.minecraft.nbt.CompoundTag());
                var restored = LightData.load(saved).graph.span(span.id);
                helper.assertTrue(restored.broken(0) && pos.equals(restored.a.source())
                        && restored.curve.bulbs().equals(span.curve.bulbs()), "The store retained topology, damage and exact bulb positions");
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 140)
    public static void oneSourceSettlesOneHundredFiftyAndTwentyLamps(GameTestHelper helper) {
        var level = helper.getLevel();
        var world = LightWorld.get(level);
        var player = builder(helper);
        BlockPos pos = helper.absolutePos(new BlockPos(0, 7, 1));
        var box = placeBox(helper, pos, Direction.SOUTH);
        box.getCapability(ForgeCapabilities.ENERGY).ifPresent(fe -> fe.receiveEnergy(1000, false));
        helper.runAfterDelay(3, () -> {
            var previous = world.graph.box(pos);
            var lamps = new java.util.ArrayList<LightSpatial.Lamp>();
            for (int i = 0; i < 100; i++) {
                int row = i / 10, column = i % 10;
                var next = node(helper, 2 + (row % 2 == 0 ? column : 9 - column), 7, 1 + row);
                var curve = new LightCurve(previous.anchor, next.anchor);
                helper.assertTrue(world.join(player, previous, next, curve), "Dense span " + i + " was installed");
                var span = next.edges.iterator().next();
                helper.assertTrue(span.curve.bulbs().size() == 1, "Each short span gets exactly one lamp");
                lamps.add(new LightSpatial.Lamp(span, 0));
                previous = next;
            }
            level.setBlockAndUpdate(pos, box.getBlockState().setValue(PowerBoxBlock.ON, true));
            verifyDemand(helper, world, box, lamps, 100, 50, 20);
        });
    }

    private static void verifyDemand(GameTestHelper helper, LightWorld world, PowerBoxEntity box,
                                     java.util.List<LightSpatial.Lamp> lamps, int... demands) {
        if (demands.length == 0) {
            helper.succeed();
            return;
        }
        int expected = demands[0];
        for (int i = expected; i < lamps.size(); i++) world.damage(lamps.get(i), false);
        helper.runAfterDelay(2, () -> {
            int before = box.displayedEnergy();
            helper.assertTrue(box.demand() == expected && box.supplying(), "All " + expected + " actual bulbs were supplied");
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(before - box.displayedEnergy() == expected, "One second consumed exactly " + expected + " FE");
                verifyDemand(helper, world, box, lamps, java.util.Arrays.copyOfRange(demands, 1, demands.length));
            });
        });
    }

    private static PowerBoxEntity placeBox(GameTestHelper helper, BlockPos pos, Direction face) {
        helper.getLevel().setBlockAndUpdate(pos, StringLights.BOX.get().defaultBlockState().setValue(PowerBoxBlock.FACE, face));
        return (PowerBoxEntity) helper.getLevel().getBlockEntity(pos);
    }

    private static LightGraph.Span install(GameTestHelper helper, LightWorld world, ServerPlayer player, int z) {
        var a = node(helper, 3, 4, z);
        var b = node(helper, 7, 4, z);
        world.graph.add(a);
        world.spatial.add(a);
        helper.assertTrue(world.join(player, a, b, new LightCurve(a.anchor, b.anchor)), "Span placement succeeded");
        return a.edges.iterator().next();
    }

    private static final class TaczProbe extends com.tacz.guns.entity.EntityKineticBullet {
        TaczProbe(net.minecraft.world.level.Level level) { super(com.tacz.guns.init.ModEntities.BULLET.get(), level); }
        void bulletTick() { onBulletTick(); }
    }

    private static LightGraph.Node node(GameTestHelper helper, int x, int y, int z) {
        BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
        return new LightGraph.Node(UUID.randomUUID(), Vec3.atCenterOf(pos).add(0, 0.25, 0),
                pos.below(), Direction.UP, false);
    }

    private static ServerPlayer builder(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = new ServerPlayer(level.getServer(), level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "light-test"));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(StringLights.WIRE.get(), 64));
        return player;
    }
}
