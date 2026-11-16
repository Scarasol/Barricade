package com.scarasol.barricade.pile;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.joml.Vector3f;

public record PileModel(String id, List<ModelPart> parts, float sizeX, float sizeY, float sizeZ,
                        float weight, List<CollisionBox> boxes, float pivotX, float pivotY, float pivotZ) {
    public PileModel(String id, String blockState, float sizeX, float sizeY, float sizeZ,
                     float weight, List<CollisionBox> boxes) {
        this(id, List.of(new ModelPart(blockState, 0, 0, 0)), sizeX, sizeY, sizeZ,
                weight, boxes, 0.5f, 0.5f, 0.5f);
    }

    public PileModel {
        parts = List.copyOf(parts);
        boxes = List.copyOf(boxes);
        if (id.isBlank() || parts.isEmpty() || parts.size() > 16
                || !Float.isFinite(sizeX + sizeY + sizeZ + weight + pivotX + pivotY + pivotZ)
                || sizeX < 0.1f || sizeY < 0.1f || sizeZ < 0.1f
                || sizeX > 1.6f || sizeY > 1.6f || sizeZ > 1.6f
                || Math.abs(pivotX) > 4 || Math.abs(pivotY) > 4 || Math.abs(pivotZ) > 4
                || weight <= 0 || boxes.isEmpty() || boxes.size() > 16) {
            throw new IllegalArgumentException("Invalid pile model: " + id);
        }
    }

    public String blockState() {
        return parts.get(0).blockState();
    }

    public Vector3f collisionMin() {
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        for (CollisionBox box : boxes) {
            min.x = Math.min(min.x, box.minX());
            min.y = Math.min(min.y, box.minY());
            min.z = Math.min(min.z, box.minZ());
        }
        return min;
    }

    public Vector3f collisionMax() {
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        for (CollisionBox box : boxes) {
            max.x = Math.max(max.x, box.maxX());
            max.y = Math.max(max.y, box.maxY());
            max.z = Math.max(max.z, box.maxZ());
        }
        return max;
    }

    public Vector3f collisionCenter() {
        return collisionMin().add(collisionMax()).mul(0.5f);
    }

    public Vector3f span() {
        return collisionMax().sub(collisionMin()).mul(sizeX, sizeY, sizeZ);
    }

    public float volume() {
        float volume = 0;
        for (CollisionBox box : boxes) {
            volume += (box.maxX() - box.minX()) * (box.maxY() - box.minY()) * (box.maxZ() - box.minZ());
        }
        return volume * sizeX * sizeY * sizeZ;
    }

    public boolean panel() {
        Vector3f span = span();
        return Math.min(span.x, Math.min(span.y, span.z)) < 0.36f
                && Math.max(span.x, Math.max(span.y, span.z)) > 1.5f;
    }

    public boolean bulk() {
        Vector3f span = span();
        return Math.min(span.x, Math.min(span.y, span.z)) > 0.8f && volume() > 0.8f;
    }

    public boolean elongated() {
        Vector3f span = span();
        return Math.max(span.x, Math.max(span.y, span.z)) > 1.5f;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        ListTag savedParts = new ListTag();
        parts.forEach(part -> savedParts.add(part.save()));
        tag.put("Parts", savedParts);
        tag.putFloat("PivotX", pivotX);
        tag.putFloat("PivotY", pivotY);
        tag.putFloat("PivotZ", pivotZ);
        tag.putFloat("X", sizeX);
        tag.putFloat("Y", sizeY);
        tag.putFloat("Z", sizeZ);
        tag.putFloat("Weight", weight);
        ListTag shapes = new ListTag();
        boxes.forEach(box -> shapes.add(box.save()));
        tag.put("Boxes", shapes);
        return tag;
    }

    public static PileModel load(CompoundTag tag) {
        ListTag shapes = tag.getList("Boxes", Tag.TAG_COMPOUND);
        java.util.ArrayList<CollisionBox> boxes = new java.util.ArrayList<>();
        for (int i = 0; i < shapes.size(); i++) {
            boxes.add(CollisionBox.load(shapes.getCompound(i)));
        }
        java.util.ArrayList<ModelPart> parts = new java.util.ArrayList<>();
        ListTag savedParts = tag.getList("Parts", Tag.TAG_COMPOUND);
        for (int i = 0; i < savedParts.size(); i++) {
            parts.add(ModelPart.load(savedParts.getCompound(i)));
        }
        return new PileModel(tag.getString("Id"), parts,
                tag.getFloat("X"), tag.getFloat("Y"), tag.getFloat("Z"), tag.getFloat("Weight"), boxes,
                tag.getFloat("PivotX"), tag.getFloat("PivotY"), tag.getFloat("PivotZ"));
    }
}
