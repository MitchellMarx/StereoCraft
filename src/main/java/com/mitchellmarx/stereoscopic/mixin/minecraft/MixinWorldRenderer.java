package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * <p><b>26.3 note — no clouds-target override.</b> 26.2 forced
 * {@code cloudsTarget()} to null so clouds drew into the per-eye scratch
 * instead of the framegraph's separate Fabulous cloud FB (own cleared depth).
 * 26.3 replaced Fabulous with OIT and removed {@code cloudsTarget()} entirely:
 * {@code CloudRenderer.render} draws into the RenderPass it is handed, and the
 * OIT path reads depth via {@code GameRenderer.mainRenderTarget()} (= scratch
 * during an eye pass), so there is nothing left to redirect.
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
