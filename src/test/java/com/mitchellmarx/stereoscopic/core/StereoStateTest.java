package com.mitchellmarx.stereoscopic.core;

import com.mitchellmarx.stereoscopic.config.StereoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StereoStateTest {

    @BeforeEach
    void resetConfig() {
        StereoConfig.stereoscopicMode    = StereoMode.OFF;
        StereoConfig.stereoIpd           = 0.064f;
        // Reset transient state on the singleton too.
        StereoState.INSTANCE.beginFrame();
        StereoState.INSTANCE.endFrame();
    }

    @Test
    void isActive_false_when_mode_off() {
        StereoConfig.stereoscopicMode = StereoMode.OFF;
        assertFalse(StereoState.INSTANCE.beginFrame());
        assertFalse(StereoState.INSTANCE.isActive());
    }

    @Test
    void isActive_true_when_mode_sbs_half() {
        StereoConfig.stereoscopicMode = StereoMode.SBS_HALF;
        assertTrue(StereoState.INSTANCE.beginFrame());
        assertTrue(StereoState.INSTANCE.isActive());
    }

    @Test
    void getEyeOffset_sign_LEFT_positive_RIGHT_negative() {
        StereoConfig.stereoscopicMode = StereoMode.SBS_HALF;
        StereoConfig.stereoIpd = 0.064f;
        StereoState.INSTANCE.beginFrame();

        StereoState.INSTANCE.setEye(StereoState.Eye.LEFT);
        assertEquals(+0.032f, StereoState.INSTANCE.getEyeOffset(), 1e-6f,
            "LEFT eye must use +ipd/2 (sbs2 convention; do not flip)");

        StereoState.INSTANCE.setEye(StereoState.Eye.RIGHT);
        assertEquals(-0.032f, StereoState.INSTANCE.getEyeOffset(), 1e-6f,
            "RIGHT eye must use -ipd/2");
    }

    @Test
    void getEyeOffset_zero_when_inactive() {
        StereoConfig.stereoscopicMode = StereoMode.OFF;
        StereoState.INSTANCE.beginFrame();
        StereoState.INSTANCE.setEye(StereoState.Eye.LEFT);
        assertEquals(0f, StereoState.INSTANCE.getEyeOffset(), 1e-6f);
    }

    @Test
    void getEyeOffset_zero_when_eye_is_mono() {
        StereoConfig.stereoscopicMode = StereoMode.SBS_HALF;
        StereoState.INSTANCE.beginFrame();
        StereoState.INSTANCE.setEye(StereoState.Eye.MONO);
        assertEquals(0f, StereoState.INSTANCE.getEyeOffset(), 1e-6f);
    }

    @Test
    void beginFrame_snapshots_mode_and_ipd() {
        StereoConfig.stereoscopicMode = StereoMode.SBS_HALF;
        StereoConfig.stereoIpd = 0.080f;
        StereoState.INSTANCE.beginFrame();
        assertEquals(StereoMode.SBS_HALF, StereoState.INSTANCE.getFrameMode());
        assertEquals(0.080f, StereoState.INSTANCE.getFrameIpd(), 1e-6f);
    }

    @Test
    void stereoEyeCount_reads_config_not_cache() {
        // Pipeline-init can happen before first beginFrame() — stereoEyeCount() must read
        // config directly, not the cached active flag. Guards memory note about iris init order.
        StereoConfig.stereoscopicMode = StereoMode.SBS_HALF;
        // Deliberately do NOT call beginFrame() — eye count must already be 2.
        assertEquals(2, StereoState.INSTANCE.stereoEyeCount());

        StereoConfig.stereoscopicMode = StereoMode.OFF;
        assertEquals(1, StereoState.INSTANCE.stereoEyeCount());
    }
}
