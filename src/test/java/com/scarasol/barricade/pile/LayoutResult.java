package com.scarasol.barricade.pile;

import java.util.List;
import java.util.Set;

public record LayoutResult(List<RegionPiece> pieces, boolean continueFilling, Set<GridPos> work) {
    public LayoutResult {
        pieces = List.copyOf(pieces);
        work = Set.copyOf(work);
    }
}
