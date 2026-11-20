package com.scarasol.barricade.stringlights;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.ForgeEventFactory;

/** Coordinates installations with the loaded world. The graph, light index and FE lifecycle are separate. */
final class LightWorld {
    private static final Map<ServerLevel, LightWorld> LEVELS = new IdentityHashMap<>();

    static LightWorld get(ServerLevel level) { return LEVELS.computeIfAbsent(level, LightWorld::new); }
    static LightWorld current(ServerLevel level) { return LEVELS.get(level); }
    static void close(ServerLevel level) { LEVELS.remove(level); }

    final ServerLevel level;
    private final LightData data;
    final LightGraph graph;
    private final Set<Long> dirtyChunks = new HashSet<>();
    final LightSpatial spatial = new LightSpatial(dirtyChunks::add);
    private final Map<BlockPos, PowerBoxEntity> boxes = new HashMap<>();
    private final Map<BlockPos, Placement> placements = new HashMap<>();
    private final Set<BlockPos> replaced = new HashSet<>();
    private final Set<BlockPos> lighting = new HashSet<>();
    private final Map<Long, Set<LightGraph.Span>> dirtyLight = new HashMap<>();
    private final Set<Long> loading = new HashSet<>();
    private final Map<LightGraph.Node, Boolean> supports = new HashMap<>();
    private final LightPower power = new LightPower(lighting::add);
    private final LightWatch watch = new LightWatch();
    private boolean placingBulbs;

    private LightWorld(ServerLevel level) {
        this.level = level;
        data = LightData.get(level);
        graph = data.graph;
        graph.nodes().forEach(spatial::add);
        graph.spans().forEach(spatial::add);
        dirtyChunks.clear();
    }

    void placed(PowerBoxEntity box, UUID mount) {
        placements.put(box.getBlockPos(), new Placement(box, mount));
    }

    void loadedBox(PowerBoxEntity box) {
        placements.putIfAbsent(box.getBlockPos(), new Placement(box, null));
    }

    private void install(PowerBoxEntity box, UUID mount) {
        BlockPos pos = box.getBlockPos();
        boxes.put(pos, box);
        if (graph.box(pos) != null) return;
        Direction face = box.getBlockState().getValue(PowerBoxBlock.FACE);
        LightGraph.Node node = mount == null ? null : graph.node(mount);
        if (node != null && (node.outlet || node.source() != null || !node.surface.relative(node.face).equals(pos)
                || node.face != face)) node = null;
        if (node == null) {
            var outlet = PowerBoxBlock.outlet(pos, face);
            for (var candidate : spatial.surface(pos)) {
                if (candidate.outlet && candidate.source() == null && candidate.face == face
                        && candidate.anchor.equals(outlet)) {
                    node = candidate;
                    break;
                }
            }
            if (node == null) {
                node = new LightGraph.Node(UUID.randomUUID(), outlet, pos, face, true);
                graph.add(node);
                spatial.add(node);
            }
        }
        graph.attach(pos, node);
        refresh(node.component());
        if (!node.outlet) node.edges.forEach(spatial::update);
        data.setDirty();
    }

    void boxSwitch(BlockPos pos, boolean on) {
        if (!on) power.stop(pos);
    }

    void boxRemoved(BlockPos pos) {
        if (level.captureBlockSnapshots || level.restoringBlockSnapshots) {
            replaced.add(pos.immutable());
        } else {
            detach(pos);
        }
    }

    private void detach(BlockPos pos) {
        boxes.remove(pos);
        power.dropBox(pos);
        LightGraph.Node node = graph.detach(pos);
        if (node != null) {
            if (graph.node(node.id) == null) removeNode(node);
            else spatial.update(node);
            for (var member : node.component()) spatial.update(member);
            if (!node.outlet) node.edges.forEach(spatial::update);
            data.setDirty();
        }
    }

    boolean isLit(LightSpatial.Lamp lamp) { return power.lit(lamp); }
    boolean supported(LightGraph.Node node) { return supports.getOrDefault(node, false); }

