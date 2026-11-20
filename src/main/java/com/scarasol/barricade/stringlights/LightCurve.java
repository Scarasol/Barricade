package com.scarasol.barricade.stringlights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Static geometry shared by construction, reload, interaction and rendering. */
public final class LightCurve {
    public static final double MAX_DISTANCE_SQUARED = 32 * 32;
    public static final double CABLE_WIDTH = 1.0 / 32;
    private static final Comparator<Vec3> ORDER = Comparator.comparingDouble((Vec3 p) -> p.x)
            .thenComparingDouble(p -> p.y).thenComparingDouble(p -> p.z);
    private final Vec3 start;
    private final Vec3 end;
    private final Vec3 delta;
    private final double horizontal;
    private final double bend;
    private final double length;
    private final List<Piece> pieces;
    private final List<Bulb> bulbs;
    private final double startLead, endLead;

    public LightCurve(Vec3 first, Vec3 second) {
        boolean forward = ORDER.compare(first, second) <= 0;
        start = forward ? first : second;
        end = forward ? second : first;
        delta = end.subtract(start);
        horizontal = Math.hypot(delta.x, delta.z);
        // Reduce sag faster than horizontal distance near vertical; pure vertical is linear.
        double chord = delta.length();
        if (!(chord > 0) || chord * chord > MAX_DISTANCE_SQUARED + 1e-10) {
            throw new IllegalArgumentException("Invalid light span endpoints");
        }
        bend = 4 * Math.min(2, horizontal / 16) * horizontal / chord;
        length = arc(1);
        pieces = split();
        bulbs = distribute();
        double lead = Math.min(0.5, 0.75 / chord);
        startLead = Math.min(lead, parameterAt(bulbs.get(0).anchor()));
        endLead = Math.max(1 - lead, parameterAt(bulbs.get(bulbs.size() - 1).anchor()));
    }

    public Vec3 start() { return start; }
    public Vec3 end() { return end; }
    public double length() { return length; }
    public int cost() { return (int) Math.ceil(length); }
    public List<Piece> pieces() { return pieces; }
    public List<Bulb> bulbs() { return bulbs; }
    double startLead() { return startLead; }
    double endLead() { return endLead; }

    private double parameterAt(Vec3 point) {
        if (horizontal == 0) return (point.y - start.y) / delta.y;
        return Math.abs(delta.x) >= Math.abs(delta.z) ? (point.x - start.x) / delta.x : (point.z - start.z) / delta.z;
    }

    public Vec3 point(double t) {
        if (t == 0) return start;
        if (t == 1) return end;
        return start.add(delta.x * t, (delta.y - bend * (1 - t)) * t, delta.z * t);
    }

    public AABB bounds(Piece piece) {
        Vec3 a = point(piece.from), b = point(piece.to);
        AABB bounds = new AABB(a, b);
        double turning = bend == 0 ? 0 : (bend - delta.y) / (2 * bend);
        if (turning > piece.from && turning < piece.to) {
            Vec3 p = point(turning);
            bounds = bounds.minmax(new AABB(p, p));
        }
        return bounds;
    }

    boolean intersects(Piece piece, AABB box) {
        double from = piece.from, to = piece.to;
        if (delta.x == 0) {
            if (start.x < box.minX || start.x > box.maxX) return false;
        } else {
            double a = (box.minX - start.x) / delta.x, b = (box.maxX - start.x) / delta.x;
            from = Math.max(from, Math.min(a, b));
            to = Math.min(to, Math.max(a, b));
        }
        if (delta.z == 0) {
            if (start.z < box.minZ || start.z > box.maxZ) return false;
        } else {
            double a = (box.minZ - start.z) / delta.z, b = (box.maxZ - start.z) / delta.z;
            from = Math.max(from, Math.min(a, b));
            to = Math.min(to, Math.max(a, b));
        }
        if (from > to) return false;
        double y0 = point(from).y, y1 = point(to).y;
        double low = Math.min(y0, y1);
        double turning = bend == 0 ? 0 : (bend - delta.y) / (2 * bend);
        if (turning > from && turning < to) low = point(turning).y;
        return low <= box.maxY && Math.max(y0, y1) >= box.minY;
    }

    double arc(double t) {
        double u = delta.y - bend;
        double v = u + 2 * bend * t;
        double a = Math.hypot(horizontal, u);
        double b = Math.hypot(horizontal, v);
        // Divided difference of u*hypot(h,u), without subtracting near-equal terms.
        double product = b + u * (v + u) / (a + b);
        if (horizontal == 0 || v == u) return t * a;
        double slope = (v + u) / (a + b);
        double change = 2 * bend * t;
        double logarithm = u >= 0 ? Math.log1p(change * (1 + slope) / (u + a))
                : v <= 0 ? Math.log1p(change * (1 - slope) / (b - v))
                : asinhPositive(v / horizontal) + asinhPositive(-u / horizontal);
        return (t * product + horizontal * horizontal * logarithm / (2 * bend)) * 0.5;
    }

