package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * MC 26.2 moved screen management from {@code Minecraft} into {@code Gui};
 * this hosts the {@code setScreen} hook that used to live in
 * {@link MixinMinecraftClient}.
 */
@Mixin(Gui.class)
public abstract class MixinGui {

    @Shadow @Final private Minecraft minecraft;

    /**
     * Seed mouse at the LEFT-half center when opening a Screen in SBS.
     * The geometric window center sits on the L/R seam — cursor would split
     * between eyes. {@code (width/4, height/2)} puts it at left-half center;
     * the per-eye HUD compression renders it cleanly in both eye views.
     */
    @Inject(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("RETURN"))
    private void stereoscopic$seedVirtualCursor(Screen screen, CallbackInfo ci) {
        if (screen == null) return;
        if (!StereoOptions.INSTANCE.mode.isActive()) return;
        Window window = minecraft.getWindow();
        MouseAccessor accessor = (MouseAccessor) (Object) minecraft.mouseHandler;
        accessor.stereoscopic$setX(window.getScreenWidth() / 4.0);
        accessor.stereoscopic$setY(window.getScreenHeight() / 2.0);
    }
}
