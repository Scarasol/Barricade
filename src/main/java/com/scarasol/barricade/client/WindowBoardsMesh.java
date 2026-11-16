package com.scarasol.barricade.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.scarasol.barricade.window.BoardCell;
import com.scarasol.barricade.window.BoardLayout;
import com.scarasol.barricade.window.BoardPlane;
import java.util.*;
import java.util.function.ToIntFunction;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Window-only adapter to the existing static-mesh acceleration and playback paths. */
public final class WindowBoardsMesh implements AutoCloseable {
    public static final RenderType MATERIAL = RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS);
    record Cell(BlockPos pos, StaticMesh.Batch batch) { }
    final List<Cell> cells;
    private boolean closed;

    public WindowBoardsMesh(BoardPlane plane, BlockPos origin, List<BoardLayout.Fragment> fragments,
                            TextureAtlasSprite wood, TextureAtlasSprite iron) {
        Map<BoardCell, List<StaticMesh.Vertex>> vertices = new TreeMap<>();
        for (BoardLayout.Fragment fragment : fragments) {
            List<StaticMesh.Vertex> output = vertices.computeIfAbsent(fragment.owner(), ignored -> new ArrayList<>());
            for (BoardLayout.Face face : fragment.faces()) {
                TextureAtlasSprite sprite = face.nail() ? iron : wood;
                Vec3 normal = plane.normal(face.normal().x, face.normal().y, face.normal().z);
                float shade = (float) (0.8 + Math.max(0, normal.y) * 0.2 - Math.max(0, -normal.y) * 0.25
                        - Math.abs(normal.x) * 0.08);
                int red = Math.round((face.tint() >> 16 & 255) * shade);
                int green = Math.round((face.tint() >> 8 & 255) * shade);
                int blue = Math.round((face.tint() & 255) * shade);
                for (BoardLayout.Vertex vertex : face.vertices()) {
                    Vec3 point = plane.point(vertex.u(), vertex.v(), vertex.depth());
                    output.add(new StaticMesh.Vertex((float) (point.x - origin.getX()),
                            (float) (point.y - origin.getY()), (float) (point.z - origin.getZ()),
                            red, green, blue, 255, sprite.getU(vertex.textureU() * 16),
                            sprite.getV(vertex.textureV() * 16), OverlayTexture.NO_OVERLAY, 0,
                            (float) normal.x, (float) normal.y, (float) normal.z));
                }
            }
        }
        cells = vertices.entrySet().stream().map(entry -> {
            BlockPos pos = plane.block(entry.getKey());
            return new Cell(pos, new StaticMesh.Batch(MATERIAL, entry.getValue(),
                    new Vector3f(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ())));
        }).toList();
    }

    public void draw(PoseStack.Pose pose, MultiBufferSource buffers, ToIntFunction<BlockPos> light) {
        if (!closed) for (Cell cell : cells) cell.batch.draw(pose, buffers, light.applyAsInt(cell.pos));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        cells.forEach(cell -> cell.batch.close());
    }
}
