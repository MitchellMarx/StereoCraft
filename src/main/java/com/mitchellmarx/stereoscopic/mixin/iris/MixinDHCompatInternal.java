package com.mitchellmarx.stereoscopic.mixin.iris;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.irisshaders.iris.compat.dh.DHCompatInternal;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Force Iris's DH depth texture to track window resizes under stereo
 * (issue: DH LODs + DH-gated shader clouds render in front of everything
 * after a window resize; toggling DH off/on mitigates).
 *
 * <p>{@code DHCompatInternal.reconnectDHTextures} decides whether to
 * recreate {@code depthTexNoTranslucent} (the {@code dhDepthTex1} the
 * shaderpack samples) by comparing {@code
 * gameRenderer.mainRenderTarget().iris$getDepthBufferVersion()} against a
 * cached int. That accessor goes through the per-eye scratch substitution,
 * so the cached value aliases two INDEPENDENT per-RenderTarget counters
 * (scratch vs real main) with no object identity — the same defect class as
 * the {@code cachedDepthBufferVersion} guard in {@link MixinRenderTargets}.
 * On a window resize both counters bump independently and can land equal to
 * the cached value, silently skipping {@code createDepthTex} — leaving
 * dhDepthTex1 at the old resolution. BSL/Complementary's DH branches then
 * sample a wrong-sized depth texture and the LOD/cloud march overdraws
 * solid geometry. Mono Iris can't hit this (single target object), and
 * DH off/on re-runs the reconnect from a clean state — matching the
 * observed mitigation.
 *
 * <p>Fix: poison {@code cachedVersion} at reconnect HEAD whenever the
 * current target's dimensions differ from the last reconnect — dimensions
 * are identical between scratch and real main, so they're alias-free, and
 * a dims change is exactly the condition {@code createDepthTex} exists to
 * handle. Worst case is one redundant depth-tex recreate.
 */
@Mixin(value = DHCompatInternal.class, remap = false)
public abstract class MixinDHCompatInternal {

    @Shadow private int cachedVersion;

    @Unique private int stereoscopic$lastDepthTexW = -1;
    @Unique private int stereoscopic$lastDepthTexH = -1;

    @Inject(method = "reconnectDHTextures", at = @At("HEAD"))
    private void stereoscopic$forceDepthTexRecreateOnResize(int depthTex, CallbackInfo ci) {
        RenderTarget target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (target == null) return;
        if (target.width != stereoscopic$lastDepthTexW || target.height != stereoscopic$lastDepthTexH) {
            stereoscopic$lastDepthTexW = target.width;
            stereoscopic$lastDepthTexH = target.height;
            // Any value the live counters can't equal; the body then takes the
            // version-changed branch and recreates depthTexNoTranslucent at
            // the current dimensions.
            cachedVersion = Integer.MIN_VALUE;
        }
    }
}
