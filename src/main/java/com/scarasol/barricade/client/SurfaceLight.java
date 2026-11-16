package com.scarasol.barricade.client;

import com.scarasol.barricade.pile.PilePiece;
import java.util.function.Predicate;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

final class SurfaceLight {
    private static final float EPSILON = 0.001f;

    private SurfaceLight() { }

    static boolean directional(BlockAndTintGetter level, BlockPos pos, int light) {
        return (light == 0 || BarricadeRenderer.suppressOriginal(pos))
                && level.getBlockState(pos).isSolidRender(level, pos);
    }

    static int sample(Level level, BlockPos owner, PilePiece piece, StaticMesh.Batch batch) {
        return sample(level, level::hasChunkAt, owner, piece, batch);
    }

    static int sample(BlockAndTintGetter level, Predicate<BlockPos> loaded, BlockPos owner, PilePiece piece,
                      StaticMesh.Batch batch) {
        BlockPos pos = position(owner, piece, batch.lightPosition, batch.lightNormal,
                cell -> loaded.test(cell) && level.getBlockState(cell).isSolidRender(level, cell));
        return loaded.test(pos) ? LevelRenderer.getLightColor(level, pos) : 0;
    }

    static BlockPos position(BlockPos owner, PilePiece piece, Vector3f local, Vector3f localNormal, boolean solid) {
        return position(owner, piece, local, localNormal, cell -> solid && cell.equals(owner));
    }

    static BlockPos position(BlockPos owner, PilePiece piece, Vector3f local, Vector3f localNormal,
                             Predicate<BlockPos> solid) {
        Vector3f point = piece.transformPoint(local.x, local.y, local.z);
        Vector3f normal = new Vector3f(localNormal).div(
                piece.model().sizeX(), piece.model().sizeY(), piece.model().sizeZ());
        piece.rotation().transform(normal);
        if (normal.lengthSquared() > 0.000001f) {
            normal.normalize();
            point.fma(EPSILON, normal);
            BlockPos cell = BlockPos.containing(owner.getX() + (double) point.x,
                    owner.getY() + (double) point.y, owner.getZ() + (double) point.z);
            // A fixed face can overlap newly placed terrain as well as a hidden embedded cell.
            if (solid.test(cell)) {
                float exit = Float.POSITIVE_INFINITY;
                for (int axis = 0; axis < 3; axis++) {
                    float direction = normal.get(axis);
                    float min = switch (axis) {
                        case 0 -> cell.getX() - owner.getX();
                        case 1 -> cell.getY() - owner.getY();
                        default -> cell.getZ() - owner.getZ();
                    };
                    if (Math.abs(direction) > 0.000001f)
                        exit = Math.min(exit, (min + (direction > 0 ? 1 : 0) - point.get(axis)) / direction);
                }
                point.fma(exit + EPSILON, normal);
            }
        }
        return BlockPos.containing(owner.getX() + (double) point.x,
                owner.getY() + (double) point.y, owner.getZ() + (double) point.z);
    }
}
