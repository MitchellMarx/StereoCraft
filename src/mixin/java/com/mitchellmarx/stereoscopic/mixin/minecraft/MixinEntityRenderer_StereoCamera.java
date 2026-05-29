package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.mitchellmarx.stereoscopic.config.StereoConfig;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.renderer.EntityRenderer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

/**
 * Mimics vanilla 1.7.10's anaglyph per-eye camera shift in {@code EntityRenderer.setupCameraTransform}.
 * Runs at priority 999 so it lands before Angelica's matrix-capture mixin, letting Iris shader
 * uniforms ({@code gbufferModelView}, {@code gbufferProjection}) pick up the eye offset for free.
 *
 * <p>Vanilla anaglyph order, for sign reference:
 * <pre>
 *   PROJECTION: loadIdentity; glTranslatef(-(field*2-1)*0.07f, 0, 0); gluPerspective(...)
 *   MODELVIEW:  loadIdentity; glTranslatef((field*2-1)*0.1f, 0, 0); hurtCameraEffect(); ...
 * </pre>
 */
@Mixin(value = EntityRenderer.class, priority = 999)
public abstract class MixinEntityRenderer_StereoCamera {

    /**
     * Off-axis frustum shear applied AFTER vanilla's gluPerspective. Skews the projection
     * matrix so each eye converges toward the configured convergence distance — objects at
     * that depth sit at the screen plane (zero parallax), closer objects pop out, farther
     * ones recede.
     *
     * <p>Math: a post-multiply by a shear matrix with column-major index 8 ({@code m02} in
     * row-major notation) set to {@code dx / convergenceDist} achieves the target
     * {@code m02 = (R+L)/(R-L) = dx / R} on a symmetric perspective matrix where
     * {@code P00 = near / R}. Since the post-multiply replaces P[0][2] with
     * {@code P00 * shear_m02}, we need {@code shear = (dx/R) / (near/R) = dx/near}; but
     * cancelling through {@code dx/near = (frustumShift/half) / near = ... = dx/convDist},
     * so the shear value used in the multiply is simply {@code dx / convergenceDist}.
     *
     * <p>{@code dx} comes signed from {@link StereoState#getEyeOffset()}: +ipd/2 for LEFT
     * (frustum shifts right → eye looks slightly right), −ipd/2 for RIGHT.
     */
    @Inject(
        method = "setupCameraTransform",
        at = @At(
            value = "INVOKE",
            target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V",
            ordinal = 0,
            shift = At.Shift.AFTER,
            remap = false
        )
    )
    private void stereoscopic$applyOffAxisFrustumShear(float partialTicks, int pass, CallbackInfo ci) {
        if (!StereoState.INSTANCE.isActive()) return;
        final float dx = StereoState.INSTANCE.getEyeOffset();
        if (dx == 0f) return;
        final float convDist = StereoConfig.stereoConvergence;
        if (convDist <= 0f) return;
        final float shear = dx / convDist;
        final FloatBuffer m = BufferUtils.createFloatBuffer(16);
        // Column-major shear matrix: identity with shear at index 8 (column 2, row 0 = m02).
        m.put(new float[] {
            1f,    0f, 0f, 0f,
            0f,    1f, 0f, 0f,
            shear, 0f, 1f, 0f,
            0f,    0f, 0f, 1f,
        });
        m.flip();
        GLStateManager.glMultMatrix(m);
    }

    /**
     * Modelview offset anchored on {@code hurtCameraEffect} — the first vanilla call after the
     * modelview is loaded with identity. {@code Minecraft.getMinecraft()} would be earlier but
     * is unreliable across remap settings.
     */
    @Inject(
        method = "setupCameraTransform",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/EntityRenderer;hurtCameraEffect(F)V",
            ordinal = 0,
            shift = At.Shift.BEFORE
        )
    )
    private void stereoscopic$applyStereoModelviewOffset(float partialTicks, int pass, CallbackInfo ci) {
        if (!StereoState.INSTANCE.isActive()) return;
        float dx = StereoState.INSTANCE.getEyeOffset();
        if (dx == 0f) return;
        // Defensively re-set matrix mode; vanilla left it on GL_MODELVIEW but mods can perturb.
        GLStateManager.glMatrixMode(GL11.GL_MODELVIEW);
        GLStateManager.glTranslatef(dx, 0f, 0f);
    }
}
