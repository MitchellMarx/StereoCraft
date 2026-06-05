package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoMath;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.cursor.StereoCursor;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class MixinGameRenderer {

    @Shadow @Final private Minecraft minecraft;

    @Shadow public abstract net.minecraft.client.renderer.state.GameRenderState getGameRenderState();

    @Shadow public abstract Camera getMainCamera();

    /** True once we've forced Iris's pipeline to rebuild after first stereo activation. */
    private static boolean stereoscopic$irisRebuiltForStereo = false;

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void stereoscopic$beginFrame(DeltaTracker tracker, boolean tick, CallbackInfo ci) {
        Window w = minecraft.getWindow();
        StereoState.INSTANCE.beginFrame(w.getWidth(), w.getHeight());
        StereoCursor.tick();
        stereoscopic$ensureIrisRebuiltForStereo();
    }

    /**
     * When stereo is enabled in stereoscopic-options.json AND a shaderpack is loaded
     * at game launch, Iris's pipeline init runs before our scratch-FB + per-eye
     * RenderTargets ever fire. The cached pipeline state breaks per-eye world
     * rendering, and the user has to toggle shaders off+on to force a rebuild.
     * Replicate the toggle trigger once on the first stereo-active frame so
     * startup-with-stereo-already-on works without manual intervention.
     */
    private void stereoscopic$ensureIrisRebuiltForStereo() {
        if (stereoscopic$irisRebuiltForStereo) return;
        if (!StereoState.INSTANCE.isActive()) return;
        if (minecraft.level == null) return;
        PerEyeRenderTargetHooks.rebuildPipelineForStereoToggle();
        stereoscopic$irisRebuiltForStereo = true;
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"))
    private void stereoscopic$endFrame(DeltaTracker tracker, boolean tick, CallbackInfo ci) {
        StereoState.INSTANCE.endFrame();
    }

    /**
     * Per-eye renderLevel wrap, redesigned for MC 26.x's render-state extraction
     * model. World rendering reads the immutable {@code CameraRenderState}
     * (snapshotted once per frame by {@code extract()}), NOT the live Camera /
     * GameRenderer, so the old "shift Camera.pos via CameraAccessor + shear
     * getBasicProjectionMatrix" approach no longer reaches the world render
     * (and getBasicProjectionMatrix no longer exists). Instead, between eyes we
     * mutate {@code cameraRenderState.pos} (eye offset) and
     * {@code cameraRenderState.projectionMatrix} (convergence shear) — both the
     * GPU projection UBO upload and the Matrix4fc handed to LevelRenderer read
     * from that state. Renders into a full-FB scratch FB (substituted via
     * {@link MixinMinecraftClient}'s {@code getMainRenderTarget()} HEAD inject),
     * then blits scratch -> MC main FB at the eye rect with {@code GL_LINEAR}.
     */
    @WrapOperation(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V")
    )
    private void stereoscopic$twoPassRenderLevel(
            GameRenderer self,
            DeltaTracker deltaTracker,
            Operation<Void> original) {
        if (!StereoState.INSTANCE.isActive()) {
            original.call(self, deltaTracker);
            return;
        }
        CameraRenderState crs = getGameRenderState().levelRenderState.cameraRenderState;
        Camera camera = getMainCamera();
        Vec3 basePos = crs.pos;
        Matrix4f baseProj = new Matrix4f(crs.projectionMatrix);
        // Mono pos cached so shared passes (Iris shadow render, Sodium chunk
        // setup) can restore it for their duration — they must use the
        // un-IPD-shifted center, not either eye's offset position.
        StereoState.INSTANCE.setFrameMonoCameraPos(basePos);
        try {
            // Null-FB guard hoisted OUTSIDE the per-eye lambda: if checked
            // inside, a transient null would double-invoke original.call.
            RenderTarget outerCheckFb = this.minecraft.getMainRenderTarget();
            if (outerCheckFb == null || outerCheckFb.getColorTexture() == null) {
                original.call(self, deltaTracker);
                return;
            }
            PerEyeRenderer.runForEachEye(PerEyeRenderer.Pass.WORLD, () -> {
                RenderTarget realMainFb = this.minecraft.getMainRenderTarget();
                int w = realMainFb.width;
                int h = realMainFb.height;
                TextureTarget scratch = PerEyeRenderer.ensureScratchFb(w, h);

                // Eye offset along the camera's local RIGHT vector. 26.x Camera
                // exposes leftVector() (= -right); negate to get right. Sign:
                // getEyeOffset() returns -ipd/2 for LEFT, +ipd/2 for RIGHT, so
                // LEFT shifts along -right to sit at the physical left eye.
                float dx = StereoState.INSTANCE.getEyeOffset();
                Vector3fc left = camera.leftVector();
                crs.pos = basePos.add(-left.x() * dx, -left.y() * dx, -left.z() * dx);
                // Off-axis convergence shear on a fresh copy of the mono
                // projection (applyConvergenceShear mutates in place).
                crs.projectionMatrix = StereoMath.applyConvergenceShear(
                    new Matrix4f(baseProj), dx, StereoState.INSTANCE.getFrameConvergence());

                PerEyeRenderer.setScratchFbActive(true);
                try {
                    original.call(self, deltaTracker);
                } finally {
                    PerEyeRenderer.setScratchFbActive(false);
                }
                stereoscopic$blitScratchToMain(scratch, realMainFb, w, h);
            });
        } finally {
            crs.pos = basePos;
            crs.projectionMatrix = baseProj;
            StereoState.INSTANCE.setFrameMonoCameraPos(null);
        }
    }

    /**
     * Raw {@code glBlitFramebuffer} (not {@code CommandEncoder.copyTextureToTexture})
     * — that forces {@code GL_NEAREST} and same-size copy, neither acceptable here.
     */
    private static void stereoscopic$blitScratchToMain(TextureTarget scratch,
                                                        RenderTarget mainFb,
                                                        int scratchW, int scratchH) {
        if (scratch == null || scratch.getColorTexture() == null) return;
        if (mainFb == null || mainFb.getColorTexture() == null) return;
        int scratchTex = stereoscopic$extractGlId(scratch.getColorTexture());
        int mainTex = stereoscopic$extractGlId(mainFb.getColorTexture());
        if (scratchTex == 0 || mainTex == 0) return;
        StereoState s = StereoState.INSTANCE;

        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        boolean wasScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (wasScissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);

        int readFbo = GL30.glGenFramebuffers();
        int drawFbo = GL30.glGenFramebuffers();
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, scratchTex, 0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, mainTex, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);

            int rs = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
            int ds = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
            if (rs != GL30.GL_FRAMEBUFFER_COMPLETE || ds != GL30.GL_FRAMEBUFFER_COMPLETE) {
                Stereoscopic.LOG.warn("[scratch-blit] FBO incomplete read=0x{} draw=0x{}",
                    Integer.toHexString(rs), Integer.toHexString(ds));
                return;
            }
            int dstX0 = s.getEyeVpX();
            int dstY0 = s.getEyeVpY();
            int dstX1 = dstX0 + s.getEyeVpW();
            int dstY1 = dstY0 + s.getEyeVpH();
            GL30.glBlitFramebuffer(
                0, 0, scratchW, scratchH,
                dstX0, dstY0, dstX1, dstY1,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glDeleteFramebuffers(readFbo);
            GL30.glDeleteFramebuffers(drawFbo);
            if (wasScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }

    /**
     * Direct cast + call - NOT reflection by name. See
     * feedback_no_reflection_by_yarn_name.
     */
    private static int stereoscopic$extractGlId(GpuTexture tex) {
        if (tex instanceof GlTexture gl) return gl.glId();
        Stereoscopic.LOG.warn("[scratch-blit] glId extraction failed for non-GlTexture {}; eye blit will skip",
            tex.getClass().getName());
        return 0;
    }
}
