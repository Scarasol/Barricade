package com.scarasol.barricade.sampling;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.PaletteSpec;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class TaggedSamplingTest {
    private static final PaletteSpec DEFAULT = PaletteSpec.create(List.of(
            new PileModels.Candidate("fallback", "minecraft:oak_planks", 1)), "resources");
    private static PaletteSpec selected(List<PileModels.Candidate> candidates) {
        return PaletteSpec.create(candidates, "resources", "example:chosen", DEFAULT);
    }
    private static List<PileModel> finish(MaterialSampler sampler, CompletableFuture<List<PileModel>> future) {
        for (int i = 0; i < 256 && !future.isDone(); i++) sampler.tick();
        assertTrue(future.isDone());
        return future.join();
    }
    @Test void missingEmptyAndUnsupportedSelectionsUseOnlyTheFrozenDefaultSnapshot() {
        try (var sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            for (var candidates : List.<List<PileModels.Candidate>>of(List.of(),
                    List.of(new PileModels.Candidate("air", "minecraft:air", 1)),
                    List.of(new PileModels.Candidate("bad", "example:missing_block", 1)))) {
                PaletteSpec selected = selected(candidates);
                var future = sampler.prepare(selected);
                assertEquals(List.of("fallback"), finish(sampler, future).stream().map(PileModel::id).toList());
                assertSame(future, sampler.prepare(selected));
            }
        }
    }
    @Test void aPartiallyUsableCustomPoolNeverMixesInDefaultMaterials() {
        try (var sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            var chosen = selected(List.of(new PileModels.Candidate("slab", "minecraft:oak_slab", 1),
                    new PileModels.Candidate("bad", "minecraft:air", 1)));
            List<PileModel> result = finish(sampler, sampler.prepare(chosen));
            assertEquals(List.of("slab"), result.stream().map(PileModel::id).toList());
            assertFalse(result.get(0).bulk());
        }
    }
    @Test void defaultFailureCompletesEmptyWithoutRecursiveFallbackOrRepeatedSampling() {
        var empty = PaletteSpec.create(List.of(new PileModels.Candidate("bad", "minecraft:air", 1)), "resources");
        var chosen = PaletteSpec.create(List.of(), "resources", "example:empty", empty);
        try (var sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            var future = sampler.prepare(chosen);
            assertTrue(finish(sampler, future).isEmpty());
            String stats = sampler.stats();
            for (int i = 0; i < 100; i++) { assertSame(future, sampler.prepare(chosen)); sampler.tick(); }
            assertEquals(stats, sampler.stats());
        }
    }
    @Test void invalidationCancelsPendingSelectionsBeforeTheyCanQueueOrphanFallbackRequests() {
        var chosen = selected(List.of(new PileModels.Candidate("bad", "minecraft:air", 1)));
        try (var sampler = MemoryFixture.sampler(MemoryFixture.create(false))) {
            var pending = sampler.prepare(chosen);
            assertFalse(pending.isDone());
            sampler.invalidateSamples();
            assertTrue(pending.isCompletedExceptionally());
            assertEquals(List.of("fallback"), finish(sampler, sampler.prepare(chosen)).stream().map(PileModel::id).toList());
        }
    }
}
