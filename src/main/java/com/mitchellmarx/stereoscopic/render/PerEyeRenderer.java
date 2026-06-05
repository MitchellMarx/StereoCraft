package com.mitchellmarx.stereoscopic.render;

import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.TextureTarget;

public final class PerEyeRenderer {

    private static boolean bypass;

    public static boolean isBypassActive() { return bypass; }

    private static boolean scratchFbActive;
    private static TextureTarget scratchFb;
    private static int scratchFbW = -1;
    private static int scratchFbH = -1;

    /**
     * Projection-swap state, used by
     * {@link com.mitchellmarx.stereoscopic.mixin.minecraft.MixinFramebuffer}'s
     * bindWrite RETURN hook to keep mod-managed intermediate FBOs rendering
     * with the mono projection while the per-eye projection is reasserted
     * the moment the main FB is rebound. Xaero's world-map terrain is the
     * motivating case: it binds {@code primaryScaleFBO} as a writable target
     * and draws terrain using vanilla render types, which read
     * {@code RenderSystem.projection}. With our per-eye projection still
     * active, terrain compresses into one half of the FBO; the subsequent
     * FBO-to-main blit then samples a half-content FBO and lands mirrored
     * content in each eye.
     */
    private static boolean projectionSwapActive;
    private static org.joml.Matrix4f swapVanillaProjection;
    private static org.joml.Matrix4f swapPerEyeProjection;
    private static com.mojang.blaze3d.vertex.VertexSorting swapVertexSorting;
    private static com.mojang.blaze3d.pipeline.RenderTarget swapRealMainFb;

    public static boolean isProjectionSwapActive() { return projectionSwapActive; }

    public static org.joml.Matrix4f getSwapVanillaProjection() { return swapVanillaProjection; }
    public static org.joml.Matrix4f getSwapPerEyeProjection() { return swapPerEyeProjection; }
    public static com.mojang.blaze3d.vertex.VertexSorting getSwapVertexSorting() { return swapVertexSorting; }
    public static com.mojang.blaze3d.pipeline.RenderTarget getSwapRealMainFb() { return swapRealMainFb; }

    private static int swapRealMainFbId = -1;
    public static int getSwapRealMainFbId() { return swapRealMainFbId; }

    public static void beginProjectionSwap(org.joml.Matrix4f vanilla,
                                            org.joml.Matrix4f perEye,
                                            com.mojang.blaze3d.vertex.VertexSorting sorting,
                                            com.mojang.blaze3d.pipeline.RenderTarget realMainFb) {
        swapVanillaProjection = vanilla;
        swapPerEyeProjection = perEye;
        swapVertexSorting = sorting;
        swapRealMainFb = realMainFb;
        swapRealMainFbId = realMainFb != null ? realMainFb.frameBufferId : -1;
        projectionSwapActive = true;
    }

    public static void endProjectionSwap() {
        projectionSwapActive = false;
        swapVanillaProjection = null;
        swapPerEyeProjection = null;
        swapVertexSorting = null;
        swapRealMainFb = null;
        swapRealMainFbId = -1;
    }

    public static boolean isScratchFbActive() { return scratchFbActive; }
    public static void setScratchFbActive(boolean active) { scratchFbActive = active; }
    public static TextureTarget getScratchFb() { return scratchFb; }
    public static int getScratchFbW() { return scratchFbW; }
    public static int getScratchFbH() { return scratchFbH; }

    public static TextureTarget ensureScratchFb(int w, int h) {
        if (scratchFb != null && scratchFbW == w && scratchFbH == h) return scratchFb;
        if (scratchFb != null) {
            try { scratchFb.destroyBuffers(); }
            catch (Throwable t) { Stereoscopic.LOG.warn("Scratch FB delete failed during resize; GPU FB leaked", t); }
            scratchFb = null;
        }
        scratchFb = new TextureTarget(w, h, true, true);
        scratchFbW = w;
        scratchFbH = h;
        return scratchFb;
    }

    public static void disposeScratch() {
        if (scratchFb != null) {
            try { scratchFb.destroyBuffers(); }
            catch (Throwable t) { Stereoscopic.LOG.warn("Scratch FB delete failed on shutdown; GPU FB leaked", t); }
            scratchFb = null;
        }
        scratchFbW = -1;
        scratchFbH = -1;
    }

    public static void viewportRaw(int x, int y, int w, int h) {
        bypass = true;
        try { GlStateManager._viewport(x, y, w, h); }
        finally { bypass = false; }
    }

    public static void scissorRaw(int x, int y, int w, int h) {
        bypass = true;
        try {
            GlStateManager._enableScissorTest();
            GlStateManager._scissorBox(x, y, w, h);
        } finally { bypass = false; }
    }

    public static void scissorDisableRaw() {
        bypass = true;
        try { GlStateManager._disableScissorTest(); }
        finally { bypass = false; }
    }

