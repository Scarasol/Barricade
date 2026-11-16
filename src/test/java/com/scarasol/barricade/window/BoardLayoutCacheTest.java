package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class BoardLayoutCacheTest {
    @Test void immutableGroupsRetainOneValidatedLayoutKeyForBothMaterials() {
        for (SealMaterial material : SealMaterial.values()) {
            Set<BoardCell> shape = new HashSet<>(BoardLayoutTest.rectangle(-8, -3, 16, 2));
            Set<BoardCell> supports = new HashSet<>(BoardLayoutTest.supports(shape));
            var group = new BoardGroup(new UUID(1, 2), 1, 42, new BoardPlane(Direction.NORTH, 0),
                    shape, Set.of(), supports, null, material);
            var key = group.layoutKey();
            for (int i = 0; i < 100; i++) assertSame(key, group.layoutKey());
            shape.clear();
            supports.clear();
            assertEquals(32, key.shape().size());
            assertSame(group.shape(), key.shape());
            assertSame(group.supports(), key.supports());
            var loaded = BoardGroup.load(group.save());
            assertEquals(group, loaded);
            assertEquals(group.hashCode(), loaded.hashCode());
            assertEquals(key, loaded.layoutKey());
            assertThrows(UnsupportedOperationException.class, () -> key.shape().clear());
        }
    }
    @Test void splitGroupsReuseTheirSourceIdentityWithoutRevalidatingItOnAccess() {
        var shape = Set.of(new BoardCell(0, 0), new BoardCell(1, 0));
        for (SealMaterial material : SealMaterial.values()) {
            var source = new BoardGroup.LayoutKey(42, new BoardPlane(Direction.DOWN, 64), shape, Set.of(), material);
            var remnant = new BoardRemnant(source, Set.of(new BoardCell(1, 0)), Map.of());
            var child = new BoardGroup(new UUID(1, 2), 2, 42, source.plane(),
                    Set.of(new BoardCell(0, 0)), Set.of(), Set.of(), remnant, material);
            assertSame(source, child.layoutKey());
            assertSame(source, child.layoutKey());
            assertEquals(child, BoardGroup.load(child.save()));
        }
    }
    @Test void singlePassSpanChecksStillHandleNegativeAndExtremeCoordinates() {
        assertTrue(BoardGroup.fits(BoardLayoutTest.rectangle(-16, -16, 16, 16)));
        assertFalse(BoardGroup.fits(Set.of()));
        assertFalse(BoardGroup.fits(Set.of(new BoardCell(-16, 0), new BoardCell(0, 0))));
        assertFalse(BoardGroup.fits(Set.of(new BoardCell(Integer.MIN_VALUE, 0), new BoardCell(Integer.MAX_VALUE, 0))));
        assertFalse(BoardGroup.fits(Set.of(new BoardCell(0, Integer.MIN_VALUE), new BoardCell(0, Integer.MAX_VALUE))));
    }
    @Test void sharedAnalysisSurvivesManyDifferentTransientMasksWithoutStaleGeometry() {
        Set<BoardCell> shape = BoardLayoutTest.rectangle(-8, -3, 16, 2);
        BoardGroup group = BoardLayoutTest.group(shape, Set.of(), BoardLayoutTest.supports(shape));
        var shared = BoardLayout.generate(group);
        List<BoardCell> cells = new ArrayList<>(new TreeSet<>(shape));
        Random random = new Random(193);
        for (int iteration = 0; iteration < 80; iteration++) {
            Set<BoardCell> hidden = new HashSet<>();
            for (BoardCell cell : cells) if (random.nextBoolean()) hidden.add(cell);
            assertEquals(BoardLayout.visible(BoardLayout.generate(group), group, hidden),
                    BoardLayout.visible(shared, group, hidden));
        }
        assertEquals(BoardLayout.visible(BoardLayout.generate(group), group, Set.of()),
                BoardLayout.visible(shared, group, Set.of()));
    }
}
