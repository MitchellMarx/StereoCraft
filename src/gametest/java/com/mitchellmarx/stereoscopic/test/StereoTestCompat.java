package com.mitchellmarx.stereoscopic.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;

/**
 * Every Minecraft / Fabric API / Voxy call that differs between the Fabric
 * branches, so StereoClientGameTest and StereoMetrics stay identical across
 * them. (MC 1.21.11, Yarn names, Fabric API 0.141.)
 */
@SuppressWarnings("UnstableApiUsage")
final class StereoTestCompat {
    private StereoTestCompat() {}

    static void waitForChunksRender(TestSingleplayerContext sp) {
        sp.getClientWorld().waitForChunksRender();
    }

    static void hideHud(ClientGameTestContext context) {
        context.runOnClient(mc -> mc.options.hudHidden = true);
    }

    static Path gameDir(MinecraftClient mc) {
        return mc.runDirectory.toPath();
    }

    static void resizeGui(MinecraftClient mc) {
        mc.onResolutionChanged();
    }

    static int renderDistance(MinecraftClient mc) {
        return mc.options.getViewDistance().getValue();
    }

    /** Like the video settings screen: Sodium only re-reads it on a renderer reload. */
    static void setRenderDistance(MinecraftClient mc, int chunks) {
        mc.options.getViewDistance().setValue(chunks);
        mc.worldRenderer.reload();
    }

    /** What Voxy's own "rendering" option does on change (Voxy 0.2.16 API). */
    static void setVoxyRendering(MinecraftClient mc, boolean on) {
        me.cortex.voxy.client.config.VoxyConfig.CONFIG.enableRendering = on;
        me.cortex.voxy.client.core.IGetVoxyRenderSystem rs = (me.cortex.voxy.client.core.IGetVoxyRenderSystem) mc.worldRenderer;
        boolean running = rs.voxy$getRenderSystem() != null;
        if (on && !running) rs.voxy$createRenderer();
        else if (!on && running) rs.voxy$shutdownRenderer();
    }

    /** Static cyan-wool block_marker particle (80-tick lifetime): block_state as a {Name:...} map. */
    static String particleCommand(String pos) {
        return "particle minecraft:block_marker{block_state:{Name:\"minecraft:cyan_wool\"}} " + pos + " 0 0 0 0 1 force";
    }
}
