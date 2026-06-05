package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import com.mitchellmarx.stereoscopic.render.StereoBlur;
import com.mitchellmarx.stereoscopic.render.ViewportMath;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Two-pass HUD flush. Wrapping the outer {@code render(GpuBufferSlice)} call
 * doesn't work: its prepare → renderPreparedDraws → clear sequence wipes the
 * draws list after the first eye, leaving the second to early-return.
 * Wrapping the inner {@code renderPreparedDraws} instead — that call only
 * reads the lists — lets us flush twice cleanly before the outer cleanup.
 */
@Mixin(GuiRenderer.class)
public abstract class MixinGuiRenderer {

    @WrapOperation(
        method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/render/GuiRenderer;draw(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V")
    )
    private void stereoscopic$twoPassRenderPreparedDraws(GuiRenderer self,
                                                          GpuBufferSlice fogSlice,
                                                          Operation<Void> original) {
        if (!StereoState.INSTANCE.isActive()) {
            original.call(self, fogSlice);
            return;
        }
        PerEyeRenderer.runForEachEye(PerEyeRenderer.Pass.GUI, () -> {
            original.call(self, fogSlice);
        });
    }

    /**
     * Per-eye panorama. {@code GuiRenderer.render} draws the title / world-picker
     * cube map at the top of the method — before the GUI {@code draw()} flush —
     * and it would render once (mono) across the full FB, so each SBS half shows
     * half of one panorama. The cube map's projection rides 26.x's single shared
     * projection UBO, which can't be reliably overridden per-eye, so instead we
     * render the panorama ONCE into the scratch FB (substituted via
     * {@link MixinMinecraftClient}'s {@code getMainRenderTarget()} redirect) and
     * then squish the full image into each eye-half with a {@code GL_LINEAR}
     * blit. Both halves get a complete, half-width-squished panorama — identical
     * between eyes, which is correct for a skybox at infinity (zero parallax).
     */
    @WrapOperation(
        method = "render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/renderer/CubeMap;render(FF)V")
    )
    private void stereoscopic$twoPassPanorama(CubeMap cubeMap, float spin, float alpha,
                                              Operation<Void> original) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive()) {
            original.call(cubeMap, spin, alpha);
            return;
        }
        int fbW = s.getFrameFbW();
        int fbH = s.getFrameFbH();
        RenderTarget mainFb = Minecraft.getInstance().getMainRenderTarget();
        if (mainFb == null || fbW <= 0 || fbH <= 0) {
            original.call(cubeMap, spin, alpha);
            return;
        }
        TextureTarget scratch = PerEyeRenderer.ensureScratchFb(fbW, fbH);
        PerEyeRenderer.setScratchFbActive(true);
        try {
            // Renders the full mono panorama into the scratch FB (getMainRenderTarget redirect).
            original.call(cubeMap, spin, alpha);
        } finally {
            PerEyeRenderer.setScratchFbActive(false);
        }
        ViewportMath.Rect left = ViewportMath.eyeRect(StereoState.Eye.LEFT, StereoOptions.INSTANCE.mode, fbW, fbH);
        ViewportMath.Rect right = ViewportMath.eyeRect(StereoState.Eye.RIGHT, StereoOptions.INSTANCE.mode, fbW, fbH);
        PerEyeRenderer.blitScratchColorToRect(scratch, mainFb,
            left.x(), left.y(), left.x() + left.w(), left.y() + left.h());
        PerEyeRenderer.blitScratchColorToRect(scratch, mainFb,
            right.x(), right.y(), right.x() + right.w(), right.y() + right.h());
    }

    /**
     * Per-eye HUD compression via the model-view matrix. 1.21's GUI flush goes
     * through {@code CommandEncoder.createRenderPass} which rasterizes to the
     * full color-attachment view ignoring {@code glViewport}; per-Draw
     * {@code scissorArea} also overrides any global scissor. The robust way
     * to clip the HUD to an eye's half is to compress the GUI coordinate
     * system at the model-view level — the full-FB ortho projection then
     * maps the compressed coords to the correct NDC half.
     *
     * <p>Which half each eye lands in depends on {@link StereoOptions#swapEyes},
     * so this mirrors {@link com.mitchellmarx.stereoscopic.render.ViewportMath#eyeRect}'s
     * swap branch — otherwise the world (which IS routed through the
     * swap-aware viewport) and the HUD (which is routed by this model-view
     * scaling) land on opposite halves, leaving the per-eye blur backdrop
     * stacked on one eye's world render with the other eye's HUD elements
     * floating on top.
     *
     * <ul>
     *   <li>"Left half" (NDC -1..0): scale x by 0.5</li>
     *   <li>"Right half" (NDC 0..+1): scale x by 0.5, translate +W/2</li>
     * </ul>
     *
     * <p>{@code renderPreparedDraws} builds its model-view via
     * {@code new Matrix4f().setTranslation(0, 0, -11000)} and passes it as
     * arg 0 of {@code DynamicUniforms.write(...)}; we modify it there.
     * JOML chains right-to-left in vector application: {@code m.translate(t).scale(s)}
     * = {@code M * T * S} = "scale first, then translate."
     */
    @ModifyArg(
        method = "draw(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/renderer/DynamicUniforms;writeTransform(Lorg/joml/Matrix4fc;Lorg/joml/Vector4fc;Lorg/joml/Vector3fc;Lorg/joml/Matrix4fc;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
        index = 0
    )
    private Matrix4fc stereoscopic$perEyeHudTransform(Matrix4fc original) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInGuiPass()) return original;

        Window w = Minecraft.getInstance().getWindow();
        float guiW = (float) w.getWidth() / (float) w.getGuiScale();

        boolean leftHalf;
        if (StereoOptions.INSTANCE.swapEyes) {
            leftHalf = (s.getCurrentEye() == StereoState.Eye.RIGHT);
        } else {
            leftHalf = (s.getCurrentEye() == StereoState.Eye.LEFT);
        }

        Matrix4f m = new Matrix4f(original);
        if (leftHalf) {
            m.scale(0.5f, 1f, 1f);
        } else {
            m.translate(guiW * 0.5f, 0f, 0f).scale(0.5f, 1f, 1f);
        }
        return m;
    }

    /**
     * Per-eye blur via {@link StereoBlur} — the vanilla blur runs through
     * {@code CommandEncoder.createRenderPass} which ignores classic glScissor
     * and the per-render-type scissor state, so we can't constrain it that
     * way. StereoBlur copies the eye's sub-rect into a half-width FB, runs
     * the shader there (kernel sees only that eye's pixels), and copies back.
     */
    @WrapOperation(
        method = "draw(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V")
    )
    private void stereoscopic$perEyeBlur(GameRenderer gameRenderer, Operation<Void> original) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInGuiPass()) {
            original.call(gameRenderer);
            return;
        }
        StereoBlur.applyPerEye();
    }
}
