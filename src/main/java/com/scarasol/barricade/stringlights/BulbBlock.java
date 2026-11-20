package com.scarasol.barricade.stringlights;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

final class BulbBlock extends Block implements SimpleWaterloggedBlock {
    static final BooleanProperty LIT = BlockStateProperties.LIT;
    static final BooleanProperty WATER = BlockStateProperties.WATERLOGGED;

    BulbBlock() {
        super(Properties.of().noCollission().noOcclusion().dynamicShape().strength(0.15f)
                .sound(SoundType.GLASS).noLootTable().pushReaction(PushReaction.DESTROY)
                .lightLevel(state -> state.getValue(LIT) ? 15 : 0));
        registerDefaultState(stateDefinition.any().setValue(LIT, false).setValue(WATER, false));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT, WATER);
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override public boolean canBeReplaced(BlockState state, BlockPlaceContext context) { return true; }
    @Override public boolean canBeReplaced(BlockState state, Fluid fluid) { return false; }
    @Override public FluidState getFluidState(BlockState state) {
        return state.getValue(WATER) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override public BlockState updateShape(BlockState state, net.minecraft.core.Direction side, BlockState adjacent,
                                           LevelAccessor level, BlockPos pos, BlockPos other) {
        if (state.getValue(WATER)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        return state;
    }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return LightTargets.bulbShape(level, pos);
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!replacement.is(this) && level instanceof ServerLevel server) LightWorld.get(server).bulbRemoved(pos);
        super.onRemove(state, level, pos, replacement, moving);
    }
}
