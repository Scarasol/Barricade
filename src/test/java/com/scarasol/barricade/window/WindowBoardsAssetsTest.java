package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class WindowBoardsAssetsTest {
    @Test void recipeUsesThreePlanksAndTwoIronNuggetsForSixBoards() throws Exception {
        try (var input = getClass().getResourceAsStream("/data/barricade/recipes/window_boards.json")) {
            assertNotNull(input);
            var recipe = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
            var pattern = recipe.getAsJsonArray("pattern").asList().stream()
                    .map(value -> value.getAsString()).toList();
            assertEquals(List.of("PPP", "N N"), pattern);
            String slots = String.join("", pattern);
            assertEquals(3L, slots.chars().filter(slot -> slot == 'P').count());
            assertEquals(2L, slots.chars().filter(slot -> slot == 'N').count());
            var key = recipe.getAsJsonObject("key");
            assertEquals("minecraft:planks", key.getAsJsonObject("P").get("tag").getAsString());
            assertEquals("minecraft:iron_nugget", key.getAsJsonObject("N").get("item").getAsString());
            var result = recipe.getAsJsonObject("result");
            assertEquals("barricade:window_boards", result.get("item").getAsString());
            assertEquals(6, result.get("count").getAsInt());
        }
    }
}
