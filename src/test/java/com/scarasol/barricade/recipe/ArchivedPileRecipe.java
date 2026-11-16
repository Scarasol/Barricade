package com.scarasol.barricade.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** Immutable edit node. Parents retain the inputs needed to replay merged regions. */
public record ArchivedPileRecipe(long id, long seed, List<Long> parents, Set<BlockPos> domain,
                         Set<BlockPos> active, Set<BlockPos> repack, String palette,
                         ArchivedTerrainSnapshot terrain, List<Damage> damage, List<Long> neighbors,
                         Map<BlockPos, EmbeddedBlock> embedded) {
    public static final int ALGORITHM = 5;

    public ArchivedPileRecipe(long id, long seed, List<Long> parents, Set<BlockPos> domain,
                      Set<BlockPos> active, Set<BlockPos> repack, String palette,
                      ArchivedTerrainSnapshot terrain, List<Damage> damage) {
        this(id, seed, parents, domain, active, repack, palette, terrain, damage, List.of(), Map.of());
    }
    public ArchivedPileRecipe(long id, long seed, List<Long> parents, Set<BlockPos> domain,
                      Set<BlockPos> active, Set<BlockPos> repack, String palette,
                      ArchivedTerrainSnapshot terrain, List<Damage> damage, List<Long> neighbors) {
        this(id, seed, parents, domain, active, repack, palette, terrain, damage, neighbors, Map.of());
    }
    public ArchivedPileRecipe {
        parents = List.copyOf(parents);
        domain = Set.copyOf(domain);
        active = Set.copyOf(active);
        repack = Set.copyOf(repack);
        damage = List.copyOf(damage);
        neighbors = List.copyOf(neighbors);
        embedded = Map.copyOf(embedded);
        boolean invalidEmbedded = false;
        for (var entry : embedded.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().pos()) || !domain.contains(entry.getKey())) {
                invalidEmbedded = true;
                break;
            }
        }
        if (id <= 0 || domain.isEmpty() || !domain.containsAll(active) || !active.containsAll(repack)
                || invalidEmbedded
                || prerequisites(parents, neighbors).stream().anyMatch(parent -> parent <= 0 || parent >= id)) {
            throw new IllegalArgumentException("Invalid barricade recipe");
        }
    }
    private static List<Long> prerequisites(List<Long> parents, List<Long> neighbors) {
        List<Long> result = new ArrayList<>(parents);
        result.addAll(neighbors);
        return List.copyOf(result);
    }
    public List<Long> prerequisites() { return prerequisites(parents, neighbors); }

    public record Damage(BlockPos pos, boolean fullCell, String state, ArchivedTerrainSnapshot context) {
        public Damage { pos = pos.immutable(); }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("Pos", pos.asLong());
            tag.putBoolean("Full", fullCell);
            tag.putString("State", state);
            if (!fullCell) tag.put("Context", context.save());
            return tag;
        }

        public static Damage load(CompoundTag tag) {
            return new Damage(BlockPos.of(tag.getLong("Pos")), tag.getBoolean("Full"),
                    tag.getString("State"), ArchivedTerrainSnapshot.load(tag.getCompound("Context")));
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Algorithm", ALGORITHM);
        tag.putLong("Id", id);
        tag.putLong("Seed", seed);
        tag.putLongArray("Parents", parents.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray("Neighbors", neighbors.stream().mapToLong(Long::longValue).toArray());
        RecipeCodec.positions(tag, "Domain", domain);
        RecipeCodec.positions(tag, "Active", active);
        RecipeCodec.positions(tag, "Repack", repack);
        tag.putString("Palette", palette);
        tag.put("Terrain", terrain.save());
        ListTag edits = new ListTag();
        damage.forEach(edit -> edits.add(edit.save()));
        tag.put("Damage", edits);
        ListTag savedEmbedded = new ListTag();
        embedded.values().stream().sorted(java.util.Comparator.comparing(EmbeddedBlock::pos))
                .forEach(block -> savedEmbedded.add(block.save()));
        tag.put("Embedded", savedEmbedded);
        return tag;
    }

    public static ArchivedPileRecipe load(CompoundTag tag) {
        List<Damage> damage = new ArrayList<>();
        for (Tag entry : tag.getList("Damage", Tag.TAG_COMPOUND)) damage.add(Damage.load((CompoundTag) entry));
        return load(tag, ArchivedTerrainSnapshot.load(tag.getCompound("Terrain")), damage);
    }

    static ArchivedPileRecipe load(CompoundTag tag, ArchivedTerrainSnapshot terrain, List<Damage> damage) {
        if (tag.getInt("Algorithm") != ALGORITHM) throw new IllegalArgumentException("Unsupported layout algorithm");
        List<Long> parents = new ArrayList<>();
        for (long parent : tag.getLongArray("Parents")) parents.add(parent);
        List<Long> neighbors = new ArrayList<>();
        for (long neighbor : tag.getLongArray("Neighbors")) neighbors.add(neighbor);
        Map<BlockPos, EmbeddedBlock> embedded = new java.util.HashMap<>();
        for (Tag value : tag.getList("Embedded", Tag.TAG_COMPOUND)) {
            EmbeddedBlock block = EmbeddedBlock.load((CompoundTag) value);
            if (embedded.put(block.pos(), block) != null)
                throw new IllegalArgumentException("Duplicate embedded block position");
        }
        return new ArchivedPileRecipe(tag.getLong("Id"), tag.getLong("Seed"), parents,
                RecipeCodec.positions(tag, "Domain"), RecipeCodec.positions(tag, "Active"),
                RecipeCodec.positions(tag, "Repack"), tag.getString("Palette"),
                terrain, damage, neighbors, embedded);
    }
}
