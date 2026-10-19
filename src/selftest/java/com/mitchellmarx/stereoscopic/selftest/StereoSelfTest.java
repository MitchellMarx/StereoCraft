package com.mitchellmarx.stereoscopic.selftest;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.cursor.StereoCursor;
import com.mitchellmarx.stereoscopic.selftest.StereoMetrics.Shot;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * NeoForge counterpart of the Fabric branches' client gametest (NeoForge has
 * no client gametest API). Dev-only source set, active only with
 * {@code -Dstereoscopic.selftest=true} (the {@code selftest} run). Drives the
 * client from ClientTickEvent: creates a superflat world, builds the same
 * scene, steps through the stereo configurations, screenshots the main render
 * target, runs {@link StereoChecks}, writes PASS/FAIL to {@link #RESULT_FILE}
 * and exits; {@code ./gradlew runSelftest} fails the build unless it reads PASS.
 */
@EventBusSubscriber(modid = Stereoscopic.MOD_ID, value = Dist.CLIENT)
public final class StereoSelfTest {

    private static final boolean ENABLED = Boolean.getBoolean("stereoscopic.selftest");
    private static final float IPD = 0.064f;
    private static final float CONV = 4f;
    private static final float PARALLEL_IPD = 0.1f;

    private record Step(String name, StereoMode mode, float ipd, float conv, boolean swap, boolean resize) {}

    private static final Step[] STEPS = {
        new Step("mono", StereoMode.OFF, IPD, CONV, false, false),
        new Step("half_conv4", StereoMode.SBS_HALF, IPD, CONV, false, false),
        new Step("half_conv4_swap", StereoMode.SBS_HALF, IPD, CONV, true, false),
        new Step("half_parallel", StereoMode.SBS_HALF, PARALLEL_IPD, 0f, false, false),
        new Step("half_parallel_swap", StereoMode.SBS_HALF, PARALLEL_IPD, 0f, true, false),
        new Step("full_conv4", StereoMode.SBS_FULL, IPD, CONV, false, false),
        new Step("half_conv4_after_resize", StereoMode.SBS_HALF, IPD, CONV, false, true),
    };

    private enum Phase { BOOT, CREATING, SCENE, SETTLE, STEP, EXITING, DONE }

    private static Phase phase = Phase.BOOT;
    private static int wait = 0;
    private static int step = 0;
    private static int totalTicks = 0;
    static final String RESULT_FILE = "stereo-selftest-result.txt";
    private static final Map<String, Shot> shots = new HashMap<>();

    private StereoSelfTest() {}

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        if (!ENABLED || phase == Phase.DONE) return;
        Minecraft mc = Minecraft.getInstance();
        if (++totalTicks > 20 * 240 && phase != Phase.EXITING) finish(mc, "timed out in phase " + phase);
        if (wait > 0) { wait--; return; }
        if (phase == Phase.EXITING) {
            phase = Phase.DONE;
            mc.stop();
            return;
        }
        try {
            switch (phase) {
                case BOOT -> {
                    mc.options.pauseOnLostFocus = false;
                    mc.options.onboardAccessibility = false;
                    // First launch shows the accessibility onboarding screen
                    // instead of the title screen; start from any menu once idle.
                    if (!(mc.screen instanceof TitleScreen) && totalTicks < 100) return;
                    if (mc.level != null || mc.getOverlay() != null) return;
                    mc.createWorldOpenFlows().createFreshLevel("stereo-selftest",
                        new LevelSettings("stereo-selftest", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT),
                        new WorldOptions(0L, false, false),
                        ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                        mc.screen);
                    phase = Phase.CREATING;
                }
                case CREATING -> {
                    if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null || mc.screen != null) return;
                    MinecraftServer server = mc.getSingleplayerServer();
                    server.execute(() -> {
                        for (String cmd : new String[] {
                            "time set noon", "weather clear", "gamerule doDaylightCycle false",
                            "fill -12 -60 2 12 -50 64 air",
                            "fill -2 -60 6 -2 -56 6 red_wool",
                            "fill 0 -60 12 0 -55 12 blue_wool",
                            "fill 5 -60 30 5 -52 30 yellow_wool",
                            "summon minecraft:sheep 2.5 -60 8.5 {NoAI:1b,Silent:1b,Color:2b,Rotation:[90f,0f]}",
                            "tp @p 0.5 -60 0.5 0 10",
                        }) {
                            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
                        }
                    });
                    mc.options.hideGui = true;
                    phase = Phase.SCENE;
                    wait = 40;
                }
                case SCENE -> {
                    if (!mc.levelRenderer.hasRenderedAllSections()) return;
                    phase = Phase.SETTLE;
                    wait = 40;
                }
                case SETTLE -> {
                    apply(mc, STEPS[0]);
                    phase = Phase.STEP;
                    wait = 10;
                }
                case STEP -> {
                    // The first stereo activation rebuilds the Iris pipeline and
                    // reloads chunks; shoot only once terrain is back.
                    if (!mc.levelRenderer.hasRenderedAllSections()) return;
                    Step s = STEPS[step];
                    shots.put(s.name(), capture(mc, s.name()));
                    if (++step >= STEPS.length) {
                        apply(mc, new Step("off", StereoMode.OFF, IPD, CONV, false, false));
                        evaluate(mc);
                        return;
                    }
                    apply(mc, STEPS[step]);
                    wait = STEPS[step].resize() ? 30 : 10;
                }
                default -> { }
            }
        } catch (Throwable t) {
            Stereoscopic.LOG.error("[stereo-test] self-test crashed", t);
            finish(mc, "exception: " + t);
        }
    }

    private static void apply(Minecraft mc, Step s) {
        StereoOptions o = StereoOptions.INSTANCE;
        o.mode = s.mode();
        o.ipd = s.ipd();
        o.convergence = s.conv();
        o.swapEyes = s.swap();
        if (s.resize()) {
            GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 1000, 560);
        }
    }

    private static Shot capture(Minecraft mc, String name) throws Exception {
        Path dir = mc.gameDirectory.toPath().resolve("screenshots");
        Files.createDirectories(dir);
        Path png = dir.resolve("selftest_" + name + ".png");
        try (NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            img.writeToFile(png);
        }
        return StereoMetrics.measure(name, png);
    }

    private static void evaluate(Minecraft mc) {
        StereoChecks c = new StereoChecks(IPD, PARALLEL_IPD);
        c.presence(shots.get("half_conv4"), shots.get("half_conv4_swap"), shots.get("half_parallel"),
            shots.get("half_parallel_swap"), shots.get("full_conv4"), shots.get("half_conv4_after_resize"));
        c.swapMirrors(shots.get("half_conv4"), shots.get("half_conv4_swap"));
        c.swapMirrors(shots.get("half_parallel"), shots.get("half_parallel_swap"));
        c.parallel(shots.get("half_parallel"));
        c.convergenceConsistent(shots.get("half_conv4"), shots.get("half_parallel"));
        c.aspect(shots.get("mono"), shots.get("half_conv4"), shots.get("full_conv4"));
        finish(mc, c.failures.isEmpty() ? null : String.join("\n  ", c.failures));
    }

    /**
     * Records the verdict in {@code run-selftest/stereo-selftest-result.txt}
     * (the gradle task fails unless it reads PASS) and shuts the client down
     * normally. The verdict lives in a file, not the exit code: process exit
     * status is unreliable here (a forced halt dies with 0xC0000409).
     */
    private static void finish(Minecraft mc, String failure) {
        if (failure == null) {
            Stereoscopic.LOG.info("[stereo-test] ALL CHECKS PASSED");
        } else {
            Stereoscopic.LOG.error("[stereo-test] Stereo self-test failed:\n  {}", failure);
        }
        try {
            Files.writeString(mc.gameDirectory.toPath().resolve(RESULT_FILE),
                failure == null ? "PASS\n" : "FAIL\n  " + failure + "\n");
        } catch (Exception ex) {
            Stereoscopic.LOG.error("[stereo-test] could not write result file", ex);
        }
        StereoOptions.INSTANCE.mode = StereoMode.OFF;
        StereoCursor.stop();
        phase = Phase.EXITING;
        wait = 20;
    }
}
