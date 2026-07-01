package com.mitchellmarx.stereoscopic.core;

/**
 * Stereoscopic output layout. SBS_HALF splits the display left/right, rendering each eye at half
 * the horizontal dimension (native aspect) — what SBS-3D viewers expect. OFF disables stereo.
 *
 * <p>Earlier prototypes also carried SBS_FULL and over-under (OU_HALF/OU_FULL) variants; those
 * were prototyping scaffolding and are not part of the canonical feature set. Over-under is not
 * supported. The {@link #isSideBySide()} / {@link #isHalf()} helpers are retained so the eye-rect
 * math in the renderer reads the same as the other ports.
 */
public enum StereoMode {
    OFF,
    SBS_HALF;

    public boolean isActive() {
        return this != OFF;
    }

    public boolean isSideBySide() {
        return this == SBS_HALF;
    }

    public boolean isHalf() {
        return this == SBS_HALF;
    }
}
