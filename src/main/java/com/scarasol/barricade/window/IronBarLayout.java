package com.scarasol.barricade.window;

import java.util.*;
import static com.scarasol.barricade.window.BoardLayout.*;

/** Integer-sized rods and boundary frames use the same damage/ownership solids as boards. */
final class IronBarLayout {
    private static final int DIAMETER = 2;
    private static final double JOINT_OFFSET = 1.0 / 64;
    private static final double MAX_SLOPE = StrictMath.tan(StrictMath.toRadians(5));
    private IronBarLayout() { }

    // Render-only translations separate corner sides and weld fronts without moving ownership geometry.
    static Line renderLine(Plank plank, Line line) {
        return plank.brace() ? new Line(line.at(JOINT_OFFSET), line.direction()) : line;
    }

    static double renderDepth(Plank plank, int index) {
        double layer = plank.brace() ? (plank.direction().u() == 0 ? 2 : 1) : 3 + (index & 31) / 32.0;
        return plank.depth() - layer * JOINT_OFFSET;
    }

    private record Edge(boolean vertical, int coordinate) implements Comparable<Edge> {
        @Override public int compareTo(Edge other) {
            int axis = Boolean.compare(vertical, other.vertical);
            return axis != 0 ? axis : Integer.compare(coordinate, other.coordinate);
        }
    }

    static Layout generate(BoardGroup.LayoutKey key) {
        Set<BoardCell> shape = key.shape();
        int left = shape.stream().mapToInt(BoardCell::u).min().orElseThrow() * 16;
        int right = (shape.stream().mapToInt(BoardCell::u).max().orElseThrow() + 1) * 16;
        int bottom = shape.stream().mapToInt(BoardCell::v).min().orElseThrow() * 16;
        int top = (shape.stream().mapToInt(BoardCell::v).max().orElseThrow() + 1) * 16;
        List<Plank> solids = new ArrayList<>();
        frames(key, solids);

        Random random = new Random(key.seed());
        List<Integer> centers = new ArrayList<>();
        for (int x = left + 3; x <= right - 3; x += 4 + random.nextInt(3)) centers.add(x);
        int shift = (int) Math.round((left + (double) right - centers.get(0) - centers.get(centers.size() - 1)) / 2);
        double halfHeight = (top - bottom) / 2.0 + 2;
        double[] slopes = new double[centers.size()];
        for (int i = 0; i < slopes.length; i++) {
            centers.set(i, centers.get(i) + shift);
            double limit = Math.min(MAX_SLOPE, Math.max(0,
                    Math.min(centers.get(i) - left - 2, right - 2 - centers.get(i))) / halfHeight);
            slopes[i] = (random.nextDouble() * 2 - 1) * limit;
        }
        // Scale the entire profile once so no adjacent pair crosses anywhere along its full height.
        double scale = 1;
        for (int i = 1; i < slopes.length; i++) {
            double difference = Math.abs(slopes[i] - slopes[i - 1]);
            if (difference > 0)
                scale = Math.min(scale, (centers.get(i) - centers.get(i - 1) - DIAMETER - 1) / (difference * halfHeight));
        }
        for (int i = 0; i < slopes.length; i++) {
            Line line = new Line(new Point(centers.get(i), (bottom + (double) top) / 2), unit(slopes[i] * scale, 1));
            for (Interval interval : intervals(line, shape)) {
                int first = (int) Math.floor(interval.start() + 1e-6);
                int last = (int) Math.ceil(interval.end() - 1e-6);
                if (last - first < 4) continue;
                Point start = line.at(first);
                List<BoardCell> cells = cells(shape, new Line(start, line.direction()), last - first);
                List<Nail> welds = List.of(new Nail(1, nearest(cells, line.at(first + 1))),
                        new Nail(last - first - 1, nearest(cells, line.at(last - 1))));
                add(solids, key, start, line.direction(), last - first, false, welds, cells);
            }
        }
        return new Layout(key, solids);
    }

    private static void frames(BoardGroup.LayoutKey key, List<Plank> solids) {
        Map<Edge, SortedSet<Integer>> edges = new TreeMap<>();
        for (BoardCell cell : new TreeSet<>(key.shape())) {
            if (!key.shape().contains(cell.offset(-1, 0))) edge(edges, true, cell.u() * 16, cell.v() * 16);
            if (!key.shape().contains(cell.offset(1, 0))) edge(edges, true, (cell.u() + 1) * 16, cell.v() * 16);
            if (!key.shape().contains(cell.offset(0, -1))) edge(edges, false, cell.v() * 16, cell.u() * 16);
            if (!key.shape().contains(cell.offset(0, 1))) edge(edges, false, (cell.v() + 1) * 16, cell.u() * 16);
        }
        for (var entry : edges.entrySet()) {
            int first = Integer.MIN_VALUE, last = first;
            for (int coordinate : entry.getValue()) {
                if (first != Integer.MIN_VALUE && coordinate != last) {
                    frame(key, solids, entry.getKey(), first, last);
                    first = coordinate;
                } else if (first == Integer.MIN_VALUE) first = coordinate;
                last = coordinate + 16;
            }
            frame(key, solids, entry.getKey(), first, last);
        }
    }

    private static void edge(Map<Edge, SortedSet<Integer>> edges, boolean vertical, int coordinate, int start) {
        edges.computeIfAbsent(new Edge(vertical, coordinate), ignored -> new TreeSet<>()).add(start);
    }

    private static void frame(BoardGroup.LayoutKey key, List<Plank> solids, Edge edge, int first, int last) {
        Point start = edge.vertical ? new Point(edge.coordinate, first - 1) : new Point(first - 1, edge.coordinate);
        Point direction = edge.vertical ? new Point(0, 1) : new Point(1, 0);
        int length = last - first + 2;
        add(solids, key, start, direction, length, true, List.of(),
                cells(key.shape(), new Line(start, direction), length));
    }

    private static List<BoardCell> cells(Set<BoardCell> shape, Line line, int length) {
        return coveredCells(rectangle(line, 0, length, DIAMETER)).stream().filter(shape::contains).toList();
    }

    private static void add(List<Plank> solids, BoardGroup.LayoutKey key, Point start, Point direction, int length,
                            boolean frame, List<Nail> welds, List<BoardCell> cells) {
        long id = mix(key.seed() ^ solids.size() * 0x9e3779b97f4a7c15L);
        int shade = 212 + choice(id, 28);
        int tint = shade << 16 | shade << 8 | shade;
        solids.add(new Plank(id, start, direction, length, DIAMETER, DIAMETER, 0,
                frame, tint, 0, welds, cells));
    }
}
