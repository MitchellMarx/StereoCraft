package com.mitchellmarx.stereoscopic.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Disable the per-eye scissor at the handoff from vanilla {@code renderWorld}
 * to Iris's deferred pipeline.
 *
 * <p>{@link PerEyeRenderer#renderOneEye} sets eye-rect scissor to protect MC's
 * main FB during the vanilla pre-Iris portion of {@code WorldRenderer.render}.
 * Once Iris takes over, that scissor is actively harmful: every composite
 * pass writes to its own per-eye colortex bank (no shared FB to protect), and
 * pinning the bank writes to the eye-half leaves the rest empty — the final
 * pass then samples 0..1 across the bank and reads the never-written half as
 * black/junk.
 *
 * <p>Mirrors Angelica {@code stereo-sbs}'s
 * {@code DeferredWorldRenderingPipeline.beginLevelRendering} which has the
 * same {@code glDisable(GL_SCISSOR_TEST)} at the same handoff. Scissor is
 * re-engaged by {@link MixinFinalPassRenderer} on the final pass itself.
 */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public abstract class MixinIrisRenderingPipeline {

    @Inject(method = "beginLevelRendering", at = @At("HEAD"))
    private void stereoscopic$dropScissorAtIrisHandoff(CallbackInfo ci) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInWorldPass()) return;
        PerEyeRenderer.scissorDisableRaw();
    }

    /**
     * Skip the shadow-clear block on the RIGHT eye. {@code beginLevelRendering}
     * runs per {@code renderLevel} call (twice per stereo frame), and the shadow
     * branch clears {@code shadowtex0}/{@code shadowtex1} unconditionally on
     * entry. With {@link MixinShadowRenderer} canceling {@code renderShadows}
     * on RIGHT eye, that clear wipes the LEFT eye's shadow map before RIGHT's
     * gbuffer samples it — visible as zero shadows in the right half.
     *
     * <p>Returning null from the {@code shadowRenderTargets} field read at the
     * outer {@code if (shadowRenderTargets != null)} short-circuits the entire
     * clear/compute block without touching the rest of beginLevelRendering
     * (image clears, custom uniforms, frame notifier — all per-eye-correct).
     */
    @WrapOperation(
        method = "beginLevelRendering",
        at = @At(value = "FIELD",
                 target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;shadowRenderTargets:Lnet/irisshaders/iris/shadows/ShadowRenderTargets;",
                 opcode = org.objectweb.asm.Opcodes.GETFIELD,
                 ordinal = 0)
    )
    private ShadowRenderTargets stereoscopic$skipShadowClearOnRightEye(
            IrisRenderingPipeline self, Operation<ShadowRenderTargets> original) {
        StereoState s = StereoState.INSTANCE;
        if (s.isActive() && s.currentEyeIndex() == 1) return null;
        return original.call(self);
    }
}
