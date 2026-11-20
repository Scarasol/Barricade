package com.scarasol.barricade.client;

import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import java.util.*;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;

/** A disposable display selection. Suppression never removes objects from the base field. */
final class FieldOverlay {
    private static final long TICK_BUDGET_NANOS = 2_000_000;
    final FieldRegion region;
    final long epoch;
    private final Iterator<FieldObject> iterator;
    private final List<FieldObject> visible = new ArrayList<>();
    private final Set<BlockPos> accepted = new HashSet<>();
    private final Map<BlockPos, Rejected> rejected = new HashMap<>();
    private Iterator<Rejected> fallbacks;
    private record Rejected(FieldObject object, float depth) { }
    private final SpatialMask mask;
    private final Set<BlockPos> logical = new HashSet<>();
    private final Map<BlockPos, EmbeddedBlock> embedded = new HashMap<>();
    private TerrainShapes.View view;
    FieldOverlay(FieldRegion region, long epoch, Collection<FieldObject> objects, Collection<FieldRegion> regions) {
        this.region = region; this.epoch = epoch;
        iterator = List.copyOf(objects).iterator(); mask = region.mask();
        for (FieldRegion group : regions) {
            logical.addAll(group.baseline());
            embedded.putAll(group.embedded());
        }
    }
    boolean advanceForTick(TerrainShapes terrain, long start, LongSupplier clock) {
        return advance(terrain, Integer.MAX_VALUE, start + TICK_BUDGET_NANOS, clock);
    }
    boolean advance(TerrainShapes terrain, int maximum, long deadline) {
        return advance(terrain, maximum, deadline, System::nanoTime);
    }
    private boolean advance(TerrainShapes terrain, int maximum, long deadline, LongSupplier clock) {
        while (iterator.hasNext()) {
            if (maximum-- <= 0 || clock.getAsLong() - deadline >= 0) return false;
            if (view == null) view = terrain.view(logical, embedded);
            FieldObject object = iterator.next();
            if (FieldVisibility.activated(object, region.domain()) && !FieldVisibility.damaged(object, mask)) {
                TerrainShapes.Overlap overlap = view.overlap(object);
                float depth = overlap.total();
                BlockPos cell = object.owner();
                if (depth <= TerrainShapes.TOLERANCE + 1e-6f) {
                    visible.add(object); accepted.add(cell); rejected.remove(cell);
                } else if (Float.isFinite(depth) && overlap.embedded() <= TerrainShapes.TOLERANCE + 1e-6f
                        && !accepted.contains(cell)) {
                    Rejected previous = rejected.get(cell);
                    if (previous == null || depth < previous.depth || depth == previous.depth
                            && object.key().compareTo(previous.object.key()) < 0)
                        rejected.put(cell, new Rejected(object, depth));
                }
            }
        }
        // No fallback is final until every candidate has had a chance to pass.
        if (!rejected.isEmpty()) {
            if (fallbacks == null) fallbacks = rejected.values().iterator();
            while (fallbacks.hasNext()) {
                if (clock.getAsLong() - deadline >= 0) return false;
                visible.add(fallbacks.next().object);
                fallbacks.remove();
            }
        }
        return true;
    }
    List<FieldObject> visible() {
        if (iterator.hasNext() || !rejected.isEmpty()) throw new IllegalStateException("Incomplete barricade overlay");
        return List.copyOf(visible);
    }
}
