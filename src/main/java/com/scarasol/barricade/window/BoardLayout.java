package com.scarasol.barricade.window;

import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Immutable whole planks. Damage only removes intervals from their cached solids. */
public final class BoardLayout {
    public static final int MAX_LENGTH = 512;
    public static final int NAIL_MERGE_DISTANCE = 4;
    private static final int MAIN_SPACING = 8;
    private static final int MIN_LENGTH = 12;
    private static final int MAX_UNBRACED = 48;
    private static final double EPSILON = 0.000001;
    private BoardLayout() { }
    public record Point(double u, double v) {
        Point add(Point other) { return new Point(u + other.u, v + other.v); }
        Point scale(double scale) { return new Point(u * scale, v * scale); }
        double dot(Point other) { return u * other.u + v * other.v; }
    }
    public record Vertex(double u, double v, double depth, float textureU, float textureV) { }
    public record Face(List<Vertex> vertices, Vec3 normal, boolean nail, int tint) {
        public Face { vertices = List.copyOf(vertices); }
    }
    public record Fragment(long plankId, BoardCell owner, List<Face> faces) {
        public Fragment { faces = List.copyOf(faces); }
    }
    public record Nail(int along, int across, BoardCell owner) {
        public Nail(int along, BoardCell owner) { this(along, 0, owner); }
    }
    public record Plank(long id, Point start, Point direction, int length, int width, int thickness,
                        int depth, boolean brace, int tint, int stripe, List<Nail> nails, List<BoardCell> cells) {
        public Plank { nails = List.copyOf(nails); cells = List.copyOf(cells); }
    }
    public static final class Layout {
        private final BoardGroup.LayoutKey key;
        private final List<Plank> planks;
        private final Map<Long, PlankGeometry> geometry = new HashMap<>();
        private final Map<Long, OwnershipSlice> slices = new HashMap<>();
        private final List<PlankGeometry> mains;
        private final List<PlankGeometry> braces;
        public Layout(BoardGroup.LayoutKey key, List<Plank> planks) {
            this.key = key;
            this.planks = List.copyOf(planks);
            for (int i = 0; i < planks.size(); i++)
                geometry.put(planks.get(i).id, new PlankGeometry(planks.get(i), i));
            for (Plank plank : planks) {
                PlankGeometry solid = geometry.get(plank.id);
                solid.sliceIds = new long[(plank.length + 63) / 64];
                for (int i = 0; i < solid.sliceIds.length; i++) {
                    long id = i == 0 ? plank.id : mix(plank.id ^ (long) i * 0xd1b54a32d192ed03L);
                    if (i != 0) while (geometry.containsKey(id) || slices.containsKey(id))
                        id = mix(id + 0x9e3779b97f4a7c15L);
                    solid.sliceIds[i] = id;
                    slices.put(id, new OwnershipSlice(solid, Math.min(64, plank.length - i * 64)));
                }
            }
            mains = planks.stream().filter(plank -> !plank.brace).map(plank -> geometry.get(plank.id)).toList();
            braces = planks.stream().filter(Plank::brace).map(plank -> geometry.get(plank.id)).toList();
        }
        public BoardGroup.LayoutKey key() { return key; }
        public List<Plank> planks() { return planks; }
        @Override public boolean equals(Object other) {
            return other instanceof Layout layout && key.equals(layout.key) && planks.equals(layout.planks);
        }
        @Override public int hashCode() { return Objects.hash(key, planks); }
        @Override public String toString() { return "Layout[key=" + key + ", planks=" + planks + "]"; }
    }
    record Interval(double start, double end) { }
    private record OwnershipSlice(PlankGeometry geometry, int length) { }
    private record Surface(List<Point> outline, Point direction, int depth) { }
    private record Piece(List<BoardCell> cells, Surface surface) { }
    private record BraceJoint(PlankGeometry geometry, Nail nail, Point point) { }
    private record NailContext(List<Point> head, List<PlankGeometry> surfaces, List<BraceJoint> joints,
                                List<PlankGeometry> anchors, boolean boundary) { }
    /** Source-local analysis is shared by remnants and dies with the source layout. */
    private static final class PlankGeometry {
        final Plank plank;
        final int index;
        final Line line;
        long[] sliceIds;
        private Interval[] cuts;
        private double[][] coverage;
        private final Map<Integer, Piece> pieces = boundedCache(16);
        private final Map<Long, List<Interval>> masks = boundedCache(8);
        private final Map<Nail, NailContext> nails = new HashMap<>();
        PlankGeometry(Plank plank, int index) {
            this.plank = plank;
            this.index = index;
            line = new Line(plank.start, plank.direction);
        }
        NailContext nailContext(Layout layout, Nail nail) {
            return nails.computeIfAbsent(nail, ignored -> {
                Point point = nailPoint(plank, nail);
                Line head = new Line(point, plank.direction);
                List<Point> outline = rectangle(head, -0.5, 0.5, 1);
                if (plank.brace)
                    return new NailContext(outline, List.of(), List.of(),
                            layout.mains.stream().filter(main -> headFits(main.plank, head)).toList(),
                            outsideOpening(outline, layout.key.shape()));
                List<PlankGeometry> surfaces = new ArrayList<>();
                List<BraceJoint> joints = new ArrayList<>();
                for (PlankGeometry brace : layout.braces) {
                    Point offset = new Point(point.u - brace.plank.start.u, point.v - brace.plank.start.v);
                    double along = offset.dot(brace.plank.direction);
                    double across = offset.dot(new Point(-brace.plank.direction.v, brace.plank.direction.u));
                    // One unit conservatively bounds the rotated half-unit nail head.
                    if (along >= -1 && along <= brace.plank.length + 1 && Math.abs(across) <= brace.plank.width / 2.0 + 1)
                        surfaces.add(brace);
                    if (brace.plank.depth != plank.depth + plank.thickness) continue;
                    for (Nail joint : brace.plank.nails) {
                        Point center = nailPoint(brace.plank, joint);
                        double u = center.u - point.u, v = center.v - point.v;
                        if (u * u + v * v <= NAIL_MERGE_DISTANCE * NAIL_MERGE_DISTANCE + EPSILON)
                            joints.add(new BraceJoint(brace, joint, center));
                    }
                }
                return new NailContext(outline, List.copyOf(surfaces), List.copyOf(joints), List.of(), false);
            });
        }
        Piece piece(Interval span) {
            int key = (int) span.start * (MAX_LENGTH + 1) + (int) span.end;
            return pieces.computeIfAbsent(key, ignored -> {
                List<Point> outline = rectangle(line, span.start, span.end, plank.width);
                List<BoardCell> cells = plank.cells.stream().filter(cell -> overlap(line, outline, cell) != null).toList();
                return new Piece(cells, new Surface(outline, plank.direction, plank.depth + plank.thickness));
            });
        }
        double[] coverage(int unit) {
            if (coverage == null) coverage = new double[plank.length][];
            if (coverage[unit] == null) {
                double[] values = new double[plank.cells.size()];
                List<Point> piece = rectangle(line, unit, unit + 1, plank.width);
                for (int i = 0; i < values.length; i++) values[i] = area(clipCell(piece, plank.cells.get(i)));
                coverage[unit] = values;
            }
            return coverage[unit];
        }
        List<Interval> spans(Set<BoardCell> damage) {
            long mask = 0;
            for (int i = 0; i < plank.cells.size(); i++) if (damage.contains(plank.cells.get(i))) mask |= 1L << i;
            if (plank.cells.size() <= Long.SIZE && masks.containsKey(mask)) return masks.get(mask);
            if (cuts == null) {
                cuts = new Interval[plank.cells.size()];
                List<Point> outline = rectangle(line, 0, plank.length, plank.width);
                for (int i = 0; i < cuts.length; i++) {
                    Interval overlap = overlap(line, outline, plank.cells.get(i));
                    if (overlap != null) cuts[i] = new Interval(Math.floor(overlap.start + EPSILON),
                            Math.ceil(overlap.end - EPSILON));
                }
            }
            List<Interval> removed = new ArrayList<>();
            for (int i = 0; i < cuts.length; i++)
                if (cuts[i] != null && damage.contains(plank.cells.get(i))) removed.add(cuts[i]);
            List<Interval> result = subtract(0, plank.length, removed).stream()
                    .filter(span -> piece(span).cells.stream().anyMatch(cell -> !damage.contains(cell))).toList();
            if (plank.cells.size() <= Long.SIZE) masks.put(mask, result);
            return result;
        }
    }
    private static <K, V> Map<K, V> boundedCache(int limit) {
        return new LinkedHashMap<>(limit, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K, V> entry) { return size() > limit; }
        };
    }
    record Line(Point origin, Point direction) {
        Point at(double t) { return origin.add(direction.scale(t)); }
        double parameter(Point point) {
            return (point.u - origin.u) * direction.u + (point.v - origin.v) * direction.v;
        }
    }
    public static Layout generate(BoardGroup group) {
        return generate(group.layoutKey());
    }
    public static Layout generate(BoardGroup.LayoutKey group) {
        if (group.material() == SealMaterial.IRON) return IronBarLayout.generate(group);
        Set<BoardCell> shape = group.shape();
        double minU = shape.stream().mapToInt(BoardCell::u).min().orElseThrow() * 16.0;
        double maxU = (shape.stream().mapToInt(BoardCell::u).max().orElseThrow() + 1.0) * 16;
        double minV = shape.stream().mapToInt(BoardCell::v).min().orElseThrow() * 16.0;
        double maxV = (shape.stream().mapToInt(BoardCell::v).max().orElseThrow() + 1.0) * 16;
        List<Plank> planks = new ArrayList<>();
        int first = (int) Math.floor(minV / MAIN_SPACING), last = (int) Math.ceil(maxV / MAIN_SPACING) - 1;
        TiltProfile profile = tiltProfile(group, first, last);
        List<MainRow> rows = new ArrayList<>();
        for (int lane = first; lane <= last; lane++) {
            long key = mix(group.seed() ^ (long) lane * 0x9e3779b97f4a7c15L);
            double centerV = lane * (double) MAIN_SPACING + 3 + choice(key >>> 5, 3);
            Point origin = new Point((minU + maxU) / 2, centerV);
            Point mainDirection = mainDirection(group, origin, profile.degrees(key), maxU - minU, maxV - minV);
            Line line = new Line(origin, mainDirection);
            double length = intervals(line, shape).stream().mapToDouble(value -> value.end - value.start).max().orElse(0) + 12;
            int width = Math.min(10, 3 + Math.min(3, Math.max(0, (int) Math.ceil((length - 64) / 64)))
                    + (Math.abs(profile.degrees(key)) >= 10 ? 3 : Math.abs(profile.degrees(key)) >= 6 ? 1 : 0)
                    + choice(key >>> 9, length > 96 ? 4 : 3));
            List<Plank> raw = boards(group, line, key, width, false);
            if (!raw.isEmpty()) rows.add(new MainRow(key, line, width, raw));
        }
        packMains(planks, group, rows, minU, maxU, minV, maxV);
        widenBoards(planks, group.shape(), false);
        planBraces(planks, group, minU, maxU, minV, maxV);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < planks.size(); i++) {
            Plank plank = planks.get(i);
            long id = plank.id;
            while (!ids.add(id)) id = mix(id + 0x9e3779b97f4a7c15L);
            if (id != plank.id) planks.set(i, new Plank(id, plank.start, plank.direction, plank.length,
                    plank.width, plank.thickness, plank.depth, plank.brace, plank.tint, plank.stripe, plank.nails, plank.cells));
        }
        return new Layout(group, planks);
    }
    private static double normal(long key) {
        double first = ((mix(key ^ 0x510e527fade682d1L) >>> 11) + 1) * 0x1.0p-53;
        double second = (mix(key ^ 0x1f83d9abfb41bd6bL) >>> 11) * 0x1.0p-53;
        return StrictMath.sqrt(-2 * StrictMath.log(first)) * StrictMath.cos(2 * StrictMath.PI * second);
    }
    private record TiltProfile(double mean, double scale) {
        double degrees(long key) { return (normal(key) - mean) * scale * 5; }
    }
    private static TiltProfile tiltProfile(BoardGroup.LayoutKey group, int first, int last) {
        Set<Integer> rows = new HashSet<>();
        for (BoardCell cell : group.shape()) rows.add(cell.v());
        double total = 0;
        int count = 0;
        for (int lane = first; lane <= last; lane++) {
            long key = mix(group.seed() ^ (long) lane * 0x9e3779b97f4a7c15L);
            double center = lane * (double) MAIN_SPACING + 3 + choice(key >>> 5, 3);
            if (!rows.contains((int) Math.floor(center / 16))) continue;
            total += normal(key);
            count++;
        }
        // Center each window before packing without forcing alternating or equal angles.
        return count < 2 ? new TiltProfile(0, 1)
                : new TiltProfile(total / count, StrictMath.sqrt((double) count / (count - 1)));
    }
    private static Point mainDirection(BoardGroup.LayoutKey group, Point origin, double degrees, double width, double height) {
        double angle = StrictMath.toRadians(degrees);
        Line trial = new Line(origin, unit(1, StrictMath.tan(angle)));
        double span = intervals(trial, group.shape()).stream()
                .mapToDouble(interval -> interval.end - interval.start).max().orElse(0);
        // Include both possible six-unit wall ends; disconnected short runs keep their larger angle allowance.
        double length = Math.max(1, span + 12);
        angle *= Math.min(1, StrictMath.sqrt(64 / length));
        double limit = Math.min(StrictMath.toRadians(Math.min(15, 15 * 64 / length)),
                StrictMath.atan2(height - 2, width));
        return unit(1, StrictMath.tan(Math.max(-limit, Math.min(limit, angle))));
    }
    private record MainRow(long key, Line line, int width, List<Plank> planks) { }
    private static void packMains(List<Plank> output, BoardGroup.LayoutKey group, List<MainRow> rows,
                                   double minU, double maxU, double bottom, double top) {
        int count = rows.size();
        double[] lowerBounds = new double[count], upperBounds = new double[count];
        for (int i = 0; i < count; i++) {
            double swing = Math.abs(rows.get(i).line.direction.v / rows.get(i).line.direction.u) * (maxU - minU) / 2 + 1;
            lowerBounds[i] = bottom + swing;
            upperBounds[i] = top - swing;
        }
        double[][] gaps = new double[count][count];
        for (int i = 0; i < count; i++) for (int j = i + 1; j < count; j++) {
            MainRow a = rows.get(i), b = rows.get(j);
            double slope = a.line.direction.v / a.line.direction.u - b.line.direction.v / b.line.direction.u;
            for (Plank first : a.planks) for (Plank second : b.planks) {
                double left = Math.max(first.start.u - Math.abs(first.direction.v) * first.width / 2.0,
                        second.start.u - Math.abs(second.direction.v) * second.width / 2.0);
                double right = Math.min(first.start.u + first.direction.u * first.length + Math.abs(first.direction.v) * first.width / 2.0,
                        second.start.u + second.direction.u * second.length + Math.abs(second.direction.v) * second.width / 2.0);
                if (left >= right) continue;
                gaps[i][j] = Math.max(gaps[i][j], Math.max(slope * (left - a.line.origin.u), slope * (right - a.line.origin.u))
                        + a.width / (2 * a.line.direction.u) + b.width / (2 * b.line.direction.u) + 1);
            }
        }
        List<Integer> active = new ArrayList<>();
        for (int i = 0; i < count; i++) active.add(i);
        while (active.size() > 1 && packedOverflow(active, gaps, lowerBounds, upperBounds) > EPSILON) {
            int remove = -1;
            double best = Double.POSITIVE_INFINITY;
            for (int i : active) {
                List<Integer> trial = new ArrayList<>(active);
                trial.remove(Integer.valueOf(i));
                double score = packedOverflow(trial, gaps, lowerBounds, upperBounds) + Math.abs(rows.get(i).line.direction.v) * 2;
                if (score < best) { best = score; remove = i; }
            }
            active.remove(Integer.valueOf(remove));
        }
        double[] latest = upperBounds.clone(), placed = new double[count];
        for (int k = active.size() - 1; k >= 0; k--) {
            int i = active.get(k);
            for (int j : active) if (j > i) latest[i] = Math.min(latest[i], latest[j] - gaps[i][j]);
        }
        for (int i : active) {
            MainRow row = rows.get(i);
            double lower = lowerBounds[i];
            for (int j : active) if (j < i) lower = Math.max(lower, placed[j] + gaps[j][i]);
            placed[i] = Math.max(lower, Math.min(latest[i], row.line.origin.v));
            List<Plank> selected = List.of();
            for (int shift : new int[]{0, -1, 1, -2, 2}) {
                double v = placed[i] + shift;
                if (v < lowerBounds[i] - EPSILON || v > upperBounds[i] + EPSILON) continue;
                List<Plank> trial = boards(group, new Line(new Point(row.line.origin.u, v), row.line.direction),
                        row.key, row.width, false);
                if (!trial.isEmpty() && trial.stream().noneMatch(candidate -> output.stream().anyMatch(other -> intersects(candidate, other)))) {
                    selected = trial;
                    placed[i] = v;
                    break;
                }
            }
            for (Plank plank : selected) if (output.stream().noneMatch(other -> intersects(plank, other))) output.add(plank);
        }
    }
    private static double packedOverflow(List<Integer> active, double[][] gaps, double[] lower, double[] upper) {
        double[] heights = new double[gaps.length];
        double result = -Double.MAX_VALUE;
        for (int i : active) {
            heights[i] = lower[i];
            for (int j : active) if (j < i) heights[i] = Math.max(heights[i], heights[j] + gaps[j][i]);
            result = Math.max(result, heights[i] - upper[i]);
        }
        return result;
    }
    private static void widenBoards(List<Plank> planks, Set<BoardCell> shape, boolean braces) {
        Map<BoardCell, Double> coverage = new HashMap<>();
        for (Plank plank : planks) addCoverage(coverage, plank);
        for (BoardCell cell : new TreeSet<>(shape)) {
            if (coverage.getOrDefault(cell, 0.0) >= 24) continue;
            for (int i = 0; i < planks.size() && coverage.getOrDefault(cell, 0.0) < 24; i++) {
                Plank old = planks.get(i);
                if (old.brace != braces) continue;
                Line line = new Line(old.start, old.direction);
                for (int width = old.width + 1; width <= 10; width++) {
                    List<Point> outline = rectangle(line, 0, old.length, width);
                    if (area(clipCell(outline, cell)) <= EPSILON) continue;
                    List<BoardCell> cells = coveredCells(outline).stream().filter(shape::contains).toList();
                    Plank wider = new Plank(old.id, old.start, old.direction, old.length, width, old.thickness, old.depth,
                            braces, old.tint, Math.min(old.stripe, 16 - width), old.nails, cells);
                    int index = i;
                    if (java.util.stream.IntStream.range(0, planks.size())
                            .anyMatch(other -> other != index && planks.get(other).brace == braces && intersects(wider, planks.get(other)))) break;
                    addCoverage(coverage, wider);
                    List<Point> before = rectangle(line, 0, old.length, old.width);
                    for (BoardCell member : old.cells) coverage.merge(member, -area(clipCell(before, member)), Double::sum);
                    planks.set(i, braces ? wider : withNails(wider, boundaryNails(wider)));
                    old = wider;
                    if (coverage.getOrDefault(cell, 0.0) >= 24) break;
                }
            }
        }
    }
    static Set<BoardCell> boundary(Set<BoardCell> shape) {
        Set<BoardCell> result = new HashSet<>();
        for (BoardCell cell : shape) for (int u = -1; u <= 1; u++) for (int v = -1; v <= 1; v++)
            if (!shape.contains(cell.offset(u, v))) result.add(cell.offset(u, v));
        return result;
    }
    private static List<Plank> boards(BoardGroup.LayoutKey group, Line line, long key, int width, boolean brace) {
        return boards(group, line, key, width, brace, null);
    }
    private static List<Plank> boards(BoardGroup.LayoutKey group, Line line, long key, int width, boolean brace, Point target) {
        List<Plank> result = new ArrayList<>();
        List<Interval> spans = intervals(line, group.shape());
        for (int i = 0; i < spans.size(); i++) {
            Interval span = spans.get(i);
            if (span.end - span.start < 6) continue;
            long id = mix(key ^ (long) i * 0x94d049bb133111ebL);
            double left = 4 + choice(id, 3), right = 4 + choice(id >>> 12, 3);
            if (i > 0) left = Math.min(left, Math.max(0, (span.start - spans.get(i - 1).end) / 2 - 1));
            if (i + 1 < spans.size()) right = Math.min(right, Math.max(0, (spans.get(i + 1).start - span.end) / 2 - 1));
            int start = (int) Math.ceil(span.start - left), end = (int) Math.floor(span.end + right);
            if (end - start < MIN_LENGTH || end - start > MAX_LENGTH) continue;
            if (target != null && (line.parameter(target) < start || line.parameter(target) > end)) continue;
            // A short brace's usable anchor pair cannot extend this far from its target.
            if (target != null) {
                start = Math.max(start, (int) Math.floor(line.parameter(target) - 84));
                end = Math.min(end, (int) Math.ceil(line.parameter(target) + 84));
            }
            List<BoardCell> cells = coveredCells(rectangle(line, start, end, width)).stream()
                    .filter(group.shape()::contains).toList();
            if (cells.isEmpty()) continue;
            Plank plank = new Plank(id, line.at(start), line.direction, end - start, width, brace ? 1 : 2,
                    brace ? 2 : 0, brace, 0xc0b6a4 + choice(id >>> 6, 4) * 0x080808,
                    choice(id, 17 - width), List.of(), cells);
            result.add(withNails(plank, boundaryNails(plank)));
        }
        return result;
    }
    private static Plank withNails(Plank plank, List<Nail> nails) {
        return new Plank(plank.id, plank.start, plank.direction, plank.length, plank.width, plank.thickness,
                plank.depth, plank.brace, plank.tint, plank.stripe, nails, plank.cells);
    }
    private static List<Nail> boundaryNails(Plank plank) {
        List<Nail> result = new ArrayList<>();
        int[] across = plank.width >= 8 ? new int[]{-plank.width / 2 + 2, plank.width / 2 - 2} : new int[]{0};
        for (boolean end : new boolean[]{false, true}) for (int preferred : across) {
            int sideLimit = (plank.width - 1) / 2;
            int[] offsets = plank.width >= 8 ? new int[]{preferred} : new int[]{0, -sideLimit, sideLimit};
            boolean found = false;
            for (int side : offsets) {
                for (int inset : new int[]{2, 1, 3, 4}) {
                    int along = end ? plank.length - inset : inset;
                    Nail nail = new Nail(along, side, nearest(plank.cells, nailPoint(plank, new Nail(along, side, plank.cells.get(0)))));
                    List<Point> head = rectangle(new Line(nailPoint(plank, nail), plank.direction), -0.5, 0.5, 1);
                    if (!outsideOpening(head, plank.cells)) continue;
                    result.add(nail);
                    found = true;
                    break;
                }
                if (found) break;
            }
        }
        return result;
    }
    private static boolean outsideOpening(List<Point> head, Collection<BoardCell> cells) {
        for (BoardCell cell : coveredCells(head)) if (cells.contains(cell)) return false;
        return true;
    }
    private static void planBraces(List<Plank> planks, BoardGroup.LayoutKey group,
                                   double minU, double maxU, double minV, double maxV) {
        List<Plank> mains = List.copyOf(planks);
        Map<BoardCell, Double> coverage = new HashMap<>();
        for (Plank plank : planks) addCoverage(coverage, plank);
        // Reserve low-coverage cells before optional braces can occupy their attachment space.
        for (BoardCell cell : new TreeSet<>(group.shape())) {
            if (coverage.getOrDefault(cell, 0.0) >= 24) continue;
            Plank brace = braceAt(planks, group, new Point(cell.u() * 16.0 + 8, cell.v() * 16.0 + 8),
                    mix(group.seed() ^ cell.packed() ^ 0x3c6ef372fe94f82bL), null, cell);
            if (brace != null) { planks.add(brace); addCoverage(coverage, brace); }
        }
        for (int u = (int) Math.floor(minU / 48); u <= Math.floor((maxU - EPSILON) / 48); u++)
            for (int v = (int) Math.floor(minV / 48); v <= Math.floor((maxV - EPSILON) / 48); v++) {
                int tileU = u, tileV = v;
                BoardCell cell = group.shape().stream().filter(value -> Math.floor(value.u() / 3.0) == tileU
                        && Math.floor(value.v() / 3.0) == tileV).min(Comparator
                        .comparingDouble((BoardCell value) -> Math.pow(value.u() * 16.0 + 8 - (tileU * 48.0 + 24), 2)
                                + Math.pow(value.v() * 16.0 + 8 - (tileV * 48.0 + 24), 2))
                        .thenComparing(Comparator.naturalOrder())).orElse(null);
                if (cell == null) continue;
                long key = mix(group.seed() ^ (long) u * 0x632be59bd9b4e019L ^ v ^ 0x6a09e667f3bcc909L);
                Plank brace = braceAt(planks, group, new Point(cell.u() * 16.0 + 8, cell.v() * 16.0 + 8), key);
                if (brace != null) planks.add(brace);
            }
        // Support spacing is measured along each real solid, not across a region's bounding box.
        for (Plank main : mains) for (int attempt = 0; attempt < main.length / 24 + 2; attempt++) {
            List<Double> links = connections(main, planks);
            List<Double> spans = new ArrayList<>(links);
            spans.add(0.0);
            spans.add((double) main.length);
            spans.sort(Double::compare);
            double largest = 0, center = main.length / 2.0;
            for (int i = 1; i < spans.size(); i++) if (spans.get(i) - spans.get(i - 1) > largest) {
                largest = spans.get(i) - spans.get(i - 1);
                center = (spans.get(i) + spans.get(i - 1)) / 2;
            }
            if (largest <= MAX_UNBRACED && links.size() >= 2) break;
            Plank brace = braceAt(planks, group, new Line(main.start, main.direction).at(center),
                    mix(main.id ^ (long) attempt * 0xbb67ae8584caa73bL), main);
            if (brace == null) break;
            planks.add(brace);
        }
        fillWideGaps(planks, group);
        widenBoards(planks, group.shape(), true);
    }
    private record Gap(Point center, double height) { }
    private static void fillWideGaps(List<Plank> planks, BoardGroup.LayoutKey group) {
        List<Gap> gaps = new ArrayList<>();
        for (int u : group.shape().stream().mapToInt(BoardCell::u).distinct().sorted().toArray())
            for (int offset : new int[]{4, 12}) {
                double x = u * 16.0 + offset;
                Line vertical = new Line(new Point(x, 0), new Point(0, 1));
                for (Interval opening : intervals(vertical, group.shape()))
                    for (Interval span : verticalGaps(planks, x, opening))
                        if (span.end - span.start >= 10)
                            gaps.add(new Gap(new Point(x, (span.start + span.end) / 2), span.end - span.start));
            }
        gaps.sort(Comparator.comparingDouble(Gap::height).reversed()
                .thenComparingDouble(gap -> gap.center.u).thenComparingDouble(gap -> gap.center.v));
        int budget = Math.max(2, (group.shape().size() + 8) / 9);
        for (Gap gap : gaps) {
            if (budget == 0) break;
            Interval band = new Interval(gap.center.v - gap.height / 2, gap.center.v + gap.height / 2);
            if (verticalGaps(planks, gap.center.u, band).stream().noneMatch(span ->
                    span.start <= gap.center.v && span.end >= gap.center.v && span.end - span.start >= 10)) continue;
            Plank brace = braceAt(planks, group, gap.center, mix(group.seed() ^ Double.doubleToLongBits(gap.center.u)
                    ^ Long.rotateLeft(Double.doubleToLongBits(gap.center.v), 21) ^ 0xa54ff53a5f1d36f1L));
            if (brace != null) { planks.add(brace); budget--; }
        }
    }
    private static List<Interval> verticalGaps(List<Plank> planks, double u, Interval opening) {
        List<Interval> occupied = new ArrayList<>();
        for (Plank plank : planks) {
            double x = u - plank.start.u, y = -plank.start.v;
            double[] range = {opening.start, opening.end};
            if (slab(range, x * plank.direction.u + y * plank.direction.v, plank.direction.v, 0, plank.length)
                    && slab(range, -x * plank.direction.v + y * plank.direction.u, plank.direction.u,
                    -plank.width / 2.0, plank.width / 2.0) && range[1] - range[0] > EPSILON)
                occupied.add(new Interval(range[0], range[1]));
        }
        return subtract(opening.start, opening.end, occupied);
    }
    private static void addCoverage(Map<BoardCell, Double> coverage, Plank plank) {
        List<Point> outline = rectangle(new Line(plank.start, plank.direction), 0, plank.length, plank.width);
        for (BoardCell cell : plank.cells) coverage.merge(cell, area(clipCell(outline, cell)), Double::sum);
    }
    private static List<Double> connections(Plank main, List<Plank> planks) {
        List<Point> points = new ArrayList<>();
        for (Nail nail : main.nails) points.add(nailPoint(main, nail));
        Line line = new Line(main.start, main.direction);
        for (Plank brace : planks) if (brace.brace) for (Nail nail : brace.nails) {
            Point point = nailPoint(brace, nail);
            if (!headFits(main, new Line(point, brace.direction))) continue;
            Nail shared = sharedNail(main, point);
            if (shared != null) points.remove(nailPoint(main, shared));
            points.add(point);
        }
        return points.stream().map(line::parameter).distinct().sorted().toList();
    }
    private static Plank braceAt(List<Plank> planks, BoardGroup.LayoutKey group, Point target, long key) {
        return braceAt(planks, group, target, key, null);
    }
    private static Plank braceAt(List<Plank> planks, BoardGroup.LayoutKey group, Point target, long key, Plank required) {
        return braceAt(planks, group, target, key, required, null);
    }
    private static Plank braceAt(List<Plank> planks, BoardGroup.LayoutKey group, Point target, long key, Plank required,
                                  BoardCell requiredCell) {
        Plank best = null;
        double score = Double.POSITIVE_INFINITY;
        for (int variant = 0; variant < 8; variant++) {
            long sample = mix(key ^ (long) variant * 0x9e3779b97f4a7c15L);
            double magnitude = variant < 2 ? 0.65 + choice(sample >>> 8, 5) * 0.15
                    : variant < 4 ? 1.4 + choice(sample >>> 8, 5) * 0.3
                    : variant < 6 ? 2.8 + choice(sample >>> 8, 5) * 0.6 : 0.9 + choice(sample >>> 8, 4) * 0.2;
            int sign = choice(key, 2) == 0 ? -1 : 1;
            if (variant % 2 != 0) sign = -sign;
            double slope = sign * magnitude;
            Point point = target.add(new Point(variant < 6 ? 0 : variant == 6 ? -6 : 6, 0));
            Line line = new Line(point, unit(1, slope));
            for (Plank source : boards(group, line, sample, 4 + choice(sample >>> 16, 3), true, target)) {
                int center = (int) Math.round(new Line(source.start, source.direction).parameter(target));
                if (center < 0 || center > source.length) continue;
                List<Nail> joints = new ArrayList<>();
                Nail requiredNail = required == null ? null : jointNail(source, required);
                if (required != null && requiredNail == null) continue;
                for (Plank main : planks) if (!main.brace) {
                    Nail nail = jointNail(source, main);
                    if (nail != null && joints.stream().noneMatch(old -> Math.abs(old.along - nail.along) < 3)) joints.add(nail);
                }
                List<Nail> anchors = new ArrayList<>(source.nails);
                for (Nail nail : joints)
                    if (anchors.stream().noneMatch(old -> Math.abs(old.along - nail.along) < 3)) anchors.add(nail);
                anchors.sort(Comparator.comparingInt(Nail::along));
                for (int i = 0; i < anchors.size(); i++) for (int j = i + 1; j < anchors.size(); j++) {
                    int left = anchors.get(i).along, right = anchors.get(j).along;
                    if (right - left < 8 || right - left > 76 || left > center + 4 || right < center - 4) continue;
                    int first = Math.max(0, left - 2 - choice(sample >>> 24, 2));
                    int last = Math.min(source.length, right + 2 + choice(sample >>> 28, 2));
                    if (last - first < MIN_LENGTH) continue;
                    if (requiredNail != null && (requiredNail.along < first + 1 || requiredNail.along > last - 1)) continue;
                    double cost = Math.abs((left + right) / 2.0 - center) + (last - first) * 0.05 - (j - i + 1);
                    if (cost >= score) continue;
                    List<Point> outline = rectangle(new Line(source.start, source.direction), first, last, source.width);
                    if (requiredCell != null && area(clipCell(outline, requiredCell)) < 24 - EPSILON) continue;
                    List<BoardCell> cells = coveredCells(outline).stream().filter(group.shape()::contains).toList();
                    if (cells.isEmpty()) continue;
                    Plank candidate = new Plank(source.id, new Line(source.start, source.direction).at(first), source.direction,
                            last - first, source.width, source.thickness, source.depth, true, source.tint, source.stripe,
                            anchors.subList(i, j + 1).stream()
                                    .map(nail -> new Nail(nail.along - first, nail.across, nearest(cells, nailPoint(source, nail)))).toList(), cells);
                    if (planks.stream().anyMatch(other -> other.brace && intersects(candidate, other))) continue;
                    if (cost < score) { best = candidate; score = cost; }
                }
            }
        }
        return best;
    }
    private static boolean headFits(Plank plank, Line head) {
        double u = head.origin.u - plank.start.u, v = head.origin.v - plank.start.v;
        double along = u * plank.direction.u + v * plank.direction.v;
        double across = -u * plank.direction.v + v * plank.direction.u;
        double radius = (Math.abs(plank.direction.dot(head.direction))
                + Math.abs(plank.direction.u * head.direction.v - plank.direction.v * head.direction.u)) / 2;
        return along >= radius - EPSILON && along <= plank.length - radius + EPSILON
                && Math.abs(across) <= plank.width / 2.0 - radius + EPSILON;
    }
    private static Nail jointNail(Plank brace, Plank main) {
        Line line = new Line(brace.start, brace.direction);
        Point perpendicular = new Point(-main.direction.v, main.direction.u);
        double[] range = {1, brace.length - 1};
        Point offset = new Point(brace.start.u - main.start.u, brace.start.v - main.start.v);
        if (!slab(range, offset.dot(main.direction), brace.direction.dot(main.direction), 0, main.length)
                || !slab(range, offset.dot(perpendicular), brace.direction.dot(perpendicular), -main.width / 2.0, main.width / 2.0))
            return null;
        int along = (int) Math.round((range[0] + range[1]) / 2);
        if (!headFits(main, new Line(line.at(along), brace.direction))) return null;
        return new Nail(along, nearest(brace.cells, line.at(along)));
    }
    private static boolean intersects(Plank a, Plank b) {
        double u = b.start.u + b.direction.u * b.length / 2 - a.start.u - a.direction.u * a.length / 2;
        double v = b.start.v + b.direction.v * b.length / 2 - a.start.v - a.direction.v * a.length / 2;
        double dot = Math.abs(a.direction.dot(b.direction));
        double cross = Math.abs(a.direction.u * b.direction.v - a.direction.v * b.direction.u);
        return Math.abs(u * a.direction.u + v * a.direction.v) < (a.length + b.length * dot + b.width * cross) / 2 - EPSILON
                && Math.abs(-u * a.direction.v + v * a.direction.u) < (a.width + b.length * cross + b.width * dot) / 2 - EPSILON
                && Math.abs(u * b.direction.u + v * b.direction.v) < (b.length + a.length * dot + a.width * cross) / 2 - EPSILON
                && Math.abs(-u * b.direction.v + v * b.direction.u) < (b.width + a.length * cross + a.width * dot) / 2 - EPSILON;
    }
    private static boolean intersects(List<Point> first, Point a, List<Point> second, Point b) {
        for (Point axis : List.of(a, new Point(-a.v, a.u), b, new Point(-b.v, b.u))) {
            double minA = Double.POSITIVE_INFINITY, maxA = Double.NEGATIVE_INFINITY;
            double minB = Double.POSITIVE_INFINITY, maxB = Double.NEGATIVE_INFINITY;
            for (Point point : first) {
                double value = axis.dot(point);
                minA = Math.min(minA, value);
                maxA = Math.max(maxA, value);
            }
            for (Point point : second) {
                double value = axis.dot(point);
                minB = Math.min(minB, value);
                maxB = Math.max(maxB, value);
            }
            if (maxA <= minB + EPSILON || maxB <= minA + EPSILON) return false;
        }
        return true;
    }
    /** Owners are lighting hints only. A cell must never hide an entire intact plank. */
    public static List<Fragment> visible(Layout layout, Set<BoardCell> damage) {
        return visible(layout, damage, null, layout.key.shape());
    }
    public static List<Fragment> visible(Layout layout, BoardGroup group, Set<BoardCell> hidden) {
        Set<BoardCell> damage = new HashSet<>(group.layoutDamage());
        damage.addAll(hidden);
        return visible(layout, damage, group.remnant() == null ? null : group.remnant().wood(), group.shape());
    }
    private static List<Fragment> visible(Layout layout, Set<BoardCell> damage,
                                          Map<Long, Long> ownership, Set<BoardCell> members) {
        List<PlankGeometry> selected = selected(layout, ownership);
        List<Fragment> result = new ArrayList<>();
        boolean iron = layout.key.material() == SealMaterial.IRON;
        for (PlankGeometry geometry : selected) {
            Plank plank = geometry.plank;
            Line line = geometry.line;
            Line renderLine = iron ? IronBarLayout.renderLine(plank, line) : line;
            double depth = iron ? IronBarLayout.renderDepth(plank, geometry.index) : plank.depth;
            List<Interval> visible = geometry.spans(damage);
            for (Interval span : ownedSpans(geometry, visible, ownership, members, damage)) {
                List<BoardCell> surviving = geometry.piece(span).cells.stream()
                        .filter(cell -> members.contains(cell) && !damage.contains(cell)).toList();
                List<Face> faces = new ArrayList<>();
                cuboid(faces, renderLine, span.start, span.end, plank.width, depth,
                        plank.thickness, false, plank.tint, plank.stripe, 0);
                for (Nail nail : plank.nails) {
                    Interval original = visible.stream().filter(value -> contains(value, nail)).findFirst().orElse(null);
                    boolean owned = ownership == null ? contains(span, nail)
                            : nail.along >= span.start && nail.along < span.end;
                    if (damage.contains(nail.owner) || original == null || !owned) continue;
                    if (iron) {
                        if (nail.along - 1 < span.start || nail.along + 1 > span.end
                                || !weldSupported(layout, nailPoint(plank, nail), damage)) continue;
                        cuboid(faces, new Line(nailPoint(plank, nail), plank.direction), -1, 1, 4,
                                depth + plank.thickness, 1, true, 0xffffff, 0, -1);
                        continue;
                    }
                    NailContext context = geometry.nailContext(layout, nail);
                    if (plank.brace && !context.boundary && context.anchors.stream().noneMatch(main ->
                            main.spans(damage).stream().anyMatch(value -> headFits(main, value, context.head)))) continue;
                    if (!plank.brace && sharesJoint(plank, nail, original, context.joints, damage)) continue;
                    cuboid(faces, new Line(nailPoint(plank, nail), plank.direction), -0.5, 0.5, 1,
                            nailSurface(plank, context, damage), 1, true, 0x66676a, 0, -0.5);
                }
                result.add(new Fragment(plank.id, nearest(surviving, line.at((span.start + span.end) / 2)), faces));
            }
        }
        return List.copyOf(result);
    }
    private static boolean weldSupported(Layout layout, Point point, Set<BoardCell> damage) {
        for (PlankGeometry frame : layout.braces) {
            Point offset = new Point(point.u - frame.plank.start.u, point.v - frame.plank.start.v);
            double across = Math.abs(offset.dot(new Point(-frame.plank.direction.v, frame.plank.direction.u)));
            if (across > frame.plank.width / 2.0 + 1 + EPSILON) continue;
            double along = frame.line.parameter(point);
            if (frame.spans(damage).stream().anyMatch(span -> along >= span.start && along <= span.end)) return true;
        }
        return false;
    }
    private static List<PlankGeometry> selected(Layout layout, Map<Long, Long> ownership) {
        if (ownership == null) return layout.planks.stream().map(plank -> layout.geometry.get(plank.id)).toList();
        return ownership.keySet().stream().map(layout.slices::get).filter(Objects::nonNull)
                .map(OwnershipSlice::geometry).distinct()
                .sorted(Comparator.comparingInt(geometry -> geometry.index)).toList();
    }
    private static List<Interval> ownedSpans(PlankGeometry geometry, List<Interval> visible, Map<Long, Long> ownership,
                                             Set<BoardCell> members, Set<BoardCell> damage) {
        if (ownership == null) return visible;
        Plank plank = geometry.plank;
        List<Interval> result = new ArrayList<>();
        for (Interval span : visible) {
            int unit = (int) span.start, limit = (int) span.end;
            while (unit < limit) {
                if (!owns(geometry, ownership, unit)) { unit++; continue; }
                int start = unit++;
                while (unit < limit && owns(geometry, ownership, unit)) unit++;
                Interval piece = new Interval(start, unit);
                if (geometry.piece(piece).cells.stream().anyMatch(cell -> members.contains(cell) && !damage.contains(cell)))
                    result.add(piece);
            }
        }
        return result;
    }
    private static boolean owns(PlankGeometry geometry, Map<Long, Long> ownership, int unit) {
        return (ownership.getOrDefault(geometry.sliceIds[unit / 64], 0L) & 1L << (unit % 64)) != 0;
    }
    private static void own(long[] slices, int first, int last) {
        for (int unit = first; unit < last; unit++) slices[unit / 64] |= 1L << (unit % 64);
    }
    /** Assign the already visible solid once; sibling membership never becomes a destructive cut. */
    public static List<BoardRemnant> partition(BoardGroup group, Layout layout, List<Set<BoardCell>> components) {
        Map<BoardCell, Integer> owners = new HashMap<>();
        List<Map<Long, Long>> wood = new ArrayList<>();
        for (int i = 0; i < components.size(); i++) {
            wood.add(new HashMap<>());
            for (BoardCell cell : components.get(i))
                if (owners.putIfAbsent(cell, i) != null) throw new IllegalArgumentException("Overlapping components");
        }
        if (!owners.keySet().equals(group.survivors()) || !layout.key.equals(group.layoutKey()))
            throw new IllegalArgumentException("Incomplete window-board partition");
        Set<BoardCell> damage = group.layoutDamage();
        Map<Long, Long> inherited = group.remnant() == null ? null : group.remnant().wood();
        double[] coverage = new double[components.size()];
        long[][] units = new long[components.size()][(MAX_LENGTH + 63) / 64];
        for (PlankGeometry geometry : selected(layout, inherited)) {
            Plank plank = geometry.plank;
            int[] cellOwners = new int[plank.cells.size()];
            int soleOwner = -1;
            boolean multiple = false;
            for (int i = 0; i < cellOwners.length; i++) {
                int owner = owners.getOrDefault(plank.cells.get(i), -1);
                cellOwners[i] = owner;
                if (owner < 0) continue;
                if (soleOwner >= 0 && soleOwner != owner) multiple = true;
                soleOwner = owner;
            }
            for (long[] slices : units) Arrays.fill(slices, 0);
            for (Interval span : ownedSpans(geometry, geometry.spans(damage), inherited, group.shape(), damage)) {
                int first = (int) span.start, last = (int) span.end;
                if (!multiple && soleOwner >= 0) {
                    own(units[soleOwner], first, last);
                    continue;
                }
                int[] assigned = new int[plank.length];
                Arrays.fill(assigned, -1);
                for (int unit = first; unit < last; unit++) {
                    Arrays.fill(coverage, 0);
                    double[] areas = geometry.coverage(unit);
                    for (int i = 0; i < cellOwners.length; i++)
                        if (cellOwners[i] >= 0) coverage[cellOwners[i]] += areas[i];
                    double best = 0;
                    for (int i = 0; i < coverage.length; i++) if (coverage[i] > best + EPSILON
                            || assigned[unit] < 0 && coverage[i] > 0) {
                        assigned[unit] = i;
                        best = coverage[i];
                    }
                }
                // Wall overhang belongs to the nearest supported unit of this same visible span.
                int[] supported = assigned.clone();
                for (int unit = first; unit < last; unit++) {
                    int owner = supported[unit], distance = Integer.MAX_VALUE;
                    if (owner < 0) for (int other = first; other < last; other++) {
                        if (supported[other] >= 0 && Math.abs(other - unit) < distance) {
                            owner = supported[other];
                            distance = Math.abs(other - unit);
                        }
                    }
                    if (owner < 0) throw new IllegalStateException("Unsupported window-board span");
                    own(units[owner], unit, unit + 1);
                }
            }
            for (int i = 0; i < units.length; i++)
                for (int slice = 0; slice < geometry.sliceIds.length; slice++)
                    if (units[i][slice] != 0) wood.get(i).put(geometry.sliceIds[slice], units[i][slice]);
        }
        return wood.stream().map(owned -> new BoardRemnant(layout.key, damage, owned)).toList();
    }
    public static void validateOwnership(BoardGroup group, Layout layout) {
        if (group.remnant() == null) return;
        for (var entry : group.remnant().wood().entrySet()) {
            OwnershipSlice slice = layout.slices.get(entry.getKey());
            if (slice == null || slice.length < 64 && (entry.getValue() >>> slice.length) != 0)
                throw new IllegalArgumentException("Window-board ownership outside source plank");
        }
    }
    private static Point nailPoint(Plank plank, Nail nail) {
        return new Line(plank.start, plank.direction).at(nail.along)
                .add(new Point(-plank.direction.v, plank.direction.u).scale(nail.across));
    }
    private static boolean contains(Interval span, Nail nail) {
        return nail.along - 0.5 >= span.start - EPSILON && nail.along + 0.5 <= span.end + EPSILON;
    }
    private static boolean headFits(PlankGeometry geometry, Interval span, List<Point> head) {
        for (Point corner : head) {
            double along = geometry.line.parameter(corner);
            if (along < span.start - EPSILON || along > span.end + EPSILON) return false;
        }
        return true;
    }
    private static boolean sharesJoint(Plank main, Nail nail, Interval span, List<BraceJoint> joints, Set<BoardCell> damage) {
        for (BraceJoint joint : joints) {
            if (damage.contains(joint.nail.owner)
                    || joint.geometry.spans(damage).stream().noneMatch(value -> contains(value, joint.nail))) continue;
            Point offset = new Point(joint.point.u - main.start.u, joint.point.v - main.start.v);
            double along = offset.dot(main.direction);
            double across = offset.dot(new Point(-main.direction.v, main.direction.u));
            Point direction = joint.geometry.plank.direction;
            double radius = (Math.abs(main.direction.dot(direction))
                    + Math.abs(main.direction.u * direction.v - main.direction.v * direction.u)) / 2;
            if (along >= span.start + radius - EPSILON && along <= span.end - radius + EPSILON
                    && Math.abs(across) + radius <= main.width / 2.0 + EPSILON
                    && nail.equals(sharedNail(main, joint.point)))
                return true;
        }
        return false;
    }
    private static Nail sharedNail(Plank main, Point point) {
        Nail nearest = null;
        double distance = NAIL_MERGE_DISTANCE * NAIL_MERGE_DISTANCE + EPSILON;
        for (Nail nail : main.nails) {
            Point head = nailPoint(main, nail);
            double value = (head.u - point.u) * (head.u - point.u) + (head.v - point.v) * (head.v - point.v);
            if (value > distance) continue;
            if (nearest == null || value < distance - EPSILON || Math.abs(value - distance) <= EPSILON
                    && (nail.along < nearest.along || nail.along == nearest.along && nail.across < nearest.across)) {
                nearest = nail;
                distance = value;
            }
        }
        return nearest;
    }
    private static int nailSurface(Plank main, NailContext context, Set<BoardCell> damage) {
        int depth = main.depth + main.thickness;
        if (context == null) return depth;
        for (PlankGeometry brace : context.surfaces) for (Interval span : brace.spans(damage)) {
            Surface surface = brace.piece(span).surface;
            if (intersects(context.head, main.direction, surface.outline, surface.direction))
                depth = Math.max(depth, surface.depth);
        }
        return depth;
    }
    static List<Interval> intervals(Line line, Set<BoardCell> shape) {
        List<Interval> values = new ArrayList<>();
        for (BoardCell cell : shape) {
            double[] range = {-Double.MAX_VALUE, Double.MAX_VALUE};
            if (slab(range, line.origin.u, line.direction.u, cell.u() * 16.0, (cell.u() + 1.0) * 16)
                    && slab(range, line.origin.v, line.direction.v, cell.v() * 16.0, (cell.v() + 1.0) * 16)
                    && range[1] - range[0] > EPSILON) values.add(new Interval(range[0], range[1]));
        }
        return merge(values);
    }
    private static List<Interval> merge(List<Interval> values) {
        values.sort(Comparator.comparingDouble(Interval::start));
        List<Interval> result = new ArrayList<>();
        for (Interval value : values) {
            if (result.isEmpty() || value.start > result.get(result.size() - 1).end + EPSILON) result.add(value);
            else {
                Interval previous = result.remove(result.size() - 1);
                result.add(new Interval(previous.start, Math.max(previous.end, value.end)));
            }
        }
        return result;
    }
    private static List<Interval> subtract(double start, double end, List<Interval> cuts) {
        List<Interval> result = new ArrayList<>();
        for (Interval cut : merge(cuts)) {
            if (cut.end <= start + EPSILON) continue;
            if (cut.start >= end - EPSILON) break;
            if (cut.start > start + EPSILON) result.add(new Interval(start, cut.start));
            start = Math.max(start, cut.end);
            if (start >= end - EPSILON) break;
        }
        if (end > start + EPSILON) result.add(new Interval(start, end));
        return result;
    }
    private static boolean slab(double[] range, double origin, double direction, double min, double max) {
        if (Math.abs(direction) < EPSILON) return origin >= min && origin <= max;
        double a = (min - origin) / direction, b = (max - origin) / direction;
        range[0] = Math.max(range[0], Math.min(a, b));
        range[1] = Math.min(range[1], Math.max(a, b));
        return range[0] <= range[1];
    }
    private static Interval overlap(Line line, List<Point> outline, BoardCell cell) {
        List<Point> polygon = clipCell(outline, cell);
        if (polygon.size() < 3 || area(polygon) < EPSILON) return null;
        double start = Double.POSITIVE_INFINITY, end = Double.NEGATIVE_INFINITY;
        for (Point point : polygon) {
            double value = line.parameter(point);
            start = Math.min(start, value);
            end = Math.max(end, value);
        }
        return new Interval(start, end);
    }
    static List<BoardCell> coveredCells(List<Point> outline) {
        double lowerU = Double.POSITIVE_INFINITY, upperU = Double.NEGATIVE_INFINITY;
        double lowerV = Double.POSITIVE_INFINITY, upperV = Double.NEGATIVE_INFINITY;
        for (Point point : outline) {
            lowerU = Math.min(lowerU, point.u);
            upperU = Math.max(upperU, point.u);
            lowerV = Math.min(lowerV, point.v);
            upperV = Math.max(upperV, point.v);
        }
        int minU = (int) Math.floor(lowerU / 16), maxU = (int) Math.floor(upperU / 16);
        int minV = (int) Math.floor(lowerV / 16), maxV = (int) Math.floor(upperV / 16);
        Point first = new Point(outline.get(1).u - outline.get(0).u, outline.get(1).v - outline.get(0).v);
        Point second = new Point(outline.get(3).u - outline.get(0).u, outline.get(3).v - outline.get(0).v);
        double firstLimit = first.dot(first) / 2 + 8 * (Math.abs(first.u) + Math.abs(first.v)) + EPSILON;
        double secondLimit = second.dot(second) / 2 + 8 * (Math.abs(second.u) + Math.abs(second.v)) + EPSILON;
        double centerU = (outline.get(0).u + outline.get(2).u) / 2;
        double centerV = (outline.get(0).v + outline.get(2).v) / 2;
        List<BoardCell> result = new ArrayList<>();
        for (int u = minU; u <= maxU; u++) for (int v = minV; v <= maxV; v++) {
            double deltaU = u * 16.0 + 8 - centerU, deltaV = v * 16.0 + 8 - centerV;
            if (Math.abs(deltaU * first.u + deltaV * first.v) > firstLimit
                    || Math.abs(deltaU * second.u + deltaV * second.v) > secondLimit) continue;
            BoardCell cell = new BoardCell(u, v);
            List<Point> polygon = clipCell(outline, cell);
            if (polygon.size() >= 3 && area(polygon) > EPSILON) result.add(cell);
        }
        return result;
    }
    static BoardCell nearest(List<BoardCell> cells, Point point) {
        return cells.stream().min(Comparator.comparingDouble((BoardCell cell) ->
                Math.pow(cell.u() * 16.0 + 8 - point.u, 2) + Math.pow(cell.v() * 16.0 + 8 - point.v, 2))
                .thenComparing(Comparator.naturalOrder())).orElseThrow();
    }
    static List<Point> rectangle(Line line, double start, double end, int width) {
        Point perpendicular = new Point(-line.direction.v, line.direction.u).scale(width / 2.0);
        Point a = line.at(start), b = line.at(end);
        return List.of(a.add(perpendicular.scale(-1)), b.add(perpendicular.scale(-1)),
                b.add(perpendicular), a.add(perpendicular));
    }
    private static void cuboid(List<Face> faces, Line line, double start, double end,
                               int width, double depth, int thickness, boolean nail, int tint, int stripe, double textureOrigin) {
        Point side = new Point(-line.direction.v, line.direction.u);
        int first = (int) Math.floor((start - textureOrigin) / 16);
        int last = (int) Math.floor((end - textureOrigin - EPSILON) / 16);
        for (int tile = first; tile <= last; tile++) {
            double lower = Math.max(start, textureOrigin + tile * 16);
            double upper = Math.min(end, textureOrigin + (tile + 1) * 16);
            List<Point> corners = rectangle(line, lower, upper, width);
            Point a = corners.get(0), b = corners.get(1), c = corners.get(2), d = corners.get(3);
            float u0 = (float) ((lower - textureOrigin - tile * 16) / 16);
            float u1 = (float) ((upper - textureOrigin - tile * 16) / 16);
            float v0 = stripe / 16f, v1 = (stripe + width) / 16f;
            quad(faces, a, b, c, d, depth + thickness, depth + thickness, new Vec3(0, 0, 1),
                    u0, u1, v0, v1, nail, tint);
            quad(faces, d, c, b, a, depth, depth, new Vec3(0, 0, -1),
                    u0, u1, v1, v0, nail, tint);
            quad(faces, a, b, b, a, depth, depth + thickness, new Vec3(-side.u, -side.v, 0),
                    u0, u1, 0, thickness / 16f, nail, tint);
            quad(faces, c, d, d, c, depth, depth + thickness, new Vec3(side.u, side.v, 0),
                    u1, u0, 0, thickness / 16f, nail, tint);
        }
        List<Point> corners = rectangle(line, start, end, width);
        Point a = corners.get(0), b = corners.get(1), c = corners.get(2), d = corners.get(3);
        quad(faces, d, a, a, d, depth, depth + thickness, new Vec3(-line.direction.u, -line.direction.v, 0),
                0, width / 16f, 0, thickness / 16f, nail, tint);
        quad(faces, b, c, c, b, depth, depth + thickness, new Vec3(line.direction.u, line.direction.v, 0),
                0, width / 16f, 0, thickness / 16f, nail, tint);
    }
    private static void quad(List<Face> faces, Point a, Point b, Point c, Point d, double back, double front,
                              Vec3 normal, float u0, float u1, float v0, float v1, boolean nail, int tint) {
        faces.add(new Face(List.of(new Vertex(a.u, a.v, back, u0, v0), new Vertex(b.u, b.v, back, u1, v0),
                new Vertex(c.u, c.v, front, u1, v1), new Vertex(d.u, d.v, front, u0, v1)), normal, nail, tint));
    }
    private static List<Point> clipCell(List<Point> polygon, BoardCell cell) {
        List<Point> result = clip(polygon, 1, 0, (cell.u() + 1.0) * 16);
        result = clip(result, -1, 0, -cell.u() * 16.0);
        result = clip(result, 0, 1, (cell.v() + 1.0) * 16);
        return clip(result, 0, -1, -cell.v() * 16.0);
    }
    private static List<Point> clip(List<Point> polygon, double nx, double ny, double limit) {
        if (polygon.isEmpty()) return polygon;
        boolean inside = false, outside = false;
        for (Point point : polygon) {
            if (point.u * nx + point.v * ny - limit <= EPSILON) inside = true;
            else outside = true;
        }
        if (!outside) return polygon;
        if (!inside) return List.of();
        List<Point> result = new ArrayList<>();
        Point a = polygon.get(polygon.size() - 1);
        double da = a.u * nx + a.v * ny - limit;
        for (Point b : polygon) {
            double db = b.u * nx + b.v * ny - limit;
            if ((da <= EPSILON) != (db <= EPSILON)) {
                double t = da / (da - db);
                result.add(new Point(a.u + (b.u - a.u) * t, a.v + (b.v - a.v) * t));
            }
            if (db <= EPSILON) result.add(b);
            a = b; da = db;
        }
        return result;
    }
    private static double area(List<Point> points) {
        if (points.size() < 3) return 0;
        double area = 0;
        Point origin = points.get(0);
        for (int i = 1; i < points.size() - 1; i++) {
            Point a = points.get(i), b = points.get(i + 1);
            area += (a.u - origin.u) * (b.v - origin.v) - (a.v - origin.v) * (b.u - origin.u);
        }
        return Math.abs(area) / 2;
    }
    private static BoardCell cell(Point point) {
        return new BoardCell((int) Math.floor(point.u / 16), (int) Math.floor(point.v / 16));
    }
    static Point unit(double x, double y) {
        double length = Math.hypot(x, y);
        return new Point(x / length, y / length);
    }
    static int choice(long value, int bound) { return (int) Long.remainderUnsigned(value, bound); }
    static long mix(long value) {
        value = (value ^ value >>> 30) * 0xbf58476d1ce4e5b9L;
        value = (value ^ value >>> 27) * 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }
}
