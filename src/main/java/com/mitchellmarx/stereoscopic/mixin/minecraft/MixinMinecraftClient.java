package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;
import com.mitchellmarx.stereoscopic.cursor.StereoCursor;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import com.mitchellmarx.stereoscopic.render.StereoBlur;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraftClient {

    // MC 26.2: setScreen moved to net.minecraft.client.gui.Gui (see MixinGui,
    // which now hosts the virtual-cursor seeding) and getMainRenderTarget()
    // moved to GameRenderer.mainRenderTarget() (scratch-FB substitution now
    // lives in MixinGameRenderer).

    /**
     * Route MC's per-frame swapchain blit through the async cursor present
     * thread when it's running. Two threads racing for the same window would
     * interleave swaps; instead, MC's present is starved (blit skipped here,
     * swap skipped in {@link MixinGlSurface}) and the cursor thread captures
     * the main FB into a shared texture, renders the cursor overlay, and
     * {@code SwapBuffers} itself on its own GL context bound to the same
     * window.
     *
     * <p>26.2 replaced {@code RenderTarget.blitToScreen()} (the 26.1.2 hook,
     * {@code MixinFramebuffer}) with a swapchain model: {@code
     * Minecraft.renderFrame} does {@code GpuSurface.acquireNextTexture()} →
     * {@code blitFromTexture(encoder, mainTargetView)} → {@code present()}.
     * The blit is the analogous capture point — same timing (frame complete,
     * pre-present).
     *
     * <p>No-op when {@link CursorPresentThread#isRunning()} is false (mono,
     * non-Windows, setup failure).
     */
    @WrapOperation(
        method = "renderFrame(Z)V",
        at = @At(value = "INVOKE",
                 target = "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V")
    )
    private void stereoscopic$routePresentThroughCursorThread(GpuSurface surface,
                                                              CommandEncoder encoder,
                                                              GpuTextureView view,
                                                              Operation<Void> original) {
        if (!CursorPresentThread.isRunning()) {
            original.call(surface, encoder, view);
            return;
        }
        CursorPresentThread.publishFrame();
    }

    /**
     * Stop the cursor present thread before MC tears the window down. The
     * daemon worker spends most of its iteration inside native SwapBuffers;
     * once {@code Window.close()} invalidates the HDC the JNI frame can't be
     * preempted, and the JVM hangs after the window closes. Joining while the
     * window is still valid avoids the hang.
     */
    @Inject(method = "close()V", at = @At("HEAD"))
    private void stereoscopic$disposeOnShutdown(CallbackInfo ci) {
        StereoCursor.stop();
        StereoBlur.dispose();
        PerEyeRenderer.disposeScratch();
    }
}
