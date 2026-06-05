package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.irisshaders.iris.gl.sampler.SamplerBinding;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Iris's {@link SamplerBinding#updateSampler()} binds a texture to a sampler
 * unit via {@code IrisRenderSystem.bindTextureToUnit(target, unit, textureId)}.
 * The {@code textureId} comes from an {@code IntSupplier} captured at pipeline
 * init time — typically a method-reference to a specific
 * {@code DepthTexture}/{@code RenderTarget} instance, so it always returns the
 * LEFT-bank texture id even when the active eye is RIGHT.
 *
 * <p>{@link com.mitchellmarx.stereoscopic.mixin.iris.MixinRenderTargets}'s
 * per-eye bank swap rebinds FBO color attachments (where shaders WRITE) but
 * cannot rewrite captured supplier references (where shaders READ). Without
 * this remap, RIGHT-eye composite passes sample LEFT-bank colortex / depth
 * while writing to RIGHT-bank — view-dependent effects (reflections, TAA
 * history, depth-based composites) surface as RIGHT-eye flicker, with pixels
 * flashing to the colortex clear color where stale samples miss.
 *
 * <p>Gated on {@link StereoState#isInWorldPass()} so vanilla render-type
 * sampler binds during HUD/screen passes (where Iris isn't using its own
 * bank-routed pipeline) don't accidentally trigger the remap with a
 * GL-id-recycled non-colortex texture.
 */
@Mixin(value = SamplerBinding.class, remap = false)
public abstract class MixinSamplerBinding {

    @ModifyArg(
        method = "updateSampler()V",
        at = @At(value = "INVOKE",
                 target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;bindTextureToUnit(III)V",
                 remap = false),
        index = 2,
        remap = false
    )
    private int stereoscopic$remapColortexSamplerTexture(int textureId) {
        if (!StereoState.INSTANCE.isInWorldPass()) return textureId;
        return PerEyeRenderTargetHooks.resolveActiveEyeTexId(textureId);
    }
}
