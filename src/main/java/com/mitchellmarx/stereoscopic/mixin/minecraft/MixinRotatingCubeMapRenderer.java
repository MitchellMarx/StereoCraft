package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import com.mitchellmarx.stereoscopic.render.ViewportMath;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.CubeMapRenderer;
import net.minecraft.client.gui.RotatingCubeMapRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Per-eye panorama. In 1.21.11 {@code Screen.renderPanoramaBackground} →
 * {@code RotatingCubeMapRenderer.render} calls {@code CubeMapRenderer.draw}
 * immediately (its own render pass on {@code getFramebuffer()}), outside the
 * per-eye GUI flush, so it renders once (mono) across the full FB and each SBS
 * half shows half of one panorama. Render the panorama ONCE into the scratch FB
 * (substituted via {@link MixinMinecraftClient}'s {@code getFramebuffer()}
 * redirect) and then squish the full image into each eye-half with a
 * {@code GL_LINEAR} blit. Both halves get a complete, half-width-squished
 * panorama — identical between eyes, which is correct for a skybox at
 * infinity (zero parallax). The overlay texture drawn after it goes through
 * {@code DrawContext} and the two-pass GUI flush as usual.
 */
@Mixin(RotatingCubeMapRenderer.class)
public abstract class MixinRotatingCubeMapRenderer {

    @WrapOperation(
        method = "render(Lnet/minecraft/client/gui/DrawContext;IIZ)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/CubeMapRenderer;draw(Lnet/minecraft/client/MinecraftClient;FF)V")
    )
    private void stereoscopic$twoPassPanorama(CubeMapRenderer cubeMap, MinecraftClient client,
                                              float pitch, float yaw,
                                              Operation<Void> original) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive()) {
            original.call(cubeMap, client, pitch, yaw);
            return;
        }
        int fbW = s.getFrameFbW();
        int fbH = s.getFrameFbH();
        Framebuffer mainFb = MinecraftClient.getInstance().getFramebuffer();
        if (mainFb == null || fbW <= 0 || fbH <= 0) {
            original.call(cubeMap, client, pitch, yaw);
            return;
        }
        SimpleFramebuffer scratch = PerEyeRenderer.ensureScratchFb(fbW, fbH);
        PerEyeRenderer.setScratchFbActive(true);
        try {
            // Renders the full mono panorama into the scratch FB (getFramebuffer redirect).
            original.call(cubeMap, client, pitch, yaw);
        } finally {
            PerEyeRenderer.setScratchFbActive(false);
        }
        ViewportMath.Rect left = ViewportMath.eyeRect(StereoState.Eye.LEFT, StereoOptions.INSTANCE.mode, fbW, fbH);
        ViewportMath.Rect right = ViewportMath.eyeRect(StereoState.Eye.RIGHT, StereoOptions.INSTANCE.mode, fbW, fbH);
        PerEyeRenderer.blitScratchColorToRect(scratch, mainFb,
            left.x(), left.y(), left.x() + left.w(), left.y() + left.h());
        PerEyeRenderer.blitScratchColorToRect(scratch, mainFb,
            right.x(), right.y(), right.x() + right.w(), right.y() + right.h());
    }
}
