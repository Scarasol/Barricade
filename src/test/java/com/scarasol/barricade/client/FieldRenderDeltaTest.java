package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class FieldRenderDeltaTest {
    @Test void expansionAndCrossQueryDeduplicationRetainExistingRenderEntries() {
        var palette = PaletteSpec.create(List.of(new PileModels.Candidate("a", "minecraft:stone", 1)), "test");
        var definition = FieldDefinition.create(42, palette);
        PileModel model = new PileModel("cube", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
        FieldObject first = new FieldObject(new FieldObject.Key("test", definition, "g", new GridPos(0, 0, 0), 2, 0),
                BlockPos.ZERO, PilePiece.create(model, .5f, .5f, .5f, 1, new Quaternionf()));
        FieldObject second = new FieldObject(new FieldObject.Key("test", definition, "g", new GridPos(1, 0, 0), 2, 0),
                BlockPos.ZERO.east(), first.piece());
        UUID a = new UUID(1, 2), b = new UUID(3, 4);
        BarricadeRenderer.clear();
        long created = BarricadeRenderer.counters().created();
        BarricadeRenderer.replace(a, List.of(first));
        BarricadeRenderer.replace(a, List.of(first, second));
        BarricadeRenderer.replace(b, List.of(first));
        assertEquals(2, BarricadeRenderer.counters().created() - created);
        assertEquals(2, BarricadeRenderer.counters().objects());
        BarricadeRenderer.remove(a);
        assertEquals(1, BarricadeRenderer.counters().objects());
        BarricadeRenderer.remove(b);
        assertEquals(0, BarricadeRenderer.counters().objects());
    }
}
