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
 * ({@code Gui}/{@code Hud}/{@code ContextualBar}/{@code LoadingOverlay}) and
 * the mouse→GUI coordinate mapping ({@code MouseHandler}) — must see the
 * per-eye logical width, i.e. half the vanilla value.
 *
 * <p><b>Why halving this getter is safe on 26.1.2 (verified in bytecode).</b>
 * The GUI ortho projection and the per-eye scissor do NOT derive from
 * {@code Window.getGuiScaledWidth()}:
 * <ul>
 *   <li>{@code GuiRenderer.draw()} builds its ortho via
 *       {@code Projection.setupOrtho(width/guiScale, guiScale, height/guiScale,
 *       guiScale, ...)} where {@code width}/{@code height}/{@code guiScale} are
 *       read from {@code GameRenderState.windowRenderState}
 *       ({@code WindowRenderState.width}/{@code .height}/{@code .guiScale}) —
 *       the FULL framebuffer dimensions, not this getter. So the ortho keeps
 *       spanning the full logical width, and half-width-laid-out GUI content
 *       drawn UNcompressed lands 1:1 in one half (see {@link MixinGuiRenderer}'s
 *       FULL branch; the right eye is translate-only, no 0.5 squeeze).</li>
 *   <li>{@code GuiRenderer.enableScissor} forwards {@code ScreenRectangle}s
 *       already in framebuffer-pixel space (logical×guiScale, guiScale
 *       unchanged), so a widget laid out in {@code [0..guiW/2]} scissors into
 *       {@code [0..fbW/2]} — matching the uncompressed left-half render; the
 *       right eye only needs the {@code +fbW/2} offset (see
 *       {@link MixinGuiRendererScissor}'s FULL branch).</li>
 * </ul>
 * Confirmed against this branch's {@code minecraft-merged-deobf-26.1.2} jar.
 *
 * <p>Ceil-halving the cached vanilla value ({@code ceil(fbW/scale)}) keeps
 * the same rounding family; being one gui unit generous on odd sizes is
 * harmless.
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
