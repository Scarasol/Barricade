package com.scarasol.barricade.client;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.scarasol.barricade.pile.PilePiece;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import org.joml.Intersectionf;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Narrow phase for embedded models whose bounds overlap a barricade piece. */
final class VisualOverlap {
    private static final float TOLERANCE = TerrainShapes.TOLERANCE;
    private static final float EPSILON = 0.00001f;
    private static final Vector3f[] RAYS = {
            new Vector3f(1, .173f, .267f).normalize(),
            new Vector3f(-1, -.173f, -.267f).normalize(),
            new Vector3f(.091f, 1, .371f).normalize(),
            new Vector3f(-.091f, -1, -.371f).normalize(),
            new Vector3f(.193f, .113f, 1).normalize(),
            new Vector3f(-.193f, -.113f, -1).normalize()
    };

    private VisualOverlap() { }

    record Geometry(List<Face> faces, AABB bounds) {
        Geometry { faces = List.copyOf(faces); }

        Geometry transformed(PilePiece piece, int dx, int dy, int dz) {
            var model = piece.model();
            Matrix4f transform = new Matrix4f().translation(piece.x() + dx, piece.y() + dy, piece.z() + dz)
                    .rotate(piece.rotation()).scale(model.sizeX() * piece.scale(),
                            model.sizeY() * piece.scale(), model.sizeZ() * piece.scale())
                    .translate(-model.pivotX(), -model.pivotY(), -model.pivotZ());
            List<Face> result = new ArrayList<>(faces.size());
            for (Face face : faces) {
                Vector3f[] points = new Vector3f[face.points.length];
                for (int i = 0; i < points.length; i++)
                    points[i] = transform.transformPosition(new Vector3f(face.points[i]));
                result.add(new Face(points));
            }
            return geometry(result);
        }
    }

    private static final class Face {
        final Vector3f[] points;
        final Vector3f normal;
        final AABB bounds;

        Face(Vector3f[] points) {
            this.points = points;
            normal = new Vector3f(points[1]).sub(points[0]).cross(new Vector3f(points[2]).sub(points[0]));
            if (normal.lengthSquared() > EPSILON * EPSILON) normal.normalize();
            AABB box = pointBounds(points[0]);
            for (int i = 1; i < points.length; i++) box = box.minmax(pointBounds(points[i]));
            bounds = box;
        }

        boolean straddles(Face plane) {
            float min = Float.POSITIVE_INFINITY, max = Float.NEGATIVE_INFINITY;
            for (Vector3f point : points) {
                float distance = (point.x - plane.points[0].x) * plane.normal.x
                        + (point.y - plane.points[0].y) * plane.normal.y
                        + (point.z - plane.points[0].z) * plane.normal.z;
                min = Math.min(min, distance); max = Math.max(max, distance);
            }
            return min < -TOLERANCE && max > TOLERANCE;
        }

        float ray(Vector3f origin, Vector3f direction) {
            float nearest = Float.POSITIVE_INFINITY;
            for (int i = 1; i < points.length - 1; i++) {
                float distance = Intersectionf.intersectRayTriangle(origin, direction,
                        points[0], points[i], points[i + 1], EPSILON);
                if (distance >= 0) nearest = Math.min(nearest, distance);
            }
            return nearest;
        }
    }

    static Geometry capture(StaticMesh mesh) {
        List<Face> faces = new ArrayList<>();
        for (StaticMesh.Batch batch : mesh.batches) {
            int size = batch.material.mode() == VertexFormat.Mode.QUADS ? 4
                    : batch.material.mode() == VertexFormat.Mode.TRIANGLES ? 3 : 0;
            if (size == 0) continue;
            for (int i = 0; i + size <= batch.vertices.size(); i += size) {
                Vector3f[] points = new Vector3f[size];
                for (int j = 0; j < size; j++) {
                    StaticMesh.Vertex vertex = batch.vertices.get(i + j);
                    points[j] = new Vector3f(vertex.x(), vertex.y(), vertex.z());
                }
                Face face = new Face(points);
                if (face.normal.lengthSquared() > EPSILON) faces.add(face);
            }
        }
        return geometry(faces);
    }

    private static Geometry geometry(List<Face> faces) {
        AABB bounds = null;
        for (Face face : faces) bounds = bounds == null ? face.bounds : bounds.minmax(face.bounds);
        return new Geometry(faces, bounds == null ? new AABB(0, 0, 0, 0, 0, 0) : bounds);
    }

    static boolean obvious(Geometry first, Geometry second) {
        if (!first.bounds.intersects(second.bounds)) return false;
        Vector3f start = new Vector3f(), point = new Vector3f();
        for (Face a : first.faces) for (Face b : second.faces) {
            if (!a.bounds.intersects(b.bounds) || !a.straddles(b) || !b.straddles(a)) continue;
            boolean found = false;
            for (int side = 0; side < 2; side++) {
                Face edges = side == 0 ? a : b, surface = side == 0 ? b : a;
                for (int edge = 0; edge < edges.points.length; edge++)
                    for (int triangle = 1; triangle < surface.points.length - 1; triangle++) {
                        if (!Intersectionf.intersectLineSegmentTriangle(edges.points[edge],
                                edges.points[(edge + 1) % edges.points.length], surface.points[0],
                                surface.points[triangle], surface.points[triangle + 1], EPSILON, point)) continue;
                        if (found && point.distanceSquared(start) > TOLERANCE * TOLERANCE) return true;
                        if (!found) { start.set(point); found = true; }
                    }
            }
        }
        return enclosedSurface(first, second) || enclosedSurface(second, first);
    }

    private static boolean enclosedSurface(Geometry samples, Geometry solid) {
        // Test actual face points, not a collider/bounds center that may sit in a chair or frame's empty space.
        int count = Math.min(12, samples.faces.size());
        Vector3f point = new Vector3f();
        for (int i = 0; i < count; i++) {
            Face face = samples.faces.get(i * samples.faces.size() / count);
            point.zero();
            for (Vector3f vertex : face.points) point.add(vertex);
            point.div(face.points.length);
            if (solid.bounds.contains(point.x, point.y, point.z) && inside(point, solid)) return true;
            // Coincident closed surfaces have no crossing line; probe just inside their visible shell.
            point.fma(-2 * TOLERANCE, face.normal);
            if (solid.bounds.contains(point.x, point.y, point.z) && inside(point, solid)
                    && inside(point, samples)) return true;
        }
        return false;
    }

    private static boolean inside(Vector3f point, Geometry solid) {
        for (Vector3f ray : RAYS) {
            if (!enclosedRay(point, ray, solid)) return false;
        }
        return true;
    }

    private static boolean enclosedRay(Vector3f point, Vector3f ray, Geometry solid) {
        int winding = 0;
        float nearest = Float.POSITIVE_INFINITY;
        for (Face face : solid.faces) {
            float distance = face.ray(point, ray);
            if (!Float.isFinite(distance)) continue;
            nearest = Math.min(nearest, distance);
            winding += face.normal.dot(ray) > 0 ? 1 : -1;
        }
        return winding != 0 && nearest > TOLERANCE;
    }

    private static AABB pointBounds(Vector3f point) {
        return new AABB(point.x, point.y, point.z, point.x, point.y, point.z);
    }
}
