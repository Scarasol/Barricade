package com.scarasol.barricade.client;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

class CaptureBuffersTest {
    @Test
    void nestedRenderingRestoresTheOuterBufferEvenWhenTheInnerRendererFails() {
        MultiBufferSource outer = type -> { throw new AssertionError("Not a draw test"); };
        MultiBufferSource inner = type -> { throw new AssertionError("Not a draw test"); };
        assertNull(CaptureBuffers.current());
        CaptureBuffers.<BlockEntity>render((entity, tick, pose, buffers, light, overlay) -> {
            assertSame(outer, CaptureBuffers.current());
            assertThrows(IllegalStateException.class, () -> CaptureBuffers.<BlockEntity>render(
                    (nested, nestedTick, nestedPose, nestedBuffers, nestedLight, nestedOverlay) -> {
                        assertSame(inner, CaptureBuffers.current());
                        throw new IllegalStateException("inner failure");
                    }, null, pose, inner, 0, 0));
            assertSame(outer, CaptureBuffers.current());
        }, null, new PoseStack(), outer, 0, 0);
        assertNull(CaptureBuffers.current());
    }

    @Test
    void aConcurrentThreadCannotUseTheRenderThreadsCaptureBuffer() {
        MultiBufferSource capture = type -> { throw new AssertionError("Not a draw test"); };
        CaptureBuffers.<BlockEntity>render((entity, tick, pose, buffers, light, overlay) -> {
            assertSame(capture, CaptureBuffers.current());
            assertNull(CompletableFuture.supplyAsync(CaptureBuffers::current).join());
        }, null, new PoseStack(), capture, 0, 0);
        assertNull(CaptureBuffers.current());
    }

    @Test
    void liveGlobalBufferRoutingDoesNotDisableSbmsViewCulling() {
        MultiBufferSource live = type -> { throw new AssertionError("Not a draw test"); };
        assertFalse(CaptureBuffers.capturing());
        CaptureBuffers.live(live, () -> {
            assertSame(live, CaptureBuffers.current());
            assertFalse(CaptureBuffers.capturing());
            CaptureBuffers.<BlockEntity>render((entity, tick, pose, buffers, light, overlay) ->
                    assertTrue(CaptureBuffers.capturing()), null, new PoseStack(), live, 0, 0);
            assertSame(live, CaptureBuffers.current());
            assertFalse(CaptureBuffers.capturing());
        });
        assertNull(CaptureBuffers.current());
    }

    @Test
    void taczHookReturnsTheScopedBufferAndLeavesNormalLookupsUntouched() throws Exception {
        var hook = Class.forName("com.scarasol.barricade.mixin.tacz.CaptureBufferMixin")
                .getDeclaredMethod("barricade$captureBuffer", ItemDisplayContext.class, CallbackInfoReturnable.class);
        hook.setAccessible(true);
        var normal = new CallbackInfoReturnable<MultiBufferSource>("getBufferSource", true);
        hook.invoke(null, ItemDisplayContext.NONE, normal);
        assertFalse(normal.isCancelled());
        MultiBufferSource capture = type -> { throw new AssertionError("Not a draw test"); };
        CaptureBuffers.<BlockEntity>render((entity, tick, pose, buffers, light, overlay) -> {
            for (ItemDisplayContext context : ItemDisplayContext.values()) {
                var callback = new CallbackInfoReturnable<MultiBufferSource>("getBufferSource", true);
                assertDoesNotThrow(() -> hook.invoke(null, context, callback));
                assertTrue(callback.isCancelled());
                assertSame(capture, callback.getReturnValue());
            }
        }, null, new PoseStack(), capture, 0, 0);
        var after = new CallbackInfoReturnable<MultiBufferSource>("getBufferSource", true);
        hook.invoke(null, ItemDisplayContext.NONE, after);
        assertFalse(after.isCancelled());
    }
}
