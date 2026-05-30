package com.mitchellmarx.stereoscopic.gui.sodium;

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

    private StereoOptionStorage() {}

    @Override
    public StereoOptions getData() {
        return StereoOptions.INSTANCE;
    }

    @Override
    public void save() {
        StereoOptions.INSTANCE.save();
        // Trigger Iris pipeline rebuild so the eye-count change picks up.
        PerEyeRenderTargetHooks.rebuildPipelineForStereoToggle();
    }
}
