package com.mitchellmarx.stereoscopic.mixin.iris;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mitchellmarx.stereoscopic.config.StereoConfig;
import com.mitchellmarx.stereoscopic.core.StereoMath;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.coderbot.iris.compat.dh.DHCompat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Apply the per-eye convergence shear to {@link DHCompat#getProjection()}.
 *
 * <p>This is the supplier that backs Iris's {@code dhProjection} / {@code dhProjectionInverse}
 * global uniforms, registered in {@code MatrixUniforms.addMatrixUniforms} via
 * {@code addDHMatrix(holder, "Projection", DHCompat::getProjection)} and wired through
 * {@code LambdaMetafactory}. The reference doesn't appear as a direct {@code invokestatic} in
 * user-class bytecode, so a grep for {@code DHCompat.getProjection} callers misses it. The lambda
 * fires every frame as part of PER_FRAME uniform refresh.
 *
 * <p>Inside {@code getProjection()}, DH builds the matrix via {@code tempProj.setPerspective(fov,
 * aspect, dhNear, dhFar)} — FOV via {@code projection.perspectiveFov()} (m11 only) and aspect via
 * {@code m11 / m00}, both insensitive to our m20 shear. {@code setPerspective} then rebuilds a
 * symmetric perspective with m20 = 0, wiping the shear that
 * {@code MixinEntityRenderer_StereoCamera} applied at GL level.
 *
 * <p>The unsheared {@code dhProjection} uniform breaks shaderpacks under stereo+shaders+DH in two
 * ways:
 * <ul>
 *   <li><b>DH terrain rasterization.</b> BSL's {@code dh_terrain.glsl} and Complementary's
 *       {@code dh_terrain.glsl} use {@code gl_Position = dhProjection * gbufferModelView *
 *       position}. With unsheared {@code dhProjection}, distant terrain renders parallax-free
 *       while regular MC chunks (via sheared {@code gbufferProjection}) render with parallax —
 *       DH terrain visibly misaligns from nearby chunks/water per eye.</li>
 *   <li><b>Depth-tex reconstruction.</b> Composite passes reconstruct view position from
 *       {@code dhDepthTex} via {@code dhProjectionInverse * (screenPos*2 - 1)} and read an
 *       inverse-of-unsheared matrix, while actual depths came from sheared rasterization. SSR,
 *       water shading, reflections, and TAA reprojection on DH terrain land at wrong screen X
 *       per eye.</li>
 * </ul>
 *
 * <p><b>Known co-caller:</b> {@code ShadowRenderer.createShadowFrustum} calls
 * {@code DHCompat.getProjection()} as the viewer reference for the cull frustum. Shearing here
 * biases that frustum slightly toward the active eye (~0.8% horizontal extent at 0.064m IPD /
 * 4-block convergence — below chunk granularity at any shadow distance). Deliberately NOT gated
 * by {@code ShadowRenderingState.areShadowsCurrentlyBeingRendered()}: Iris's PER_FRAME uniform
 * cache means the first lambda fetch (typically during shadow pass) is what subsequent reads see,
 * so a shadow-gated mixin would leak the unsheared value into composites and leave the visible
 * bug unfixed.
 *
 * <p>Complementary to {@link MixinIrisLodRenderProgram}, which shears the
 * {@code iris_ProjectionMatrix} arg of the LOD program's {@code fillUniformData} — that path
 * handles packs whose DH terrain shader reads {@code iris_ProjectionMatrix} instead of
 * {@code dhProjection}. Separate uniforms; no double-shear.
 *
 * <p>Angelica's {@code DHCompat.getProjection()} returns the shared static {@code tempProj}
 * field. Mutating the returned instance here is safe because each call resets it via
 * {@code setPerspective} before we shear.
 */
@Mixin(value = DHCompat.class, remap = false)
public abstract class MixinDHCompat {

    @ModifyReturnValue(method = "getProjection()Lorg/joml/Matrix4fc;", at = @At("RETURN"))
    private static Matrix4fc stereoscopic$shearDHProjection(Matrix4fc proj) {
        final StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInWorldPass()) return proj;
        if (proj instanceof Matrix4f m) {
            return StereoMath.applyConvergenceShear(m, s.getEyeOffset(), StereoConfig.stereoConvergence);
        }
        return proj;
    }
}
