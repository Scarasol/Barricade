package com.scarasol.barricade.pile;

import com.mojang.brigadier.Command;
import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.network.PileNetwork;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.SamplingResources;
import java.util.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DoubleHighBlockItem;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.*;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.world.InteractionResult;
import java.util.function.Supplier;

public final class PileService {
    private static final Map<UUID, Watch> WATCHERS = new HashMap<>();
    private static PaletteSpec palette;
    private static final Map<String, PaletteSpec> PALETTES = new HashMap<>();
    private static final Map<Level, Map<BlockPos, Placement>> PLACEMENTS = new WeakHashMap<>();
    private static long paletteRevision = -1;
    private static long transfer;
    private static final ThreadLocal<Integer> TRACKING_SUSPENDED = new ThreadLocal<>();

    private PileService() { }

    public static PaletteSpec palette() {
        if (palette == null || paletteRevision != PileModels.revision()) {
            PALETTES.clear();
            palette = PaletteSpec.create(PileModels.candidates(), SamplingResources.signature());
            paletteRevision = PileModels.revision();
        }
        return palette;
    }

    public static PaletteSpec palette(String source) {
        PaletteSpec defaults = palette();
        ResourceLocation id = ResourceLocation.tryParse(source);
        if (id == null || id.toString().equals(PileModels.DEFAULT_TAG)) return defaults;
        if (PALETTES.size() >= 256) PALETTES.clear();
        return PALETTES.computeIfAbsent(id.toString(), ignored -> PaletteSpec.create(
                PileModels.candidates(id), defaults.resources(), id.toString(), defaults));
    }

    public static String source(ItemStack stack) {
        if (!stack.hasCustomHoverName()) return PileModels.DEFAULT_TAG;
        String value = stack.getHoverName().getString().trim();
        if (value.startsWith("#")) value = value.substring(1);
        ResourceLocation id = value.indexOf(':') > 0 ? ResourceLocation.tryParse(value) : null;
        return id == null ? PileModels.DEFAULT_TAG : id.toString();
    }

    private record Placement(String source) { }
    public static final class PlacementScope implements AutoCloseable {
        private final Level level;
        private final BlockPos pos;
        private final Placement placement, previous;
        private boolean successful;
        private PlacementScope(BlockPlaceContext context) {
            level = context.getLevel(); pos = context.getClickedPos().immutable();
            placement = new Placement(source(context.getItemInHand()));
            previous = PLACEMENTS.computeIfAbsent(level, ignored -> new HashMap<>()).put(pos, placement);
        }
        public void successful() { successful = true; }
        @Override public void close() {
            Map<BlockPos, Placement> entries = PLACEMENTS.get(level);
            if (entries == null || entries.get(pos) != placement) return;
            if (successful && level.captureBlockSnapshots && !level.restoringBlockSnapshots) return;
            if (previous == null) clearPlacement(level, pos); else entries.put(pos, previous);
        }
    }
    public static PlacementScope placing(BlockPlaceContext context) { return new PlacementScope(context); }
    static String placementSource(Level level, BlockPos pos) {
        Map<BlockPos, Placement> entries = PLACEMENTS.get(level);
        Placement placement = entries == null ? null : entries.get(pos);
        clearPlacement(level, pos);
        return placement == null ? PileModels.DEFAULT_TAG : placement.source();
    }
    public static PaletteSpec placedPalette(Level level, BlockPos pos) { return palette(placementSource(level, pos)); }
    public static void clearPlacement(Level level, BlockPos pos) {
        Map<BlockPos, Placement> entries = PLACEMENTS.get(level);
        if (entries == null) return;
        entries.remove(pos);
        if (entries.isEmpty()) PLACEMENTS.remove(level);
    }

    static final class TrackingScope implements AutoCloseable {
        private boolean closed;
        private TrackingScope() {
            Integer depth = TRACKING_SUSPENDED.get();
            TRACKING_SUSPENDED.set(depth == null ? 1 : depth + 1);
        }
        @Override public void close() {
            if (closed) return;
            int depth = TRACKING_SUSPENDED.get() - 1;
            if (depth == 0) TRACKING_SUSPENDED.remove(); else TRACKING_SUSPENDED.set(depth);
            closed = true;
        }
    }

    static TrackingScope suspendTracking() { return new TrackingScope(); }
    public static boolean trackingSuspended() {
        Integer depth = TRACKING_SUSPENDED.get();
        return depth != null && depth > 0;
    }

    private static <T> T withoutTracking(Supplier<T> operation) {
        try (TrackingScope ignored = suspendTracking()) { return operation.get(); }
    }

