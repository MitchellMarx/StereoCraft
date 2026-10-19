package com.mitchellmarx.stereoscopic.mixin.angelica;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.mitchellmarx.stereoscopic.config.StereoConfig;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla {@code EntityRenderer.renderWorld} sets the viewport to the full main framebuffer
 * early in its body (a vanilla {@code GL11.glViewport} call that {@code GLSMRedirector} ASM-
 * rewrites at class load to {@code GLStateManager.glViewport}). With shaders enabled, Iris's
 * pipeline rebinds the framebuffer mid-renderWorld and our {@link
 * com.mitchellmarx.stereoscopic.mixin.minecraft.MixinFramebuffer_Stereo} restores the eye
 * viewport for the remainder of the pass. With shaders disabled, no rebind happens, so the
 * full-screen viewport persists and both eye passes rasterize at full resolution (clipped only
 * by scissor — visible result is "mono").
 *
 * <p>This mixin intercepts the full-screen viewport call during a stereo world pass and remaps
 * it to the current eye's viewport. Uses a thread-local re-entry flag so the recursive call
 * after remap doesn't loop.
 *
 * <p>The {@code (x == 0 && y == 0 && width == displayW && height == displayH)} check is a
 * narrow trigger — it specifically catches "the caller wants to reset to full screen" and is
 * structurally impossible to trigger for legitimate eye-sized viewport calls.
 */
@Mixin(value = GLStateManager.class, remap = false)
public class MixinGLStateManager_StereoRemap {

    private static final ThreadLocal<Boolean> stereoscopic$remapping =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "glViewport(IIII)V", at = @At("HEAD"), cancellable = true)
    private static void stereoscopic$remapWorldPassViewport(int x, int y, int width, int height,
                                                            CallbackInfo ci) {
        if (stereoscopic$remapping.get()) return;
        if (!StereoState.INSTANCE.isInWorldPass()) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        // Only remap "caller asked for full main FB" — leave eye-sized calls alone.
        if (x != 0 || y != 0 || width != mc.displayWidth || height != mc.displayHeight) return;
        final int eyeX = StereoState.INSTANCE.getEyeVpX();
        final int eyeY = StereoState.INSTANCE.getEyeVpY();
        final int eyeW = StereoState.INSTANCE.getEyeVpW();
        final int eyeH = StereoState.INSTANCE.getEyeVpH();
        if (eyeW <= 0 || eyeH <= 0) return;  // not yet primed
        stereoscopic$remapping.set(Boolean.TRUE);
        try {
            GLStateManager.glViewport(eyeX, eyeY, eyeW, eyeH);
        } finally {
            stereoscopic$remapping.set(Boolean.FALSE);
        }
        ci.cancel();
    }

    /**
     * SBS_FULL GUI scissor. The screen is drawn once into the full-size scratch FB under a
     * half-width ortho (MixinScaledResolution_StereoFull), so every GUI unit spans 2× the
     * horizontal pixels a {@code scaleFactor}-based scissor assumes. Callers that build scissor
     * rects as GUI coords × scaleFactor (Reese's {@code AbstractFrame.applyScissor}, which clips
     * the Sodium options pages) end up with X and width halved and clip their own content away.
     * Doubling X/width during the scratch drawScreen pass puts the rect back over the stretched
     * geometry; Y/height are untouched since FULL only halves the layout width. HALF keeps the
     * full-width layout, so its scissor already matches the scratch.
     *
     * <p>Reads live {@link StereoConfig#stereoscopicMode} to agree with the ScaledResolution the
     * caller (and the ortho) were built from.
     */
    @Inject(method = "glScissor(IIII)V", at = @At("HEAD"), cancellable = true)
    private static void stereoscopic$widenFullSbsGuiScissor(int x, int y, int width, int height,
                                                            CallbackInfo ci) {
        if (stereoscopic$remapping.get()) return;
        if (!StereoState.INSTANCE.isInScratchGuiDraw()) return;
        final StereoMode mode = StereoConfig.stereoscopicMode;
        if (mode == null || !mode.isFull()) return;
        stereoscopic$remapping.set(Boolean.TRUE);
        try {
            GLStateManager.glScissor(x * 2, y, width * 2, height);
        } finally {
            stereoscopic$remapping.set(Boolean.FALSE);
        }
        ci.cancel();
    }
}
