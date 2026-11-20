package com.scarasol.barricade.stringlights;

import com.scarasol.barricade.Barricade;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Barricade.MOD_ID)
public final class LightEvents {
    private LightEvents() { }

    @SubscribeEvent public static void load(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            LightWorld.get(level).chunkLoad(chunk);
        }
    }

    @SubscribeEvent public static void unload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            var world = LightWorld.current(level);
            if (world != null) world.chunkUnload(chunk);
        }
    }

    @SubscribeEvent public static void unloadLevel(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LightWorld.close(level);
    }

    @SubscribeEvent public static void watch(ChunkWatchEvent.Watch event) {
        LightWorld.get(event.getLevel()).watch(event.getPlayer(), event.getPos());
    }

    @SubscribeEvent public static void unwatch(ChunkWatchEvent.UnWatch event) {
        LightWorld.get(event.getLevel()).unwatch(event.getPlayer(), event.getPos());
    }

    @SubscribeEvent public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.level instanceof ServerLevel level) LightWorld.get(level).tick();
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            LightWorld.get(player.serverLevel()).leave(player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void neighbor(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var world = LightWorld.get(level);
            world.refreshSupport(event.getPos());
            for (var face : event.getNotifiedSides()) world.refreshSupport(event.getPos().relative(face));
        }
    }
}
