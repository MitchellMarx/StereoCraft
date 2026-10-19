package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
// 1.21.11: GlStateManager moved from com.mojang.blaze3d.platform to com.mojang.blaze3d.opengl.
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Legacy scissor-pin intercept for the non-scratch world path. Short-circuits
 * when scratch FB is active (each iter writes its own FB — no cross-eye
 * clobber to protect against; pinning scissor to eye-rect would clip the
 * scratch writes and produce mini-SBS within scratch) and when Iris deferred
 * pipeline is active (bank-per-eye routing makes scissor pinning actively
 * harmful — see comment history before this cleanup for the LEFT-half junk
 * symptom).
 *
 * <p>The viewport remap that used to live here was actively harmful with the
 * Iris deferred pipeline (caught Iris's composite-pass {@code _viewport(0, 0,
 * colortexW, colortexH)} and remapped them to the eye-rect → composite
 * sampled colortex 0..1 across an eye-half viewport, returning junk from the
 * never-written second half). Removed entirely; scissor pinning is enough.
 */
@Mixin(GlStateManager.class)
public abstract class MixinGlStateManager {

    @Inject(method = "_disableScissorTest()V", at = @At("HEAD"), cancellable = true)
    private static void stereoscopic$pinScissorEnable(CallbackInfo ci) {
        if (PerEyeRenderer.isBypassActive()) return;
        StereoState s = StereoState.INSTANCE;
        if (!s.isInWorldPass() || !s.isActive()) return;
        if (PerEyeRenderer.isScratchFbActive()) return;
        if (PerEyeRenderTargetHooks.hasIrisRenderTargets()) return;
        ci.cancel();
        PerEyeRenderer.scissorRaw(s.getEyeVpX(), s.getEyeVpY(), s.getEyeVpW(), s.getEyeVpH());
    }

    @Inject(method = "_scissorBox(IIII)V", at = @At("HEAD"), cancellable = true)
    private static void stereoscopic$remapScissorBox(int x, int y, int w, int h, CallbackInfo ci) {
        if (PerEyeRenderer.isBypassActive()) return;
        StereoState s = StereoState.INSTANCE;
        if (!s.isInWorldPass() || !s.isActive()) return;
        if (PerEyeRenderer.isScratchFbActive()) return;
        if (PerEyeRenderTargetHooks.hasIrisRenderTargets()) return;

        // Only remap "full main-FB" scissors — intentional sub-rects (shadow map etc.) pass through.
        int fbW = s.getFrameFbW();
        int fbH = s.getFrameFbH();
        if (x != 0 || y != 0 || w != fbW || h != fbH) return;

        ci.cancel();
        PerEyeRenderer.scissorRaw(s.getEyeVpX(), s.getEyeVpY(), s.getEyeVpW(), s.getEyeVpH());
    }
}
