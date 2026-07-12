package com.mitchellmarx.stereoscopic.compat.sodium;

import com.mitchellmarx.stereoscopic.core.StereoState;

/**
 * Skip Sodium chunk-graph/upload on the RIGHT eye — LEFT's result feeds both
 * eyes. Correctness depends on two cooperating mechanisms (see
 * {@link com.mitchellmarx.stereoscopic.mixin.sodium.MixinSodiumWorldRenderer}):
 * <ol>
 *   <li>LEFT's {@code setupTerrain} runs from the mono camera position so the
 *       visible-section list it builds is centered between the two eyes.</li>
 *   <li>{@code MixinOcclusionCuller} widens the strict per-section frustum test
 *       by {@link com.mitchellmarx.stereoscopic.core.StereoMath#cullMargin} so
 *       that mono-centered list covers BOTH eyes' frusta — including the
 *       convergence shear tilt, which Sodium's fixed ~1.125-block pad does not
 *       absorb past ~140 blocks (issue #4).</li>
 * </ol>
 */
public final class SecondEyeSkipHooks {

    private SecondEyeSkipHooks() {}

    public static boolean shouldSkipChunkUploadThisFrame() {
        StereoState s = StereoState.INSTANCE;
        return s.isActive() && s.getCurrentEye() == StereoState.Eye.RIGHT;
    }
}
