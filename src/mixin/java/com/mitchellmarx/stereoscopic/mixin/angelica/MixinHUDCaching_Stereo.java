package com.mitchellmarx.stereoscopic.mixin.angelica;

import com.gtnewhorizons.angelica.hudcaching.HUDCaching;
import com.mitchellmarx.stereoscopic.compat.xaero.XaeroCompat;
import com.mitchellmarx.stereoscopic.core.StereoState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngame;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.IntBuffer;

/**
 * Makes Angelica's {@link HUDCaching#renderCachedHud} stereo-aware via two surgical @Redirects:
 *
 * <ol>
 *   <li>Gates the Xaero pre-render hook to once-per-frame (sbs2's renderCachedHudStereo
 *       fires it once; calling Angelica's renderCachedHud twice would fire it twice).
 *   <li>Forces glViewport to full-screen around the dirty-branch's cache-fill
 *       renderGameOverlay call (ordinal 1 — the one inside `if (dirty)`, not the early-out
 *       in the `!hudCachingActive` branch). The cache framebuffer is full-display-sized; if
 *       the caller has an eye-region viewport set, the HUD renders into a half of the cache
 *       and the second eye then blits a half-filled cache. With this @Redirect, our stereo
 *       path can set the eye viewport, call renderCachedHud, and get one full-screen cache
 *       fill plus one eye-region blit per call.
 * </ol>
 *
 * <p>The captured-bits and final blit phases of renderCachedHud naturally respect the
 * caller's viewport (they use {@code setupOverlayRendering}'s ortho projection rasterized
 * to the current viewport). Those fire twice per frame in stereo, matching sbs2's
 * blitCachedHud-twice behavior.
 */
@Mixin(HUDCaching.class)
public abstract class MixinHUDCaching_Stereo {

    @Unique private static long stereoscopic$lastXaeroFrameToken = -1L;
    @Unique private static final IntBuffer stereoscopic$viewportScratch = BufferUtils.createIntBuffer(16);

    @Redirect(
        method = "renderCachedHud",
        remap = false,
        at = @At(
            value = "INVOKE",
            target = "Lxaero/common/core/XaeroMinimapCore;beforeIngameGuiRender(F)V",
            remap = false
        )
    )
    private static void stereoscopic$gateXaeroOncePerFrame(float partialTicks) {
        if (!StereoState.INSTANCE.isActive()) {
            // Non-stereo path: pass through unchanged.
            XaeroCompat.beforeIngameGuiRender(partialTicks);
            return;
        }
        // Frame token: StereoState.frameSequence increments once per beginFrame() call, so it's
        // guaranteed monotonic per frame. getSystemTime() (milliseconds) raced at >1 KHz fps where
        // two renderCachedHud calls could land 1ms+ apart in the same frame and fire the hook twice.
        final long token = StereoState.INSTANCE.getFrameSequence();
        if (token != stereoscopic$lastXaeroFrameToken) {
            stereoscopic$lastXaeroFrameToken = token;
            XaeroCompat.beforeIngameGuiRender(partialTicks);
        }
    }

    @Redirect(
        method = "renderCachedHud",
        remap = false,
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;renderGameOverlay(FZII)V",
            ordinal = 1   // 0 is the !hudCachingActive bail-out; 1 is the cache-fill call
        )
    )
    private static void stereoscopic$cacheFillAtFullScreen(GuiIngame ingame, float partialTicks,
                                                           boolean hasScreen, int mouseX, int mouseY) {
        if (!StereoState.INSTANCE.isActive()) {
            // Non-stereo path: pass through unchanged.
            ingame.renderGameOverlay(partialTicks, hasScreen, mouseX, mouseY);
            return;
        }
        // Save current viewport, force full-screen for the cache render, restore on return.
        stereoscopic$viewportScratch.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, stereoscopic$viewportScratch);
        final int x = stereoscopic$viewportScratch.get(0);
        final int y = stereoscopic$viewportScratch.get(1);
        final int w = stereoscopic$viewportScratch.get(2);
        final int h = stereoscopic$viewportScratch.get(3);
        final Minecraft mc = Minecraft.getMinecraft();
        GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        try {
            ingame.renderGameOverlay(partialTicks, hasScreen, mouseX, mouseY);
        } finally {
            GL11.glViewport(x, y, w, h);
        }
    }
}
