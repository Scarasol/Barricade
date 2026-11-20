package com.scarasol.barricade.stringlights;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class PowerBoxBlock extends BaseEntityBlock {
    public static final DirectionProperty FACE = BlockStateProperties.FACING;
    public static final BooleanProperty ON = BlockStateProperties.POWERED;

    PowerBoxBlock() {
        super(Properties.of().strength(0.8f, 3).sound(SoundType.METAL).noOcclusion().pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any().setValue(FACE, Direction.NORTH).setValue(ON, false));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, ON);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACE, context.getClickedFace());
    }

    @Override public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACE, rotation.rotate(state.getValue(FACE)));
    }

    @Override public BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACE, mirror.mirror(state.getValue(FACE)));
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new PowerBoxEntity(pos, state); }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACE)) {
            case NORTH -> box(3, 3, 10, 13, 13, 16);
            case SOUTH -> box(3, 3, 0, 13, 13, 6);
            case EAST -> box(0, 3, 3, 6, 13, 13);
            case WEST -> box(10, 3, 3, 16, 13, 13);
            case UP -> box(3, 0, 3, 13, 6, 13);
            case DOWN -> box(3, 10, 3, 13, 16, 13);
        };
    }

    static Vec3 outlet(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(face.getNormal()).scale(-3.0 / 32));
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                            InteractionHand hand, BlockHitResult hit) {
        if (player.isSecondaryUseActive() || player.getItemInHand(hand).is(StringLights.WIRE.get())) {
            return InteractionResult.PASS;
        }
        if (!player.getAbilities().mayBuild) return InteractionResult.PASS;
        if (level instanceof ServerLevel server) {
            boolean on = !state.getValue(ON);
            level.setBlock(pos, state.setValue(ON, on), Block.UPDATE_CLIENTS);
            LightWorld.get(server).boxSwitch(pos, on);
            level.playSound(null, pos, SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.35f, on ? 0.65f : 0.55f);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof PowerBoxEntity box) {
            LightWorld.get(server).placed(box, null);
        }
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!replacement.is(this) && level instanceof ServerLevel server) LightWorld.get(server).boxRemoved(pos);
        super.onRemove(state, level, pos, replacement, moving);
    }
}
