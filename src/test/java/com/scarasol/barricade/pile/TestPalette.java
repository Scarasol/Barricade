package com.scarasol.barricade.pile;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Fixed geometry for packing regressions without bootstrapping MC. */
final class TestPalette {
    private static final List<PileModel> MODELS = load();
    static List<PileModel> models() { return MODELS; }

    private static List<PileModel> load() {
        try (var reader = new InputStreamReader(TestPalette.class.getResourceAsStream("/legacy-palette.json"),
                StandardCharsets.UTF_8)) {
            return PileModels.parse(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
