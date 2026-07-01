package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-eye particle deferral — the particle half of the one-shot-state-between-eyes
 * problem ({@link MixinWorldRenderer#stereoscopic$deferResetUntilLastEye} handles
 * entities / block entities).
 *
 * <p>MC 26.x extracts particles once per frame into
 * {@code LevelRenderState.particlesRenderState}; the main render pass submits them
 * (draws) then calls {@code reset()} to clear the list. Our two-pass renders the
 * world once per eye, so the LEFT eye would draw the particles and then clear the
 * list, leaving the RIGHT eye with none.
 *
 * <p><b>Why intercept {@code reset()} directly instead of wrapping its call site.</b>
 * That call lives inside a framegraph main-pass lambda
 * ({@code LevelRenderer.lambda$addMainPass$0}), not in {@code renderLevel}. Targeting
 * a synthetic lambda by name is fragile — the name can shift with a recompile or when
 * Sodium/Iris restructure the pass, and a missed {@code @WrapOperation} target on
 * {@code renderLevel} is a critical injection failure that crashes on load. Hooking
 * {@code ParticlesRenderState.reset()} itself catches the drain wherever it's called
 * from, and {@code reset()} is a stable named method so the injection always resolves.
 *
 * <p>Cancel the reset on the LEFT eye so the RIGHT eye still has the particle list;
 * let it run on RIGHT / MONO so the per-frame clear still happens exactly once. The
 * {@code isActive() && LEFT} guard confines the cancel to the LEFT eye of a stereo
 * world pass, so non-stereo and any unrelated reset callers are untouched.
 */
@Mixin(ParticlesRenderState.class)
public abstract class MixinParticlesRenderState {

    @Inject(method = "reset()V", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$deferResetUntilLastEye(CallbackInfo ci) {
        StereoState s = StereoState.INSTANCE;
        if (s.isActive() && s.getCurrentEye() == StereoState.Eye.LEFT) {
            ci.cancel();
        }
    }
}
