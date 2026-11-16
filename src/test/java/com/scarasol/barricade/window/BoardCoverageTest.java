package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class BoardCoverageTest {
    @Test void everyIntactCellContainsMeaningfulWoodAcrossShapesSupportsAndSeeds() {
        List<String> sparse = new ArrayList<>();
        var ring = BoardLayoutTest.rectangle(-2, -3, 5, 5);
        ring.removeAll(BoardLayoutTest.rectangle(-1, -2, 3, 3));
        var steps = BoardLayoutTest.rectangle(0, 0, 7, 3);
        for (int u = 0; u < 7; u += 2) steps.remove(new BoardCell(u, 1));
        for (var shape : List.of(BoardLayoutTest.rectangle(0, 0, 1, 1),
                BoardLayoutTest.rectangle(0, 0, 2, 2), BoardLayoutTest.rectangle(0, 0, 3, 3),
                BoardLayoutTest.rectangle(0, 0, 4, 3), BoardLayoutTest.rectangle(-4, -7, 16, 16),
                BoardLayoutTest.rectangle(0, 0, 16, 1), BoardLayoutTest.rectangle(0, 0, 1, 16), ring, steps)) {
            for (var supports : List.of(Set.<BoardCell>of(), BoardLayoutTest.supports(shape))) {
                for (long seed = 0; seed < 64; seed++) {
                    var layout = BoardLayout.generate(group(shape, supports, seed));
                    Map<BoardCell, Double> coverage = new HashMap<>();
                    for (var fragment : BoardLayout.visible(layout, Set.of())) {
                        var plank = BoardLayoutTest.plank(layout, fragment);
                        Area footprint = BoardLayoutTest.footprint(fragment);
                        for (var cell : plank.cells()) {
                            Area covered = new Area(footprint);
                            covered.intersect(new Area(new Rectangle2D.Double(cell.u() * 16, cell.v() * 16, 16, 16)));
                            coverage.merge(cell, BoardLayoutTest.area(covered), Double::sum);
                        }
                    }
                    for (var cell : new TreeSet<>(shape)) {
                        double area = coverage.getOrDefault(cell, 0.0);
                        if (area < 24 - 0.00001) sparse.add("seed=" + seed + ", size=" + shape.size()
                                + ", supports=" + supports.size() + ", cell=" + cell + ", area=" + area);
                    }
                }
            }
        }
        assertTrue(sparse.isEmpty(), sparse.size() + " empty or nearly empty cells: " + sparse.stream().limit(8).toList());
    }

    @Test void mainLayerDensityIsMeasuredWithoutReplacingLargeTiltsWithExtraHorizontalRows() {
        double minimum = 1, total = 0;
        int samples = 0;
        for (int size : new int[]{2, 3, 4, 8, 16}) {
            var shape = BoardLayoutTest.rectangle(-3, -5, size, size);
            Area opening = new Area(new Rectangle2D.Double(-48, -80, size * 16, size * 16));
            for (var supports : List.of(Set.<BoardCell>of(), BoardLayoutTest.supports(shape))) {
                for (long seed = 0; seed < 64; seed++) {
                    var layout = BoardLayout.generate(group(shape, supports, seed));
                    Area wood = new Area();
                    for (var fragment : BoardLayout.visible(layout, Set.of()))
                        if (!BoardLayoutTest.plank(layout, fragment).brace()) wood.add(BoardLayoutTest.footprint(fragment));
                    wood.intersect(opening);
                    double density = BoardLayoutTest.area(wood) / (shape.size() * 256);
                    minimum = Math.min(minimum, density);
                    total += density;
                    samples++;
                    assertTrue(density > 0.15, "Missing main layer: size=" + size + ", seed=" + seed);
                }
            }
        }
        System.out.println("Window-board main density: minimum=" + minimum + ", average=" + total / samples);
    }

    @Test void constructionNeverCreatesTinyBoardsDominatedByTheirTwoEndNails() {
        for (int size : new int[]{1, 2, 3, 16}) for (long seed = 0; seed < 64; seed++) {
            var shape = BoardLayoutTest.rectangle(-3, -5, size, size);
            var layout = BoardLayout.generate(group(shape, BoardLayoutTest.supports(shape), seed));
            for (var plank : layout.planks())
                assertTrue(plank.length() >= 12, "Tiny constructed board: " + plank);
        }
    }

    private static BoardGroup group(Set<BoardCell> shape, Set<BoardCell> supports, long seed) {
        return new BoardGroup(new UUID(1, 2), 1, seed, new BoardPlane(Direction.NORTH, 0), shape, Set.of(), supports);
    }
}