    @SubscribeEvent public static void rightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getEntity().isShiftKeyDown()) return;
        if (!(event.getItemStack().getItem() instanceof BlockItem blockItem)
                || blockItem instanceof DoubleHighBlockItem || blockItem instanceof BedItem) return;
        BlockPos pos = event.getPos();
        if (!event.getLevel().getBlockState(pos).is(Barricade.BARRICADE.get())) return;
        if (event.getLevel().isClientSide) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        InteractionResult result = EmbeddedPlacement.place(level, event.getEntity(), event.getHand(),
                event.getHitVec(), blockItem);
        event.setCancellationResult(result);
        event.setCanceled(true);
    }

    public static void embeddedChanged(ServerLevel level, PileData data, BlockPos pos, BlockState state) {
        if (state.isAir()) {
            data.removeEmbedded(pos);
            withoutTracking(() -> level.setBlock(pos, Barricade.BARRICADE.get().defaultBlockState(), 3));
        } else if (state.is(Barricade.BARRICADE.get())) {
            data.removeEmbedded(pos);
        } else {
            data.updateEmbedded(pos);
        }
    }

    @SubscribeEvent public static void piston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        PileData data = PileData.loaded(level);
        if (data != null && data.hasEmbedded() && PistonGuard.blocks(event, data)) event.setCanceled(true);
    }

    @SubscribeEvent public static void reload(AddReloadListenerEvent event) {
        event.addListener(new PileModels());
        event.addListener(new SamplingResources());
    }
    @SubscribeEvent public static void synced(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            PileModels.tagsChanged();
            palette = null;
        }
    }
    private static final class Watch {
        final Set<Long> chunks = new HashSet<>();
        final Set<String> palettes = new HashSet<>();
        final ArrayDeque<PileNetwork.Fragment> outbound = new ArrayDeque<>();
        final UUID world;
        Map<UUID, Long> revisions = Map.of();
        long serial = -1, epoch = -1, lastResync = -1000, baseline = -1;
        boolean dirty = true;
        Watch(UUID world) { this.world = world; }
    }
    private static Watch watch(ServerPlayer player) {
        PileData data = PileData.get(player.serverLevel());
        UUID world = data.world();
        Watch found = WATCHERS.get(player.getUUID());
        if (found == null || !found.world.equals(world)) {
            found = new Watch(world);
            WATCHERS.put(player.getUUID(), found);
        }
        return found;
    }
    @SubscribeEvent public static void watch(ChunkWatchEvent.Watch event) {
        Watch watch = watch(event.getPlayer());
        watch.chunks.add(event.getPos().toLong());
        watch.dirty = true;
    }
    @SubscribeEvent public static void unwatch(ChunkWatchEvent.UnWatch event) {
        Watch watch = WATCHERS.get(event.getPlayer().getUUID());
        PileData data = PileData.loaded(event.getLevel());
        if (watch != null && data != null && watch.world.equals(data.world())) {
            watch.chunks.remove(event.getPos().toLong());
            watch.dirty = true;
        }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        WATCHERS.remove(event.getEntity().getUUID());
    }
    public static void resync(ServerPlayer player) {
        Watch watch = watch(player);
        long time = player.serverLevel().getGameTime();
        if (time - watch.lastResync < 40) return;
        watch.lastResync = time;
        watch.outbound.clear();
        watch.palettes.clear();
        watch.serial = -1;
        watch.baseline = -1;
        watch.dirty = true;
    }
    @SubscribeEvent public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        PLACEMENTS.clear();
        for (ServerPlayer player : List.copyOf(event.getServer().getPlayerList().getPlayers())) {
            PileData data = PileData.get(player.serverLevel());
            Watch watch = watch(player);
            if (watch.outbound.isEmpty() && (watch.dirty || watch.serial != data.serial())) {
                sync(player, watch, data);
            }
            for (int i = 0; i < 4 && !watch.outbound.isEmpty(); i++)
                PileNetwork.send(player, watch.outbound.removeFirst());
        }
    }
    @SubscribeEvent public static void levelLoaded(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) PileData.get(level);
    }
    @SubscribeEvent public static void chunkLoaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level)
            PileRecovery.rebuild(event.getChunk(), PileData.get(level), PileService::palette, level.random);
    }
    private static void sync(ServerPlayer player, Watch watch, PileData data) {
        Map<UUID, Long> revisions = new TreeMap<>();
        data.visible(watch.chunks).forEach(id -> revisions.put(id, data.revision(id)));
        boolean resend = watch.baseline < 0;
        boolean scopeChanged = watch.dirty;
        if (!resend && !scopeChanged && revisions.equals(watch.revisions) && watch.epoch == data.sampleEpoch()) {
            watch.serial = data.serial(); watch.dirty = false;
            return;
        }
        Set<String> rules = new TreeSet<>();
        net.minecraft.nbt.CompoundTag payload = new net.minecraft.nbt.CompoundTag();
        var removed = new net.minecraft.nbt.ListTag();
        var regions = new net.minecraft.nbt.ListTag();
        int layoutPadding = 2 + ((FieldRules.DISPLAY_RADIUS + 1) * FieldRules.REGION_SIDE + 15) / 16;
        Set<Long> visibleChunks = padded(watch.chunks, 2), maskChunks = padded(watch.chunks, layoutPadding);
        revisions.forEach((id, revision) -> {
            FieldRegion snapshot = data.snapshot(id, visibleChunks, maskChunks);
            rules.add(snapshot.definition().palette());
            if (resend || scopeChanged || !Objects.equals(watch.revisions.get(id), revision)) regions.add(snapshot.save());
        });
        for (UUID id : watch.revisions.keySet()) if (!revisions.containsKey(id)) {
            var header = new net.minecraft.nbt.CompoundTag();
            header.putUUID("Id", id); removed.add(header);
        }
        var palettes = new net.minecraft.nbt.ListTag();
        for (String id : rules) if (resend || !watch.palettes.contains(id)) palettes.add(data.palette(id).save());
        long revision = ++transfer;
        payload.putInt("Format", 2); payload.putLong("Base", resend ? -1 : watch.baseline);
        payload.putLong("Revision", revision); payload.put("Regions", regions); payload.put("Removed", removed);
        payload.put("Palettes", palettes);
        payload.putLong("Samples", data.sampleEpoch());
        try {
            watch.outbound.addAll(PileNetwork.fragment(player.level().dimension().location(), data.world(),
                    revision, payload));
            watch.palettes.retainAll(rules);
            watch.palettes.addAll(rules);
            watch.revisions = Map.copyOf(revisions);
            watch.epoch = data.sampleEpoch(); watch.baseline = revision;
            watch.serial = data.serial(); watch.dirty = false;
        } catch (RuntimeException exception) {
            Barricade.LOGGER.error("Barricade metadata exceeds transfer budget for {}", player.getGameProfile().getName(), exception);
        }
    }
    private static Set<Long> padded(Set<Long> chunks, int radius) {
        Set<Long> result = new HashSet<>();
        for (long chunk : chunks) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++)
            result.add(net.minecraft.world.level.ChunkPos.asLong(net.minecraft.world.level.ChunkPos.getX(chunk) + dx,
                    net.minecraft.world.level.ChunkPos.getZ(chunk) + dz));
        return result;
    }
    @SubscribeEvent public static void stop(ServerStoppedEvent event) {
        WATCHERS.clear();
        PileData.clearLoaded();
        palette = null;
        PALETTES.clear();
        PLACEMENTS.clear();
        paletteRevision = -1;
    }

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(Barricade.MOD_ID).requires(source -> source.hasPermission(2))
                .then(Commands.literal("stats").executes(context -> {
                    PileData data = PileData.get(context.getSource().getLevel());
                    context.getSource().sendSuccess(() -> Component.literal("Barricades: " + data.cellCount()
                            + ", regions: " + data.regionCount() + "; current-state spatial fields run on clients"), false);
                    return Command.SINGLE_SUCCESS;
                })).then(Commands.literal("resample").executes(context -> {
                    PileData.get(context.getSource().getLevel()).resample(old -> palette(old.tag()));
                    context.getSource().sendSuccess(() -> Component.literal("Explicit material/geometry revision; client fields invalidated"), false);
                    return Command.SINGLE_SUCCESS;
                })).then(Commands.literal("regenerate")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(context -> {
                            BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
                            PileData data = PileData.get(context.getSource().getLevel());
                            UUID region = data.regionAt(pos);
                            PaletteSpec selected = region == null ? palette()
                                    : palette(data.palette(data.snapshot(region).definition().palette()).tag());
                            int count = data.regenerate(pos, selected,
                                    context.getSource().getLevel().random.nextLong(), UUID.randomUUID());
                            context.getSource().sendSuccess(() -> Component.literal("Rebuilt definition for " + count + " barricade cells"), false);
                            return count > 0 ? Command.SINGLE_SUCCESS : 0;
                        }))));
    }
}
