package com.scarasol.barricade.pile;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public record PilePiece(PileModel model, float x, float y, float z, float scale,
                        float qx, float qy, float qz, float qw) {
    public PilePiece {
        float length = qx * qx + qy * qy + qz * qz + qw * qw;
        if (!Float.isFinite(x + y + z + scale + length) || scale < 0.1f || scale > 1.2f
                || x < -8 || x > 9 || y < -8 || y > 9 || z < -8 || z > 9
                || Math.abs(length - 1) > 0.01f) {
            throw new IllegalArgumentException("Invalid pile piece transform");
        }
    }

    public static PilePiece create(PileModel model, float x, float y, float z, float scale, Quaternionf rotation) {
        return new PilePiece(model, x, y, z, scale, rotation.x, rotation.y, rotation.z, rotation.w);
    }

    public PilePiece translated(float dx, float dy, float dz) {
        return new PilePiece(model, x + dx, y + dy, z + dz, scale, qx, qy, qz, qw);
    }

    public Quaternionf rotation() {
        return new Quaternionf(qx, qy, qz, qw);
    }

    public List<OrientedBox> colliders(int offsetX, int offsetY, int offsetZ) {
        Quaternionf rotation = rotation();
        float sx = model.sizeX() * scale, sy = model.sizeY() * scale, sz = model.sizeZ() * scale;
        List<OrientedBox> result = new ArrayList<>(model.boxes().size());
        for (CollisionBox box : model.boxes()) {
            Vector3f center = new Vector3f(
                    ((box.minX() + box.maxX()) * 0.5f - model.pivotX()) * sx,
                    ((box.minY() + box.maxY()) * 0.5f - model.pivotY()) * sy,
                    ((box.minZ() + box.maxZ()) * 0.5f - model.pivotZ()) * sz);
            rotation.transform(center).add(x + offsetX, y + offsetY, z + offsetZ);
            result.add(new OrientedBox(center, new Vector3f(
                    (box.maxX() - box.minX()) * sx * 0.5f,
                    (box.maxY() - box.minY()) * sy * 0.5f,
                    (box.maxZ() - box.minZ()) * sz * 0.5f), rotation));
        }
        return result;
    }

    /** Same quantized OBBs as colliders(), without allocating a box and its axes per component. */
    void encodeColliders(int[] data, int offset) {
        encodeColliders(data, offset, 0, 0, 0);
    }
    void encodeColliders(int[] data, int offset, int offsetX, int offsetY, int offsetZ) {
        Quaternionf rotation = rotation();
        Vector3f[] axes = {rotation.transform(new Vector3f(1, 0, 0)),
                rotation.transform(new Vector3f(0, 1, 0)), rotation.transform(new Vector3f(0, 0, 1))};
        float sx = model.sizeX() * scale, sy = model.sizeY() * scale, sz = model.sizeZ() * scale;
        Vector3f center = new Vector3f(), half = new Vector3f();
        for (CollisionBox box : model.boxes()) {
            center.set(((box.minX() + box.maxX()) * 0.5f - model.pivotX()) * sx,
                    ((box.minY() + box.maxY()) * 0.5f - model.pivotY()) * sy,
                    ((box.minZ() + box.maxZ()) * 0.5f - model.pivotZ()) * sz);
            rotation.transform(center).add(x + offsetX, y + offsetY, z + offsetZ);
            half.set((box.maxX() - box.minX()) * sx * 0.5f,
                    (box.maxY() - box.minY()) * sy * 0.5f, (box.maxZ() - box.minZ()) * sz * 0.5f);
            FixedGeometry.encode(center, half, axes, data, offset);
            offset += FixedGeometry.STRIDE;
        }
    }

    /** Exact collider AABB without allocating the individual oriented boxes. */
    float[] collisionBounds() {
        Quaternionf rotation = rotation();
        Vector3f ax = rotation.transform(new Vector3f(1, 0, 0));
        Vector3f ay = rotation.transform(new Vector3f(0, 1, 0));
        Vector3f az = rotation.transform(new Vector3f(0, 0, 1));
        float sx = model.sizeX() * scale, sy = model.sizeY() * scale, sz = model.sizeZ() * scale;
        float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        Vector3f center = new Vector3f();
        for (CollisionBox box : model.boxes()) {
            center.set(((box.minX() + box.maxX()) * 0.5f - model.pivotX()) * sx,
                    ((box.minY() + box.maxY()) * 0.5f - model.pivotY()) * sy,
                    ((box.minZ() + box.maxZ()) * 0.5f - model.pivotZ()) * sz);
            rotation.transform(center).add(x, y, z);
            float hx = (box.maxX() - box.minX()) * sx * 0.5f;
            float hy = (box.maxY() - box.minY()) * sy * 0.5f;
            float hz = (box.maxZ() - box.minZ()) * sz * 0.5f;
            float ex = Math.abs(ax.x) * hx + Math.abs(ay.x) * hy + Math.abs(az.x) * hz;
            float ey = Math.abs(ax.y) * hx + Math.abs(ay.y) * hy + Math.abs(az.y) * hz;
            float ez = Math.abs(ax.z) * hx + Math.abs(ay.z) * hy + Math.abs(az.z) * hz;
            bounds[0] = Math.min(bounds[0], center.x - ex);
            bounds[1] = Math.min(bounds[1], center.y - ey);
            bounds[2] = Math.min(bounds[2], center.z - ez);
            bounds[3] = Math.max(bounds[3], center.x + ex);
            bounds[4] = Math.max(bounds[4], center.y + ey);
            bounds[5] = Math.max(bounds[5], center.z + ez);
        }
        return bounds;
    }

    public Vector3f transformPoint(float localX, float localY, float localZ) {
        Vector3f point = new Vector3f((localX - model.pivotX()) * model.sizeX() * scale,
                (localY - model.pivotY()) * model.sizeY() * scale,
                (localZ - model.pivotZ()) * model.sizeZ() * scale);
        return rotation().transform(point).add(x, y, z);
    }

    public List<OrientedBox> renderBoxes() {
        List<OrientedBox> bounds = new ArrayList<>();
        for (ModelPart part : model.parts()) {
            bounds.add(new OrientedBox(transformPoint(part.x() + 0.5f, part.y() + 0.5f, part.z() + 0.5f),
                    new Vector3f(model.sizeX(), model.sizeY(), model.sizeZ()).mul(scale * 0.5f), rotation()));
        }
        return bounds;
    }

    public CompoundTag save(int modelIndex) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Model", modelIndex);
        tag.putFloat("X", x);
        tag.putFloat("Y", y);
        tag.putFloat("Z", z);
        tag.putFloat("Scale", scale);
        tag.putFloat("Qx", qx);
        tag.putFloat("Qy", qy);
        tag.putFloat("Qz", qz);
        tag.putFloat("Qw", qw);
        return tag;
    }

    public static PilePiece load(CompoundTag tag, List<PileModel> models) {
        return new PilePiece(models.get(tag.getInt("Model")), tag.getFloat("X"), tag.getFloat("Y"),
                tag.getFloat("Z"), tag.getFloat("Scale"), tag.getFloat("Qx"), tag.getFloat("Qy"),
                tag.getFloat("Qz"), tag.getFloat("Qw"));
    }
}
