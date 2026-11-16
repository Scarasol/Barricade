package com.scarasol.barricade.pile;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.HashMap;
import java.util.Map;

/** Local coordinates fit in one primitive key; distant coordinates retain all integer bits. */
final class CellMap<V> {
    private static final int LIMIT = 1 << 20;
    private static final long MASK = (1L << 21) - 1;
    private final Long2ObjectOpenHashMap<V> local = new Long2ObjectOpenHashMap<>();
    private Map<GridPos, V> distant;

    V get(int x, int y, int z) {
        if (local(x, y, z)) return local.get(key(x, y, z));
        return distant == null ? null : distant.get(new GridPos(x, y, z));
    }

    void put(int x, int y, int z, V value) {
        if (local(x, y, z)) local.put(key(x, y, z), value);
        else {
            if (distant == null) distant = new HashMap<>();
            distant.put(new GridPos(x, y, z), value);
        }
    }

    private static boolean local(int x, int y, int z) {
        return x >= -LIMIT && x < LIMIT && y >= -LIMIT && y < LIMIT && z >= -LIMIT && z < LIMIT;
    }

    private static long key(int x, int y, int z) {
        return ((long) x & MASK) << 42 | ((long) y & MASK) << 21 | (long) z & MASK;
    }
}
