package com.mitchellmarx.stereoscopic.config;

import com.mitchellmarx.stereoscopic.core.StereoDebugEye;
import com.mitchellmarx.stereoscopic.core.StereoHudMode;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * Replaces the AngelicaConfig fields that sbs2 reads from for stereo state.
 *
 * <p>Persisted to {@code config/stereoscopic.cfg} via Forge's {@link Configuration}:
 * <ul>
 *   <li>{@link #stereoscopicMode} — Sodium UI tick-box (OFF ↔ SBS_HALF)
 *   <li>{@link #stereoIpd} — Sodium UI integer slider in mm, persisted as meters
 *   <li>{@link #stereoConvergence} — Sodium UI integer slider in blocks; off-axis convergence
 *       distance (where each eye's frustum lines up at zero parallax)
 *   <li>{@link #stereoSwapEyes} — Sodium UI tick-box; swaps which eye-render goes to which
 *       physical eye, for SBS displays / VR passthrough setups with reversed half-assignment
 * </ul>
 *
 * <p>Internal runtime state (not persisted, no UI — per spec §"Config & state"):
 * <ul>
 *   <li>{@link #stereoHudMode} — default DUPLICATE; flipped at runtime by debug code
 *   <li>{@link #stereoDebugForceEye} — default OFF; flipped at runtime by debug code
 * </ul>
 */
public final class StereoConfig {

    private static final Logger LOGGER = LogManager.getLogger("StereoscopicConfig");

    public static volatile StereoMode      stereoscopicMode    = StereoMode.OFF;
    public static volatile float           stereoIpd           = 0.064f;
    public static volatile float           stereoConvergence   = 4.0f;
    public static volatile boolean         stereoSwapEyes      = false;
    public static volatile StereoHudMode   stereoHudMode       = StereoHudMode.DUPLICATE;
    public static volatile StereoDebugEye  stereoDebugForceEye = StereoDebugEye.OFF;

    private static Configuration config;

    private StereoConfig() {}

    public static void load(File file) {
        config = new Configuration(file);
        try {
            config.load();
            read();
        } catch (Throwable t) {
            LOGGER.warn("Failed to load stereoscopic config; using in-memory defaults", t);
        }
        try {
            if (config.hasChanged()) config.save();
        } catch (Throwable t) {
            LOGGER.warn("Failed to write stereoscopic config", t);
        }
    }

    private static void read() {
        final String modeName = config.getString(
            "stereoscopicMode", "general", StereoMode.OFF.name(),
            "Stereo layout. Values: OFF, SBS_HALF, SBS_FULL, OU_HALF, OU_FULL. Default OFF.");
        stereoscopicMode = parseEnum(StereoMode.class, modeName, StereoMode.OFF);

        stereoIpd = (float) config.get("general", "stereoIpd", 0.064,
            "Eye separation (interpupillary distance) in meters. Default 0.064.").getDouble();

        stereoConvergence = (float) config.get("general", "stereoConvergence", 4.0,
            "Off-axis convergence distance in blocks. Objects at this depth sit at the screen plane "
            + "(zero parallax). Closer objects pop out, farther objects recede. Default 4.0.")
            .getDouble();

        stereoSwapEyes = config.get("general", "stereoSwapEyes", false,
            "Swap which eye-render goes to which physical eye. Toggle if depth looks inverted "
            + "(world appears behind the screen when it should pop out) on your SBS display / "
            + "VR passthrough setup. Default false.").getBoolean();
    }

    public static void save() {
        if (config == null) {
            LOGGER.warn("StereoConfig.save() called before load() — setting not persisted");
            return;
        }
        config.get("general", "stereoscopicMode", StereoMode.OFF.name()).set(stereoscopicMode.name());
        config.get("general", "stereoIpd", 0.064).set((double) stereoIpd);
        config.get("general", "stereoConvergence", 4.0).set((double) stereoConvergence);
        config.get("general", "stereoSwapEyes", false).set(stereoSwapEyes);
        if (config.hasChanged()) config.save();
    }

    static <E extends Enum<E>> E parseEnum(Class<E> cls, String name, E fallback) {
        try { return Enum.valueOf(cls, name); }
        catch (Exception e) { return fallback; }
    }
}
