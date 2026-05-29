package com.mitchellmarx.stereoscopic.compat.iris;

import com.gtnewhorizons.angelica.config.AngelicaConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Facade for per-eye Iris {@code RenderTargets} banking. {@code MixinRenderTargets} registers
 * itself here on construction; {@code MixinEntityRenderer_Stereo} dispatches LEFT/RIGHT switches
 * through {@link #setActiveEye(int)}; {@code MixinFinalPassRenderer} routes the SwapPass target
 * texture through {@link #resolveActiveEyeTexId(int)}.
 *
 * <p>All entry points are no-ops when Iris is disabled in Angelica config or no shader pipeline
 * has built a {@code RenderTargets} yet (between shader reloads, on the main menu, etc.).
 *
 * <p>Threading: registration/dispatch is render-thread only; the {@code volatile} on
 * {@code activeTargets} guards against torn reads across the cursor thread which can asynchronously
 * touch GL but never touches RenderTargets.
 */
public final class PerEyeRenderTargetHooks {

    private static final Logger LOGGER = LogManager.getLogger("StereoscopicPerEyeRT");

    private PerEyeRenderTargetHooks() {}

    private static volatile EyeAwareRenderTargets activeTargets;

    /**
     * Implemented by {@code MixinRenderTargets} via interface injection. Distinguishes the
     * banking-enabled RenderTargets instance from arbitrary Iris pipeline state.
     */
    public interface EyeAwareRenderTargets {
        void stereoscopic$setActiveEye(int eyeIndex);
        int stereoscopic$getActiveEye();

        /**
         * Map a colortex-bank texture id (LEFT or RIGHT bank, MAIN or ALT) to the active eye's
         * equivalent. Used to remap statically-captured texture ids (e.g.
         * {@code FinalPassRenderer$SwapPass.targetTexture}) that aren't re-bound by the per-eye
         * framebuffer-attachment walk in {@link #stereoscopic$setActiveEye(int)}. Returns the
         * input unchanged when the id isn't a tracked bank texture or banking is disabled.
         */
        int stereoscopic$resolveActiveEyeTexId(int referenceTexId);
    }

    public static void registerActiveTargets(EyeAwareRenderTargets rt) {
        activeTargets = rt;
    }

    /** Tolerates out-of-order destroy callbacks: only clears when the destroyed instance was active. */
    public static void unregisterActiveTargets(EyeAwareRenderTargets rt) {
        if (activeTargets == rt) activeTargets = null;
    }

    /**
     * True when a banking-enabled Iris {@code RenderTargets} exists for the current shader pack.
     * Callers use this to gate per-eye logic that depends on Iris having built its target bank.
     */
    public static boolean hasIrisRenderTargets() {
        if (!AngelicaConfig.enableIris) return false;
        return activeTargets != null;
    }

    public static void setActiveEye(int eyeIndex) {
        if (!AngelicaConfig.enableIris) return;
        final EyeAwareRenderTargets t = activeTargets;
        if (t == null) return;
        try {
            t.stereoscopic$setActiveEye(eyeIndex);
        } catch (Throwable th) {
            LOGGER.warn("setActiveEye({}) failed; eye textures may desync this frame", eyeIndex, th);
        }
    }

    public static int getActiveEye() {
        if (!AngelicaConfig.enableIris) return -1;
        final EyeAwareRenderTargets t = activeTargets;
        if (t == null) return -1;
        try {
            return t.stereoscopic$getActiveEye();
        } catch (Throwable th) {
            return -1;
        }
    }

    public static int resolveActiveEyeTexId(int referenceTexId) {
        if (!AngelicaConfig.enableIris) return referenceTexId;
        final EyeAwareRenderTargets t = activeTargets;
        if (t == null) return referenceTexId;
        try {
            return t.stereoscopic$resolveActiveEyeTexId(referenceTexId);
        } catch (Throwable th) {
            return referenceTexId;
        }
    }
}
