package com.scarasol.barricade.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.pile.PilePiece;
import com.scarasol.barricade.pile.PileModel;
import com.scarasol.barricade.recipe.EmbeddedBlock;
import com.scarasol.barricade.pile.FieldObject;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

public final class BarricadeRenderer {
    private static final double DISTANCE = 96;
    private static final Map<UUID, Set<FieldObject.Key>> REGIONS = new HashMap<>();
    private static final Map<FieldObject.Key, Entry> OBJECTS = new HashMap<>();
    private static final Map<UUID, Map<BlockPos, EmbeddedBlock>> EMBEDDED_REGIONS = new HashMap<>();
    private static final Map<BlockPos, EmbeddedView> EMBEDDED = new HashMap<>();
    private static final RenderIndex<Entry> INDEX = new RenderIndex<>();
    private static final RenderIndex<Entry> UNCAPTURED = new RenderIndex<>();
    private static final RenderIndex<EmbeddedView> EMBEDDED_INDEX = new RenderIndex<>();
    private static final Set<BlockPos> SUPPRESSED = ConcurrentHashMap.newKeySet();
    private static List<Entry> frame = List.of();
    private static long drawn;
    private static long createdEntries, removedEntries;
    private static final class Entry {
        final FieldObject object;
        int references;
        AABB bounds;
        int generation = -1;
        Entry(FieldObject object) {
            this.object = object;
            // Captures reject vertices outside this local range. Refine on first visible use.
            bounds = transformed(object, new AABB(-16, -16, -16, 16, 16, 16));
        }
    }
    private static final class EmbeddedView {
        final EmbeddedBlock identity;
        PileModel model;
        PilePiece piece;
        StaticMesh mesh;
        boolean attempted;
        EmbeddedView(EmbeddedBlock identity) { this.identity = identity; }
    }
    private BarricadeRenderer() { }
    static void replace(UUID region, List<FieldObject> pieces) {
        Set<FieldObject.Key> previous = REGIONS.getOrDefault(region, Set.of()), next = new HashSet<>();
        for (FieldObject object : pieces) {
            if (!next.add(object.key())) continue;
            Entry entry = OBJECTS.get(object.key());
            if (entry == null) {
                entry = new Entry(object); OBJECTS.put(object.key(), entry);
                createdEntries++;
                UNCAPTURED.put(entry, entry.bounds);
            } else if (!entry.object.equals(object)) throw new IllegalStateException("Stable field identity changed transform");
            if (!previous.contains(object.key())) entry.references++;
        }
        for (FieldObject.Key key : previous) if (!next.contains(key)) release(key);
        REGIONS.put(region, Set.copyOf(next));
    }
    static void remove(UUID region) {
        removePieces(region);
        replaceEmbedded(region, Map.of()); EMBEDDED_REGIONS.remove(region);
    }
    private static void removePieces(UUID region) {
        Set<FieldObject.Key> keys = REGIONS.remove(region);
        if (keys != null) keys.forEach(BarricadeRenderer::release);
    }
    private static void release(FieldObject.Key key) {
        Entry entry = OBJECTS.get(key);
        if (entry == null || --entry.references != 0) return;
        OBJECTS.remove(key); INDEX.remove(entry); UNCAPTURED.remove(entry); removedEntries++;
    }
    static void replaceEmbedded(UUID region, Map<BlockPos, EmbeddedBlock> blocks) {
        Map<BlockPos, EmbeddedBlock> replacement = Map.copyOf(blocks);
        if (replacement.equals(EMBEDDED_REGIONS.get(region))) return;
        Map<BlockPos, EmbeddedBlock> previous = EMBEDDED_REGIONS.put(region, replacement);
        Set<BlockPos> changed = new HashSet<>(replacement.keySet());
        if (previous != null) changed.addAll(previous.keySet());
        for (BlockPos pos : changed) {
            EmbeddedBlock block = EMBEDDED_REGIONS.values().stream().map(group -> group.get(pos)).filter(Objects::nonNull)
                    .max(Comparator.comparingLong(EmbeddedBlock::instance).thenComparingLong(EmbeddedBlock::revision)).orElse(null);
            EmbeddedView old = EMBEDDED.get(pos);
            if (block != null && old != null && block.equals(old.identity)) continue;
            removeEmbedded(pos);
            if (block != null) {
                EmbeddedView view = new EmbeddedView(block);
                EMBEDDED.put(pos.immutable(), view); EMBEDDED_INDEX.put(view, new AABB(pos));
            }
        }
    }
    private static void removeEmbedded(BlockPos pos) {
        EmbeddedView view = EMBEDDED.remove(pos);
        if (view == null) return;
        EMBEDDED_INDEX.remove(view);
        if (SUPPRESSED.remove(pos)) rebuildOriginal(pos);
    }
    public static boolean suppressOriginal(BlockPos pos) { return SUPPRESSED.contains(pos); }
    private static void rebuildOriginal(BlockPos pos) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.hasChunkAt(pos)) return;
        // The state is unchanged; setBlockDirty(state, state) would skip rebuilding its baked mesh.
        minecraft.levelRenderer.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX(), pos.getY(), pos.getZ());
    }
    public static void invalidateEmbedded(BlockPos pos) {
        EmbeddedView view = EMBEDDED.get(pos);
        if (view == null) return;
        view.model = null;
        view.piece = null;
        view.mesh = null;
        view.attempted = false;
        EMBEDDED_INDEX.put(view, new AABB(pos));
        if (SUPPRESSED.remove(pos)) rebuildOriginal(pos);
    }
    static void clear() {
        List.copyOf(SUPPRESSED).forEach(BarricadeRenderer::removeEmbedded);
        REGIONS.clear(); OBJECTS.clear(); EMBEDDED_REGIONS.clear(); EMBEDDED.clear();
        INDEX.clear(); UNCAPTURED.clear(); EMBEDDED_INDEX.clear(); frame = List.of();
    }
    static void invalidateBounds() {
        OBJECTS.values().forEach(entry -> {
            entry.bounds = transformed(entry.object, new AABB(-16, -16, -16, 16, 16, 16));
            entry.generation = -1;
            INDEX.remove(entry);
            UNCAPTURED.put(entry, entry.bounds);
        });
        for (EmbeddedView view : EMBEDDED.values()) {
            view.model = null;
            view.piece = null;
            view.mesh = null;
            view.attempted = false;
            if (SUPPRESSED.remove(view.identity.pos())) rebuildOriginal(view.identity.pos());
            EMBEDDED_INDEX.put(view, new AABB(view.identity.pos()));
        }
        RenderPasses.clear();
    }
    static String stats() {
        return "objects=" + OBJECTS.size() + ", drawn_batches=" + drawn;
    }
    record Counters(long created, long removed, int objects) { }
    static Counters counters() { return new Counters(createdEntries, removedEntries, OBJECTS.size()); }
    public static void render(RenderLevelStageEvent event) {
        boolean transparent = event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES;
        if (!transparent && event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        Minecraft minecraft = Minecraft.getInstance();
        var level = minecraft.level;
        if (level == null) return;
        var camera = event.getCamera().getPosition();
        if (!transparent) {
            Set<Entry> candidates = new HashSet<>(INDEX.near(camera, DISTANCE));
            // Capture limits imply at most 80 blocks from owner, even at maximum override scale.
            candidates.addAll(UNCAPTURED.near(camera, DISTANCE + 80));
            frame = candidates.stream()
                    .filter(entry -> entry.bounds.distanceToSqr(camera) < DISTANCE * DISTANCE
                            && event.getFrustum().isVisible(entry.bounds))
                    .sorted(Comparator.comparingDouble((Entry entry) -> entry.bounds.getCenter().distanceToSqr(camera)).reversed()
                            .thenComparing(entry -> entry.object.key())).toList();
            drawn = 0;
        }
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Set<RenderType> used = Collections.newSetFromMap(new IdentityHashMap<>());
        PoseStack pose = event.getPoseStack();
        for (Entry entry : frame) {
            var object = entry.object;
            var piece = object.piece();
            StaticMesh mesh = MaterialMeshes.get(piece.model(), level);
            if (mesh == null) continue;
            if (entry.generation != MaterialMeshes.generation()) {
                entry.bounds = transformed(object, mesh.bounds);
                entry.generation = MaterialMeshes.generation();
                UNCAPTURED.remove(entry);
                INDEX.put(entry, entry.bounds);
            }
            if (!event.getFrustum().isVisible(entry.bounds)) continue;
            pose.pushPose();
            pose.translate(object.owner().getX() - camera.x + piece.x(), object.owner().getY() - camera.y + piece.y(),
                    object.owner().getZ() - camera.z + piece.z());
            pose.mulPose(piece.rotation());
            pose.scale(piece.model().sizeX() * piece.scale(), piece.model().sizeY() * piece.scale(),
                    piece.model().sizeZ() * piece.scale());
            pose.translate(-piece.model().pivotX(), -piece.model().pivotY(), -piece.model().pivotZ());
            for (StaticMesh.Batch batch : mesh.batches) {
                if (RenderPasses.transparent(batch.material) != transparent) continue;
                Vector3f center = piece.transformPoint(batch.lightPosition.x, batch.lightPosition.y, batch.lightPosition.z);
                BlockPos sample = BlockPos.containing(object.owner().getX() + (double) center.x,
                        object.owner().getY() + (double) center.y, object.owner().getZ() + (double) center.z);
                RenderType type = transparent ? RenderPasses.particles(batch.material) : batch.material;
                boolean loaded = level.hasChunkAt(sample);
                int light = loaded ? LevelRenderer.getLightColor(level, sample) : 0;
                // Split only batches whose center light is trapped in an opaque cell.
                if (loaded && SurfaceLight.directional(level, sample, light)) {
                    for (StaticMesh.Batch surface : batch.surfaces()) {
                        surface.draw(pose.last(), ignored -> buffers.getBuffer(type),
                                SurfaceLight.sample(level, object.owner(), piece, surface));
                        drawn++;
                    }
                } else {
                    batch.draw(pose.last(), ignored -> buffers.getBuffer(type), light);
                    drawn++;
                }
                used.add(type);
            }
            pose.popPose();
        }
        renderEmbedded(event, pose, buffers, used, level, camera, transparent);
        // Only submit types used by this renderer. Shared GPU buffers retire next frame.
        used.forEach(buffers::endBatch);
    }

    private static void renderEmbedded(RenderLevelStageEvent event, PoseStack pose, MultiBufferSource.BufferSource buffers,
                                       Set<RenderType> used, net.minecraft.client.multiplayer.ClientLevel level,
                                       net.minecraft.world.phys.Vec3 camera, boolean transparent) {
        for (EmbeddedView view : EMBEDDED_INDEX.near(camera, DISTANCE + 2)) {
            BlockPos pos = view.identity.pos();
            if (pos.distToCenterSqr(camera) >= DISTANCE * DISTANCE || !level.hasChunkAt(pos)) continue;
            if (!view.attempted) {
                var state = level.getBlockState(pos);
                if (state.isAir() || state.is(Barricade.BARRICADE.get())) continue;
                view.attempted = true;
                try {
                    PileModel model = view.identity.model(level);
                    StaticMesh mesh = MaterialMeshes.get(model, level);
                    // Initializing the cache may invalidate every view during this call.
                    view.attempted = true;
                    if (mesh != null) {
                        view.model = model;
                        view.mesh = mesh;
                        view.piece = view.identity.transform(model);
                        EMBEDDED_INDEX.put(view, transformed(view, mesh.bounds));
                        if (SUPPRESSED.add(pos)) rebuildOriginal(pos);
                    }
                } catch (RuntimeException | LinkageError exception) {
                    Barricade.LOGGER.warn("Cannot capture embedded appearance at {}: {}", pos, exception.toString());
                }
            }
            if (view.mesh == null || view.piece == null || !event.getFrustum().isVisible(transformed(view, view.mesh.bounds)))
                continue;
            pose.pushPose();
            pose.translate(pos.getX() - camera.x + view.piece.x(), pos.getY() - camera.y + view.piece.y(),
                    pos.getZ() - camera.z + view.piece.z());
            pose.mulPose(view.piece.rotation());
            pose.scale(view.model.sizeX() * view.piece.scale(), view.model.sizeY() * view.piece.scale(),
                    view.model.sizeZ() * view.piece.scale());
            pose.translate(-view.model.pivotX(), -view.model.pivotY(), -view.model.pivotZ());
            for (StaticMesh.Batch batch : view.mesh.surfaces()) {
                if (RenderPasses.transparent(batch.material) != transparent) continue;
                int light = SurfaceLight.sample(level, pos, view.piece, batch);
                RenderType type = transparent ? RenderPasses.particles(batch.material) : batch.material;
                batch.draw(pose.last(), ignored -> buffers.getBuffer(type), light);
                used.add(type);
                drawn++;
            }
            pose.popPose();
        }
    }

    private static AABB transformed(EmbeddedView view, AABB local) {
        return transformed(view.identity.pos(), view.piece, local);
    }
    static AABB transformed(FieldObject object, AABB local) {
        return transformed(object.owner(), object.piece(), local);
    }
    static AABB transformed(BlockPos owner, PilePiece piece, AABB local) {
        AABB bounds = null;
        for (int corner = 0; corner < 8; corner++) {
            Vector3f point = piece.transformPoint(
                    (float) ((corner & 1) == 0 ? local.minX : local.maxX),
                    (float) ((corner & 2) == 0 ? local.minY : local.maxY),
                    (float) ((corner & 4) == 0 ? local.minZ : local.maxZ));
            AABB vertex = new AABB(point.x, point.y, point.z, point.x, point.y, point.z).move(owner);
            bounds = bounds == null ? vertex : bounds.minmax(vertex);
        }
        return bounds;
    }
}
