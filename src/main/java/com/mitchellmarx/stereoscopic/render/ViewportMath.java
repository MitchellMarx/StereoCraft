// src/main/java/com/mitchellmarx/stereoscopic/render/ViewportMath.java
package com.mitchellmarx.stereoscopic.render;

import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.core.StereoState;

public final class ViewportMath {
    private ViewportMath() {}

    public record Rect(int x, int y, int w, int h) {}

    /** Compute eye viewport rect in main-FB pixel space. With {@link
     * StereoOptions#swapEyes} set, swaps which eye lands in which half. */
    public static Rect eyeRect(StereoState.Eye eye, StereoMode mode, int fbW, int fbH) {
        if (mode == StereoMode.SBS_HALF) {
            int half = fbW / 2;
            boolean leftHalf;
            if (StereoOptions.INSTANCE.swapEyes) {
                leftHalf = (eye == StereoState.Eye.RIGHT);
            } else {
                leftHalf = (eye == StereoState.Eye.LEFT || eye == StereoState.Eye.MONO);
            }
            return leftHalf ? new Rect(0, 0, half, fbH) : new Rect(half, 0, half, fbH);
        }
        return new Rect(0, 0, fbW, fbH);
    }
}
