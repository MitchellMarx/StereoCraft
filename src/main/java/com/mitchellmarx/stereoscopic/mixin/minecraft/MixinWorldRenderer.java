package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
 * <p><b>26.2 note — the reset-deferral machinery is gone.</b> The 26.1.2 port
 * deferred {@code LevelRenderState.reset()} past the LEFT eye (and cancelled
 * {@code ParticlesRenderState.reset()} there) because {@code renderLevel}
 * drained the extracted render states at its end, leaving the RIGHT eye with
 * no entities/block-entities/particles. In 26.2 extraction moved out of the
 * render path entirely: {@code LevelExtractor.extract()} (called from
 * {@code GameRenderer.extract()}, once per frame, BEFORE the per-eye
 * {@code renderLevel} loop) resets-then-fills {@code LevelRenderState}, and
 * {@code renderLevel} no longer calls {@code reset()} at all. Both eyes read
 * the same per-frame extraction naturally; there is nothing to defer.
 */
@Mixin(LevelRenderer.class)
public abstract class MixinWorldRenderer {

    @Inject(method = "cloudsTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$forceMainFbForClouds(CallbackInfoReturnable<RenderTarget> cir) {
        if (!StereoState.INSTANCE.isActive()) return;
        if (!PerEyeRenderer.isScratchFbActive()) return;
        cir.setReturnValue(null);
    }
}
