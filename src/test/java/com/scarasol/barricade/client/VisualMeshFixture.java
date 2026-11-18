package com.scarasol.barricade.client;

import com.scarasol.barricade.pile.CollisionBox;
import com.scarasol.barricade.pile.PileModel;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;

/** Explicit synthetic geometry for headless tests; archived collider proxies are not real furniture meshes. */
final class VisualMeshFixture {
    private static final Map<List<CollisionBox>, StaticMesh> PROXIES = new HashMap<>();
    private VisualMeshFixture() { }

    static StaticMesh collisionProxy(PileModel model) {
        return PROXIES.computeIfAbsent(model.boxes(), VisualMeshFixture::boxes);
    }

    static StaticMesh boxes(CollisionBox... boxes) { return boxes(List.of(boxes)); }

    static StaticMesh boxes(List<CollisionBox> boxes) {
        return geometry(boxes.stream().map(box -> new AABB(box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ())).toList());
    }

    static StaticMesh box(AABB bounds) { return geometry(List.of(bounds)); }

    private static StaticMesh geometry(List<AABB> boxes) {
        MemoryFixture.create(true);
        List<StaticMesh.Vertex> vertices = new ArrayList<>();
        AABB bounds = null;
        for (AABB box : boxes) {
            float x = (float) box.minX, y = (float) box.minY, z = (float) box.minZ;
            float a = (float) box.maxX, b = (float) box.maxY, c = (float) box.maxZ;
            quad(vertices, x,y,z, x,y,c, x,b,c, x,b,z);
            quad(vertices, a,y,c, a,y,z, a,b,z, a,b,c);
            quad(vertices, x,y,c, x,y,z, a,y,z, a,y,c);
            quad(vertices, x,b,z, x,b,c, a,b,c, a,b,z);
            quad(vertices, a,y,z, x,y,z, x,b,z, a,b,z);
            quad(vertices, x,y,c, a,y,c, a,b,c, x,b,c);
            AABB part = new AABB(x, y, z, a, b, c);
            bounds = bounds == null ? part : bounds.minmax(part);
        }
        return new StaticMesh(List.of(new StaticMesh.Batch(RenderType.cutout(), vertices, new Vector3f(.5f))),
                bounds == null ? new AABB(0, 0, 0, 0, 0, 0) : bounds);
    }

    private static void quad(List<StaticMesh.Vertex> vertices, float... coordinates) {
        Vector3f normal = new Vector3f(coordinates[3] - coordinates[0], coordinates[4] - coordinates[1],
                coordinates[5] - coordinates[2]).cross(new Vector3f(coordinates[6] - coordinates[0],
                coordinates[7] - coordinates[1], coordinates[8] - coordinates[2])).normalize();
        for (int i = 0; i < 12; i += 3)
            vertices.add(new StaticMesh.Vertex(coordinates[i], coordinates[i + 1], coordinates[i + 2],
                    255, 255, 255, 255, 0, 0, 0, 0, normal.x, normal.y, normal.z));
    }
}
