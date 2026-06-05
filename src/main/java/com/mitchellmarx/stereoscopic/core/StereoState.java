package com.mitchellmarx.stereoscopic.core;

import net.minecraft.world.phys.Vec3;

public final class StereoState {

    public static final StereoState INSTANCE = new StereoState();

    public enum Eye { LEFT, RIGHT, MONO }

    private boolean active;
    private Eye currentEye = Eye.MONO;
    private boolean inWorldPass;
    private boolean inGuiPass;

    private int eyeVpX, eyeVpY, eyeVpW, eyeVpH;

    // Frame-cached snapshot. NOT cleared by endFrame so post-frame callbacks
    // can still read the mode that was active for the frame just rendered.
    private StereoMode frameMode = StereoMode.OFF;
    private float frameIpd = 0.064f;
    private float frameConvergence = 4.0f;

    private int frameFbW, frameFbH;

    /**
     * Mono (un-IPD-shifted) Camera position captured by MixinGameRenderer
     * BEFORE the per-eye loop. Shared passes that must be eye-agnostic (Iris
     * shadow render, Sodium chunk setup) restore this for their duration.
     * Null when not inside the per-eye world wrap.
     */
    private Vec3 frameMonoCameraPos;

    private StereoState() {}

    /** From MixinGameRenderer's HEAD inject on render(). Snapshots options
     *  into frame-local fields so mid-frame config flips can't desync state. */
    public void beginFrame(int fbW, int fbH) {
        StereoOptions o = StereoOptions.INSTANCE;
        this.frameMode = o.mode;
        this.frameIpd = o.ipd;
        this.frameConvergence = o.convergence;
        this.frameFbW = fbW;
        this.frameFbH = fbH;
        this.active = frameMode.isActive();
        this.currentEye = Eye.MONO;
    }

    public void endFrame() {
        this.active = false;
        this.currentEye = Eye.MONO;
        this.inWorldPass = false;
        this.inGuiPass = false;
        this.frameMonoCameraPos = null;
    }

    public void setEye(Eye eye) { this.currentEye = eye; }

    public boolean isActive()      { return active; }
    public boolean isInWorldPass() { return inWorldPass; }
    public boolean isInGuiPass()   { return inGuiPass; }
    public Eye     getCurrentEye() { return currentEye; }
    public StereoMode getFrameMode() { return frameMode; }
    public float      getFrameIpd()  { return frameIpd; }
    public float      getFrameConvergence() { return frameConvergence; }
    public int getFrameFbW() { return frameFbW; }
    public int getFrameFbH() { return frameFbH; }

    public void enterWorldPass(int x, int y, int w, int h) {
        this.inWorldPass = true;
        this.eyeVpX = x; this.eyeVpY = y; this.eyeVpW = w; this.eyeVpH = h;
    }
    public void exitWorldPass() { this.inWorldPass = false; }

    public void enterGuiPass(int x, int y, int w, int h) {
        this.inGuiPass = true;
        this.eyeVpX = x; this.eyeVpY = y; this.eyeVpW = w; this.eyeVpH = h;
    }
    public void exitGuiPass() { this.inGuiPass = false; }

    public int getEyeVpX() { return eyeVpX; }
    public int getEyeVpY() { return eyeVpY; }
    public int getEyeVpW() { return eyeVpW; }
    public int getEyeVpH() { return eyeVpH; }

    public Vec3 getFrameMonoCameraPos() { return frameMonoCameraPos; }
    public void setFrameMonoCameraPos(Vec3 pos) { this.frameMonoCameraPos = pos; }

    /**
     * -ipd/2 LEFT, +ipd/2 RIGHT, 0 MONO — vanilla 1.21 convention. LEFT eye's
     * camera shifts along the negative right-vector (camera moves left along
     * the head's local +X), so the left viewport renders the world from the
     * physical left eye's position. Angelica's 1.7.10 port used the opposite
     * signs; copying its convention here produced eye-swapped stereo because
     * Yarn's {@code Camera.getDiagonalPlane()} resolves to the local right
     * vector with the standard right-handed orientation.
     *
     * <p>If {@link StereoOptions#swapEyes} is set, returns the opposite sign
     * per eye — for SBS displays / VR virtual-monitor setups that interpret
     * the SBS halves with reversed eye-assignment.
     */
    public float getEyeOffset() {
        float base = switch (currentEye) {
            case LEFT  -> -frameIpd * 0.5f;
            case RIGHT -> +frameIpd * 0.5f;
            case MONO  -> 0f;
        };
        return StereoOptions.INSTANCE.swapEyes ? -base : base;
    }

    public int currentEyeIndex() {
        return currentEye == Eye.RIGHT ? 1 : 0;
    }

    /**
     * Reads StereoOptions directly, NOT the cached {@link #active} flag —
     * Iris pipeline init runs before the first beginFrame(); reading the
     * cached flag there would size RenderTargets for mono and contaminate
     * the first stereo frame until shader reload.
     */
    public int stereoEyeCount() {
        return StereoOptions.INSTANCE.mode.isActive() ? 2 : 1;
    }
}
