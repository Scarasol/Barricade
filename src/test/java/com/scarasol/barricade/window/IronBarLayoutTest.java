package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class IronBarLayoutTest {
    private static Set<BoardCell> rectangle(int width, int height) {
        Set<BoardCell> shape = new HashSet<>();
        for (int u = 0; u < width; u++) for (int v = 0; v < height; v++) shape.add(new BoardCell(u, v));
        return shape;
    }
    private static BoardLayout.Layout layout(Set<BoardCell> shape, long seed) {
        return BoardLayout.generate(new BoardGroup.LayoutKey(seed, new BoardPlane(Direction.NORTH, 0),
                shape, BoardLayout.boundary(shape), SealMaterial.IRON));
    }
    private static List<BoardLayout.Plank> rods(BoardLayout.Layout layout) {
        return layout.planks().stream().filter(plank -> !plank.brace()).toList();
    }
    @Test void rectanglesHaveContinuousIntegerSizedRodsTwoWeldsAndOnlyAxisAlignedFrames() {
        for (int width : new int[]{1, 2, 3, 8, 16}) for (int height : new int[]{1, 2, 8, 16})
            for (long seed = 0; seed < 16; seed++) {
                var layout = layout(rectangle(width, height), seed);
                assertEquals(layout, layout(rectangle(width, height), seed));
                assertEquals(4, layout.planks().stream().filter(BoardLayout.Plank::brace).count());
                assertTrue(rods(layout).size() >= width * 2);
                Set<Long> ids = new HashSet<>();
                for (var plank : layout.planks()) {
                    assertTrue(ids.add(plank.id()));
                    assertEquals(2, plank.width());
                    assertEquals(2, plank.thickness());
                    assertTrue(plank.length() >= height * 16 || plank.brace());
                    assertTrue(plank.length() <= BoardLayout.MAX_LENGTH);
                    assertFalse(plank.cells().isEmpty());
                    if (plank.brace()) {
                        assertTrue(plank.direction().u() == 0 || plank.direction().v() == 0);
                        assertTrue(plank.nails().isEmpty());
                    } else {
                        assertEquals(2, plank.nails().size());
                        assertTrue(Math.abs(Math.toDegrees(Math.atan2(plank.direction().u(), plank.direction().v()))) <= 5 + 1e-6);
                    }
                }
                var fragments = BoardLayout.visible(layout, Set.of());
                assertEquals(layout.planks().size(), fragments.size(), "Intact rods must not be split at block edges");
                assertEquals(rods(layout).size() * 12L, fragments.stream().flatMap(fragment -> fragment.faces().stream())
                        .filter(BoardLayout.Face::nail).count(), "Two six-face weld cuboids per rod");
            }
    }
    @Test void centerPitchesAreRandomIntegersFromFourToSixAndRodsCannotCrossAtEitherEnd() {
        Set<Integer> pitches = new HashSet<>();
        boolean positive = false, negative = false;
        for (long seed = 0; seed < 64; seed++) for (int height : new int[]{1, 4, 16}) {
            var rods = rods(layout(rectangle(4, height), seed));
            double previousCenter = Double.NaN, previousBottom = Double.NaN, previousTop = Double.NaN;
            for (var rod : rods) {
                double slope = rod.direction().u() / rod.direction().v();
                positive |= slope > 0.001;
                negative |= slope < -0.001;
                double center = rod.start().u() + (height * 8 - rod.start().v()) * slope;
                double bottom = rod.start().u() - rod.start().v() * slope;
                double top = rod.start().u() + (height * 16 - rod.start().v()) * slope;
                assertEquals(Math.rint(center), center, 1e-6);
                if (!Double.isNaN(previousCenter)) {
                    int pitch = (int) Math.round(center - previousCenter);
                    assertTrue(pitch >= 4 && pitch <= 6);
                    pitches.add(pitch);
                    assertTrue(bottom - previousBottom > 2.9);
                    assertTrue(top - previousTop > 2.9);
                }
                previousCenter = center; previousBottom = bottom; previousTop = top;
            }
        }
        assertEquals(Set.of(4, 5, 6), pitches);
        assertTrue(positive && negative);
    }
    @Test void weldsAreActualFourByTwoByOneCuboidsAtVanillaTexelDensity() {
        var layout = layout(rectangle(3, 3), 42);
        Map<Long, BoardLayout.Plank> planks = new HashMap<>();
        layout.planks().forEach(plank -> planks.put(plank.id(), plank));
        for (var fragment : BoardLayout.visible(layout, Set.of())) {
            var plank = planks.get(fragment.plankId());
            var welds = fragment.faces().stream().filter(BoardLayout.Face::nail).toList();
            for (int first = 0; first < welds.size(); first += 6) {
                double minAlong = Double.POSITIVE_INFINITY, maxAlong = Double.NEGATIVE_INFINITY;
                double minAcross = minAlong, maxAcross = maxAlong, minDepth = minAlong, maxDepth = maxAlong;
                for (var face : welds.subList(first, first + 6)) for (var vertex : face.vertices()) {
                    double u = vertex.u() - plank.start().u(), v = vertex.v() - plank.start().v();
                    double along = u * plank.direction().u() + v * plank.direction().v();
                    double across = -u * plank.direction().v() + v * plank.direction().u();
                    minAlong = Math.min(minAlong, along); maxAlong = Math.max(maxAlong, along);
                    minAcross = Math.min(minAcross, across); maxAcross = Math.max(maxAcross, across);
                    minDepth = Math.min(minDepth, vertex.depth()); maxDepth = Math.max(maxDepth, vertex.depth());
                }
                assertEquals(2, maxAlong - minAlong, 1e-6);
                assertEquals(4, maxAcross - minAcross, 1e-6);
                assertEquals(1, maxDepth - minDepth, 1e-6);
            }
            for (var face : fragment.faces()) for (int i = 0; i < 4; i++) {
                var a = face.vertices().get(i);
                var b = face.vertices().get((i + 1) % 4);
                double world = Math.sqrt(Math.pow(a.u() - b.u(), 2) + Math.pow(a.v() - b.v(), 2)
                        + Math.pow(a.depth() - b.depth(), 2));
                double texels = Math.hypot(a.textureU() - b.textureU(), a.textureV() - b.textureV()) * 16;
                assertEquals(world, texels, 2e-5, "UV density must not introduce subpixel texture detail");
            }
        }
    }
    @Test void allBodiesTouchTheWallInEveryFacingAndKeepTheirIntegerDimensions() {
        for (Direction facing : Direction.values()) {
            var plane = new BoardPlane(facing, -17);
            var shape = rectangle(3, 2);
            var layout = BoardLayout.generate(new BoardGroup.LayoutKey(42, plane,
                    shape, BoardLayout.boundary(shape), SealMaterial.IRON));
            Map<Long, BoardLayout.Plank> planks = new HashMap<>();
            layout.planks().forEach(plank -> planks.put(plank.id(), plank));
            for (var fragment : BoardLayout.visible(layout, Set.of())) {
                var plank = planks.get(fragment.plankId());
                var vertices = fragment.faces().stream().filter(face -> !face.nail())
                        .flatMap(face -> face.vertices().stream()).toList();
                double back = vertices.stream().mapToDouble(BoardLayout.Vertex::depth).min().orElseThrow();
                double front = vertices.stream().mapToDouble(BoardLayout.Vertex::depth).max().orElseThrow();
                assertTrue(back < 0 && back >= -1 / 16.0, "Bodies must slightly enter the wall, not float off it");
                assertEquals(2, front - back, 1e-9);
                Vec3 wall = plane.point(0, 0, 0), normal = plane.normal(0, 0, 1);
                Vec3 rear = plane.point(vertices.get(0).u(), vertices.get(0).v(), back);
                assertEquals(back / 16, rear.subtract(wall).dot(normal), 1e-9);
                double minAlong = Double.POSITIVE_INFINITY, maxAlong = Double.NEGATIVE_INFINITY;
                double minAcross = minAlong, maxAcross = maxAlong;
                for (var vertex : vertices) {
                    double u = vertex.u() - plank.start().u(), v = vertex.v() - plank.start().v();
                    double along = u * plank.direction().u() + v * plank.direction().v();
                    double across = -u * plank.direction().v() + v * plank.direction().u();
                    minAlong = Math.min(minAlong, along); maxAlong = Math.max(maxAlong, along);
                    minAcross = Math.min(minAcross, across); maxAcross = Math.max(maxAcross, across);
                }
                assertEquals(plank.length(), maxAlong - minAlong, 1e-9);
                assertEquals(2, maxAcross - minAcross, 1e-9);
                assertTrue(Math.abs(minAlong) < 1 / 16.0 && Math.abs(minAcross + 1) < 1 / 16.0);
                double weldBack = fragment.faces().stream().filter(BoardLayout.Face::nail)
                        .flatMap(face -> face.vertices().stream()).mapToDouble(BoardLayout.Vertex::depth)
                        .min().orElse(front);
                assertEquals(front, weldBack, 1e-9, "Welds must follow their rod's surface");
            }
        }
    }
    @Test void frameCornersAndNeighboringWeldsHaveNoOverlappingCoplanarFaces() {
        var ring = rectangle(3, 3);
        ring.remove(new BoardCell(1, 1));
        for (var shape : List.of(rectangle(1, 1), rectangle(3, 2), rectangle(16, 1), ring))
            for (long seed = 0; seed < 8; seed++) {
                var fragments = BoardLayout.visible(layout(shape, seed), Set.of());
                for (int i = 0; i < fragments.size(); i++) for (int j = i + 1; j < fragments.size(); j++)
                    for (var a : fragments.get(i).faces()) for (var b : fragments.get(j).faces())
                        if (coplanarOverlap(a, b)) fail("Overlapping coplanar faces at seed " + seed
                                + " between solids " + i + " and " + j + ": " + a + " / " + b);
            }
    }
    private static Vec3 position(BoardLayout.Vertex vertex) {
        return new Vec3(vertex.u(), vertex.v(), vertex.depth());
    }
    private static boolean coplanarOverlap(BoardLayout.Face a, BoardLayout.Face b) {
        if (Math.abs(a.normal().dot(b.normal())) < 1 - 1e-9) return false;
        var first = a.vertices().stream().map(IronBarLayoutTest::position).toList();
        var second = b.vertices().stream().map(IronBarLayoutTest::position).toList();
        if (Math.abs(first.get(0).subtract(second.get(0)).dot(a.normal())) > 1e-9) return false;
        for (var polygon : List.of(first, second)) for (int edge = 0; edge < 2; edge++) {
            Vec3 axis = polygon.get(edge + 1).subtract(polygon.get(edge)).normalize();
            double minA = Double.POSITIVE_INFINITY, maxA = Double.NEGATIVE_INFINITY;
            double minB = minA, maxB = maxA;
            for (var point : first) { minA = Math.min(minA, axis.dot(point)); maxA = Math.max(maxA, axis.dot(point)); }
            for (var point : second) { minB = Math.min(minB, axis.dot(point)); maxB = Math.max(maxB, axis.dot(point)); }
            if (maxA <= minB + 1e-9 || maxB <= minA + 1e-9) return false;
        }
        return true;
    }
    @Test void irregularOpeningsAndHolesHaveCoverageAndWeldedEndpointsWithoutDiagonalBraces() {
        List<Set<BoardCell>> shapes = new ArrayList<>();
        var ring = rectangle(3, 3);
        ring.remove(new BoardCell(1, 1));
        shapes.add(ring);
        shapes.add(Set.of(new BoardCell(0, 0), new BoardCell(1, 0), new BoardCell(0, 1),
                new BoardCell(0, 2), new BoardCell(1, 2), new BoardCell(2, 2)));
        for (var shape : shapes) for (long seed = 0; seed < 32; seed++) {
            var layout = layout(shape, seed);
            for (var cell : shape) assertTrue(rods(layout).stream().anyMatch(rod -> rod.cells().contains(cell)));
            var fragments = BoardLayout.visible(layout, Set.of());
            assertEquals(layout.planks().size(), fragments.size());
            assertEquals(rods(layout).size() * 12L,
                    fragments.stream().flatMap(fragment -> fragment.faces().stream()).filter(BoardLayout.Face::nail).count());
        }
    }
    @Test void breakingACellOnlyCutsItsSolidAndDoesNotCreateNewWelds() {
        var layout = layout(rectangle(3, 3), 42);
        var intact = BoardLayout.visible(layout, Set.of());
        var damaged = BoardLayout.visible(layout, Set.of(new BoardCell(1, 1)));
        Set<List<BoardLayout.Vertex>> joints = new HashSet<>();
        intact.forEach(fragment -> fragment.faces().stream().filter(BoardLayout.Face::nail)
                .forEach(face -> joints.add(face.vertices())));
        for (var fragment : damaged) for (var face : fragment.faces())
            if (face.nail()) assertTrue(joints.contains(face.vertices()));
        assertTrue(damaged.size() > intact.size(), "Only actual destruction may split continuous rods");
    }
    @Test void faceBudgetIsMeasuredFromProductionTiledGeometry() {
        for (int size : new int[]{1, 3, 16}) {
            var layout = layout(rectangle(size, size), 42);
            long faces = BoardLayout.visible(layout, Set.of()).stream().mapToLong(fragment -> fragment.faces().size()).sum();
            assertEquals(switch (size) { case 1 -> 106; case 3 -> 342; default -> 4294; }, faces,
                    "Joint offsets must not add geometry");
            assertTrue(faces < 30L * size * size + 200);
            System.out.printf("Window bars %dx%d: %d solids, %d quads%n", size, size, layout.planks().size(), faces);
        }
    }
}
