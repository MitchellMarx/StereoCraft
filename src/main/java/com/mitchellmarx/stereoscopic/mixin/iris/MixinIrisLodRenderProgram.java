package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.core.StereoMath;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.irisshaders.iris.compat.dh.IrisLodRenderProgram;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Re-apply the convergence shear to DH-terrain projection under Iris.
 *
 * <p>When a shaderpack is loaded, DH's terrain (solid + translucent passes)
 * is rendered by Iris's {@link IrisLodRenderProgram}, bound from
 * {@code LodRendererEvents$13.beforeRender}. That code builds the projection
 * uniform fresh via {@code new Matrix4f().setPerspective(fov, aspect,
 * dhNear, dhFar)} — it extracts FOV from {@code gbufferProjection.perspectiveFov()}
 * (m11 only) and aspect from {@code m11/m00}, both insensitive to our m20
 * shear, then rebuilds a symmetric perspective from scratch which writes
 * m20 = 0. The per-eye shear that {@code MixinGameRenderer} put on
 * {@code gbufferProjection} is gone by the time the uniform is uploaded.
 *
 * <p>Net effect (shaders on, stereo on): DH terrain rasterizes WITHOUT
 * shear while MC chunks (sheared gbuffer projection) and DH water (via
 * {@code IrisGenericRenderProgram}, which uploads
 * {@code DhApiRenderParam.dhProjectionMatrix} directly — shear preserved
 * through {@code RenderUtil.createLodProjectionMatrix} /
 * {@code Mat4f.setClipPlanes}, which only touches m22/m23) both have it.
 * DH terrain shifts per-eye relative to water + chunk terrain;
 * toggling shaders off makes both paths run DH's own renderer with
 * {@code dhProjectionMatrix}, restoring agreement.
 *
 * <p>This mixin post-multiplies the shear back onto the projection arg at
 * method HEAD, so both the projection uniform AND its inverse (computed
 * in-method from the same arg) stay consistent.
 *
 * <p>Gated to skip the shadow path: {@code LodRendererEvents$13} also calls
 * {@code fillUniformData} with {@code ShadowRenderer.PROJECTION /
 * MODELVIEW} during shadow rendering. That's the sun-camera projection,
 * not the viewer's, and our shadow pass already uses the mono camera (see
 * {@code MixinShadowRenderer} and {@code project_iris_shadow_pass_camera}).
 * {@code areShadowsCurrentlyBeingRendered()} is the same gate Iris itself
 * uses to pick {@code getShadowShader()} vs {@code getSolidShader()} two
 * lines above the bug site.
 */
@Mixin(value = IrisLodRenderProgram.class, remap = false)
public abstract class MixinIrisLodRenderProgram {

    @ModifyVariable(
        method = "fillUniformData(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;IF)V",
        at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Matrix4fc stereoscopic$shearProjection(Matrix4fc proj) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInWorldPass()) return proj;
        if (ShadowRenderingState.areShadowsCurrentlyBeingRendered()) return proj;
        Matrix4f sheared = new Matrix4f(proj);
        return StereoMath.applyConvergenceShear(sheared, s.getEyeOffset(), s.getFrameConvergence());
    }
}
