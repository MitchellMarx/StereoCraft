package com.mitchellmarx.stereoscopic.core;

/**
 * <ul>
 *   <li>{@code SBS_HALF} — half-width SBS for displays that stretch each half
 *       back to full width (3D TVs/monitors in "side by side" mode). Each eye
 *       renders at the full-window aspect and is squeezed 2:1 into its half.</li>
 *   <li>{@code SBS_FULL} — full SBS for displays/receivers that present each
 *       half as-is at per-eye aspect (double-wide desktops like 3840×1080, or
 *       full-SBS 3D inputs). Each eye renders at {@code (W/2)/H} aspect and
 *       its half is proportionally correct without any display stretch.</li>
 * </ul>
 */
public enum StereoMode {
    OFF, SBS_HALF, SBS_FULL;

    public boolean isActive()      { return this != OFF; }
    public boolean isSideBySide()  { return this != OFF; }
    public boolean isHalf()        { return this == SBS_HALF; }
    public boolean isFull()        { return this == SBS_FULL; }
}
