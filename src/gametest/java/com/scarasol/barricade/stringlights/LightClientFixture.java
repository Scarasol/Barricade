package com.scarasol.barricade.stringlights;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "barricade")
public final class LightClientFixture {
    private static final BlockPos ORIGIN = new BlockPos(0, 96, 0);
    private static final BlockPos BOX = ORIGIN.offset(2, 5, 3);

    private LightClientFixture() { }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("stringlights_fixture")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("build").executes(context -> build(context.getSource().getPlayerOrException())))
                .then(Commands.literal("connect").executes(context -> connect(context.getSource().getPlayerOrException())))
                .then(Commands.literal("camera").then(Commands.argument("view", IntegerArgumentType.integer(0, 3))
                        .executes(context -> camera(context.getSource().getPlayerOrException(),
                                IntegerArgumentType.getInteger(context, "view"))))));
    }

    private static int build(ServerPlayer player) {
        var level = player.serverLevel();
        var world = LightWorld.get(level);
        if (!level.hasChunkAt(BOX) || !level.hasChunkAt(ORIGIN.offset(31, 0, 17))
                || world.graph.box(BOX) != null) return 0;
        for (BlockPos pos : BlockPos.betweenClosed(ORIGIN, ORIGIN.offset(31, 0, 17))) {
            level.setBlockAndUpdate(pos, Blocks.STONE_BRICKS.defaultBlockState());
        }
        for (BlockPos pos : BlockPos.betweenClosed(ORIGIN.offset(0, 1, 2), ORIGIN.offset(31, 7, 2))) {
            level.setBlockAndUpdate(pos, Blocks.STONE_BRICKS.defaultBlockState());
        }
        for (int x : new int[] {2, 18, 30}) {
            for (int y = 1; y <= 6; y++) {
                level.setBlockAndUpdate(ORIGIN.offset(x, y, 11), Blocks.DARK_OAK_LOG.defaultBlockState());
            }
        }
        var state = StringLights.BOX.get().defaultBlockState().setValue(PowerBoxBlock.FACE, Direction.SOUTH);
        level.setBlockAndUpdate(BOX, state);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(StringLights.WIRE.get(), 64));
        player.getInventory().setItem(1, new ItemStack(Items.SHEARS));
        camera(player, 0);
        return 1;
    }

    private static int connect(ServerPlayer player) {
        var level = player.serverLevel();
        var world = LightWorld.get(level);
        var box = world.graph.box(BOX);
        if (box == null || !box.edges.isEmpty() || !(level.getBlockEntity(BOX) instanceof PowerBoxEntity entity)) return 0;
        var far = mount(18, 6, 10, Direction.NORTH);
        var middle = mount(30, 6, 10, Direction.NORTH);
        var wall = mount(27, 5, 3, Direction.SOUTH);
        var near = mount(2, 6, 10, Direction.NORTH);
        boolean success = join(world, player, box, far) && join(world, player, far, middle)
                && join(world, player, middle, wall) && join(world, player, box, near);
        entity.getCapability(ForgeCapabilities.ENERGY).ifPresent(fe -> fe.receiveEnergy(1000, false));
        level.setBlockAndUpdate(BOX, level.getBlockState(BOX).setValue(PowerBoxBlock.ON, true));
        player.sendSystemMessage(Component.literal(success ? "String lights fixture ready" : "String lights fixture placement failed"));
        camera(player, 0);
        return 1;
    }

    private static LightGraph.Node mount(int x, int y, int z, Direction face) {
        BlockPos cell = ORIGIN.offset(x, y, z);
        var anchor = Vec3.atCenterOf(cell).subtract(Vec3.atLowerCornerOf(face.getNormal()).scale(7.0 / 16));
        return new LightGraph.Node(UUID.randomUUID(), anchor, cell.relative(face.getOpposite()), face, false);
    }

    private static boolean join(LightWorld world, ServerPlayer player, LightGraph.Node a, LightGraph.Node b) {
        var curve = new LightCurve(a.anchor, b.anchor);
        return LightTargets.canBuild(player, curve) && world.join(player, a, b, curve);
    }

    private static int camera(ServerPlayer player, int view) {
        double[][] views = {{13.5, 1, 15, 177, -13}, {15.5, 1, 9.0, 158, -32},
                {15.5, 3.1, 35, 179, -2}, {2.5, 1, 6.3, 180, -46}};
        var pose = views[view];
        player.teleportTo(player.serverLevel(), pose[0], ORIGIN.getY() + pose[1], pose[2], (float) pose[3], (float) pose[4]);
        return 1;
    }
}
