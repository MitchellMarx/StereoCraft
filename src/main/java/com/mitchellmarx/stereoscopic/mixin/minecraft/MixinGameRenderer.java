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
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GameRenderer.class, priority = 2000)
public abstract class MixinGameRenderer {

    // GameRenderer.minecraft is package-private final in MC 1.21.1, not private.
    @Shadow @Final Minecraft minecraft;

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
     * The Sodium options-page Mode binding already drives
     * {@link PerEyeRenderTargetHooks#rebuildPipelineForStereoToggle()} on each
     * toggle; replicate the same trigger once on the first stereo-active frame
     * so startup-with-stereo-already-on works without manual intervention.
     */
    private void stereoscopic$ensureIrisRebuiltForStereo() {
        if (stereoscopic$irisRebuiltForStereo) return;
        if (!StereoState.INSTANCE.isActive()) return;
        if (minecraft.level == null) return;
        PerEyeRenderTargetHooks.rebuildPipelineForStereoToggle();
        stereoscopic$irisRebuiltForStereo = true;
    }

    /**
     * Off-axis frustum shear applied to each eye's projection. Skews the
     * matrix so the eye's view converges toward the configured convergence
     * distance: objects at that depth sit at the screen plane (zero parallax),
     * closer objects pop out, farther ones recede.
     */
    @ModifyReturnValue(method = "getProjectionMatrix(D)Lorg/joml/Matrix4f;", at = @At("RETURN"))
    private Matrix4f stereoscopic$applyConvergenceShear(Matrix4f proj) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInWorldPass()) return proj;
        return StereoMath.applyConvergenceShear(proj, s.getEyeOffset(), s.getFrameConvergence());
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"))
    private void stereoscopic$endFrame(DeltaTracker tracker, boolean tick, CallbackInfo ci) {
        StereoState.INSTANCE.endFrame();
    }

