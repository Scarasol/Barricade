package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class FieldSlotsTest {
    @Test void frozenBoxVisitsAreSafeAcrossConcurrentReadersAndTheOwnerCache() throws Exception {
        FieldSlots<GridPos> index = new FieldSlots<>();
        List<GridPos> origins = List.of(new GridPos(-17, -9, 7), new GridPos(1 << 29, -32770, -(1 << 29)));
        for (GridPos origin : origins)
            for (int x = 0; x < 24; x++) for (int y = 0; y < 9; y++) for (int z = 0; z < 17; z++) {
                GridPos pos = new GridPos(origin.x() + x, origin.y() + y, origin.z() + z);
                index.put(pos, pos);
            }
        ExecutorService readers = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> checks = new ArrayList<>();
            for (int reader = 0; reader < 4; reader++) {
                final int seed = reader;
                checks.add(readers.submit(() -> {
                    Random random = new Random(seed);
                    for (int i = 0; i < 400; i++) {
                        GridPos origin = origins.get(i % origins.size());
                        int x = random.nextInt(16), y = random.nextInt(5), z = random.nextInt(9);
                        Set<GridPos> actual = new HashSet<>();
                        index.visitBoxReadOnly(origin.x() + x, origin.y() + y, origin.z() + z,
                                origin.x() + x + 8, origin.y() + y + 4, origin.z() + z + 8, actual::add);
                        assertEquals(9 * 5 * 9, actual.size());
                        for (GridPos pos : actual) {
                            assertTrue(pos.x() >= origin.x() + x && pos.x() <= origin.x() + x + 8);
                            assertTrue(pos.y() >= origin.y() + y && pos.y() <= origin.y() + y + 4);
                            assertTrue(pos.z() >= origin.z() + z && pos.z() <= origin.z() + z + 8);
                        }
                    }
                }));
            }
            for (int i = 0; i < 2000; i++) {
                GridPos origin = origins.get(i % origins.size());
                assertEquals(origin, index.get(origin.x(), origin.y(), origin.z()));
            }
            for (Future<?> check : checks) check.get(20, TimeUnit.SECONDS);
        } finally {
            readers.shutdownNow();
            assertTrue(readers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
    @Test void packedCoordinatesAreBijectiveAtSignedBoundaries() {
        Set<Long> keys = new HashSet<>();
        for (int x : new int[]{-(1 << 25), -30000000, -17, -1, 0, 16, 30000000, (1 << 25) - 1})
            for (int y : new int[]{-2048, -1, 0, 319, 2047})
                for (int z : new int[]{-(1 << 25), -17, -1, 0, 16, (1 << 25) - 1}) {
                    assertTrue(FieldSlots.fits(x, y, z));
                    long key = FieldSlots.key(x, y, z);
                    assertTrue(keys.add(key));
                    assertEquals(new GridPos(x, y, z), FieldSlots.position(key));
                }
    }
    @Test void outOfRangeTuplesCannotAliasPackedCoordinatesAndIteratorRemovalIsExact() {
        List<GridPos> values = List.of(new GridPos(0, 0, 0), new GridPos(1 << 26, 0, 0),
                new GridPos(0, 4096, 0), new GridPos(0, 0, 1 << 26), new GridPos(Integer.MIN_VALUE, -2049, Integer.MAX_VALUE));
        FieldSlots<String> index = new FieldSlots<>();
        FieldSlots.Slots slots = new FieldSlots.Slots();
        for (GridPos pos : values) { slots.add(pos); index.put(pos, pos.toString()); }
        assertEquals(Set.copyOf(values), slots);
        Set<GridPos> primitive = new HashSet<>();
        slots.visit((x, y, z) -> assertTrue(primitive.add(new GridPos(x, y, z))));
        assertEquals(Set.copyOf(values), primitive);
        for (GridPos pos : values) assertEquals(pos.toString(), index.get(pos.x(), pos.y(), pos.z()));
        slots.removeIf(pos -> pos.y() != 0);
        assertEquals(3, slots.size());
        index.clear();
        for (GridPos pos : values) assertNull(index.get(pos.x(), pos.y(), pos.z()));
    }
    @Test void bitmapBoxUnionsMatchIndividualCoordinatesAcrossSignedTileBoundaries() {
        Set<GridPos> expected = new HashSet<>();
        FieldSlots.Slots actual = new FieldSlots.Slots();
        Random random = new Random(42);
        for (int i = 0; i < 100; i++) {
            int x = random.nextInt(40) - 20, y = random.nextInt(40) - 20, z = random.nextInt(40) - 20;
            int bx = x + random.nextInt(12), by = y + random.nextInt(12), bz = z + random.nextInt(12);
            actual.addBox(x, y, z, bx, by, bz);
            for (int a = x; a <= bx; a++) for (int b = y; b <= by; b++) for (int c = z; c <= bz; c++)
                expected.add(new GridPos(a, b, c));
        }
        assertEquals(expected, actual);
        assertEquals(expected.size(), actual.size());
        actual.removeIf(pos -> (pos.x() & 1) == 0);
        expected.removeIf(pos -> (pos.x() & 1) == 0);
        assertEquals(expected, actual);
        assertEquals(expected.size(), actual.size());
        Set<GridPos> primitive = new HashSet<>();
        actual.visit((x, y, z) -> {
            assertTrue(actual.contains(x, y, z));
            assertTrue(primitive.add(new GridPos(x, y, z)));
        });
        assertEquals(expected, primitive);
        for (GridPos pos : expected) assertTrue(actual.contains(pos));
        actual.clear(); assertTrue(actual.isEmpty()); assertFalse(actual.iterator().hasNext());
    }
    @Test void tiledBoxVisitsMatchExactPointLookupsIncludingDistantAndEmptyTiles() {
        Random random = new Random(2718);
        for (GridPos origin : List.of(new GridPos(-17, -9, 7), new GridPos(0, 0, 0),
                new GridPos(1 << 29, -32770, -(1 << 29)))) {
            FieldSlots<GridPos> index = new FieldSlots<>();
            Set<GridPos> stored = new HashSet<>();
            for (int i = 0; i < 2000; i++) {
                GridPos pos = new GridPos(origin.x() + random.nextInt(65), origin.y() + random.nextInt(33),
                        origin.z() + random.nextInt(65));
                stored.add(pos); index.put(pos, pos);
            }
            for (int i = 0; i < 100; i++) {
                int ax = origin.x() - 10 + random.nextInt(75), ay = origin.y() - 10 + random.nextInt(45),
                        az = origin.z() - 10 + random.nextInt(75);
                int bx = ax + random.nextInt(20), by = ay + random.nextInt(20), bz = az + random.nextInt(20);
                List<GridPos> actual = new ArrayList<>();
                index.visitBox(ax, ay, az, bx, by, bz, actual::add);
                Set<GridPos> expected = new HashSet<>();
                for (GridPos pos : stored)
                    if (pos.x() >= ax && pos.x() <= bx && pos.y() >= ay && pos.y() <= by
                            && pos.z() >= az && pos.z() <= bz) expected.add(pos);
                assertEquals(expected, Set.copyOf(actual)); assertEquals(expected.size(), actual.size());
                assertEquals(!expected.isEmpty(), index.anyInBox(ax, ay, az, bx, by, bz, ignored -> true));
                assertFalse(index.anyInBox(ax, ay, az, bx, by, bz, ignored -> false));
                if (!expected.isEmpty()) {
                    GridPos chosen = expected.iterator().next();
                    assertTrue(index.anyInBox(ax, ay, az, bx, by, bz, chosen::equals));
                }
            }
        }
    }
}
