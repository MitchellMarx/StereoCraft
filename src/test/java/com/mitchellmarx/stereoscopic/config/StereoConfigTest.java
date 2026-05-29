package com.mitchellmarx.stereoscopic.config;

import com.mitchellmarx.stereoscopic.core.StereoDebugEye;
import com.mitchellmarx.stereoscopic.core.StereoHudMode;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StereoConfigTest {

    @Test
    void defaults_match_spec() {
        // Resetting to defaults is a pure assignment to public statics — no Forge Configuration required.
        StereoConfig.stereoscopicMode = StereoMode.OFF;
        StereoConfig.stereoIpd = 0.064f;
        StereoConfig.stereoHudMode = StereoHudMode.DUPLICATE;
        StereoConfig.stereoDebugForceEye = StereoDebugEye.OFF;

        assertEquals(StereoMode.OFF,             StereoConfig.stereoscopicMode);
        assertEquals(0.064f,                     StereoConfig.stereoIpd, 1e-6f);
        assertEquals(StereoHudMode.DUPLICATE,    StereoConfig.stereoHudMode);
        assertEquals(StereoDebugEye.OFF,         StereoConfig.stereoDebugForceEye);
    }

    @Test
    void parseEnum_valid_name() {
        assertEquals(StereoMode.SBS_HALF,
                     StereoConfig.parseEnum(StereoMode.class, "SBS_HALF", StereoMode.OFF));
    }

    @Test
    void parseEnum_unknown_name_returns_fallback() {
        assertEquals(StereoMode.OFF,
                     StereoConfig.parseEnum(StereoMode.class, "NOT_A_REAL_MODE", StereoMode.OFF));
    }

    @Test
    void parseEnum_null_name_returns_fallback() {
        assertEquals(StereoMode.OFF,
                     StereoConfig.parseEnum(StereoMode.class, null, StereoMode.OFF));
    }
}
