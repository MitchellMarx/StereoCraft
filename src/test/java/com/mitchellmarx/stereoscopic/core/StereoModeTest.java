package com.mitchellmarx.stereoscopic.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StereoModeTest {

    @Test
    void valueOf_round_trips_for_every_constant() {
        for (StereoMode m : StereoMode.values()) {
            assertEquals(m, StereoMode.valueOf(m.name()));
        }
    }

    @Test
    void valueOf_rejects_unknown_name() {
        assertThrows(IllegalArgumentException.class, () -> StereoMode.valueOf("NOPE"));
    }

    @Test
    void isActive_only_for_non_off() {
        for (StereoMode m : StereoMode.values()) {
            assertEquals(m != StereoMode.OFF, m.isActive());
        }
    }

    @Test
    void isHalf_and_isSideBySide_true_only_for_SBS_HALF() {
        assertEquals(true, StereoMode.SBS_HALF.isHalf());
        assertEquals(true, StereoMode.SBS_HALF.isSideBySide());
        assertEquals(false, StereoMode.OFF.isHalf());
        assertEquals(false, StereoMode.OFF.isSideBySide());
    }
}
