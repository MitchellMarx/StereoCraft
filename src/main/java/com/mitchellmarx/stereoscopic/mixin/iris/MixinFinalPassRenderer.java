package com.mitchellmarx.stereoscopic.mixin.iris;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.irisshaders.iris.pipeline.FinalPassRenderer;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.OptionalInt;
import java.util.function.Supplier;

/**
 * Per-eye final-pass blit via Iris-internal scratch GpuTexture (Angelica step 7
 * port). Substituting the createRenderPass view sends Iris's final.fsh writes
 * into our scratch at full bank dims; we then {@code glBlitFramebuffer} scratch
 * into the destination.
 *
 * <p>Destination depends on whether the outer-scratch (per-eye FB substitution)
 * is active:
 * <ul>
 *   <li>Outer scratch active: blit full-extent into outer scratch; the outer
 *       MixinGameRenderer wrap squishes to the eye-half. Blitting into the
 *       eye-half here would double-squish.</li>
 *   <li>Outer scratch inactive (defensive): blit straight into MC main FB at
 *       the eye-half rect.</li>
 * </ul>
 *
 * <p>Earlier attempts that just played with scissor + viewport on MC main FB
 * failed because final.fsh's varying {@code texCoord} samples colortex 0..1
 * regardless of viewport — full eye view squished into eye-half visible only
 * on RIGHT; LEFT bank's colortex ended up empty/junk through interference
 * between Iris's {@code iris$setCustomPass} rebinds and our per-eye colortex
 * rebinds during composite.
 */
@Mixin(value = FinalPassRenderer.class, remap = false)
public abstract class MixinFinalPassRenderer {

    @Unique private GpuTexture stereoscopic$scratchTex;
    @Unique private GpuTextureView stereoscopic$scratchView;
    @Unique private int stereoscopic$scratchFbo = 0;
    @Unique private int stereoscopic$scratchW = -1;
    @Unique private int stereoscopic$scratchH = -1;
    @Unique private boolean stereoscopic$pendingBlit = false;

