package com.mitchellmarx.stereoscopic.mixin.sodium;

import com.mitchellmarx.stereoscopic.compat.sodium.SecondEyeSkipHooks;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.mixin.minecraft.CameraAccessor;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two stereo-correctness handlers on {@code setupTerrain}:
 *
 * <ul>
 *   <li><b>Skip on RIGHT eye</b> (perf): setupTerrain bundles the full
 *       chunk-graph chain. Running once per stereo frame is enough — LEFT's
 *       output (visible-section list, chunk buffers) feeds RIGHT's render.</li>
 *   <li><b>Mono-pos visibility</b>: LEFT runs while {@code Camera.pos} is at
 *       the LEFT eye. Its frustum drops chunks visible only to the RIGHT eye's
 *       IPD-shifted frustum — sky-color gaps in RIGHT where geometry should be.
 *       Restore mono pos for the duration of setupTerrain so the visible-section
 *       list covers both eyes' frusta (IPD ~6cm is three orders of magnitude
 *       smaller than a chunk).</li>
 * </ul>
 *
 * <p><b>Caveat — Viewport frustum is not widened.</b> The mono-pos restore
 * only widens {@code setupTerrain}'s direct {@code Camera.getCameraPos()}
 * reads (notably {@code prepareFrame} and section-nearby logic). It does NOT
 * widen the pre-built {@code Viewport}'s frustum, which is constructed
 * upstream from the LEFT-eye camera and arrives at setupTerrain already
 * baked. The safety margin for chunks visible only to the RIGHT eye comes
 * from Sodium's section-padded {@code Viewport} margin
 * ({@code CHUNK_SECTION_PADDED_RADIUS} et al.) being much larger than IPD/2.
 * A future Sodium release that tightens that margin would re-surface the
 * chunk-gap symptom this fix addresses — preserve that invariant when
 * auditing/upgrading Sodium.
 */
@Mixin(SodiumWorldRenderer.class)
public abstract class MixinSodiumWorldRenderer {

    @Unique private static Vec3 stereoscopic$savedEyePos;

    @Inject(method = "setupTerrain", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$skipSetupOnRightEye(CallbackInfo ci) {
        if (SecondEyeSkipHooks.shouldSkipChunkUploadThisFrame()) {
            ci.cancel();
            return;
        }
        if (!StereoState.INSTANCE.isActive()) return;
        Vec3 monoPos = StereoState.INSTANCE.getFrameMonoCameraPos();
        if (monoPos == null) return;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        stereoscopic$savedEyePos = camera.position();
        ((CameraAccessor)(Object)camera).stereoscopic$setPos(monoPos);
    }

    @Inject(method = "setupTerrain", at = @At("RETURN"))
    private void stereoscopic$restoreEyePosAfterSetup(CallbackInfo ci) {
        if (stereoscopic$savedEyePos == null) return;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        ((CameraAccessor)(Object)camera).stereoscopic$setPos(stereoscopic$savedEyePos);
        stereoscopic$savedEyePos = null;
    }
}
