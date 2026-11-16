package com.scarasol.barricade.recipe;

import java.util.*;
import net.minecraft.nbt.*;

/** An atomic metadata manifest. Arrival order of nodes does not affect replay order. */
public final class ArchivedRecipeStore {
    private Map<Long, ArchivedPileRecipe> recipes = Map.of();
    private Map<String, PaletteSpec> palettes = Map.of();
    private Map<UUID, Long> heads = Map.of();
    private long samples;
    public Map<Long, ArchivedPileRecipe> recipes() { return recipes; }
    public Map<UUID, Long> heads() { return heads; }
    public PaletteSpec palette(String id) { return palettes.get(id); }
    public long samples() { return samples; }
    public void accept(CompoundTag payload) {
        Map<Long, ArchivedPileRecipe> nodes = new HashMap<>(recipes);
        Map<String, PaletteSpec> rules = new HashMap<>(palettes);
        Map<UUID, Long> regions = new TreeMap<>();
        for (ArchivedPileRecipe node : ArchivedRecipeArchive.read(payload)) {
            ArchivedPileRecipe previous = nodes.putIfAbsent(node.id(), node);
            if (previous != null && !previous.equals(node)) throw new IllegalArgumentException("Conflicting recipe");
        }
        for (Tag entry : payload.getList("Palettes", Tag.TAG_COMPOUND)) {
            PaletteSpec rule = PaletteSpec.load((CompoundTag) entry);
            PaletteSpec previous = rules.putIfAbsent(rule.id(), rule);
            if (previous != null && !previous.equals(rule)) throw new IllegalArgumentException("Conflicting material rules");
        }
        for (Tag entry : payload.getList("Heads", Tag.TAG_COMPOUND)) {
            CompoundTag header = (CompoundTag) entry;
            regions.put(header.getUUID("Id"), header.getLong("Head"));
        }
        Set<Long> live = new HashSet<>();
        Set<String> used = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>(regions.values());
        while (!queue.isEmpty()) {
            long id = queue.removeFirst();
            if (!live.add(id)) continue;
            ArchivedPileRecipe node = nodes.get(id);
            if (node == null) throw new IllegalArgumentException("Missing recipe prerequisite " + id);
            if (!rules.containsKey(node.palette())) throw new IllegalArgumentException("Missing material rules");
            used.add(node.palette());
            queue.addAll(node.prerequisites());
        }
        nodes.keySet().retainAll(live);
        rules.keySet().retainAll(used);
        recipes = Map.copyOf(nodes);
        palettes = Map.copyOf(rules);
        heads = Map.copyOf(regions);
        samples = payload.getLong("Samples");
    }
}
