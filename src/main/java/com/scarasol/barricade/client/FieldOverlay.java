package com.scarasol.barricade.client;

import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import java.util.*;
import net.minecraft.core.BlockPos;

/** A disposable display selection. Suppression never removes objects from the base field. */
final class FieldOverlay {
    final FieldRegion region;
    final long epoch;
    private final Iterator<FieldObject> iterator;
    private final List<FieldObject> visible = new ArrayList<>();
    private final Set<BlockPos> accepted = new HashSet<>();
    private final Map<BlockPos, Rejected> rejected = new HashMap<>();
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
    boolean advance(TerrainShapes terrain, int maximum, long deadline) {
        if (view == null) view = terrain.view(logical, embedded);
        int count = 0;
        while (iterator.hasNext() && count++ < maximum) {
            FieldObject object = iterator.next();
            if (FieldVisibility.activated(object, region.domain()) && !FieldVisibility.damaged(object, mask)) {
                float depth = view.penetration(object);
                BlockPos cell = object.owner();
                if (depth <= TerrainShapes.TOLERANCE + 1e-6f) {
                    visible.add(object); accepted.add(cell); rejected.remove(cell);
                } else if (Float.isFinite(depth) && !accepted.contains(cell)) {
                    Rejected previous = rejected.get(cell);
                    if (previous == null || depth < previous.depth || depth == previous.depth
                            && object.key().compareTo(previous.object.key()) < 0)
                        rejected.put(cell, new Rejected(object, depth));
                }
            }
            if (System.nanoTime() >= deadline) break;
        }
        if (iterator.hasNext()) return false;
        rejected.values().forEach(choice -> visible.add(choice.object));
        rejected.clear();
        return true;
    }
    List<FieldObject> visible() { return List.copyOf(visible); }
}
