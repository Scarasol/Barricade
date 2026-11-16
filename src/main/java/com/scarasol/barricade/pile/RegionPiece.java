package com.scarasol.barricade.pile;

import java.util.List;

/** Storage ownership is independent of the space occupied by the model. */
public record RegionPiece(GridPos owner, PilePiece piece) {
    public List<OrientedBox> colliders() {
        return piece.colliders(owner.x(), owner.y(), owner.z());
    }
}
