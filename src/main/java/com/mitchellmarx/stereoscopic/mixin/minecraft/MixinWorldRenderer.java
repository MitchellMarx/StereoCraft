package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Force {@code cloudsTarget()} to return null while stereo is active
 * so {@link net.minecraft.client.renderer.CloudRenderer} picks the main-FB
 * branch (= per-eye scratch via the {@code mainRenderTarget()} substitution),
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
 *
 * <p><b>26.2 note — the drain moved, it didn't disappear.</b> The 26.1.2 port
 * deferred {@code LevelRenderState.reset()} past the LEFT eye because
 * {@code renderLevel} drained the extracted render states at its end. In 26.2
 * extraction moved out of the render path ({@code LevelExtractor.extract()}
 * resets-then-fills once per frame BEFORE the per-eye loop) — but
 * {@code submitFeatures} now {@code clear()}s each render-state list inline
 * right after submitting it (entityRenderStates, blockEntityRenderStates,
 * blockBreakingRenderStates). Same one-shot-state bug class, new site: the
 * LEFT eye's submit wipes the lists and the RIGHT eye renders no
 * entities/chests/signs/block-cracks. {@code ParticlesRenderState.submit}
 * iterates without draining, so particles need no handling in 26.2.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinWorldRenderer {

    @Inject(method = "cloudsTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$forceMainFbForClouds(CallbackInfoReturnable<RenderTarget> cir) {
        if (!StereoState.INSTANCE.isActive()) return;
        if (!PerEyeRenderer.isScratchFbActive()) return;
        cir.setReturnValue(null);
    }

    /**
     * Defer the three post-submit {@code List.clear()} drains in
     * {@code submitFeatures} past the LEFT eye. One wrap matches all three
     * invokes; they run normally on RIGHT/MONO so per-frame cleanup still
     * happens exactly once per frame.
     */
    @WrapOperation(
        method = "submitFeatures(Lnet/minecraft/client/renderer/state/level/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;Z)V",
        at = @At(value = "INVOKE", target = "Ljava/util/List;clear()V")
    )
    private void stereoscopic$deferSubmitDrainUntilLastEye(List<?> list, Operation<Void> original) {
        StereoState s = StereoState.INSTANCE;
        if (s.isActive() && s.getCurrentEye() == StereoState.Eye.LEFT) return;
        original.call(list);
    }
}
