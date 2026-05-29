package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.render.PerEyeScratchFb;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris's pipeline rebinds the main framebuffer mid-renderWorld. {@code bindFramebuffer(true)}
 * calls {@code glViewport(0,0,fbW,fbH)}, which clobbers the per-eye viewport set by
 * {@link MixinEntityRenderer_Stereo}. Without this restore, both eye passes render to the full
 * screen and you get a continuous panorama instead of SBS.
 *
 * <p>Inert while {@link PerEyeScratchFb#isActive()}. Under the scratch-FB design the world pass
 * renders into a full-display-sized scratch FB and the per-eye blit-squish runs afterward, so we
 * WANT the bind-time viewport to cover the full FB. Remapping back to the eye region here would
 * clip Iris's internal FBO writes to half the FB and produce the outer-quarter render artifact.
 */
@Mixin(Framebuffer.class)
public class MixinFramebuffer_Stereo {

    @Inject(method = "bindFramebuffer", at = @At("RETURN"))
    private void stereoscopic$restoreStereoViewport(boolean updateViewport, CallbackInfo ci) {
        if (PerEyeScratchFb.isActive()) return;
        if (!updateViewport) return;
        final StereoMode mode = StereoState.INSTANCE.getFrameMode();
        if (mode == null || !mode.isActive()) return;
        final StereoState.Eye eye = StereoState.INSTANCE.getCurrentEye();
        if (eye != StereoState.Eye.LEFT && eye != StereoState.Eye.RIGHT) return;

        final Minecraft mc = Minecraft.getMinecraft();
        final int fullW = mc.displayWidth;
        final int fullH = mc.displayHeight;
        final boolean sbs = mode.isSideBySide();
        final boolean half = mode.isHalf();
        final int eyeW = sbs ? (half ? fullW / 2 : fullW) : fullW;
        final int eyeH = sbs ? fullH               : (half ? fullH / 2 : fullH);

        final int x, y;
        if (eye == StereoState.Eye.LEFT) {
            x = 0;
            y = sbs ? 0 : fullH - eyeH;
        } else {
            x = sbs ? eyeW : 0;
            y = 0;
        }
        GL11.glViewport(x, y, eyeW, eyeH);
    }
}
