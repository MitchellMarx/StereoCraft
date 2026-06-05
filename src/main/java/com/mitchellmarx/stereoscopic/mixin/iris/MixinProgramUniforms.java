package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Force per-eye PER_FRAME uniform refresh on each {@link ProgramUniforms}
 * instance WITHOUT bumping {@code SystemTimeUniforms.COUNTER} between eye
 * iters.
 *
 * <p><b>Why this replaces the COUNTER bump.</b> Iris's
 * {@code ProgramUniforms.update()} only refreshes PER_FRAME uniforms (matrices,
 * camera position, viewport, etc.) when {@code lastFrame != COUNTER}. Without
 * a per-eye trigger the RIGHT eye would re-use the LEFT eye's uploaded
 * matrices and camera position, breaking stereo entirely.
 *
 * <p>The previous fix was to bump {@code COUNTER} between iters. That worked
 * for matrices, but {@code COUNTER} also drives the shader-visible
 * {@code frameCounter} uniform — Complementary's lib/common.glsl reads
 * {@code framemod2}, {@code framemod4}, {@code framemod8}, {@code framemod600}
 * and uses {@code goldenRatio * mod(float(frameCounter), 3600.0)} as the dither
 * salt for reflection / SSPT / SSAO noise. With the COUNTER bump, LEFT sees
 * {@code K} and RIGHT sees {@code K+1} every frame — every dither / per-pixel
 * stochastic decision falls on opposite sides of its branch on the two eyes,
 * producing per-eye intensity divergence that accumulates through the deferred
 * lighting chain even with TAA, reflections, and voxel lighting all off (the
 * dither sites live in the always-on dither / lightmap / fog blocks). Visible
 * as the persistent right-eye wash-out the user reported across every preset.
 *
 * <p><b>This mixin's mechanism.</b> Hook each {@link ProgramUniforms}
 * instance's {@code update()} at HEAD. Track the last-seen eye on the
 * instance. When the current eye differs from the last-seen eye, invalidate
 * {@code lastFrame} so Iris's existing {@code lastFrame != COUNTER} check
 * triggers PER_FRAME refresh exactly once for this instance on the eye switch.
 * After refresh, store the new eye. End result: PER_FRAME uniforms refresh
 * once per eye iter per program (same as the COUNTER bump), but the shader
 * sees a stable {@code frameCounter} for the whole frame.
 *
 * <p><b>Pairs with removing the COUNTER bump</b> in
 * {@link com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks#irisStartFrameBetweenEyes}.
 * Both pieces must change together — leaving the bump in would re-introduce
 * the per-eye {@code frameCounter} divergence even with this mixin in place.
 */
@Mixin(value = ProgramUniforms.class, remap = false)
public abstract class MixinProgramUniforms {

    @Shadow int lastFrame;

    /** -1 = never seen; 0 = LEFT/MONO; 1 = RIGHT. Stored per ProgramUniforms
     *  instance because PER_FRAME state is per-program. */
    @Unique private int stereoscopic$lastEye = -1;

    @Inject(method = "update()V", at = @At("HEAD"))
    private void stereoscopic$invalidateOnEyeSwitch(CallbackInfo ci) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInWorldPass()) return;
        int eye = s.currentEyeIndex();
        if (eye < 0 || eye > 1) return;
        if (stereoscopic$lastEye != eye) {
            // Invalidate lastFrame so Iris's existing check fires PER_FRAME refresh.
            // -1 is safe — COUNTER's value range is [0, 720720), so -1 never equals it.
            lastFrame = -1;
            stereoscopic$lastEye = eye;
        }
    }
}
