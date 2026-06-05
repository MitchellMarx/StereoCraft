package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoState;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Per-eye history slots for Iris's {@code CameraPositionTracker}. With one
 * shared slot, LEFT reads RIGHT's N-1 (wrong IPD, stale by a frame) and RIGHT
 * reads LEFT's N (wrong IPD, same frame); any composite that reprojects
 * against {@code previousCameraPosition} surfaces this as an offset/squashed
 * copy in LEFT eye's right half (bloom, motion blur, lens flare).
 *
 * <p>Cancelling {@code update()} on RIGHT instead is wrong: the shift-wraparound
 * mechanism mutates the {@code shift} field and the position fields directly,
 * so running it on one eye and skipping the other leaves the shift state
 * inconsistent between the per-eye uniform reads.
 *
 * <p>Targets the package-private inner class via string name. Direct port of
 * Angelica stereo-sbs commit {@code c28a73d7}.
 */
@Mixin(targets = "net.irisshaders.iris.uniforms.CameraUniforms$CameraPositionTracker", remap = false)
public abstract class MixinCameraUniforms {

    @Shadow private Vector3d previousCameraPosition;
    @Shadow private Vector3d currentCameraPosition;
    @Shadow private Vector3d previousCameraPositionUnshifted;
    @Shadow private Vector3d currentCameraPositionUnshifted;

    @Unique private Vector3d[] stereoscopic$prevPerEye;
    @Unique private Vector3d[] stereoscopic$currPerEye;
    @Unique private Vector3d[] stereoscopic$prevUnshiftedPerEye;
    @Unique private Vector3d[] stereoscopic$currUnshiftedPerEye;

    @Unique
    private boolean stereoscopic$shouldRoutePerEye() {
        if (!PerEyeRenderTargetHooks.IRIS_PRESENT) return false;
        return StereoState.INSTANCE.stereoEyeCount() == 2;
    }

    @Unique
    private void stereoscopic$lazyInit() {
        if (stereoscopic$prevPerEye != null) return;
        // Seed both slots from the single-bank fields so neither eye starts with a zero delta.
        stereoscopic$prevPerEye = new Vector3d[] {
            new Vector3d(previousCameraPosition), new Vector3d(previousCameraPosition)
        };
        stereoscopic$currPerEye = new Vector3d[] {
            new Vector3d(currentCameraPosition), new Vector3d(currentCameraPosition)
        };
        stereoscopic$prevUnshiftedPerEye = new Vector3d[] {
            new Vector3d(previousCameraPositionUnshifted), new Vector3d(previousCameraPositionUnshifted)
        };
        stereoscopic$currUnshiftedPerEye = new Vector3d[] {
            new Vector3d(currentCameraPositionUnshifted), new Vector3d(currentCameraPositionUnshifted)
        };
    }

    // Iris's update() body still runs (we don't cancel) so the shift-wraparound
    // mechanism stays self-consistent in the shared fields. After it runs,
    // snapshot into the active eye's slot; the getters below return that slot.
    @Inject(method = "update", at = @At("RETURN"))
    private void stereoscopic$captureUpdatePerEye(CallbackInfo ci) {
        if (!stereoscopic$shouldRoutePerEye()) return;
        stereoscopic$lazyInit();
        int eye = StereoState.INSTANCE.currentEyeIndex();
        if (eye < 0 || eye > 1) return;
        stereoscopic$prevPerEye[eye].set(stereoscopic$currPerEye[eye]);
        stereoscopic$currPerEye[eye].set(currentCameraPosition);
        stereoscopic$prevUnshiftedPerEye[eye].set(stereoscopic$currUnshiftedPerEye[eye]);
        stereoscopic$currUnshiftedPerEye[eye].set(currentCameraPositionUnshifted);
    }

    @Inject(method = "getCurrentCameraPosition", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$getCurrent(CallbackInfoReturnable<Vector3d> cir) {
        if (!stereoscopic$shouldRoutePerEye() || stereoscopic$currPerEye == null) return;
        int eye = StereoState.INSTANCE.currentEyeIndex();
        if (eye < 0 || eye > 1) return;
        cir.setReturnValue(stereoscopic$currPerEye[eye]);
    }

    @Inject(method = "getPreviousCameraPosition", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$getPrevious(CallbackInfoReturnable<Vector3d> cir) {
        if (!stereoscopic$shouldRoutePerEye() || stereoscopic$prevPerEye == null) return;
        int eye = StereoState.INSTANCE.currentEyeIndex();
        if (eye < 0 || eye > 1) return;
        cir.setReturnValue(stereoscopic$prevPerEye[eye]);
    }

    @Inject(method = "getPreviousCameraPositionUnshifted", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$getPreviousUnshifted(CallbackInfoReturnable<Vector3d> cir) {
        if (!stereoscopic$shouldRoutePerEye() || stereoscopic$prevUnshiftedPerEye == null) return;
        int eye = StereoState.INSTANCE.currentEyeIndex();
        if (eye < 0 || eye > 1) return;
        cir.setReturnValue(stereoscopic$prevUnshiftedPerEye[eye]);
    }
}
