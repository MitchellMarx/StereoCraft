package com.mitchellmarx.stereoscopic.core;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StereoMathTest {

    private static final float EPS = 1e-6f;

    @Test
    void shearIsZeroForMonoEye() {
        assertEquals(0f, StereoMath.convergenceShear(0f, 4f), EPS);
    }

    @Test
    void shearIsZeroForParallelAxisConvergence() {
        assertEquals(0f, StereoMath.convergenceShear(-0.032f, 0f), EPS);
        assertEquals(0f, StereoMath.convergenceShear(-0.032f, -1f), EPS,
            "negative convDist also disables shear (defensive)");
    }

    @Test
    void shearSignIsPositiveForLeftEyeNegativeForRight() {
        // Per StereoState.getEyeOffset(): LEFT = -ipd/2, RIGHT = +ipd/2.
        // LEFT eye sits left of head, sees convergence point on its right;
        // frustum must shear right (positive m20) to bring it to center.
        float leftShear  = StereoMath.convergenceShear(-0.032f, 4f);
        float rightShear = StereoMath.convergenceShear(+0.032f, 4f);
        assertTrue(leftShear  > 0f, "LEFT eye shear should be positive, got " + leftShear);
        assertTrue(rightShear < 0f, "RIGHT eye shear should be negative, got " + rightShear);
        assertEquals(leftShear, -rightShear, EPS, "LEFT and RIGHT shears must be antisymmetric");
    }

    @Test
    void shearMagnitudeMatchesFormula() {
        // shear = -eyeOffset / convDist
        assertEquals(0.008f, StereoMath.convergenceShear(-0.032f, 4f), EPS);
        assertEquals(0.032f, StereoMath.convergenceShear(-0.064f, 2f), EPS);
        assertEquals(-0.001f, StereoMath.convergenceShear(+0.016f, 16f), EPS);
    }

    @Test
    void applyConvergenceShearIsNoopWhenShearIsZero() {
        Matrix4f baseline = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.1f, 1000f);
        Matrix4f mutated = new Matrix4f(baseline);
        StereoMath.applyConvergenceShear(mutated, 0f, 4f);
        assertMatrixEquals(baseline, mutated, EPS);

        mutated = new Matrix4f(baseline);
        StereoMath.applyConvergenceShear(mutated, -0.032f, 0f);
        assertMatrixEquals(baseline, mutated, EPS);
    }

    @Test
    void applyConvergenceShearSetsExpectedM20() {
        // For a symmetric perspective, P[0][2] (JOML m20) starts at 0.
        // After post-multiply by identity-with-shear, new m20 = m00 * shear.
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.1f, 1000f);
        float originalM00 = proj.m00();
        float originalM20 = proj.m20();
        assertEquals(0f, originalM20, EPS, "symmetric perspective should have m20 = 0");

        StereoMath.applyConvergenceShear(proj, -0.032f, 4f);

        float expectedShear = -(-0.032f) / 4f;
        assertEquals(originalM00 * expectedShear, proj.m20(), EPS,
            "post-mul shear sets new m20 = original m00 * shear");
        assertEquals(originalM00, proj.m00(), EPS, "m00 (focal) unchanged");
    }

    @Test
    void applyConvergenceShearReturnsSameInstance() {
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.1f, 1000f);
        Matrix4f result = StereoMath.applyConvergenceShear(proj, -0.032f, 4f);
        assertSame(proj, result, "helper should mutate in place and return the same instance");
    }

    @Test
    void convergencePointProjectsToScreenCenterForLeftEye() {
        // End-to-end check: a world point at distance D along the central
        // optical axis, viewed from LEFT eye at offset dx along right vector,
        // should land at NDC x = 0 after the shear is applied.
        float dx = -0.032f; // LEFT eye
        float D  = 4.0f;
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9f, 0.1f, 1000f);
        StereoMath.applyConvergenceShear(proj, dx, D);

        // In LEFT-eye local coords, the convergence target is at (-dx, 0, -D).
        // Project: P * (x, 0, -D, 1).
        float x = -dx;
        float z = -D;
        float xClip = proj.m00() * x + proj.m20() * z;
        float wClip = proj.m23() * z + proj.m33();
        float xNdc = xClip / wClip;
        assertEquals(0f, xNdc, 1e-5f,
            "convergence target should project to NDC x = 0 after shear");
    }

    @Test
    void cullMarginIsHalfIpdAtZeroDistance() {
        // At the camera, only the origin offset (ipd/2) diverges.
        assertEquals(0.032f, StereoMath.cullMargin(0.064f, 4f, 0f), EPS);
    }

    @Test
    void cullMarginAddsShearTermWithDistance() {
        // extra = ipd/2 + (ipd/2 / convergence) * horizontalDist.
        // ipd=0.064, conv=4, dist=100 -> 0.032 + 0.008*100 = 0.832
        assertEquals(0.832f, StereoMath.cullMargin(0.064f, 4f, 100f), 1e-5f);
        // The shear slope matches convergenceShear's magnitude (0.008 per block).
        assertEquals(0.008f, StereoMath.convergenceShear(-0.032f, 4f), EPS);
    }

    @Test
    void cullMarginExceedsSodiumSlackBeyond140Blocks() {
        // Sodium's strict test carries 1.125 blocks of slack over the section
        // half-extent. The default profile (0.064 IPD, 4-block convergence)
        // needs more than that past ~140 blocks — the flicker threshold.
        assertTrue(StereoMath.cullMargin(0.064f, 4f, 140f) > 1.125f,
            "margin at 140 blocks should exceed Sodium's 1.125-block pad");
        assertTrue(StereoMath.cullMargin(0.064f, 4f, 130f) < 1.125f,
            "margin at 130 blocks should still fit within Sodium's pad");
    }

    @Test
    void cullMarginHasNoShearTermInParallelAxisMode() {
        // convergence <= 0 disables the shear; only ipd/2 remains at any range.
        assertEquals(0.032f, StereoMath.cullMargin(0.064f, 0f, 500f), EPS);
        assertEquals(0.032f, StereoMath.cullMargin(0.064f, -1f, 500f), EPS);
    }

    private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual, float eps) {
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float e = expected.get(col, row);
                float a = actual.get(col, row);
                assertEquals(e, a, eps,
                    "matrix element [col=" + col + ",row=" + row + "] differs");
            }
        }
    }
}
