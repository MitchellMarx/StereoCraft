package com.mitchellmarx.stereoscopic.render;

import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.TextureTarget;

public final class PerEyeRenderer {

    private static boolean bypass;

    public static boolean isBypassActive() { return bypass; }

    private static boolean scratchFbActive;
    private static TextureTarget scratchFb;
    private static int scratchFbW = -1;
    private static int scratchFbH = -1;

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
        // WORLD: set viewport/scissor to the eye-rect of main FB. (When scratch
        // FB is active the world wrap immediately overrides to full-FB viewport
        // for the duration of renderLevel — see MixinGameRenderer.)
        // GUI: leave viewport/scissor at whatever MC set up (full window).
        // The HUD wraps use GuiGraphics.pose() compression to land in the
        // correct eye-half; combining viewport-half AND pose-half would
        // double-compress to 1/4 width. Mirrors the Fabric mod's approach
        // (MixinGuiRenderer relies on pose-stack compression, not viewport).
        if (pass == Pass.WORLD) {
            viewportRaw(r.x(), r.y(), r.w(), r.h());
            scissorRaw(r.x(), r.y(), r.w(), r.h());
        }
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
