package com.scarasol.barricade.pile;

import java.util.ArrayList;
import java.util.List;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Small fixed set of sight corridors from a large face to the region's outside. */
final class PanelExposure {
    private PanelExposure() { }

    static List<OrientedBox> corridors(RegionPiece region, CellMask domain,
                                       SpatialIndex occupied, SpatialIndex obstacles) {
        PilePiece piece = region.piece();
        var model = piece.model();
        Vector3f min = model.collisionMin(), max = model.collisionMax(), span = model.span();
        int thin = span.x < span.y && span.x < span.z ? 0 : span.y < span.z ? 1 : 2;
        int a = (thin + 1) % 3, b = (thin + 2) % 3;
        List<OrientedBox> best = List.of();
        for (int sign : new int[]{-1, 1}) {
            Vector3f normal = new Vector3f().setComponent(thin, sign);
            piece.rotation().transform(normal);
            List<OrientedBox> clear = new ArrayList<>();
            for (float u : new float[]{0.2f, 0.5f, 0.8f}) {
                for (float v : new float[]{0.2f, 0.5f, 0.8f}) {
                    Vector3f local = new Vector3f(min).lerp(max, 0.5f);
                    local.setComponent(thin, sign < 0 ? min.get(thin) : max.get(thin));
                    local.setComponent(a, min.get(a) + (max.get(a) - min.get(a)) * u);
                    local.setComponent(b, min.get(b) + (max.get(b) - min.get(b)) * v);
                    Vector3f start = piece.transformPoint(local.x, local.y, local.z)
                            .add(region.owner().x(), region.owner().y(), region.owner().z()).fma(0.025f, normal);
                    float outside = 0;
                    for (float distance = 0.04f; distance <= 1.45f; distance += 0.08f) {
                        Vector3f point = new Vector3f(start).fma(distance, normal);
                        if (occupied.contains(point.x, point.y, point.z)
                                || obstacles.contains(point.x, point.y, point.z)) break;
                        outside = domain.contains((int) Math.floor(point.x), (int) Math.floor(point.y),
                                (int) Math.floor(point.z)) ? 0 : outside + 0.08f;
                        if (outside >= 0.24f) {
                            clear.add(new OrientedBox(new Vector3f(start).fma(distance / 2, normal),
                                    new Vector3f(0.015f, 0.015f, distance / 2),
                                    new Quaternionf().rotationTo(new Vector3f(0, 0, 1), normal)));
                            break;
                        }
                    }
                }
            }
            if (clear.size() > best.size()) best = clear;
        }
        return best;
    }

    static final class Guard {
        private List<OrientedBox> rays;
        private final int minimum;

        Guard(List<OrientedBox> rays) {
            this.rays = rays;
            minimum = Math.min(3, rays.size());
        }

        boolean permits(List<OrientedBox> boxes) {
            int clear = 0;
            for (OrientedBox ray : rays) {
                if (!blocked(ray, boxes) && ++clear >= minimum) return true;
            }
            return minimum == 0;
        }

        void accept(List<OrientedBox> boxes) {
            List<OrientedBox> remaining = new ArrayList<>(rays.size());
            for (OrientedBox ray : rays) if (!blocked(ray, boxes)) remaining.add(ray);
            rays = remaining;
        }

        private static boolean blocked(OrientedBox ray, List<OrientedBox> boxes) {
            for (OrientedBox box : boxes) if (box.intersects(ray, 0)) return true;
            return false;
        }
    }
}
