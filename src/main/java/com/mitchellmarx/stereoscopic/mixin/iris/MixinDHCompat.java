package com.mitchellmarx.stereoscopic.mixin.iris;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mitchellmarx.stereoscopic.core.StereoMath;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.irisshaders.iris.compat.dh.DHCompat;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Apply the per-eye convergence shear to {@code DHCompat.getProjection()}.
 *
 * <p>This is the supplier that backs Iris's {@code dhProjection} /
 * {@code dhProjectionInverse} global uniforms — registered in
 * {@code MatrixUniforms.addMatrixUniforms} via {@code addDHMatrix(holder,
 * "Projection", DHCompat::getProjection)}, wired through
 * {@code LambdaMetafactory}. The reference doesn't appear as a direct
 * {@code invokestatic} in user-class bytecode; searching only direct callers
 * (e.g. grepping {@code DHCompat.getProjection}) misses it. The lambda fires
 * every frame as part of PER_FRAME uniform refresh.
 *
 * <p>Inside {@code getProjection()}, DH builds the matrix via
 * {@code new Matrix4f().setPerspective(fov, aspect, dhNear, dhFar)} — FOV and
 * aspect are extracted from {@code CapturedRenderingState.gbufferProjection}
 * via {@code .perspectiveFov()} (reads m11 only) and {@code m11/m00} (reads
 * those two), both insensitive to the {@code m20} shear we apply in
 * {@link com.mitchellmarx.stereoscopic.mixin.minecraft.MixinGameRenderer}.
 * {@code setPerspective} then rebuilds a fresh symmetric perspective with
 * {@code m20 = 0}, wiping our shear before the supplier returns.
 *
 * <p>The resulting unsheared {@code dhProjection} uniform breaks shaderpacks
 * in two ways under stereo+shaders+DH:
 * <ul>
 *   <li><b>DH terrain rasterization.</b> BSL's {@code dh_terrain.glsl:366}
 *       and Complementary's {@code dh_terrain.glsl:159} use
 *       {@code gl_Position = dhProjection * gbufferModelView * position}.
 *       With unsheared {@code dhProjection}, distant terrain renders
 *       parallax-free while regular MC chunks (via sheared
 *       {@code gbufferProjection}) render with parallax — visibly
 *       misaligning DH terrain relative to nearby chunks/water per eye.</li>
 *   <li><b>Depth-tex reconstruction.</b> Composite passes that reconstruct
 *       view position from {@code dhDepthTex} via
 *       {@code dhProjectionInverse * (screenPos * 2 - 1)} read an
 *       inverse-of-unsheared matrix, while the actual depth values came from
 *       sheared rendering. Result: reflections, water shading, SSR, and TAA
 *       reprojection on DH terrain land at wrong screen X per eye.</li>
 * </ul>
 *
 * <p>Re-applying the shear here restores consistency: both {@code dhProjection}
 * (used directly by shader-pack DH terrain vertex shaders) and the inverse
 * Iris derives from it via {@code MatrixUniforms$Inverted} (used by
 * composites) end up matching {@code gbufferProjection}'s per-eye shear.
 *
 * <p><b>Known co-caller:</b> {@code ShadowRenderer.createShadowFrustum} also
 * calls {@code DHCompat.getProjection()} and uses {@code proj * modelView} as
 * the viewer reference matrix for the shadow culling frustum. Shearing here
 * biases that cull frustum slightly toward the active eye (~0.8% horizontal
 * extent at 0.064m IPD / 4-block convergence — well below chunk granularity at
 * any shadow distance). Don't gate by
 * {@code ShadowRenderingState.areShadowsCurrentlyBeingRendered()}: Iris's
 * PER_FRAME uniform cache means the first lambda fetch per frame (typically
 * during the shadow pass) is what all subsequent reads see in that frame, so
 * a shadow-gated mixin would leak the unsheared value back into the composite
 * pass and leave the visible bug unfixed.
 *
 * <p>Gated on {@link StereoState#isActive()} and
 * {@link StereoState#isInWorldPass()} so non-stereo and out-of-frame calls
 * pass through unchanged.
 *
 * <p>Complementary to {@link MixinIrisLodRenderProgram}, which shears the
 * {@code iris_ProjectionMatrix} arg passed to the LOD program's
 * {@code fillUniformData} — that handles the case where a pack's DH terrain
 * shader reads {@code iris_ProjectionMatrix} instead of {@code dhProjection}.
 * Both mixins target separate uniforms; no double-shear.
 */
@Mixin(value = DHCompat.class, remap = false)
public abstract class MixinDHCompat {

    @ModifyReturnValue(method = "getProjection()Lorg/joml/Matrix4f;", at = @At("RETURN"))
    private static Matrix4f stereoscopic$shearDHProjection(Matrix4f proj) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInWorldPass()) return proj;
        return StereoMath.applyConvergenceShear(proj, s.getEyeOffset(), s.getFrameConvergence());
    }
}