    private static double asinhPositive(double x) {
        return Math.log1p(x + x * (x / (1 + Math.hypot(1, x))));
    }

    double parameter(double distance) {
        double low = 0, high = 1;
        for (int i = 0; i < 48; i++) {
            double mid = (low + high) * 0.5;
            double atMid = arc(mid);
            if (atMid == distance) return mid;
            if (atMid < distance) low = mid;
            else high = mid;
        }
        return (low + high) * 0.5;
    }

    private List<Piece> split() {
        var stops = new ArrayList<Double>();
        stops.add(0.0);
        stops.add(1.0);
        planes(stops, start.x, delta.x, 0);
        planes(stops, start.y, delta.y - bend, bend);
        planes(stops, start.z, delta.z, 0);
        stops.sort(null);
        List<Piece> result = new ArrayList<>();
        double from = 0;
        for (double to : stops) {
            if (to <= from) continue;
            BlockPos cell = BlockPos.containing(point((from + to) * 0.5));
            int last = result.size() - 1;
            if (last >= 0 && result.get(last).cell().equals(cell)) {
                result.set(last, new Piece(result.get(last).from(), to, cell));
            } else {
                result.add(new Piece(from, to, cell));
            }
            from = to;
        }
        return List.copyOf(result);
    }

    private static void planes(List<Double> stops, double origin, double slope, double quadratic) {
        double end = origin + slope + quadratic;
        double turning = quadratic == 0 ? 0 : Math.max(0, Math.min(1, -slope / (2 * quadratic)));
        double minimum = origin + turning * (slope + quadratic * turning);
        for (int grid = (int) Math.ceil(Math.min(minimum, end)); grid <= Math.max(origin, end); grid++) {
            double offset = origin - grid;
            if (quadratic == 0) {
                if (slope != 0) addStop(stops, -offset / slope);
            } else {
                double discriminant = slope * slope - 4 * quadratic * offset;
                if (discriminant < 0) continue;
                double term = -0.5 * (slope + Math.copySign(Math.sqrt(discriminant), slope));
                addStop(stops, term / quadratic);
                if (term != 0) addStop(stops, offset / term);
            }
        }
    }

    private static void addStop(List<Double> stops, double t) {
        if (t > 0 && t < 1) stops.add(t);
    }

    private List<Bulb> distribute() {
        int count = Math.max(1, (int) Math.round(length / 4));
        double[] input = {start.x, start.y, start.z, end.x, end.y, end.z};
        long seed = 0;
        for (double coordinate : input) seed = Long.rotateLeft(seed, 11) ^ Double.doubleToLongBits(coordinate);
        var random = new SplittableRandom(seed);
        Bulb[] result = new Bulb[count];
        int pieceIndex = 0;
        for (int i = 0; i < count; i++) {
            double t = parameter((i + 0.5) * length / count);
            while (pieceIndex + 1 < pieces.size() && t >= pieces.get(pieceIndex).to) pieceIndex++;
            Piece piece = pieces.get(pieceIndex);
            double from = arc(piece.from);
            double to = arc(piece.to);
            double fraction = random.nextDouble();
            Vec3 anchor = point(parameter(from + (to - from) * fraction));
            if (!piece.cell.equals(BlockPos.containing(anchor))) anchor = point((piece.from + piece.to) * 0.5);
            result[i] = new Bulb(piece.cell, anchor);
        }
        return List.of(result);
    }

    public record Piece(double from, double to, BlockPos cell) { }

    public record Bulb(BlockPos cell, Vec3 anchor) {
        private static final double TARGET_PADDING = 3.0 / 64;

        public AABB glass() { return box(3, 2, 4); }
        public AABB tip() { return box(1, 4, 5); }
        public AABB socket() { return box(1, 0, 2); }
        public AABB bounds() { return glass().minmax(tip()).minmax(socket()); }

        public AABB glassTarget() { return expandTarget(glass()); }
        public AABB tipTarget() { return expandTarget(tip()); }
        public AABB targetBounds() { return glassTarget().minmax(tipTarget()).minmax(socket()); }

        private static AABB expandTarget(AABB box) {
            // Keep the upper edge of the glass below the socket.
            return box.inflate(TARGET_PADDING, 0, TARGET_PADDING).expandTowards(0, -TARGET_PADDING, 0);
        }

        private AABB box(int width, int top, int bottom) {
            double radius = width / 32.0;
            return new AABB(anchor.x - radius, anchor.y - bottom / 16.0, anchor.z - radius,
                    anchor.x + radius, anchor.y - top / 16.0, anchor.z + radius);
        }
    }
}
