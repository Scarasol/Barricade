package com.scarasol.barricade.recipe;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

public final class RecipeCodec {
    private RecipeCodec() { }

    public static void positions(CompoundTag tag, String key, Collection<BlockPos> positions) {
        tag.putLongArray(key, positions.stream().sorted().mapToLong(BlockPos::asLong).toArray());
    }

    public static Set<BlockPos> positions(CompoundTag tag, String key) {
        Set<BlockPos> result = new LinkedHashSet<>();
        for (long value : tag.getLongArray(key)) result.add(BlockPos.of(value));
        return Set.copyOf(result);
    }
}
