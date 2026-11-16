package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.Blocks;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class FieldBoundariesTest {
    @Test void wireSnapshotsRetainTagAndFallbackWithoutReservingForeignCells() {
        var defaults = PaletteSpec.create(List.of(new PileModels.Candidate("a", "minecraft:stone", 1)), "test");
        var custom = PaletteSpec.create(defaults.candidates(), "test", "example:a", defaults);
        var leftDefinition = FieldDefinition.create(42, defaults);
        var rightDefinition = FieldDefinition.create(9, custom);
        SpatialMask mask = new SpatialMask(); mask.set(BlockPos.ZERO.east());
        FieldRegion left = new FieldRegion(new UUID(1, 2), 1, leftDefinition, Set.of(BlockPos.ZERO), new SpatialMask(), Map.of());
        FieldRegion right = new FieldRegion(new UUID(3, 4), 1, rightDefinition, Set.of(BlockPos.ZERO.east(2)), mask, Map.of());
        CompoundTag payload = new CompoundTag();
        payload.putInt("Format", 2); payload.putLong("Base", -1); payload.putLong("Revision", 1);
        ListTag regions = new ListTag(); regions.add(left.save()); regions.add(right.save()); payload.put("Regions", regions);
        ListTag palettes = new ListTag(); palettes.add(defaults.save()); palettes.add(custom.save()); payload.put("Palettes", palettes);
        FieldStore store = new FieldStore(); store.accept(payload);
        assertEquals(custom, store.palette(custom.id()));
        assertEquals(Set.of(BlockPos.ZERO.east(), BlockPos.ZERO.east(2)),
                store.regions().get(right.id()).baseline(), "Foreign damage metadata must remain intact");
        assertEquals(defaults, store.palette(leftDefinition.palette()));
        assertEquals("example:a", store.palette(rightDefinition.palette()).tag());
    }
    @Test void displayKeepsIntersectingProceduralBodiesFromDifferentTags() {
        var defaults = PaletteSpec.create(List.of(new PileModels.Candidate("a", "minecraft:stone", 1)), "test");
        var custom = PaletteSpec.create(defaults.candidates(), "test", "example:a", defaults);
        var leftDefinition = FieldDefinition.create(42, defaults);
        var rightDefinition = FieldDefinition.create(9, custom);
        BlockPos leftCell = BlockPos.ZERO, rightCell = leftCell.east();
        var model = new PileModel("cube", "minecraft:stone", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
        FieldObject first = new FieldObject(new FieldObject.Key("test", leftDefinition, "left",
                new GridPos(0, 0, 0), 2, 0), leftCell,
                PilePiece.create(model, .75f, .5f, .5f, 1, new Quaternionf()));
        FieldObject second = new FieldObject(new FieldObject.Key("test", rightDefinition, "right",
                new GridPos(1, 0, 0), 2, 0), rightCell,
                PilePiece.create(model, .25f, .5f, .5f, 1, new Quaternionf()));
        assertTrue(first.piece().colliders(0, 0, 0).get(0).intersects(second.piece().colliders(1, 0, 0).get(0), .005f));
        FieldRegion left = new FieldRegion(new UUID(1, 2), 1, leftDefinition, Set.of(leftCell), new SpatialMask(), Map.of());
        FieldRegion right = new FieldRegion(new UUID(3, 4), 1, rightDefinition, Set.of(rightCell), new SpatialMask(), Map.of());
        var memory = MemoryFixture.create(false);
        memory.context(Map.of(leftCell, Blocks.STONE.defaultBlockState(), rightCell, Blocks.STONE.defaultBlockState()), Map.of());
        TerrainShapes terrain = new TerrainShapes(memory, ignored -> true);
        FieldOverlay one = new FieldOverlay(left, 1, List.of(first), List.of(left, right));
        FieldOverlay two = new FieldOverlay(right, 1, List.of(second), List.of(left, right));
        assertTrue(one.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertTrue(two.advance(terrain, Integer.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(List.of(first), one.visible());
        assertEquals(List.of(second), two.visible());
        assertEquals(0, memory.chunkRequests());
    }
}
