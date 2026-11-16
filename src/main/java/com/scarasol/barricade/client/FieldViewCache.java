package com.scarasol.barricade.client;

import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.FieldRules;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/** Bounded display data, separate from the field's reusable dependency caches. */
final class FieldViewCache {
    private final Set<BlockPos> queried = new HashSet<>();
    private final Map<FieldObject.Key, FieldObject> objects = new TreeMap<>();
    private Set<BlockPos> baseline = Set.of();
    Set<BlockPos> baseline() { return baseline; }
    void synchronizeBaseline(Set<BlockPos> next) {
        if (baseline.equals(next)) return;
        Set<GridPos> changed = new HashSet<>();
        for (BlockPos cell : baseline) if (!next.contains(cell)) changed.add(SpatialField.region(cell));
        for (BlockPos cell : next) if (!baseline.contains(cell)) changed.add(SpatialField.region(cell));
        queried.removeIf(cell -> near(SpatialField.region(cell), changed, FieldRules.DISPLAY_RADIUS));
        objects.values().removeIf(object -> near(SpatialField.region(new BlockPos(object.key().slot().x(),
                object.key().slot().y(), object.key().slot().z())), changed, FieldRules.DEPENDENCY_RADIUS));
        baseline = Set.copyOf(next);
    }
    private static boolean near(GridPos block, Set<GridPos> changed, int radius) {
        for (GridPos other : changed)
            if (Math.abs(block.x() - other.x()) <= radius && Math.abs(block.y() - other.y()) <= radius
                    && Math.abs(block.z() - other.z()) <= radius) return true;
        return false;
    }
    void accept(Set<BlockPos> cells, List<FieldObject> values) {
        queried.addAll(cells);
        values.forEach(object -> objects.putIfAbsent(object.key(), object));
    }
    Set<BlockPos> missing(Set<BlockPos> domain) {
        Set<BlockPos> result = new HashSet<>(domain);
        result.removeAll(queried);
        return result;
    }
    boolean ready(Set<BlockPos> domain) { return queried.containsAll(domain); }
    Collection<FieldObject> objects() { return objects.values(); }
    int queriedCells() { return queried.size(); }
    static Set<BlockPos> nextBatch(Set<BlockPos> missing, BlockPos focus, int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Invalid field query batch size");
        if (missing.size() <= maximum) return Set.copyOf(missing);
        Comparator<BlockPos> order = Comparator.comparingDouble((BlockPos cell) -> cell.distSqr(focus))
                .thenComparing(Comparator.naturalOrder());
        PriorityQueue<BlockPos> nearest = new PriorityQueue<>(maximum, order.reversed());
        for (BlockPos cell : missing) {
            if (nearest.size() < maximum) nearest.add(cell);
            else if (order.compare(cell, nearest.peek()) < 0) { nearest.remove(); nearest.add(cell); }
        }
        return Set.copyOf(nearest);
    }
    void retain(Set<BlockPos> scope) {
        Set<Long> chunks = new HashSet<>();
        for (BlockPos cell : scope) {
            int x = cell.getX() >> 4, z = cell.getZ() >> 4;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                chunks.add(ChunkPos.asLong(x + dx, z + dz));
        }
        queried.removeIf(cell -> !chunks.contains(ChunkPos.asLong(cell)));
        // Supported geometry and bounded contact reach less than one chunk from its source.
        objects.values().removeIf(object -> !chunks.contains(ChunkPos.asLong(object.owner())));
    }
}
