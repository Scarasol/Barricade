package com.scarasol.barricade.pile;

import com.scarasol.barricade.recipe.FieldDefinition;
import net.minecraft.core.BlockPos;

/** Full identity, never a hash-only identifier. Owner is an immutable storage origin. */
public record FieldObject(Key key, BlockPos owner, PilePiece piece) {
    public FieldObject { owner = owner.immutable(); }
    public record Key(String dimension, FieldDefinition definition, String geometry, GridPos slot,
                      int layer, int trial) implements Comparable<Key> {
        @Override public int compareTo(Key other) {
            int c = dimension.compareTo(other.dimension);
            if (c == 0) c = Long.compare(definition.seed(), other.definition.seed());
            if (c == 0) c = Integer.compare(definition.algorithm(), other.definition.algorithm());
            if (c == 0) c = definition.parameters().compareTo(other.definition.parameters());
            if (c == 0) c = definition.palette().compareTo(other.definition.palette());
            if (c == 0) c = geometry.compareTo(other.geometry);
            if (c == 0) c = slot.compareTo(other.slot);
            if (c == 0) c = Integer.compare(layer, other.layer);
            return c == 0 ? Integer.compare(trial, other.trial) : c;
        }
    }
}
