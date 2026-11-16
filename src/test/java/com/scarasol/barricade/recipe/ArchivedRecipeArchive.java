package com.scarasol.barricade.recipe;

import java.util.*;
import net.minecraft.nbt.*;

/** Per-batch context dictionary; identical environment snapshots are encoded once. */
public final class ArchivedRecipeArchive {
    private ArchivedRecipeArchive() { }
    public static void write(CompoundTag root, Collection<ArchivedPileRecipe> recipes) {
        Map<ArchivedTerrainSnapshot, Integer> indices = new LinkedHashMap<>();
        ListTag nodes = new ListTag(), contexts = new ListTag();
        for (ArchivedPileRecipe recipe : recipes.stream().sorted(Comparator.comparingLong(ArchivedPileRecipe::id)).toList()) {
            CompoundTag node = recipe.save();
            node.remove("Terrain");
            node.putInt("TerrainIndex", indices.computeIfAbsent(recipe.terrain(), key -> indices.size()));
            ListTag edits = node.getList("Damage", Tag.TAG_COMPOUND);
            for (int i = 0; i < edits.size(); i++) {
                var edit = recipe.damage().get(i);
                if (edit.fullCell()) continue;
                CompoundTag damage = edits.getCompound(i);
                damage.remove("Context");
                damage.putInt("ContextIndex", indices.computeIfAbsent(edit.context(), key -> indices.size()));
            }
            nodes.add(node);
        }
        indices.keySet().forEach(context -> contexts.add(context.save()));
        root.put("Recipes", nodes);
        root.put("Contexts", contexts);
    }
    public static List<ArchivedPileRecipe> read(CompoundTag root) {
        List<ArchivedTerrainSnapshot> contexts = new ArrayList<>();
        for (Tag context : root.getList("Contexts", Tag.TAG_COMPOUND)) contexts.add(ArchivedTerrainSnapshot.load((CompoundTag) context));
        List<ArchivedPileRecipe> recipes = new ArrayList<>();
        for (Tag entry : root.getList("Recipes", Tag.TAG_COMPOUND)) {
            CompoundTag node = (CompoundTag) entry;
            if (!node.contains("TerrainIndex", Tag.TAG_INT)) throw new IllegalArgumentException("Missing terrain reference");
            int index = node.getInt("TerrainIndex");
            List<ArchivedPileRecipe.Damage> damage = new ArrayList<>();
            for (Tag edit : node.getList("Damage", Tag.TAG_COMPOUND)) {
                CompoundTag value = (CompoundTag) edit;
                boolean full = value.getBoolean("Full");
                if (!full && !value.contains("ContextIndex", Tag.TAG_INT))
                    throw new IllegalArgumentException("Missing damage context reference");
                damage.add(new ArchivedPileRecipe.Damage(net.minecraft.core.BlockPos.of(value.getLong("Pos")), full,
                        value.getString("State"), full ? ArchivedTerrainSnapshot.EMPTY : context(contexts, value.getInt("ContextIndex"))));
            }
            recipes.add(ArchivedPileRecipe.load(node, context(contexts, index), damage));
        }
        return List.copyOf(recipes);
    }
    private static ArchivedTerrainSnapshot context(List<ArchivedTerrainSnapshot> contexts, int index) {
        if (index < 0 || index >= contexts.size()) throw new IllegalArgumentException("Missing historical terrain context");
        return contexts.get(index);
    }
}
