package com.mitchellmarx.stereoscopic.gui.sodium;

import com.google.common.collect.ImmutableList;
import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import net.caffeinemc.mods.sodium.client.gui.options.OptionFlag;
import net.caffeinemc.mods.sodium.client.gui.options.OptionGroup;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpact;
import net.caffeinemc.mods.sodium.client.gui.options.OptionImpl;
import net.caffeinemc.mods.sodium.client.gui.options.OptionPage;
import net.caffeinemc.mods.sodium.client.gui.options.control.CyclingControl;
import net.caffeinemc.mods.sodium.client.gui.options.control.SliderControl;
import net.caffeinemc.mods.sodium.client.gui.options.control.TickBoxControl;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import toni.sodiumoptionsapi.api.OptionGUIConstruction;

/**
 * Adds a "Stereoscopic" tab to the Sodium options screen via
 * sodiumoptionsapi (SOAPI). The integration is soft: when SOAPI isn't loaded
 * (i.e. running on a non-Aeronautics setup) the registration is skipped and
 * users fall back to the standalone keybinding-driven screen.
 *
 * <p>SOAPI exposes {@code OptionGUIConstruction.EVENT} as a Fabric Event
 * (provided by forgified-fabric-api on NeoForge). Registering a listener with
 * {@code EVENT.register(this)} adds our page to the OptionPage list each time
 * the Sodium options GUI is constructed.
 */
public final class SodiumOptionsPageIntegration {

    private SodiumOptionsPageIntegration() {}

    /** Wire from Stereoscopic.onClientSetup. No-op if SOAPI absent. */
    public static void tryRegister() {
        if (!ModList.get().isLoaded("sodiumoptionsapi")) {
            Stereoscopic.LOG.info("Sodium options integration skipped — sodiumoptionsapi not present (standalone keybinding screen remains available).");
            return;
        }
        try {
            doRegister();
            Stereoscopic.LOG.info("Sodium options tab registered via sodiumoptionsapi.");
        } catch (Throwable t) {
            Stereoscopic.LOG.warn("Failed to register Sodium options tab via sodiumoptionsapi; standalone keybinding screen still works.", t);
        }
    }

    private static void doRegister() {
        OptionGUIConstruction.EVENT.register(pageList -> pageList.add(buildPage()));
    }

    private static OptionPage buildPage() {
        OptionGroup main = OptionGroup.createBuilder()
            .add(OptionImpl.createBuilder(StereoMode.class, StereoOptionStorage.INSTANCE)
                .setName(Component.translatable("stereoscopic.options.mode.name"))
                .setTooltip(Component.translatable("stereoscopic.options.mode.tooltip"))
                .setControl(opt -> new CyclingControl<>(opt, StereoMode.class, new Component[] {
                    Component.translatable("stereoscopic.options.mode.off"),
                    Component.translatable("stereoscopic.options.mode.sbs_half")
                }))
                .setBinding((s, v) -> s.mode = v, s -> s.mode)
                .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                .setImpact(OptionImpact.LOW)
                .build())

            .add(OptionImpl.createBuilder(int.class, StereoOptionStorage.INSTANCE)
                .setName(Component.translatable("stereoscopic.options.ipd.name"))
                .setTooltip(Component.translatable("stereoscopic.options.ipd.tooltip"))
                .setControl(opt -> new SliderControl(opt, 55, 75, 1,
                    v -> Component.literal(v + " mm")))
                .setBinding(
                    (s, v) -> s.ipd = v / 1000f,
                    s -> Math.round(s.ipd * 1000f))
                .setImpact(OptionImpact.LOW)
                .build())

            .add(OptionImpl.createBuilder(int.class, StereoOptionStorage.INSTANCE)
                .setName(Component.translatable("stereoscopic.options.convergence.name"))
                .setTooltip(Component.translatable("stereoscopic.options.convergence.tooltip"))
                .setControl(opt -> new SliderControl(opt, 0, 32, 1,
                    v -> v == 0
                        ? Component.translatable("stereoscopic.options.convergence.off")
                        : Component.translatable("stereoscopic.options.convergence.blocks", v)))
                .setBinding(
                    (s, v) -> s.convergence = (float) v,
                    s -> Math.round(s.convergence))
                .setImpact(OptionImpact.LOW)
                .build())

            .add(OptionImpl.createBuilder(boolean.class, StereoOptionStorage.INSTANCE)
                .setName(Component.translatable("stereoscopic.options.swap_eyes.name"))
                .setTooltip(Component.translatable("stereoscopic.options.swap_eyes.tooltip"))
                .setControl(TickBoxControl::new)
                .setBinding((s, v) -> s.swapEyes = v, s -> s.swapEyes)
                .setImpact(OptionImpact.LOW)
                .build())

            .build();

        return new OptionPage(
            Component.translatable("stereoscopic.options.group.name"),
            ImmutableList.of(main));
    }
}
