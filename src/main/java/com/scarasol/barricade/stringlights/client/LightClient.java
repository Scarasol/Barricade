package com.scarasol.barricade.stringlights.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.stringlights.LightCurve;
import com.scarasol.barricade.stringlights.LightNetwork;
import com.scarasol.barricade.stringlights.LightTargets;
import com.scarasol.barricade.stringlights.LightTools;
import com.scarasol.barricade.stringlights.LightView;
import com.scarasol.barricade.stringlights.PowerBoxBlock;
import com.scarasol.barricade.stringlights.PowerBoxEntity;
import com.scarasol.barricade.stringlights.StringLights;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = Barricade.MOD_ID, value = Dist.CLIENT)
public final class LightClient {
    private static final Map<Long, Entry> SECTIONS = new HashMap<>();
    private static final Map<UUID, CurveEntry> CURVES = new HashMap<>();
    private static final Map<Long, CompoundTag> PAGES = new HashMap<>();
    private static ClientLevel world;
    private static UUID selected;
    private static LightPreview preview;
    private LightClient() { }

    @Mod.EventBusSubscriber(modid = Barricade.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        @SubscribeEvent public static void client(FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                LightNetwork.client(LightClient::receive, LightClient::select);
                LightTargets.client(LightClient::view, LightClient::outline, LightClient::hitBulbs);
            });
        }

        @SubscribeEvent public static void reload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                    manager -> invalidateMeshes());
        }
    }

    private static LightView view(long key) {
        Entry entry = SECTIONS.get(key);
        return entry != null ? entry.view : null;
    }

    private static VoxelShape outline(BlockPos pos) {
        Entry entry = SECTIONS.get(ChunkPos.asLong(pos));
        return entry == null ? Shapes.empty() : entry.outlines.getOrDefault(pos, Shapes.empty());
    }

    private static List<LightCurve.Bulb> hitBulbs(BlockPos pos) {
        Entry entry = SECTIONS.get(ChunkPos.asLong(pos));
        return entry == null ? List.of() : entry.hits.getOrDefault(pos, List.of());
    }

    public static boolean hasInstallations() { return !SECTIONS.isEmpty(); }

    public static void lightChanged(ClientLevel level, long chunk) {
        if (level != world) return;
        Entry entry = SECTIONS.get(chunk);
        if (entry != null) entry.cableDirty = entry.fixtureDirty = entry.lampsDirty = true;
    }

    private static void select(LightNetwork.Selection packet) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null && level.dimension().location().equals(packet.dimension())) {
            ensure(level);
            selected = packet.node();
            preview = null;
        }
    }

    private static void receive(LightNetwork.ViewPacket packet) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !level.dimension().location().equals(packet.dimension())) return;
        ensure(level);
        Entry old = SECTIONS.get(packet.chunk());
        CompoundTag data = packet.snapshot();
        if (packet.parts() > 1) {
            if (packet.part() == 0) {
                PAGES.put(packet.chunk(), data);
                return;
            }
            CompoundTag current = PAGES.get(packet.chunk());
            if (current == null) return;
            for (String key : current.getAllKeys()) {
                current.getList(key, Tag.TAG_COMPOUND).addAll(data.getList(key, Tag.TAG_COMPOUND));
            }
            if (packet.part() + 1 != packet.parts()) return;
            data = PAGES.remove(packet.chunk());
        } else {
            PAGES.remove(packet.chunk());
        }
        if (data == null) {
            if (old != null) closeEntry(SECTIONS.remove(packet.chunk()));
        } else if (data.contains("lights")) {
            if (old != null) old.replace(old.view.relight(data));
        } else {
            LightView next = LightView.read(data, (id, tag) -> {
                CurveEntry cached = CURVES.computeIfAbsent(id, ignored -> new CurveEntry(LightView.readCurve(tag)));
                if (old == null || !old.view.spans().containsKey(id)) cached.references++;
                return cached.curve;
            });
            if (old == null) SECTIONS.put(packet.chunk(), new Entry(new ChunkPos(packet.chunk()), next));
            else {
                old.view.spans().keySet().forEach(id -> { if (!next.spans().containsKey(id)) release(id); });
                old.replace(next);
            }
        }
    }

    private static void closeEntry(Entry entry) {
        entry.view.spans().keySet().forEach(LightClient::release);
        entry.close();
    }

    private static void release(UUID id) {
        CurveEntry curve = CURVES.get(id);
        if (--curve.references == 0) CURVES.remove(id);
    }

    private static void ensure(ClientLevel level) {
        if (level != world) {
            clear();
            world = level;
        }
    }

    private static void invalidateMeshes() {
        SECTIONS.values().forEach(Entry::close);
    }

    private static void clear() {
        invalidateMeshes();
        SECTIONS.clear();
        CURVES.clear();
        PAGES.clear();
        world = null;
        selected = null;
        preview = null;
    }

    @SubscribeEvent public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() == world) clear();
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;
        ensure(client.level);
        if (client.screen != null) {
            preview = null;
            return;
        }
        var tool = tool(client.player.getMainHandItem());
        if (tool == null) tool = tool(client.player.getOffhandItem());
        LightView.Node start = null;
        if (tool == LightTargets.Tool.WIRE && selected != null) {
            for (Entry entry : SECTIONS.values()) {
                start = entry.view.nodes().get(selected);
                if (start != null) break;
            }
        }
        preview = LightPreview.update(preview, client.player, tool, start, LightClient::view);
    }

    private static LightTargets.Tool tool(ItemStack stack) {
        if (stack.is(StringLights.WIRE.get())) return LightTargets.Tool.WIRE;
        if (stack.is(Items.SHEARS)) return LightTargets.Tool.SHEARS;
        return stack.is(StringLights.BOX_ITEM.get()) ? LightTargets.Tool.BOX : null;
    }

    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || world == null
                || (SECTIONS.isEmpty() && preview == null)) return;
        var camera = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        LightRenderMesh.MATERIAL.setupRenderState();
        try {
            for (var entry : SECTIONS.values()) {
                if (!world.hasChunk(entry.chunk.x, entry.chunk.z) || !event.getFrustum().isVisible(entry.bounds)) continue;
                entry.prepare(world);
                pose.pushPose();
                pose.translate(entry.chunk.getMinBlockX() - camera.x, -camera.y, entry.chunk.getMinBlockZ() - camera.z);
                var matrix = pose.last().pose();
                LightRenderMesh.draw(entry.cables, matrix, event.getProjectionMatrix());
                LightRenderMesh.draw(entry.fixtures, matrix, event.getProjectionMatrix());
                LightRenderMesh.draw(entry.lamps, matrix, event.getProjectionMatrix());
                pose.popPose();
            }
        } finally {
            VertexBuffer.unbind();
            LightRenderMesh.MATERIAL.clearRenderState();
        }
        if (preview != null) preview.render(event);
    }

    @SubscribeEvent public static void overlay(RenderGuiOverlayEvent.Post event) {
        if (!event.getOverlay().id().equals(VanillaGuiOverlay.CROSSHAIR.id())) return;
        var client = Minecraft.getInstance();
        Component text = preview == null ? null : preview.message();
        if (text == null && client.level != null && client.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK && client.level.getBlockEntity(hit.getBlockPos()) instanceof PowerBoxEntity box) {
            String status = !box.getBlockState().getValue(PowerBoxBlock.ON) ? "off"
                    : box.demand() == 0 ? "idle" : box.supplying() ? "on" : "empty";
            text = Component.translatable("string_lights.box.status", box.displayedEnergy(), box.demand(),
                    Component.translatable("string_lights.box." + status));
        }
        if (text != null) event.getGuiGraphics().drawCenteredString(client.font, text,
                event.getGuiGraphics().guiWidth() / 2, event.getGuiGraphics().guiHeight() / 2 + 15, 0xffffff);
    }

    private static final class CurveEntry {
        final LightCurve curve;
        int references;
        CurveEntry(LightCurve curve) { this.curve = curve; }
    }

    private static final class Entry {
        final ChunkPos chunk;
        final AABB bounds;
        LightView view;
        VertexBuffer cables, fixtures, lamps;
        final Map<BlockPos, VoxelShape> outlines = new HashMap<>();
        final Map<BlockPos, List<LightCurve.Bulb>> hits = new HashMap<>();
        boolean cableDirty = true, fixtureDirty = true, lampsDirty = true;

        Entry(ChunkPos chunk, LightView view) {
            this.chunk = chunk;
            this.view = view;
            bounds = new AABB(chunk.getMinBlockX() - 2, world.getMinBuildHeight(), chunk.getMinBlockZ() - 2,
                    chunk.getMaxBlockX() + 3, world.getMaxBuildHeight(), chunk.getMaxBlockZ() + 3);
            index();
        }

        void replace(LightView next) {
            boolean spansChanged = !view.spans().keySet().equals(next.spans().keySet());
            cableDirty = cableDirty || spansChanged || next.spans().values().stream()
                    .anyMatch(span -> !span.cable().equals(view.spans().get(span.id()).cable()));
            fixtureDirty |= !view.nodes().equals(next.nodes());
            lampsDirty |= !view.spans().equals(next.spans());
            boolean targetsChanged = spansChanged
                    || next.spans().values().stream().anyMatch(span -> span.broken() != view.spans().get(span.id()).broken());
            view = next;
            if (targetsChanged) index();
        }

        void index() {
            outlines.clear();
            hits.clear();
            for (var span : view.spans().values()) for (int i = 0; i < span.curve().bulbs().size(); i++) {
                if (span.broken(i)) continue;
                var bulb = span.curve().bulbs().get(i);
                if (ChunkPos.asLong(bulb.cell()) == chunk.toLong()) {
                    outlines.compute(bulb.cell(), (pos, shape) -> LightTargets.addBulb(shape == null ? Shapes.empty() : shape, bulb, pos));
                }
                for (BlockPos pos : LightTargets.cells(bulb.targetBounds())) if (ChunkPos.asLong(pos) == chunk.toLong()) {
                    hits.computeIfAbsent(pos.immutable(), ignored -> new java.util.ArrayList<>()).add(bulb);
                }
            }
        }

        void prepare(ClientLevel level) {
            if (cableDirty) {
                close(cables);
                cables = LightRenderMesh.cable(level, chunk, view);
                cableDirty = false;
            }
            if (fixtureDirty) {
                close(fixtures);
                fixtures = LightRenderMesh.fixtures(level, chunk, view);
                fixtureDirty = false;
            }
            if (lampsDirty) {
                close(lamps);
                lamps = LightRenderMesh.bulbs(level, chunk, view);
                lampsDirty = false;
            }
        }

        void close() {
            close(cables);
            close(fixtures);
            close(lamps);
            cables = fixtures = lamps = null;
            cableDirty = fixtureDirty = lampsDirty = true;
        }

        private static void close(VertexBuffer buffer) { if (buffer != null) buffer.close(); }
    }
}
