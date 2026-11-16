package com.scarasol.barricade.pile;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.recipe.*;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.BlockPos;

public final class TaggedFieldChecks {
    private TaggedFieldChecks() { }
    public static void verify(Function<FieldBatch, int[]> solve) {
        var defaults = PaletteSpec.create(List.of(new PileModels.Candidate("a", "minecraft:stone", 1)), "test");
        var a = PaletteSpec.create(defaults.candidates(), "test", "example:a", defaults);
        var b = PaletteSpec.create(defaults.candidates(), "test", "example:b", defaults);
        List<PileModel> models = List.of(new PileModel("cube", "minecraft:stone",
                1, 1, 1, 1, List.of(CollisionBox.CUBE)));
        List<BlockPos> offsets = new ArrayList<>();
        BlockPos.betweenClosed(-1, -1, -1, 1, 1, 1).forEach(pos -> {
            if (!pos.equals(BlockPos.ZERO)) offsets.add(pos.immutable());
        });
        int crossings = 0, overlaps = 0;
        for (BlockPos origin : List.of(new BlockPos(-17, -1, 31), new BlockPos(-3, 8, 5), BlockPos.ZERO)) {
            for (BlockPos offset : offsets) {
                BlockPos other = origin.offset(offset);
                Set<BlockPos> left = Set.of(origin), right = Set.of(other);
                var leftDefinition = FieldDefinition.create(7501765099988357541L, a);
                var rightDefinition = FieldDefinition.create(42, b);
                SpatialField first = new SpatialField("test", leftDefinition, models, solve);
                var original = first.query(left);
                long jobs = first.metrics().jobs();
                var adjacent = first.query(left, left, right);
                var second = new SpatialField("test", rightDefinition, models, solve).query(right, right, left);
                if (Math.abs(offset.getX()) + Math.abs(offset.getY()) + Math.abs(offset.getZ()) == 1) {
                    assertFalse(adjacent.isEmpty(), "Adjacent tags must leave usable full-size poses: " + origin + "/" + offset);
                    assertFalse(second.isEmpty(), "Opposite tag lost its full-size pose: " + origin + "/" + offset);
                }
                assertEquals(original, adjacent, "Foreign cells must not affect geometry or object identity");
                assertEquals(jobs, first.metrics().jobs(), "Foreign cells must not invalidate cached layouts");
                crossings += (int) adjacent.stream().filter(object -> crosses(object, other)).count();
                crossings += (int) second.stream().filter(object -> crosses(object, origin)).count();
                for (FieldObject one : adjacent) for (FieldObject two : second)
                    if (intersects(one, two)) overlaps++;
                assertEquals(adjacent, new SpatialField("test", leftDefinition, models, solve).query(left, left, right));
                assertEquals(second, new SpatialField("test", rightDefinition, models, solve).query(right, right, left));
                assertEquals(second, new SpatialField("test", rightDefinition, models, solve).query(right));
                assertEquals(original, first.query(left), "Removing a foreign tag must not change the field");
            }
        }
        assertTrue(crossings > 0, "Procedural bodies must be allowed to extend into foreign logical cells");
        assertTrue(overlaps > 0, "Actual procedural bodies from different tags must be allowed to interpenetrate");
        System.out.printf("TAG OVERLAP pairs=%d logical_crossings=%d intersecting_object_pairs=%d%n",
                offsets.size() * 3, crossings, overlaps);
    }
    private static boolean crosses(FieldObject object, BlockPos cell) {
        return FieldVisibility.hit(object, new FieldVisibility.Shape(cell,
                List.of(OrientedBox.axisAligned(0, 0, 0, 1, 1, 1)), true));
    }
    private static boolean intersects(FieldObject one, FieldObject two) {
        var first = one.piece().colliders(0, 0, 0);
        var second = two.piece().colliders(two.owner().getX() - one.owner().getX(),
                two.owner().getY() - one.owner().getY(), two.owner().getZ() - one.owner().getZ());
        return first.stream().anyMatch(box -> second.stream().anyMatch(other -> box.intersects(other, .005f)));
    }
}
