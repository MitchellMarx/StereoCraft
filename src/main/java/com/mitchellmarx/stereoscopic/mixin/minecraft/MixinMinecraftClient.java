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
     * Feed the async cursor present thread and hand it window ownership.
     * When it runs, the cursor thread captures the main FB into a shared
     * texture, renders the cursor overlay, and {@code SwapBuffers} itself on
     * its own GL context bound to the same window; MC's own swap is
     * suppressed in {@link MixinGlSurface} so the two never interleave.
     *
     * <p>26.2 replaced {@code RenderTarget.blitToScreen()} (the 26.1.2 hook,
     * {@code MixinFramebuffer}) with a validated swapchain state machine:
     * {@code Minecraft.renderFrame} does {@code GpuSurface.acquireNextTexture()}
     * → {@code blitFromTexture(encoder, mainTargetView)} → {@code present()}.
     * Unlike 26.1.2 the vanilla cycle CANNOT be starved: skipping only the
     * blit trips present()'s "Must blit to surface before presenting!"
     * validation (startup crash), and skipping the acquire dead-ends the
     * whole tail — the blit and present sites are each guarded by
     * {@code isAcquired()}, so nothing (including this capture hook) runs
     * and the window stays black. So the full acquire→blit→present cycle is
     * left intact — vanilla's bookkeeping stays consistent, its backbuffer
     * write is redundant but harmless — and only the REAL swap
     * ({@code glfwSwapBuffers} inside {@code GlSurface.present}) is
     * suppressed. The blit is the analogous 26.1.2 capture point (frame
     * complete, pre-present), so publishFrame() rides it. Worst case of the
     * shared-FB0 write is one refresh showing the frame without the cursor
     * sprite.
     */
    @WrapOperation(
        method = "renderFrame(Z)V",
        at = @At(value = "INVOKE",
                 target = "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V")
    )
    private void stereoscopic$publishFrameToCursorThread(GpuSurface surface,
                                                         CommandEncoder encoder,
                                                         GpuTextureView view,
                                                         Operation<Void> original) {
        original.call(surface, encoder, view);
        if (CursorPresentThread.isRunning()) {
            CursorPresentThread.publishFrame();
        }
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
