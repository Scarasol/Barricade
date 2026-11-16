package com.scarasol.barricade.pile;

import java.util.*;

/** One finite region, expressed relative to its fixed integer world origin. */
public record PackingInput(long seed, int generation, Set<GridPos> domain, List<PileModel> models,
                           List<RegionPiece> existing, List<OrientedBox> neighbors, int side,
                           boolean interior, Set<GridPos> active, List<OrientedBox> guards) {
    public PackingInput(long seed, int generation, Set<GridPos> domain, List<PileModel> models,
                        List<RegionPiece> existing, List<OrientedBox> neighbors, int side,
                        boolean interior, Set<GridPos> active) {
        this(seed, generation, domain, models, existing, neighbors, side, interior, active, List.of());
    }
    public PackingInput(long seed, int generation, Set<GridPos> domain, List<PileModel> models,
                        List<RegionPiece> existing, List<OrientedBox> neighbors, int side, boolean interior) {
        this(seed, generation, domain, models, existing, neighbors, side, interior, domain, List.of());
    }
    public PackingInput {
        domain = Set.copyOf(domain); models = List.copyOf(models);
        existing = List.copyOf(existing); neighbors = List.copyOf(neighbors);
        active = Set.copyOf(active);
        guards = List.copyOf(guards);
        if (active.isEmpty() || !domain.containsAll(active) || models.isEmpty() || side < 2 || side > 8)
            throw new IllegalArgumentException("Invalid finite packing region");
    }
}
