package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Shadow map is sun-direction-driven at thousand-block texel scale — the IPD
 * (~6cm) is below its sampling granularity, so running it twice doubles the
 * most expensive shaderpack work for no visible change. Render it once on the
 * LEFT eye and let the RIGHT eye's gbuffer pass sample that same shadow map.
 *
 * <p>Iris 1.8.12's {@code createShadowModelView} is built purely from sun
 * angle + shadow distance ({@code sunPathRotation, intervalSize, nearPlane,
 * farPlane}); camera position does not feed the shadow matrix, only the
 * geometry-culling frustum. So the LEFT eye's shadow map is already valid for
 * the RIGHT eye — no per-eye camera rewrite is needed.
 */
@Mixin(value = ShadowRenderer.class, remap = false)
public abstract class MixinShadowRenderer {

    @Inject(method = "renderShadows", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$skipShadowsOnRightEye(CallbackInfo ci) {
        if (!PerEyeRenderTargetHooks.IRIS_PRESENT) return;
        if (PerEyeRenderTargetHooks.currentEyeIndex() == 1) {
            ci.cancel();
        }
    }
}
