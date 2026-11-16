package com.scarasol.barricade.client;

import com.scarasol.barricade.Barricade;
import com.scarasol.barricade.pile.OrientedBox;
import com.scarasol.barricade.recipe.*;
import com.scarasol.barricade.sampling.TestMemoryLevel;
import java.util.*;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

final class ArchivedTerrainShapes {
    private final TestMemoryLevel level;
    ArchivedTerrainShapes(TestMemoryLevel level) { this.level = level; }
    private void context(ArchivedTerrainSnapshot snapshot) {
        Map<BlockPos, BlockState> states = new HashMap<>();
        Map<BlockPos, CompoundTag> data = new HashMap<>();
        snapshot.blocks().forEach((pos, block) -> {
            states.put(pos, parse(block.state()));
            data.put(pos, block.data());
        });
        snapshot.unknown().forEach(pos -> states.put(pos, Blocks.BARRIER.defaultBlockState()));
        level.context(states, data);
    }
    static BlockState parse(String state) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), state, false).blockState();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            throw new IllegalArgumentException("Missing historical block " + state, exception);
        }
    }
    ArchivedPileReplay.Prepared prepare(ArchivedPileRecipe node, List<com.scarasol.barricade.pile.PileModel> models) {
        List<OrientedBox> obstacles = new ArrayList<>(), unknown = new ArrayList<>();
        List<ArchivedPileReplay.DamageShape> damage = new ArrayList<>();
        BlockPos origin = ArchivedPileReplay.origin(node.domain());
        try {
            if (!node.active().isEmpty()) {
                context(node.terrain());
                for (BlockPos pos : node.terrain().blocks().keySet().stream().sorted().toList()) {
                    WorldBlock block = node.terrain().blocks().get(pos);
                    if (block.embedded() != null) {
                        obstacles.addAll(embeddedBoxes(block.embedded(), origin));
                    } else if (!node.domain().contains(pos)
                            && !(level.getBlockState(pos).getBlock() instanceof com.scarasol.barricade.block.BarricadeBlock)) {
                        obstacles.addAll(boxes(pos, origin));
                    }
                }
                for (BlockPos pos : node.terrain().unknown().stream().sorted().toList())
                    unknown.add(cube(pos, origin));
            }
            for (ArchivedPileRecipe.Damage edit : node.damage()) {
                List<OrientedBox> boxes;
                boolean embedded = false;
                if (edit.fullCell()) boxes = List.of(cube(edit.pos(), edit.pos()));
                else {
                    context(edit.context());
                    WorldBlock block = edit.context().blocks().get(edit.pos());
                    embedded = block != null && block.embedded() != null;
                    boxes = embedded ? embeddedBoxes(block.embedded(), edit.pos()) : boxes(edit.pos(), edit.pos());
                }
                damage.add(new ArchivedPileReplay.DamageShape(edit.pos(), boxes, embedded));
            }
            return new ArchivedPileReplay.Prepared(node, models, obstacles, unknown, damage);
        } finally {
            level.reset();
        }
    }
    private List<OrientedBox> embeddedBoxes(EmbeddedBlock block, BlockPos origin) {
        var piece = block.transform(block.model(level));
        return piece.colliders(block.pos().getX() - origin.getX(), block.pos().getY() - origin.getY(),
                block.pos().getZ() - origin.getZ());
    }
    private List<OrientedBox> boxes(BlockPos pos, BlockPos origin) {
        try {
            List<OrientedBox> result = new ArrayList<>();
            for (AABB box : level.getBlockState(pos).getCollisionShape(level, pos).toAabbs()) {
                int dx = pos.getX() - origin.getX(), dy = pos.getY() - origin.getY(), dz = pos.getZ() - origin.getZ();
                result.add(OrientedBox.axisAligned((float) box.minX + dx, (float) box.minY + dy,
                        (float) box.minZ + dz, (float) box.maxX + dx, (float) box.maxY + dy, (float) box.maxZ + dz));
            }
            return result;
        } catch (RuntimeException | LinkageError exception) {
            Barricade.LOGGER.warn("Unsupported historical collision at {}: {}; using cell boundary", pos, exception.toString());
            return List.of(cube(pos, origin));
        }
    }
    private static OrientedBox cube(BlockPos pos, BlockPos origin) {
        int x = pos.getX() - origin.getX(), y = pos.getY() - origin.getY(), z = pos.getZ() - origin.getZ();
        return OrientedBox.axisAligned(x, y, z, x + 1, y + 1, z + 1);
    }
}
