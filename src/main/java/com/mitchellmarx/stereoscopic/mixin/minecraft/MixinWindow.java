package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SBS_FULL GUI layout width. In FULL mode each eye shows an UNcompressed GUI
 * in its half of the window, so everything laid out against the "gui scaled
 * width" — screen layout ({@code Screen.init}/{@code resize}), HUD anchoring
 * ({@code Gui}), and the mouse→GUI coordinate mapping ({@code MouseHandler})
 * — must see the per-eye logical width, i.e. half the vanilla value.
 *
 * <p>Crucially, on 1.21.1 the GUI ortho projection is built by
 * {@code GameRenderer.render} from {@code Window.getWidth() / getGuiScale()}
 * (the raw framebuffer width over the scale), NOT from this cached
 * {@code getGuiScaledWidth()} getter — so halving the getter shrinks the GUI
 * layout to half width while the ortho stays full-width. Half-width content
 * then projects into NDC [-1, 0] and {@link MixinGameRenderer}'s FULL branch
 * renders it 1:1 into each half. (The GUI-internal scissor pixels likewise
 * come out in the left half and are offset per-eye by
 * {@code MixinGlStateManager}'s FULL scissor branch.)
 *
 * <p>Ceil-halving the cached vanilla value keeps the same rounding family;
 * being one gui unit generous on odd sizes is harmless.
 *
 * <p>Reads live {@link StereoOptions} rather than the frame snapshot — screen
 * layout happens outside the render loop (e.g. {@code Screen.init} on mode
 * toggle), and layout must agree with the mode the next frames will render.
 */
@Mixin(Window.class)
public abstract class MixinWindow {

    @ModifyReturnValue(method = "getGuiScaledWidth()I", at = @At("RETURN"))
    private int stereoscopic$halfLayoutWidthForFullSbs(int original) {
        return StereoOptions.INSTANCE.mode.isFull() ? (original + 1) / 2 : original;
    }
}
