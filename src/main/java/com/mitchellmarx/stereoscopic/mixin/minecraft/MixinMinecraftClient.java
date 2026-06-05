package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.cursor.StereoCursor;
import com.mitchellmarx.stereoscopic.render.PerEyeRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MixinMinecraftClient {

    @Shadow @Final public MouseHandler mouseHandler;
    @Shadow @Final private Window window;

    /**
     * Seed mouse at the LEFT-half center when opening a Screen in SBS_HALF.
     * The geometric window center sits on the L/R seam — cursor would split
     * between eyes. {@code (width/4, height/2)} puts it at left-half center;
     * the per-eye HUD compression renders it cleanly in both eye views.
     */
    @Inject(method = "setScreen(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("RETURN"))
    private void stereoscopic$seedVirtualCursor(Screen screen, CallbackInfo ci) {
        if (screen == null) return;
        if (!StereoOptions.INSTANCE.mode.isActive()) return;
        MouseAccessor accessor = (MouseAccessor) (Object) this.mouseHandler;
        accessor.stereoscopic$setX(window.getScreenWidth() / 4.0);
        accessor.stereoscopic$setY(window.getScreenHeight() / 2.0);
    }

    /**
     * Per-eye world-FB substitution. Intercepts every caller — including
     * lambda-synthetics inside render passes that a method-scoped
     * {@code @Redirect} would miss.
     */
    @Inject(method = "getMainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;", at = @At("HEAD"), cancellable = true)
    private void stereoscopic$redirectToScratch(CallbackInfoReturnable<RenderTarget> cir) {
        if (!PerEyeRenderer.isScratchFbActive()) return;
        RenderTarget scratch = PerEyeRenderer.getScratchFb();
        if (scratch == null) return;
        cir.setReturnValue(scratch);
    }

    /**
     * Stop the cursor present thread before MC tears the window down. The
     * daemon worker spends most of its iteration inside native SwapBuffers;
     * once {@code Window.close()} invalidates the HDC the JNI frame can't be
     * preempted, and the JVM hangs after the window closes. Joining while the
     * window is still valid avoids the hang.
     */
    @Inject(method = "close()V", at = @At("HEAD"))
    private void stereoscopic$disposeOnShutdown(CallbackInfo ci) {
        StereoCursor.stop();
        PerEyeRenderer.disposeScratch();
    }
}
