package com.mitchellmarx.stereoscopic.mixin.iris;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import net.irisshaders.iris.pipeline.FinalPassRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Per-eye final-pass adaptations for Iris 1.8.12.
 *
 * <p>Iris 1.8.12's {@code renderFinalPass()} writes to MC's main FB directly
 * via {@code Minecraft.getMainRenderTarget()} — no {@code CommandEncoder} /
 * {@code RenderPass} machinery (those arrived in MC 1.21.6+). Our
 * {@link com.mitchellmarx.stereoscopic.mixin.minecraft.MixinMinecraftClient}
 * redirects {@code getMainRenderTarget()} to the per-eye scratch FB during
 * the world wrap, so Iris's final pass output lands in scratch automatically;
 * {@link com.mitchellmarx.stereoscopic.mixin.minecraft.MixinGameRenderer}
 * then blits scratch → eye-half of MC main FB.
 *
 * <p>What this mixin still has to do:
 * <ul>
 *   <li><b>SwapPass.targetTexture remap</b> — captured at pipeline build as
 *       LEFT-bank MAIN, never updated by {@link MixinRenderTargets#stereoscopic$setActiveEye}'s
 *       framebuffer-attachment walk. Without remap, the per-frame ALT→MAIN
 *       swap that backs Complementary's {@code colortex7} reflection cache
 *       (and {@code colortex2} TAA history) writes RIGHT-eye ALT data into
 *       LEFT-bank MAIN every frame, leaving RIGHT-bank MAIN never written.</li>
 *   <li><b>Path B copyTexSubImage2D per-eye remap</b> — fires when the
 *       shaderpack has no {@code final.fsh}. Iris does a direct
 *       {@code copyTexSubImage2D(baseline → main)} to the eye-half rect.
 *       With outer scratch active the destination is outer scratch's color
 *       (substituted by MixinMinecraftClient); pass through full-window so
 *       the outer wrap squishes to the eye-half. Without outer scratch,
 *       remap to the eye-rect.</li>
 * </ul>
 */
@Mixin(value = FinalPassRenderer.class, remap = false)
public abstract class MixinFinalPassRenderer {

    /**
     * Remap the ALT→MAIN swap-pass target texture per active eye.
     *
     * <p>{@code FinalPassRenderer$SwapPass.targetTexture} is captured at pipeline
     * construction as LEFT-bank MAIN of the swap slot and never updated when
     * the active eye changes. The corresponding {@code from} framebuffer's
     * color attachment IS rebound per eye by
     * {@link MixinRenderTargets#stereoscopic$setActiveEye} because {@code from}
     * lives in {@code ownedFramebuffers}, but {@code targetTexture} is a raw
     * int field outside that machinery.
     *
     * <p>In LEFT iter: copies LEFT-ALT → LEFT-MAIN (correct)
     * <br>In RIGHT iter without remap: copies RIGHT-ALT → LEFT-MAIN (wrong)
     *
     * <p>End result without this mixin: LEFT-MAIN ends up holding RIGHT eye's
     * previous-frame reflection (RIGHT iter runs after LEFT and overwrites).
     * RIGHT-MAIN never gets written. Next frame Complementary's PBR_REFLECTIONS
     * temporal block reads {@code colortex7} MAIN as {@code prevRef}: LEFT eye
     * samples RIGHT's stale reflection (wrong eye), RIGHT eye samples zeros.
     */
    @ModifyExpressionValue(
        method = "renderFinalPass",
        at = @At(value = "FIELD",
                 target = "Lnet/irisshaders/iris/pipeline/FinalPassRenderer$SwapPass;targetTexture:I",
                 opcode = org.objectweb.asm.Opcodes.GETFIELD)
    )
    private int stereoscopic$remapSwapTargetTexture(int targetTextureId) {
        if (!stereoscopic$eyeActive()) return targetTextureId;
        return PerEyeRenderTargetHooks.resolveActiveEyeTexId(targetTextureId);
    }

    /**
     * Path B — no shader-pack {@code final.fsh}; Iris does a direct
     * {@code copyTexSubImage2D(baseline -> main color tex)}. With outer scratch
     * active the destination is outer scratch's color (substituted by
     * MixinMinecraftClient); pass through full-window so the outer wrap
     * squishes to the eye-half. Without outer scratch, remap to the eye-rect.
     */
    @ModifyArgs(
        method = "renderFinalPass",
        at = @At(value = "INVOKE",
                 target = "Lnet/irisshaders/iris/gl/IrisRenderSystem;copyTexSubImage2D(IIIIIIIII)V",
                 remap = false)
    )
    private void stereoscopic$perEyeCopy(Args args) {
        if (!stereoscopic$eyeActive()) return;
        if (PerEyeRenderer.isScratchFbActive()) return;
        StereoState s = StereoState.INSTANCE;
        int x = s.getEyeVpX();
        int w = s.getEyeVpW();
        int h = s.getEyeVpH();
        args.set(3, x);
        args.set(5, x);
        args.set(7, w);
        args.set(8, h);
    }

    @Unique
    private static boolean stereoscopic$eyeActive() {
        if (!PerEyeRenderTargetHooks.IRIS_PRESENT) return false;
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive()) return false;
        return s.getCurrentEye() != StereoState.Eye.MONO;
    }
}
