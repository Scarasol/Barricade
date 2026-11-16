package com.scarasol.barricade.window.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.client.WindowBoardsMesh;
import com.scarasol.barricade.window.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = Barricade.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class WindowBoardsClient {
    private static final Map<UUID, Entry> GROUPS = new HashMap<>();
    private static final Map<BoardGroup.LayoutKey, BoardLayout.Layout> LAYOUTS = new HashMap<>();
    private static final Map<BoardGroup.LayoutKey, Integer> SOURCE_USERS = new HashMap<>();
    private static final WindowBoardsUpdates UPDATES = new WindowBoardsUpdates();
    private static final WindowBoardsVisibility VISIBILITY = new WindowBoardsVisibility();
    private static final ResourceLocation WOOD = new ResourceLocation("minecraft", "block/oak_planks");
    private static final ResourceLocation IRON = new ResourceLocation("minecraft", "block/iron_block");
    private static final ResourceLocation BARS = new ResourceLocation(Barricade.MOD_ID, "block/window_bars");
    private static final ResourceLocation WELD = new ResourceLocation(Barricade.MOD_ID, "block/window_bars_weld");
    private static ResourceLocation dimension;
    private static UUID world;
    private static final class Entry {
        BoardGroup group;
        final BlockPos origin;
        final AABB bounds;
        final BoardLayout.Layout layout;
        final WindowBoardsVisibility.State visibility;
        Set<BoardCell> mask;
        WindowBoardsMesh mesh;
        Entry(BoardGroup group) {
            this.group = group;
            layout = LAYOUTS.computeIfAbsent(group.layoutKey(), BoardLayout::generate);
            BoardLayout.validateOwnership(group, layout);
            visibility = VISIBILITY.track(group);
            int minU = Integer.MAX_VALUE, minV = Integer.MAX_VALUE;
            int maxU = Integer.MIN_VALUE, maxV = Integer.MIN_VALUE;
            for (BoardCell cell : group.shape()) {
                minU = Math.min(minU, cell.u());
                maxU = Math.max(maxU, cell.u());
                minV = Math.min(minV, cell.v());
                maxV = Math.max(maxV, cell.v());
            }
            origin = group.plane().block(new BoardCell(minU, minV));
            BlockPos opposite = group.plane().block(new BoardCell(maxU, maxV));
            bounds = new AABB(Math.min(origin.getX(), opposite.getX()), Math.min(origin.getY(), opposite.getY()),
                    Math.min(origin.getZ(), opposite.getZ()), Math.max(origin.getX(), opposite.getX()) + 1,
                    Math.max(origin.getY(), opposite.getY()) + 1, Math.max(origin.getZ(), opposite.getZ()) + 1).inflate(0.5);
            SOURCE_USERS.merge(group.layoutKey(), 1, Integer::sum);
        }
        void updateMesh(Level level) {
            if (mesh != null && !visibility.dirty()) return;
            Set<BoardCell> hidden = visibility.hidden(pos -> {
                if (!level.hasChunkAt(pos)) return false;
                var state = level.getBlockState(pos);
                return state.getBlock() instanceof WindowSealBlock seal && seal.material() == group.material()
                        && state.getValue(WindowSealBlock.FACING) == group.plane().facing();
            });
            if (mesh != null && hidden == mask) return;
            var atlas = Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS);
            WindowBoardsMesh replacement = new WindowBoardsMesh(group.plane(), origin,
                    BoardLayout.visible(layout, group, hidden),
                    atlas.apply(group.material() == SealMaterial.IRON ? BARS : WOOD),
                    atlas.apply(group.material() == SealMaterial.IRON ? WELD : IRON));
            closeMesh();
            mesh = replacement;
            mask = hidden;
        }
        void closeMesh() {
            if (mesh != null) mesh.close();
            mesh = null;
            mask = null;
        }
    }
    private WindowBoardsClient() { }
    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> WindowBoardsNetwork.receiver(WindowBoardsClient::receive));
    }
    @SubscribeEvent public static void reloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                manager -> GROUPS.values().forEach(Entry::closeMesh));
    }
    private static void receive(WindowBoardsNetwork.Packet packet) {
        if (packet.reset() || !Objects.equals(world, packet.world()) || !Objects.equals(dimension, packet.dimension())) {
            clear();
            world = packet.world();
            dimension = packet.dimension();
        }
        if (packet.reset()) return;
        WindowBoardsUpdates.Changes changes = UPDATES.accept(packet);
        if (changes == null) return;
        for (UUID id : changes.removed()) release(GROUPS.remove(id));
        for (BoardGroup group : changes.groups()) update(group);
    }
    private static void update(BoardGroup group) {
        Entry old = GROUPS.get(group.id());
        if (old != null && old.group.revision() >= group.revision()) return;
        if (old != null && old.group.layoutKey().equals(group.layoutKey())
                && old.group.shape().equals(group.shape()) && Objects.equals(old.group.remnant(), group.remnant())) {
            old.group = group;
            old.visibility.update(group);
        } else {
            Entry replacement = new Entry(group);
            GROUPS.put(group.id(), replacement);
            release(old);
        }
    }
    private static void release(Entry entry) {
        if (entry == null) return;
        VISIBILITY.untrack(entry.visibility);
        entry.closeMesh();
        BoardGroup.LayoutKey source = entry.group.layoutKey();
        SOURCE_USERS.computeIfPresent(source, (key, count) -> count == 1 ? null : count - 1);
        if (!SOURCE_USERS.containsKey(source)) LAYOUTS.remove(source);
    }
    private static void clear() {
        GROUPS.values().forEach(Entry::closeMesh);
        GROUPS.clear();
        LAYOUTS.clear();
        SOURCE_USERS.clear();
        VISIBILITY.clear();
        UPDATES.clear();
        dimension = null;
        world = null;
    }
    private static boolean active(Level level) {
        return !GROUPS.isEmpty() && level == Minecraft.getInstance().level
                && level.dimension().location().equals(dimension);
    }
    public static void blockChanged(Level level, BlockPos pos) {
        if (active(level)) VISIBILITY.blockChanged(pos);
    }
    public static void chunkChanged(Level level, ChunkPos chunk) {
        if (active(level)) VISIBILITY.chunkChanged(chunk);
    }
    public static void chunksChanged(Level level) {
        if (active(level)) VISIBILITY.invalidateAll();
    }
    @Mod.EventBusSubscriber(modid = Barricade.MOD_ID, value = Dist.CLIENT)
    public static final class Events {
        @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }
        @SubscribeEvent public static void shutdown(net.minecraftforge.event.GameShuttingDownEvent event) { clear(); }
        @SubscribeEvent public static void render(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
            Minecraft minecraft = Minecraft.getInstance();
            var level = minecraft.level;
            if (level == null || !level.dimension().location().equals(dimension) || GROUPS.isEmpty()) return;
            Vec3 camera = event.getCamera().getPosition();
            var buffers = minecraft.renderBuffers().bufferSource();
            PoseStack pose = event.getPoseStack();
            for (Entry entry : GROUPS.values()) {
                if (entry.bounds.distanceToSqr(camera) > 96 * 96 || !event.getFrustum().isVisible(entry.bounds)) continue;
                entry.updateMesh(level);
                pose.pushPose();
                pose.translate(entry.origin.getX() - camera.x, entry.origin.getY() - camera.y, entry.origin.getZ() - camera.z);
                entry.mesh.draw(pose.last(), buffers, pos -> LevelRenderer.getLightColor(level, pos));
                pose.popPose();
            }
            buffers.endBatch(WindowBoardsMesh.MATERIAL);
        }
    }
}