    /**
     * Copy the current iter's eye-rect of MC's main FB color into the other
     * eye-rect of the same FB. Used by {@code MixinScreen} after the first
     * iter's title-screen panorama renders into its eye-half: the subsequent
     * {@code renderBlurredBackground} runs a full-FB blur ({@code PostPass.process}
     * writes a fullscreen quad through its own ortho matrix, ignoring our
     * per-eye projection), so both halves of main FB must contain identical
     * content before blur runs, else only the iter's half ends up correctly
     * blurred and the other half is left as stale prev-frame content.
     *
     * <p>Mirrors the active iter's eye-rect ({@code StereoState.getEyeVp*})
     * to the opposite-X half-rect (same Y, same dimensions). Pure
     * {@code glBlitFramebuffer} -- preserves blend / depth / scissor state
     * exactly like {@code MixinGameRenderer.stereoscopic$blitScratchToMain}.
     */
    public static void mirrorActiveEyeHalfToOther() {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive()) return;
        com.mojang.blaze3d.pipeline.RenderTarget mainFb = swapRealMainFb;
        if (mainFb == null) mainFb = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
        if (mainFb == null || mainFb.getColorTextureId() <= 0) return;
        int colorTex = mainFb.getColorTextureId();
        int fbW = s.getFrameFbW();
        int srcX = s.getEyeVpX();
        int srcY = s.getEyeVpY();
        int w = s.getEyeVpW();
        int h = s.getEyeVpH();
        int dstX = (srcX == 0) ? fbW - w : 0;
        int dstY = srcY;

        int prevDraw = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
        boolean wasScissor = org.lwjgl.opengl.GL11.glIsEnabled(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        if (wasScissor) org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);

        int readFbo = org.lwjgl.opengl.GL30.glGenFramebuffers();
        int drawFbo = org.lwjgl.opengl.GL30.glGenFramebuffers();
        try {
            GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, readFbo);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,
                org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL11.GL_TEXTURE_2D, colorTex, 0);
            org.lwjgl.opengl.GL11.glReadBuffer(org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0);

            GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            org.lwjgl.opengl.GL30.glFramebufferTexture2D(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER,
                org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0, org.lwjgl.opengl.GL11.GL_TEXTURE_2D, colorTex, 0);
            org.lwjgl.opengl.GL11.glDrawBuffer(org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0);

            int rs = org.lwjgl.opengl.GL30.glCheckFramebufferStatus(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER);
            int ds = org.lwjgl.opengl.GL30.glCheckFramebufferStatus(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER);
            if (rs != org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE || ds != org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE) return;
            org.lwjgl.opengl.GL30.glBlitFramebuffer(
                srcX, srcY, srcX + w, srcY + h,
                dstX, dstY, dstX + w, dstY + h,
                org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT, org.lwjgl.opengl.GL11.GL_NEAREST);
        } finally {
            GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            GlStateManager._glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER, prevRead);
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(readFbo);
            org.lwjgl.opengl.GL30.glDeleteFramebuffers(drawFbo);
            if (wasScissor) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        }
    }

    public enum Pass { WORLD, GUI }

    public static void runForEachEye(Pass pass, Runnable body) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive()) { body.run(); return; }

        int fbW = s.getFrameFbW();
        int fbH = s.getFrameFbH();

        StereoState.Eye[] eyes = { StereoState.Eye.LEFT, StereoState.Eye.RIGHT };
        for (int i = 0; i < eyes.length; i++) {
            if (i > 0 && pass == Pass.WORLD) {
                PerEyeRenderTargetHooks.irisStartFrameBetweenEyes();
            }
            renderOneEye(eyes[i], pass, body, fbW, fbH, s);
        }
        viewportRaw(0, 0, fbW, fbH);
        scissorDisableRaw();
        // MC 1.21.1 has no RenderSystem.{en,dis}ableScissorForRenderTypeDraws —
        // those were added with the 1.21.6 CommandEncoder pipeline. 1.21.1's
        // GuiRenderer flush goes through the older render path which respects
        // classic glScissor, so scissorDisableRaw() above is sufficient.
    }

    private static void renderOneEye(StereoState.Eye eye, Pass pass, Runnable body,
                                     int fbW, int fbH, StereoState s) {
        s.setEye(eye);
        if (pass == Pass.WORLD) {
            PerEyeRenderTargetHooks.setActiveEye(s.currentEyeIndex());
        }
        ViewportMath.Rect r = ViewportMath.eyeRect(eye, StereoOptions.INSTANCE.mode, fbW, fbH);
        // Both WORLD and GUI: set viewport/scissor to the eye-rect of main FB.
        // For GUI in MC 1.21.1, glViewport works — the HUD shader gets clipped
        // and squished into the eye-rect. (The Fabric mod's pose-stack
        // workaround in MixinGuiRenderer is specific to MC 1.21.6+, where
        // CommandEncoder.createRenderPass ignores glViewport.)
        // For WORLD with scratch FB active, the world wrap immediately
        // overrides to full-FB viewport for the duration of renderLevel.
        viewportRaw(r.x(), r.y(), r.w(), r.h());
        scissorRaw(r.x(), r.y(), r.w(), r.h());
        switch (pass) {
            case WORLD -> s.enterWorldPass(r.x(), r.y(), r.w(), r.h());
            case GUI   -> s.enterGuiPass(r.x(), r.y(), r.w(), r.h());
        }
        try { body.run(); }
        finally {
            switch (pass) {
                case WORLD -> s.exitWorldPass();
                case GUI   -> s.exitGuiPass();
            }
        }
    }
}
