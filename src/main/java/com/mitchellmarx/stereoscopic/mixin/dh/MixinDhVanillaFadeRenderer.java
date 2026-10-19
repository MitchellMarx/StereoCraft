package com.mitchellmarx.stereoscopic.mixin.dh;

import com.seibel.distanthorizons.common.render.openGl.postProcessing.fade.GlVanillaFadeRenderer;
import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper;
import com.seibel.distanthorizons.core.render.RenderParams;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL45C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DH's vanilla-fade pass writes into a framebuffer whose colour attachment is
 * MC's main colour texture, captured once in {@code createFramebuffer} and
 * only re-captured on a size change. Our per-eye scratch FB has the same size
 * as the real main FB, so a fade FB created outside the eye passes (stereo
 * toggled on in-game) keeps the REAL main texture: each eye pass then paints
 * its full-size, unsqueezed image straight into the window's main target, and
 * the left half shows a cropped full-width eye image instead of the squeezed
 * LEFT eye. Re-attach whatever {@code getMainRenderTarget()} is right now
 * (scratch inside an eye pass) before every fade render.
 */
@Mixin(value = GlVanillaFadeRenderer.class, remap = false)
public abstract class MixinDhVanillaFadeRenderer {

    @Shadow private int fadeFramebuffer;

    @Inject(method = "render", at = @At("HEAD"))
    private void stereoscopic$attachCurrentColorTarget(RenderParams params, CallbackInfo ci) {
        MinecraftRenderWrapper mc = MinecraftRenderWrapper.INSTANCE;
        if (fadeFramebuffer == -1 || mc.mcRendersToFrameBuffer()) return; // fade FB owns its texture then
        int color = mc.getGlColorTextureId();
        if (color != -1) GL45C.glNamedFramebufferTexture(fadeFramebuffer, GL30C.GL_COLOR_ATTACHMENT0, color, 0);
    }
}
