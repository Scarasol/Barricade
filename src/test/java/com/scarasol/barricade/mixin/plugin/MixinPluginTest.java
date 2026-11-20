package com.scarasol.barricade.mixin.plugin;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MixinPluginTest {
    @Test void everyConfiguredCompatMixinIsGatedByItsOwnModId() throws Exception {
        var resource = getClass().getResourceAsStream("/barricade.mixins.json");
        try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            var config = JsonParser.parseReader(reader).getAsJsonObject();
            assertEquals(MixinPlugin.class.getName(), config.get("plugin").getAsString());
            String root = config.get("package").getAsString();
            var common = config.getAsJsonArray("mixins").asList().stream().map(JsonElement::getAsString)
                    .collect(Collectors.toSet());
            var client = config.getAsJsonArray("client").asList().stream().map(JsonElement::getAsString)
                    .collect(Collectors.toSet());
            assertTrue(common.containsAll(Set.of("ArrowLightsMixin", "ProjectileTraceMixin", "ProjectileImpactMixin",
                    "LightStructureMixin", "tacz.TaczLightsMixin", "superbwarfare.SuperbLightsMixin")));
            assertTrue(client.containsAll(Set.of("ClientBulbPickMixin", "ClientLightUpdateMixin", "tacz.CaptureBufferMixin",
                    "simplebedrockmodel.CaptureCullingMixin")));
            var entries = new HashSet<>(common);
            entries.addAll(client);
            assertEquals(config.getAsJsonArray("mixins").size() + config.getAsJsonArray("client").size(), entries.size());
            var modIds = Set.of("tacz", "superbwarfare", "bettercombat", "simplebedrockmodel");
            var installed = new HashSet<String>();
            var plugin = new MixinPlugin(installed::contains);
            plugin.onLoad(root);
            assertTrue(plugin.getMixins().isEmpty(), "Config-declared mixins must all run through shouldApplyMixin");
            for (String entry : entries) {
                String name = root + "." + entry;
                assertNotNull(getClass().getResource("/" + name.replace('.', '/') + ".class"), name);
                int separator = entry.indexOf('.');
                String modId = separator < 0 ? null : entry.substring(0, separator);
                if (modId != null) assertTrue(modIds.contains(modId), name);
                assertEquals(modId == null, plugin.shouldApplyMixin("unused", name), name);
                for (String enabled : modIds) {
                    installed.add(enabled);
                    assertEquals(modId == null || enabled.equals(modId), plugin.shouldApplyMixin("unused", name), name);
                    installed.clear();
                }
            }
        }
    }
}
