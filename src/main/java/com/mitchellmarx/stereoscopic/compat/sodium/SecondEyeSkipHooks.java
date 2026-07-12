package com.mitchellmarx.stereoscopic.compat.sodium;

import com.mitchellmarx.stereoscopic.core.StereoState;

/**
 * Skip Sodium chunk-graph/upload on the RIGHT eye — LEFT's result feeds both
 * eyes. The RIGHT eye reuses LEFT's cull list, and that list is built from a
 * frustum baked entirely from the LEFT eye (both origin and convergence-shear
 * tilt — see {@code MixinOcclusionCuller} for the bytecode trace). So the RIGHT
 * eye diverges from the cull frustum by the FULL eye separation, which
 * {@code MixinOcclusionCuller} covers by widening the strict per-section frustum
 * test by {@link com.mitchellmarx.stereoscopic.core.StereoMath#cullMargin}
 * ({@code ipd + (ipd/convergence)·dist}) — Sodium's fixed ~1.125-block pad does
 * not absorb the shear tilt past ~66 blocks (issue #4). The mono-position
 * restore in
 * {@link com.mitchellmarx.stereoscopic.mixin.sodium.MixinSodiumWorldRenderer}
 * only affects the section-manager's camera state, not this frustum test.
 */
public final class SecondEyeSkipHooks {

    private SecondEyeSkipHooks() {}

    public static boolean shouldSkipChunkUploadThisFrame() {
        StereoState s = StereoState.INSTANCE;
        return s.isActive() && s.getCurrentEye() == StereoState.Eye.RIGHT;
    }
}
