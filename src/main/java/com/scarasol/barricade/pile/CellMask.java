package com.scarasol.barricade.pile;

import java.util.HashSet;
import java.util.Set;

/** Dense bounded regions use bits; sparse/wide regions retain exact integer coordinates. */
final class CellMask {
    private static final int MAX_BITS = 4 * 1024 * 1024;
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private final int width, depth;
    private final long[] bits;
    private final Set<GridPos> sparse;
    private final boolean solid;

    CellMask(Set<GridPos> cells) {
        int x = Integer.MAX_VALUE, y = x, z = x;
        int a = Integer.MIN_VALUE, b = a, c = a;
        for (GridPos cell : cells) {
            x = Math.min(x, cell.x()); y = Math.min(y, cell.y()); z = Math.min(z, cell.z());
            a = Math.max(a, cell.x()); b = Math.max(b, cell.y()); c = Math.max(c, cell.z());
        }
        minX = x; minY = y; minZ = z; maxX = a; maxY = b; maxZ = c;
        long sx = (long) a - x + 1, sy = (long) b - y + 1, sz = (long) c - z + 1;
        long limit = Math.min(MAX_BITS, Math.max(4096L, cells.size() * 16L));
        boolean dense = !cells.isEmpty() && sx > 0 && sy > 0 && sz > 0
                && sx <= limit && sy <= limit / sx && sz <= limit / (sx * sy);
        width = dense ? (int) sx : 0;
        depth = dense ? (int) sz : 0;
        solid = dense && sx * sy * sz == cells.size();
        if (dense) {
            bits = new long[((int) (sx * sy * sz) + 63) >>> 6];
            sparse = null;
            for (GridPos cell : cells) {
                int index = index(cell.x(), cell.y(), cell.z());
                bits[index >>> 6] |= 1L << (index & 63);
            }
        } else {
            bits = null;
            sparse = new HashSet<>(cells);
        }
    }
    boolean contains(GridPos cell) { return contains(cell.x(), cell.y(), cell.z()); }
    boolean contains(int x, int y, int z) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) return false;
        if (bits == null) return sparse.contains(new GridPos(x, y, z));
        int index = index(x, y, z);
        return (bits[index >>> 6] & 1L << (index & 63)) != 0;
    }
    boolean containsBounds(OrientedBox box) {
        int x0 = (int) Math.floor(box.minX()), y0 = (int) Math.floor(box.minY()),
                z0 = (int) Math.floor(box.minZ());
        int x1 = (int) Math.floor(box.maxX()), y1 = (int) Math.floor(box.maxY()),
                z1 = (int) Math.floor(box.maxZ());
        if (!contains(x0, y0, z0) || !contains(x1, y1, z1)) return false;
        if (solid) return true;
        // Wide sparse bounds are cheaper to leave to the original point test.
        long sx = (long) x1 - x0 + 1, sy = (long) y1 - y0 + 1, sz = (long) z1 - z0 + 1;
        if (sx <= 0 || sy <= 0 || sz <= 0 || sx > 4096 || sy > 4096 / sx || sz > 4096 / (sx * sy))
            return false;
        for (long x = x0; x <= x1; x++) for (long y = y0; y <= y1; y++) for (long z = z0; z <= z1; z++)
            if (!contains((int) x, (int) y, (int) z)) return false;
        return true;
    }
    private int index(int x, int y, int z) {
        return (x - minX) + width * ((z - minZ) + depth * (y - minY));
    }
}
