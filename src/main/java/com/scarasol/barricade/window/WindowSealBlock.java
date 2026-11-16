package com.scarasol.barricade.window;

import com.scarasol.barricade.Barricade;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public abstract class WindowSealBlock extends DirectionalBlock {
    public static final TagKey<EntityType<?>> PASS_THROUGH_ENTITY_TYPES = TagKey.create(Registries.ENTITY_TYPE,
            new ResourceLocation(Barricade.MOD_ID, "window_boards_pass_through"));
    public static final TagKey<Item> ATTACK_THROUGH_ITEMS = TagKey.create(Registries.ITEM,
            new ResourceLocation(Barricade.MOD_ID, "window_boards_attack_through"));

    private final SealMaterial material;

    protected WindowSealBlock(SealMaterial material, Properties properties) {
        super(properties.noOcclusion().pushReaction(PushReaction.BLOCK)
                .isSuffocating((state, level, pos) -> false).isViewBlocking((state, level, pos) -> false));
        this.material = material;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }
    public final SealMaterial material() { return material; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos behind = context.getClickedPos().relative(context.getClickedFace().getOpposite());
        BlockState support = context.getLevel().getBlockState(behind);
        Direction facing = support.getBlock() instanceof WindowSealBlock ? support.getValue(FACING) : context.getClickedFace();
        return defaultBlockState().setValue(FACING, facing);
    }
    @Override public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }
    @Override public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.INVISIBLE; }
    @Override public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) { return true; }
    @Override public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) { return 0; }
    @Override public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (context instanceof EntityCollisionContext entityContext) {
            Entity entity = entityContext.getEntity();
            if (entity != null && entity.getType().is(PASS_THROUGH_ENTITY_TYPES)) return Shapes.empty();
        }
        return getShape(state, level, pos, context);
    }
    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case NORTH -> box(0, 0, 10, 16, 16, 16);
            case SOUTH -> box(0, 0, 0, 16, 16, 6);
            case WEST -> box(10, 0, 0, 16, 16, 16);
            case EAST -> box(0, 0, 0, 6, 16, 16);
            case DOWN -> box(0, 10, 0, 16, 16, 16);
            case UP -> box(0, 0, 0, 16, 6, 16);
        };
    }
    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (level instanceof ServerLevel server) WindowBoardsService.changed(server, pos);
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (level instanceof ServerLevel server && (!next.is(this) || state.getValue(FACING) != next.getValue(FACING)))
            WindowBoardsService.removed(server, pos);
        super.onRemove(state, level, pos, next, moving);
    }
}
