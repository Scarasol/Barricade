package com.scarasol.barricade.window;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

public record BoardPlane(Direction facing, int coordinate) {
    public static BoardPlane at(BlockPos pos, Direction facing) {
        return new BoardPlane(facing, pos.get(facing.getAxis()));
    }
    public BoardCell cell(BlockPos pos) {
        return switch (facing.getAxis()) {
            case X -> new BoardCell(pos.getZ(), pos.getY());
            case Y -> new BoardCell(pos.getX(), pos.getZ());
            case Z -> new BoardCell(pos.getX(), pos.getY());
        };
    }
    public BlockPos block(BoardCell cell) {
        return switch (facing.getAxis()) {
            case X -> new BlockPos(coordinate, cell.v(), cell.u());
            case Y -> new BlockPos(cell.u(), coordinate, cell.v());
            case Z -> new BlockPos(cell.u(), cell.v(), coordinate);
        };
    }
    public Vec3 point(double u, double v, double depth) {
        double normal = coordinate + (facing.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? depth / 16 : 1 - depth / 16);
        return switch (facing.getAxis()) {
            case X -> new Vec3(normal, v / 16, u / 16);
            case Y -> new Vec3(u / 16, normal, v / 16);
            case Z -> new Vec3(u / 16, v / 16, normal);
        };
    }
    public Vec3 normal(double u, double v, double depth) {
        double n = depth * facing.getAxisDirection().getStep();
        return switch (facing.getAxis()) {
            case X -> new Vec3(n, v, u);
            case Y -> new Vec3(u, n, v);
            case Z -> new Vec3(u, v, n);
        };
    }
}
