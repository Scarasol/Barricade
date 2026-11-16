package com.scarasol.barricade.pile;

import it.unimi.dsi.fastutil.longs.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Absolute-coordinate index with dense 8-cube tiles and full-tuple overflow keys. */
final class FieldSlots<V> {
    private final Tiles<Object[]> tiles = new Tiles<>();
    @FunctionalInterface interface Coordinates { void accept(int x, int y, int z); }
    static boolean fits(int x, int y, int z) {
        return x >= -(1 << 25) && x < 1 << 25 && z >= -(1 << 25) && z < 1 << 25 && y >= -2048 && y < 2048;
    }
    static long key(int x, int y, int z) {
        return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | (long) y & 4095;
    }
    static GridPos position(long key) { return new GridPos((int) (key >> 38), (int) (key << 52 >> 52), (int) (key << 26 >> 38)); }
    @SuppressWarnings("unchecked")
    V get(int x, int y, int z) {
        Object[] tile = tiles.get(x >> 3, y >> 3, z >> 3);
        return tile == null ? null : (V) tile[(x & 7) << 6 | (y & 7) << 3 | z & 7];
    }
    void put(GridPos pos, V value) {
        put(pos.x(), pos.y(), pos.z(), value);
    }
    void put(int x, int y, int z, V value) {
        Object[] tile = tiles.get(x >> 3, y >> 3, z >> 3);
        if (tile == null) { tile = new Object[512]; tiles.put(x >> 3, y >> 3, z >> 3, tile); }
        tile[(x & 7) << 6 | (y & 7) << 3 | z & 7] = value;
    }
    void visitBox(int ax, int ay, int az, int bx, int by, int bz, Consumer<V> visitor) {
        anyInBox(ax, ay, az, bx, by, bz, value -> { visitor.accept(value); return false; });
    }
    /** Concurrent reads are allowed only while the owning thread leaves this index unchanged. */
    void visitBoxReadOnly(int ax, int ay, int az, int bx, int by, int bz, Consumer<V> visitor) {
        anyInBox(ax, ay, az, bx, by, bz, value -> { visitor.accept(value); return false; }, true);
    }
    boolean anyInBox(int ax, int ay, int az, int bx, int by, int bz, Predicate<V> predicate) {
        return anyInBox(ax, ay, az, bx, by, bz, predicate, false);
    }
    boolean anyInBoxReadOnly(int ax, int ay, int az, int bx, int by, int bz, Predicate<V> predicate) {
        return anyInBox(ax, ay, az, bx, by, bz, predicate, true);
    }
    @SuppressWarnings("unchecked")
    private boolean anyInBox(int ax, int ay, int az, int bx, int by, int bz, Predicate<V> predicate, boolean readOnly) {
        if (ax > bx || ay > by || az > bz) return false;
        for (int tx = ax >> 3; tx <= bx >> 3; tx++)
            for (int ty = ay >> 3; ty <= by >> 3; ty++)
                for (int tz = az >> 3; tz <= bz >> 3; tz++) {
                    Object[] tile = readOnly ? tiles.read(tx, ty, tz) : tiles.get(tx, ty, tz);
                    if (tile == null) continue;
                    int x0 = tx == ax >> 3 ? ax & 7 : 0, x1 = tx == bx >> 3 ? bx & 7 : 7;
                    int y0 = ty == ay >> 3 ? ay & 7 : 0, y1 = ty == by >> 3 ? by & 7 : 7;
                    int z0 = tz == az >> 3 ? az & 7 : 0, z1 = tz == bz >> 3 ? bz & 7 : 7;
                    for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++)
                        for (int z = z0; z <= z1; z++) {
                            V value = (V) tile[x << 6 | y << 3 | z];
                            if (value != null && predicate.test(value)) return true;
                        }
                }
        return false;
    }
    void clear() { tiles.clear(); }

    @FunctionalInterface private interface TileVisitor<T> { void accept(int x, int y, int z, T value); }
    private record Tile<T>(GridPos pos, T value) { }
    private static final class Tiles<T> {
        private final Long2ObjectOpenHashMap<T> packed = new Long2ObjectOpenHashMap<>();
        private final Map<GridPos, T> distant = new HashMap<>();
        private int lastX, lastY, lastZ;
        private boolean cached;
        private T last;
        T get(int x, int y, int z) {
            if (cached && lastX == x && lastY == y && lastZ == z) return last;
            lastX = x; lastY = y; lastZ = z; cached = true;
            return last = read(x, y, z);
        }
        T read(int x, int y, int z) {
            return fits(x, y, z) ? packed.get(key(x, y, z)) : distant.get(new GridPos(x, y, z));
        }
        void put(int x, int y, int z, T value) {
            if (fits(x, y, z)) packed.put(key(x, y, z), value);
            else distant.put(new GridPos(x, y, z), value);
            lastX = x; lastY = y; lastZ = z; cached = true; last = value;
        }
        List<Tile<T>> entries() {
            List<Tile<T>> result = new ArrayList<>(packed.size() + distant.size());
            for (var entry : packed.long2ObjectEntrySet()) result.add(new Tile<>(position(entry.getLongKey()), entry.getValue()));
            distant.forEach((pos, value) -> result.add(new Tile<>(pos, value)));
            return result;
        }
        void visit(TileVisitor<T> visitor) {
            for (var entry : packed.long2ObjectEntrySet()) {
                long key = entry.getLongKey();
                visitor.accept((int) (key >> 38), (int) (key << 52 >> 52), (int) (key << 26 >> 38), entry.getValue());
            }
            distant.forEach((pos, value) -> visitor.accept(pos.x(), pos.y(), pos.z(), value));
        }
        void clear() { packed.clear(); distant.clear(); cached = false; last = null; }
    }
    static final class Slots extends AbstractSet<GridPos> {
        private final Tiles<long[]> tiles = new Tiles<>();
        private int size;
        boolean add(int x, int y, int z) {
            long[] tile = tile(x >> 3, y >> 3, z >> 3);
            long bit = 1L << ((y & 7) << 3 | z & 7);
            int word = x & 7;
            if ((tile[word] & bit) != 0) return false;
            tile[word] |= bit; size++; return true;
        }
        void addBox(int ax, int ay, int az, int bx, int by, int bz) {
            if (ax > bx || ay > by || az > bz) return;
            for (int tx = ax >> 3; tx <= bx >> 3; tx++)
                for (int ty = ay >> 3; ty <= by >> 3; ty++)
                    for (int tz = az >> 3; tz <= bz >> 3; tz++) {
                        long[] tile = tile(tx, ty, tz);
                        int x0 = tx == ax >> 3 ? ax & 7 : 0, x1 = tx == bx >> 3 ? bx & 7 : 7;
                        int y0 = ty == ay >> 3 ? ay & 7 : 0, y1 = ty == by >> 3 ? by & 7 : 7;
                        int z0 = tz == az >> 3 ? az & 7 : 0, z1 = tz == bz >> 3 ? bz & 7 : 7;
                        long line = (255L >>> (7 - z1 + z0)) << z0, mask = 0;
                        for (int y = y0; y <= y1; y++) mask |= line << (y * 8);
                        for (int x = x0; x <= x1; x++) {
                            size += Long.bitCount(mask & ~tile[x]); tile[x] |= mask;
                        }
                    }
        }
        private long[] tile(int x, int y, int z) {
            long[] value = tiles.get(x, y, z);
            if (value == null) { value = new long[8]; tiles.put(x, y, z, value); }
            return value;
        }
        @Override public boolean add(GridPos pos) { return add(pos.x(), pos.y(), pos.z()); }
        @Override public boolean contains(Object object) {
            if (!(object instanceof GridPos pos)) return false;
            return contains(pos.x(), pos.y(), pos.z());
        }
        boolean contains(int x, int y, int z) {
            long[] tile = tiles.get(x >> 3, y >> 3, z >> 3);
            return tile != null && (tile[x & 7] & 1L << ((y & 7) << 3 | z & 7)) != 0;
        }
        void visit(Coordinates visitor) {
            tiles.visit((tx, ty, tz, tile) -> {
                int x = tx << 3, y = ty << 3, z = tz << 3;
                for (int word = 0; word < 8; word++) {
                    long bits = tile[word];
                    while (bits != 0) {
                        int bit = Long.numberOfTrailingZeros(bits);
                        bits &= bits - 1;
                        visitor.accept(x + word, y + (bit >> 3), z + (bit & 7));
                    }
                }
            });
        }
        @Override public int size() { return size; }
        @Override public void clear() { tiles.clear(); size = 0; }
        @Override public Iterator<GridPos> iterator() {
            Iterator<Tile<long[]>> entries = tiles.entries().iterator();
            return new Iterator<>() {
                Tile<long[]> tile;
                int word = 8, nextOffset, lastOffset;
                long remaining;
                long[] last;
                GridPos next;
                @Override public boolean hasNext() {
                    while (next == null) {
                        while (remaining == 0) {
                            if (word == 8) {
                                if (!entries.hasNext()) return false;
                                tile = entries.next(); word = 0;
                            }
                            remaining = tile.value[word];
                            if (remaining == 0) word++;
                        }
                        int x = word, bit = Long.numberOfTrailingZeros(remaining);
                        remaining &= remaining - 1;
                        if (remaining == 0) word++;
                        nextOffset = x << 6 | bit;
                        next = new GridPos((tile.pos.x() << 3) + x, (tile.pos.y() << 3) + (bit >> 3),
                                (tile.pos.z() << 3) + (bit & 7));
                    }
                    return true;
                }
                @Override public GridPos next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    GridPos value = next; next = null; last = tile.value; lastOffset = nextOffset; return value;
                }
                @Override public void remove() {
                    if (last == null) throw new IllegalStateException();
                    last[lastOffset >> 6] &= ~(1L << (lastOffset & 63)); size--; last = null;
                }
            };
        }
    }
}
