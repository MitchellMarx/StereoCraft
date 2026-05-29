package com.mitchellmarx.stereoscopic.mixin.angelica;

import com.gtnewhorizons.angelica.glsm.GLStateManager;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla {@code EntityRenderer.renderWorld} sets the viewport to the full main framebuffer
 * early in its body (a vanilla {@code GL11.glViewport} call that {@code GLSMRedirector} ASM-
 * rewrites at class load to {@code GLStateManager.glViewport}). With shaders enabled, Iris's
 * pipeline rebinds the framebuffer mid-renderWorld and our {@link
 * com.mitchellmarx.stereoscopic.mixin.minecraft.MixinFramebuffer_Stereo} restores the eye
 * viewport for the remainder of the pass. With shaders disabled, no rebind happens, so the
 * full-screen viewport persists and both eye passes rasterize at full resolution (clipped only
 * by scissor — visible result is "mono").
 *
 * <p>This mixin intercepts the full-screen viewport call during a stereo world pass and remaps
 * it to the current eye's viewport. Uses a thread-local re-entry flag so the recursive call
 * after remap doesn't loop.
 *
 * <p>The {@code (x == 0 && y == 0 && width == displayW && height == displayH)} check is a
 * narrow trigger — it specifically catches "the caller wants to reset to full screen" and is
 * structurally impossible to trigger for legitimate eye-sized viewport calls.
 */
@Mixin(value = GLStateManager.class, remap = false)
public class MixinGLStateManager_StereoRemap {

    private static final ThreadLocal<Boolean> stereoscopic$remapping =
        ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Inject(method = "glViewport(IIII)V", at = @At("HEAD"), cancellable = true)
    private static void stereoscopic$remapWorldPassViewport(int x, int y, int width, int height,
                                                            CallbackInfo ci) {
        if (stereoscopic$remapping.get()) return;
        if (!StereoState.INSTANCE.isInWorldPass()) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        // Only remap "caller asked for full main FB" — leave eye-sized calls alone.
        if (x != 0 || y != 0 || width != mc.displayWidth || height != mc.displayHeight) return;
        final int eyeX = StereoState.INSTANCE.getEyeVpX();
        final int eyeY = StereoState.INSTANCE.getEyeVpY();
        final int eyeW = StereoState.INSTANCE.getEyeVpW();
        final int eyeH = StereoState.INSTANCE.getEyeVpH();
        if (eyeW <= 0 || eyeH <= 0) return;  // not yet primed
        stereoscopic$remapping.set(Boolean.TRUE);
        try {
            GLStateManager.glViewport(eyeX, eyeY, eyeW, eyeH);
        } finally {
            stereoscopic$remapping.set(Boolean.FALSE);
        }
        ci.cancel();
    }
}
