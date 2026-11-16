package com.scarasol.barricade.window;

import com.scarasol.barricade.Barricade;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Barricade.MOD_ID)
public final class WindowBoardsService {
    private static final Map<ServerLevel, Set<BlockPos>> PENDING = new WeakHashMap<>();
    private static final Map<UUID, Watch> WATCHERS = new HashMap<>();
    private static final UUID RESET_ID = new UUID(0, 0);
    private static final class Watch {
        final UUID world;
        final Set<Long> chunks = new HashSet<>();
        final Map<UUID, BoardGroup> sent = new HashMap<>();
        long serial = -1;
        boolean dirty = true, reset = true;
        List<WindowBoardsNetwork.Packet> pending;
        int next;
        Map<UUID, BoardGroup> target;
        long targetSerial;
        Watch(UUID world) { this.world = world; }
    }
    private WindowBoardsService() { }
    public static void changed(ServerLevel level, BlockPos pos) {
        PENDING.computeIfAbsent(level, ignored -> new HashSet<>()).add(pos.immutable());
    }
    public static void removed(ServerLevel level, BlockPos pos) {
        WindowBoardsData data = WindowBoardsData.get(level);
        data.remove(pos);
        changed(level, pos);
    }
    private static Watch watcher(ServerPlayer player, WindowBoardsData data) {
        Watch watch = WATCHERS.get(player.getUUID());
        if (watch == null || !watch.world.equals(data.world())) {
            watch = new Watch(data.world());
            WATCHERS.put(player.getUUID(), watch);
        }
        return watch;
    }
    @SubscribeEvent public static void watch(ChunkWatchEvent.Watch event) {
        WindowBoardsData data = WindowBoardsData.get(event.getLevel());
        Watch watch = watcher(event.getPlayer(), data);
        watch.chunks.add(event.getPos().toLong());
        watch.dirty = true;
    }
    @SubscribeEvent public static void unwatch(ChunkWatchEvent.UnWatch event) {
        Watch watch = WATCHERS.get(event.getPlayer().getUUID());
        WindowBoardsData data = WindowBoardsData.get(event.getLevel());
        if (watch != null && watch.world.equals(data.world())) {
            watch.chunks.remove(event.getPos().toLong());
            watch.dirty = true;
        }
    }
    @SubscribeEvent public static void chunkLoaded(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        WindowBoardsData data = WindowBoardsData.get(level);
        collectLoaded(event.getChunk(), data, PENDING.computeIfAbsent(level, ignored -> new HashSet<>()));
    }
    static void collectLoaded(ChunkAccess chunk, WindowBoardsData data, Set<BlockPos> pending) {
        pending.addAll(data.activeInChunk(chunk.getPos().toLong()));
        var sections = chunk.getSections();
        int minX = chunk.getPos().getMinBlockX(), minZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section.hasOnlyAir() || !section.maybeHas(state -> state.getBlock() instanceof WindowSealBlock)) continue;
            int minY = chunk.getSectionYFromSectionIndex(i) << 4;
            // Discover actual blocks as well as indexed ones, including after an empty-store reset.
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                if (section.getBlockState(x, y, z).getBlock() instanceof WindowSealBlock)
                    pending.add(new BlockPos(minX + x, minY + y, minZ + z));
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        WATCHERS.remove(event.getEntity().getUUID());
    }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel server) PENDING.remove(server);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        PENDING.clear();
        WATCHERS.clear();
        WindowBoardsData.clearLoaded();
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (var entry : List.copyOf(PENDING.entrySet())) reconcile(entry.getKey(), entry.getValue());
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            WindowBoardsData data = WindowBoardsData.get(player.serverLevel());
            Watch watch = watcher(player, data);
            if (watch.pending != null || watch.dirty || watch.serial != data.serial()) synchronize(player, data, watch);
        }
    }
    private static void reconcile(ServerLevel level, Set<BlockPos> pending) {
        WindowBoardsData data = WindowBoardsData.get(level);
        List<BlockPos> batch = pending.stream().sorted().toList();
        pending.clear();
        Map<BlockPos, WindowBoardsData.Observation> observed = new LinkedHashMap<>();
        for (BlockPos pos : batch) {
            if (!level.hasChunkAt(pos)) { pending.add(pos); continue; }
            BlockState state = level.getBlockState(pos);
            observed.put(pos, state.getBlock() instanceof WindowSealBlock seal
                    ? new WindowBoardsData.Observation(state.getValue(WindowSealBlock.FACING), seal.material()) : null);
        }
        reconcile(data, observed, level.random);
    }
    static void reconcile(WindowBoardsData data, Map<BlockPos, WindowBoardsData.Observation> observed, RandomSource random) {
        data.reconcileSeals(observed);
        for (var entry : observed.entrySet()) {
            if (entry.getValue() != null) {
                WindowBoardsData.Observation seal = entry.getValue();
                WindowBoardsData.Placement placement = data.place(entry.getKey(), seal.facing(),
                        UUID.randomUUID(), random.nextLong(), seal.material());
                if (placement.geometryChanged()) captureBoundary(data, placement.id());
            }
        }
    }
    private static void captureBoundary(WindowBoardsData data, UUID id) {
        BoardGroup group = data.group(id);
        data.supports(id, BoardLayout.boundary(group.shape()));
    }
    private static void synchronize(ServerPlayer player, WindowBoardsData data, Watch watch) {
        var dimension = player.serverLevel().dimension().location();
        if (watch.reset) {
            WindowBoardsNetwork.send(player, new WindowBoardsNetwork.Packet(
                    dimension, data.world(), true, RESET_ID, new CompoundTag()));
            watch.reset = false;
        }
        if (watch.pending == null) {
            Set<UUID> visible = data.visible(watch.chunks);
            watch.target = new HashMap<>();
            for (UUID id : visible) watch.target.put(id, data.group(id));
            watch.pending = WindowBoardsUpdates.synchronize(dimension, data.world(), watch.sent, watch.target);
            watch.next = 0;
            watch.targetSerial = data.serial();
            watch.dirty = false;
        }
        int budget = 8;
        while (watch.next < watch.pending.size() && budget-- > 0)
            WindowBoardsNetwork.send(player, watch.pending.get(watch.next++));
        if (watch.next < watch.pending.size()) return;
        watch.sent.clear();
        watch.sent.putAll(watch.target);
        watch.serial = watch.targetSerial;
        watch.pending = null;
        watch.target = null;
    }
}
