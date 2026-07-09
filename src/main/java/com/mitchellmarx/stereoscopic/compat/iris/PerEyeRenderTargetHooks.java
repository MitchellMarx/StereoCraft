package com.mitchellmarx.stereoscopic.compat.iris;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Optional-dependency facade around Iris's pipeline lifecycle and per-eye
 * render-target switching. Every entry point is a no-op when Iris is absent.
 * Calls that actually touch Iris classes are isolated in their own method
 * bodies so the JVM verifier doesn't try to resolve Iris symbols at class
 * load when Iris isn't on the classpath.
 */
public final class PerEyeRenderTargetHooks {

    private PerEyeRenderTargetHooks() {}

    public static final boolean IRIS_PRESENT =
        FabricLoader.getInstance().isModLoaded("iris");

    public static int wantedEyeCount() {
        if (!IRIS_PRESENT) return 1;
        return StereoState.INSTANCE.stereoEyeCount();
    }

    public static int currentEyeIndex() {
        return StereoState.INSTANCE.currentEyeIndex();
    }

    // --- Post-resize DH program uniform refresh ---
    //
    // A window resize leaves DH's Iris-side render programs with stale
    // program-lifetime uniforms: dh_terrain's viewWidth/viewHeight keep the
    // pre-resize values (no per-frame Iris uniform holder reaches them — the
    // DH programs only register fog/custom/builtin-replacement holders), so
    // the LOD distance-discard reconstructs huge view distances and LOD
    // terrain overdraws vanilla terrain. Rebuilding the whole pipeline fixes
    // it (that's what a DH toggle does) but costs a multi-second shader
    // recompile hang. Instead, surgically re-upload viewWidth / viewHeight /
    // aspectRatio on the DH programs the moment the framebuffer dims change —
    // a couple of glUniform1f calls, no hitch, no delay. Gated on stereo
    // being active (with stereo off the mod's target substitution is inert
    // and vanilla/Iris behavior applies).

    private static final boolean DH_PRESENT =
        FabricLoader.getInstance().isModLoaded("distanthorizons");

    private static int resizeLastW = -1;
    private static int resizeLastH = -1;

    /** Called once per frame from MixinGameRenderer's beginFrame HEAD. */
    public static void tickResizeWatch(int fbW, int fbH) {
        if (!IRIS_PRESENT || !DH_PRESENT) return;
        if (fbW <= 0 || fbH <= 0) return;
        boolean changed = (fbW != resizeLastW || fbH != resizeLastH) && resizeLastW != -1;
        resizeLastW = fbW;
        resizeLastH = fbH;
        if (!changed) return;
        if (!com.mitchellmarx.stereoscopic.core.StereoOptions.INSTANCE.mode.isActive()) return;
        try {
            irisRefreshDhViewportUniforms(fbW, fbH);
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("[stereo] DH viewport-uniform refresh after resize failed", t);
        }
    }

    private static void irisRefreshDhViewportUniforms(int fbW, int fbH) {
        net.irisshaders.iris.compat.dh.DHCompatInternal dh =
            com.mitchellmarx.stereoscopic.mixin.iris.LodRendererEventsInvoker.stereoscopic$getInstance();
        if (dh == null) return;
        int refreshed = 0;
        refreshed += stereoscopic$uploadViewport(dh.getSolidShader(), fbW, fbH);
        refreshed += stereoscopic$uploadViewport(dh.getTranslucentShader(), fbW, fbH);
        refreshed += stereoscopic$uploadViewport(dh.getShadowShader(), fbW, fbH);
        if (refreshed > 0) {
            Stereoscopic.LOG.info("[stereo] resize {}x{}: refreshed viewport uniforms on {} DH program(s)",
                fbW, fbH, refreshed);
        }
    }

    private static int stereoscopic$uploadViewport(
            net.irisshaders.iris.compat.dh.IrisLodRenderProgram program, int fbW, int fbH) {
        if (program == null) return 0;
        int id = ((com.mitchellmarx.stereoscopic.mixin.iris.IrisLodRenderProgramAccessor) (Object) program)
            .stereoscopic$getId();
        if (id == 0) return 0;
        int prev = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        try {
            org.lwjgl.opengl.GL20.glUseProgram(id);
            stereoscopic$setFloatUniform(id, "viewWidth", fbW);
            stereoscopic$setFloatUniform(id, "viewHeight", fbH);
            stereoscopic$setFloatUniform(id, "aspectRatio", (float) fbW / (float) fbH);
        } finally {
            org.lwjgl.opengl.GL20.glUseProgram(prev);
        }
        return 1;
    }

    private static void stereoscopic$setFloatUniform(int programId, String name, float value) {
        int loc = org.lwjgl.opengl.GL20.glGetUniformLocation(programId, name);
        if (loc >= 0) org.lwjgl.opengl.GL20.glUniform1f(loc, value);
    }

    /**
     * Force-rebuild the Iris shader pipeline because the stereo eye count just
     * changed (driven from the Sodium options-page Mode binding's
     * REQUIRES_RENDERER_RELOAD chain). Reloads the vanilla WorldRenderer too so
     * Sodium's chunk graph rebuilds with the new pipeline state.
     */
    public static void rebuildPipelineForStereoToggle() {
        if (!IRIS_PRESENT) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        try {
            irisReloadPipeline();
            // 26.2: allChanged() moved from LevelRenderer to LevelExtractor.
            if (mc.levelExtractor != null) mc.levelExtractor.allChanged();
        } catch (Throwable t) {
            Stereoscopic.LOG.error("Iris pipeline rebuild on stereo toggle failed", t);
        }
    }