    /**
     * Run vanilla menu blur once per frame, not once per eye-iter. The blur
     * chain ping-pongs through {@code minecraft:main} — it operates on the
     * entire main FB regardless of viewport — so the first iter's blur output
     * already covers both eye halves of main. Letting the second iter's
     * {@code processBlurEffect} run reblurs the full FB and overwrites the
     * first eye's already-rendered widgets.
     *
     * <p>Cancel for the right eye (second iter); the left eye's blur is the
     * one that survives into the final frame. Both halves of main therefore
     * see the same blurred backdrop.
     */
    @Inject(method = "processBlurEffect(F)V", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$skipBlurInSecondIter(float partialTick, CallbackInfo ci) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInGuiPass()) return;
        if (s.getCurrentEye() == StereoState.Eye.RIGHT) {
            ci.cancel();
        }
    }

    /**
     * {@code GameRenderer.pick} runs inside {@code renderLevel} — which our
     * two-pass world wrap calls once per eye. Letting it run on both passes
     * recomputes {@code minecraft.hitResult} a second time mid-second-pass
     * (scratch FB bound, per-eye GL state in flight), where the raycast lands
     * wrong — block-info overlays (Jade) then read a bogus target. Run pick on
     * the LEFT (first) eye only; it uses the player's mono eye position, so the
     * single result is correct and both eyes share it.
     */
    @Inject(method = "pick(F)V", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$pickOnlyFirstEye(float partialTick, CallbackInfo ci) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInWorldPass()) return;
        if (s.getCurrentEye() == StereoState.Eye.RIGHT) {
            ci.cancel();
        }
    }

    /**
     * Per-eye GUI placement via the projection matrix (not pose-stack scale).
     *
     * <p>Pose-stack modifications leak into intermediate FBO renders — Xaero's
     * world map renders terrain into its private {@code primaryScaleFBO} using
     * {@code graphics.pose()}, so any scale we push there ends up baked into
     * the FBO's contents, and the later FBO-to-main blit (which also reads our
     * scaled pose) double-compresses everything. Result: terrain in each eye
     * sees a differently-shifted view, perceived as doubling.
     *
     * <p>Modifying {@link RenderSystem#setProjectionMatrix} instead has the
     * opposite leakage profile: vanilla render types read it at draw-submission
     * time so widgets / buttons / vanilla blits land per-eye, but mod-shader
     * intermediate render targets (Xaero's FBO, PostChain blur passes that set
     * their own {@code ProjMat} uniform) never query it, so their contents
     * stay mono and re-displaying them via vanilla render types lands them
     * per-eye through our projection. Matches Fabric's
     * {@code MixinGuiRenderer.stereoscopic$perEyeHudTransform} approach but at
     * a different point in the pipeline (1.21.1 has no {@code GuiRenderer}; the
     * equivalent late binding is {@code RenderSystem.projectionMatrix} read at
     * each batched draw).
     *
     * <p>{@code leftHalf} tracks {@link StereoOptions#swapEyes} so the
     * projection-shifted half matches whichever eye the world wrap routed
     * through {@link ViewportMath#eyeRect}.
     */
    @Unique
    private static Matrix4f stereoscopic$perEyeProjection(Matrix4f originalProj, boolean leftHalf) {
        // P' = T * S * P_original. Vertices map through P_original to NDC, then
        // S(0.5) compresses NDC.x by half, then T(±0.5) shifts the compressed
        // range into [-1, 0] (left half) or [0, +1] (right half).
        return new Matrix4f()
            .translate(leftHalf ? -0.5f : 0.5f, 0f, 0f)
            .scale(0.5f, 1f, 1f)
            .mul(originalProj);
    }

    @Unique
    private static boolean stereoscopic$isLeftHalfForCurrentEye() {
        boolean isRight = StereoState.INSTANCE.getCurrentEye() == StereoState.Eye.RIGHT;
        return com.mitchellmarx.stereoscopic.core.StereoOptions.INSTANCE.swapEyes ? isRight : !isRight;
    }

    @WrapOperation(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V")
    )
    private void stereoscopic$perEyeHud(net.minecraft.client.gui.Gui self,
                                         net.minecraft.client.gui.GuiGraphics graphics,
                                         DeltaTracker delta,
                                         Operation<Void> original) {
        if (!StereoState.INSTANCE.isActive()) { original.call(self, graphics, delta); return; }
        int fbW = StereoState.INSTANCE.getFrameFbW();
        int fbH = StereoState.INSTANCE.getFrameFbH();
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        try {
            PerEyeRenderer.runForEachEye(PerEyeRenderer.Pass.GUI, () -> {
                PerEyeRenderer.viewportRaw(0, 0, fbW, fbH);
                PerEyeRenderer.scissorDisableRaw();
                Matrix4f perEye = stereoscopic$perEyeProjection(savedProj, stereoscopic$isLeftHalfForCurrentEye());
                RenderSystem.setProjectionMatrix(perEye, savedSorting);
                original.call(self, graphics, delta);
                graphics.flush();
            });
        } finally {
            RenderSystem.setProjectionMatrix(savedProj, savedSorting);
        }
    }

    @WrapOperation(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/neoforged/neoforge/client/ClientHooks;drawScreen(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
    )
    private void stereoscopic$perEyeScreen(net.minecraft.client.gui.screens.Screen screen,
                                            net.minecraft.client.gui.GuiGraphics graphics,
                                            int mouseX, int mouseY, float partialTick,
                                            Operation<Void> original) {
        if (!StereoState.INSTANCE.isActive()) { original.call(screen, graphics, mouseX, mouseY, partialTick); return; }
        int fbW = StereoState.INSTANCE.getFrameFbW();
        int fbH = StereoState.INSTANCE.getFrameFbH();
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        com.mojang.blaze3d.pipeline.RenderTarget realMain = this.minecraft.getMainRenderTarget();
        try {
            PerEyeRenderer.runForEachEye(PerEyeRenderer.Pass.GUI, () -> {
                PerEyeRenderer.viewportRaw(0, 0, fbW, fbH);
                PerEyeRenderer.scissorDisableRaw();
                Matrix4f perEye = stereoscopic$perEyeProjection(savedProj, stereoscopic$isLeftHalfForCurrentEye());
                RenderSystem.setProjectionMatrix(perEye, savedSorting);
                // Arm the FB-bind projection swap: any non-main-FB bind during
                // this iter (Xaero's primaryScaleFBO, intermediate post-effect
                // targets, etc.) gets vanilla projection so their internal
                // draws fill the whole intermediate. When the real main FB is
                // rebound, the hook reasserts our per-eye projection so the
                // FBO-to-main blits land in the correct eye-half.
                PerEyeRenderer.beginProjectionSwap(savedProj, perEye, savedSorting, realMain);
                try {
                    original.call(screen, graphics, mouseX, mouseY, partialTick);
                    graphics.flush();
                } finally {
                    PerEyeRenderer.endProjectionSwap();
                }
            });
        } finally {
            RenderSystem.setProjectionMatrix(savedProj, savedSorting);
        }
    }

    @WrapOperation(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/screens/Overlay;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
    )
    private void stereoscopic$perEyeOverlay(net.minecraft.client.gui.screens.Overlay self,
                                             net.minecraft.client.gui.GuiGraphics graphics,
                                             int mouseX, int mouseY, float partialTick,
                                             Operation<Void> original) {
        if (!StereoState.INSTANCE.isActive()) { original.call(self, graphics, mouseX, mouseY, partialTick); return; }
        // LoadingOverlay and other Overlay subclasses follow the same render
        // path as Screens — full-window background plus widgets at fixed
        // pixel/scaled positions. Apply the same per-eye projection + FB-bind
        // swap the screen wrap uses so progress bars, panorama backgrounds,
        // and overlay-managed intermediate FBOs all land symmetrically.
        int fbW = StereoState.INSTANCE.getFrameFbW();
        int fbH = StereoState.INSTANCE.getFrameFbH();
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        com.mojang.blaze3d.pipeline.RenderTarget realMain = this.minecraft.getMainRenderTarget();
        try {
            PerEyeRenderer.runForEachEye(PerEyeRenderer.Pass.GUI, () -> {
                PerEyeRenderer.viewportRaw(0, 0, fbW, fbH);
                PerEyeRenderer.scissorDisableRaw();
                Matrix4f perEye = stereoscopic$perEyeProjection(savedProj, stereoscopic$isLeftHalfForCurrentEye());
                RenderSystem.setProjectionMatrix(perEye, savedSorting);
                PerEyeRenderer.beginProjectionSwap(savedProj, perEye, savedSorting, realMain);
                try {
                    original.call(self, graphics, mouseX, mouseY, partialTick);
                    graphics.flush();
                } finally {
                    PerEyeRenderer.endProjectionSwap();
                }
            });
        } finally {
            RenderSystem.setProjectionMatrix(savedProj, savedSorting);
        }
    }

    @WrapOperation(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/components/toasts/ToastComponent;render(Lnet/minecraft/client/gui/GuiGraphics;)V")
    )
    private void stereoscopic$perEyeToasts(net.minecraft.client.gui.components.toasts.ToastComponent self,
                                            net.minecraft.client.gui.GuiGraphics graphics,
                                            Operation<Void> original) {
        if (!StereoState.INSTANCE.isActive()) { original.call(self, graphics); return; }
        int fbW = StereoState.INSTANCE.getFrameFbW();
        int fbH = StereoState.INSTANCE.getFrameFbH();
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        com.mojang.blaze3d.pipeline.RenderTarget realMain = this.minecraft.getMainRenderTarget();
        try {
            PerEyeRenderer.runForEachEye(PerEyeRenderer.Pass.GUI, () -> {
                PerEyeRenderer.viewportRaw(0, 0, fbW, fbH);
                PerEyeRenderer.scissorDisableRaw();
                Matrix4f perEye = stereoscopic$perEyeProjection(savedProj, stereoscopic$isLeftHalfForCurrentEye());
                RenderSystem.setProjectionMatrix(perEye, savedSorting);
                PerEyeRenderer.beginProjectionSwap(savedProj, perEye, savedSorting, realMain);
                try {
                    original.call(self, graphics);
                    graphics.flush();
                } finally {
                    PerEyeRenderer.endProjectionSwap();
                }
            });
        } finally {
            RenderSystem.setProjectionMatrix(savedProj, savedSorting);
        }
    }

    /**
     * Per-eye renderLevel wrap. Shifts {@code Camera.pos} by ±(ipd/2) along the
     * camera's local right vector so every downstream consumer of
     * {@code camera.getPosition()} (Sodium chunk transforms, frustum culling,
     * Iris's {@code cameraPosition} uniform) sees the per-eye position.
     * Renders into a full-FB scratch FB substituted via
     * {@link MixinMinecraftClient}'s {@code getMainRenderTarget()} HEAD inject,
     * then blits scratch → MC main FB at the eye rect with {@code GL_LINEAR}
     * (horizontal squish for SBS_HALF).
     */
    @WrapOperation(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;)V")
    )
    private void stereoscopic$twoPassRenderWorld(
            GameRenderer self,
            DeltaTracker tickCounter,
            Operation<Void> original) {
        if (!StereoState.INSTANCE.isActive()) {
            original.call(self, tickCounter);
            return;
        }
        Camera camera = self.getMainCamera();
        Vec3 basePos = camera.getPosition();
        StereoState.INSTANCE.setFrameMonoCameraPos(basePos);
        CameraAccessor cameraAcc = (CameraAccessor)(Object)camera;
        try {
            RenderTarget outerCheckFb = this.minecraft.getMainRenderTarget();
            if (outerCheckFb == null || outerCheckFb.getColorTextureId() <= 0) {
                original.call(self, tickCounter);
                return;
            }
            PerEyeRenderer.runForEachEye(PerEyeRenderer.Pass.WORLD, () -> {
                RenderTarget realMainFb = this.minecraft.getMainRenderTarget();
                int w = realMainFb.width;
                int h = realMainFb.height;
                TextureTarget scratch = PerEyeRenderer.ensureScratchFb(w, h);

                // Mojang's Camera exposes getLeftVector() — local LEFT direction.
                // Yarn's getDiagonalPlane() is the local RIGHT vector. We need
                // RIGHT, so negate Left. Write Camera.position field directly:
                // both setPosition overloads are wrapped by Sable mixins that
                // NPE when Camera.entity is null (briefly true during world
                // load / dimension transition). Direct field write mirrors
                // vanilla setPosition(Vec3) bytecode exactly, sidestepping the
                // wraps so per-eye IPD shift works during world load too.
                float dx = StereoState.INSTANCE.getEyeOffset();
                Vector3f left = camera.getLeftVector();
                Vec3 eyePos = basePos.add(-left.x() * dx, -left.y() * dx, -left.z() * dx);
                cameraAcc.stereoscopic$setPositionField(eyePos);
                cameraAcc.stereoscopic$getBlockPositionField().set(eyePos.x, eyePos.y, eyePos.z);

                PerEyeRenderer.setScratchFbActive(true);
                try {
                    // PerEyeRenderer.renderOneEye set viewport+scissor to the
                    // eye-rect of main FB. With scratch FB active we're actually
                    // rendering into scratch (sized fbW x fbH), so override to
                    // full-FB viewport/scissor — otherwise the world fills only
                    // the eye-rect portion of scratch and the rest stays as
                    // previous-frame garbage which then gets blitted into the
                    // eye-half of main alongside the proper content.
                    PerEyeRenderer.viewportRaw(0, 0, w, h);
                    PerEyeRenderer.scissorRaw(0, 0, w, h);
                    // Clear scratch's color+depth between iters. Without this,
                    // the second iter's sky pass fails depth test against the
                    // first iter's terrain depth values, leaving the first
                    // iter's geometry visible under the second iter's render —
                    // both views end up overlaid in scratch and get blitted to
                    // the second eye-rect = the "world drawn twice with
                    // parallax" artifact. Use MC's bindWrite so GlStateManager's
                    // cached binding stays in sync — raw glBindFramebuffer
                    // bypasses the cache and breaks subsequent MC binds that
                    // skip when they think the target is already bound.
                    scratch.bindWrite(false);
                    // Reset write masks before the clear. Iris's renderFinalPass
                    // calls GlStateManager.depthMask(false) for its fullscreen
                    // quad and doesn't restore it on exit — state persists into
                    // the next eye iter. glClear(GL_DEPTH_BUFFER_BIT) is masked
                    // by depthMask per GL spec, so without this reset the RIGHT
                    // iter's depth clear silently no-ops, scratch keeps LEFT
                    // eye's depth, RIGHT eye's gbuffer also can't write depth
                    // (mask still false), and composites depth-test RIGHT eye
                    // fragments against LEFT eye depth — pixels visible in LEFT
                    // but occluded in RIGHT pass the test and show sky.
                    // Visible as "missing surfaces in specific directions
                    // showing skybox" in the right eye only (LEFT iter benefits
                    // from MC's frame-start state reset).
                    RenderSystem.depthMask(true);
                    RenderSystem.colorMask(true, true, true, true);
                    GL11.glClearColor(0f, 0f, 0f, 1f);
                    GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                    original.call(self, tickCounter);
                } finally {
                    PerEyeRenderer.setScratchFbActive(false);
                }
                stereoscopic$blitScratchToMain(scratch, realMainFb, w, h);
            });
        } finally {
            cameraAcc.stereoscopic$setPositionField(basePos);
            cameraAcc.stereoscopic$getBlockPositionField().set(basePos.x, basePos.y, basePos.z);
            StereoState.INSTANCE.setFrameMonoCameraPos(null);
        }
    }

    /**
     * Raw {@code glBlitFramebuffer} (no CommandEncoder.copyTextureToTexture in
     * 1.21.1; the new render-pipeline arrived in 1.21.6). Reads the raw int
     * texture IDs directly from each RenderTarget — no GpuTexture indirection.
     */
    private static void stereoscopic$blitScratchToMain(TextureTarget scratch,
                                                        RenderTarget mainFb,
                                                        int scratchW, int scratchH) {
        if (scratch == null || scratch.getColorTextureId() <= 0) return;
        if (mainFb == null || mainFb.getColorTextureId() <= 0) return;
        int scratchTex = scratch.getColorTextureId();
        int scratchDepth = scratch.getDepthTextureId();
        int mainTex = mainFb.getColorTextureId();
        int mainDepth = mainFb.getDepthTextureId();
        StereoState s = StereoState.INSTANCE;

        // Use GlStateManager helpers so MC's framebuffer-binding cache stays
        // in sync — raw glBindFramebuffer leaves the cache referring to the
        // pre-blit binding, and subsequent MC bindWrite calls may skip the
        // actual rebind on a "cache hit" against stale state. Under Iris,
        // intermittent shader-pipeline state desyncs from this would surface
        // as occasional black-frame flickers.
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        boolean wasScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (wasScissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);

        int readFbo = GL30.glGenFramebuffers();
        int drawFbo = GL30.glGenFramebuffers();
        try {
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, scratchTex, 0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
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

            // Mirror sbs2's blitEyeDepthStencilToMain: after the eye's full
            // deferred + composite + final pipeline writes scratch depth, copy
            // that depth into the eye's region of MC main FB. Post-world passes
            // (particles, hand item, item-in-hand glint, the next eye's setup,
            // Iris's own re-reads of MC main FB depth in subsequent renderLevel
            // invocations) all sample mainFb.getDepthTextureId() — without
            // this blit they read stale depth from the previous frame or from
            // a never-cleared region, and depth tests fail in unpredictable
            // ways that surface as the persistent "right-eye washed out" the
            // user reported (sky-color overlay where geometry should depth-occlude).
            if (scratchDepth > 0 && mainDepth > 0) {
                GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, scratchDepth, 0);
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                    GL11.GL_TEXTURE_2D, mainDepth, 0);
                // GL_NEAREST is REQUIRED for depth blits — GL_LINEAR on depth
                // is undefined and most drivers reject it with GL_INVALID_OPERATION.
                GL30.glBlitFramebuffer(
                    0, 0, scratchW, scratchH,
                    dstX0, dstY0, dstX1, dstY1,
                    GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            }
        } finally {
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            com.mojang.blaze3d.platform.GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glDeleteFramebuffers(readFbo);
            GL30.glDeleteFramebuffers(drawFbo);
            if (wasScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }
}
