package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.config.StereoConfig;
import com.mitchellmarx.stereoscopic.core.StereoMath;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.coderbot.iris.compat.dh.IrisLodRenderProgram;
import net.coderbot.iris.shadows.ShadowRenderingState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Re-apply the convergence shear to the DH-terrain projection under Iris.
 *
 * <p>When a shaderpack is loaded, DH's terrain (solid + translucent passes) is rendered by Iris's
 * {@link IrisLodRenderProgram}, bound from {@code LodRendererEvents$13.beforeRender}. That code
 * builds the projection uniform fresh via {@code new Matrix4f().setPerspective(fov, aspect,
 * dhNear, dhFar)} — it extracts FOV via {@code projection.perspectiveFov()} (m11 only) and aspect
 * via {@code m11 / m00}, both insensitive to our m20 shear, then rebuilds a symmetric perspective
 * which writes m20 = 0. The per-eye shear that {@code MixinEntityRenderer_StereoCamera} put on the
 * GL projection (and which Iris captured into {@code gbufferProjection}) is gone by the time the
 * uniform reaches the shader.
 *
 * <p>Net effect (shaders on, stereo on): DH terrain rasterizes without shear while MC chunks
 * (sheared gbufferProjection) and DH water (via {@code IrisGenericRenderProgram}, uploaded with
 * {@code DhApiRenderParam.dhProjectionMatrix} preserved through {@code RenderUtil
 * .createLodProjectionMatrix} / {@code Mat4f.setClipPlanes}, which only touches m22/m23) both
 * have it. DH terrain shifts per-eye relative to water + chunk terrain; toggling shaders off
 * makes both paths run DH's own renderer with {@code dhProjectionMatrix}, restoring agreement.
 *
 * <p>This mixin post-multiplies the shear back onto the projection arg at method HEAD so both the
 * projection uniform AND its inverse (computed in-method from the same arg) stay consistent.
 *
 * <p>Gated past the shadow path: {@code LodRendererEvents$13} also calls {@code fillUniformData}
 * with {@code ShadowRenderer.PROJECTION / MODELVIEW} during shadow rendering. That's the sun
 * camera, not the viewer's, and our shadow pass already uses the mono camera.
 * {@code areShadowsCurrentlyBeingRendered()} is the same gate Iris itself uses to pick
 * {@code getShadowShader()} vs {@code getSolidShader()} two lines above the bug site.
 */
@Mixin(value = IrisLodRenderProgram.class, remap = false)
public abstract class MixinIrisLodRenderProgram {

    @ModifyVariable(
        method = "fillUniformData(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;IF)V",
        at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Matrix4fc stereoscopic$shearProjection(Matrix4fc proj) {
        final StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInWorldPass()) return proj;
        if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) return proj;
        final Matrix4f sheared = new Matrix4f(proj);
        return StereoMath.applyConvergenceShear(sheared, s.getEyeOffset(), StereoConfig.stereoConvergence);
    }
}
