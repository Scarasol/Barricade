package com.scarasol.barricade.stringlights;

import net.minecraft.world.phys.Vec3;

/** The installed wire can enter a box without moving the span's lamp layout. */
public record LightCable(LightCurve curve, Vec3 start, Vec3 end) {
    public static LightCable between(LightCurve curve, Vec3 anchor, Vec3 first, Vec3 second) {
        return curve.start().equals(anchor) ? new LightCable(curve, first, second) : new LightCable(curve, second, first);
    }

    public Vec3 point(double t) {
        if (t == 0) return start;
        if (t == 1) return end;
        Vec3 point = curve.point(t);
        if (t < curve.startLead() && !start.equals(curve.start())) {
            return point.add(start.subtract(curve.start()).scale(fade(t / curve.startLead())));
        }
        if (t > curve.endLead() && !end.equals(curve.end())) {
            return point.add(end.subtract(curve.end()).scale(fade((1 - t) / (1 - curve.endLead()))));
        }
        return point;
    }

    public void walk(LightCurve.Piece piece, Segment segment) {
        double from = piece.from();
        double to = piece.to();
        if (!start.equals(curve.start()) && from < curve.startLead() && curve.startLead() < to) {
            walk(from, curve.startLead(), segment);
            from = curve.startLead();
        }
        if (!end.equals(curve.end()) && from < curve.endLead() && curve.endLead() < to) {
            walk(from, curve.endLead(), segment);
            from = curve.endLead();
        }
        walk(from, to, segment);
    }

    private void walk(double from, double to, Segment segment) {
        Vec3 a = point(from), endPoint = point(to);
        int count = Math.max(1, (int) Math.ceil(a.distanceTo(endPoint) * 3));
        if ((!start.equals(curve.start()) && from < curve.startLead())
                || (!end.equals(curve.end()) && to > curve.endLead())) count = Math.max(count, 4);
        for (int i = 1; i <= count; i++) {
            double next = i == count ? to : from + (to - from) * i / count;
            Vec3 b = i == count ? endPoint : point(next);
            segment.accept(i == 1 ? from : from + (to - from) * (i - 1) / count, next, a, b);
            a = b;
        }
    }

    private static double fade(double t) { return 1 - t * t * (3 - 2 * t); }

    @FunctionalInterface
    public interface Segment {
        void accept(double from, double to, Vec3 a, Vec3 b);
    }
}
