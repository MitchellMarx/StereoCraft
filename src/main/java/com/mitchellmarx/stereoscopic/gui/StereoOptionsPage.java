package com.mitchellmarx.stereoscopic.gui;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Sodium options-page integration via the public Sodium Config API. Registered
 * through the {@code sodium:config_api_user} entrypoint in fabric.mod.json.
 * Reference: {@code common/src/api/java/net/caffeinemc/mods/sodium/api/config/USAGE.md}
 * on Sodium's 1.21.11/stable branch.
 */
public final class StereoOptionsPage implements ConfigEntryPoint {

    private static final String MODID = Stereoscopic.MOD_ID;

    private static final Identifier ID_MODE        = Identifier.of(MODID, "mode");
    private static final Identifier ID_IPD         = Identifier.of(MODID, "ipd");
    private static final Identifier ID_CONVERGENCE = Identifier.of(MODID, "convergence");
    private static final Identifier ID_SWAP_EYES   = Identifier.of(MODID, "swap_eyes");

    private final StorageEventHandler storageFlush = () -> {
        try { StereoOptions.INSTANCE.save(); }
        catch (Throwable t) { Stereoscopic.LOG.error("StereoOptions save failed", t); }
    };

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
            .setIcon(Identifier.of(MODID, "icon.png"))
            .addPage(builder.createOptionPage()
                .setName(Text.translatable("stereoscopic.options.group.name"))
                .addOptionGroup(builder.createOptionGroup()
                    .addOption(builder.createEnumOption(ID_MODE, StereoMode.class)
                        .setName(Text.translatable("stereoscopic.options.mode.name"))
                        .setTooltip(Text.translatable("stereoscopic.options.mode.tooltip"))
                        .setElementNameProvider(StereoOptionsPage::modeName)
                        .setDefaultValue(StereoMode.OFF)
                        .setBinding(StereoOptionsPage::applyModeChange,
                                    () -> StereoOptions.INSTANCE.mode)
                        .setImpact(OptionImpact.HIGH)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setStorageHandler(storageFlush))
                    .addOption(builder.createIntegerOption(ID_IPD)
                        .setName(Text.translatable("stereoscopic.options.ipd.name"))
                        .setTooltip(Text.translatable("stereoscopic.options.ipd.tooltip"))
                        // Upper bound is stereo STRENGTH, not anatomical IPD. Real human IPD is
                        // ~55-77mm (avg ~63); 100mm allows mild hyperstereo (exaggerated depth /
                        // strong SBS pop) while staying fusible for most people.
                        .setRange(55, 100, 1)
                        .setValueFormatter(mm -> Text.literal(String.format("%.3f m", mm / 1000.0)))
                        .setDefaultValue(64) // 0.064 m
                        .setBinding(mm -> StereoOptions.INSTANCE.ipd = clampedMetersFromMm(mm),
                                    () -> Math.round(StereoOptions.INSTANCE.ipd * 1000f))
                        .setStorageHandler(storageFlush))
                    .addOption(builder.createIntegerOption(ID_CONVERGENCE)
                        .setName(Text.translatable("stereoscopic.options.convergence.name"))
                        .setTooltip(Text.translatable("stereoscopic.options.convergence.tooltip"))
                        .setRange(0, 16, 1)
                        .setValueFormatter(b -> b == 0
                            ? Text.translatable("stereoscopic.options.convergence.off")
                            : Text.translatable("stereoscopic.options.convergence.blocks", b))
                        .setDefaultValue(4)
                        .setBinding(b -> StereoOptions.INSTANCE.convergence = clampedConvergence(b),
                                    () -> Math.round(StereoOptions.INSTANCE.convergence))
                        .setImpact(OptionImpact.LOW)
                        .setStorageHandler(storageFlush))
                    .addOption(builder.createBooleanOption(ID_SWAP_EYES)
                        .setName(Text.translatable("stereoscopic.options.swap_eyes.name"))
                        .setTooltip(Text.translatable("stereoscopic.options.swap_eyes.tooltip"))
                        .setDefaultValue(false)
                        .setBinding(v -> StereoOptions.INSTANCE.swapEyes = v,
                                    () -> StereoOptions.INSTANCE.swapEyes)
                        .setImpact(OptionImpact.LOW)
                        .setStorageHandler(storageFlush))
                ));
    }

    /**
     * Order matters: assign mode first, then trigger the rebuild —
     * StereoState.stereoEyeCount() reads StereoOptions.mode directly (not the
     * per-frame cache), so Iris pipeline init inside Iris.reload() sees the
     * new value synchronously. Sodium's REQUIRES_RENDERER_RELOAD flag drives
     * Sodium's own reload but doesn't cascade into Iris.reload() in 1.10.7,
     * so we drive it here.
     */
    private static void applyModeChange(StereoMode v) {
        if (StereoOptions.INSTANCE.mode == v) return;
        StereoOptions.INSTANCE.mode = v;
        PerEyeRenderTargetHooks.rebuildPipelineForStereoToggle();
    }

    private static float clampedMetersFromMm(int mm) {
        int clamped = Math.max(55, Math.min(100, mm));
        return clamped / 1000.0f;
    }

    private static float clampedConvergence(int blocks) {
        return Math.max(0, Math.min(16, blocks));
    }

    private static Text modeName(StereoMode m) {
        return switch (m) {
            case OFF      -> Text.translatable("stereoscopic.options.mode.off");
            case SBS_HALF -> Text.translatable("stereoscopic.options.mode.sbs_half");
        };
    }
}
