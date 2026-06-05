package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.mixin.minecraft.CameraAccessor;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shadow map is sun-direction-driven at thousand-block texel scale — IPD/2
 * (~3cm) is below its sampling granularity, so running it twice doubles the
 * most expensive shaderpack work for no visible change. Skip on RIGHT eye and
 * let RIGHT's gbuffer pass sample the LEFT eye's shadow map.
 *
 * <p>The LEFT eye render must use the MONO camera position, not the LEFT-eye
 * offset, or the single shadow map ends up biased toward the LEFT eye and
 * miscovers the RIGHT eye. Restore mono pos around renderShadows so Iris
 * consumers reading {@code Camera.getPosition()} during the shadow pass
 * (frustum culling, entity proximity, etc.) see mono.
 */
@Mixin(value = ShadowRenderer.class, remap = false)
public abstract class MixinShadowRenderer {

    @Unique private static Vec3 stereoscopic$savedEyePos;

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$skipShadowsOnRightEye(CallbackInfo ci) {
        if (!PerEyeRenderTargetHooks.IRIS_PRESENT) return;
        if (PerEyeRenderTargetHooks.currentEyeIndex() == 1) {
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

    @Inject(method = "renderShadows", at = @At("RETURN"))
    private void stereoscopic$restoreEyePosAfterShadows(CallbackInfo ci) {
        if (stereoscopic$savedEyePos == null) return;
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        ((CameraAccessor)(Object)camera).stereoscopic$setPos(stereoscopic$savedEyePos);
        stereoscopic$savedEyePos = null;
    }
}
