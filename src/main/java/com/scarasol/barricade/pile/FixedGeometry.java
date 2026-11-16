package com.scarasol.barricade.pile;

import org.joml.Vector3f;

/** Center, three half edges and three cached planes; quantized to 1/1024 blocks. */
public final class FixedGeometry {
    public static final int Q = 1024, STRIDE = 24;
    private FixedGeometry() { }
    public static int[] encode(OrientedBox box) {
        int[] data = new int[STRIDE];
        encode(box, data, 0);
        return data;
    }
    public static void encode(OrientedBox box, int[] data, int offset) {
        Vector3f center = box.point(0, 0, 0);
        data[offset] = Math.round(center.x * Q); data[offset + 1] = Math.round(center.y * Q);
        data[offset + 2] = Math.round(center.z * Q);
        for (int axis = 0; axis < 3; axis++) {
            Vector3f edge = box.point(axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0).sub(center);
            encodeEdge(edge, data, offset + 3 + axis * 3);
        }
        encodePlanes(data, offset);
    }
    static void encode(Vector3f center, Vector3f half, Vector3f[] axes, int[] data, int offset) {
        data[offset] = Math.round(center.x * Q); data[offset + 1] = Math.round(center.y * Q);
        data[offset + 2] = Math.round(center.z * Q);
        Vector3f edge = new Vector3f();
        for (int axis = 0; axis < 3; axis++)
            encodeEdge(edge.set(center).fma(half.get(axis), axes[axis]).sub(center),
                    data, offset + 3 + axis * 3);
        encodePlanes(data, offset);
    }
    private static void encodeEdge(Vector3f edge, int[] data, int offset) {
        float largest = Math.max(Math.abs(edge.x), Math.max(Math.abs(edge.y), Math.abs(edge.z)));
        if (largest > 0 && largest * Q < 1) edge.mul(1 / (largest * Q));
        data[offset] = Math.round(edge.x * Q);
        data[offset + 1] = Math.round(edge.y * Q);
        data[offset + 2] = Math.round(edge.z * Q);
    }
    private static void encodePlanes(int[] data, int offset) {
        for (int i = 0; i < 3; i++) {
            int u = offset + 3 + (i + 1) % 3 * 3, v = offset + 3 + (i + 2) % 3 * 3;
            int x = data[u + 1] * data[v + 2] - data[u + 2] * data[v + 1];
            int y = data[u + 2] * data[v] - data[u] * data[v + 2];
            int z = data[u] * data[v + 1] - data[u + 1] * data[v];
            int divisor = Math.max(1, (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) + 255) / 256);
            x /= divisor; y /= divisor; z /= divisor;
            int n = offset + 12 + i * 4;
            data[n] = x; data[n + 1] = y; data[n + 2] = z;
            data[n + 3] = 0;
            for (int e = offset + 3; e < offset + 12; e += 3)
                data[n + 3] += Math.abs(data[e] * x + data[e + 1] * y + data[e + 2] * z);
        }
    }
    public static int extent(int[] a, int p, int axis) {
        return Math.abs(a[p + 3 + axis]) + Math.abs(a[p + 6 + axis]) + Math.abs(a[p + 9 + axis]);
    }
    public static boolean intersects(int[] a, int p, int dy, int r, int ry) {
        return intersects(a, p, dy, r, ry, 3);
    }
    public static boolean intersects(int[] a, int p, int dy, int r, int ry, int margin) {
        return intersects(a, p, a, r, 0, ry - dy, 0, margin);
    }
    /** Relative fixed-point translation without copying or modifying either encoded shape. */
    public static boolean intersects(int[] a, int p, int[] b, int r, int dx, int dy, int dz, int margin) {
        if (margin < 0) margin = Math.max(margin, -Math.min(shortHalf(a, p), shortHalf(b, r)) / 2);
        for (int axis = 0; axis < 3; axis++) {
            int delta = b[r + axis] - a[p + axis] + (axis == 0 ? dx : axis == 1 ? dy : dz);
            if (Math.abs(delta) > extent(a, p, axis) + extent(b, r, axis) + 3) return false;
        }
        for (int i = 0; i < 3; i++) {
            int n = p + 12 + i * 4, m = r + 12 + i * 4;
            if (separatesAxis(a, p, b, r, dx, dy, dz, a[n], a[n + 1], a[n + 2], margin)
                    || separatesAxis(a, p, b, r, dx, dy, dz, b[m], b[m + 1], b[m + 2], margin)) return false;
            for (int j = 0; j < 3; j++) {
                int u = p + 3 + i * 3, v = r + 3 + j * 3;
                int x = a[u + 1] * b[v + 2] - a[u + 2] * b[v + 1];
                int y = a[u + 2] * b[v] - a[u] * b[v + 2];
                int z = a[u] * b[v + 1] - a[u + 1] * b[v];
                int divisor = Math.max(1, (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) + 255) / 256);
                if (separatesAxis(a, p, b, r, dx, dy, dz, x / divisor, y / divisor, z / divisor, margin)) return false;
            }
        }
        return true;
    }
    private static boolean separatesAxis(int[] a, int p, int[] b, int r, int dx, int dy, int dz,
                                         int x, int y, int z, int margin) {
        int distance = Math.abs((b[r] - a[p] + dx) * x + (b[r + 1] - a[p + 1] + dy) * y
                + (b[r + 2] - a[p + 2] + dz) * z);
        int radii = margin * (Math.abs(x) + Math.abs(y) + Math.abs(z));
        for (int i = 3; i < 12; i += 3)
            radii += Math.abs(a[p + i] * x + a[p + i + 1] * y + a[p + i + 2] * z)
                    + Math.abs(b[r + i] * x + b[r + i + 1] * y + b[r + i + 2] * z);
        return distance > radii;
    }
    private static int shortHalf(int[] a, int p) {
        int smallest = Integer.MAX_VALUE;
        for (int i = 3; i < 12; i += 3)
            smallest = Math.min(smallest, Math.max(Math.abs(a[p + i]),
                    Math.max(Math.abs(a[p + i + 1]), Math.abs(a[p + i + 2]))));
        return smallest;
    }
    /**
     * Inclusive interval of colliding discrete translations of a by t * step, t in [0, maximum].
     * The high/low words hold first/last; -1 means empty. Both input arrays remain unchanged.
     */
    public static long intersectionSteps(int[] a, int p, int[] b, int r, int dx, int dy, int dz,
                                          int sx, int sy, int sz, int maximum, int margin) {
        if (maximum < 0) throw new IllegalArgumentException("Negative sweep length");
        if (margin < 0) margin = Math.max(margin, -Math.min(shortHalf(a, p), shortHalf(b, r)) / 2);
        long interval = maximum;
        for (int axis = 0; axis < 3 && interval != -1; axis++)
            interval = clipSteps(interval, b[r + axis] - a[p + axis] + (axis == 0 ? dx : axis == 1 ? dy : dz),
                    -(axis == 0 ? sx : axis == 1 ? sy : sz), extent(a, p, axis) + extent(b, r, axis) + 3);
        for (int i = 0; i < 3 && interval != -1; i++) {
            int n = p + 12 + i * 4, m = r + 12 + i * 4;
            interval = sweepSteps(a, p, b, r, dx, dy, dz, sx, sy, sz,
                    a[n], a[n + 1], a[n + 2], margin, interval);
            interval = sweepSteps(a, p, b, r, dx, dy, dz, sx, sy, sz,
                    b[m], b[m + 1], b[m + 2], margin, interval);
            for (int j = 0; j < 3 && interval != -1; j++) {
                int u = p + 3 + i * 3, v = r + 3 + j * 3;
                int x = a[u + 1] * b[v + 2] - a[u + 2] * b[v + 1];
                int y = a[u + 2] * b[v] - a[u] * b[v + 2];
                int z = a[u] * b[v + 1] - a[u + 1] * b[v];
                int divisor = Math.max(1, (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) + 255) / 256);
                interval = sweepSteps(a, p, b, r, dx, dy, dz, sx, sy, sz,
                        x / divisor, y / divisor, z / divisor, margin, interval);
            }
        }
        return interval;
    }
    private static long sweepSteps(int[] a, int p, int[] b, int r, int dx, int dy, int dz,
                                    int sx, int sy, int sz, int x, int y, int z, int margin, long interval) {
        if (interval == -1) return -1;
        int distance = (b[r] - a[p] + dx) * x + (b[r + 1] - a[p + 1] + dy) * y
                + (b[r + 2] - a[p + 2] + dz) * z;
        int radius = margin * (Math.abs(x) + Math.abs(y) + Math.abs(z));
        for (int i = 3; i < 12; i += 3)
            radius += Math.abs(a[p + i] * x + a[p + i + 1] * y + a[p + i + 2] * z)
                    + Math.abs(b[r + i] * x + b[r + i + 1] * y + b[r + i + 2] * z);
        return clipSteps(interval, distance, -(sx * x + sy * y + sz * z), radius);
    }
    private static long clipSteps(long interval, int distance, int velocity, int radius) {
        if (velocity == 0) return Math.abs(distance) <= radius ? interval : -1;
        if (velocity < 0) { distance = -distance; velocity = -velocity; }
        int first = Math.max((int) (interval >>> 32), -Math.floorDiv(radius + distance, velocity));
        int last = Math.min((int) interval, Math.floorDiv(radius - distance, velocity));
        return first > last ? -1 : ((long) first << 32) | last;
    }
    /** Translation steps at which a fixed point is contained in this quantized box. */
    public static long containmentSteps(int[] a, int p, int px, int py, int pz,
                                         int sx, int sy, int sz, int maximum) {
        if (maximum < 0) throw new IllegalArgumentException("Negative sweep length");
        long interval = maximum;
        for (int axis = 0; axis < 3 && interval != -1; axis++)
            interval = clipSteps(interval, (axis == 0 ? px : axis == 1 ? py : pz) - a[p + axis],
                    -(axis == 0 ? sx : axis == 1 ? sy : sz), extent(a, p, axis) + 3);
        for (int i = 0; i < 3 && interval != -1; i++) {
            int n = p + 12 + i * 4, x = a[n], y = a[n + 1], z = a[n + 2];
            interval = clipSteps(interval, (px - a[p]) * x + (py - a[p + 1]) * y + (pz - a[p + 2]) * z,
                    -(sx * x + sy * y + sz * z), a[n + 3]);
        }
        return interval;
    }
    /** First discrete downward step that intersects; maximum + 1 means the sweep is clear. */
    public static int firstIntersectionStep(int[] a, int p, int r, int ry, int stepSize, int maximum, int margin) {
        if (margin < 0) margin = Math.max(margin, -Math.min(shortHalf(a, p), shortHalf(a, r)) / 2);
        long interval = (1L << 32) | maximum;
        for (int axis = 0; axis < 3 && interval != 0; axis++)
            interval = clip(interval, a[r + axis] - a[p + axis] + (axis == 1 ? ry : 0),
                    axis == 1 ? stepSize : 0, extent(a, p, axis) + extent(a, r, axis) + 3);
        for (int i = 0; i < 3 && interval != 0; i++) {
            int n = p + 12 + i * 4, m = r + 12 + i * 4;
            interval = sweepAxis(a, p, r, ry, stepSize, a[n], a[n + 1], a[n + 2], margin, interval);
            interval = sweepAxis(a, p, r, ry, stepSize, a[m], a[m + 1], a[m + 2], margin, interval);
            for (int j = 0; j < 3 && interval != 0; j++) {
                int u = p + 3 + i * 3, v = r + 3 + j * 3;
                int x = a[u + 1] * a[v + 2] - a[u + 2] * a[v + 1];
                int y = a[u + 2] * a[v] - a[u] * a[v + 2];
                int z = a[u] * a[v + 1] - a[u + 1] * a[v];
                int divisor = Math.max(1, (Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z))) + 255) / 256);
                interval = sweepAxis(a, p, r, ry, stepSize, x / divisor, y / divisor, z / divisor, margin, interval);
            }
        }
        return interval == 0 ? maximum + 1 : (int) (interval >>> 32);
    }
    private static long sweepAxis(int[] a, int p, int r, int ry, int step, int x, int y, int z,
                                  int margin, long interval) {
        if (interval == 0) return 0;
        int distance = (a[r] - a[p]) * x + (a[r + 1] - a[p + 1] + ry) * y + (a[r + 2] - a[p + 2]) * z;
        int radius = margin * (Math.abs(x) + Math.abs(y) + Math.abs(z));
        for (int i = 3; i < 12; i += 3)
            radius += Math.abs(a[p + i] * x + a[p + i + 1] * y + a[p + i + 2] * z)
                    + Math.abs(a[r + i] * x + a[r + i + 1] * y + a[r + i + 2] * z);
        return clip(interval, distance, step * y, radius);
    }
    private static long clip(long interval, int distance, int velocity, int radius) {
        if (velocity == 0) return Math.abs(distance) <= radius ? interval : 0;
        if (velocity < 0) { distance = -distance; velocity = -velocity; }
        int first = Math.max((int) (interval >>> 32), -Math.floorDiv(radius + distance, velocity));
        int last = Math.min((int) interval, Math.floorDiv(radius - distance, velocity));
        return first > last ? 0 : ((long) first << 32) | last;
    }
    public static boolean contains(int[] a, int p, int dy, int px, int py, int pz) {
        if (Math.abs(px - a[p]) > extent(a, p, 0) + 3 || Math.abs(py - a[p + 1] - dy) > extent(a, p, 1) + 3
                || Math.abs(pz - a[p + 2]) > extent(a, p, 2) + 3) return false;
        for (int i = 0; i < 3; i++) {
            int n = p + 12 + i * 4, x = a[n], y = a[n + 1], z = a[n + 2];
            if (Math.abs((px - a[p]) * x + (py - a[p + 1] - dy) * y + (pz - a[p + 2]) * z)
                    > a[n + 3]) return false;
        }
        return true;
    }
}
