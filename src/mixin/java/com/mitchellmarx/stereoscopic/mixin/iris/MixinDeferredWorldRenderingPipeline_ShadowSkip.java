package com.mitchellmarx.stereoscopic.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.coderbot.iris.pipeline.DeferredWorldRenderingPipeline;
import net.coderbot.iris.shadows.ShadowRenderTargets;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two-part skip of the Iris shadow pass on the RIGHT eye:
 *
 * <ol>
 *   <li>{@code renderShadows} HEAD-inject — cancel the entire shadow render on RIGHT.
 *       The shadow map is rendered from the sun/moon POV (not the player camera), so
 *       it's identical for both eyes; LEFT's shadow framebuffer is still bound when
 *       RIGHT runs. Sbs2 measured ~35% of frame-time savings from this skip with
 *       shaders that ship a shadow pass.</li>
 *   <li>{@code prepareRenderTargets} field-read wrap — return null for
 *       {@code shadowRenderTargets} on RIGHT eye, which makes the
 *       {@code if (shadowRenderTargets != null) { ... }} branch guarding the
 *       shadow-target clear logic fall through. Without this, the RIGHT eye's
 *       {@code prepareRenderTargets} would wipe the LEFT eye's shadow map and the
 *       right eye would sample an empty shadow.</li>
 * </ol>
 *
 * <p>Port of sbs2 commit {@code 936840f9}. The sbs2 fork inlines the second hunk into
 * Angelica's own {@code DeferredWorldRenderingPipeline.java} source; here we apply it
 * externally via {@link WrapOperation} (available via Angelica's transitive dependency
 * on {@code mixinextras-fabric}).
 */
@Mixin(value = DeferredWorldRenderingPipeline.class, remap = false)
public abstract class MixinDeferredWorldRenderingPipeline_ShadowSkip {

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true, remap = false)
    private void stereoscopic$skipShadowRenderOnRightEye(CallbackInfo ci) {
        if (StereoState.INSTANCE.isActive()
            && StereoState.INSTANCE.getCurrentEye() == StereoState.Eye.RIGHT) {
            ci.cancel();
        }
    }

    @WrapOperation(
        method = "prepareRenderTargets",
        at = @At(
            value = "FIELD",
            target = "Lnet/coderbot/iris/pipeline/DeferredWorldRenderingPipeline;shadowRenderTargets:Lnet/coderbot/iris/shadows/ShadowRenderTargets;",
            opcode = Opcodes.GETFIELD,
            remap = false
        )
    )
    private ShadowRenderTargets stereoscopic$nullShadowTargetsOnRightEye(
            DeferredWorldRenderingPipeline self,
            Operation<ShadowRenderTargets> original) {
        final ShadowRenderTargets real = original.call(self);
        if (real != null
            && StereoState.INSTANCE.isActive()
            && StereoState.INSTANCE.getCurrentEye() == StereoState.Eye.RIGHT) {
            return null;
        }
        return real;
    }
}
