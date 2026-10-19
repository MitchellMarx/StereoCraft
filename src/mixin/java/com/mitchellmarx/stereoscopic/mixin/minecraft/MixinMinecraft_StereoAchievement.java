package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.config.StereoConfig;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.achievement.GuiAchievement;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Vanilla {@code Minecraft.runGameLoop} calls {@code guiAchievement.updateAchievementWindow}
 * after the {@code EntityRenderer.updateCameraAndRender} block — outside every stereo
 * redirect. The popup's own setup resets the GL viewport to the full display each call
 * (see {@code GuiAchievement.func_146258_c}), so left alone it draws once at the top-right
 * of the full window and only one eye sees it. Redirect the call and post it per eye, with
 * {@code StereoState.enterGuiPass} active so {@code GLStateManager} can route the inner
 * full-FB viewport reset to the current eye's region.
 */
@Mixin(value = Minecraft.class, priority = 1100)
public class MixinMinecraft_StereoAchievement {

    @Redirect(
        method = "runGameLoop",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/achievement/GuiAchievement;func_146254_a()V"
        )
    )
    private void stereoscopic$stereoAchievement(GuiAchievement popup) {
        final StereoMode mode = StereoState.INSTANCE.getFrameMode();
        // Both SBS modes draw the popup once per eye-half; FULL's 1:1 per-eye layout comes from
        // the halved ScaledResolution (MixinScaledResolution_StereoFull).
        final boolean stereoActive = mode != null && mode.isActive() && mode.isSideBySide();
        if (!stereoActive) {
            popup.func_146254_a();
            return;
        }

        final Minecraft mc = Minecraft.getMinecraft();
        final int fullW = mc.displayWidth;
        final int fullH = mc.displayHeight;
        final int eyeW = fullW / 2;
        final int eyeH = fullH;
        // Same swap-aware half selection as MixinEntityRenderer_Stereo's GUI paths.
        final boolean swap = StereoConfig.stereoSwapEyes;
        final int leftX  = swap ? eyeW : 0;
        final int rightX = swap ? 0    : eyeW;

        GL11.glViewport(leftX, 0, eyeW, eyeH);
        StereoState.INSTANCE.enterGuiPass(leftX, 0, eyeW, eyeH);
        // func_146254_a's final renderItemAndEffectIntoGUI leaves GL_BLEND disabled, so the second
        // pass would render the popup background's transparent regions as opaque black. Set the
        // expected GUI blend state explicitly before each pass.
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        popup.func_146254_a();
        StereoState.INSTANCE.exitGuiPass();

        GL11.glViewport(rightX, 0, eyeW, eyeH);
        StereoState.INSTANCE.enterGuiPass(rightX, 0, eyeW, eyeH);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        popup.func_146254_a();
        StereoState.INSTANCE.exitGuiPass();

        GL11.glViewport(0, 0, fullW, fullH);
    }
}
