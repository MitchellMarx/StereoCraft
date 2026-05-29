package com.mitchellmarx.stereoscopic.render;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.shader.Framebuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Owned scratch framebuffer used as the world-render target during each eye's pass.
 *
 * <p>Pattern: each eye renders the world into a full-display-sized scratch FB instead of
 * directly into the main FB's eye region. After {@code renderWorld} returns, the scratch is
 * blit-squished into the main FB at the eye's region. The substitution is wired by
 * {@code MixinMinecraft_StereoScratchFb}'s {@code @Inject} on
 * {@code Minecraft.getFramebuffer()}, which returns the scratch instance whenever
 * {@link #isActive()} is {@code true}.
 *
 * <p>Why full-size scratch instead of half-width main-FB region:
 * <ul>
 *   <li>Avoids aspect-ratio distortion. With a half-width viewport on the main FB,
 *       MC's {@code gluPerspective} uses the full-screen aspect ratio in projection,
 *       producing horizontally-squished chunk geometry.</li>
 *   <li>Iris's per-eye intermediate FBOs naturally inherit the FB size, eliminating
 *       the per-eye {@code RenderTargets} machinery sbs2 needed.</li>
 *   <li>Works identically with or without a shaderpack loaded.</li>
 * </ul>
 *
 * <p>Lifecycle: lazily allocated at first activation, resized on display dimension change,
 * destroyed on game shutdown via {@code MixinMinecraft_AsyncCursor.shutdownMinecraftApplet}
 * HEAD inject.
 *
 * <p>Single-threaded: only the render thread (main) writes to this. No locking needed.
 */
public final class PerEyeScratchFb {

    private static final Logger LOGGER = LogManager.getLogger("StereoscopicScratchFb");

    /** When true, {@code Minecraft.getFramebuffer()} returns the scratch instance. */
    private static volatile boolean active = false;

    /** Allocated framebuffer. Null until first {@link #ensureSized(int, int)}. */
    private static Framebuffer scratch = null;
    private static int scratchW = -1;
    private static int scratchH = -1;

    private PerEyeScratchFb() {}

    public static boolean isActive() { return active; }
    public static void setActive(boolean v) { active = v; }
    public static Framebuffer get() { return scratch; }

    /**
     * Allocate the scratch FB at the given dimensions, or resize an existing one. Idempotent
     * when dimensions match. Uses depth attachment to support depth-buffered world rendering
     * (chunks, entities, particles — they all use depth testing).
     */
    public static Framebuffer ensureSized(int width, int height) {
        if (scratch != null && scratchW == width && scratchH == height) return scratch;
        if (scratch != null) {
            try {
                scratch.deleteFramebuffer();
            } catch (Throwable t) {
                LOGGER.warn("Scratch FB delete failed during resize; GPU FB leaked.", t);
            }
            scratch = null;
        }
        scratch = new Framebuffer(width, height, true);
        scratchW = width;
        scratchH = height;
        LOGGER.info("Allocated stereo scratch framebuffer: {}x{} (FBO id={})", width, height,
            scratch.framebufferObject);
        return scratch;
    }

    /** Dispose on shutdown. Safe to call before any allocation (no-op then). */
    public static void dispose() {
        if (scratch != null) {
            try {
                scratch.deleteFramebuffer();
            } catch (Throwable t) {
                LOGGER.warn("Scratch FB delete failed on dispose; GPU FB leaked.", t);
            }
            scratch = null;
        }
        scratchW = -1;
        scratchH = -1;
        active = false;
    }

    /**
     * Blit the scratch FB into the main FB at the current eye's region, horizontally squished.
     * Uses raw {@code glBlitFramebuffer} so we can pick {@code GL_LINEAR} filtering and an
     * asymmetric source-vs-dest size — the higher-level MC blit helper forces equal-size +
     * {@code GL_NEAREST}, neither acceptable here.
     *
     * <p>Caller invariants:
     * <ul>
     *   <li>{@code StereoState.INSTANCE.getEyeVp{X,Y,W,H}()} are set to the current eye's
     *       region on the main FB (the two-pass loop in {@code MixinEntityRenderer_Stereo}
     *       sets these before each {@code renderWorld} call).</li>
     *   <li>{@code mainFb} is the real main framebuffer (caller passes the original, not
     *       our scratch substitute).</li>
     * </ul>
     */
    public static void blitScratchToMain(Framebuffer mainFb) {
        if (scratch == null) return;
        if (mainFb == null) return;
        final StereoState s = StereoState.INSTANCE;
        final int dstX0 = s.getEyeVpX();
        final int dstY0 = s.getEyeVpY();
        final int dstX1 = dstX0 + s.getEyeVpW();
        final int dstY1 = dstY0 + s.getEyeVpH();
        if (dstX1 <= dstX0 || dstY1 <= dstY0) return;

        final int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        final boolean wasScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (wasScissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);

        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, scratch.framebufferObject);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainFb.framebufferObject);
            GL30.glBlitFramebuffer(
                0, 0, scratchW, scratchH,
                dstX0, dstY0, dstX1, dstY1,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            if (wasScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }
}
