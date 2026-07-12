package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * SBS_FULL GUI layout width. In FULL mode each eye shows an UNcompressed GUI
 * in its half of the window, so everything laid out against the "scaled width" —
 * screen layout ({@code Screen.init}/{@code resize}), HUD anchoring, and the
 * mouse→GUI coordinate mapping — must see the per-eye logical width, i.e. half
 * the vanilla value.
 *
 * <p><b>Safe because the GUI ortho + scissor scale are NOT derived from this
 * getter on 1.21.11.</b> {@code GuiRenderer.renderPreparedDraws} builds its
 * orthographic projection from {@code Window.getFramebufferWidth() /
 * getScaleFactor()} (verified in the 1.21.11 bytecode), and {@code enableScissor}
 * works in framebuffer-pixel space — neither reads {@code getScaledWidth()}. So
 * halving the layout getter leaves the render coordinate system full-width,
 * which is exactly what lets the half-width layout draw 1:1 into each half; the
 * per-eye placement is done in {@link MixinGuiRenderer}'s FULL branch (translate
 * only) and {@link MixinGuiRendererScissor}'s FULL branch (offset only). This is
 * the 1.21.11 analogue of the 26.2 port's split, where the ortho/scissor came
 * from {@code WindowRenderState} instead of the halved {@code getGuiScaledWidth}.
 *
 * <p>Ceil-halving the vanilla value keeps the same rounding family; being one
 * gui unit generous on odd sizes is harmless. Only the width is halved —
 * {@code getScaledHeight()} is untouched.
 *
 * <p>Reads live {@link StereoOptions} rather than the frame snapshot — screen
 * layout happens outside the render loop (e.g. {@code Screen.init} on mode
 * toggle), and layout must agree with the mode the next frames will render.
 */
@Mixin(Window.class)
public abstract class MixinWindow {

    @ModifyReturnValue(method = "getScaledWidth()I", at = @At("RETURN"))
    private int stereoscopic$halfLayoutWidthForFullSbs(int original) {
        return StereoOptions.INSTANCE.mode.isFull() ? (original + 1) / 2 : original;
    }
}
