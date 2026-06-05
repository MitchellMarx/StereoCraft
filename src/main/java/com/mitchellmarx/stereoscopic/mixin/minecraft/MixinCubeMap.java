package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import net.minecraft.client.renderer.CubeMap;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Make the title-screen / world-picker panorama stereo-correct under our
 * per-eye GUI iter.
 *
 * <p>{@code CubeMap.render} sets its own perspective matrix via
 * {@code RenderSystem.setProjectionMatrix(setPerspective(fov, fullWindowAspect, ...))}
 * which <em>overrides</em> the per-eye horizontal squish we set up in
 * {@code MixinGameRenderer.stereoscopic$perEyeScreen}. With the override in
 * place, the cube map vertices project through a full-window-aspect
 * perspective and land across the entire FB. The user's SBS_HALF display
 * then shows the left portion of one mono panorama in the left eye and the
 * right portion in the right eye -- a "single image stretched across both
 * halves" effect rather than each eye seeing its own complete squished view.
 *
 * <p>This mixin {@code @ModifyArg}s the matrix argument of that
 * {@code setProjectionMatrix} call and pre-multiplies it with the per-eye
 * {@code translate(±0.5, 0, 0) * scale(0.5, 1, 1)} transform -- the same
 * horizontal squish + half-shift that {@code MixinGameRenderer.stereoscopic$perEyeProjection}
 * applies to the GUI projection. The cube map's vertices now project into
 * NDC range [-1, 0] (LEFT half) or [0, +1] (RIGHT half), so each eye half
 * of the FB receives a complete cube-map view squished to half width. Both
 * eyes see the same view (correct for a cube map at infinity -- IPD shift
 * produces zero parallax), each as a complete squished half, which the
 * SBS_HALF display un-squishes back to normal aspect.
 *
 * <p>Companion mixin: {@code MixinScreen.stereoscopic$prePanorama} cancels
 * the second-iter panorama render and mirrors the first-iter eye-half into
 * the other half via FB blit so the full-FB blur in
 * {@code renderBlurredBackground} sees identical content in both halves.
 */
@Mixin(CubeMap.class)
public abstract class MixinCubeMap {

    @ModifyArg(
        method = "render(Lnet/minecraft/client/Minecraft;FFF)V",
        at = @At(value = "INVOKE",
                 target = "Lcom/mojang/blaze3d/systems/RenderSystem;setProjectionMatrix(Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/vertex/VertexSorting;)V"),
        index = 0
    )
    private Matrix4f stereoscopic$squishCubeMapPerEye(Matrix4f cubeMapPerspective) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInGuiPass()) return cubeMapPerspective;
        if (!PerEyeRenderer.isProjectionSwapActive()) return cubeMapPerspective;
        boolean isRight = s.getCurrentEye() == StereoState.Eye.RIGHT;
        boolean leftHalf = StereoOptions.INSTANCE.swapEyes ? isRight : !isRight;
        return new Matrix4f()
            .translate(leftHalf ? -0.5f : 0.5f, 0f, 0f)
            .scale(0.5f, 1f, 1f)
            .mul(cubeMapPerspective);
    }
}
