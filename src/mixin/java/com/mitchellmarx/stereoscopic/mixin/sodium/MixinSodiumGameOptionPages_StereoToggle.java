package com.mitchellmarx.stereoscopic.mixin.sodium;

import com.google.common.collect.ImmutableList;
import com.mitchellmarx.stereoscopic.config.StereoConfig;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoState;
import me.jellysquid.mods.sodium.client.gui.SodiumGameOptionPages;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.AngelicaOptionsStorage;
import net.coderbot.iris.Iris;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * External mixin into Angelica-bundled Sodium's options-page builder. Appends a "Stereoscopic"
 * OptionGroup to the General page with two entries:
 * <ol>
 *   <li>Tick-box — SBS on/off (port of sbs2 commit 60b2f3f9's inline patch into
 *       {@link SodiumGameOptionPages#general()}).</li>
 *   <li>Integer slider — eye separation in mm, persisted as meters (new in this mod;
 *       matches the existing Stereoscopic Fabric mod's UX).</li>
 * </ol>
 *
 * <p>{@code @At("RETURN") cancellable = true} lets us read the existing OptionPage, append
 * our group to a mutable copy of its groups list, and return a replacement page with the
 * same name. We use {@code remap = false} since these are non-Mojang names.
 *
 * <p>We instantiate our own {@link AngelicaOptionsStorage} rather than {@code @Shadow}-ing
 * Sodium's private-static field — same effect, simpler bytecode. The binding lambdas ignore
 * the storage parameter (they operate on {@link StereoConfig} directly); we only need a
 * non-null object so {@link OptionImpl}'s constructor doesn't NPE during {@code reset()}.
 */
@Mixin(SodiumGameOptionPages.class)
public abstract class MixinSodiumGameOptionPages_StereoToggle {

    @Inject(method = "general", at = @At("RETURN"), cancellable = true, remap = false)
    private static void stereoscopic$appendStereoGroup(CallbackInfoReturnable<OptionPage> cir) {
        final OptionPage original = cir.getReturnValue();
        if (original == null) return;

        final List<OptionGroup> merged = new ArrayList<>(original.getGroups());
        final AngelicaOptionsStorage angelicaOpts = new AngelicaOptionsStorage();

        merged.add(OptionGroup.createBuilder()
            .add(OptionImpl.createBuilder(boolean.class, angelicaOpts)
                .setName("Stereoscopic SBS (Side-by-Side)")
                .setTooltip("Splits the screen into two eye views for VR headset use via Virtual Desktop / Steam Link. Roughly halves framerate.")
                .setControl(TickBoxControl::new)
                .setBinding(
                    (opts, value) -> {
                        final StereoMode newMode = value ? StereoMode.SBS_HALF : StereoMode.OFF;
                        if (StereoConfig.stereoscopicMode == newMode) return;
                        StereoConfig.stereoscopicMode = newMode;
                        StereoConfig.save();
                        // Force the StereoState frame-cache to pick up the new config NOW. Without
                        // this, isActive() / stereoEyeCount() still return the cached value from
                        // the last frame and the pipeline rebuild below would allocate the wrong
                        // number of per-eye textures.
                        StereoState.INSTANCE.beginFrame();
                        if (com.gtnewhorizons.angelica.config.AngelicaConfig.enableIris
                            && Minecraft.getMinecraft().theWorld != null) {
                            // Iris RenderTargets.eyeCount is captured at pipeline init, so we have
                            // to drop the pipeline and rebuild it for the new eye count to take
                            // effect. Then force a chunk reload so Sodium's terrain renderer drops
                            // its stale per-chunk texture references and rebinds against the new
                            // pipeline's render targets — otherwise the world stops rendering
                            // until the user reloads shaders manually.
                            Iris.getPipelineManager().destroyPipeline();
                            Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimensionName());
                            Minecraft.getMinecraft().renderGlobal.loadRenderers();
                        }
                    },
                    opts -> StereoConfig.stereoscopicMode != null
                        && StereoConfig.stereoscopicMode != StereoMode.OFF)
                .setImpact(OptionImpact.HIGH)
                .build())
            .add(OptionImpl.createBuilder(int.class, angelicaOpts)
                .setName("Eye separation (IPD)")
                .setTooltip("Eye separation / stereo strength. 64 mm matches average human IPD (real ~55-77 mm); above ~77 mm is hyperstereo (exaggerated depth). Max 100 mm.")
                .setControl(opt -> new SliderControl(opt, 55, 100, 1, mm -> String.format("%.3f m", mm / 1000.0)))
                .setBinding(
                    (opts, mm) -> {
                        StereoConfig.stereoIpd = Math.max(0.055f, Math.min(0.100f, mm / 1000f));
                        StereoConfig.save();
                    },
                    opts -> Math.round(StereoConfig.stereoIpd * 1000f))
                .setImpact(OptionImpact.LOW)
                .build())
            .add(OptionImpl.createBuilder(int.class, angelicaOpts)
                .setName("Convergence distance")
                .setTooltip("Off-axis convergence depth in blocks. Objects at this depth sit at the screen plane (zero parallax); closer objects pop out, farther ones recede. 0 = parallel-axis (no shear, everything pops out).")
                .setControl(opt -> new SliderControl(opt, 0, 16, 1, blocks -> blocks == 0 ? "off (parallel)" : blocks + " blocks"))
                .setBinding(
                    (opts, blocks) -> {
                        StereoConfig.stereoConvergence = Math.max(0f, Math.min(16f, (float) blocks));
                        StereoConfig.save();
                    },
                    opts -> Math.round(StereoConfig.stereoConvergence))
                .setImpact(OptionImpact.LOW)
                .build())
            .add(OptionImpl.createBuilder(boolean.class, angelicaOpts)
                .setName("Swap eyes")
                .setTooltip("Flip which eye-render goes to which physical eye. Toggle if depth looks inverted (objects appear behind the screen when they should pop out, or vice versa), or if reflections look like they're for the opposite eye on your SBS display / VR passthrough setup.")
                .setControl(TickBoxControl::new)
                .setBinding(
                    (opts, value) -> {
                        if (StereoConfig.stereoSwapEyes == value) return;
                        StereoConfig.stereoSwapEyes = value;
                        StereoConfig.save();
                    },
                    opts -> StereoConfig.stereoSwapEyes)
                .setImpact(OptionImpact.LOW)
                .build())
            .build());

        cir.setReturnValue(new OptionPage(original.getName(), ImmutableList.copyOf(merged)));
    }
}
