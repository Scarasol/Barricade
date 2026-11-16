package com.scarasol.barricade.window;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class WindowBarsAssetsTest {
    private JsonObject json(String path) throws Exception {
        try (var input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, path);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test void spritesAreOpaqueSixteenPixelImagesAndAreReferencedForAtlasStitching() throws Exception {
        var textures = json("/assets/barricade/models/block/window_bars.json").getAsJsonObject("textures");
        for (String name : new String[]{"window_bars", "window_bars_weld"}) {
            assertTrue(textures.asMap().values().stream().anyMatch(value -> value.getAsString().equals("barricade:block/" + name)));
            try (var input = getClass().getResourceAsStream("/assets/barricade/textures/block/" + name + ".png")) {
                assertNotNull(input);
                var image = ImageIO.read(input);
                assertEquals(16, image.getWidth());
                assertEquals(16, image.getHeight());
                for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++)
                    assertEquals(255, image.getRGB(x, y) >>> 24);
            }
        }
    }
    @Test void inventoryModelUsesIntegerBoxesAndAllGameplayAssetsUseTheNewBlockId() throws Exception {
        for (var element : json("/assets/barricade/models/block/window_bars.json").getAsJsonArray("elements")) {
            var from = element.getAsJsonObject().getAsJsonArray("from");
            var to = element.getAsJsonObject().getAsJsonArray("to");
            for (int axis = 0; axis < 3; axis++) {
                double a = from.get(axis).getAsDouble(), b = to.get(axis).getAsDouble();
                assertEquals(Math.rint(a), a);
                assertEquals(Math.rint(b), b);
                assertTrue(b - a >= 1);
            }
        }
        assertEquals("barricade:block/window_bars", json("/assets/barricade/blockstates/window_bars.json")
                .getAsJsonObject("variants").getAsJsonObject("").get("model").getAsString());
        assertEquals("barricade:block/window_bars", json("/assets/barricade/models/item/window_bars.json").get("parent").getAsString());
        assertEquals("barricade:window_bars", json("/data/barricade/recipes/window_bars.json")
                .getAsJsonObject("result").get("item").getAsString());
        assertEquals("barricade:window_bars", json("/data/barricade/loot_tables/blocks/window_bars.json")
                .getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0)
                .getAsJsonObject().get("name").getAsString());
        assertEquals("barricade:window_bars", json("/data/minecraft/tags/blocks/mineable/pickaxe.json")
                .getAsJsonArray("values").get(0).getAsString());
        assertEquals("Window Bars", json("/assets/barricade/lang/en_us.json").get("block.barricade.window_bars").getAsString());
        assertEquals("\u5c01\u7a97\u94c1\u6761", json("/assets/barricade/lang/zh_cn.json").get("block.barricade.window_bars").getAsString());
    }
}
