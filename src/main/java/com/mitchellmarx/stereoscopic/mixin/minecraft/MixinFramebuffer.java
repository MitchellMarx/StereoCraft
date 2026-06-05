package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Route MC's per-frame {@code Framebuffer.blitToScreen} through the async
 * cursor present thread when it's running. Two threads racing for the same
 * window would interleave swaps; instead, MC's blit is cancelled and the
 * cursor thread captures the FB into a shared texture, renders cursor
 * overlay, and {@code SwapBuffers} itself on its own GL context bound to the
 * same window. MC's later {@code presentTexture} still fires but with the
 * default FB untouched it presents stale content — the cursor thread's swap
 * is what the user sees on the next refresh.
 *
 * <p>No-op when {@link CursorPresentThread#isRunning()} is false (mono, non-
 * Windows, setup failure).
 */
@Mixin(RenderTarget.class)
public abstract class MixinFramebuffer {

    @Inject(method = "blitToScreen()V", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$routeBlitThroughCursorThread(CallbackInfo ci) {
        if (!CursorPresentThread.isRunning()) return;
        CursorPresentThread.publishFrame();
        ci.cancel();
    }
}
