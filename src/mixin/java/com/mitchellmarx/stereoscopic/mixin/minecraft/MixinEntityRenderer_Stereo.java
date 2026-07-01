package com.mitchellmarx.stereoscopic.mixin.minecraft;

import com.gtnewhorizons.angelica.hudcaching.HUDCaching;
import com.mitchellmarx.stereoscopic.compat.chromatictooltips.ChromaticTooltipsCompat;
import com.mitchellmarx.stereoscopic.config.StereoConfig;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoState;
import com.mitchellmarx.stereoscopic.cursor.CursorPresentThread;
import com.mitchellmarx.stereoscopic.cursor.StereoCursor;
import com.mitchellmarx.stereoscopic.render.PerEyeScratchFb;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import net.coderbot.iris.Iris;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraftforge.client.event.GuiScreenEvent;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Priority 1100 (above HUD_CACHING's default) so our renderGameOverlay redirect wins. The
 * non-stereo path delegates to {@link HUDCaching#renderCachedHud} to preserve HUD-caching perf
 * for users who didn't enable stereo.
 */
@Mixin(value = EntityRenderer.class, priority = 1100)
public abstract class MixinEntityRenderer_Stereo {

    @Shadow public abstract void renderWorld(float partialTicks, long finishTimeNano);

    /** True once we've forced Iris's pipeline to rebuild after first stereo activation. */
    private static boolean stereoscopic$irisRebuiltForStereo = false;

    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    private void stereoscopic$stereoBeginFrame(float partialTicks, CallbackInfo ci) {
        StereoState.INSTANCE.beginFrame();
        StereoCursor.update();
        stereoscopic$ensureIrisRebuiltForStereo();
    }

    /**
     * When stereo is enabled in config at game launch AND a shaderpack is loaded, Iris's
     * pipeline init runs BEFORE our scratch-FB / per-eye state ever fires — the pipeline
     * caches references that don't include stereo handling and the world fails to render
     * until the user manually toggles shaders. The Sodium stereo-toggle binding already
     * does destroyPipeline + preparePipeline + loadRenderers on each flip; this replicates
     * the same trigger once on the first stereo-active frame so startup-with-stereo-already-
     * on works without manual intervention.
     */
    private static void stereoscopic$ensureIrisRebuiltForStereo() {
        if (stereoscopic$irisRebuiltForStereo) return;
        if (!StereoState.INSTANCE.isActive()) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.renderGlobal == null) return;
        if (!com.gtnewhorizons.angelica.config.AngelicaConfig.enableIris) {
            // Nothing to rebuild — vanilla pipeline doesn't cache main-FB references.
            stereoscopic$irisRebuiltForStereo = true;
            return;
        }
        Iris.getPipelineManager().destroyPipeline();
        Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimensionName());
        mc.renderGlobal.loadRenderers();
        stereoscopic$irisRebuiltForStereo = true;
    }

    @Inject(method = "updateCameraAndRender", at = @At("RETURN"))
    private void stereoscopic$stereoEndFrame(float partialTicks, CallbackInfo ci) {
        if (StereoState.INSTANCE.isActive()) {
            // Restore the full viewport for anything that runs after this frame's end
            // (screenshots, post-frame overlays) and assumes the default state.
            Minecraft mc = Minecraft.getMinecraft();
            GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
        }
        // Note: cursor thread's publishFrame() is intentionally NOT called here. It is called
        // later in the frame at framebufferRender HEAD (see MixinFramebuffer_AsyncCursor), so
        // we capture framebufferMc after onRenderTickEnd has had a chance to add its content
        // (WAILA HUD, achievement popups, etc.). Capturing here would miss those.
        StereoState.INSTANCE.endFrame();
    }

    @Redirect(
        method = "updateCameraAndRender",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/EntityRenderer;renderWorld(FJ)V"
        )
    )
    private void stereoscopic$stereoRenderWorld(EntityRenderer self, float partialTicks, long finishTimeNano) {
        final StereoMode mode = StereoState.INSTANCE.getFrameMode();
        if (mode == null || !mode.isActive()) {
            self.renderWorld(partialTicks, finishTimeNano);
            return;
        }

        final Minecraft mc = Minecraft.getMinecraft();
        final int fullW = mc.displayWidth;
        final int fullH = mc.displayHeight;

        final boolean sbs = mode.isSideBySide();
        final boolean half = mode.isHalf();

        final int eyeW = sbs ? (half ? fullW / 2 : fullW) : fullW;
        final int eyeH = sbs ? fullH               : (half ? fullH / 2 : fullH);

        // Swap-eyes flips which screen half each logical eye blits into. Paired with the
        // negated IPD in StereoState.getEyeOffset so reversed-SBS displays / VR passthrough
        // virtual monitors stay self-consistent.
        final boolean swap = StereoConfig.stereoSwapEyes;
        final int leftX  = swap ? (sbs ? eyeW : 0)            : 0;
        final int leftY  = swap ? 0                           : (sbs ? 0 : fullH - eyeH);
        final int rightX = swap ? 0                           : (sbs ? eyeW : 0);
        final int rightY = swap ? (sbs ? 0 : fullH - eyeH)    : 0;

        // Capture the REAL main FB reference before activating the scratch substitution.
        // After setActive(true), Minecraft.getFramebuffer() returns the scratch — we need
        // the real one for the final blit destination.
        final Framebuffer realMainFb = mc.getFramebuffer();

        PerEyeScratchFb.ensureSized(fullW, fullH);

        // LEFT eye
        StereoState.INSTANCE.setEye(StereoState.Eye.LEFT);
        StereoState.INSTANCE.enterWorldPass(leftX, leftY, eyeW, eyeH);
        stereoscopic$setIrisActiveEye(0);
        PerEyeScratchFb.setActive(true);
        try {
            PerEyeScratchFb.get().bindFramebuffer(true);
            // Reset state that prior-frame HUD/GUI render leaves dirty (GL_LIGHTING from
            // enableGUIStandardItemLighting, possibly non-white glColor) so the FIRST eye's
            // sky/sun/terrain don't render dimmer than the SECOND eye's. RIGHT eye inherits
            // LEFT's post-renderWorld state, which is already correct.
            RenderHelper.disableStandardItemLighting();
            GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
            self.renderWorld(partialTicks, finishTimeNano);
        } finally {
            PerEyeScratchFb.setActive(false);
        }
        PerEyeScratchFb.blitScratchToMain(realMainFb);
        StereoState.INSTANCE.exitWorldPass();

        // RIGHT eye
        StereoState.INSTANCE.setEye(StereoState.Eye.RIGHT);
        StereoState.INSTANCE.enterWorldPass(rightX, rightY, eyeW, eyeH);
        stereoscopic$setIrisActiveEye(1);
        PerEyeScratchFb.setActive(true);
        try {
            PerEyeScratchFb.get().bindFramebuffer(true);
            RenderHelper.disableStandardItemLighting();
            GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
            self.renderWorld(partialTicks, finishTimeNano);
        } finally {
            PerEyeScratchFb.setActive(false);
        }
        PerEyeScratchFb.blitScratchToMain(realMainFb);
        StereoState.INSTANCE.exitWorldPass();

        // Restore mono eye + main FB for the HUD/GUI rendering that follows.
        stereoscopic$setIrisActiveEye(0);
        StereoState.INSTANCE.setEye(StereoState.Eye.MONO);
        realMainFb.bindFramebuffer(true);
        GL11.glViewport(0, 0, fullW, fullH);
    }

    @Redirect(
        method = "updateCameraAndRender",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiIngame;renderGameOverlay(FZII)V"
        )
    )
    private void stereoscopic$stereoRenderHud(GuiIngame ingame, float partialTicks, boolean hasScreen, int mouseX, int mouseY) {
        final StereoMode mode = StereoState.INSTANCE.getFrameMode();
        if (mode == null || !mode.isActive()) {
            // Stereo off — defer to HUDCaching since we won the @Redirect priority fight.
            HUDCaching.renderCachedHud(
                Minecraft.getMinecraft().entityRenderer, ingame, partialTicks, hasScreen, mouseX, mouseY);
            return;
        }

        // Keep HUD caching alive — render once into the cache FBO at full resolution,
        // then blit the cache into each eye's viewport. MixinHUDCaching_Stereo's @Redirects make
        // the published renderCachedHud stereo-safe (once-per-frame Xaero gate + full-screen
        // viewport for the cache fill); we drive it twice with eye-specific viewports.
        final Minecraft mc = Minecraft.getMinecraft();
        final int fullW = mc.displayWidth;
        final int fullH = mc.displayHeight;
        final boolean sbs = mode.isSideBySide();
        final boolean half = mode.isHalf();

        final int eyeW = sbs ? (half ? fullW / 2 : fullW) : fullW;
        final int eyeH = sbs ? fullH               : (half ? fullH / 2 : fullH);

        final boolean swap = StereoConfig.stereoSwapEyes;
        final int leftX  = swap ? (sbs ? eyeW : 0)            : 0;
        final int leftY  = swap ? 0                           : (sbs ? 0 : fullH - eyeH);
        final int rightX = swap ? 0                           : (sbs ? eyeW : 0);
        final int rightY = swap ? (sbs ? 0 : fullH - eyeH)    : 0;

        GL11.glViewport(leftX, leftY, eyeW, eyeH);
        StereoState.INSTANCE.enterGuiPass(leftX, leftY, eyeW, eyeH);
        HUDCaching.renderCachedHud(mc.entityRenderer, ingame, partialTicks, hasScreen, mouseX, mouseY);
        StereoState.INSTANCE.exitGuiPass();

        GL11.glViewport(rightX, rightY, eyeW, eyeH);
        StereoState.INSTANCE.enterGuiPass(rightX, rightY, eyeW, eyeH);
        HUDCaching.renderCachedHud(mc.entityRenderer, ingame, partialTicks, hasScreen, mouseX, mouseY);
        StereoState.INSTANCE.exitGuiPass();

        GL11.glViewport(0, 0, fullW, fullH);
    }

    @Redirect(
        method = "updateCameraAndRender",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiScreen;drawScreen(IIF)V"
        )
    )
    private void stereoscopic$stereoDrawScreen(GuiScreen screen, int mouseX, int mouseY, float partialTicks) {
        final StereoMode mode = StereoState.INSTANCE.getFrameMode();
        if (mode == null || !mode.isActive()) {
            screen.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        // Scratch-FB GUI path. We render the screen ONCE into a full-display-sized scratch FB,
        // then blit-squish into each eye's region of the main FB. This makes any GUI code that
        // uses display-pixel coordinates (e.g. Reese's Sodium Options' applyScissor, which
        // builds scissor rectangles from ScaledResolution.getScaleFactor() and assumes the
        // GUI starts at display pixel 0) "honest" — the scratch IS at full display size, so
        // those scissor coordinates clip correctly. The per-eye horizontal compression then
        // happens at the blit step instead of leaking into the GUI's own scissor math.
        //
        // Side effect: the dim-world background (drawDefaultBackground) is no longer
        // alpha-blended against the per-eye world render in the main FB — the blit is
        // destructive. For typical GUIs (pause menu, inventory, options) the world fully
        // hides behind the dim BG anyway, so the visual delta is minor.

        final Minecraft mc = Minecraft.getMinecraft();
        final int fullW = mc.displayWidth;
        final int fullH = mc.displayHeight;
        final boolean sbs = mode.isSideBySide();
        final boolean half = mode.isHalf();
        final int eyeW = sbs ? (half ? fullW / 2 : fullW) : fullW;
        final int eyeH = sbs ? fullH               : (half ? fullH / 2 : fullH);
        final boolean swap = StereoConfig.stereoSwapEyes;
        final int leftX  = swap ? (sbs ? eyeW : 0)            : 0;
        final int leftY  = swap ? 0                           : (sbs ? 0 : fullH - eyeH);
        final int rightX = swap ? 0                           : (sbs ? eyeW : 0);
        final int rightY = swap ? (sbs ? 0 : fullH - eyeH)    : 0;

        final Framebuffer realMainFb = mc.getFramebuffer();
        PerEyeScratchFb.ensureSized(fullW, fullH);

        // Render the screen ONCE at full display size into scratch.
        PerEyeScratchFb.setActive(true);
        try {
            PerEyeScratchFb.get().bindFramebuffer(true);
            // The scratch carries world-render content from earlier in the frame; clear so
            // those fragments don't bleed through the GUI's dim background.
            GL11.glClearColor(0f, 0f, 0f, 0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            // Same GL-state reset as the world pass: GuiContainer.drawScreen leaves item
            // lighting enabled and HUD render can leave glColor in arbitrary state.
            RenderHelper.disableStandardItemLighting();
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            screen.drawScreen(mouseX, mouseY, partialTicks);
        } finally {
            PerEyeScratchFb.setActive(false);
        }

        // Blit-squish scratch into each eye region of the main FB. The blit is destructive
        // — that's correct here because the GUI screen fully painted the scratch (dim BG +
        // controls), so replacing the eye region matches expected GUI-overlay behavior.
        StereoState.INSTANCE.enterGuiPass(leftX, leftY, eyeW, eyeH);
        PerEyeScratchFb.blitScratchToMain(realMainFb);
        StereoState.INSTANCE.exitGuiPass();

        StereoState.INSTANCE.enterGuiPass(rightX, rightY, eyeW, eyeH);
        PerEyeScratchFb.blitScratchToMain(realMainFb);
        StereoState.INSTANCE.exitGuiPass();

        // Restore main FB + full viewport. The DrawScreenEvent.Post handler that follows
        // (stereoscopic$stereoPostEvent) will set its own per-eye viewport.
        realMainFb.bindFramebuffer(true);
        GL11.glViewport(0, 0, fullW, fullH);
    }

    /**
     * Wrap the {@code DrawScreenEvent.Post} dispatch. NEI and other mods render tooltips,
     * overlay buttons, and item icons during that event; we post it twice (once per eye
     * viewport) so the items appear in both eyes, then draw the synthetic cursor on top.
     * The {@code ordinal = 1} targets the second {@code EVENT_BUS.post} in
     * {@code updateCameraAndRender} — Pre is ordinal 0, Post is ordinal 1.
     */
    @Redirect(
        method = "updateCameraAndRender",
        at = @At(
            value = "INVOKE",
            target = "Lcpw/mods/fml/common/eventhandler/EventBus;post(Lcpw/mods/fml/common/eventhandler/Event;)Z",
            ordinal = 1,
            remap = false
        )
    )
    private boolean stereoscopic$stereoPostEvent(EventBus bus, Event event) {
        final StereoMode mode = StereoState.INSTANCE.getFrameMode();
        final boolean stereoActive = mode != null && mode.isActive()
            && mode.isSideBySide() && mode.isHalf();
        if (!stereoActive) {
            return bus.post(event);
        }

        final Minecraft mc = Minecraft.getMinecraft();
        final int fullW = mc.displayWidth;
        final int fullH = mc.displayHeight;
        final int eyeW = fullW / 2;
        final int eyeH = fullH;
        final boolean swap = StereoConfig.stereoSwapEyes;
        final int leftX  = swap ? eyeW : 0;
        final int rightX = swap ? 0    : eyeW;

        // LEFT eye: post the event so NEI's overlay + tooltip items render here.
        GL11.glViewport(leftX, 0, eyeW, eyeH);
        StereoState.INSTANCE.enterGuiPass(leftX, 0, eyeW, eyeH);
        final boolean result = bus.post(event);
        StereoState.INSTANCE.exitGuiPass();

        // Re-arm ChromaticTooltips' deferred-render flag so the RIGHT eye Post draws too.
        ChromaticTooltipsCompat.rearm();

        // RIGHT eye: Forge phase-tracking refuses a re-post of the same event instance, so build
        // a fresh DrawScreenEvent.Post with the same params.
        GL11.glViewport(rightX, 0, eyeW, eyeH);
        StereoState.INSTANCE.enterGuiPass(rightX, 0, eyeW, eyeH);
        if (event instanceof GuiScreenEvent.DrawScreenEvent.Post) {
            final GuiScreenEvent.DrawScreenEvent.Post original = (GuiScreenEvent.DrawScreenEvent.Post) event;
            final GuiScreenEvent.DrawScreenEvent.Post copy =
                new GuiScreenEvent.DrawScreenEvent.Post(
                    original.gui, original.mouseX, original.mouseY, original.renderPartialTicks);
            bus.post(copy);
        }
        StereoState.INSTANCE.exitGuiPass();

        if (!CursorPresentThread.isRunning()) {
            final ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
            final int scaledWidth = sr.getScaledWidth();
            final int scaledHeight = sr.getScaledHeight();
            final int cursorX = Mouse.getX() * scaledWidth / mc.displayWidth;
            final int cursorY = scaledHeight - Mouse.getY() * scaledHeight / mc.displayHeight - 1;

            // Cursor on the just-rendered RIGHT eye viewport (still bound).
            stereoscopic$drawSyntheticCursor(cursorX, cursorY);
            // Cursor on the LEFT eye viewport.
            GL11.glViewport(leftX, 0, eyeW, eyeH);
            stereoscopic$drawSyntheticCursor(cursorX, cursorY);
        }

        GL11.glViewport(0, 0, fullW, fullH);
        return result;
    }

    // When stereo is active each eye has its own set of color and depth textures in Iris's
    // RenderTargets; this rebinds the owned framebuffer attachments to the requested eye's
    // textures so subsequent Iris passes write into and sample from the right bubble. The
    // facade dispatches to MixinRenderTargets's per-eye bank when one was allocated; both the
    // facade call and the IStereoPipeline path are no-ops when Iris is disabled / no pipeline.
    private static void stereoscopic$setIrisActiveEye(int eyeIndex) {
        if (!com.gtnewhorizons.angelica.config.AngelicaConfig.enableIris) return;
        com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks.setActiveEye(eyeIndex);
        final WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();
        if (pipeline instanceof com.mitchellmarx.stereoscopic.iris.IStereoPipeline) {
            ((com.mitchellmarx.stereoscopic.iris.IStereoPipeline) pipeline).setActiveEye(eyeIndex);
        }
    }

    /** White arrow with 1px black outline; tip at (x, y). Item icons drawn earlier in the frame
     *  leave depth values that would occlude this rect, so depth-test is toggled around it. */
    private static void stereoscopic$drawSyntheticCursor(int x, int y) {
        final int B = 0xFF000000;
        final int F = 0xFFFFFFFF;
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        for (int i = 0; i < 9; i++) {
            Gui.drawRect(x - 1, y + i - 1, x + i + 2, y + i, B);
        }
        Gui.drawRect(x - 1, y + 8, x + 9, y + 9, B);
        for (int i = 0; i < 8; i++) {
            Gui.drawRect(x, y + i, x + i + 1, y + i + 1, F);
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }
}
