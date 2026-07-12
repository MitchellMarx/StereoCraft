package com.mitchellmarx.stereoscopic.core;

/**
 * Stereoscopic output layout. Both side-by-side modes split the display left/right (each eye
 * gets a {@code fullW/2 × fullH} half); they differ only in the per-eye aspect and GUI layout:
 *
 * <ul>
 *   <li>{@code SBS_HALF} — each eye renders at the FULL window aspect ({@code W/H}) and is
 *       squeezed 2:1 into its half. For 3D TVs / monitors in "side by side" mode, which stretch
 *       each half back to full width.</li>
 *   <li>{@code SBS_FULL} — each eye renders at the per-eye aspect ({@code (W/2)/H}) and its half
 *       is proportionally correct with no display stretch. For double-wide desktops (e.g.
 *       3840×1080) and full-SBS receivers that present each half as-is.</li>
 * </ul>
 *
 * <p>Over-under (OU_HALF/OU_FULL) prototype variants are not part of the canonical feature set
 * and are not supported.
 */
public enum StereoMode {
    OFF,
    SBS_HALF,
    SBS_FULL;

    public boolean isActive() {
        return this != OFF;
    }

    public boolean isSideBySide() {
        return this != OFF;
    }

    public boolean isHalf() {
        return this == SBS_HALF;
    }

    public boolean isFull() {
        return this == SBS_FULL;
    }
}
