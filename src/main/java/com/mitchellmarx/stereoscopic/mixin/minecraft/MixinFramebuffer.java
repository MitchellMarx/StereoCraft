package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Two responsibilities on RenderTarget:
 *
 * <ol>
 *   <li><b>Cursor present routing</b> — intercept MC's per-frame
 *       {@code blitToScreen} so the async cursor thread is the one calling
 *       {@code SwapBuffers}.</li>
 *   <li><b>Per-eye viewport restore</b> — Iris's pipeline, MC's blur post-effect,
 *       and assorted internals all call {@code RenderTarget.bindWrite(true)}
 *       mid-render, which calls {@code RenderSystem.viewport(0,0,fbW,fbH)} and
 *       clobbers the per-eye viewport set by {@link PerEyeRenderer#runForEachEye}.
 *       Without restore, the first eye's draws land in the eye-rect but
 *       everything after the rebind lands full-FB on top — visible as "screen
 *       only draws in one eye / full-width on top of stereo content."</li>
 * </ol>
 *
 * <p>Inert while scratch FB is active: the world pass renders into a full-FB
 * scratch and WANTS the bind-time viewport to cover the full FB; restoring an
 * eye-rect viewport here would clip Iris's writes to half the FB.
 *
 * <p>Port of Angelica's {@code MixinFramebuffer_Stereo}
 * ({@code Framebuffer.bindFramebuffer} → {@code RenderTarget.bindWrite}).
 */
@Mixin(RenderTarget.class)
public abstract class MixinFramebuffer {

    @Inject(method = "blitToScreen(II)V", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$routeBlitThroughCursorThread(int width, int height, CallbackInfo ci) {
        if (!CursorPresentThread.isRunning()) return;
        CursorPresentThread.publishFrame();
        ci.cancel();
    }

    @Inject(method = "bindWrite(Z)V", at = @At("RETURN"))
    private void stereoscopic$restoreStereoViewport(boolean setViewport, CallbackInfo ci) {
        if (PerEyeRenderer.isScratchFbActive()) return;
        if (!setViewport) return;
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive()) return;
        StereoState.Eye eye = s.getCurrentEye();
        if (eye != StereoState.Eye.LEFT && eye != StereoState.Eye.RIGHT) return;
        // Only restore for the MAIN render target. Intermediate FBs
        // (PostChain blur passes, glyph atlases, item icon FBs) should keep
        // the viewport bindWrite just set to their own dimensions —
        // overriding it forces full-screen draws to land in the eye-rect of
        // a small intermediate FB and tiles the result everywhere it gets
        // sampled.
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if ((Object) this != mc.getMainRenderTarget()) return;
        int w = s.getEyeVpW();
        int h = s.getEyeVpH();
        if (w <= 0 || h <= 0) return;
        GL11.glViewport(s.getEyeVpX(), s.getEyeVpY(), w, h);
    }
}
