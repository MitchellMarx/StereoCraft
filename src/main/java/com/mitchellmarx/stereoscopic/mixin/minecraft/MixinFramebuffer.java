package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;
import com.mojang.blaze3d.pipeline.RenderTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes MC's per-frame {@code blitToScreen} through the async cursor present
 * thread so {@code SwapBuffers} runs off the render thread.
 */
@Mixin(RenderTarget.class)
public abstract class MixinFramebuffer {

    @Inject(method = "blitToScreen(II)V", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$routeBlitThroughCursorThread(int width, int height, CallbackInfo ci) {
        if (!CursorPresentThread.isRunning()) return;
        CursorPresentThread.publishFrame();
        ci.cancel();
    }
}
