package com.scarasol.barricade.recipe;

import net.minecraft.nbt.CompoundTag;

/** Immutable generation inputs. Region identity, domain and overlays are not part of this key. */
public record FieldDefinition(long seed, int algorithm, String parameters, String palette) {
    public static final int ALGORITHM = 10;
    public static final String PARAMETERS = FieldRules.FINGERPRINT;
    public FieldDefinition {
        if (algorithm != ALGORITHM || !PARAMETERS.equals(parameters) || palette.isBlank())
            throw new IllegalArgumentException("Unsupported barricade field definition (algorithm " + algorithm
                    + ", required " + ALGORITHM + ", parameters must match)");
    }
    public static FieldDefinition create(long seed, PaletteSpec palette) {
        return new FieldDefinition(seed, ALGORITHM, PARAMETERS, palette.id());
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Seed", seed); tag.putInt("Algorithm", algorithm);
        tag.putString("Parameters", parameters); tag.putString("Palette", palette);
        return tag;
    }
    public static FieldDefinition load(CompoundTag tag) {
        return new FieldDefinition(tag.getLong("Seed"), tag.getInt("Algorithm"),
                tag.getString("Parameters"), tag.getString("Palette"));
    }
}
