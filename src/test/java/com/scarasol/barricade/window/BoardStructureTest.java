package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.geom.Area;
import java.util.*;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class BoardStructureTest {
    private static BoardGroup group(Set<BoardCell> shape, long seed, Set<BoardCell> supports) {
        return new BoardGroup(new UUID(1, 2), 1, seed, new BoardPlane(Direction.NORTH, 0), shape, Set.of(), supports);
    }
    @Test void longMainBoardsCrossSixtyFourAndOneTwentyEightWithoutInternalEnds() {
        for (int width : new int[]{5, 8, 16}) for (long seed = 0; seed < 32; seed++) {
            var shape = BoardLayoutTest.rectangle(0, 0, width, 4);
            var layout = BoardLayout.generate(group(shape, seed, Set.of()));
            assertTrue(layout.planks().stream().anyMatch(plank -> !plank.brace() && plank.length() > width * 16),
                    "Missing full-width main: width=" + width + ", seed=" + seed);
            for (var fragment : BoardLayout.visible(layout, Set.of())) {
                var plank = BoardLayoutTest.plank(layout, fragment);
                BoardLayoutTest.assertCuboid(plank, fragment);
                if (plank.brace()) continue;
                for (var end : List.of(plank.start(), plank.start().add(plank.direction().scale(plank.length()))))
                    assertFalse(shape.contains(cell(end)), "Artificial internal end: " + plank);
            }
        }
    }
    @Test void boundaryFastenersDoNotDependOnTheMaterialSupportSnapshot() {
        var shape = BoardLayoutTest.rectangle(-3, -5, 5, 4);
        for (long seed = 0; seed < 32; seed++) {
            var first = BoardLayout.generate(group(shape, seed, Set.of()));
            var second = BoardLayout.generate(group(shape, seed, BoardLayoutTest.supports(shape)));
            assertEquals(first.planks(), second.planks());
        }
    }
    @Test void nailsOnlyOccupyGeometricBoundariesOrActualBraceMainJointsAndEveryBoardHasTwoConnections() {
        var ring = BoardLayoutTest.rectangle(-2, -3, 5, 5);
        ring.removeAll(BoardLayoutTest.rectangle(-1, -2, 3, 3));
        var steps = BoardLayoutTest.rectangle(0, 0, 7, 3);
        for (int u = 0; u < 7; u += 2) steps.remove(new BoardCell(u, 1));
        for (var shape : List.of(BoardLayoutTest.rectangle(0, 0, 1, 1), BoardLayoutTest.rectangle(0, 0, 2, 2),
                BoardLayoutTest.rectangle(0, 0, 5, 3), BoardLayoutTest.rectangle(-3, -5, 16, 4), ring, steps)) {
            for (long seed = 0; seed < 48; seed++) {
                var layout = BoardLayout.generate(group(shape, seed, Set.of()));
                for (var plank : layout.planks()) {
                    List<BoardLayout.Point> connections = new ArrayList<>();
                    for (var nail : plank.nails()) {
                        var point = point(plank, nail);
                        assertTrue(!shape.contains(cell(point)) || plank.brace() && layout.planks().stream()
                                .anyMatch(main -> !main.brace() && contains(main, point)),
                                "Nail over glass: seed=" + seed + ", plank=" + plank + ", nail=" + nail);
                        connections.add(point);
                    }
                    if (!plank.brace()) for (var brace : layout.planks()) if (brace.brace())
                        for (var nail : brace.nails()) if (contains(plank, point(brace, nail))) connections.add(point(brace, nail));
                    assertTrue(connections.stream().distinct().count() >= 2,
                            "Under-fastened board: seed=" + seed + ", plank=" + plank);
                }
                var fragments = BoardLayout.visible(layout, Set.of());
                var braceHeads = fragments.stream().filter(fragment -> BoardLayoutTest.plank(layout, fragment).brace())
                        .flatMap(fragment -> heads(fragment).stream()).toList();
                for (var fragment : fragments) {
                    var plank = BoardLayoutTest.plank(layout, fragment);
                    var physical = new ArrayList<>(heads(fragment));
                    if (!plank.brace()) braceHeads.stream().filter(head -> contains(plank, head)).forEach(physical::add);
                    assertTrue(physical.stream().distinct().count() >= 2,
                            "Under-fastened rendered board: seed=" + seed + ", plank=" + plank);
                }
            }
        }
    }
    @Test void widerAndLongerBoardsUseCornerFastenersWithoutIncreasingTheirWoodPieceCount() {
        int wide = 0;
        for (long seed = 0; seed < 32; seed++) {
            var shape = BoardLayoutTest.rectangle(0, 0, 16, 4);
            var layout = BoardLayout.generate(group(shape, seed, Set.of()));
            for (var plank : layout.planks()) if (!plank.brace() && plank.width() >= 8
                    && plank.start().u() < 0 && plank.start().u() + plank.direction().u() * plank.length() > 256) {
                wide++;
                assertEquals(4, plank.nails().size(), "Four geometric corner nails: " + plank);
                assertEquals(2, plank.nails().stream().map(BoardLayout.Nail::across).distinct().count());
            }
        }
        assertTrue(wide > 10);
    }
    @Test void shortWindowsStillHaveBracesAndLongWindowsGetSeveralShortStaggeredBraces() {
        for (long seed = 0; seed < 32; seed++) {
            var small = BoardLayout.generate(group(BoardLayoutTest.rectangle(0, 0, 2, 2), seed, Set.of()));
            assertTrue(small.planks().stream().anyMatch(BoardLayout.Plank::brace), "Small window: seed=" + seed);
            var large = BoardLayout.generate(group(BoardLayoutTest.rectangle(0, 0, 16, 3), seed, Set.of()));
            var braces = large.planks().stream().filter(BoardLayout.Plank::brace).toList();
            assertTrue(braces.size() >= 4, "Long window reinforcement: seed=" + seed + ", count=" + braces.size());
            assertTrue(braces.stream().allMatch(plank -> plank.length() <= 82));
            for (var main : large.planks()) if (!main.brace()) {
                List<Double> links = new ArrayList<>();
                links.add(0.0);
                links.add((double) main.length());
                for (var nail : main.nails()) links.add((double) nail.along());
                for (var brace : braces) for (var nail : brace.nails()) {
                    var point = point(brace, nail);
                    if (contains(main, point)) links.add(parameter(main, point));
                }
                links.sort(Double::compare);
                for (int i = 1; i < links.size(); i++)
                    assertTrue(links.get(i) - links.get(i - 1) <= 64.00001,
                            "Unsupported long span: seed=" + seed + ", main=" + main
                                    + ", gap=" + (links.get(i) - links.get(i - 1)));
            }
        }
    }
    @Test void wideVoidsBetweenMainBoardsReceiveShortReinforcementWithoutExtraMainRows() {
        var shape = BoardLayoutTest.rectangle(0, 0, 3, 3);
        var layout = BoardLayout.generate(group(shape, 42, Set.of()));
        var braces = layout.planks().stream().filter(BoardLayout.Plank::brace).toList();
        assertTrue(braces.size() >= 2, "The tall upper gap needs more than the central brace");
        assertTrue(braces.stream().anyMatch(plank -> plank.length() < 40
                && plank.start().v() + plank.direction().v() * plank.length() / 2 > 28),
                "Missing short brace inside the wide upper gap");
        assertEquals(5, layout.planks().stream().filter(plank -> !plank.brace()).count());
    }
    @Test void ownershipSlicesStayInvisibleAndPreserveAWholeLongBoardAcrossSplittingAndReload() {
        var shape = BoardLayoutTest.rectangle(0, 0, 10, 2);
        BoardGroup source = group(shape, 42, Set.of());
        var plank = new BoardLayout.Plank(1, new BoardLayout.Point(-4, 8), new BoardLayout.Point(1, 0),
                168, 5, 2, 0, false, 0xc0b6a4, 0,
                List.of(new BoardLayout.Nail(2, new BoardCell(0, 0)), new BoardLayout.Nail(166, new BoardCell(9, 0))),
                BoardLayoutTest.rectangle(0, 0, 10, 1).stream().sorted().toList());
        var layout = new BoardLayout.Layout(source.layoutKey(), List.of(plank));
        var intact = BoardLayout.visible(layout, Set.of());
        assertEquals(1, intact.size());
        BoardLayoutTest.assertCuboid(plank, intact.get(0));
        Set<BoardCell> cuts = BoardLayoutTest.rectangle(5, 0, 1, 2);
        BoardGroup damaged = new BoardGroup(source.id(), 2, source.seed(), source.plane(), shape, cuts, Set.of());
        var components = WindowBoardsData.components(damaged.survivors());
        var partitions = BoardLayout.partition(damaged, layout, components);
        Area actual = new Area();
        for (int i = 0; i < components.size(); i++) {
            var child = new BoardGroup(new UUID(2, i + 1), 3, source.seed(), source.plane(),
                    components.get(i), Set.of(), Set.of(), partitions.get(i));
            assertEquals(child, BoardGroup.load(child.save()));
            BoardLayout.validateOwnership(child, layout);
            for (var fragment : BoardLayout.visible(layout, child, Set.of())) {
                BoardLayoutTest.assertCuboid(plank, fragment);
                actual.add(BoardLayoutTest.footprint(fragment));
            }
        }
        Area expected = new Area();
        for (var fragment : BoardLayout.visible(layout, cuts)) expected.add(BoardLayoutTest.footprint(fragment));
        expected.exclusiveOr(actual);
        assertEquals(0, BoardLayoutTest.area(expected), 0.00001);
        assertTrue(partitions.stream().mapToInt(part -> part.wood().size()).sum() > partitions.size());
    }
    @Test void jointNailsDisappearWhenTheUnderlyingMainIsCutAwayEvenIfTheBraceSurvives() {
        var shape = BoardLayoutTest.rectangle(0, 0, 3, 2);
        BoardGroup source = group(shape, 42, Set.of());
        var cells = shape.stream().sorted().toList();
        var main = new BoardLayout.Plank(1, new BoardLayout.Point(-4, 16), new BoardLayout.Point(1, 0),
                56, 10, 2, 0, false, 0xc0b6a4, 0, List.of(), cells);
        double diagonal = Math.sqrt(0.5);
        var brace = new BoardLayout.Plank(2, new BoardLayout.Point(-4, -4), new BoardLayout.Point(diagonal, diagonal),
                56, 3, 1, 2, true, 0xc0b6a4, 0,
                List.of(new BoardLayout.Nail(2, new BoardCell(0, 0)), new BoardLayout.Nail(34, new BoardCell(1, 1))), cells);
        var layout = new BoardLayout.Layout(source.layoutKey(), List.of(main, brace));
        assertEquals(12, BoardLayout.visible(layout, Set.of()).stream().filter(fragment -> fragment.plankId() == 2)
                .flatMap(fragment -> fragment.faces().stream()).filter(BoardLayout.Face::nail).count());
        var visible = BoardLayout.visible(layout, Set.of(new BoardCell(1, 0))).stream()
                .filter(fragment -> fragment.plankId() == 2).toList();
        assertFalse(visible.isEmpty());
        assertEquals(6, visible.stream().flatMap(fragment -> fragment.faces().stream()).filter(BoardLayout.Face::nail).count());
        assertTrue(visible.stream().flatMap(fragment -> fragment.faces().stream()).filter(face -> !face.nail())
                .flatMap(face -> face.vertices().stream()).anyMatch(vertex -> vertex.u() > 22 && vertex.v() > 22));
    }
    @Test void constructionCostIsMeasuredWithoutLaunchingAClientOrAddingPerFrameWork() throws Exception {
        var profile = Boolean.getBoolean("barricade.window.profile") ? new jdk.jfr.Recording() : null;
        if (profile != null) {
            profile.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1));
            profile.start();
        }
        for (int size : new int[]{3, 16}) {
            var shape = BoardLayoutTest.rectangle(0, 0, size, size);
            for (int i = 0; i < 8; i++) BoardLayout.generate(group(shape, i, Set.of()));
            long[] times = new long[16];
            int maximum = 0;
            for (int i = 0; i < times.length; i++) {
                long start = System.nanoTime();
                var layout = BoardLayout.generate(group(shape, i, Set.of()));
                times[i] = System.nanoTime() - start;
                maximum = Math.max(maximum, layout.planks().size());
            }
            Arrays.sort(times);
            System.out.println("Window-board construction " + size + "x" + size + ": median="
                    + times[times.length / 2] / 1_000_000.0 + " ms, max=" + times[times.length - 1] / 1_000_000.0
                    + " ms (wall clock), maximum planks=" + maximum);
            assertTrue(maximum < 512);
        }
        if (profile != null) {
            profile.stop();
            var file = java.nio.file.Path.of("build/verification/window-boards/construction.jfr");
            java.nio.file.Files.createDirectories(file.getParent());
            profile.dump(file);
            profile.close();
            Map<String, Integer> samples = new HashMap<>();
            for (var event : jdk.jfr.consumer.RecordingFile.readAllEvents(file)) {
                var stack = event.getStackTrace();
                if (stack == null) continue;
                for (var frame : stack.getFrames()) {
                    var method = frame.getMethod();
                    if (method.getType().getName().equals(BoardLayout.class.getName()))
                        samples.merge(method.getName(), 1, Integer::sum);
                }
            }
            System.out.println("Window-board inclusive CPU samples: " + samples.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(20).toList());
        }
    }
    static BoardLayout.Point point(BoardLayout.Plank plank, BoardLayout.Nail nail) {
        return plank.start().add(plank.direction().scale(nail.along()))
                .add(new BoardLayout.Point(-plank.direction().v(), plank.direction().u()).scale(nail.across()));
    }
    private static List<BoardLayout.Point> heads(BoardLayout.Fragment fragment) {
        return fragment.faces().stream().filter(face -> face.nail() && face.normal().z() > 0.9).map(face ->
                new BoardLayout.Point(face.vertices().stream().mapToDouble(BoardLayout.Vertex::u).average().orElseThrow(),
                        face.vertices().stream().mapToDouble(BoardLayout.Vertex::v).average().orElseThrow())).toList();
    }
    private static BoardCell cell(BoardLayout.Point point) {
        return new BoardCell((int) Math.floor(point.u() / 16), (int) Math.floor(point.v() / 16));
    }
    private static double parameter(BoardLayout.Plank plank, BoardLayout.Point point) {
        return (point.u() - plank.start().u()) * plank.direction().u() + (point.v() - plank.start().v()) * plank.direction().v();
    }
    private static boolean contains(BoardLayout.Plank plank, BoardLayout.Point point) {
        double along = parameter(plank, point);
        double across = -(point.u() - plank.start().u()) * plank.direction().v()
                + (point.v() - plank.start().v()) * plank.direction().u();
        return along >= -0.00001 && along <= plank.length() + 0.00001 && Math.abs(across) <= plank.width() / 2.0 + 0.00001;
    }
}
