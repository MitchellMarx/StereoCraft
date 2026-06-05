package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
// MC 1.21.1: GlStateManager lives in com.mojang.blaze3d.platform.
// (Yarn 1.21.11 moves it to com.mojang.blaze3d.opengl; not the case here.)
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
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
 * harmful).
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

    /**
     * Remap GUI-pass scissor boxes from vanilla pixel coords to the active
     * eye's pixel region. Sodium's options menu (and any other UI that uses
     * scissor for clipping scrollable content) computes scissor pixels
     * assuming the vanilla full-FB projection. With our per-eye projection
     * the same content lands at different pixel positions; the unmapped
     * scissor then clips those draws out entirely on one eye while leaving
     * them visible on the other.
     *
     * <p>Scissor box {@code [x, y, w, h]} on the vanilla full-FB layout maps
     * to:
     * <ul>
     *   <li>Left half: {@code [x/2, y, w/2, h]}</li>
     *   <li>Right half: {@code [x/2 + fbW/2, y, w/2, h]}</li>
     * </ul>
     *
     * <p>Triggered by the same projection-swap flag the screen wrap arms;
     * world-pass and the HUD wrap (which doesn't arm the swap) are
     * unaffected.
     */
    @Inject(method = "_scissorBox(IIII)V", at = @At("HEAD"), cancellable = true)
    private static void stereoscopic$remapGuiScissor(int x, int y, int w, int h, CallbackInfo ci) {
        if (PerEyeRenderer.isBypassActive()) return;
        if (!PerEyeRenderer.isProjectionSwapActive()) return;
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInGuiPass()) return;
        int fbW = s.getFrameFbW();
        boolean leftHalf;
        if (com.mitchellmarx.stereoscopic.core.StereoOptions.INSTANCE.swapEyes) {
            leftHalf = (s.getCurrentEye() == StereoState.Eye.RIGHT);
        } else {
            leftHalf = (s.getCurrentEye() == StereoState.Eye.LEFT);
        }
        int halvedW = Math.max(1, w / 2);
        int newX = leftHalf ? (x / 2) : (x / 2 + fbW / 2);
        ci.cancel();
        PerEyeRenderer.scissorRaw(newX, y, halvedW, h);
    }

    /**
     * Per-eye projection swap on every framebuffer bind. Hooked at the
     * {@code GlStateManager} layer so it catches both Mojang's
     * {@link com.mojang.blaze3d.pipeline.RenderTarget#bindWrite} (which calls
     * this) and mod-managed FBO classes that override {@code bindWrite} and
     * skip the Mojang path entirely (notably Xaero's
     * {@code ImprovedFramebuffer.bindWrite}, which routes through its own
     * static {@code beginWrite} → {@code _glBindFramebuffer}).
     *
     * <p>When the bound FBO ID matches the real main FB recorded at the start
     * of the screen-wrap iter, reassert the per-eye projection so vanilla
     * render-type draws (widgets, FBO-to-main blits) land in the correct
     * eye-half. When the bound FBO is something else (Xaero's
     * {@code primaryScaleFBO}, PostChain intermediates, etc.), swap to vanilla
     * projection so those internal renders fill the whole intermediate and
     * aren't compressed into a half-width region.
     *
     * <p>Only triggers during the screen-wrap iter
     * ({@link PerEyeRenderer#isProjectionSwapActive}); world-pass and idle
     * frames pass through untouched.
     */
    @Inject(method = "_glBindFramebuffer(II)V", at = @At("RETURN"))
    private static void stereoscopic$swapProjectionOnFbBind(int target, int fbId, CallbackInfo ci) {
        if (!PerEyeRenderer.isProjectionSwapActive()) return;
        // Only react to DRAW-side binds. READ-only binds (used during
        // glBlitFramebuffer source setup) don't change which FB receives the
        // next set of shader draws, so swapping projection on them would
        // thrash unnecessarily.
        if (target != GL30.GL_FRAMEBUFFER && target != GL30.GL_DRAW_FRAMEBUFFER) return;
        int realId = PerEyeRenderer.getSwapRealMainFbId();
        if (realId < 0) return;
        Matrix4f targetProj = (fbId == realId)
            ? PerEyeRenderer.getSwapPerEyeProjection()
            : PerEyeRenderer.getSwapVanillaProjection();
        if (targetProj == null) return;
        VertexSorting sorting = PerEyeRenderer.getSwapVertexSorting();
        if (sorting == null) sorting = VertexSorting.ORTHOGRAPHIC_Z;
        RenderSystem.setProjectionMatrix(targetProj, sorting);
    }
}
