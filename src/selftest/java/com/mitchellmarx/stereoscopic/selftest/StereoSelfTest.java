package com.mitchellmarx.stereoscopic.selftest;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.cursor.StereoCursor;
import com.mitchellmarx.stereoscopic.selftest.StereoMetrics.Marker;
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
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NeoForge counterpart of the Fabric branches' client gametest (NeoForge has
 * no client gametest API) — same scene, markers and checks. Dev-only source
 * set, active only with {@code -Dstereoscopic.selftest=true} (the
 * {@code selftest} run). Drives the client from ClientTickEvent through a
 * script of {@link Action}s: superflat world, vanilla scene in every stereo
 * mode, the shader pack (TAA on/off), and a far scene drawn only by each
 * loaded LOD mod (Distant Horizons, Voxy). Writes PASS/FAIL to
 * {@link #RESULT_FILE}; {@code ./gradlew runSelftest} fails unless it reads PASS.
 */
@EventBusSubscriber(modid = Stereoscopic.MOD_ID, value = Dist.CLIENT)
public final class StereoSelfTest {

    private static final boolean ENABLED = Boolean.getBoolean("stereoscopic.selftest");
    private static final float IPD = 0.064f;
    private static final float CONV = 4f;
    private static final float PARALLEL_IPD = 0.1f;
    private static final float ZERO_IPD = 0.0005f;
    /** -Dstereoscopic.test.shaderpack: Iris shader passes (skipped if absent). */
    private static final String SHADER_PACK = System.getProperty("stereoscopic.test.shaderpack");
    private static final boolean SHADERS = SHADER_PACK != null && Files.isRegularFile(Path.of(SHADER_PACK));

    private static final EnumSet<Marker> TERRAIN = EnumSet.of(Marker.RED, Marker.BLUE, Marker.YELLOW);

    /** Forward distance (blocks) from the eye to each marker's visible face. */
    private static final Map<Marker, Double> NEAR_DEPTH = Map.of(
        Marker.RED, 5.5, Marker.BLUE, 11.5, Marker.YELLOW, 29.5, Marker.SHEEP, 7.6, Marker.PARTICLE, 19.5);
    private static final String NEAR_PARTICLE = "-3 -57.5 20";

    /**
     * LOD scene (behind the start position, viewed facing -Z): pillars 70-110
     * blocks away, beyond the LOD pass's vanilla render distance, so only the
     * LOD mod draws them; a second sheep and the particle stay vanilla.
     */
    private static final Map<Marker, Double> LOD_DEPTH = Map.of(
        Marker.RED, 69.5, Marker.BLUE, 89.5, Marker.YELLOW, 109.5, Marker.SHEEP, 20.55, Marker.PARTICLE, 17.0);
    private static final String LOD_PARTICLE = "4 -57 -16.5";
    private static final float LOD_PARALLEL_IPD = 1.5f;
    private static final int LOD_RENDER_DISTANCE = 3;

    /** One script step; returns false to be retried next tick (waiting on a condition). */
    @FunctionalInterface
    private interface Action {
        boolean run(Minecraft mc) throws Exception;
    }

    static final String RESULT_FILE = "stereo-selftest-result.txt";
    private static final Deque<Action> script = new ArrayDeque<>();
    private static final Map<String, Shot> shots = new HashMap<>();
    private static final StereoChecks checks = new StereoChecks(IPD, PARALLEL_IPD);
    private static boolean started, exiting, done;
    private static int wait, totalTicks;
    private static String particleAt = NEAR_PARTICLE;
    private static int nearRenderDistance;

    private StereoSelfTest() {}

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post e) {
        if (!ENABLED || done) return;
        Minecraft mc = Minecraft.getInstance();
        if (++totalTicks > 20 * 900 && !exiting) finish(mc, "timed out at " + script.size() + " remaining steps");
        if (wait > 0) { wait--; return; }
        if (exiting) {
            done = true;
            if (ModList.get().isLoaded("distanthorizons")) {
                // Leaving the world with DH loaded can hang the shutdown; the
                // verdict is already in the result file.
                Runtime.getRuntime().halt(0);
            }
            mc.stop();
            return;
        }
        if (!started) {
            started = true;
            buildScript();
        }
        try {
            Action a = script.peek();
            if (a != null && a.run(mc)) script.poll();
        } catch (Throwable t) {
            Stereoscopic.LOG.error("[stereo-test] self-test crashed", t);
            finish(mc, "exception: " + t);
        }
    }

    // --- script --------------------------------------------------------------

    private static void buildScript() {
        then(StereoSelfTest::createWorld);
        then(mc -> mc.level != null && mc.player != null && mc.getSingleplayerServer() != null && mc.screen == null);
        cmd("time set noon", "weather clear", "gamerule doDaylightCycle false",
            "fill -12 -60 2 12 -50 64 air",
            "fill -2 -60 6 -2 -56 6 red_wool",
            "fill 0 -60 12 0 -55 12 blue_wool",
            "fill 5 -60 30 5 -52 30 yellow_wool",
            "summon minecraft:sheep 2.5 -60 8.5 {NoAI:1b,Silent:1b,Color:2b,Rotation:[90f,0f]}",
            // LOD scene, behind the camera until the LOD pass turns around. Its
            // chunks (to z -112) are past the spawn area: forceload them.
            "forceload add -12 -112 13 -1");
        pause(40);
        cmd("fill -12 -60 -1 13 -50 -112 air",
            "fill -12 -60 -70 -7 -51 -70 red_wool",
            "fill -2 -60 -90 3 -51 -90 blue_wool",
            "fill 8 -60 -110 13 -51 -110 yellow_wool",
            "summon minecraft:sheep -4.5 -60 -20.5 {NoAI:1b,Silent:1b,Color:2b,Rotation:[90f,0f]}",
            "tp @p 0.5 -60 0.5 0 10");
        then(mc -> { mc.options.hideGui = true; nearRenderDistance = mc.options.renderDistance().get(); return true; });
        pause(40);
        rendered();
        pause(40);

        // Vanilla scene in every mode.
        shot("mono", StereoMode.OFF, IPD, CONV, false);
        shot("half_conv4", StereoMode.SBS_HALF, IPD, CONV, false);
        shot("half_conv4_swap", StereoMode.SBS_HALF, IPD, CONV, true);
        shot("half_parallel", StereoMode.SBS_HALF, PARALLEL_IPD, 0f, false);
        shot("half_parallel_swap", StereoMode.SBS_HALF, PARALLEL_IPD, 0f, true);
        shot("full_conv4", StereoMode.SBS_FULL, IPD, CONV, false);
        shot("half_ipd0", StereoMode.SBS_HALF, ZERO_IPD, 0f, false);
        then(mc -> { GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), 1000, 560); return true; });
        pause(30);
        shot("half_conv4_after_resize", StereoMode.SBS_HALF, IPD, CONV, false);
        then(mc -> {
            checks.presence(get("half_conv4"), get("half_conv4_swap"), get("half_parallel"),
                get("half_parallel_swap"), get("full_conv4"), get("half_conv4_after_resize"));
            checks.swapMirrors(get("half_conv4"), get("half_conv4_swap"));
            checks.swapMirrors(get("half_parallel"), get("half_parallel_swap"));
            checks.parallel(get("half_parallel"));
            checks.depthScaling(get("half_parallel"), NEAR_DEPTH);
            checks.convergenceConsistent(get("half_conv4"), get("half_parallel"));
            checks.aspect(get("mono"), get("half_conv4"), get("full_conv4"));
            checks.eyesIdentical(get("half_ipd0"));
            return true;
        });

        if (SHADERS) {
            shaderPass("shaders", "TAA_DEFINE=1\n", NEAR_DEPTH, PARALLEL_IPD, false);
            shaderPass("shaders_noTAA", "TAA_DEFINE=0\n", NEAR_DEPTH, PARALLEL_IPD, false);
        } else {
            then(mc -> { StereoChecks.log("shaders: SKIPPED (no -Dstereoscopic.test.shaderpack)"); return true; });
        }

        for (String mod : List.of("distanthorizons", "voxy")) {
            if (ModList.get().isLoaded(mod)) lodPass(mod);
            else then(mc -> { StereoChecks.log("%s: SKIPPED (not loaded)", mod); return true; });
        }

        then(mc -> { setMode(mc, StereoMode.OFF); finish(mc, null); return true; });
    }

    /**
     * Far scene drawn only by {@code mod}: load its chunks (LOD ingest), view
     * it from the start position with vanilla render distance too short to
     * reach the pillars, prove with a control shot ({@code mod} rendering off)
     * that the pillars are the LOD mod's, then the stereo checks against the
     * vanilla sheep and particle; repeated with the shader pack.
     */
    private static void lodPass(String mod) {
        String tag = mod.equals("distanthorizons") ? "dh" : mod;
        then(mc -> { for (String m : List.of("distanthorizons", "voxy")) lodRendering(mc, m, m.equals(mod)); return true; });
        cmd("tp @p 0.5 -60 -80 180 0");
        pause(20);
        rendered();
        pause(100);
        cmd("tp @p 0.5 -60 0.5 180 0");
        then(mc -> { setRenderDistance(mc, LOD_RENDER_DISTANCE); particleAt = LOD_PARTICLE; return true; });
        pause(20);
        rendered();
        pause(100);
        then(mc -> { lodRendering(mc, mod, false); return true; });
        pause(20);
        shot(tag + "_control_rendering_off", StereoMode.OFF, IPD, CONV, false);
        then(mc -> { checks.lodControl(get(tag + "_control_rendering_off"), tag); lodRendering(mc, mod, true); return true; });
        pause(100);
        shot(tag + "_half_conv4", StereoMode.SBS_HALF, IPD, CONV, false);
        shot(tag + "_half_conv4_swap", StereoMode.SBS_HALF, IPD, CONV, true);
        shot(tag + "_half_parallel", StereoMode.SBS_HALF, LOD_PARALLEL_IPD, 0f, false);
        shot(tag + "_full_conv4", StereoMode.SBS_FULL, IPD, CONV, false);
        shot(tag + "_half_ipd0", StereoMode.SBS_HALF, ZERO_IPD, 0f, false);
        then(mc -> {
            checks.presence(get(tag + "_half_conv4"), get(tag + "_half_conv4_swap"), get(tag + "_half_parallel"), get(tag + "_full_conv4"));
            checks.swapMirrors(get(tag + "_half_conv4"), get(tag + "_half_conv4_swap"));
            checks.parallel(get(tag + "_half_parallel"));
            checks.depthScaling(get(tag + "_half_parallel"), LOD_DEPTH);
            checks.convergenceConsistent(get(tag + "_half_conv4"), get(tag + "_half_parallel"), LOD_PARALLEL_IPD);
            checks.eyesIdentical(get(tag + "_half_ipd0"));
            return true;
        });
        if (SHADERS) {
            shaderPass(tag + "_shaders", "TAA_DEFINE=1\n", LOD_DEPTH, LOD_PARALLEL_IPD, true);
            shaderPass(tag + "_shaders_noTAA", "TAA_DEFINE=0\n", LOD_DEPTH, LOD_PARALLEL_IPD, true);
        }
        then(mc -> {
            for (String m : List.of("distanthorizons", "voxy")) lodRendering(mc, m, true);
            setRenderDistance(mc, nearRenderDistance);
            particleAt = NEAR_PARTICLE;
            return true;
        });
        cmd("tp @p 0.5 -60 0.5 0 10");
        pause(20);
        rendered();
    }

    /**
     * The checks with the shader pack. In the LOD scene the LOD terrain
     * streams back in after the pipeline rebuild: wait (mono) until it is
     * drawn, failing if it never is.
     */
    private static void shaderPass(String tag, String options, Map<Marker, Double> depth, float parallelIpd, boolean lod) {
        then(mc -> { setShaders(mc, true, options); return true; });
        pause(120);
        rendered();
        if (lod) {
            final int[] attempts = {0};
            shot(tag + "_mono", StereoMode.OFF, IPD, CONV, false);
            then(mc -> {
                Shot mono = capture(mc, tag + "_mono");
                boolean drawn = TERRAIN.stream().allMatch(m -> mono.whole().get(m) != null);
                if (drawn) return true;
                if (++attempts[0] >= 6) {
                    checks.fail(tag + ": LOD terrain not drawn with the shader pack, even in mono");
                    return true;
                }
                wait = 40; // retried after the pause
                return false;
            });
        }
        shot(tag + "_half_conv4", StereoMode.SBS_HALF, IPD, CONV, false);
        shot(tag + "_half_parallel", StereoMode.SBS_HALF, parallelIpd, 0f, false);
        shot(tag + "_half_ipd0", StereoMode.SBS_HALF, ZERO_IPD, 0f, false);
        then(mc -> {
            setMode(mc, StereoMode.OFF);
            setShaders(mc, false, null);
            checks.presence(get(tag + "_half_conv4"), get(tag + "_half_parallel"));
            checks.parallel(get(tag + "_half_parallel"));
            checks.depthScaling(get(tag + "_half_parallel"), depth);
            checks.convergenceConsistent(get(tag + "_half_conv4"), get(tag + "_half_parallel"), parallelIpd);
            checks.eyesIdentical(get(tag + "_half_ipd0"));
            return true;
        });
        pause(40);
        rendered();
    }

    // --- script building blocks ------------------------------------------------

    private static void then(Action a) { script.add(a); }

    private static void pause(int ticks) { then(mc -> { wait = ticks; return true; }); }

    private static void rendered() { then(mc -> mc.levelRenderer.hasRenderedAllSections()); }

    private static void cmd(String... commands) {
        then(mc -> {
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> {
                for (String c : commands) server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
            });
            wait = 2;
            return true;
        });
    }

    /**
     * Apply options (a mode change the way the options page does it, then
     * wait for the rebuilt pipeline and terrain), re-spawn the particle marker
     * (cyan-wool block_marker, 80-tick lifetime) and screenshot.
     */
    private static void shot(String name, StereoMode mode, float ipd, float conv, boolean swap) {
        then(mc -> {
            StereoOptions o = StereoOptions.INSTANCE;
            o.ipd = ipd;
            o.convergence = conv;
            o.swapEyes = swap;
            if (o.mode != mode) {
                setMode(mc, mode);
                wait = 60;
            }
            return true;
        });
        rendered();
        then(mc -> {
            MinecraftServer server = mc.getSingleplayerServer();
            String c = "particle minecraft:block_marker{block_state:{Name:\"minecraft:cyan_wool\"}} " + particleAt + " 0 0 0 0 1 force";
            server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c));
            wait = 10;
            return true;
        });
        then(mc -> { shots.put(name, capture(mc, name)); return true; });
    }

    /** StereoOptionStorage.applyMode + save(): assign, Iris pipeline rebuild, re-layout. */
    private static void setMode(Minecraft mc, StereoMode mode) {
        if (StereoOptions.INSTANCE.mode == mode) return;
        StereoOptions.INSTANCE.mode = mode;
        PerEyeRenderTargetHooks.rebuildPipelineForStereoToggle();
        mc.resizeDisplay();
    }

    /** Like the video settings screen: Sodium only re-reads it on a renderer reload. */
    private static void setRenderDistance(Minecraft mc, int chunks) {
        mc.options.renderDistance().set(chunks);
        mc.levelRenderer.allChanged();
    }

    private static void lodRendering(Minecraft mc, String mod, boolean on) {
        if (!ModList.get().isLoaded(mod)) return;
        switch (mod) {
            case "distanthorizons" -> com.seibel.distanthorizons.api.DhApi.Delayed.configs.graphics().renderingEnabled().setValue(on);
            case "voxy" -> {
                // What Voxy's own "rendering" option does on change.
                me.cortex.voxy.client.config.VoxyConfig.CONFIG.enableRendering = on;
                me.cortex.voxy.client.core.IGetVoxyRenderSystem rs = (me.cortex.voxy.client.core.IGetVoxyRenderSystem) mc.levelRenderer;
                boolean running = rs.voxy$getRenderSystem() != null;
                if (on && !running) rs.voxy$createRenderer();
                else if (!on && running) rs.voxy$shutdownRenderer();
            }
            default -> throw new IllegalArgumentException(mod);
        }
    }

    private static boolean createWorld(Minecraft mc) {
        mc.options.pauseOnLostFocus = false;
        mc.options.onboardAccessibility = false;
        // First launch shows the accessibility onboarding screen instead of
        // the title screen; start from any menu once idle.
        if (!(mc.screen instanceof TitleScreen) && totalTicks < 100) return false;
        if (mc.level != null || mc.getOverlay() != null) return false;
        mc.createWorldOpenFlows().createFreshLevel("stereo-selftest",
            new LevelSettings("stereo-selftest", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT),
            new WorldOptions(0L, false, false),
            ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
            mc.screen);
        return true;
    }

    private static void setShaders(Minecraft mc, boolean on, String options) {
        try {
            net.irisshaders.iris.config.IrisConfig cfg = net.irisshaders.iris.Iris.getIrisConfig();
            if (on) {
                Path src = Path.of(SHADER_PACK);
                Path dir = mc.gameDirectory.toPath().resolve("shaderpacks");
                Files.createDirectories(dir);
                Files.copy(src, dir.resolve(src.getFileName().toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(dir.resolve(src.getFileName() + ".txt"), options); // Iris per-pack options
                cfg.setShaderPackName(src.getFileName().toString());
            }
            cfg.setShadersEnabled(on);
            cfg.save();
            net.irisshaders.iris.Iris.reload();
            StereoChecks.log("shaders %s: %s [%s]", on ? "ON" : "OFF", SHADER_PACK, options == null ? "" : options.trim());
        } catch (Exception e) {
            throw new RuntimeException("could not toggle shaders", e);
        }
    }

    private static Shot get(String name) {
        Shot s = shots.get(name);
        if (s == null) throw new IllegalStateException("no screenshot " + name);
        return s;
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

    /**
     * Records the verdict in {@code run-selftest/stereo-selftest-result.txt}
     * (the gradle task fails unless it reads PASS) and shuts the client down.
     * The verdict lives in a file, not the exit code: process exit status is
     * unreliable here (a forced halt dies with 0xC0000409).
     */
    private static void finish(Minecraft mc, String failure) {
        if (failure == null && !checks.failures.isEmpty()) failure = String.join("\n  ", checks.failures);
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
        script.clear();
        exiting = true;
        wait = 20;
    }
}
