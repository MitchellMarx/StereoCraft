package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Per-eye particle position. {@code SingleQuadParticle.extract} bakes each
 * quad's position camera-relative against the live {@code Camera} (mono —
 * the per-eye loop only moves {@code CameraRenderState.pos}), once per frame.
 * Drawn as-is, particles get the convergence shear but no eye separation:
 * zero parallax, so they sit at the convergence distance in front of the
 * scene (issue #5, "particles have exaggerated eye distance").
 *
 * <p>{@code renderRotatedQuad} runs in {@code prepare()}, which the particle
 * feature renderer calls per eye pass; subtract the current eye's offset from
 * the mono camera there.
 *
 * <p>Priority below the default 1000: Sodium's {@code QuadParticleRenderStateMixin}
 * injects at the same HEAD and cancels after emitting vertices from the
 * arguments, so these modifications must be applied (and placed) first.
 */
@Mixin(value = QuadParticleRenderState.class, priority = 900)
public abstract class MixinQuadParticleRenderState {

    @ModifyVariable(method = "renderRotatedQuad", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float stereoscopic$eyeX(float x) {
        return x - (float) StereoState.INSTANCE.getEyeCameraOffset().x;
    }

    @ModifyVariable(method = "renderRotatedQuad", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float stereoscopic$eyeY(float y) {
        return y - (float) StereoState.INSTANCE.getEyeCameraOffset().y;
    }

    @ModifyVariable(method = "renderRotatedQuad", at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private float stereoscopic$eyeZ(float z) {
        return z - (float) StereoState.INSTANCE.getEyeCameraOffset().z;
    }
}
