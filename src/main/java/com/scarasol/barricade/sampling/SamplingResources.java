package com.scarasol.barricade.sampling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/** A tag/weight edit changes selection; other server data can change placement itself. */
public final class SamplingResources extends SimplePreparableReloadListener<String> {
    private static volatile String signature = "";

    public static String signature() { return signature; }

    @Override protected String prepare(ResourceManager resources, ProfilerFiller profiler) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (var entry : resources.listResources("", id -> !(id.getNamespace().equals("barricade")
                    && id.getPath().equals("tags/blocks/materials.json"))
                    && !id.getPath().startsWith("barricade_models/")).entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                digest.update(entry.getKey().toString().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (var stream = entry.getValue().open()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = stream.read(buffer)) >= 0) digest.update(buffer, 0, count);
                }
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not fingerprint placement resources", exception);
        }
    }

    @Override protected void apply(String prepared, ResourceManager manager, ProfilerFiller profiler) {
        signature = prepared;
    }
}
