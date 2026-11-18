package com.scarasol.barricade.pile;

/** Integer reference for GPU candidate evaluation, conflict selection and application. */
public final class LayoutKernel {
    private final int[] a, s;
    public LayoutKernel(int[] input, int[] state) { a = input; s = state; }
    private int box(int id) { return a[12] + id * 24; }
    private int cell(int x, int y, int z) {
        int index = ArchivedBatchLayout.index(a, x, y, z);
        return index < 0 ? 0 : a[a[8] + index];
    }
    private boolean near(int x, int y, int z) {
        int cx = Math.floorDiv(x, 1024), cy = Math.floorDiv(y, 1024), cz = Math.floorDiv(z, 1024);
        if (cell(cx, cy, cz) > 0) return true;
        int rx = x - cx * 1024, ry = y - cy * 1024, rz = z - cz * 1024;
        for (int i = cx - (rx <= 225 ? 1 : 0); i <= cx + (rx >= 799 ? 1 : 0); i++)
            for (int j = cy - (ry <= 225 ? 1 : 0); j <= cy + (ry >= 799 ? 1 : 0); j++)
            for (int k = cz - (rz <= 225 ? 1 : 0); k <= cz + (rz >= 799 ? 1 : 0); k++) if (cell(i, j, k) > 0) {
                int dx = Math.max(Math.max(i * 1024 - x, 0), x - (i + 1) * 1024);
                int dy = Math.max(Math.max(j * 1024 - y, 0), y - (j + 1) * 1024);
                int dz = Math.max(Math.max(k * 1024 - z, 0), z - (k + 1) * 1024);
                if (dx * dx + dy * dy + dz * dz <= 225 * 225) return true;
            }
        return false;
    }
    private int[] bounds(int start, int count, int dy, int margin) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int i = 0; i < count; i++) {
            int p = box(start + i);
            for (int axis = 0; axis < 3; axis++) {
                int center = a[p + axis] + (axis == 1 ? dy : 0), radius = FixedGeometry.extent(a, p, axis);
                b[axis] = Math.min(b[axis], Math.floorDiv(center - radius - margin, 1024));
                b[axis + 3] = Math.max(b[axis + 3], Math.floorDiv(center + radius + margin, 1024));
            }
        }
        return b;
    }
    private boolean fits(int id, int dy) {
        int p = box(id);
        if (a[18] != 0) for (int axis = 0; axis < 3; axis++) {
            int center = a[p + axis] + (axis == 1 ? dy : 0), radius = FixedGeometry.extent(a, p, axis);
            if (center - radius < -a[20] || center + radius > a[19] + a[20]) return false;
        }
        int[] b = bounds(id, 1, dy, 0);
        boolean interior = true;
        for (int x = b[0]; x <= b[3] && interior; x++) for (int y = b[1]; y <= b[4] && interior; y++)
            for (int z = b[2]; z <= b[5]; z++) if (cell(x, y, z) == 0) { interior = false; break; }
        if (interior) return true;
        int nx = Math.max(2, (Math.abs(a[p + 3]) + Math.abs(a[p + 4]) + Math.abs(a[p + 5]) + 127) / 128);
        int ny = Math.max(2, (Math.abs(a[p + 6]) + Math.abs(a[p + 7]) + Math.abs(a[p + 8]) + 127) / 128);
        int nz = Math.max(2, (Math.abs(a[p + 9]) + Math.abs(a[p + 10]) + Math.abs(a[p + 11]) + 127) / 128);
        for (int i = 0; i <= nx; i++) for (int j = 0; j <= ny; j++) for (int k = 0; k <= nz; k++) {
            int x = a[p] + a[p + 3] * (i * 2 - nx) / nx + a[p + 6] * (j * 2 - ny) / ny + a[p + 9] * (k * 2 - nz) / nz;
            int y = a[p + 1] + dy + a[p + 4] * (i * 2 - nx) / nx + a[p + 7] * (j * 2 - ny) / ny + a[p + 10] * (k * 2 - nz) / nz;
            int z = a[p + 2] + a[p + 5] * (i * 2 - nx) / nx + a[p + 8] * (j * 2 - ny) / ny + a[p + 11] * (k * 2 - nz) / nz;
            if (!near(x, y, z)) return false;
        }
        return true;
    }
    private boolean blocked(int id, int dy, boolean ray) {
        return blockedBy(id, dy, ray, ray ? 5 : 15);
    }
    private boolean blockedBy(int id, int dy, boolean ray, int kinds) {
        int p = box(id);
        int[] b = bounds(id, 1, dy, 3);
        for (int x = b[0]; x <= b[3]; x++) for (int y = b[1]; y <= b[4]; y++) for (int z = b[2]; z <= b[5]; z++) {
            int cell = ArchivedBatchLayout.index(a, x, y, z);
            if (cell < 0) continue;
            for (int node = s[s[8] + cell]; node >= 0; node = s[s[9] + node * 2 + 1]) {
                int e = s[6] + s[s[9] + node * 2] * ArchivedBatchLayout.ENTRY;
                // Entries fill their union AABB: visit each once at the first shared grid cell.
                if (x != Math.max(Math.max(b[0], s[e + 4]), a[0])
                        || y != Math.max(Math.max(b[1], s[e + 5]), a[1])
                        || z != Math.max(Math.max(b[2], s[e + 6]), a[2])) continue;
                if ((kinds & (1 << s[e + 3])) == 0) continue;
                for (int i = 0; i < s[e + 1]; i++)
                    if (FixedGeometry.intersects(a, p, dy, box(s[e] + i), s[e + 2],
                            !ray && s[e + 3] == 0 ? -5 : 3)) return true;
            }
        }
        return false;
    }
    private boolean rankingReady() { return a[21] != 0 && (s[0] == 0 || s[4] < a[22]); }
    private boolean needsObject(int owner) {
        return s[s[13] + owner] == 0 && s[s[12] + owner] < 44;
    }
    public boolean rankedBefore(int left, int right) {
        if (right < 0) return true;
        int l = s[23] + left * 3, r = s[23] + right * 3;
        for (int i = 0; i < 3; i++) if (s[l + i] != s[r + i]) return s[l + i] > s[r + i];
        return left < right;
    }
    public void rankCandidate(int id) {
        int m = a[10] + id * 12;
        if (!rankingReady() || !needsObject(a[m + 2])) return;
        int owner = a[9] + a[m + 2] * 3;
        int[] point = {a[owner] * 1024 + 512, a[owner + 1] * 1024 + 512, a[owner + 2] * 1024 + 512};
        int dx = point[0] - a[m + 8], dy = point[1] - a[m + 9], dz = point[2] - a[m + 10];
        move(m, point[0], point[1], point[2]);
        for (int i = 0; i < a[m + 6]; i++) {
            int p = box(a[m + 5] + i);
            a[p] += dx; a[p + 1] += dy; a[p + 2] += dz;
        }
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int i = 0; i < a[m + 1]; i++) {
            int p = box(a[m] + i);
            for (int axis = 0; axis < 3; axis++) {
                int radius = FixedGeometry.extent(a, p, axis);
                lo[axis] = Math.min(lo[axis], a[p + axis] - radius);
                hi[axis] = Math.max(hi[axis], a[p + axis] + radius);
            }
        }
        int[] middle = new int[3], last = new int[3];
        for (int axis = 0; axis < 3; axis++) {
            middle[axis] = (lo[axis] + hi[axis]) / 2;
            last[axis] = Math.max(lo[axis], hi[axis] - 1);
        }
        int domain = near(middle[0], middle[1], middle[2]) ? 1 : 0;
        for (int i = 0; i < 8; i++)
            if (near((i & 1) == 0 ? lo[0] : last[0], (i & 2) == 0 ? lo[1] : last[1],
                    (i & 4) == 0 ? lo[2] : last[2])) domain++;
        for (int axis = 0; axis < 3; axis++) {
            int old = middle[axis];
            middle[axis] = lo[axis];
            if (near(middle[0], middle[1], middle[2])) domain++;
            middle[axis] = last[axis];
            if (near(middle[0], middle[1], middle[2])) domain++;
            middle[axis] = old;
        }
        boolean floor = cell(a[owner], a[owner + 1] - 1, a[owner + 2]) == 0;
        int floorHeight = a[owner + 1] * 1024;
        boolean clear = !floor || lo[1] >= floorHeight;
        boolean supported = floor && Math.abs(lo[1] - floorHeight) <= 36, corridorClear = true;
        for (int i = 0; i < a[m + 1]; i++) {
            if (blockedBy(a[m] + i, 0, false, 13)) clear = false;
            if (blockedBy(a[m] + i, -36, false, 13)) supported = true;
            if (blockedBy(a[m] + i, 0, false, 2)) corridorClear = false;
        }
        int corridors = a[m + 3] == 0 ? 0 : 3;
        if (a[m + 3] == 0) for (int i = 0; i < a[m + 6]; i++)
            if (!blocked(a[m + 5] + i, 0, true)) corridors++;
        if (!corridorClear) corridors = 0;
        int excess = 0;
        for (int axis = 0; axis < 3; axis++)
            excess += Math.max(0, -a[20] - lo[axis]) + Math.max(0, hi[axis] - a[19] - a[20]);
        boolean quota = s[s[16] + a[m + 3]] < a[a[15] + a[m + 3]];
        int rank = s[23] + id * 3;
        s[rank] = (clear ? 65536 : 0) + domain * 1024 + (supported ? 512 : 0) + corridors;
        s[rank + 1] = -excess;
        s[rank + 2] = (quota ? 536870912 : 0) + Math.min(a[m + 4], 536870911);
    }
    public void rankOwner(int owner) {
        if (!rankingReady() || !needsObject(owner)) return;
        int best = -1;
        for (int phase = 0; phase < 5; phase++) {
            int range = a[16] + (phase * a[7] + owner) * 2;
            for (int id = a[range]; id < a[range + 1]; id++) if (rankedBefore(id, best)) best = id;
        }
        s[s[17] + owner] = best;
    }
    public void retainObject() {
        if (!rankingReady()) return;
        for (int owner = 0; owner < a[7]; owner++) {
            if (!needsObject(owner)) continue;
            int best = s[s[17] + owner];
            if (best < 0) continue;
            int m = a[10] + best * 12, result = s[25] + owner * 4;
            s[result] = best; s[result + 1] = a[m + 8]; s[result + 2] = a[m + 9]; s[result + 3] = a[m + 10];
            s[0]++; s[24]++;
            s[s[15] + best] = 1; s[s[13] + owner]++; s[s[16] + a[m + 3]]++;
            cover(a[m], a[m + 1], 0, true);
        }
    }
    private boolean valid(int start, int count, int dy, boolean ray) {
        if (ray && a[18] != 0 && a[20] == 0) for (int i = 0; i < count; i++) {
            int p = box(start + i);
            for (int axis = 0; axis < 3; axis++) {
                int center = a[p + axis] + (axis == 1 ? dy : 0), radius = FixedGeometry.extent(a, p, axis);
                if (center - radius < 0 || center + radius > a[19]) return false;
            }
        }
        for (int i = 0; i < count; i++) if ((!ray && !fits(start + i, dy)) || blocked(start + i, dy, ray)) return false;
        return true;
    }
    private int settle(int start, int count, int centerX, int centerY, int centerZ) {
        for (int q = 0; q < count; q++) if (!fits(start + q, -36)) return 0;
        int steps = 48;
        if (a[18] != 0) for (int step = 1; step <= steps; step++)
            if (cell(Math.floorDiv(centerX, 1024), Math.floorDiv(centerY - step * 36, 1024),
                    Math.floorDiv(centerZ, 1024)) == 1) { steps = step - 1; break; }
        if (steps == 0) return 0;
        for (int q = 0; q < count; q++) {
            int id = start + q, p = box(id);
            int[] b = bounds(id, 1, 0, 3);
            b[1] = Math.floorDiv(a[p + 1] - FixedGeometry.extent(a, p, 1) - 3 - steps * 36, 1024);
            for (int x = b[0]; x <= b[3]; x++) for (int y = b[1]; y <= b[4]; y++) for (int z = b[2]; z <= b[5]; z++) {
                int cell = ArchivedBatchLayout.index(a, x, y, z);
                if (cell < 0) continue;
                for (int node = s[s[8] + cell]; node >= 0; node = s[s[9] + node * 2 + 1]) {
                    int e = s[6] + s[s[9] + node * 2] * ArchivedBatchLayout.ENTRY;
                    if (x != Math.max(Math.max(b[0], s[e + 4]), a[0])
                            || y != Math.max(Math.max(b[1], s[e + 5]), a[1])
                            || z != Math.max(Math.max(b[2], s[e + 6]), a[2])) continue;
                    for (int i = 0; i < s[e + 1]; i++) {
                        steps = Math.min(steps, FixedGeometry.firstIntersectionStep(a, p, box(s[e] + i),
                                s[e + 2], 36, steps, s[e + 3] == 0 ? -5 : 3) - 1);
                        if (steps == 0) return 0;
                    }
                }
            }
        }
        for (int step = 2; step <= steps; step++)
            for (int q = 0; q < count; q++) if (!fits(start + q, -36 * step)) return -36 * (step - 1);
        return -36 * steps;
    }
    public int cover(int start, int count, int dy, boolean mark) {
        int added = 0;
        int[] b = bounds(start, count, dy, 0);
        for (int x = b[0]; x <= b[3]; x++) for (int y = b[1]; y <= b[4]; y++) for (int z = b[2]; z <= b[5]; z++) {
            int owner = cell(x, y, z) - 2;
            if (owner < 0) continue;
            for (int sample = 0; sample < 125; sample++) {
                int address = s[11] + owner * 4 + sample / 32, bit = 1 << (sample % 32);
                if ((s[address] & bit) != 0) continue;
                int samplePoint = a[17] + owner * 375 + sample * 3;
                int px = a[samplePoint], py = a[samplePoint + 1], pz = a[samplePoint + 2];
                for (int i = 0; i < count; i++) if (FixedGeometry.contains(a, box(start + i), dy, px, py, pz)) {
                    added++;
                    if (mark) { s[address] |= bit; s[s[12] + owner]++; s[4]++; }
                    break;
                }
            }
        }
        return added;
    }
    public void append(int start, int count, int dy, int guard) {
        if (s[1] >= s[7]) { s[3] = 1; return; }
        int entry = s[1]++, e = s[6] + entry * ArchivedBatchLayout.ENTRY;
        s[e] = start; s[e + 1] = count; s[e + 2] = dy; s[e + 3] = guard;
        int[] b = bounds(start, count, dy, 3);
        s[e + 4] = b[0]; s[e + 5] = b[1]; s[e + 6] = b[2];
        for (int x = b[0]; x <= b[3]; x++) for (int y = b[1]; y <= b[4]; y++) for (int z = b[2]; z <= b[5]; z++) {
            int cell = ArchivedBatchLayout.index(a, x, y, z);
            if (cell < 0) continue;
            if (s[2] >= s[10]) { s[3] = 1; return; }
            int node = s[2]++, address = s[9] + node * 2;
            s[address] = entry; s[address + 1] = s[s[8] + cell]; s[s[8] + cell] = node;
        }
    }
    private static int hash(int value) {
        value ^= value >>> 16; value *= 0x7feb352d; value ^= value >>> 15; value *= 0x846ca68b;
        return value ^ value >>> 16;
    }
    private void move(int m, int x, int y, int z) {
        int dx = x - a[m + 8], dy = y - a[m + 9], dz = z - a[m + 10];
        for (int i = 0; i < a[m + 1]; i++) {
            int p = box(a[m] + i);
            a[p] += dx; a[p + 1] += dy; a[p + 2] += dz;
        }
        a[m + 8] = x; a[m + 9] = y; a[m + 10] = z;
    }
    private long closest(int x, int y, int z) {
        int cx = Math.floorDiv(x, 1024), cy = Math.floorDiv(y, 1024), cz = Math.floorDiv(z, 1024), best = -1;
        int settled = 0;
        long distance = Long.MAX_VALUE;
        for (int i = cx - 1; i <= cx + 1; i++) for (int j = cy - 1; j <= cy + 1; j++)
            for (int k = cz - 1; k <= cz + 1; k++) {
                int cell = ArchivedBatchLayout.index(a, i, j, k);
                if (cell < 0) continue;
                for (int node = s[s[8] + cell]; node >= 0; node = s[s[9] + node * 2 + 1]) {
                    int e = s[6] + s[s[9] + node * 2] * ArchivedBatchLayout.ENTRY;
                    if (i != Math.max(Math.max(cx - 1, s[e + 4]), a[0])
                            || j != Math.max(Math.max(cy - 1, s[e + 5]), a[1])
                            || k != Math.max(Math.max(cz - 1, s[e + 6]), a[2])) continue;
                    if (s[e + 3] == 1) continue;
                    for (int q = 0; q < s[e + 1]; q++) {
                        int p = box(s[e] + q);
                        long dx = a[p] - x, dy = a[p + 1] + s[e + 2] - y, dz = a[p + 2] - z;
                        long d = dx * dx + dy * dy + dz * dz;
                        if (d < distance || d == distance && p < best) { distance = d; best = p; settled = s[e + 2]; }
                    }
                }
            }
        return best < 0 ? -1L : ((long) best << 32) | (settled & 0xffffffffL);
    }
    private void contact(int m, int p, int anchorDy, int x, int y, int z, int bits, int forcedFace) {
        long[] delta = {x - a[p], y - a[p + 1] - anchorDy, z - a[p + 2]};
        int face = forcedFace >= 0 ? forcedFace / 2 : 0;
        long best = -1, bestLength = 1;
        for (int i = 0; forcedFace < 0 && i < 3; i++) {
            int e = p + 3 + i * 3;
            long length = (long) a[e] * a[e] + (long) a[e + 1] * a[e + 1] + (long) a[e + 2] * a[e + 2];
            long projection = (bits & 8) == 0 ? a[e + 1]
                    : delta[0] * a[e] + delta[1] * a[e + 1] + delta[2] * a[e + 2];
            long value = (bits & 8) == 0 ? projection * projection : Math.abs(projection);
            if (value * bestLength > best * length) { face = i; best = value; bestLength = Math.max(1, length); }
        }
        int edge = p + 3 + face * 3;
        long side = (bits & 8) == 0 ? a[edge + 1]
                : delta[0] * a[edge] + delta[1] * a[edge + 1] + delta[2] * a[edge + 2];
        int sign = forcedFace >= 0 ? (forcedFace % 2 == 1 ? -1 : 1) : side < 0 ? -1 : 1;
        long nx = a[edge] * sign, ny = a[edge + 1] * sign, nz = a[edge + 2] * sign;
        int tx = a[p] + (int) nx, ty = a[p + 1] + anchorDy + (int) ny, tz = a[p + 2] + (int) nz;
        for (int i = 0; i < 3; i++) if (i != face) {
            int e = p + 3 + i * 3;
            long length = Math.max(1, (long) a[e] * a[e] + (long) a[e + 1] * a[e + 1] + (long) a[e + 2] * a[e + 2]);
            long projection = delta[0] * a[e] + delta[1] * a[e + 1] + delta[2] * a[e + 2];
            projection = Math.max(-length * 9 / 10, Math.min(length * 9 / 10, projection));
            if ((bits & 16) == 0) projection /= 4;
            tx += (int) (a[e] * projection / length); ty += (int) (a[e + 1] * projection / length);
            tz += (int) (a[e + 2] * projection / length);
        }
        long minimum = Long.MAX_VALUE;
        for (int i = 0; i < a[m + 1]; i++) {
            int b = box(a[m] + i);
            long projection = (a[b] - (long) a[m + 8]) * nx + (a[b + 1] - (long) a[m + 9]) * ny
                    + (a[b + 2] - (long) a[m + 10]) * nz;
            for (int e = b + 3; e < b + 12; e += 3)
                projection -= Math.abs(a[e] * nx + a[e + 1] * ny + a[e + 2] * nz);
            minimum = Math.min(minimum, projection);
        }
        long gap = 8 * Math.max(Math.abs(nx), Math.max(Math.abs(ny), Math.abs(nz))) - minimum;
        long length = Math.max(1, nx * nx + ny * ny + nz * nz);
        move(m, tx + (int) (nx * gap / length), ty + (int) (ny * gap / length), tz + (int) (nz * gap / length));
    }
    public void evaluate(int id, int phase, int round) {
        int m = a[10] + id * 12;
        if (a[m + 3] != phase) return;
        int target = s[14] + id * 2;
        s[target] = 0; s[target + 1] = 0;
        if (s[s[15] + id] != 0 || s[s[13] + a[m + 2]] >= 72
                || s[s[16] + phase] >= a[a[15] + phase] || (phase == 0 && a[m + 6] != 3)) return;
        boolean contacted = false;
        if (phase != 0) {
            int bits = hash(a[m + 11] + round * 0x9e3779b9);
            int owner = a[m + 2], first = (bits & 0x7fffffff) % 125, sample = -1;
            boolean centered = a[18] != 0 && phase >= 2 && (id - a[a[16] + (phase * a[7] + owner) * 2]) % 4 == 0;
            int middle = a[9] + owner * 3, distance = Integer.MAX_VALUE;
            for (int i = 0; i < 125; i++) {
                int q = (first + i) % 125;
                if ((s[s[11] + owner * 4 + q / 32] & (1 << (q % 32))) != 0) continue;
                if (!centered) { sample = q; break; }
                int point = a[17] + owner * 375 + q * 3;
                int dx = a[point] - (a[middle] * 1024 + 512),
                        dy = a[point + 1] - (a[middle + 1] * 1024 + 512),
                        dz = a[point + 2] - (a[middle + 2] * 1024 + 512);
                int d = dx * dx + dy * dy + dz * dz;
                if (d < distance) { sample = q; distance = d; }
            }
            if (sample < 0) return;
            int c = a[17] + owner * 375 + sample * 3;
            int x = a[c], y = a[c + 1], z = a[c + 2];
            if (a[18] != 0 && a[20] == 0) {
                int[] point = {x, y, z};
                for (int axis = 0; axis < 3; axis++) {
                    int lower = Integer.MIN_VALUE, upper = Integer.MAX_VALUE;
                    for (int i = 0; i < a[m + 1]; i++) {
                        int p = box(a[m] + i), relative = a[p + axis] - a[m + 8 + axis];
                        int radius = FixedGeometry.extent(a, p, axis);
                        lower = Math.max(lower, radius - relative);
                        upper = Math.min(upper, a[19] - radius - relative);
                    }
                    if (lower > upper) return;
                    point[axis] = Math.max(lower, Math.min(upper, point[axis]));
                }
                x = point[0]; y = point[1]; z = point[2];
            }
            long anchor = a[m + 7] >= 0 ? (long) box(a[m + 7] >> 3) << 32
                    : (bits & 3) != 0 ? closest(x, y, z) : -1L;
            if (anchor >= 0) contact(m, (int) (anchor >>> 32), (int) anchor, x, y, z,
                    bits, a[m + 7] >= 0 ? a[m + 7] & 7 : -1);
            else move(m, x, y, z);
            if (!valid(a[m], a[m + 1], 0, false) || a[18] != 0
                    && cell(Math.floorDiv(a[m + 8], 1024), Math.floorDiv(a[m + 9], 1024),
                            Math.floorDiv(a[m + 10], 1024)) == 1) {
                if (anchor < 0) return;
                move(m, x, y, z);
                if (!valid(a[m], a[m + 1], 0, false)) return;
            }
            else contacted = anchor >= 0;
        } else if (!valid(a[m], a[m + 1], 0, false)) return;
        int dy = phase == 0 ? 0 : settle(a[m], a[m + 1], a[m + 8], a[m + 9], a[m + 10]);
        if (phase == 0 && !valid(a[m + 5], a[m + 6], dy, true)) return;
        if (cell(Math.floorDiv(a[m + 8], 1024), Math.floorDiv(a[m + 9] + dy, 1024),
                Math.floorDiv(a[m + 10], 1024)) == 1) return;
        int added = cover(a[m], a[m + 1], dy, false);
        if (added == 0) return;
        int deficit = Math.max(0, 44 - s[s[12] + a[m + 2]]);
        s[target] = added * a[m + 4] + deficit * 8 + (contacted || dy < 0 ? 640 : 0);
        s[target + 1] = dy;
    }
    public void propose(int owner, int phase, int round) {
        s[s[17] + owner] = -1;
        s[s[18] + owner] = 0;
        if (s[3] != 0 || s[s[16] + phase] >= a[a[15] + phase]
                || s[s[13] + owner] >= 72 || (s[4] >= a[7] * 80 && s[s[12] + owner] >= 44)) return;
        int range = a[16] + (phase * a[7] + owner) * 2;
        for (int retry = 0; retry < 8; retry++) {
            int score = 0, best = -1;
            for (int id = a[range]; id < a[range + 1]; id++)
                if (s[s[14] + id * 2] > score) { best = id; score = s[s[14] + id * 2]; }
            if (best < 0) return;
            int m = a[10] + best * 12, dy = s[s[14] + best * 2 + 1];
            boolean blocked = false;
            for (int i = 0; i < a[m + 1]; i++) if (blocked(a[m] + i, dy, false)) { blocked = true; break; }
            if (!blocked && (phase != 0 || valid(a[m + 5], a[m + 6], dy, true))
                    && cover(a[m], a[m + 1], dy, false) > 0) {
                s[s[17] + owner] = best;
                return;
            }
            s[s[14] + best * 2] = 0;
        }
    }
    public void screen(int owner, int phase, int round) {
        if (owner == 0) {
            s[s[20]] = s[4];
            s[s[22]] = s[s[16] + phase];
        }
        s[s[21] + owner] = s[s[12] + owner];
        int candidate = s[s[17] + owner];
        if (candidate < 0) { s[s[18] + owner] = 0; return; }
        int m = a[10] + candidate * 12;
        int added = cover(a[m], a[m + 1], s[s[14] + candidate * 2 + 1], false);
        s[s[19] + candidate] = added;
        if (added == 0) { s[s[18] + owner] = 0; return; }
        s[s[18] + owner] = 1;
        for (int other = 0; other < a[7]; other++) {
            if (other == owner) continue;
            int challenger = s[s[17] + other];
            if (challenger >= 0 && precedes(challenger, other, candidate, owner, round)
                    && conflicts(candidate, challenger, phase)) {
                s[s[18] + owner] = 0;
                return;
            }
        }
    }
    private boolean precedes(int left, int leftOwner, int right, int rightOwner, int round) {
        int leftScore = s[s[14] + left * 2], rightScore = s[s[14] + right * 2];
        if (leftScore != rightScore) return leftScore > rightScore;
        int count = a[7], leftOrder = Math.floorMod(leftOwner - round * 17, count);
        int rightOrder = Math.floorMod(rightOwner - round * 17, count);
        return leftOrder < rightOrder;
    }
    private boolean conflicts(int left, int right, int phase) {
        int lm = a[10] + left * 12, rm = a[10] + right * 12;
        int leftDy = s[s[14] + left * 2 + 1], rightDy = s[s[14] + right * 2 + 1];
        for (int i = 0; i < a[lm + 1]; i++) {
            int lp = box(a[lm] + i);
            for (int j = 0; j < a[rm + 1]; j++)
                if (FixedGeometry.intersects(a, lp, leftDy, box(a[rm] + j), rightDy, -5)) return true;
            if (phase == 0) for (int j = 0; j < a[rm + 6]; j++)
                if (FixedGeometry.intersects(a, lp, leftDy, box(a[rm + 5] + j), rightDy, 3)) return true;
        }
        if (phase == 0) for (int i = 0; i < a[lm + 6]; i++) for (int j = 0; j < a[rm + 1]; j++)
            if (FixedGeometry.intersects(a, box(a[lm + 5] + i), leftDy, box(a[rm] + j), rightDy, 3)) return true;
        return false;
    }
    public void apply(int phase, int round) {
        int phaseOffset = 0;
        for (int i = 0; i < phase; i++) phaseOffset += ArchivedBatchLayout.ROUNDS[i] * a[7];
        for (int k = 0; k < a[7]; k++) {
            if (s[3] != 0 || s[s[16] + phase] >= a[a[15] + phase]) return;
            int owner = (k + round * 17) % a[7], best = s[s[17] + owner];
            if (best < 0 || s[s[18] + owner] == 0 || s[s[13] + owner] >= 72) continue;
            if (s[4] >= a[7] * 80 && s[s[12] + owner] >= 44) continue;
            int m = a[10] + best * 12, dy = s[s[14] + best * 2 + 1];
            if (cover(a[m], a[m + 1], dy, false) == 0) continue;
            int result = s[5] + (phaseOffset + round * a[7] + k) * 4;
            s[result] = best; s[result + 1] = a[m + 8]; s[result + 2] = a[m + 9] + dy; s[result + 3] = a[m + 10];
            s[0]++;
            s[s[15] + best] = round + 1; s[s[13] + owner]++; s[s[16] + phase]++;
            append(a[m], a[m + 1], dy, 0);
            if (phase == 0) append(a[m + 5], a[m + 6], dy, 1);
            cover(a[m], a[m + 1], dy, true);
        }
    }
}
