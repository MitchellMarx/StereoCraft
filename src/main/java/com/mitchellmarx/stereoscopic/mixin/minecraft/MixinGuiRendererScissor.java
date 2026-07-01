package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mojang.blaze3d.systems.RenderPass;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Per-eye GUI scissor remap. {@code DrawContext.enableScissor} stores
 * {@code ScreenRect} in framebuffer-pixel space and forwards to
 * {@code RenderPass.enableScissor}. Our MixinGuiRenderer model-view
 * compression remaps vertex x → fbX (LEFT) or fbX + fbW/2 (RIGHT), but the
 * scissor coords stay at full fb scale — so an unchanged scissor at fb x=300
 * clips the LEFT eye's compressed geometry (now at fb x=150) off the eye
 * region. Apply the same compression to scissor coords:
 *
 * <ul>
 *   <li>left half:  {@code (x, y, w, h) → (x/2, y, w/2, h)}</li>
 *   <li>right half: {@code (x, y, w, h) → (x/2 + fbW/2, y, w/2, h)}</li>
 * </ul>
 *
 * <p><b>Must honor {@link StereoOptions#swapEyes}</b> — the same way
 * {@link com.mitchellmarx.stereoscopic.render.ViewportMath#eyeRect} and
 * {@link MixinGuiRenderer}'s {@code perEyeHudTransform} do. If the scissor picks
 * the half WITHOUT swap while the model-view compresses geometry into the
 * swap-chosen half, the two disagree under swap and the scissor clips the
 * geometry off the opposite half. All scissored GUI content (e.g. Sodium's
 * scrollable options list) then vanishes — the "config menu disappeared after
 * enabling eye-swap" bug.
 *
 * <p>Separate mixin from MixinGuiRenderer to keep the model-view ModifyArg
 * and this WrapOperation visually distinct — different injection targets on
 * the same class.
 */
@Mixin(GuiRenderer.class)
public abstract class MixinGuiRendererScissor {

    @WrapOperation(
        method = "enableScissor(Lnet/minecraft/client/gui/ScreenRect;Lcom/mojang/blaze3d/systems/RenderPass;)V",
        at = @At(value = "INVOKE",
                 target = "Lcom/mojang/blaze3d/systems/RenderPass;enableScissor(IIII)V")
    )
    private void stereoscopic$perEyeScissor(RenderPass pass, int x, int y, int w, int h, Operation<Void> original) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInGuiPass()) {
            original.call(pass, x, y, w, h);
            return;
        }
        if (s.getCurrentEye() == StereoState.Eye.MONO) {
            original.call(pass, x, y, w, h);
            return;
        }
        int fbW = s.getFrameFbW();
        // Which half this eye lands in MUST match ViewportMath.eyeRect and
        // MixinGuiRenderer.perEyeHudTransform, swapEyes included — otherwise the
        // scissor clips the compressed geometry off the opposite half.
        boolean leftHalf = StereoOptions.INSTANCE.swapEyes
            ? (s.getCurrentEye() == StereoState.Eye.RIGHT)
            : (s.getCurrentEye() == StereoState.Eye.LEFT);
        if (leftHalf) {
            original.call(pass, x / 2,           y, w / 2, h);
        } else {
            original.call(pass, x / 2 + fbW / 2, y, w / 2, h);
        }
    }
}
