package com.mitchellmarx.stereoscopic.mixin.sodium;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skip Sodium's chunk visibility BFS on the second eye.
 *
 * <p>The chunk graph is keyed on camera position + frustum, both effectively identical
 * between eyes: the IPD offset lives in the modelview matrix translation, not in the
 * camera position or frustum used for chunk culling. LEFT-eye's visible-chunks list is
 * therefore correct for RIGHT eye too — re-running BFS, cull, and chunk upload would burn
 * ~half the chunk-update work for no visible difference.
 *
 * <p>Angelica's {@code sodium.MixinRenderGlobal} already {@code @Overwrite}s this same
 * method (it's how Celeritas swaps the chunk renderer). HEAD-injecting onto an
 * {@code @Overwrite}ed method lands at the start of the post-merge body, which IS
 * Angelica's overwrite body, so this still works. Priority 900 (lower = applied first)
 * is set defensively so our cancel runs before Angelica's overwrite body executes any
 * meaningful work.
 *
 * <p>Known caveat (handoff memory project_sodium_chunk_skip): at extreme IPDs or very
 * small chunk sizes, the RIGHT eye's frustum could theoretically include chunks the LEFT
 * eye's frustum excluded. At 64 mm IPD and 16-block chunk granularity the divergence is
 * sub-pixel and unobservable; if a tester reports a missing-chunk artifact, widen the
 * LEFT eye's frustum to cover both eyes instead of removing this skip.
 *
 * <p>Port of sbs2 commit {@code aa91edc5}.
 */
@Mixin(value = RenderGlobal.class, priority = 900)
public abstract class MixinRenderGlobal_StereoChunkSkip {

    @Inject(method = "clipRenderersByFrustum", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$skipChunkUpdateOnSecondEye(ICamera camera, float partialTicks, CallbackInfo ci) {
        if (StereoState.INSTANCE.isActive()
            && StereoState.INSTANCE.getCurrentEye() == StereoState.Eye.RIGHT) {
            ci.cancel();
        }
    }
}