    @Unique
    private void stereoscopic$ensureScratch(int w, int h) {
        if (stereoscopic$scratchTex != null && stereoscopic$scratchW == w && stereoscopic$scratchH == h) return;
        if (stereoscopic$scratchFbo != 0) {
            GL30.glDeleteFramebuffers(stereoscopic$scratchFbo);
            stereoscopic$scratchFbo = 0;
        }
        if (stereoscopic$scratchView != null) {
            try { stereoscopic$scratchView.close(); }
            catch (Throwable t) { Stereoscopic.LOG.warn("[final-pass] scratch TextureView close failed; GPU view leaked", t); }
            stereoscopic$scratchView = null;
        }
        if (stereoscopic$scratchTex != null) {
            try { stereoscopic$scratchTex.close(); }
            catch (Throwable t) { Stereoscopic.LOG.warn("[final-pass] scratch GpuTexture close failed; GPU texture leaked", t); }
            stereoscopic$scratchTex = null;
        }
        stereoscopic$scratchTex = RenderSystem.getDevice().createTexture(
            () -> "stereoscopic-final-scratch",
            // USAGE_COPY_DST | USAGE_COPY_SRC | USAGE_TEXTURE_BINDING | USAGE_RENDER_ATTACHMENT
            1 | 2 | 4 | 8,
            TextureFormat.RGBA8,
            w, h, 1, 1);
        stereoscopic$scratchView = RenderSystem.getDevice().createTextureView(stereoscopic$scratchTex);
        stereoscopic$scratchW = w;
        stereoscopic$scratchH = h;
        int texId = stereoscopic$extractGlId(stereoscopic$scratchTex);
        stereoscopic$scratchFbo = GL30.glGenFramebuffers();
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, stereoscopic$scratchFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texId, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            Stereoscopic.LOG.warn("[scratch] final-pass scratch FBO incomplete: status=0x{} dims=({}x{})",
                Integer.toHexString(status), w, h);
        }
    }

    @Unique
    private static int stereoscopic$extractGlId(GpuTexture tex) {
        if (tex instanceof com.mojang.blaze3d.opengl.GlTexture gl) return gl.glId();
        Stereoscopic.LOG.warn("[final-pass] glId extraction failed for non-GlTexture {}; scratch will not bind",
            tex.getClass().getName());
        return 0;
    }

    @WrapOperation(
        method = "renderFinalPass",
        at = @At(value = "INVOKE",
                 target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalInt;)Lcom/mojang/blaze3d/systems/RenderPass;",
                 remap = false)
    )
    private RenderPass stereoscopic$substituteRenderPassView(CommandEncoder receiver,
                                                              Supplier<String> nameSupplier,
                                                              GpuTextureView view,
                                                              OptionalInt clearColor,
                                                              Operation<RenderPass> original) {
        if (!stereoscopic$eyeActive()) {
            return original.call(receiver, nameSupplier, view, clearColor);
        }
        int fbW = view.getWidth(0);
        int fbH = view.getHeight(0);
        stereoscopic$ensureScratch(fbW, fbH);
        RenderPass pass = original.call(receiver, nameSupplier, stereoscopic$scratchView, clearColor);
        stereoscopic$pendingBlit = true;
        return pass;
    }

    @Inject(method = "renderFinalPass", at = @At("RETURN"))
    private void stereoscopic$blitScratchToMainFb(CallbackInfo ci) {
        if (!stereoscopic$pendingBlit) return;
        stereoscopic$pendingBlit = false;
        if (!stereoscopic$eyeActive()) return;
        StereoState s = StereoState.INSTANCE;
        RenderTarget destFb = Minecraft.getInstance().getMainRenderTarget();
        if (destFb == null || destFb.getColorTexture() == null) return;
        int destTexId = stereoscopic$extractGlId(destFb.getColorTexture());
        if (destTexId == 0 || stereoscopic$scratchFbo == 0) return;

        boolean outerScratchActive = PerEyeRenderer.isScratchFbActive();
        int dstX0, dstY0, dstX1, dstY1;
        if (outerScratchActive) {
            dstX0 = 0;
            dstY0 = 0;
            dstX1 = destFb.width;
            dstY1 = destFb.height;
        } else {
            dstX0 = s.getEyeVpX();
            dstY0 = s.getEyeVpY();
            dstX1 = dstX0 + s.getEyeVpW();
            dstY1 = dstY0 + s.getEyeVpH();
        }

        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int drawFbo = GL30.glGenFramebuffers();
        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, destTexId, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            int drawStatus = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, stereoscopic$scratchFbo);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            boolean wasScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            if (wasScissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
            if (drawStatus == GL30.GL_FRAMEBUFFER_COMPLETE) {
                int filter = (stereoscopic$scratchW == (dstX1 - dstX0) &&
                              stereoscopic$scratchH == (dstY1 - dstY0))
                    ? GL11.GL_NEAREST : GL11.GL_LINEAR;
                GL30.glBlitFramebuffer(
                    0, 0, stereoscopic$scratchW, stereoscopic$scratchH,
                    dstX0, dstY0, dstX1, dstY1,
                    GL11.GL_COLOR_BUFFER_BIT, filter);
            } else {
                Stereoscopic.LOG.warn("[blit] final-pass draw FBO incomplete: status=0x{}",
                    Integer.toHexString(drawStatus));
            }
            if (wasScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glDeleteFramebuffers(drawFbo);
        }
    }

    /**
     * Remap the ALT→MAIN swap-pass target texture per active eye.
     *
     * <p>{@code FinalPassRenderer$SwapPass.targetTexture} is captured at pipeline
     * construction as LEFT-bank MAIN of the swap slot and never updated when the
     * active eye changes. The corresponding {@code from} framebuffer's color
     * attachment IS rebound per eye by {@link MixinRenderTargets#stereoscopic$setActiveEye}
     * because {@code from} lives in {@code ownedFramebuffers}, but
     * {@code targetTexture} is a raw int field outside that machinery.
     *
     * <p>The bug this causes: composite0 writes ALT of colortex7 each eye iter
     * (per the BufferFlipper layout for slots with no prior writers). At end of
     * each eye's {@code finalPass}, Iris runs the swap loop:
     * <ul>
     *   <li>Bind {@code from} framebuffer (its color attachment IS active-eye's
     *       ALT after our per-eye rebind).</li>
     *   <li>Bind {@code targetTexture} — captured LEFT-bank MAIN, NOT updated.</li>
     *   <li>{@code glCopyTexSubImage2D} copies from-fb-ALT into bound 2D tex.</li>
     * </ul>
     *
     * <p>In LEFT iter: copies LEFT-ALT → LEFT-MAIN ✓
     * <br>In RIGHT iter: copies RIGHT-ALT → LEFT-MAIN ✗
     *
     * <p>End result: LEFT-MAIN ends up holding RIGHT eye's previous-frame
     * reflection (RIGHT iter runs after LEFT and overwrites). RIGHT-MAIN never
     * gets written. Next frame, composite0's PBR_REFLECTIONS temporal block
     * (Complementary composite.glsl:184) reads {@code colortex7} MAIN as
     * {@code prevRef}: LEFT eye samples RIGHT's stale reflection (wrong eye),
     * RIGHT eye samples zeros. The {@code prevValid} heuristic weights {@code mix}
     * heavily toward {@code prevRef} when stationary, producing visible per-eye
     * misalignment that resolves only on camera turn (when {@code pixelMovement}
     * drops {@code prevValid} and the mix swings to current-frame ray-march).
     *
     * <p>Hooks the {@code GETFIELD SwapPass.targetTexture} instruction inside
     * {@code renderFinalPass}'s swap loop via {@code @ModifyExpressionValue}.
     * The field is read exactly once per swap-pass iteration (right before the
     * {@code _bindTexture} call), so this fires once per slot per eye iter.
     * Same fix automatically covers {@code colortex2} (composite6 TAA history)
     * — any non-cleared slot with a SwapPass gets remapped.
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
     * Path B — no shader-pack final.fsh; Iris does a direct
     * {@code copyTexSubImage2D(baseline -> main color tex)}. With outer scratch
     * active the destination is outer scratch's color (substituted by
     * MixinMinecraftClient); pass through full-window so the outer wrap squishes
     * to the eye-half. Without outer scratch, remap to the eye-rect.
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
