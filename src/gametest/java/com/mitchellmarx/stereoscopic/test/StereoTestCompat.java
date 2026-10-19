package com.mitchellmarx.stereoscopic.test;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * Every Minecraft / Fabric API / Voxy call that differs between the Fabric
 * branches, so StereoClientGameTest and StereoMetrics stay identical across
 * them. (MC 26.3, Fabric API 0.163.)
 */
@SuppressWarnings("UnstableApiUsage")
final class StereoTestCompat {
    private StereoTestCompat() {}

    static void waitForChunksRender(TestSingleplayerContext sp) {
        sp.getConnection().waitForChunksRender();
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

    /** No Voxy build exists for 26.3; the LOD pass for it never runs. */
    static void setVoxyRendering(Minecraft mc, boolean on) {
        throw new UnsupportedOperationException("no Voxy for MC 26.3");
    }

    /** Static cyan-wool block_marker particle (80-tick lifetime): block_state as a string (26.3 rejects the {Name:...} map). */
    static String particleCommand(String pos) {
        return "particle minecraft:block_marker{block_state:\"minecraft:cyan_wool\"} " + pos + " 0 0 0 0 1 force";
    }

    static Path screenshot(ClientGameTestContext context, String name) {
        return context.takeScreenshot(name);
    }
}