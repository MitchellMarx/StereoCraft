package com.mitchellmarx.stereoscopic.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StereoModeTest {
    @Test void offIsInactive()        { assertFalse(StereoMode.OFF.isActive()); }
    @Test void sbsHalfIsActive()      { assertTrue(StereoMode.SBS_HALF.isActive()); }
    @Test void sbsHalfIsSideBySide()  { assertTrue(StereoMode.SBS_HALF.isSideBySide()); }
    @Test void sbsHalfIsHalf()        { assertTrue(StereoMode.SBS_HALF.isHalf()); }
    @Test void offIsNotSideBySide()   { assertFalse(StereoMode.OFF.isSideBySide()); }
}
