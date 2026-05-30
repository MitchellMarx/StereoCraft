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
     * Per-eye HUD wrap. MC 1.21.1's {@code GameRenderer.render()} calls
     * {@code Gui.render(GuiGraphics, DeltaTracker)} once after the world
     * render finishes. With our world-render output already split into
     * eye-halves on the main FB, a single full-window HUD draw straddles the
     * L/R seam — each physical eye sees half. Wrap the call in
     * {@link PerEyeRenderer#runForEachEye} with {@code Pass.GUI} so the HUD
     * is drawn twice, once per eye-half, with eye-rect viewport + scissor
     * set up by PerEyeRenderer.
     */
    @WrapOperation(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/Gui;render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V")
    )
    private void stereoscopic$perEyeHud(net.minecraft.client.gui.Gui self,
                                         net.minecraft.client.gui.GuiGraphics graphics,
                                         DeltaTracker delta,
                                         Operation<Void> original) {
        if (!StereoState.INSTANCE.isActive()) {
            original.call(self, graphics, delta);
            return;
        }
        PerEyeRenderer.runForEachEye(PerEyeRenderer.Pass.GUI, () -> {
            original.call(self, graphics, delta);
            // GuiGraphics batches draws into a deferred MultiBufferSource that
            // doesn't flush until the GameRenderer.render() epilogue. Force a
            // flush HERE so each eye's draws rasterize with the eye-rect
            // viewport that PerEyeRenderer just set up.
            graphics.flush();
        });
    }

    /**
     * Per-eye Screen wrap. When a Screen is open (inventory, pause menu, our
     * Stereoscopic options screen, etc.) NeoForge routes the draw through
     * {@code ClientHooks.drawScreen(Screen, GuiGraphics, int, int, float)}.
     * Same shape as the HUD wrap — render twice, flush between iterations.
     */
    @WrapOperation(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/neoforged/neoforge/client/ClientHooks;drawScreen(Lnet/minecraft/client/gui/screens/Screen;Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
    )
    private void stereoscopic$perEyeScreen(net.minecraft.client.gui.screens.Screen screen,
                                            net.minecraft.client.gui.GuiGraphics graphics,
                                            int mouseX, int mouseY, float partialTick,
                                            Operation<Void> original) {
        if (!StereoState.INSTANCE.isActive()) {
            original.call(screen, graphics, mouseX, mouseY, partialTick);
            return;
        }
        PerEyeRenderer.runForEachEye(PerEyeRenderer.Pass.GUI, () -> {
            original.call(screen, graphics, mouseX, mouseY, partialTick);
            graphics.flush();
        });
    }

    /**
     * Per-eye loading overlay (Mojang logo on first launch, resource pack
     * reload, etc.). Same shape — wrap the Overlay.render call.
     */
    @WrapOperation(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/screens/Overlay;render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
    )
    private void stereoscopic$perEyeOverlay(net.minecraft.client.gui.screens.Overlay self,
                                             net.minecraft.client.gui.GuiGraphics graphics,
                                             int mouseX, int mouseY, float partialTick,
                                             Operation<Void> original) {
        if (!StereoState.INSTANCE.isActive()) {
            original.call(self, graphics, mouseX, mouseY, partialTick);
            return;
        }
        PerEyeRenderer.runForEachEye(PerEyeRenderer.Pass.GUI, () -> {
            original.call(self, graphics, mouseX, mouseY, partialTick);
            graphics.flush();
        });
    }

    /**
     * Per-eye toast component (advancement popups, tutorial hints).
     */
    @WrapOperation(
        method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/components/toasts/ToastComponent;render(Lnet/minecraft/client/gui/GuiGraphics;)V")
    )
    private void stereoscopic$perEyeToasts(net.minecraft.client.gui.components.toasts.ToastComponent self,
                                            net.minecraft.client.gui.GuiGraphics graphics,
                                            Operation<Void> original) {
        if (!StereoState.INSTANCE.isActive()) {
            original.call(self, graphics);
            return;
        }
        PerEyeRenderer.runForEachEye(PerEyeRenderer.Pass.GUI, () -> {
            original.call(self, graphics);
            graphics.flush();
        });
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
        // Camera.entity is null until Camera.setup() runs. Some modpack mods
        // (Sable) wrap Camera.setPosition and dereference entity unconditionally;
        // shifting the camera before setup → NPE through their wrap. Skip
        // per-eye for any frame where the camera isn't yet bound to an entity
        // (typically just the first frame after world load).
        if (camera.getEntity() == null) {
            original.call(self, tickCounter);
            return;
        }
        Vec3 basePos = camera.getPosition();
        StereoState.INSTANCE.setFrameMonoCameraPos(basePos);
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
                // RIGHT, so negate Left.
                float dx = StereoState.INSTANCE.getEyeOffset();
                Vector3f left = camera.getLeftVector();
                Vec3 eyePos = basePos.add(-left.x() * dx, -left.y() * dx, -left.z() * dx);
                ((CameraAccessor)(Object)camera).stereoscopic$setPos(eyePos);

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
        int mainTex = mainFb.getColorTextureId();
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
}
