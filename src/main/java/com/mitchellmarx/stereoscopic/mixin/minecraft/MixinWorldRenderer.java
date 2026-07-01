package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Force {@code getCloudsFramebuffer()} to return null while stereo is active
 * so {@link net.minecraft.client.render.CloudRenderer} picks the main-FB
 * branch (= per-eye scratch via the {@code getFramebuffer()} substitution),
 * not the framegraph-allocated cloud FB with its independent freshly-cleared
 * depth attachment.
 *
 * <p>{@code CloudRenderer.renderClouds} chooses its render target by checking
 * the non-null clouds FB before falling back to MC main FB. The framegraph
 * allocates a {@code SimpleFramebufferFactory(W,H,true,0)} when the improved
 * transparency post-effect ("Fabulous") is active. That FB carries its own
 * cleared-to-1.0 depth attachment never written by terrain — clouds would
 * depth-test against an empty depth buffer and every fragment passes, then
 * the resulting cloud color overdraws solid geometry once the transparency
 * post-effect composites it back. Iris normally avoids this by force-disabling
 * Fabulous on shader-pack load, but that's option-driven and depends on the
 * resource-load ordering having fired before the first stereo frame — a
 * pre-flip stereo enable would race it.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinWorldRenderer {

    @Inject(method = "getCloudsTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$forceMainFbForClouds(CallbackInfoReturnable<RenderTarget> cir) {
        if (!StereoState.INSTANCE.isActive()) return;
        if (!PerEyeRenderer.isScratchFbActive()) return;
        cir.setReturnValue(null);
    }

    /**
     * Defer the per-frame {@code LevelRenderState.reset()} past the LEFT eye.
     *
     * <p>MC 26.x extracts entities + block entities once per frame into
     * {@code LevelRenderState.entityRenderStates} / {@code blockEntityRenderStates},
     * and {@code renderLevel} {@code .clear()}s those lists at its end (via
     * {@code reset()}). Our two-pass calls {@code renderLevel} once per eye, so
     * the LEFT pass would drain the lists and the RIGHT eye would render no
     * chests/signs/item frames/paintings (terrain survives — it's Sodium's
     * persistent section data). Skip the reset on the LEFT eye so the RIGHT eye
     * still sees the extracted render states; let it run normally on RIGHT/MONO
     * so per-frame cleanup still happens exactly once.
     */
    @WrapOperation(
        method = "renderLevel",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/renderer/state/level/LevelRenderState;reset()V")
    )
    private void stereoscopic$deferResetUntilLastEye(LevelRenderState state, Operation<Void> original) {
        StereoState s = StereoState.INSTANCE;
        if (s.isActive() && s.getCurrentEye() == StereoState.Eye.LEFT) return;
        original.call(state);
    }

    /**
     * Same one-shot-state-between-eyes defer as
     * {@link #stereoscopic$deferResetUntilLastEye}, but for particles.
     *
     * <p>{@code LevelRenderer.extractLevel} populates
     * {@code LevelRenderState.particlesRenderState} once per frame (via
     * {@code ParticleEngine.extract}); {@code renderLevel} (called once per eye)
     * submits them then calls {@code particlesRenderState.reset()} to clear.
     * Crucially, {@code LevelRenderState.reset()} does NOT touch
     * {@code particlesRenderState} — the particle reset is a SEPARATE call — so
     * the deferral above misses it. Without this second wrap, the LEFT eye
     * drains the particle list and the RIGHT eye renders no particles.
     *
     * <p>Skip the reset on the LEFT eye so the RIGHT eye still sees the
     * extracted particle states; let it run on RIGHT/MONO so per-frame cleanup
     * still happens exactly once.
     */
    @WrapOperation(
        method = "renderLevel",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/renderer/state/level/ParticlesRenderState;reset()V")
    )
    private void stereoscopic$deferParticleResetUntilLastEye(ParticlesRenderState state, Operation<Void> original) {
        StereoState s = StereoState.INSTANCE;
        if (s.isActive() && s.getCurrentEye() == StereoState.Eye.LEFT) return;
        original.call(state);
    }
}
