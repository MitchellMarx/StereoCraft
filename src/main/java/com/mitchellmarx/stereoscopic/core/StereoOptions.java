package com.mitchellmarx.stereoscopic.core;

import net.neoforged.fml.loading.FMLPaths;
import java.nio.file.Path;

public final class StereoOptions {
    public static final StereoOptions INSTANCE = new StereoOptions();

    public StereoMode mode = StereoMode.OFF;
    public float ipd = 0.064f;       // meters

    /**
     * Off-axis convergence distance in blocks. Objects at this depth sit at
     * the screen plane (zero parallax); closer pops out, farther recedes.
     * 0 = parallel-axis (no frustum shear).
     *
     * <p>Default 4.0 ships off-axis ON, diverging from the Angelica reference
     * which uses parallel-axis. Angelica's rationale was "asymmetric-frustum
     * gap between eyes" - in practice that artifact appears when the shear is
     * large enough that the LEFT eye's right frustum edge and the RIGHT eye's
     * left frustum edge don't overlap in screen space, leaving a vertical
     * band where each eye only sees one side. At 64mm IPD and 4-block
     * convergence the per-eye shear is ~0.8% of the half-FOV, well under the
     * visible-gap threshold. The default was chosen so first-time users get
     * comfortable depth without having to discover an off-by-default option;
     * users who notice the gap on wide-FOV setups can drop to 0 for
     * parallel-axis behavior matching the reference.
     */
    public float convergence = 4.0f;

    /**
     * Swap which eye-render goes to which physical eye. Some SBS displays /
     * VR passthrough virtual-monitor setups display the SBS halves in the
     * opposite eye-assignment than the standard "left half → left eye"
     * convention. Toggle this if the world looks like it's behind the screen
     * when it should pop out (or vice-versa), or if reflections / parallax
     * feel swapped between eyes.
     */
    public boolean swapEyes = false;

    StereoOptions() {} // package-private for tests; production code uses INSTANCE

    public Path configFile() {
        return FMLPaths.CONFIGDIR.get().resolve("stereoscopic-options.json");
    }

    public void save() { StereoOptionsIO.writeTo(configFile(), this); }
    public void load() { StereoOptionsIO.readInto(configFile(), this); }
}
