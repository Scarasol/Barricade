package com.scarasol.barricade.pile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;

public record CollisionBox(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
    public static final CollisionBox CUBE = new CollisionBox(0, 0, 0, 1, 1, 1);

    public CollisionBox {
        if (!Float.isFinite(minX + minY + minZ + maxX + maxY + maxZ)
                || minX >= maxX || minY >= maxY || minZ >= maxZ
                || minX < -3 || minY < -3 || minZ < -3 || maxX > 4 || maxY > 4 || maxZ > 4) {
            throw new IllegalArgumentException("Collision boxes must have positive size and lie in [-3, 4]");
        }
    }

    public static CollisionBox fromJson(JsonObject json) {
        JsonArray from = json.getAsJsonArray("from");
        JsonArray to = json.getAsJsonArray("to");
        if (from.size() != 3 || to.size() != 3) {
            throw new IllegalArgumentException("Collision box from/to must contain three coordinates");
        }
        return new CollisionBox(from.get(0).getAsFloat(), from.get(1).getAsFloat(), from.get(2).getAsFloat(),
                to.get(0).getAsFloat(), to.get(1).getAsFloat(), to.get(2).getAsFloat());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("a", minX);
        tag.putFloat("b", minY);
        tag.putFloat("c", minZ);
        tag.putFloat("d", maxX);
        tag.putFloat("e", maxY);
        tag.putFloat("f", maxZ);
        return tag;
    }

    public static CollisionBox load(CompoundTag tag) {
        return new CollisionBox(tag.getFloat("a"), tag.getFloat("b"), tag.getFloat("c"),
                tag.getFloat("d"), tag.getFloat("e"), tag.getFloat("f"));
    }
}
