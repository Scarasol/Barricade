package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;
import com.scarasol.barricade.pile.*;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.MemoryFixture;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class RenderIndexTest {
    @Test void objectOverhangIsVisibleEvenWhenOwnerSectionIsOutsideSearch() {
        RenderIndex<String> index = new RenderIndex<>();
        index.put("overhang", new AABB(15, 0, 0, 18, 2, 2));
        index.put("remote", new AABB(1000, 0, 0, 1001, 1, 1));
        assertEquals(Set.of("overhang"), index.near(new Vec3(17, 1, 1), 0.5));
        index.remove("overhang");
        assertTrue(index.near(new Vec3(17, 1, 1), 0.5).isEmpty());
    }
    @Test void transformedVisualBoundsIncludeEveryRotatedCornerFarFromOrigin() {
        PileModel model = new PileModel("cube", "minecraft:oak_planks", 1, 1, 1, 1, List.of(CollisionBox.CUBE));
        PilePiece piece = PilePiece.create(model, 0.6f, 0.5f, 0.4f, 1.05f,
                new Quaternionf().rotationYXZ(0.7f, 0.2f, 0.4f));
        BlockPos owner = new BlockPos(1_000_000, 80, -1_000_000);
        var object = new ArchivedPileReplay.ObjectPiece(new ArchivedPileReplay.Key(1, 0), owner, piece);
        AABB local = new AABB(-1, 0, -0.5, 2, 2.5, 1);
        AABB bounds = BarricadeRenderer.transformed(object.owner(), object.piece(), local);
        for (int corner = 0; corner < 8; corner++) {
            var point = piece.transformPoint((float) ((corner & 1) == 0 ? local.minX : local.maxX),
                    (float) ((corner & 2) == 0 ? local.minY : local.maxY),
                    (float) ((corner & 4) == 0 ? local.minZ : local.maxZ));
            assertTrue(bounds.inflate(0.0001).contains(owner.getX() + (double) point.x,
                    owner.getY() + (double) point.y, owner.getZ() + (double) point.z));
        }
    }
    @Test void historicalTerrainUsesRecordedStateAndDoesNotRequestRealChunks() {
        var memory = MemoryFixture.create(false);
        BlockPos target = new BlockPos(100_000, 80, -250_000);
        BlockPos below = target.below();
        ArchivedTerrainSnapshot context = new ArchivedTerrainSnapshot(Map.of(below,
                new WorldBlock("minecraft:oak_slab[type=bottom,waterlogged=false]", new CompoundTag())), Set.of(target.east()));
        var node = new ArchivedPileRecipe(1, 42, List.of(), Set.of(target), Set.of(target), Set.of(), "p",
                context, List.of(new ArchivedPileRecipe.Damage(below, false, "minecraft:oak_slab", context)));
        var prepared = new ArchivedTerrainShapes(memory).prepare(node, List.of());
        assertEquals(1, prepared.obstacles().size());
        assertEquals(-0.5f, prepared.obstacles().get(0).maxY());
        assertEquals(1, prepared.unknown().size());
        assertEquals(0.5f, prepared.damage().get(0).boxes().get(0).maxY());
        assertEquals(0, memory.chunkRequests());
        assertTrue(memory.writtenPositions().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> memory.setBlock(target,
                net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 0, 0));
    }
}