    private static void irisReloadPipeline() throws Exception {
        net.irisshaders.iris.Iris.reload();
    }

    /**
     * Implemented by {@code MixinRenderTargets} via Mixin's interface
     * injection. {@link #setActiveEye(int)} dispatches the eye switch to the
     * registered instance, which rebinds its owned framebuffers' attachments
     * to the eye's sibling bank.
     */
    public interface EyeAwareRenderTargets {
        void stereoscopic$setActiveEye(int eyeIndex);
        int stereoscopic$getActiveEye();
        /**
         * Map a colortex-bank texture id (LEFT or RIGHT bank, MAIN or ALT) to the
         * active eye's equivalent. Used to remap statically-captured texture ids
         * (e.g., {@code FinalPassRenderer$SwapPass.targetTexture}) that aren't
         * re-bound by {@link #stereoscopic$setActiveEye(int)}'s framebuffer walk.
         * Returns the input unchanged if not a tracked bank texture or stereo is
         * inactive.
         */
        int stereoscopic$resolveActiveEyeTexId(int referenceTexId);
    }

    public static int getActiveEye() {
        if (!IRIS_PRESENT) return -1;
        EyeAwareRenderTargets t = activeTargets;
        if (t == null) return -1;
        try {
            return t.stereoscopic$getActiveEye();
        } catch (Throwable th) {
            return -1;
        }
    }

    private static volatile EyeAwareRenderTargets activeTargets;

    public static void registerActiveTargets(EyeAwareRenderTargets rt) {
        activeTargets = rt;
    }

    public static void unregisterActiveTargets(EyeAwareRenderTargets rt) {
        // Tolerate out-of-order destroy callbacks (an old RenderTargets being
        // destroyed after a newer one already registered).
        if (activeTargets == rt) activeTargets = null;
    }

    /**
     * True when Iris has built (and registered) a {@code RenderTargets} for the
     * current shader pack. This is a "do per-eye Iris targets exist?" check,
     * NOT a "is a deferred pipeline rendering this frame?" check — it stays
     * true between frames and across non-deferred passes as long as the Iris
     * targets remain registered. Callers use it to select the per-eye
     * world-pass strategy: Iris-targets case renders each eye full-frame into
     * its sibling colortex bank and the final pass scissor downscales to the
     * eye-half; vanilla (no Iris / no targets) case shares MC main FB and
     * needs classic eye-rect viewport + scissor.
     */
    public static boolean hasIrisRenderTargets() {
        return IRIS_PRESENT && activeTargets != null;
    }

    public static void setActiveEye(int eyeIndex) {
        if (!IRIS_PRESENT) return;
        EyeAwareRenderTargets t = activeTargets;
        if (t == null) return;
        try {
            t.stereoscopic$setActiveEye(eyeIndex);
        } catch (Throwable th) {
            Stereoscopic.LOG.warn("setActiveEye({}) failed; eye textures may desync this frame", eyeIndex, th);
        }
    }

    /**
     * Map a captured colortex texture id to the currently-active eye's equivalent.
     * No-op when Iris isn't present, no targets are registered, or the input id
     * isn't a tracked bank texture.
     */
    public static int resolveActiveEyeTexId(int referenceTexId) {
        if (!IRIS_PRESENT) return referenceTexId;
        EyeAwareRenderTargets t = activeTargets;
        if (t == null) return referenceTexId;
        try {
            return t.stereoscopic$resolveActiveEyeTexId(referenceTexId);
        } catch (Throwable th) {
            return referenceTexId;
        }
    }

    /**
     * No-op since per-eye PER_FRAME refresh moved to
     * {@code MixinProgramUniforms.stereoscopic$invalidateOnEyeSwitch}, which
     * triggers refresh on the eye-switch boundary inside Iris itself without
     * mutating {@code SystemTimeUniforms.COUNTER}.
     *
     * <p>Bumping COUNTER worked for matrix/camera refresh but also made the
     * shader-visible {@code frameCounter} (and {@code framemod2/4/8/600}, and
     * {@code goldenRatio * mod(float(frameCounter), 3600.0)} dither salts)
     * differ between eyes — every per-pixel stochastic branch landed on
     * opposite sides on LEFT vs RIGHT, producing per-eye intensity divergence
     * that surfaces as the persistent right-eye wash-out across all shader
     * presets (the dither sites are in the always-on dither / lightmap / fog
     * blocks, so disabling TAA / reflections / voxel lighting doesn't help).
     *
     * <p>Kept as a public symbol because
     * {@link com.mitchellmarx.stereoscopic.render.PerEyeRenderer#runForEachEye}
     * still calls it; turning it into a no-op is the safe minimum change.
     */
    public static void irisStartFrameBetweenEyes() {
        // Intentionally empty. See javadoc.
    }

    private static void irisDoStartFrame() {
        net.irisshaders.iris.uniforms.SystemTimeUniforms.COUNTER.beginFrame();
    }
}
