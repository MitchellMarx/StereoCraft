package com.mitchellmarx.stereoscopic.core;

import com.mitchellmarx.stereoscopic.config.StereoConfig;
import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;
import lombok.Getter;

public class StereoState {

    public enum Eye { LEFT, RIGHT, MONO }

    public static final StereoState INSTANCE = new StereoState();

    private Eye currentEye = Eye.MONO;
    private boolean active = false;
    private long frameSequence = 0;

    // While inGuiPass is true, GLStateManager.glScissor remaps caller scissor coords (assumed to
    // be "framebuffer pixels with GUI filling the whole screen") into the current eye viewport.
    // Set by MixinEntityRenderer_Stereo around each drawScreen / renderGameOverlay / Post-event
    // eye pass.
    private boolean inGuiPass = false;

    // While true, MixinGLStateManager_StereoRemap intercepts viewport-reset calls (those
    // targeting the full main framebuffer dimensions) and remaps them to the current eye
    // viewport. Set by MixinEntityRenderer_Stereo around each renderWorld eye pass.
    private boolean inWorldPass = false;

    private int eyeVpX = 0;
    private int eyeVpY = 0;
    private int eyeVpW = 0;
    private int eyeVpH = 0;

    public void enterGuiPass(int x, int y, int w, int h) {
        inGuiPass = true;
        eyeVpX = x;
        eyeVpY = y;
        eyeVpW = w;
        eyeVpH = h;
    }

    public void exitGuiPass() { inGuiPass = false; }
    public boolean isInGuiPass() { return inGuiPass; }

    public void enterWorldPass(int x, int y, int w, int h) {
        inWorldPass = true;
        eyeVpX = x;
        eyeVpY = y;
        eyeVpW = w;
        eyeVpH = h;
    }

    public void exitWorldPass() { inWorldPass = false; }
    public boolean isInWorldPass() { return inWorldPass; }

    public int getEyeVpX() { return eyeVpX; }
    public int getEyeVpY() { return eyeVpY; }
    public int getEyeVpW() { return eyeVpW; }
    public int getEyeVpH() { return eyeVpH; }

    public long getFrameSequence() { return frameSequence; }

    public Eye getCurrentEye() {
        return currentEye;
    }

    public boolean isActive() {
        return active;
    }

    /** Cached at frame start so config flips mid-frame don't cause inconsistency. */
    @Getter private StereoMode frameMode = StereoMode.OFF;
    @Getter private float frameIpd = 0.064f;

    private StereoState() {}

    public boolean beginFrame() {
        frameSequence++;
        StereoMode mode = StereoConfig.stereoscopicMode;
        if (mode == null || !mode.isActive()) {
            active = false;
            currentEye = Eye.MONO;
            frameMode = StereoMode.OFF;
            CursorPresentThread.stop();
            return false;
        }
        active = true;
        frameMode = mode;
        frameIpd = StereoConfig.stereoIpd;
        currentEye = Eye.MONO;
        CursorPresentThread.ensureStarted();
        return true;
    }

    public void endFrame() {
        active = false;
        currentEye = Eye.MONO;
        // Intentionally do NOT reset frameMode/frameIpd here. RenderTickEvent.END fires from
        // FMLCommonHandler.onRenderTickEnd *after* updateCameraAndRender returns, and
        // MixinFMLCommonHandler_Stereo needs the frame's stereo config still readable so it can
        // duplicate the event per-eye. beginFrame() overwrites these on the next frame.
    }

    public void setEye(Eye eye) {
        this.currentEye = eye;
    }

    // Signs are flipped relative to vanilla's anaglyph convention. Vanilla renders each eye from
    // the OPPOSITE camera position, which works for red/cyan anaglyph because the brain doesn't
    // see real per-eye images, but is backwards for SBS where each eye directly sees its half.
    //
    // {@link StereoConfig#stereoSwapEyes} negates the result so reversed SBS displays / VR
    // passthrough virtual monitors that interpret the halves with the opposite eye-assignment
    // stay consistent with the swap-aware viewport (MixinEntityRenderer_Stereo).
    public float getEyeOffset() {
        if (!isActive()) return 0f;
        float ipd = StereoConfig.stereoIpd > 0f ? StereoConfig.stereoIpd : frameIpd;
        float half = ipd * 0.5f;
        float base;
        switch (getCurrentEye()) {
            case LEFT:  base =  half; break;
            case RIGHT: base = -half; break;
            default:    return 0f;
        }
        return StereoConfig.stereoSwapEyes ? -base : base;
    }

    /** Hand-specific offset, currently not applied (HandRenderer uses getEyeOffset instead for viewer comfort). */
    public float getHandEyeOffset() {
        if (!isActive()) return 0f;
        float ipd = StereoConfig.stereoIpd > 0f ? StereoConfig.stereoIpd : frameIpd;
        float scale = ipd / 0.064f;
        float base = 0.1f * scale;
        switch (getCurrentEye()) {
            case LEFT:  return  base;
            case RIGHT: return -base;
            default:    return 0f;
        }
    }

    public boolean isLeftEye()  { return isActive() && getCurrentEye() == Eye.LEFT; }
    public boolean isRightEye() { return isActive() && getCurrentEye() == Eye.RIGHT; }

    /** Stable integer index for the current eye. LEFT/MONO map to 0, RIGHT maps to 1. */
    public int currentEyeIndex() {
        return getCurrentEye() == Eye.RIGHT ? 1 : 0;
    }

    /** 2 when stereo is enabled in config, otherwise 1. Reads config directly, not the cached
     *  {@link #active} flag, because RenderTargets is built at pipeline-init time which can fire
     *  before the first {@link #beginFrame()} — a cached value would still be false there and
     *  allocate mono targets, cross-contaminating the first stereo frame. */
    public int stereoEyeCount() {
        final StereoMode mode = StereoConfig.stereoscopicMode;
        return (mode != null && mode.isActive()) ? 2 : 1;
    }

    // Iris-facing helpers: return the FB dimensions Iris should use for sizing render targets,
    // compute dispatch, and the viewWidth/viewHeight uniforms. ALWAYS return the full display
    // size even in SBS stereo — each eye renders at native aspect into its own per-eye FBO
    // chain, and the final per-eye blit squishes horizontally into that eye's main-FB region.
    public int irisFbWidth(int actualWidth)   { return actualWidth;  }
    public int irisFbHeight(int actualHeight) { return actualHeight; }
}
