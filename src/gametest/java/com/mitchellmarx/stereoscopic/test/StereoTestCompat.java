package com.mitchellmarx.stereoscopic.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * Every Minecraft / Fabric API / Voxy call that differs between the Fabric
 * branches, so StereoClientGameTest and StereoMetrics stay identical across
 * them. (MC 26.1.2.)
 */
@SuppressWarnings("UnstableApiUsage")
final class StereoTestCompat {
    private StereoTestCompat() {}

    static void waitForChunksRender(TestSingleplayerContext sp) {
        sp.getClientLevel().waitForChunksRender();
    }

    static void hideHud(ClientGameTestContext context) {
        context.getInput().pressKey(o -> o.keyToggleGui); // F1
    }

    static Path gameDir(Minecraft mc) {
        return mc.gameDirectory.toPath();
    }

    static void resizeGui(Minecraft mc) {
        mc.resizeGui();
    }

    static int renderDistance(Minecraft mc) {
        return mc.options.renderDistance().get();
    }

    private static int screenshotCounter;

    /**
     * Grabs the last finished frame from the main render target. Not
     * ClientGameTestContext.takeScreenshot: that renders an extra frame
     * within the tick, which on 26.1.2 skips Sodium 0.9's per-frame terrain
     * setup and crashes it ("Global terrain uniforms have not been updated",
     * with or without this mod's mixins).
     */
    static Path screenshot(ClientGameTestContext context, String name) {
        context.waitTick();
        java.util.concurrent.atomic.AtomicReference<Path> done = new java.util.concurrent.atomic.AtomicReference<>();
        Path png = context.computeOnClient(mc -> {
            Path dir = mc.gameDirectory.toPath().resolve("screenshots");
            Path file = dir.resolve(String.format("%04d_%s.png", screenshotCounter++, name));
            net.minecraft.client.Screenshot.takeScreenshot(mc.getMainRenderTarget(), img -> {
                try (img) {
                    java.nio.file.Files.createDirectories(dir);
                    img.writeToFile(file);
                    done.set(file);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            return file;
        });
        context.waitFor(mc -> done.get() != null);
        return png;
    }

    /** Setting the option alone doesn't reload Sodium's renderer; allChanged() does. */
    static void setRenderDistance(Minecraft mc, int chunks) {
        mc.options.renderDistance().set(chunks);
        mc.levelRenderer.allChanged();
    }

    /** What Voxy's own "rendering" option does on change (Voxy 0.2.18 API). */
    static void setVoxyRendering(Minecraft mc, boolean on) {
        me.cortex.voxy.client.config.VoxyConfig.CONFIG.enableRendering = on;
        me.cortex.voxy.client.core.IVoxyRenderSystemHolder rs = (me.cortex.voxy.client.core.IVoxyRenderSystemHolder) mc.levelRenderer;
        boolean running = rs.voxy$getRenderSystem() != null;
        if (on && !running) rs.voxy$createRenderer();
        else if (!on && running) rs.voxy$shutdownRenderer();
    }

    /** Static cyan-wool block_marker particle (80-tick lifetime): block_state as a {Name:...} map. */
    static String particleCommand(String pos) {
        return "particle minecraft:block_marker{block_state:{Name:\"minecraft:cyan_wool\"}} " + pos + " 0 0 0 0 1 force";
    }
}
