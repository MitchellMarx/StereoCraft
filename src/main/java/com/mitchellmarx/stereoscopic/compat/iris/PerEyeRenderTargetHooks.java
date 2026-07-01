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
            if (mc.levelRenderer != null) mc.levelRenderer.allChanged();
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
