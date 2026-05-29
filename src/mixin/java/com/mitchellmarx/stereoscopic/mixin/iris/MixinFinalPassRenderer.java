package com.mitchellmarx.stereoscopic.mixin.iris;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.coderbot.iris.postprocess.FinalPassRenderer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Remap the ALT→MAIN swap-pass target texture per active eye.
 *
 * <p>{@code FinalPassRenderer$SwapPass.targetTexture} is captured at pipeline construction as
 * LEFT-bank MAIN of the swap slot and never updated when the active eye changes. The
 * corresponding {@code from} framebuffer's color attachment IS rebound per eye by
 * {@link MixinRenderTargets#stereoscopic$setActiveEye(int)} because {@code from} lives in
 * {@code ownedFramebuffers}, but {@code targetTexture} is a raw int field outside that machinery.
 *
 * <p>The bug this causes: composite0 writes ALT of colortex7 each eye iter (per the BufferFlipper
 * layout for slots with no prior writers). At end of each eye's {@code finalPass}, Iris runs the
 * swap loop:
 * <ul>
 *   <li>Bind {@code from} framebuffer (its color attachment IS active-eye's ALT after our
 *       per-eye rebind).</li>
 *   <li>Bind {@code targetTexture} — captured LEFT-bank MAIN, NOT updated.</li>
 *   <li>{@code glCopyTexSubImage2D} copies from-fb-ALT into bound 2D tex.</li>
 * </ul>
 *
 * <p>In LEFT iter: copies LEFT-ALT → LEFT-MAIN ✓<br>
 * In RIGHT iter: copies RIGHT-ALT → LEFT-MAIN ✗
 *
 * <p>End result: LEFT-MAIN ends up holding RIGHT eye's previous-frame reflection (RIGHT iter runs
 * after LEFT and overwrites). RIGHT-MAIN never gets written. Next frame, composite0's
 * PBR_REFLECTIONS temporal block reads {@code colortex7} MAIN as {@code prevRef}: LEFT eye
 * samples RIGHT's stale reflection (wrong eye), RIGHT eye samples zeros. The {@code prevValid}
 * heuristic weights {@code mix} heavily toward {@code prevRef} when stationary, producing
 * visible per-eye misalignment that resolves only on camera turn (when {@code pixelMovement}
 * drops {@code prevValid} and the mix swings to current-frame ray-march).
 *
 * <p>Hooks the {@code GETFIELD SwapPass.targetTexture} instruction inside {@code renderFinalPass}
 * via {@code @ModifyExpressionValue}. The field is read exactly once per swap-pass iteration
 * (right before the {@code glBindTexture} call), so this fires once per slot per eye iter. Same
 * fix automatically covers {@code colortex2} (TAA history) — any non-cleared slot with a SwapPass
 * gets remapped.
 */
@Mixin(value = FinalPassRenderer.class, remap = false)
public abstract class MixinFinalPassRenderer {

    @ModifyExpressionValue(
        method = "renderFinalPass",
        at = @At(value = "FIELD",
                 target = "Lnet/coderbot/iris/postprocess/FinalPassRenderer$SwapPass;targetTexture:I",
                 opcode = Opcodes.GETFIELD)
    )
    private int stereoscopic$remapSwapTargetTexture(int targetTextureId) {
        final StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || s.getCurrentEye() == StereoState.Eye.MONO) return targetTextureId;
        return PerEyeRenderTargetHooks.resolveActiveEyeTexId(targetTextureId);
    }
}
