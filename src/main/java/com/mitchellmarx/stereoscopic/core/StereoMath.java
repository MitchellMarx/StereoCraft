package com.mitchellmarx.stereoscopic.core;

import org.joml.Matrix4f;

/**
 * Pure math helpers for stereo rendering. Extracted from the GameRenderer
 * mixin so the load-bearing projection math can be unit-tested without a
 * Minecraft client.
 */
public final class StereoMath {

    private StereoMath() {}

    /**
     * Off-axis frustum shear scalar for one eye.
     *
     * <p>Derivation. The eye sits at world position {@code basePos + dx * right}
     * (camera pre-translated along the local right axis by the per-eye loop in
     * {@code MixinGameRenderer.stereoscopic$twoPassRenderWorld}). The
     * convergence target — the world point we want at zero parallax — sits at
     * {@code basePos + convDist * forward}. In the eye's local frame, that
     * target is at {@code (-dx, 0, -convDist)}.
     *
     * <p>For a symmetric perspective {@code P}, projecting that point gives
     * {@code x_ndc = (P[0][0] * (-dx) + P[0][2] * (-convDist)) / convDist
     *        = -P[0][0] * dx / convDist - P[0][2]}. We want {@code x_ndc = 0},
     * so {@code P[0][2] = -P[0][0] * dx / convDist}. A post-multiply by
     * identity-plus-shear at column 2 row 0 sets {@code P[0][2] = P[0][0] *
     * shear}, hence {@code shear = -dx / convDist}.
     *
     * <p>Sign sanity: with this project's {@code StereoState.getEyeOffset()}
     * returning {@code -ipd/2} for LEFT, the formula gives positive shear for
     * the LEFT eye. That shifts the LEFT-eye image leftward in NDC, bringing
     * the convergence target (which would otherwise appear on the right half
     * of the LEFT eye's screen) back to center. Symmetric for RIGHT.
     *
     * @return the shear scalar, or 0 if the inputs disable the shear
     *         ({@code dx == 0} for MONO, {@code convDist <= 0} for the
     *         documented parallel-axis off mode)
     */
    public static float convergenceShear(float eyeOffset, float convDist) {
        if (eyeOffset == 0f) return 0f;
        if (convDist <= 0f) return 0f;
        return -eyeOffset / convDist;
    }

    /**
     * Mutates {@code proj} in place by post-multiplying an identity-plus-shear
     * matrix with {@code m20 = convergenceShear(eyeOffset, convDist)}. Returns
     * the same instance for chain-style use. No-op when the shear is zero.
     *
     * <p>{@code .m20()} is JOML's column-2 row-0 entry (GL column-major index
     * 8). Post-multiply via {@code .mul()} matches the legacy
     * {@code glMultMatrix} semantics that older OpenGL stereo references used.
     */
    public static Matrix4f applyConvergenceShear(Matrix4f proj, float eyeOffset, float convDist) {
        float shear = convergenceShear(eyeOffset, convDist);
        if (shear == 0f) return proj;
        return proj.mul(new Matrix4f().m20(shear));
    }

    /**
     * Mutates {@code proj} in place for SBS_FULL: halves the projection's
     * aspect ratio from the vanilla full-window {@code W/H} to the per-eye
     * {@code (W/2)/H} by doubling {@code m00} ({@code m00 = f/aspect} for a
     * symmetric perspective — vertical FOV is untouched). The per-eye render
     * is still squeezed 2:1 into its half of the window, so each half carries
     * a proportionally correct full-aspect image with no display stretch.
     *
     * <p>Must be applied BEFORE {@link #applyConvergenceShear} — the shear
     * post-multiply reads the current {@code m00} ({@code P[0][2] = P[0][0] *
     * shear}), and convergence geometry has to match the frustum actually
     * rendered.
     */
    public static Matrix4f applyFullSbsAspect(Matrix4f proj) {
        return proj.m00(proj.m00() * 2f);
    }
}
