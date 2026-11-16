package com.scarasol.barricade.client;

import com.scarasol.barricade.recipe.ArchivedPileRecipe;
import com.scarasol.barricade.recipe.ArchivedPileReplay;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** Frozen generation inputs and a deduplicated current spatial mask, not an edit log. */
final class StateMaskPrototype {
    private final ArchivedPileRecipe base;
    private final Set<BlockPos> removed;

    StateMaskPrototype(ArchivedPileRecipe base, Set<BlockPos> removed) {
        if (!base.parents().isEmpty() || !base.neighbors().isEmpty()
                || !base.damage().isEmpty() || !base.active().equals(base.domain()))
            throw new IllegalArgumentException("A baseline must be independently reproducible");
        this.base = base;
        this.removed = Set.copyOf(removed);
    }

    ArchivedPileRecipe base() { return base; }
    Set<BlockPos> removed() { return removed; }

    StateMaskPrototype remove(BlockPos pos) {
        Set<BlockPos> next = new HashSet<>(removed);
        next.add(pos.immutable());
        return new StateMaskPrototype(base, next);
    }

    List<ArchivedPileReplay.ObjectPiece> restore(
            Function<ArchivedPileRecipe, ArchivedPileReplay.Prepared> prepare,
            Function<com.scarasol.barricade.pile.LayoutInput,
                    com.scarasol.barricade.pile.LayoutResult> generate) {
        List<ArchivedPileReplay.ObjectPiece> complete = ArchivedPileReplay.replay(
                prepare.apply(base), List.of(), List.of(), generate);
        if (removed.isEmpty()) return complete;
        ArchivedPileRecipe masked = new ArchivedPileRecipe(base.id() + 1, base.seed(), List.of(), base.domain(),
                Set.of(), Set.of(), base.palette(), base.terrain(), removed.stream().sorted()
                        .map(pos -> new ArchivedPileRecipe.Damage(pos, true, "", base.terrain())).toList());
        return ArchivedPileReplay.replay(prepare.apply(masked), List.of(complete), List.of(),
                ignored -> { throw new AssertionError("Masking must not regenerate or refill"); });
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Base", base.save());
        tag.putLongArray("Removed", removed.stream().sorted().mapToLong(BlockPos::asLong).toArray());
        return tag;
    }

    static StateMaskPrototype load(CompoundTag tag) {
        ArchivedPileRecipe base = ArchivedPileRecipe.load(tag.getCompound("Base"));
        Set<BlockPos> removed = new HashSet<>();
        for (long pos : tag.getLongArray("Removed")) removed.add(BlockPos.of(pos));
        return new StateMaskPrototype(base, removed);
    }
}
