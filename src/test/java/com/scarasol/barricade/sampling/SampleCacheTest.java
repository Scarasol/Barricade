package com.scarasol.barricade.sampling;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SampleCacheTest {
    @TempDir Path directory;
    private MaterialSampler.Key key(String state) {
        CompoundTag item = new CompoundTag();
        item.putByte("byte", (byte) 1); item.putLong("long", 99L); item.putIntArray("array", new int[]{2, 3});
        return new MaterialSampler.Key(state, item, Direction.NORTH);
    }
    private MaterialSampler sampler(SampleCache cache, String context) {
        MaterialSampler sampler = MemoryFixture.sampler(MemoryFixture.create(false), cache, context);
        sampler.resourcesReloaded(new net.minecraft.world.item.crafting.RecipeManager(), "placement-resources");
        return sampler;
    }
    @Test void aNewProcessCanReuseMultipartNbtAndFailuresWithoutAnyPlacement() throws Exception {
        Path file = directory.resolve("samples.json");
        Map<MaterialSampler.Key, MaterialSampler.Sample> original = new LinkedHashMap<>();
        try (SampleCache cache = new SampleCache(file); MaterialSampler sampler = sampler(cache, "install")) {
            for (String state : List.of("minecraft:red_bed", "minecraft:oak_door", "minecraft:chest")) {
                var task = sampler.request(key(state));
                while (!task.isDone()) sampler.tick();
                original.put(key(state), task.join());
            }
            var failed = sampler.request(key("minecraft:air"));
            sampler.tick();
            assertTrue(failed.isCompletedExceptionally());
        }
        try (SampleCache cache = new SampleCache(file); MaterialSampler sampler = sampler(cache, "install")) {
            assertEquals(4, cache.size());
            original.forEach((key, sample) -> {
                var task = sampler.request(key);
                assertTrue(task.isDone());
                assertEquals(sample, task.join());
            });
            assertTrue(sampler.request(key("minecraft:air")).isCompletedExceptionally());
            assertTrue(sampler.stats().contains("samples=0"));
            assertTrue(sampler.stats().contains("disk_reused=4"));
            assertTrue(sampler.stats().contains("chunk_requests=0"));
            sampler.invalidateSamples();
            assertFalse(sampler.request(key("minecraft:red_bed")).isDone());
        }
    }
    @Test void changedEnvironmentMissesButWeightAndTagSelectionCanReuseSavedSamples() {
        Path file = directory.resolve("samples.json");
        try (SampleCache cache = new SampleCache(file); MaterialSampler first = sampler(cache, "install-a")) {
            var task = first.request(key("minecraft:oak_slab"));
            while (!task.isDone()) first.tick();
        }
        try (SampleCache cache = new SampleCache(file);
             MaterialSampler same = sampler(cache, "install-a");
             MaterialSampler changed = sampler(cache, "install-b")) {
            assertTrue(same.request(key("minecraft:oak_slab")).isDone());
            assertFalse(changed.request(key("minecraft:oak_slab")).isDone());
            same.resourcesReloaded(new net.minecraft.world.item.crafting.RecipeManager(), "different-placement-resources");
            assertFalse(same.request(key("minecraft:oak_slab")).isDone());
        }
    }
    @Test void corruptedAndOtherVersionFilesAreDisposable() throws Exception {
        for (String json : List.of("{", "{\"version\":999}", "{\"version\":1,\"environments\":null}")) {
            Path file = directory.resolve("broken.json");
            Files.writeString(file, json);
            try (SampleCache cache = new SampleCache(file); MaterialSampler sampler = sampler(cache, "install")) {
                assertEquals(0, cache.size());
                assertFalse(sampler.request(key("minecraft:oak_slab")).isDone());
            }
        }
    }
    @Test void nbtNumericTypesAndNestedListsRoundTripExactly() {
        var key = key("minecraft:chest");
        CompoundTag data = key.itemData();
        data.putFloat("fraction", .25f); data.putDouble("double", 2.5);
        var sample = new MaterialSampler.Sample(List.of(new com.scarasol.barricade.pile.ModelPart(
                "minecraft:chest", 0, 0, 0, data)), List.of(com.scarasol.barricade.pile.CollisionBox.CUBE));
        Path file = directory.resolve("nbt.json");
        String scope = SampleCache.digest("scope");
        try (SampleCache cache = new SampleCache(file)) { cache.put(scope, key, sample, null); }
        try (SampleCache cache = new SampleCache(file)) {
            assertEquals(sample, cache.get(scope, key).sample());
            assertEquals(data, cache.get(scope, key).sample().parts().get(0).blockEntityData());
        }
    }
    @Test void selectorNamesAndWeightsDoNotInvalidatePersistedGeometry() {
        Path file = directory.resolve("selection.json");
        try (SampleCache cache = new SampleCache(file); MaterialSampler sampler = sampler(cache, "install")) {
            var models = sampler.prepare(List.of(new com.scarasol.barricade.pile.PileModels.Candidate(
                    "old-name", "minecraft:oak_slab", 1)), 1);
            while (!models.isDone()) sampler.tick();
            assertEquals(1, models.join().get(0).weight());
        }
        try (SampleCache cache = new SampleCache(file); MaterialSampler sampler = sampler(cache, "install")) {
            var models = sampler.prepare(List.of(new com.scarasol.barricade.pile.PileModels.Candidate(
                    "new-name", "minecraft:oak_slab", 7)), 2);
            assertTrue(models.isDone());
            assertEquals("new-name", models.join().get(0).id());
            assertEquals(7, models.join().get(0).weight());
            assertTrue(sampler.stats().contains("samples=0"));
            assertTrue(sampler.stats().contains("disk_reused=1"));
        }
    }
}
