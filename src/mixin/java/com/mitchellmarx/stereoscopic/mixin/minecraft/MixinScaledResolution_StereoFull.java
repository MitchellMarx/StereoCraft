package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.config.StereoConfig;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SBS_FULL GUI layout width. In FULL mode each eye shows the WHOLE GUI proportioned for its
 * half-width ({@code (W/2)/H}) region — versus HALF, where the GUI is laid out at full width and
 * squeezed 2:1 into each half (a 3D TV stretches it back). So in FULL the GUI's logical width
 * must be halved: everything laid out against {@link ScaledResolution#getScaledWidth()} — screen
 * {@code initGui}, HUD/hotbar anchoring, chat, and the mouse→GUI coordinate mapping — sees the
 * per-eye width.
 *
 * <p>This is the 1.7.10 analog of the Fabric reference's {@code Window.getGuiScaledWidth} hook.
 * Unlike that port, on 1.7.10 the GUI ortho projection ({@code setupOverlayRendering} →
 * {@code glOrtho(0, getScaledWidth_double(), ...)}) is ALSO derived from this value, so halving
 * it narrows the ortho too. That is exactly what makes the existing scratch-FB + blit-squish GUI
 * pipeline ({@link MixinEntityRenderer_Stereo}) render FULL correctly with no other change: the
 * half-width layout drawn under a half-width ortho fills the full-size scratch (2× horizontal
 * stretch), and the blit that squeezes the full scratch into each fullW/2 half cancels that
 * stretch back to a native 1:1 GUI — the reference's "uncompressed into each half" outcome
 * reached by a different but equivalent seam. HALF keeps the full width and its 2:1 squeeze.
 *
 * <p>Only the width is touched (never {@code scaledHeight} or {@code scaleFactor}) — the per-eye
 * aspect halves horizontally only, and GUI element pixel scale must stay constant between modes.
 * Ceil-halving matches vanilla's {@code ceiling_double_int} rounding family; one GUI unit of slack
 * on odd widths is harmless.
 *
 * <p>Reads live {@link StereoConfig#stereoscopicMode} rather than the frame snapshot —
 * {@code ScaledResolution} is constructed outside the render loop too (screen resize/open, mouse
 * mapping), and layout must agree with the mode the next frames render.
 */
@Mixin(ScaledResolution.class)
public abstract class MixinScaledResolution_StereoFull {

    @Shadow private int scaledWidth;
    @Shadow private double scaledWidthD;

    @Inject(method = "<init>(Lnet/minecraft/client/Minecraft;II)V", at = @At("RETURN"))
    private void stereoscopic$halveGuiWidthForFullSbs(Minecraft mc, int width, int height, CallbackInfo ci) {
        final StereoMode mode = StereoConfig.stereoscopicMode;
        if (mode == null || !mode.isFull()) return;
        this.scaledWidth = (this.scaledWidth + 1) / 2;
        this.scaledWidthD = this.scaledWidthD * 0.5;
    }
}
