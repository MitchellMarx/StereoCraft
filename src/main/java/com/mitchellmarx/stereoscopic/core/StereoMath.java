package com.mitchellmarx.stereoscopic.core;

import org.joml.Matrix4f;

/**
 * Stereo projection math. Mirrors the off-axis frustum shear applied at the GL level by
 * {@code MixinEntityRenderer_StereoCamera}, but operates on JOML matrices for the Iris paths
 * that rebuild projection from scratch and would otherwise clobber the GL-level shear.
 */
public final class StereoMath {

    private StereoMath() {}

    /**
     * Post-multiply {@code proj} by an off-axis convergence shear: identity with
     * {@code m20 = eyeOffset / convDist} (column 2, row 0). Mutates {@code proj} in place
     * and returns it for chaining.
     *
     * <p>For a symmetric perspective ({@code m00 != 0}, column 0 zero elsewhere) this only
     * affects {@code m20}. The full column-0 read handles already-sheared inputs cleanly.
     *
     * <p>No-op when {@code eyeOffset == 0} (MONO) or {@code convDist <= 0} (parallel-axis
     * mode).
     */
    public static Matrix4f applyConvergenceShear(Matrix4f proj, float eyeOffset, float convDist) {
        if (eyeOffset == 0f || convDist <= 0f) return proj;
        final float shear = eyeOffset / convDist;
        proj.m20(proj.m20() + proj.m00() * shear);
        proj.m21(proj.m21() + proj.m01() * shear);
        proj.m22(proj.m22() + proj.m02() * shear);
        proj.m23(proj.m23() + proj.m03() * shear);
        return proj;
    }
}
