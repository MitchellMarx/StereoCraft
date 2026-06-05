package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Make {@code Screen.renderPanorama} stereo-correct under our per-eye GUI
 * iter. Companion to {@link MixinCubeMap}, which handles the cube map's
 * per-eye squish.
 *
 * <p>Vanilla's {@code renderBackground} sequence inside our per-eye iter:
 * <ol>
 *   <li>renderPanorama (when level is null)</li>
 *   <li>renderBlurredBackground -> {@code PostPass.process}, a full-FB blur
 *       that writes a fullscreen quad through its own ortho matrix --
 *       <em>ignores</em> {@code RenderSystem.getProjectionMatrix()}, so
 *       per-eye projection cannot restrict blur output to a half.</li>
 *   <li>renderMenuBackground (tile, per-eye projection)</li>
 *   <li>widgets (per-eye projection)</li>
 * </ol>
 *
 * <p>Two cases driven by {@code level}:
 * <ul>
 *   <li><b>Level loaded</b> (in-game pause menu, world-loading screen with
 *       reason OTHER): cancel the panorama entirely. The world is already
 *       rendered stereo into main FB by
 *       {@code MixinGameRenderer.stereoscopic$twoPassRenderWorld};
 *       subsequent {@code renderBlurredBackground} blurs that stereo world
 *       content.</li>
 *   <li><b>Level null</b> (title screen, world picker):
 *     <ul>
 *       <li>Second iter: cancel the panorama. The first iter's panorama
 *           survives.</li>
 *       <li>First iter: let {@link MixinCubeMap} squish the cube map into
 *           this iter's eye-half. On RETURN, mirror the eye-half into the
 *           other half via FB blit so both halves contain identical content
 *           before {@code renderBlurredBackground} runs. The full-FB blur
 *           then produces identical output in both halves; the second iter
 *           adds its own tile + widgets on top of the mirrored blurred
 *           backdrop. Without the mirror, the second iter's half would hold
 *           stale prev-frame content under the blur.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>Gated on {@code isInGuiPass()} so non-per-eye paths are unaffected.
 */
@Mixin(Screen.class)
public abstract class MixinScreen {

    @Inject(method = "renderPanorama(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            at = @At("HEAD"), cancellable = true)
    private void stereoscopic$prePanorama(GuiGraphics g, float partialTick, CallbackInfo ci) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInGuiPass()) return;
        if (s.getCurrentEye() == StereoState.Eye.RIGHT) {
            ci.cancel();
            return;
        }
        if (Minecraft.getInstance().level != null) {
            ci.cancel();
        }
    }

    @Inject(method = "renderPanorama(Lnet/minecraft/client/gui/GuiGraphics;F)V",
            at = @At("RETURN"))
    private void stereoscopic$postPanorama(GuiGraphics g, float partialTick, CallbackInfo ci) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isActive() || !s.isInGuiPass()) return;
        if (s.getCurrentEye() == StereoState.Eye.RIGHT) return;
        if (Minecraft.getInstance().level != null) return;
        PerEyeRenderer.mirrorActiveEyeHalfToOther();
    }
}
