package com.scarasol.barricade.recipe;

import net.minecraft.nbt.CompoundTag;

public record WorldBlock(String state, CompoundTag data, EmbeddedBlock embedded) {
    public WorldBlock(String state, CompoundTag data) { this(state, data, null); }
    public WorldBlock { data = data.copy(); }
    @Override public CompoundTag data() { return data.copy(); }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("State", state);
        if (!data.isEmpty()) tag.put("Data", data.copy());
        if (embedded != null) tag.put("Embedded", embedded.save());
        return tag;
    }

    public static WorldBlock load(CompoundTag tag) {
        return new WorldBlock(tag.getString("State"), tag.getCompound("Data"),
                tag.contains("Embedded") ? EmbeddedBlock.load(tag.getCompound("Embedded")) : null);
    }
}
