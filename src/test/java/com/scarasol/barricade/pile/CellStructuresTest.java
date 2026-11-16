package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CellStructuresTest {
    @Test
    void denseMembershipMatchesAConcaveSetAcrossNegativeCoordinates() {
        Set<GridPos> cells = new HashSet<>();
        for (int x = -9; x <= 11; x++) for (int y = -3; y <= 4; y++) for (int z = -7; z <= 8; z++)
            if (x < 0 || z < 0 || (x + y + z) % 3 == 0) cells.add(new GridPos(x, y, z));
        CellMask mask = new CellMask(cells);
        for (int x = -11; x <= 13; x++) for (int y = -5; y <= 6; y++) for (int z = -9; z <= 10; z++)
            assertEquals(cells.contains(new GridPos(x, y, z)), mask.contains(x, y, z));
    }

    @Test
    void sparseMembershipRetainsExtremeCoordinatesWithoutOverflowOrAliasing() {
        Set<GridPos> cells = Set.of(new GridPos(Integer.MIN_VALUE, 0, Integer.MAX_VALUE),
                new GridPos(Integer.MAX_VALUE, Integer.MIN_VALUE, 0),
                new GridPos(0, Integer.MAX_VALUE, Integer.MIN_VALUE), new GridPos(0, 0, 0));
        CellMask mask = new CellMask(cells);
        cells.forEach(cell -> assertTrue(mask.contains(cell)));
        assertFalse(mask.contains(1, 0, 0));
        assertFalse(mask.contains(Integer.MAX_VALUE, 0, Integer.MIN_VALUE));
        assertFalse(new CellMask(Set.of()).contains(0, 0, 0));
    }

    @Test
    void boundsShortcutRequiresEveryTouchedCellIncludingInteriorHoles() {
        Set<GridPos> cube = new HashSet<>();
        for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++)
            cube.add(new GridPos(x, y, z));
        OrientedBox interior = OrientedBox.axisAligned(-1.9f, -1.9f, -1.9f, 2.9f, 2.9f, 2.9f);
        assertTrue(new CellMask(cube).containsBounds(interior));
        assertFalse(new CellMask(cube).containsBounds(
                OrientedBox.axisAligned(-2.01f, 0, 0, 1, 1, 1)));
        cube.remove(new GridPos(0, 0, 0));
        assertFalse(new CellMask(cube).containsBounds(interior));
        assertTrue(new CellMask(cube).containsBounds(
                OrientedBox.axisAligned(-1.9f, -1.9f, -1.9f, -1.1f, -1.1f, -1.1f)));
    }

    @Test
    void primitiveKeysAndFallbackAgreeWithAnIntegerTripleMap() {
        CellMap<Integer> actual = new CellMap<>();
        Map<GridPos, Integer> expected = new HashMap<>();
        int[] boundaries = {Integer.MIN_VALUE, -(1 << 21), -(1 << 20) - 1, -(1 << 20),
                -1, 0, 1, (1 << 20) - 1, 1 << 20, 1 << 21, Integer.MAX_VALUE};
        int value = 0;
        for (int x : boundaries) for (int y : boundaries) for (int z : boundaries) {
            actual.put(x, y, z, ++value);
            expected.put(new GridPos(x, y, z), value);
        }
        Random random = new Random(17);
        for (int index = 0; index < 2000; index++) {
            GridPos cell = new GridPos(random.nextInt(20000) - 10000,
                    random.nextInt(1000) - 500, random.nextInt(20000) - 10000);
            actual.put(cell.x(), cell.y(), cell.z(), index);
            expected.put(cell, index);
        }
        expected.forEach((cell, entry) -> assertEquals(entry, actual.get(cell.x(), cell.y(), cell.z())));
        assertNull(actual.get(123, 456, 789));
        actual.put(0, 0, 0, 999);
        assertEquals(999, actual.get(0, 0, 0));
    }
}
