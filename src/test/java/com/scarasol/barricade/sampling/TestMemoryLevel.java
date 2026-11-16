package com.scarasol.barricade.sampling;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;

/** Read-only terrain fixtures at arbitrary coordinates, never included in production. */
public final class TestMemoryLevel extends MemoryLevel {
    private final Map<BlockPos, BlockState> contextStates = new LinkedHashMap<>();
    private final Map<BlockPos, BlockEntity> contextEntities = new LinkedHashMap<>();
    private boolean contextMode;

    TestMemoryLevel(RegistryAccess registries, Holder<DimensionType> dimension,
                    FeatureFlagSet features, RecipeManager recipes, boolean client) {
        super(registries, dimension, features, recipes, client);
    }
    @Override public void reset() {
        contextEntities.values().forEach(BlockEntity::setRemoved);
        contextEntities.clear(); contextStates.clear(); contextMode = false;
        super.reset();
    }
    public void context(Map<BlockPos, BlockState> blocks, Map<BlockPos, CompoundTag> data) {
        reset(); contextMode = true; contextStates.putAll(blocks);
        blocks.forEach((pos, state) -> {
            if (state.getBlock() instanceof EntityBlock source) {
                BlockEntity entity = source.newBlockEntity(pos, state);
                if (entity != null) {
                    setBlockEntity(entity);
                    CompoundTag tag = data.get(pos);
                    if (tag != null && !tag.isEmpty()) entity.load(tag.copy());
                }
            }
        });
    }
    @Override public BlockState getBlockState(BlockPos pos) {
        return contextMode ? contextStates.getOrDefault(pos, Blocks.AIR.defaultBlockState()) : super.getBlockState(pos);
    }
    @Override public BlockEntity getBlockEntity(BlockPos pos) {
        return contextMode ? contextEntities.get(pos) : super.getBlockEntity(pos);
    }
    @Override public void setBlockEntity(BlockEntity entity) {
        if (!contextMode) { super.setBlockEntity(entity); return; }
        entity.setLevel(this); contextEntities.put(entity.getBlockPos().immutable(), entity);
    }
    @Override public void removeBlockEntity(BlockPos pos) {
        if (!contextMode) { super.removeBlockEntity(pos); return; }
        BlockEntity entity = contextEntities.remove(pos);
        if (entity != null) entity.setRemoved();
    }
    @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) {
        if (contextMode) throw new UnsupportedOperationException("Test terrain is read-only");
        return super.setBlock(pos, state, flags, recursion);
    }
    @Override public boolean hasChunk(int x, int z) { return contextMode || super.hasChunk(x, z); }
}
