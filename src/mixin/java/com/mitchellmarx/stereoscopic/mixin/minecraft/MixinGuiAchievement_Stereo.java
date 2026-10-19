package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.gui.achievement.GuiAchievement;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code GuiAchievement.func_146258_c} (the popup's GL setup) opens with
 * {@code glViewport(0, 0, displayWidth, displayHeight)}, so both per-eye passes
 * posted by {@link MixinMinecraft_StereoAchievement} drew at the same
 * full-window spot and the popup appeared once, top-right of the window, in
 * one eye only. Nothing re-routed that reset (an earlier comment assumed
 * GLStateManager did). Re-apply the current eye's viewport after the setup:
 * the popup's ortho then maps into the eye half like the rest of the HUD
 * (squeezed in HALF; 1:1 in FULL via the halved ScaledResolution).
 * Caught by the self-test's popup check (dark px LEFT=0 / RIGHT=12648).
 */
@Mixin(GuiAchievement.class)
public class MixinGuiAchievement_Stereo {

    @Inject(method = "func_146258_c", at = @At("TAIL"))
    private void stereoscopic$perEyeViewport(CallbackInfo ci) {
        StereoState s = StereoState.INSTANCE;
        if (!s.isInGuiPass()) return;
        GL11.glViewport(s.eyeVpX(), s.eyeVpY(), s.eyeVpW(), s.eyeVpH());
    }
}
