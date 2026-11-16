package com.scarasol.barricade.pile;

import java.util.List;
import java.util.Set;

public record LayoutInput(long seed, int generation, Set<GridPos> domain,
                          List<PileModel> models, List<RegionPiece> existing,
                          List<OrientedBox> neighbors, List<OrientedBox> obstacles,
                          List<OrientedBox> unloaded, Set<GridPos> active, Set<GridPos> repack,
                          boolean preserveExisting) {
    public LayoutInput(long seed, int generation, Set<GridPos> domain,
                       List<PileModel> models, List<RegionPiece> existing,
                       List<OrientedBox> neighbors, List<OrientedBox> obstacles,
                       List<OrientedBox> unloaded, Set<GridPos> active, Set<GridPos> repack) {
        this(seed, generation, domain, models, existing, neighbors, obstacles, unloaded, active, repack, false);
    }

    public LayoutInput(long seed, int generation, Set<GridPos> domain, List<PileModel> models,
                       List<RegionPiece> existing, List<OrientedBox> neighbors,
                       List<OrientedBox> obstacles, List<OrientedBox> unloaded) {
        this(seed, generation, domain, models, existing, neighbors, obstacles, unloaded, domain, Set.of());
    }

    public LayoutInput {
        domain = Set.copyOf(domain);
        models = List.copyOf(models);
        existing = List.copyOf(existing);
        neighbors = List.copyOf(neighbors);
        obstacles = List.copyOf(obstacles);
        unloaded = List.copyOf(unloaded);
        active = Set.copyOf(active);
        repack = Set.copyOf(repack);
        if (domain.isEmpty() || models.isEmpty() || active.isEmpty()
                || !domain.containsAll(active) || !active.containsAll(repack)) {
            throw new IllegalArgumentException("A pile needs a domain, palette, and active cells");
        }
    }
}
