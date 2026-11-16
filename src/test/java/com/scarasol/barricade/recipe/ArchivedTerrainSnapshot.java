package com.scarasol.barricade.recipe;

import com.scarasol.barricade.pile.ArchivedPileData;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

/** Historical block inputs, without evaluating their collision shapes on the server. */
public record ArchivedTerrainSnapshot(Map<BlockPos, WorldBlock> blocks, Set<BlockPos> unknown) {
    public static final ArchivedTerrainSnapshot EMPTY = new ArchivedTerrainSnapshot(Map.of(), Set.of());

    public ArchivedTerrainSnapshot {
        blocks = Map.copyOf(blocks);
        unknown = Set.copyOf(unknown);
    }

    public static ArchivedTerrainSnapshot capture(ServerLevel level, Set<BlockPos> targets) {
        return capture(level, targets, Map.of());
    }

    public static ArchivedTerrainSnapshot capture(ServerLevel level, Set<BlockPos> targets,
                                         Map<BlockPos, WorldBlock> previous) {
        Map<BlockPos, WorldBlock> blocks = new HashMap<>();
        Set<BlockPos> unknown = new HashSet<>();
        Set<BlockPos> context = new HashSet<>();
        targets.forEach(pos -> BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))
                .forEach(near -> context.add(near.immutable())));
        for (BlockPos pos : context) {
            if (!level.hasChunkAt(pos)) {
                unknown.add(pos);
                continue;
            }
            WorldBlock before = previous.get(pos);
            if (before != null) {
                blocks.put(pos, before);
                continue;
            }
            var state = level.getBlockState(pos);
            if (state.isAir()) continue;
            var entity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            CompoundTag data = entity == null ? new CompoundTag() : entity.saveWithoutMetadata();
            if (data.sizeInBytes() > 65_536) {
                unknown.add(pos);
                com.scarasol.barricade.Barricade.LOGGER.warn(
                        "Barricade terrain input at {} exceeds NBT budget; treating as unknown", pos);
                continue;
            }
            blocks.put(pos, new WorldBlock(BlockStateParser.serialize(state), data, ArchivedPileData.get(level).embeddedAt(pos)));
        }
        return new ArchivedTerrainSnapshot(blocks, unknown);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag entries = new ListTag();
        blocks.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag value = entry.getValue().save();
            value.putLong("Pos", entry.getKey().asLong());
            entries.add(value);
        });
        tag.put("Blocks", entries);
        RecipeCodec.positions(tag, "Unknown", unknown);
        return tag;
    }

    public static ArchivedTerrainSnapshot load(CompoundTag tag) {
        Map<BlockPos, WorldBlock> blocks = new HashMap<>();
        for (Tag entry : tag.getList("Blocks", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) entry;
            blocks.put(BlockPos.of(value.getLong("Pos")), WorldBlock.load(value));
        }
        return new ArchivedTerrainSnapshot(blocks, RecipeCodec.positions(tag, "Unknown"));
    }
}
