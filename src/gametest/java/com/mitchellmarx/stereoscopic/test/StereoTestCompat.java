package com.mitchellmarx.stereoscopic.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;

/** The only Minecraft/Fabric-API-version-specific calls in the harness. */
@SuppressWarnings("UnstableApiUsage")
final class StereoTestCompat {
    private StereoTestCompat() {}

    static void waitForChunksRender(TestSingleplayerContext sp) {
        sp.getClientLevel().waitForChunksRender();
    }

    /** 26.1.2: setting the option alone doesn't reload Sodium's renderer; allChanged() does. */
    static void setRenderDistance(Minecraft mc, int chunks) {
        mc.options.renderDistance().set(chunks);
        mc.levelRenderer.allChanged();
    }

    /** What Voxy's own "rendering" option does on change (Voxy 0.2.16 API). */
    static void setVoxyRendering(Minecraft mc, boolean on) {
        me.cortex.voxy.client.config.VoxyConfig.CONFIG.enableRendering = on;
        me.cortex.voxy.client.core.IGetVoxyRenderSystem rs = (me.cortex.voxy.client.core.IGetVoxyRenderSystem) mc.levelRenderer;
        boolean running = rs.voxy$getRenderSystem() != null;
        if (on && !running) rs.voxy$createRenderer();
        else if (!on && running) rs.voxy$shutdownRenderer();
    }
}
