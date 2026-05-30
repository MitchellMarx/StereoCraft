package com.mitchellmarx.stereoscopic.compat.sodium;

import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.core.StereoState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks in the contract of {@link SecondEyeSkipHooks#shouldSkipChunkUploadThisFrame()}.
 *
 * <p>This predicate has been flipped 4 times in git history (4d291f4 → e1870c0 revert →
 * 36bfd59 re-revert → 1e88b99 final form). The intended semantics: skip Sodium's chunk
 * upload only on the RIGHT eye of an active stereo frame — LEFT's result feeds both eyes.
 */
class SecondEyeSkipHooksTest {

    @BeforeEach
    void resetOptions() {
        StereoOptions.INSTANCE.mode = StereoMode.OFF;
        StereoOptions.INSTANCE.ipd = 0.064f;
        StereoState.INSTANCE.endFrame();
    }

    @Test
    void returnsFalseWhenStereoInactive() {
        // mode OFF, no beginFrame: predicate must not skip anything.
        assertFalse(SecondEyeSkipHooks.shouldSkipChunkUploadThisFrame(),
            "with stereo off and no active frame, chunk upload must run normally");
    }

    @Test
    void returnsFalseOnLeftEye() {
        StereoOptions.INSTANCE.mode = StereoMode.SBS_HALF;
        StereoState.INSTANCE.beginFrame(1920, 1080);
        StereoState.INSTANCE.setEye(StereoState.Eye.LEFT);

        assertFalse(SecondEyeSkipHooks.shouldSkipChunkUploadThisFrame(),
            "LEFT eye must run the chunk-upload pass — its result feeds both eyes");
    }

    @Test
    void returnsTrueOnRightEye() {
        StereoOptions.INSTANCE.mode = StereoMode.SBS_HALF;
        StereoState.INSTANCE.beginFrame(1920, 1080);
        StereoState.INSTANCE.setEye(StereoState.Eye.RIGHT);

        assertTrue(SecondEyeSkipHooks.shouldSkipChunkUploadThisFrame(),
            "RIGHT eye must skip the chunk-upload pass — LEFT already produced an identical result");
    }

    @Test
    void returnsFalseOnMonoEye() {
        StereoOptions.INSTANCE.mode = StereoMode.SBS_HALF;
        StereoState.INSTANCE.beginFrame(1920, 1080);
        StereoState.INSTANCE.setEye(StereoState.Eye.MONO);

        assertFalse(SecondEyeSkipHooks.shouldSkipChunkUploadThisFrame(),
            "MONO eye must run the chunk-upload pass — nothing else will");
    }
}
