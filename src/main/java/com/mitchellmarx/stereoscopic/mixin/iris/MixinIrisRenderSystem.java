package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.irisshaders.iris.gl.IrisRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Single chokepoint for per-eye colortex / depth-attachment remap on Iris
 * texture bindings. {@code ExtendedShader.apply()},
 * {@code FallbackShader.apply()}, {@code SodiumShader.apply()},
 * {@code IrisLodRenderProgram}, and {@code IrisGenericRenderProgram} all
 * call {@code IrisRenderSystem.bindTextureToUnit} directly without going
 * through {@code SamplerBinding}. Hooking the static method covers every site.
 *
 * <p>The companion {@code MixinSamplerBinding} is intentionally NOT ported on
 * this branch: Iris 1.10.x renamed {@code SamplerBinding.update} →
 * {@code updateSampler}, and this static chokepoint already covers every read
 * path (SamplerBinding ultimately dispatches through
 * {@code IrisRenderSystem.bindTextureToUnit} too). The remap goes through
 * {@link PerEyeRenderTargetHooks#resolveActiveEyeTexId}, which is idempotent
 * (LEFT/RIGHT bank id → active-eye bank id regardless of input).
 *
 * <p>Gated on {@link StereoState#isInWorldPass}: outside world rendering,
 * a colliding GL-id (e.g. vanilla atlas getting an id recycled from a
 * destroyed colortex bank texture) would mis-remap; bank routing is only
 * meaningful while Iris's per-eye world iter is mid-flight.
 */
@Mixin(value = IrisRenderSystem.class, remap = false)
public abstract class MixinIrisRenderSystem {

    @ModifyArg(
        method = "bindTextureToUnit(III)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/irisshaders/iris/gl/IrisRenderSystem$DSAAccess;bindTextureToUnit(III)V",
                 remap = false),
        index = 2,
        remap = false
    )
    private static int stereoscopic$remapActiveEyeTexture(int textureId) {
        if (!StereoState.INSTANCE.isInWorldPass()) return textureId;
        return PerEyeRenderTargetHooks.resolveActiveEyeTexId(textureId);
    }

    /**
     * Image-sampler binds (compute shaders, read/write image attachments).
     * Iris's {@code net.irisshaders.iris.gl.image.ImageBinding#update}
     * captures a texture-id supplier — typically the LEFT-bank colortex /
     * voxel image id — so RIGHT eye's compute passes (shadowcomp, voxel
     * lighting) sample the wrong bank without this remap.
     *
     * <p>Modify the parameter at HEAD via {@code @ModifyVariable} so both
     * the GL 4.2 and {@code EXTShaderImageLoadStore} fallback paths inside
     * the method body see the remapped id.
     */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(
        method = "bindImageTexture(IIIZIII)V",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 1,
        remap = false
    )
    private static int stereoscopic$remapActiveEyeImageTexture(int textureId) {
        if (!StereoState.INSTANCE.isInWorldPass()) return textureId;
        return PerEyeRenderTargetHooks.resolveActiveEyeTexId(textureId);
    }
}
