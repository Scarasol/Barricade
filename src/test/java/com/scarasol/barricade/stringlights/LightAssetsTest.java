package com.scarasol.barricade.stringlights;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class LightAssetsTest {
    @Test void shapedRecipesMatchTheApprovedMaterialsAndOutputs() throws Exception {
        var wire = recipe("wire");
        var box = recipe("box");
        assertEquals(Set.of("C", "G", "N", "R"), wire.getAsJsonObject("key").keySet());
        assertEquals("minecraft:copper_ingot", ingredient(wire, "C"));
        assertEquals("minecraft:glass", ingredient(wire, "G"));
        assertEquals("minecraft:iron_nugget", ingredient(wire, "N"));
        assertEquals("minecraft:redstone", ingredient(wire, "R"));
        assertEquals("CGC|NRN| G ", pattern(wire));
        assertEquals(4, wire.getAsJsonObject("result").get("count").getAsInt());
        assertEquals("barricade:string_light_wire", wire.getAsJsonObject("result").get("item").getAsString());
        assertEquals("ICI|IRI| L ", pattern(box));
        assertEquals("minecraft:lever", ingredient(box, "L"));
        assertEquals("minecraft:iron_ingot", ingredient(box, "I"));
        assertEquals("minecraft:copper_ingot", ingredient(box, "C"));
        assertEquals("minecraft:redstone", ingredient(box, "R"));
        assertEquals("barricade:string_light_box", box.getAsJsonObject("result").get("item").getAsString());
    }

    @Test void texturesAreSixteenPixelAndAllSixBoxFacingsHaveBothStates() throws Exception {
        String prefix = "/assets/barricade/";
        for (String texture : new String[] {"block/string_light_box", "block/string_light_box_on",
                "block/string_light_palette", "item/string_light_wire"}) {
            try (var stream = getClass().getResourceAsStream(prefix + "textures/" + texture + ".png")) {
                assertNotNull(stream);
                var image = ImageIO.read(stream);
                assertEquals(16, image.getWidth());
                assertEquals(16, image.getHeight());
            }
        }
        var variants = json(prefix + "blockstates/string_light_box.json").getAsJsonObject("variants");
        assertEquals(12, variants.size());
        for (var face : net.minecraft.core.Direction.values()) {
            assertTrue(variants.has("facing=" + face.getSerializedName() + ",powered=false"));
            assertTrue(variants.has("facing=" + face.getSerializedName() + ",powered=true"));
        }
    }

    private JsonObject recipe(String name) throws Exception {
        var recipe = json("/data/barricade/recipes/string_light_" + name + ".json");
        assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
        return recipe;
    }

    private JsonObject json(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream(name)) {
            assertNotNull(stream);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static String ingredient(JsonObject recipe, String slot) {
        return recipe.getAsJsonObject("key").getAsJsonObject(slot).get("item").getAsString();
    }

    private static String pattern(JsonObject recipe) {
        return recipe.getAsJsonArray("pattern").asList().stream().map(com.google.gson.JsonElement::getAsString)
                .collect(java.util.stream.Collectors.joining("|"));
    }
}
