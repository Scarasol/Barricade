package com.scarasol.barricade.pile;

import java.util.*;
import net.minecraft.core.BlockPos;
import org.joml.Vector3f;

/** Overlay-only predicates. They never call candidate preparation or GPU selection. */
public final class FieldVisibility {
    private FieldVisibility() { }
    public record Shape(BlockPos pos, List<OrientedBox> boxes, boolean precise) {
        public Shape { pos = pos.immutable(); boxes = List.copyOf(boxes); }
    }
    public static boolean activated(FieldObject object, Set<BlockPos> domain) {
        Vector3f center = center(object);
        BlockPos owner = object.owner();
        if (domain.contains(owner.offset((int) Math.floor(center.x), (int) Math.floor(center.y), (int) Math.floor(center.z))))
            return true;
        for (BlockPos cell : cells(object)) if (domain.contains(cell) && hit(object, cube(cell))) return true;
        return false;
    }
    public static boolean damaged(FieldObject object, SpatialMask mask) {
        if (mask.isEmpty()) return false;
        // Damage follows the body center; overhang into a destroyed neighbor is allowed.
        Vector3f center = center(object);
        return mask.contains(object.owner().offset((int) Math.floor(center.x),
                (int) Math.floor(center.y), (int) Math.floor(center.z)));
    }
    /** Temporary embedded coverage tests the body center against the actual transformed shape. */
    public static boolean centerInside(FieldObject object, Shape shape) {
        BlockPos owner = object.owner(), pos = shape.pos;
        Vector3f center = center(object).add(owner.getX() - pos.getX(), owner.getY() - pos.getY(), owner.getZ() - pos.getZ());
        return shape.boxes.stream().anyMatch(box -> box.contains(center.x, center.y, center.z));
    }
    public static boolean hit(FieldObject object, Shape shape) {
        int dx = object.owner().getX() - shape.pos.getX(), dy = object.owner().getY() - shape.pos.getY(),
                dz = object.owner().getZ() - shape.pos.getZ();
        Vector3f center = center(object).add(dx, dy, dz);
        if (shape.boxes.stream().anyMatch(box -> box.contains(center.x, center.y, center.z))) return true;
        for (OrientedBox box : object.piece().colliders(dx, dy, dz))
            for (OrientedBox other : shape.boxes)
                if (shape.precise ? box.intersects(other, .001f) : box.substantiallyIntersects(other)) return true;
        return false;
    }
    public static Shape cube(BlockPos pos) {
        return new Shape(pos, List.of(OrientedBox.axisAligned(0, 0, 0, 1, 1, 1)), false);
    }
    public static Set<BlockPos> cells(FieldObject object) {
        return cells(object, object.piece().colliders(0, 0, 0));
    }
    public static Set<BlockPos> cells(FieldObject object, List<OrientedBox> boxes) {
        Set<BlockPos> result = new HashSet<>();
        Vector3f center = center(object);
        result.add(object.owner().offset((int) Math.floor(center.x), (int) Math.floor(center.y), (int) Math.floor(center.z)));
        for (OrientedBox box : boxes) {
            for (int x = (int) Math.floor(box.minX()); x <= Math.floor(box.maxX()); x++)
                for (int y = (int) Math.floor(box.minY()); y <= Math.floor(box.maxY()); y++)
                    for (int z = (int) Math.floor(box.minZ()); z <= Math.floor(box.maxZ()); z++)
                        result.add(object.owner().offset(x, y, z));
        }
        return result;
    }
    private static Vector3f center(FieldObject object) {
        var c = object.piece().model().collisionCenter();
        return object.piece().transformPoint(c.x, c.y, c.z);
    }
}
