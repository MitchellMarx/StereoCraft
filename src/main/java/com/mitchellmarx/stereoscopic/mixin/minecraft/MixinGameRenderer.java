package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoMath;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.cursor.StereoCursor;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.util.Window;
import net.minecraft.util.math.Vec3d;
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

    @Shadow @Final private MinecraftClient client;

    /** True once we've forced Iris's pipeline to rebuild after first stereo activation. */
    private static boolean stereoscopic$irisRebuiltForStereo = false;

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("HEAD"))
    private void stereoscopic$beginFrame(RenderTickCounter tracker, boolean tick, CallbackInfo ci) {
        Window w = client.getWindow();
        StereoState.INSTANCE.beginFrame(w.getFramebufferWidth(), w.getFramebufferHeight());
        StereoCursor.tick();
        stereoscopic$ensureIrisRebuiltForStereo();
    }

    /**
     * When stereo is enabled in stereoscopic-options.json AND a shaderpack is loaded
     * at game launch, Iris's pipeline init runs before our scratch-FB + per-eye
     * RenderTargets ever fire. The cached pipeline state breaks per-eye world
     * rendering, and the user has to toggle shaders off+on to force a rebuild.
     * The Sodium options-page Mode binding already drives
     * {@link PerEyeRenderTargetHooks#rebuildPipelineForStereoToggle()} on each
     * toggle; replicate the same trigger once on the first stereo-active frame
     * so startup-with-stereo-already-on works without manual intervention.
     */
    private void stereoscopic$ensureIrisRebuiltForStereo() {
        if (stereoscopic$irisRebuiltForStereo) return;
        if (!StereoState.INSTANCE.isActive()) return;
        if (client.world == null) return;
        PerEyeRenderTargetHooks.rebuildPipelineForStereoToggle();
        stereoscopic$irisRebuiltForStereo = true;
    }

    /**
     * Off-axis frustum shear applied to each eye's projection. Skews the
     * matrix so the eye's view converges toward the configured convergence
     * distance: objects at that depth sit at the screen plane (zero parallax),
     * closer objects pop out, farther ones recede.
     *
     * <p>This is a new feature relative to the Angelica reference. Both
     * Angelica trees (sbs2 mod-on-modern, and the older fork-style port) use
     * parallel-axis stereo and explicitly disable any per-eye projection shear
     * - see {@code angelica$applyStereoProjectionOffset} in Angelica-sbs2's
     * {@code MixinEntityRenderer_StereoCamera}, which fences the shear behind
     * {@code if (true) return} with the rationale "parallel-axis stereo
     * avoids the asymmetric-frustum gap between eyes". This mod ships
     * off-axis ON by default; the justification lives on
     * {@link com.mitchellmarx.stereoscopic.core.StereoOptions#convergence}.
     *
     * <p>The math is in {@link StereoMath#convergenceShear(float, float)} -
     * see that JavaDoc for the full sign derivation. The sign of
     * {@code StereoState.getEyeOffset()} is opposite to Angelica's
     * ({@code -ipd/2} for LEFT here vs {@code +ipd/2} there); the formula
     * carries the sign through correctly for both conventions.
     *
     * <p>Callsite coverage: {@code GameRenderer.getBasicProjectionMatrix} is
     * called from {@code renderWorld} (the per-eye-wrapped path that triggers
     * this shear via {@code isInWorldPass()}) and from item/hand model
     * projection setup. The {@code isInWorldPass()} gate intentionally skips
     * the latter - first-person item/hand projection is set up outside the
     * per-eye loop, so it stays at the un-sheared center projection. Stereo
     * separation for first-person hand still comes from the camera offset
     * applied in {@code stereoscopic$twoPassRenderWorld}.
     */
    @ModifyReturnValue(method = "getBasicProjectionMatrix(F)Lorg/joml/Matrix4f;", at = @At("RETURN"))
    private Matrix4f stereoscopic$applyConvergenceShear(Matrix4f proj) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInWorldPass()) return proj;
        // SBS_FULL first halves the aspect to (W/2)/H so each half carries a
        // proportionally correct image. Order matters: the shear post-multiply
        // reads m00, so the aspect fix must run first (see StereoMath).
        if (s.getFrameMode().isFull()) {
            StereoMath.applyFullSbsAspect(proj);
        }
        return StereoMath.applyConvergenceShear(proj, s.getEyeOffset(), s.getFrameConvergence());
    }

    @Inject(method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V", at = @At("RETURN"))
    private void stereoscopic$endFrame(RenderTickCounter tracker, boolean tick, CallbackInfo ci) {
        StereoState.INSTANCE.endFrame();
    }

    /**
     * Per-eye renderWorld wrap. Shifts {@code Camera.pos} by ±(ipd/2) along the
     * camera's local right vector so every downstream consumer of
     * {@code camera.getCameraPos()} (Sodium chunk transforms, frustum culling,
     * Iris's {@code cameraPosition} uniform, the cameraRenderState snapshot
     * inside renderWorld) sees the per-eye position. Renders into a full-FB
     * scratch FB substituted via {@link MixinMinecraftClient}'s
     * {@code getFramebuffer()} HEAD inject, then blits scratch → MC main FB at
     * the eye rect with {@code GL_LINEAR} (horizontal squish for SBS_HALF).
     */
    @WrapOperation(
        method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/render/GameRenderer;renderWorld(Lnet/minecraft/client/render/RenderTickCounter;)V")
    )
    private void stereoscopic$twoPassRenderWorld(
            GameRenderer self,
            RenderTickCounter tickCounter,
            Operation<Void> original) {
        if (!StereoState.INSTANCE.isActive()) {
            original.call(self, tickCounter);
            return;
        }
        Camera camera = self.getCamera();
        Vec3d basePos = camera.getCameraPos();
        // Mono pos cached so shared passes (Iris shadow render, Sodium chunk
        // setup) can restore it for their duration — they must use the
        // un-IPD-shifted center, not either eye's offset position.
        StereoState.INSTANCE.setFrameMonoCameraPos(basePos);
        try {
            // Null-FB guard hoisted OUTSIDE the per-eye lambda: if checked
            // inside, a transient null would double-invoke original.call
            // (once per eye) with the IPD-shifted camera persisting between
            // calls, since the outer finally only restores at the very end.
            Framebuffer outerCheckFb = this.client.getFramebuffer();
            if (outerCheckFb == null || outerCheckFb.getColorAttachment() == null) {
                original.call(self, tickCounter);
                return;
            }
            PerEyeRenderer.runForEachEye(PerEyeRenderer.Pass.WORLD, () -> {
                Framebuffer realMainFb = this.client.getFramebuffer();
                int w = realMainFb.textureWidth;
                int h = realMainFb.textureHeight;
                SimpleFramebuffer scratch = PerEyeRenderer.ensureScratchFb(w, h);

                // Axis: getDiagonalPlane is the local RIGHT vector (X-axis
                // after camera rotation). getHorizontalPlane is forward, not
                // right — the names mislead. See feedback_camera_plane_naming.
                // Sign: getEyeOffset() returns -ipd/2 for LEFT, +ipd/2 for
                // RIGHT — LEFT shifts along the negative right-vector to sit
                // at the physical left eye's position.
                float dx = StereoState.INSTANCE.getEyeOffset();
                Vector3fc right = camera.getDiagonalPlane();
                Vec3d eyePos = basePos.add(right.x() * dx, right.y() * dx, right.z() * dx);
                ((CameraAccessor)(Object)camera).stereoscopic$setPos(eyePos);

                PerEyeRenderer.setScratchFbActive(true);
                try {
                    original.call(self, tickCounter);
                } finally {
                    PerEyeRenderer.setScratchFbActive(false);
                }
                stereoscopic$blitScratchToMain(scratch, realMainFb, w, h);
            });
        } finally {
            ((CameraAccessor)(Object)camera).stereoscopic$setPos(basePos);
            StereoState.INSTANCE.setFrameMonoCameraPos(null);
        }
    }

    /**
     * Raw {@code glBlitFramebuffer} (not {@code CommandEncoder.copyTextureToTexture})
     * — that forces {@code GL_NEAREST} and same-size copy, neither acceptable here.
     */
    private static void stereoscopic$blitScratchToMain(SimpleFramebuffer scratch,
                                                        Framebuffer mainFb,
                                                        int scratchW, int scratchH) {
        if (scratch == null || scratch.getColorAttachment() == null) return;
        if (mainFb == null || mainFb.getColorAttachment() == null) return;
        int scratchTex = stereoscopic$extractGlId(scratch.getColorAttachment());
        int mainTex = stereoscopic$extractGlId(mainFb.getColorAttachment());
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
     * Direct cast + call - NOT reflection by yarn-name. {@code getField("glId")}
     * silently fails in production because Loom doesn't remap reflection string
     * literals; the runtime field name is {@code field_XXXXX}. See
     * feedback_no_reflection_by_yarn_name.
     */
    private static int stereoscopic$extractGlId(GpuTexture tex) {
        if (tex instanceof GlTexture gl) return gl.getGlId();
        Stereoscopic.LOG.warn("[scratch-blit] glId extraction failed for non-GlTexture {}; eye blit will skip",
            tex.getClass().getName());
        return 0;
    }
}
