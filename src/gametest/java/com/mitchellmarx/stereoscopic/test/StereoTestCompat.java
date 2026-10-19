package com.mitchellmarx.stereoscopic.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * Every Minecraft / Fabric API / Voxy call that differs between the Fabric
 * branches, so StereoClientGameTest and StereoMetrics stay identical across
 * them. (MC 26.2.)
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

    /** The option's change listener runs LevelExtractor.allChanged() (renderer reload). */
    static void setRenderDistance(Minecraft mc, int chunks) {
        mc.options.renderDistance().set(chunks);
    }

    /** What Voxy's own "rendering" option does on change (Voxy 0.2.19 API). */
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
