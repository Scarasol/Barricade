package com.scarasol.barricade.pile;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class OrientedBox {
    private final Vector3f center;
    private final Vector3f axisX;
    private final Vector3f axisY;
    private final Vector3f axisZ;
    private final Vector3f half;
    private final Vector3f extent;

    public OrientedBox(Vector3f center, Vector3f half, Quaternionf rotation) {
        this.center = new Vector3f(center);
        this.half = new Vector3f(half);
        this.axisX = rotation.transform(new Vector3f(1, 0, 0));
        this.axisY = rotation.transform(new Vector3f(0, 1, 0));
        this.axisZ = rotation.transform(new Vector3f(0, 0, 1));
        this.extent = new Vector3f(
                Math.abs(axisX.x) * half.x + Math.abs(axisY.x) * half.y + Math.abs(axisZ.x) * half.z,
                Math.abs(axisX.y) * half.x + Math.abs(axisY.y) * half.y + Math.abs(axisZ.y) * half.z,
                Math.abs(axisX.z) * half.x + Math.abs(axisY.z) * half.y + Math.abs(axisZ.z) * half.z);
    }

    public static OrientedBox axisAligned(float minX, float minY, float minZ,
                                         float maxX, float maxY, float maxZ) {
        return new OrientedBox(new Vector3f((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2),
                new Vector3f((maxX - minX) / 2, (maxY - minY) / 2, (maxZ - minZ) / 2), new Quaternionf());
    }
    private OrientedBox(OrientedBox source, float x, float y, float z) {
        center = new Vector3f(source.center).add(x, y, z);
        axisX = source.axisX; axisY = source.axisY; axisZ = source.axisZ;
        half = source.half; extent = source.extent;
    }
    public OrientedBox translated(float x, float y, float z) { return new OrientedBox(this, x, y, z); }

    public boolean intersects(OrientedBox other, float inset) {
        float expansion = Math.max(0, -inset);
        if (Math.abs(center.x - other.center.x) >= extent.x + other.extent.x
                    + expansion * (Math.abs(axisX.x) + Math.abs(axisY.x) + Math.abs(axisZ.x))
                || Math.abs(center.y - other.center.y) >= extent.y + other.extent.y
                    + expansion * (Math.abs(axisX.y) + Math.abs(axisY.y) + Math.abs(axisZ.y))
                || Math.abs(center.z - other.center.z) >= extent.z + other.extent.z
                    + expansion * (Math.abs(axisX.z) + Math.abs(axisY.z) + Math.abs(axisZ.z))) {
            return false;
        }
        float hx = Math.max(0.001f, half.x - inset), hy = Math.max(0.001f, half.y - inset),
                hz = Math.max(0.001f, half.z - inset);
        // JOML 1.10.5 testObOb has an incorrect relative rotation matrix (upstream #379).
        // Test the six face normals and nine edge cross products directly instead.
        for (int i = 0; i < 3; i++) {
            if (separated(other, axis(i), hx, hy, hz) || separated(other, other.axis(i), hx, hy, hz)) {
                return false;
            }
        }
        Vector3f cross = new Vector3f();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                axis(i).cross(other.axis(j), cross);
                if (cross.lengthSquared() > 1.0e-10f && separated(other, cross, hx, hy, hz)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean substantiallyIntersects(OrientedBox other) {
        float inset = Math.min(0.08f, Math.min(half.x, Math.min(half.y, half.z)) * 0.2f);
        return intersects(other, inset);
    }

    /** Minimum separating translation in world units; offsets translate this box into the other's frame. */
    public float penetration(OrientedBox other, float offsetX, float offsetY, float offsetZ) {
        float dx = other.center.x - center.x - offsetX, dy = other.center.y - center.y - offsetY,
                dz = other.center.z - center.z - offsetZ;
        if (Math.abs(dx) >= extent.x + other.extent.x || Math.abs(dy) >= extent.y + other.extent.y
                || Math.abs(dz) >= extent.z + other.extent.z) return 0;
        float depth = Float.POSITIVE_INFINITY;
        for (int i = 0; i < 3; i++) {
            float first = overlap(other, axis(i), dx, dy, dz);
            float second = overlap(other, other.axis(i), dx, dy, dz);
            if (first <= 0 || second <= 0) return 0;
            depth = Math.min(depth, Math.min(first, second));
        }
        Vector3f cross = new Vector3f();
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            axis(i).cross(other.axis(j), cross);
            float lengthSquared = cross.lengthSquared();
            if (lengthSquared <= 1.0e-10f) continue;
            float overlap = overlap(other, cross, dx, dy, dz);
            if (overlap <= 0) return 0;
            depth = Math.min(depth, overlap / (float) Math.sqrt(lengthSquared));
        }
        return depth;
    }

    private float overlap(OrientedBox other, Vector3f direction, float dx, float dy, float dz) {
        return Math.abs(axisX.dot(direction)) * half.x + Math.abs(axisY.dot(direction)) * half.y
                + Math.abs(axisZ.dot(direction)) * half.z
                + Math.abs(other.axisX.dot(direction)) * other.half.x
                + Math.abs(other.axisY.dot(direction)) * other.half.y
                + Math.abs(other.axisZ.dot(direction)) * other.half.z
                - Math.abs(dx * direction.x + dy * direction.y + dz * direction.z);
    }

    private boolean separated(OrientedBox other, Vector3f direction, float hx, float hy, float hz) {
        float distance = Math.abs((other.center.x - center.x) * direction.x
                + (other.center.y - center.y) * direction.y + (other.center.z - center.z) * direction.z);
        float radius = Math.abs(axisX.dot(direction)) * hx + Math.abs(axisY.dot(direction)) * hy
                + Math.abs(axisZ.dot(direction)) * hz + Math.abs(other.axisX.dot(direction)) * other.half.x
                + Math.abs(other.axisY.dot(direction)) * other.half.y
                + Math.abs(other.axisZ.dot(direction)) * other.half.z;
        return distance > radius + 1.0e-6f;
    }

    private Vector3f axis(int index) {
        return index == 0 ? axisX : index == 1 ? axisY : axisZ;
    }

    public boolean contains(float x, float y, float z) {
        float dx = x - center.x, dy = y - center.y, dz = z - center.z;
        return Math.abs(dx * axisX.x + dy * axisX.y + dz * axisX.z) <= half.x
                && Math.abs(dx * axisY.x + dy * axisY.y + dz * axisY.z) <= half.y
                && Math.abs(dx * axisZ.x + dy * axisZ.y + dz * axisZ.z) <= half.z;
    }

    public Vector3f point(float x, float y, float z) {
        return point(x, y, z, new Vector3f());
    }

    public Vector3f point(float x, float y, float z, Vector3f target) {
        return target.set(center).fma(x * half.x, axisX).fma(y * half.y, axisY).fma(z * half.z, axisZ);
    }

    public float volume() {
        return 8 * half.x * half.y * half.z;
    }

    public record Contact(Vector3f point, Vector3f normal, Vector3f tangent) {
    }

    public Contact contactToward(Vector3f toward, boolean above, float offsetFraction) {
        Vector3f[] axes = {axisX, axisY, axisZ};
        Vector3f delta = new Vector3f(toward).sub(center);
        int face = 0;
        float best = -1;
        for (int i = 0; i < 3; i++) {
            float score = above ? Math.abs(axes[i].y) : Math.abs(delta.dot(axes[i])) / half.get(i);
            if (score > best) {
                face = i;
                best = score;
            }
        }
        float sign = (above ? axes[face].y : delta.dot(axes[face])) >= 0 ? 1 : -1;
        Vector3f normal = new Vector3f(axes[face]).mul(sign);
        Vector3f point = new Vector3f(center).fma(sign * half.get(face), axes[face]);
        for (int i = 0; i < 3; i++) {
            if (i != face) {
                float limit = half.get(i) * 0.9f;
                point.fma(Math.max(-limit, Math.min(limit, delta.dot(axes[i]))) * offsetFraction, axes[i]);
            }
        }
        return new Contact(point, normal, new Vector3f(axes[(face + 1) % 3]));
    }

    public float minimumProjection(Vector3f direction, Vector3f origin) {
        return new Vector3f(center).sub(origin).dot(direction) - Math.abs(axisX.dot(direction)) * half.x
                - Math.abs(axisY.dot(direction)) * half.y - Math.abs(axisZ.dot(direction)) * half.z;
    }

    public float minX() { return center.x - extent.x; }
    public float minY() { return center.y - extent.y; }
    public float minZ() { return center.z - extent.z; }
    public float maxX() { return center.x + extent.x; }
    public float maxY() { return center.y + extent.y; }
    public float maxZ() { return center.z + extent.z; }
}
