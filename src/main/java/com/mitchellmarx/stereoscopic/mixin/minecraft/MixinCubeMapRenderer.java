package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoOptions;
import net.minecraft.client.gui.CubeMapRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * SBS_FULL aspect for the title/world-picker panorama. {@code CubeMapRenderer.draw}
 * builds its own perspective from the full framebuffer dims
 * ({@code ProjectionMatrix3.set(fbW, fbH, 85)}), and
 * {@link MixinRotatingCubeMapRenderer}'s panorama path then squishes the mono
 * render 2:1 into each eye half. In SBS_HALF the display stretches the half
 * back out, so the full-window aspect is correct; in SBS_FULL each half is
 * shown as-is, so — exactly like the world projection
 * ({@code StereoMath.applyFullSbsAspect}) — the panorama must be rendered at
 * the per-eye aspect {@code (W/2)/H} to come out proportionally correct after
 * the squish. Halve the width arg.
 *
 * <p>The GUI panorama is CubeMapRenderer's only render path, and its output
 * always goes through the per-half squish while stereo is active, so gating
 * on the live mode alone is right.
 */
@Mixin(CubeMapRenderer.class)
public abstract class MixinCubeMapRenderer {

    @ModifyArg(
        method = "draw(Lnet/minecraft/client/MinecraftClient;FF)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/render/ProjectionMatrix3;set(IIF)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
        index = 0
    )
    private int stereoscopic$halfAspectPanoramaForFullSbs(int width) {
        return StereoOptions.INSTANCE.mode.isFull() ? width / 2 : width;
    }
}
