package com.scarasol.barricade.pile;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Shared pose construction used by CPU candidate preparation and the GPU layout solver. */
public final class ArchivedPileGeometry {
    private ArchivedPileGeometry() { }

    static Quaternionf panelOrientation(PileModel model, GridPos cell, CellMask domain,
                                       SpatialIndex obstacles, Random random) {
        Vector3f span = model.span();
        float min = Math.min(span.x, Math.min(span.y, span.z));
        float max = Math.max(span.x, Math.max(span.y, span.z));
        Vector3f thin = span.x == min ? new Vector3f(1, 0, 0)
                : span.y == min ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1);
        Vector3f longAxis = span.x == max ? new Vector3f(1, 0, 0)
                : span.y == max ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1);
        List<Vector3f> outside = new ArrayList<>();
        for (Vector3f direction : List.of(new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0),
                new Vector3f(0, 0, 1), new Vector3f(0, 0, -1), new Vector3f(0, 1, 0))) {
            GridPos next = new GridPos(cell.x() + (int) direction.x, cell.y() + (int) direction.y,
                    cell.z() + (int) direction.z);
            if (!domain.contains(next) && !obstacles.contains(next.x() + 0.5f, next.y() + 0.5f, next.z() + 0.5f)) {
                outside.add(direction);
            }
        }
        Vector3f normal = outside.isEmpty() ? new Vector3f(0, 1, 0)
                : outside.get(random.nextInt(outside.size()));
        Quaternionf rotation = new Quaternionf().rotationTo(thin, normal);
        Vector3f desired = Math.abs(normal.y) < 0.8f ? new Vector3f(0, 1, 0)
                : random.nextBoolean() ? new Vector3f(1, 0, 0) : new Vector3f(0, 0, 1);
        Vector3f mapped = rotation.transform(new Vector3f(longAxis));
        float spin = (float) Math.atan2(normal.dot(new Vector3f(mapped).cross(desired)), mapped.dot(desired));
        if (random.nextFloat() < 0.3f) spin += (float) (Math.PI / 2);
        return rotation.rotateAxis(spin + (random.nextFloat() - 0.5f) * 0.5f, thin.x, thin.y, thin.z)
                .rotateX((random.nextFloat() - 0.5f) * 0.25f)
                .rotateZ((random.nextFloat() - 0.5f) * 0.25f);
    }

    static Quaternionf orientation(PileModel model, Random random, OrientedBox.Contact contact) {
        Vector3f span = model.span();
        float min = Math.min(span.x, Math.min(span.y, span.z));
        float max = Math.max(span.x, Math.max(span.y, span.z));
        float yaw = random.nextInt(4) * (float) (Math.PI / 2)
                + (float) (Math.PI / 12) + random.nextFloat() * (float) (Math.PI / 3);
        if (min < max * 0.6f) {
            Vector3f thin = span.x == min ? new Vector3f(1, 0, 0)
                    : span.y == min ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1);
            Vector3f normal = contact == null ? new Vector3f(0, 1, 0) : contact.normal();
            if (contact == null && random.nextFloat() < 0.22f) {
                normal = new Vector3f((float) Math.cos(yaw), 0, (float) Math.sin(yaw));
            }
            Quaternionf rotation = new Quaternionf().rotationTo(thin, normal);
            boolean followSurface = contact != null && random.nextFloat() < 0.85f;
            if (followSurface) {
                Vector3f longAxis = span.x == max ? new Vector3f(1, 0, 0)
                        : span.y == max ? new Vector3f(0, 1, 0) : new Vector3f(0, 0, 1);
                Vector3f mapped = rotation.transform(new Vector3f(longAxis));
                yaw = (float) Math.atan2(normal.dot(new Vector3f(mapped).cross(contact.tangent())),
                        mapped.dot(contact.tangent())) + (random.nextFloat() - 0.5f) * 0.08f;
            }
            float tilt = followSurface ? 0.035f : 0.35f;
            return rotation.rotateAxis(yaw, thin.x, thin.y, thin.z)
                    .rotateX((random.nextFloat() - 0.5f) * tilt)
                    .rotateZ((random.nextFloat() - 0.5f) * tilt);
        }
        Quaternionf result = new Quaternionf().rotationYXZ(yaw,
                (random.nextFloat() - 0.5f) * 0.5f, (random.nextFloat() - 0.5f) * 0.5f);
        return random.nextFloat() < 0.35f ? result.rotateZ((float) (Math.PI / 2)) : result;
    }

    static RegionPiece atCenter(PileModel model, Vector3f center, float scale,
                                Quaternionf rotation, GridPos owner) {
        Vector3f offset = model.collisionCenter().sub(model.pivotX(), model.pivotY(), model.pivotZ())
                .mul(model.sizeX() * scale, model.sizeY() * scale, model.sizeZ() * scale);
        rotation.transform(offset);
        return new RegionPiece(owner, PilePiece.create(model, center.x - offset.x - owner.x(),
                center.y - offset.y - owner.y(), center.z - offset.z - owner.z(), scale, rotation));
    }
}
