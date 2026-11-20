package com.scarasol.barricade.client;

import com.mojang.brigadier.Command;
import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.network.PileNetwork;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = Barricade.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BarricadeClient {
    private BarricadeClient() { }
    public static boolean isEmbeddedAt(ClientLevel level, BlockPos pos) { return ClientPiles.isEmbeddedAt(level, pos); }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ClientSampleCache.initialize();
            PileNetwork.clientReceiver(ClientPiles::receive);
        });
    }
    @SubscribeEvent public static void reloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                manager -> {
                    MaterialMeshes.clear();
                    GpuLayouts.resourcesReloaded(manager);
                    ClientPiles.resourcesReloaded();
                });
    }
    @Mod.EventBusSubscriber(modid = Barricade.MOD_ID, value = Dist.CLIENT)
    public static final class Lifecycle {
        @SubscribeEvent public static void shutdown(net.minecraftforge.event.GameShuttingDownEvent event) {
            ClientPiles.logout();
            GpuLayouts.shutdown();
            ClientSampleCache.shutdown();
        }
        @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { ClientPiles.logout(); }
        @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) ClientPiles.tick();
        }
        @SubscribeEvent public static void frame(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                MaterialMeshes.frameStarted();
                BarricadeRenderer.frameStarted();
                GpuLayouts.frame();
            }
        }
        @SubscribeEvent public static void render(RenderLevelStageEvent event) { BarricadeRenderer.render(event); }
        @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
            event.getDispatcher().register(Commands.literal("barricade_client").then(
                    Commands.literal("stats").executes(context -> {
                        context.getSource().sendSuccess(() -> Component.literal(ClientPiles.stats()), false);
                        return Command.SINGLE_SUCCESS;
                    })));
        }
    }
}