    void placeStructure(LightGraph copy) {
        Set<LightGraph.Span> replacedSpans = new HashSet<>();
        for (var span : copy.spans()) {
            for (var candidate : spatial.surface(span.a.surface)) {
                if (!sameMount(candidate, span.a)) continue;
                for (var edge : candidate.edges) {
                    if (sameMount(edge.other(candidate), span.b)) replacedSpans.add(edge);
                }
            }
        }
        replacedSpans.forEach(this::cut);
        for (var node : copy.nodes()) {
            BlockPos pos = node.box();
            if (pos != null && level.getBlockEntity(pos) instanceof PowerBoxEntity box) {
                // Native placement has already put the box in the world; bind it to the copied node.
                if (graph.box(pos) != null) detach(pos);
                boxes.put(pos, box);
                placements.remove(pos);
            }
        }
        graph.addStructure(copy);
        for (var node : copy.nodes()) {
            spatial.add(node);
            refreshSupport(node);
        }
        for (var span : copy.spans()) {
            spatial.add(span);
            for (int i = 0; i < span.curve.bulbs().size(); i++) {
                if (span.broken(i)) continue;
                var bulb = span.curve.bulbs().get(i);
                BlockPos pos = bulb.cell();
                if (!LightTargets.canFit(level, bulb)) {
                    span.damage(i);
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (!state.is(StringLights.BULB.get()) && !level.setBlock(pos, StringLights.BULB.get().defaultBlockState()
                        .setValue(BulbBlock.WATER, state.is(Blocks.WATER)), Block.UPDATE_ALL)) {
                    span.damage(i);
                    continue;
                }
                addLamp(new LightSpatial.Lamp(span, i));
            }
        }
        data.setDirty();
    }

    private static boolean sameMount(LightGraph.Node a, LightGraph.Node b) {
        return a.surface.equals(b.surface) && a.face == b.face && a.anchor.equals(b.anchor);
    }

    boolean join(ServerPlayer player, LightGraph.Node a, LightGraph.Node b, LightCurve curve) {
        if (!graph.canJoin(a, b)) return false;
        if (!placeCells(player, curve.bulbs(), b.face, curve.cost())) return false;
        Collection<LightGraph.Node> reassigned = null;
        if (a.source() == null && b.source() != null) reassigned = List.copyOf(a.component());
        if (b.source() == null && a.source() != null) reassigned = List.copyOf(b.component());
        if (graph.node(b.id) == null) {
            graph.add(b);
            spatial.add(b);
            refreshSupport(b);
        }
        var span = new LightGraph.Span(UUID.randomUUID(), a, b, curve, 0);
        graph.join(span);
        spatial.add(span);
        if (reassigned != null) refresh(reassigned);
        for (int i = 0; i < curve.bulbs().size(); i++) addLamp(new LightSpatial.Lamp(span, i));
        data.setDirty();
        return true;
    }

    boolean repair(ServerPlayer player, LightSpatial.Lamp lamp) {
        if (graph.span(lamp.span().id) != lamp.span() || !lamp.broken()
                || !placeCells(player, List.of(lamp.bulb()), Direction.UP, 1)) return false;
        lamp.span().repair(lamp.index());
        spatial.invalidateOutline(lamp.bulb().cell());
        addLamp(lamp);
        spatial.update(lamp.span());
        data.setDirty();
        return true;
    }

    private void addLamp(LightSpatial.Lamp lamp) {
        BlockPos pos = lamp.bulb().cell();
        if (loaded(pos) != null) {
            power.watch(LightSpatial.chunk(pos));
            power.track(lamp);
            lighting.add(pos);
        }
    }

    private boolean placeCells(ServerPlayer player, List<LightCurve.Bulb> bulbs, Direction face, int cost) {
        if (placingBulbs || level.captureBlockSnapshots || level.restoringBlockSnapshots) return false;
        Set<BlockPos> cells = new HashSet<>();
        Set<BlockPos> shared = new HashSet<>();
        for (var bulb : bulbs) {
            if (!LightTargets.canFit(level, bulb) || !level.mayInteract(player, bulb.cell())) return false;
            if (level.getBlockState(bulb.cell()).is(StringLights.BULB.get())) shared.add(bulb.cell());
            else cells.add(bulb.cell());
        }
        var snapshots = new ArrayList<BlockSnapshot>();
        boolean committed = false;
        placingBulbs = true;
        level.captureBlockSnapshots = true;
        try {
            boolean placed = true;
            for (BlockPos pos : cells) {
                BlockState state = StringLights.BULB.get().defaultBlockState().setValue(BulbBlock.WATER,
                        level.getBlockState(pos).is(Blocks.WATER));
                if (!level.setBlock(pos, state, Block.UPDATE_ALL)) {
                    placed = false;
                    break;
                }
            }
            snapshots.addAll(level.capturedBlockSnapshots);
            level.capturedBlockSnapshots.clear();
            level.captureBlockSnapshots = false;
            if (!placed) return false;
            List<BlockSnapshot> proposed = snapshots;
            if (!shared.isEmpty()) {
                proposed = new ArrayList<>(snapshots);
                for (BlockPos pos : shared) proposed.add(BlockSnapshot.create(level.dimension(), level, pos));
            }
            boolean cancelled = proposed.size() == 1 ? ForgeEventFactory.onBlockPlace(player, proposed.get(0), face)
                    : ForgeEventFactory.onMultiBlockPlace(player, proposed, face);
            if (!cancelled && (player.isCreative() || LightTools.count(player) >= cost)) {
                committed = bulbs.stream().allMatch(bulb -> level.getBlockState(bulb.cell()).is(StringLights.BULB.get()));
            }
            return committed;
        } finally {
            if (level.captureBlockSnapshots) {
                snapshots.addAll(level.capturedBlockSnapshots);
                level.capturedBlockSnapshots.clear();
                level.captureBlockSnapshots = false;
            }
            if (!committed) {
                level.restoringBlockSnapshots = true;
                try {
                    for (int i = snapshots.size() - 1; i >= 0; i--) snapshots.get(i).restore(true, false);
                } finally {
                    level.restoringBlockSnapshots = false;
                }
            } else {
                for (var snapshot : snapshots) {
                    BlockPos pos = snapshot.getPos();
                    level.markAndNotifyBlock(pos, loaded(pos), snapshot.getReplacedBlock(), level.getBlockState(pos),
                            Block.UPDATE_ALL, 512);
                }
            }
            placingBulbs = false;
        }
    }

    static boolean canPlace(net.minecraft.world.level.Level level, BlockPos pos) {
        if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos) || !level.hasChunkAt(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.is(Blocks.WATER) || state.is(StringLights.BULB.get());
    }

    int cut(LightGraph.Span span) {
        BlockPos source = span.a.source();
        if (!graph.remove(span)) return -1;
        for (int i = 0; i < span.curve.bulbs().size(); i++) {
            var lamp = new LightSpatial.Lamp(span, i);
            power.remove(lamp);
            lighting.add(lamp.bulb().cell());
        }
        spatial.remove(span);
        if (graph.node(span.a.id) == null) removeNode(span.a);
        if (graph.node(span.b.id) == null) removeNode(span.b);
        if (source != null && span.a.source() == null) refresh(span.a.component());
        if (source != null && span.b.source() == null) refresh(span.b.component());
        flushLighting();
        data.setDirty();
        return span.refund();
    }

    private void removeNode(LightGraph.Node node) {
        supports.remove(node);
        spatial.remove(node);
    }

    private void refresh(Collection<LightGraph.Node> members) {
        Set<LightGraph.Span> seen = new HashSet<>();
        for (var node : members) {
            spatial.update(node);
            for (var span : node.edges) if (seen.add(span)) {
                for (int i = 0; i < span.curve.bulbs().size(); i++) power.track(new LightSpatial.Lamp(span, i));
            }
        }
    }

    void bulbRemoved(BlockPos pos) {
        if (placingBulbs || level.restoringBlockSnapshots) return;
        if (level.captureBlockSnapshots) replaced.add(pos.immutable());
        else damageCell(pos);
    }

    private void damageCell(BlockPos pos) {
        for (var lamp : spatial.cell(pos)) damage(lamp, false);
    }

    boolean damage(LightSpatial.Lamp lamp, boolean feedback) {
        BlockPos pos = lamp.bulb().cell();
        if (lamp.broken() || graph.span(lamp.span().id) != lamp.span()) return false;
        lamp.span().damage(lamp.index());
        spatial.invalidateOutline(pos);
        power.remove(lamp);
        lighting.add(pos);
        spatial.update(lamp.span());
        data.setDirty();
        if (feedback) {
            var anchor = lamp.bulb().anchor();
            level.playSound(null, anchor.x, anchor.y, anchor.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 0.5f, 1.35f);
            level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
                    Blocks.GLASS.defaultBlockState()), anchor.x, anchor.y - 0.2, anchor.z, 6, 0.06, 0.06, 0.06, 0.06);
            flushLighting();
        }
        return true;
    }

    void chunkLoad(LevelChunk chunk) { loading.add(chunk.getPos().toLong()); }

    void chunkUnload(LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        loading.remove(key);
        power.unload(key, spatial.sections.get(key));
        chunk.getBlockEntities().forEach((pos, entity) -> {
            if (entity instanceof PowerBoxEntity) {
                boxes.remove(pos, entity);
                power.stop(pos);
            }
        });
    }

    void watch(ServerPlayer player, ChunkPos chunk) {
        watch.add(player, chunk.toLong());
        watch.send(this, player, chunk.toLong());
    }

    void unwatch(ServerPlayer player, ChunkPos chunk) {
        watch.remove(player, chunk.toLong());
        LightNetwork.send(player, level, chunk.toLong(), null);
    }

    void leave(ServerPlayer player) { watch.remove(player); }

    void refreshSupport(BlockPos pos) { spatial.surface(pos).forEach(this::refreshSupport); }

    private void refreshSupport(LightGraph.Node node) {
        if (node.outlet || loaded(node.surface) == null) return;
        boolean next = LightTargets.supports(level, node.surface, node.face, node.anchor);
        Boolean previous = supports.put(node, next);
        if (previous == null || previous != next) spatial.update(node);
    }

    private void reconcile(LevelChunk chunk) {
        long key = chunk.getPos().toLong();
        LightSpatial.Section section = spatial.sections.get(key);
        if (section != null) {
            for (var node : List.copyOf(section.nodes)) {
                if (node.box() != null && LightSpatial.chunk(node.box()) == key
                        && !chunk.getBlockState(node.box()).is(StringLights.BOX.get())) detach(node.box());
                if (LightSpatial.chunk(node.surface) == key) refreshSupport(node);
            }
            for (var lamp : section.lamps) {
                BlockPos pos = lamp.bulb().cell();
                if (!chunk.getBlockState(pos).is(StringLights.BULB.get())) damage(lamp, false);
                else lighting.add(pos);
            }
            if (!section.lamps.isEmpty()) power.watch(key);
        }
        chunk.getBlockEntities().values().forEach(entity -> {
            if (entity instanceof PowerBoxEntity box && !placements.containsKey(box.getBlockPos())) install(box, null);
        });
        chunk.findBlocks(state -> state.is(StringLights.BULB.get()), (pos, state) -> lighting.add(pos.immutable()));
    }

    void tick() {
        for (BlockPos pos : replaced) {
            LevelChunk chunk = loaded(pos);
            if (chunk == null) continue;
            BlockState state = chunk.getBlockState(pos);
            if (!state.is(StringLights.BULB.get())) damageCell(pos);
            if (!state.is(StringLights.BOX.get()) || placements.containsKey(pos)) detach(pos);
        }
        replaced.clear();
        var pendingBoxes = new ArrayList<>(placements.values());
        placements.clear();
        for (Placement placement : pendingBoxes) {
            var box = placement.box;
            LevelChunk chunk = loaded(box.getBlockPos());
            if (chunk != null && chunk.getBlockEntity(box.getBlockPos()) == box) install(box, placement.mount);
        }
        var waiting = loading.iterator();
        while (waiting.hasNext()) {
            long key = waiting.next();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk != null) {
                reconcile(chunk);
                waiting.remove();
            }
        }
        power.tick(level, spatial, boxes);
        flushLighting();
        watch.flush(this, dirtyChunks);
        watch.light(this, dirtyChunks, dirtyLight);
        dirtyChunks.clear();
        dirtyLight.clear();
    }

    private void flushLighting() {
        for (BlockPos pos : lighting) {
            var lamps = spatial.cell(pos);
            for (var lamp : lamps) for (long key : spatial.chunks(lamp.span())) {
                dirtyLight.computeIfAbsent(key, ignored -> new HashSet<>()).add(lamp.span());
            }
            LevelChunk chunk = loaded(pos);
            if (chunk == null) continue;
            BlockState state = chunk.getBlockState(pos);
            if (!state.is(StringLights.BULB.get())) continue;
            boolean exists = false;
            boolean lit = false;
            for (var lamp : lamps) {
                if (!lamp.broken()) {
                    exists = true;
                    lit |= power.lit(lamp);
                }
            }
            if (!exists) {
                level.setBlock(pos, state.getValue(BulbBlock.WATER) ? Blocks.WATER.defaultBlockState()
                        : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            } else if (state.getValue(BulbBlock.LIT) != lit) {
                level.setBlock(pos, state.setValue(BulbBlock.LIT, lit), Block.UPDATE_CLIENTS);
            }
        }
        lighting.clear();
    }

    LevelChunk loaded(BlockPos pos) { return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4); }

    private record Placement(PowerBoxEntity box, UUID mount) { }
}
