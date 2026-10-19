package com.mitchellmarx.stereoscopic.gui.sodium;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import net.caffeinemc.mods.sodium.client.gui.options.storage.OptionStorage;

/**
 * Sodium {@link OptionStorage} wrapping our singleton {@link StereoOptions}.
 * Sodium's option binding calls {@link #getData()} to read each frame's
 * current values; {@code save()} fires when the user clicks Apply on the
 * Sodium options screen.
 */
public final class StereoOptionStorage implements OptionStorage<StereoOptions> {

    public static final StereoOptionStorage INSTANCE = new StereoOptionStorage();

    // Set by the mode binding when the applied value differs from the current
    // one; consumed by save() so screens are re-laid-out only on a real change.
    private boolean modeChanged = false;

    private StereoOptionStorage() {}

    @Override
    public StereoOptions getData() {
        return StereoOptions.INSTANCE;
    }

    /** Mode-option binding setter: assigns the mode and records whether it changed. */
    void applyMode(StereoOptions s, StereoMode v) {
        if (s.mode == v) return;
        s.mode = v;
        modeChanged = true;
    }

    @Override
    public void save() {
        StereoOptions.INSTANCE.save();
        // Trigger Iris pipeline rebuild so the eye-count change picks up.
        PerEyeRenderTargetHooks.rebuildPipelineForStereoToggle();
        if (modeChanged) {
            modeChanged = false;
            // SBS_FULL halves the GUI layout width (MixinWindow); open screens
            // cache their dimensions at init, so re-layout them for the new mode.
            // 1.21.1 has no resizeGui(); resizeDisplay() is what calls screen.resize.
            try {
                net.minecraft.client.Minecraft.getInstance().resizeDisplay();
            } catch (Throwable t) {
                Stereoscopic.LOG.warn("resizeDisplay after stereo mode change failed", t);
            }
        }
    }
}
