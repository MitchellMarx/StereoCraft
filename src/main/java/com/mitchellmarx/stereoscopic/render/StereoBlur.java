package com.mitchellmarx.stereoscopic.render;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Per-eye blur post-effect. {@code GameRenderer.renderBlur()} runs through
 * {@code CommandEncoder.createRenderPass}, which rasterizes to the full
 * color-attachment view ignoring classic glScissor and the per-render-type
 * scissor state — calling it twice in stereo reblurs the full FB twice, the
 * second pass overwrites the first eye's half, and the kernel reaches across
 * the L/R seam.
 *
 * <p>Approach: half-width offscreen FB sized {@code eyeW × eyeH}. Copy the
 * eye's sub-rect of main into it, run the blur shader there (kernel sees only
 * that eye's pixels), copy back.
 */
public final class StereoBlur {

    private static final Identifier BLUR_ID = Identifier.withDefaultNamespace("blur");

    private static TextureTarget blurFB;
    private static int currentW = -1;
    private static int currentH = -1;

    private StereoBlur() {}

    public static void applyPerEye() {
        StereoState s = StereoState.INSTANCE;
        int eyeX = s.getEyeVpX();
        int eyeY = s.getEyeVpY();
        int eyeW = s.getEyeVpW();
        int eyeH = s.getEyeVpH();
        if (eyeW <= 0 || eyeH <= 0) return;

        Minecraft client = Minecraft.getInstance();
        RenderTarget mainFB = client.getMainRenderTarget();
        if (mainFB == null) return;

        if (eyeX < 0 || eyeY < 0
            || eyeX + eyeW > mainFB.width
            || eyeY + eyeH > mainFB.height) return;

        if (!ensureBlurFB(eyeW, eyeH)) return;

        ShaderManager shaderManager = client.getShaderManager();
        PostChain blur = shaderManager.getPostChain(BLUR_ID, LevelTargetBundle.MAIN_TARGETS);
        if (blur == null) return;

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuTexture mainColor = mainFB.getColorTexture();
        GpuTexture blurColor = blurFB.getColorTexture();

        // copyTextureToTexture(src, dst, mipLevel, dstX, dstY, srcX, srcY, w, h).
        // Vanilla GlCommandEncoder.copyTextureToTexture has a bug where it
        // passes (w, h) as endpoint coords instead of (srcX+w, srcY+h, ...).
        // MixinGlCommandEncoder patches the args; see that file for detail.
        encoder.copyTextureToTexture(mainColor, blurColor, 0, 0, 0, eyeX, eyeY, eyeW, eyeH);

        // blur.render binds internal write targets and leaves the GL FB binding
        // pointing somewhere inside the blur pipeline. Save and restore the
        // draw/read FB bindings so any subsequent draw call inherits a sane state.
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        try {
            blur.process(blurFB, GraphicsResourceAllocator.UNPOOLED);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        }

        encoder.copyTextureToTexture(blurColor, mainColor, 0, eyeX, eyeY, 0, 0, eyeW, eyeH);
    }

    private static boolean ensureBlurFB(int w, int h) {
        if (currentW == w && currentH == h && blurFB != null) return true;
        if (blurFB != null) { blurFB.destroyBuffers(); blurFB = null; }
        TextureTarget fb = new TextureTarget("stereoscopic-blur", w, h, true);
        // RenderTarget has no checkFramebufferStatus(); the closest
        // post-construction sanity check is verifying the GpuTexture attachments
        // were created. createTexture throws on allocation failure, so this
        // mostly guards against a future refactor returning null silently.
        try {
            if (fb.getColorTexture() == null || fb.getDepthTexture() == null) {
                throw new IllegalStateException("blur FB missing attachments");
            }
        } catch (Throwable t) {
            Stereoscopic.LOG.warn(
                "[stereo-blur] blur FB completeness check failed at {}x{}; skipping per-eye blur", w, h, t);
            try { fb.destroyBuffers(); } catch (Throwable ignore) {}
            currentW = -1;
            currentH = -1;
            return false;
        }
        blurFB = fb;
        currentW = w;
        currentH = h;
        return true;
    }

    public static void dispose() {
        if (blurFB != null) { blurFB.destroyBuffers(); blurFB = null; }
        currentW = -1;
        currentH = -1;
    }
}
