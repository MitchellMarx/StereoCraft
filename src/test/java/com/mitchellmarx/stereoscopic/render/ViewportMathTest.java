// src/test/java/com/mitchellmarx/stereoscopic/render/ViewportMathTest.java
package com.mitchellmarx.stereoscopic.render;

import com.mitchellmarx.stereoscopic.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ViewportMathTest {
    @Test void offIsFullScreen() {
        ViewportMath.Rect r = ViewportMath.eyeRect(StereoState.Eye.MONO, StereoMode.OFF, 1920, 1080);
        assertEquals(0, r.x()); assertEquals(0, r.y());
        assertEquals(1920, r.w()); assertEquals(1080, r.h());
    }
    @Test void sbsHalfLeft() {
        ViewportMath.Rect r = ViewportMath.eyeRect(StereoState.Eye.LEFT, StereoMode.SBS_HALF, 1920, 1080);
        assertEquals(0, r.x()); assertEquals(960, r.w());
    }
    @Test void sbsHalfRight() {
        ViewportMath.Rect r = ViewportMath.eyeRect(StereoState.Eye.RIGHT, StereoMode.SBS_HALF, 1920, 1080);
        assertEquals(960, r.x()); assertEquals(960, r.w());
    }
    @Test void sbsFullUsesSameHalves() {
        // FULL mode differs from HALF in projection aspect + GUI layout only;
        // the destination half-rects are identical.
        ViewportMath.Rect l = ViewportMath.eyeRect(StereoState.Eye.LEFT, StereoMode.SBS_FULL, 3840, 1080);
        ViewportMath.Rect r = ViewportMath.eyeRect(StereoState.Eye.RIGHT, StereoMode.SBS_FULL, 3840, 1080);
        assertEquals(0, l.x()); assertEquals(1920, l.w());
        assertEquals(1920, r.x()); assertEquals(1920, r.w());
    }
    @Test void oddWidthRoundsDown() {
        // If fbW is odd, both halves round down — right eye is identical width.
        ViewportMath.Rect r = ViewportMath.eyeRect(StereoState.Eye.RIGHT, StereoMode.SBS_HALF, 1921, 1080);
        assertEquals(960, r.x());
        assertEquals(960, r.w());
    }
}
