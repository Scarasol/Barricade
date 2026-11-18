package com.scarasol.barricade.client;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.sampling.SampleCache;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.config.ConfigTracker;

/** Lives across world changes; neither a world save nor a GPU/model cache. */
final class ClientSampleCache {
    private static CompletableFuture<SampleCache> loading;
    private static String installation;

    static void initialize() {
        if (loading != null) return;
        StringBuilder code = new StringBuilder("native-quad-visible-bounds-sampler\n");
        ModList.get().getMods().stream().sorted(Comparator.comparing(mod -> mod.getModId())).forEach(mod -> {
            code.append(mod.getModId()).append('=').append(mod.getVersion()).append('\n');
            var path = mod.getOwningFile().getFile().getFilePath();
            try {
                if (Files.isRegularFile(path)) code.append(Files.size(path)).append(':')
                        .append(Files.getLastModifiedTime(path).toMillis()).append('\n');
            } catch (java.io.IOException exception) {
                code.append(UUID.randomUUID()).append('\n'); // An unreadable install must not reuse old data.
            }
        });
        installation = SampleCache.digest(code.toString());
        var file = Minecraft.getInstance().gameDirectory.toPath().resolve("cache/barricade/material_samples.json");
        loading = CompletableFuture.supplyAsync(() -> {
            SampleCache cache = new SampleCache(file);
            Barricade.LOGGER.info("Loaded {} cached client material samples from {}", cache.size(), file);
            return cache;
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { loading.join().close(); }
            catch (RuntimeException exception) { Barricade.LOGGER.warn("Sample cache shutdown failed", exception); }
        }, "Barricade cache shutdown"));
    }

    static SampleCache ready() {
        initialize();
        return loading.getNow(null);
    }
    static void shutdown() {
        if (loading != null) loading.join().close();
    }

    static String context(ClientLevel level, UUID world, long epoch) {
        StringBuilder value = new StringBuilder(installation).append('\n').append(level.dimension().location())
                .append('\n').append(level.dimensionTypeRegistration().unwrapKey().orElseThrow().location());
        ConfigTracker.INSTANCE.configSets().values().stream().flatMap(Set::stream)
                .sorted(Comparator.comparing(config -> config.getModId() + ":" + config.getFileName()))
                .forEach(config -> value.append('\n').append(config.getModId()).append(':')
                        .append(config.getFileName()).append('=')
                        .append(new com.google.gson.Gson().toJson(canonical(config.getConfigData()))));
        net.minecraft.world.flag.FeatureFlags.REGISTRY.toNames(level.enabledFeatures()).stream().sorted()
                .forEach(flag -> value.append('\n').append(flag));
        // Epoch zero can share material data between maps. Explicit resampling is map-scoped.
        if (epoch != 0) value.append('\n').append(world).append(':').append(epoch);
        return SampleCache.digest(value.toString());
    }

    private static Object canonical(Object value) {
        if (value instanceof UnmodifiableConfig config) return canonical(config.valueMap());
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(key.toString(), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ClientSampleCache::canonical).toList();
        return value;
    }
}
