package com.scarasol.barricade.block;

import com.scarasol.barricade.pile.PileData;
import com.scarasol.barricade.pile.PileService;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class BarricadeBlock extends Block {
    static final VoxelShape COLLISION_SHAPE = Block.box(0, 0, 0, 16, 24, 16);

    public BarricadeBlock() {
        super(Properties.of().mapColor(MapColor.WOOD).strength(2.5f, 8.0f).sound(SoundType.WOOD)
                .pushReaction(PushReaction.BLOCK).ignitedByLava()
                .noOcclusion().isSuffocating((state, level, pos) -> false)
                .isViewBlocking((state, level, pos) -> false));
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        return 10;
    }

    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction face) {
        return 5;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return COLLISION_SHAPE;
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        // Like fences, only entity collision extends above the one-block outline.
        return getShape(state, level, pos, context);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    public int getLightBlock(BlockState state, BlockGetter level, BlockPos pos) {
        return 0;
    }

    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moving) {
        super.onPlace(state, level, pos, previous, moving);
        if (previous.is(this) || !(level instanceof ServerLevel server)
                || level.restoringBlockSnapshots || PileService.trackingSuspended()) return;
        PileData data = PileData.get(server);
        if (data.embeddedAt(pos) != null) data.removeEmbedded(pos);
        else data.add(pos, UUID.randomUUID(), level.random.nextLong(), PileService.placedPalette(level, pos));
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!replacement.is(this) && level instanceof ServerLevel) PileService.clearPlacement(level, pos);
        if (!replacement.is(this) && level instanceof ServerLevel server
                && !level.captureBlockSnapshots && !level.restoringBlockSnapshots
                && !PileService.trackingSuspended()) removed(server, pos, state);
        super.onRemove(state, level, pos, replacement, moving);
    }

    /** Also completes removals whose onRemove callback ran during Forge's uncommitted capture. */
    public void removed(ServerLevel level, BlockPos pos, BlockState previous) {
        PileData data = PileData.get(level);
        if (!data.contains(pos) || data.embeddedAt(pos) != null) return;
        data.remove(pos);
    }
}
