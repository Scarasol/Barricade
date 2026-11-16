package com.scarasol.barricade.pile;

import net.minecraft.nbt.CompoundTag;

/** One sampled block, positioned in the shared local space of an object. */
public record ModelPart(String blockState, float x, float y, float z, CompoundTag blockEntityData) {
    public ModelPart(String blockState, float x, float y, float z) {
        this(blockState, x, y, z, new CompoundTag());
    }

    public ModelPart {
        blockEntityData = blockEntityData.copy();
        if (blockState.isBlank() || !Float.isFinite(x + y + z)
                || Math.abs(x) > 3 || Math.abs(y) > 3 || Math.abs(z) > 3) {
            throw new IllegalArgumentException("Invalid pile model part");
        }
    }

    @Override
    public CompoundTag blockEntityData() {
        return blockEntityData.copy();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Block", blockState);
        tag.putFloat("X", x);
        tag.putFloat("Y", y);
        tag.putFloat("Z", z);
        if (!blockEntityData.isEmpty()) tag.put("BlockEntity", blockEntityData.copy());
        return tag;
    }

    public static ModelPart load(CompoundTag tag) {
        return new ModelPart(tag.getString("Block"), tag.getFloat("X"), tag.getFloat("Y"), tag.getFloat("Z"),
                tag.getCompound("BlockEntity"));
    }
}
