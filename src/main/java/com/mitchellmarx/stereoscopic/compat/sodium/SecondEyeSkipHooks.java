package com.mitchellmarx.stereoscopic.compat.sodium;

import com.mitchellmarx.stereoscopic.core.StereoState;

/**
 * Skip Sodium chunk-graph/upload on the RIGHT eye — LEFT's result feeds both
 * eyes. Correctness depends on two cooperating mechanisms (see
 * {@link com.mitchellmarx.stereoscopic.mixin.sodium.MixinSodiumWorldRenderer}):
 * <ol>
 *   <li>LEFT's {@code setupTerrain} runs from the mono camera position so the
 *       visible-section list it builds covers both eyes' direct frustum reads.</li>
 *   <li>Sodium's Viewport carries a section-padded frustum margin much larger
 *       than IPD/2 (~6cm), absorbing the per-eye frustum offset at chunk
 *       granularity.</li>
 * </ol>
 */
public final class SecondEyeSkipHooks {

    private SecondEyeSkipHooks() {}

    public static boolean shouldSkipChunkUploadThisFrame() {
        StereoState s = StereoState.INSTANCE;
        return s.isActive() && s.getCurrentEye() == StereoState.Eye.RIGHT;
    }
}
