package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import com.mitchellmarx.stereoscopic.render.StereoBlur;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.Window;
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
                 target = "Lnet/minecraft/client/gui/render/GuiRenderer;renderPreparedDraws(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V")
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
        method = "renderPreparedDraws(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gl/DynamicUniforms;write(Lorg/joml/Matrix4fc;Lorg/joml/Vector4fc;Lorg/joml/Vector3fc;Lorg/joml/Matrix4fc;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"),
        index = 0
    )
    private Matrix4fc stereoscopic$perEyeHudTransform(Matrix4fc original) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInGuiPass()) return original;

        Window w = MinecraftClient.getInstance().getWindow();
        float guiW = (float) w.getFramebufferWidth() / (float) w.getScaleFactor();

        boolean leftHalf;
        if (StereoOptions.INSTANCE.swapEyes) {
            leftHalf = (s.getCurrentEye() == StereoState.Eye.RIGHT);
        } else {
            leftHalf = (s.getCurrentEye() == StereoState.Eye.LEFT);
        }

        Matrix4f m = new Matrix4f(original);
        if (s.getFrameMode().isFull()) {
            // SBS_FULL: MixinWindow halves the GUI *layout* width, so content
            // laid out for [0..guiW/2] drawn UNcompressed at the (unchanged)
            // full-width ortho lands exactly in one half at 1:1 — no 0.5
            // squeeze. Only the right eye needs the half-window offset.
            if (!leftHalf) {
                m.translate(guiW * 0.5f, 0f, 0f);
            }
        } else if (leftHalf) {
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
        method = "renderPreparedDraws(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/render/GameRenderer;renderBlur()V")
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
