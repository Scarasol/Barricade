package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.FieldDefinition;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class FieldWorkCacheTest {
    private static SpatialField field(long seed) {
        var definition = new FieldDefinition(seed, FieldDefinition.ALGORITHM, FieldDefinition.PARAMETERS, "test");
        PileModel model = new PileModel("small", "minecraft:stone", .4f, .4f, .4f, 1, List.of(CollisionBox.CUBE));
        return new SpatialField("test", definition, List.of(model), FieldOracle::solve);
    }
    @Test void solverEvictionNeverDiscardsDisplayedObjectsAndRecreationPreservesEveryPose() {
        FieldWorkCache<String> cache = new FieldWorkCache<>(2, 1_000_000);
        SpatialField a = cache.acquire("a", () -> field(1));
        var original = a.query(Set.of(BlockPos.ZERO)); cache.trim("a");
        FieldViewCache display = new FieldViewCache(); display.accept(Set.of(BlockPos.ZERO), original);
        SpatialField b = cache.acquire("b", () -> field(2));
        assertEquals(0, a.metrics().cachedPoses()); cache.trim("b");
        assertSame(a, cache.acquire("a", () -> { throw new AssertionError("Cached answer lost"); }));
        cache.acquire("c", () -> field(3)); cache.trim("c");
        assertEquals(2, cache.size());
        SpatialField replacementB = cache.acquire("b", () -> field(2)); cache.trim("b");
        assertNotSame(b, replacementB);
        assertEquals(original, List.copyOf(display.objects()));
        SpatialField replacementA = cache.acquire("a", () -> field(1));
        assertNotSame(a, replacementA);
        assertEquals(original, replacementA.query(Set.of(BlockPos.ZERO))); cache.trim("a");
        cache.retain(Set.of("a")); assertEquals(1, cache.size());
        cache.idle(); assertEquals(0, cache.cachedPoses());
        cache.retain(Set.of()); assertEquals(0, cache.size()); assertEquals(0, cache.cachedSlots());
    }
    @Test void slotBudgetEvictsOlderAnswersButAllowsOneActiveDefinition() {
        FieldWorkCache<String> cache = new FieldWorkCache<>(8, 0);
        SpatialField a = cache.acquire("a", () -> field(1)); a.query(Set.of(BlockPos.ZERO)); cache.trim("a");
        assertEquals(1, cache.size()); assertTrue(cache.cachedSlots() > 0);
        SpatialField b = cache.acquire("b", () -> field(2)); b.query(Set.of(BlockPos.ZERO)); cache.trim("b");
        assertEquals(1, cache.size()); assertEquals(b.cachedSlots(), cache.cachedSlots());
        assertEquals(0, a.metrics().cachedPoses());
    }
}
