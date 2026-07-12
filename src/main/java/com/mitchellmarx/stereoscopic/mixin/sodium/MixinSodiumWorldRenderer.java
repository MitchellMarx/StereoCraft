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
 *   <li><b>Mono-pos restore</b>: LEFT runs while {@code Camera.pos} is at the
 *       LEFT eye. This restores the mono position for the duration of
 *       setupTerrain so Sodium's {@code RenderSectionManager.updateCameraState}
 *       (BFS start section, incremental graph state, translucency sorting)
 *       stays eye-agnostic between frames. <b>Note:</b> this does NOT re-center
 *       the frustum the per-section cull tests against — that {@code Viewport}
 *       is built from the vanilla cull frustum BEFORE setupTerrain runs, baked
 *       from the LEFT eye's origin and sheared projection. Both-eye visibility
 *       coverage is provided by the margin in {@code MixinOcclusionCuller},
 *       which sizes for the FULL eye separation precisely because the cull
 *       frustum is LEFT-baked (see that class's javadoc for the bytecode
 *       trace).</li>
 * </ul>
 *
 * <p>Sodium 0.6.13 confirmed signature:
 * {@code public void setupTerrain(net.minecraft.client.Camera, net.caffeinemc.mods.sodium.client.render.viewport.Viewport, boolean, boolean)}.
 * The HEAD/RETURN injects bind by method name regardless of args.
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
        stereoscopic$savedEyePos = camera.getPosition();
        CameraAccessor acc = (CameraAccessor)(Object)camera;
        acc.stereoscopic$setPositionField(monoPos);
        acc.stereoscopic$getBlockPositionField().set(monoPos.x, monoPos.y, monoPos.z);
    }

    @Inject(method = "setupTerrain", at = @At("RETURN"))
    private void stereoscopic$restoreEyePosAfterSetup(CallbackInfo ci) {
        if (stereoscopic$savedEyePos == null) return;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 p = stereoscopic$savedEyePos;
        CameraAccessor acc = (CameraAccessor)(Object)camera;
        acc.stereoscopic$setPositionField(p);
        acc.stereoscopic$getBlockPositionField().set(p.x, p.y, p.z);
        stereoscopic$savedEyePos = null;
    }
}
