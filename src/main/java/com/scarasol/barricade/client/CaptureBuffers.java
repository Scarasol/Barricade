package com.scarasol.barricade.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Routes global buffer lookups without confusing live drawing with full-face capture. */
public final class CaptureBuffers {
    private record Scope(MultiBufferSource buffers, boolean capture) { }
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private CaptureBuffers() { }

    public static MultiBufferSource current() {
        Scope scope = CURRENT.get();
        return scope == null ? null : scope.buffers();
    }

    public static boolean capturing() {
        Scope scope = CURRENT.get();
        return scope != null && scope.capture();
    }

    static void live(MultiBufferSource buffers, Runnable draw) { scoped(buffers, false, draw); }

    static <T extends BlockEntity> void render(BlockEntityRenderer<T> renderer, T entity, PoseStack pose,
                                               MultiBufferSource buffers, int light, int overlay) {
        scoped(buffers, true, () -> renderer.render(entity, 0, pose, buffers, light, overlay));
    }

    private static void scoped(MultiBufferSource buffers, boolean capture, Runnable draw) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(buffers, capture));
        try {
            draw.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
