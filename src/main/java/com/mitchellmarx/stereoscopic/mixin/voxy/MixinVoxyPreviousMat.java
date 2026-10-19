package com.mitchellmarx.stereoscopic.mixin.voxy;

import com.mitchellmarx.stereoscopic.core.StereoState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per-eye history slots for Voxy's previous-frame shader uniforms
 * ({@code vxViewProjPrev}, {@code vxModelViewPrev}, {@code vxProjPrev}).
 * {@code PreviousMat.get()} returns the value from its last call and stores
 * the current one; Iris refreshes these once per world render, i.e. once per
 * eye. With one slot RIGHT gets LEFT's current matrices as "previous", so a
 * pack's TAA reprojects Voxy terrain from the other eye — measured as a
 * +0.4..1 px convergence shift on Voxy terrain only, gone with TAA off.
 * Same one-history-slot problem as {@code MixinCameraUniforms} (Iris).
 *
 * <p>Swap the active eye's slot into {@code previous} before the body runs
 * and save the new value back after, so each eye chains its own history.
 */
@Mixin(targets = "me.cortex.voxy.client.iris.VoxyUniforms$PreviousMat", remap = false)
public abstract class MixinVoxyPreviousMat {

    @Shadow private Matrix4f previous;

    @Unique private final Matrix4f[] stereoscopic$previousPerEye = { null, null };

    @Inject(method = "get()Lorg/joml/Matrix4fc;", at = @At("HEAD"))
    private void stereoscopic$loadEyeHistory(CallbackInfoReturnable<Matrix4fc> cir) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive()) return;
        Matrix4f slot = stereoscopic$previousPerEye[s.currentEyeIndex()];
        if (slot != null) previous = slot;
    }

    @Inject(method = "get()Lorg/joml/Matrix4fc;", at = @At("RETURN"))
    private void stereoscopic$saveEyeHistory(CallbackInfoReturnable<Matrix4fc> cir) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive()) return;
        stereoscopic$previousPerEye[s.currentEyeIndex()] = previous;
    }
}
