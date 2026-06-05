package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Supplier;

/**
 * Per-eye history for Iris's {@code MatrixUniforms.Previous} (backs
 * {@code gbufferPreviousModelView} / {@code gbufferPreviousProjection}). With
 * one shared slot under two-pass stereo, LEFT.get() returns frame N-1 and
 * stores frame N; RIGHT.get() then reads LEFT's same-frame stored matrix as
 * its "previous" — zero temporal delta on every reprojection-derived effect
 * (motion blur, TAA, motion vectors) on one side.
 *
 * <p>Each eye needs its own history slot because the IPD offset lives in the
 * matrix (unlike camera position, where the offset is in the position itself
 * and the skip-on-RIGHT pattern in MixinCameraUniforms doesn't transfer).
 *
 * <p>Targets the package-private inner class via string name. Direct port of
 * Angelica stereo-sbs commit {@code c28a73d7}.
 */
@Mixin(targets = "net.irisshaders.iris.uniforms.MatrixUniforms$Previous", remap = false)
public abstract class MixinMatrixUniforms {

    @Shadow @Final private Supplier<Matrix4fc> parent;
    @Shadow private Matrix4f previous;

    @Unique private Matrix4f[] stereoscopic$previousPerEye;

    @Inject(method = "get()Lorg/joml/Matrix4fc;", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$perEyeGet(CallbackInfoReturnable<Matrix4fc> cir) {
        if (!PerEyeRenderTargetHooks.IRIS_PRESENT) return;
        if (StereoState.INSTANCE.stereoEyeCount() != 2) return;

        int eye = StereoState.INSTANCE.currentEyeIndex();
        if (eye < 0 || eye > 1) return;

        if (stereoscopic$previousPerEye == null) {
            // Seed from existing single-bank state so both eyes start coherent.
            stereoscopic$previousPerEye = new Matrix4f[] {
                new Matrix4f(previous), new Matrix4f(previous)
            };
        }

        Matrix4f current = new Matrix4f(parent.get());
        Matrix4f prev    = new Matrix4f(stereoscopic$previousPerEye[eye]);
        stereoscopic$previousPerEye[eye] = current;
        cir.setReturnValue(prev);
    }
}
