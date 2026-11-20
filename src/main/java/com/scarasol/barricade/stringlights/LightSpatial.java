package com.scarasol.barricade.stringlights;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Geometry lookups for server edits and chunk-scoped synchronization. */
final class LightSpatial {
    final Map<Long, Section> sections = new HashMap<>();
    private final Map<BlockPos, Set<Lamp>> cells = new HashMap<>();
    private final Map<BlockPos, Set<Lamp>> hitCells = new HashMap<>();
    private final Map<BlockPos, VoxelShape> outlines = new HashMap<>();
    private final Map<BlockPos, Set<LightGraph.Node>> surfaces = new HashMap<>();
    private final Map<LightGraph.Span, Set<Long>> spanChunks = new HashMap<>();
    private final Consumer<Long> changed;

    LightSpatial(Consumer<Long> changed) { this.changed = changed; }

    Collection<Lamp> cell(BlockPos pos) { return cells.getOrDefault(pos, Set.of()); }
    Collection<Lamp> hitCell(BlockPos pos) { return hitCells.getOrDefault(pos, Set.of()); }
    Collection<LightGraph.Node> surface(BlockPos pos) { return surfaces.getOrDefault(pos, Set.of()); }
    VoxelShape outline(BlockPos pos) {
        if (!cells.containsKey(pos)) return Shapes.empty();
        return outlines.computeIfAbsent(pos.immutable(), key -> {
            VoxelShape result = Shapes.empty();
            for (var lamp : cell(key)) if (!lamp.broken()) result = LightTargets.addBulb(result, lamp.bulb(), key);
            return result.optimize();
        });
    }

    void invalidateOutline(BlockPos pos) { outlines.remove(pos); }

    void add(LightGraph.Node node) {
        insert(surfaces, node.surface, node);
        for (long key : nodeChunks(node)) {
            section(key).nodes.add(node);
            changed.accept(key);
        }
    }

    void update(LightGraph.Node node) { nodeChunks(node).forEach(changed); }

    void remove(LightGraph.Node node) {
        erase(surfaces, node.surface, node);
        for (long key : nodeChunks(node)) {
            section(key).nodes.remove(node);
            trim(key);
        }
    }

    void add(LightGraph.Span span) {
        Set<Long> keys = new HashSet<>();
        var curve = span.curve;
        for (var piece : curve.pieces()) {
            // A line can be picked or drawn on either side of a chunk edge.
            columns(curve.bounds(piece).inflate(0.1), keys);
        }
        for (int i = 0; i < curve.bulbs().size(); i++) {
            Lamp lamp = new Lamp(span, i);
            insert(cells, lamp.bulb().cell(), lamp);
            invalidateOutline(lamp.bulb().cell());
            section(chunk(lamp.bulb().cell())).lamps.add(lamp);
            AABB bounds = lamp.bulb().targetBounds();
            columns(bounds, keys);
            for (BlockPos pos : LightTargets.cells(bounds)) {
                insert(hitCells, pos.immutable(), lamp);
            }
        }
        spanChunks.put(span, keys);
        for (long key : keys) {
            section(key).spans.add(span);
            changed.accept(key);
        }
    }

    void remove(LightGraph.Span span) {
        for (int i = 0; i < span.curve.bulbs().size(); i++) {
            Lamp lamp = new Lamp(span, i);
            erase(cells, lamp.bulb().cell(), lamp);
            invalidateOutline(lamp.bulb().cell());
            section(chunk(lamp.bulb().cell())).lamps.remove(lamp);
            for (BlockPos pos : LightTargets.cells(lamp.bulb().targetBounds())) {
                erase(hitCells, pos, lamp);
            }
        }
        for (long key : spanChunks.remove(span)) {
            section(key).spans.remove(span);
            trim(key);
        }
    }

    void update(LightGraph.Span span) { spanChunks.get(span).forEach(changed); }
    Set<Long> chunks(LightGraph.Span span) { return spanChunks.get(span); }
    static long chunk(BlockPos pos) { return ChunkPos.asLong(pos); }

    private static Set<Long> nodeChunks(LightGraph.Node node) {
        Set<Long> result = new HashSet<>(4);
        columns(new AABB(node.anchor, node.anchor).inflate(0.23), result);
        result.add(chunk(node.surface));
        return result;
    }

    private static void columns(AABB bounds, Set<Long> result) {
        int x0 = BlockPos.containing(bounds.minX, 0, bounds.minZ).getX() >> 4;
        int z0 = BlockPos.containing(bounds.minX, 0, bounds.minZ).getZ() >> 4;
        int x1 = BlockPos.containing(bounds.maxX, 0, bounds.maxZ).getX() >> 4;
        int z1 = BlockPos.containing(bounds.maxX, 0, bounds.maxZ).getZ() >> 4;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) result.add(ChunkPos.asLong(x, z));
    }

    private Section section(long key) { return sections.computeIfAbsent(key, ignored -> new Section()); }

    private void trim(long key) {
        Section section = sections.get(key);
        if (section.nodes.isEmpty() && section.spans.isEmpty() && section.lamps.isEmpty()) sections.remove(key);
        changed.accept(key);
    }

    private static <K, V> void insert(Map<K, Set<V>> index, K key, V value) {
        index.computeIfAbsent(key, ignored -> new HashSet<>()).add(value);
    }

    private static <K, V> void erase(Map<K, Set<V>> index, K key, V value) {
        Set<V> list = index.get(key);
        if (list != null && list.remove(value) && list.isEmpty()) index.remove(key);
    }

    static final class Section {
        final Set<LightGraph.Node> nodes = new HashSet<>();
        final Set<LightGraph.Span> spans = new HashSet<>();
        final Set<Lamp> lamps = new HashSet<>();
    }

    record Lamp(LightGraph.Span span, int index) {
        LightCurve.Bulb bulb() { return span.curve.bulbs().get(index); }
        boolean broken() { return span.broken(index); }
        BlockPos source() { return span.a.source(); }
    }
}
