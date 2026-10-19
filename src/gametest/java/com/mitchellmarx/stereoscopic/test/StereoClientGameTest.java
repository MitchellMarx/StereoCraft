package com.mitchellmarx.stereoscopic.test;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.test.StereoMetrics.Marker;
import com.mitchellmarx.stereoscopic.test.StereoMetrics.Shot;
import com.seibel.distanthorizons.api.DhApi;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * Automated stereo correctness test. Builds a fixed scene (wool pillars =
 * Sodium terrain at three depths, a NoAI sheep = entity, a block_marker
 * particle), screenshots it under each stereo configuration, measures per-eye
 * marker positions from the screenshot pixels ({@link StereoMetrics}) and
 * fails on:
 * <ul>
 *   <li>any marker missing from either eye (dropped entities / terrain);</li>
 *   <li>swapEyes not mirroring every disparity;</li>
 *   <li>parallel-camera disparity with the wrong sign or not shrinking with depth;</li>
 *   <li>a renderer applying a different eye separation (disparity x depth);</li>
 *   <li>terrain, entities and particles disagreeing on the convergence shift (issue #5);</li>
 *   <li>SBS_FULL / SBS_HALF per-eye aspect wrong;</li>
 *   <li>eyes differing at zero IPD (one-eye artifacts);</li>
 *   <li>markers lost after a window resize mid-stereo.</li>
 * </ul>
 * The same checks run with the shader pack (TAA on/off) and on a far scene
 * drawn only by Distant Horizons / Voxy ({@link #lodPass}).
 * Run: {@code ./gradlew stereoTest}. Screenshots stay in
 * {@code build/run/clientGameTest/screenshots} for inspection.
 */
@SuppressWarnings("UnstableApiUsage")
public class StereoClientGameTest implements FabricClientGameTest {

    private static final float IPD = 0.064f;
    private static final float CONV = 4f;
    private static final float PARALLEL_IPD = 0.1f;

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

    private final List<String> failures = new ArrayList<>();
    private TestServerContext server;
    private TestSingleplayerContext sp;
    // Active scene: switched by lodPass.
    private Map<Marker, Double> depth = NEAR_DEPTH;
    private String particleAt = NEAR_PARTICLE;
    private float parallelIpd = PARALLEL_IPD;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            this.sp = sp;
            server = sp.getServer();
            // Superflat: surface at y=-61, player feet at y=-60. Camera faces +Z.
            // Markers stay within the narrower SBS_FULL per-eye FOV.
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runCommand("fill -12 -60 2 12 -50 64 air");
            server.runCommand("fill -2 -60 6 -2 -56 6 red_wool");     // near
            server.runCommand("fill 0 -60 12 0 -55 12 blue_wool");    // mid
            server.runCommand("fill 5 -60 30 5 -52 30 yellow_wool");  // far
            server.runCommand("summon minecraft:sheep 2.5 -60 8.5 {NoAI:1b,Silent:1b,Color:2b,Rotation:[90f,0f]}");
            // LOD scene, behind the camera until lodPass turns around. Its
            // chunks (to z -112) are past the spawn area: forceload them.
            server.runCommand("forceload add -12 -112 13 -1");
            context.waitTicks(40);
            server.runCommand("fill -12 -60 -1 13 -50 -112 air");
            server.runCommand("fill -12 -60 -70 -7 -51 -70 red_wool");
            server.runCommand("fill -2 -60 -90 3 -51 -90 blue_wool");
            server.runCommand("fill 8 -60 -110 13 -51 -110 yellow_wool");
            server.runCommand("summon minecraft:sheep -4.5 -60 -20.5 {NoAI:1b,Silent:1b,Color:2b,Rotation:[90f,0f]}");
            server.runCommand("tp @p 0.5 -60 0.5 0 10");
            StereoTestCompat.waitForChunksRender(sp);
            StereoTestCompat.hideHud(context);
            context.waitTicks(20);

            // -Dstereoscopic.test.lodOnly: only the LOD passes (the second
            // client of the stereoTest task, run with the other LOD mod).
            boolean lodOnly = Boolean.getBoolean("stereoscopic.test.lodOnly");
            if (!lodOnly) nearPass(context);
            context.getInput().resizeWindow(1000, 560);
            context.waitTicks(20);

            if (FabricLoader.getInstance().isModLoaded("distanthorizons")) lodPass(context, sp, "dh");
            else log("dh: SKIPPED (Distant Horizons not loaded)");
            if (FabricLoader.getInstance().isModLoaded("voxy")) lodPass(context, sp, "voxy");
            else log("voxy: SKIPPED (Voxy not loaded)");

            // Diagnostic: -Dstereoscopic.test.bisect="tag=OPT=V,OPT=V;tag2=..." runs
            // only these shader-option variants (to localise a failing feature).
            String bisect = System.getProperty("stereoscopic.test.bisect");
            if (bisect != null && !bisect.isBlank()) {
                for (String variant : bisect.split(";")) {
                    String[] kv = variant.split("=", 2);
                    shaderPass(context, "bisect_" + kv[0], kv.length > 1 ? kv[1].replace(',', '\n') + "\n" : "");
                }
            } else if (!lodOnly) {
                shaderPass(context, "shaders", "");
                shaderPass(context, "shaders_noTAA", "TAA_DEFINE=0\n");
            }

            // After rendering with shaders, every target class is loaded.
            Path gameDir = context.computeOnClient(mc -> StereoTestCompat.gameDir(mc));
            List<String> configs = new ArrayList<>(List.of("stereoscopic.mixins.json", "stereoscopic-iris.mixins.json"));
            if (FabricLoader.getInstance().isModLoaded("voxy")) configs.add("stereoscopic-voxy.mixins.json");
            if (FabricLoader.getInstance().isModLoaded("distanthorizons")) configs.add("stereoscopic-dh.mixins.json");
            configs.removeIf(c -> StereoClientGameTest.class.getClassLoader().getResource(c) == null); // not on this branch
            for (String f : StereoMixinAudit.audit(gameDir, configs.toArray(String[]::new))) fail("mixin: " + f);
            log("mixin audit: %d injectors/accessors verified", StereoMixinAudit.verified);
            if (StereoMixinAudit.verified < 20) fail("mixin audit verified only " + StereoMixinAudit.verified + " injectors — audit broken?");

            // The verdict goes to RESULT_FILE (the gradle task fails unless it
            // reads PASS), not the exit status: with DH loaded, leaving the
            // world deadlocks the gametest harness (render thread in
            // IntegratedServer.halt waiting on the server thread, which waits
            // in the gametest tick phaser), so the JVM is halted here, and a
            // forced halt exits with 0xC0000409 on Windows.
            Stereoscopic.LOG.info("[stereo-test] {}", verdict());
            try {
                Files.writeString(gameDir.resolve(RESULT_FILE), failures.isEmpty() ? "PASS\n" : "FAIL\n  " + verdict() + "\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (FabricLoader.getInstance().isModLoaded("distanthorizons")) Runtime.getRuntime().halt(0);
        }
        if (!failures.isEmpty()) throw new AssertionError(verdict());
    }

    static final String RESULT_FILE = "stereo-test-result.txt";

    /** Vanilla scene (Sodium terrain, entity, particle) in every mode, plus a mid-stereo resize. */
    private void nearPass(ClientGameTestContext context) {
        Shot mono = shot(context, "mono", StereoMode.OFF, IPD, CONV, false);
        Shot half = shot(context, "half_conv4", StereoMode.SBS_HALF, IPD, CONV, false);
        Shot halfSwap = shot(context, "half_conv4_swap", StereoMode.SBS_HALF, IPD, CONV, true);
        Shot par = shot(context, "half_parallel", StereoMode.SBS_HALF, PARALLEL_IPD, 0f, false);
        Shot parSwap = shot(context, "half_parallel_swap", StereoMode.SBS_HALF, PARALLEL_IPD, 0f, true);
        Shot full = shot(context, "full_conv4", StereoMode.SBS_FULL, IPD, CONV, false);
        checkEyesIdentical(context, "half_ipd0");

        set(context, StereoMode.SBS_HALF, IPD, CONV, false);
        context.getInput().resizeWindow(1000, 560);
        context.waitTicks(20);
        Shot resized = capture(context, "half_conv4_after_resize");

        set(context, StereoMode.OFF, IPD, CONV, false);
        context.waitTicks(5);

        checkPresence(half, halfSwap, par, parSwap, full, resized);
        checkSwapMirrors(half, halfSwap);
        checkSwapMirrors(par, parSwap);
        checkParallel(par);
        checkDepthScaling(par);
        checkConvergenceConsistent(half, par);
        checkAspect(mono, half, full);
    }

    private String verdict() {
        return failures.isEmpty() ? "ALL CHECKS PASSED" : "Stereo test failed:\n  " + String.join("\n  ", failures);
    }

    /**
     * Same stereo checks with an Iris shader pack active (entities/terrain go
     * through Iris gbuffer programs and the per-eye Iris uniform hooks).
     * Pack path from -Dstereoscopic.test.shaderpack; logged as SKIPPED if absent.
     */
    private void shaderPass(ClientGameTestContext context, String tag, String packOptions) {
        String packPath = System.getProperty("stereoscopic.test.shaderpack");
        if (packPath == null || !Files.isRegularFile(Path.of(packPath))) {
            log("shaders: SKIPPED (no -Dstereoscopic.test.shaderpack)");
            return;
        }
        Path src = Path.of(packPath);
        String name = src.getFileName().toString();
        context.runOnClient(mc -> {
            try {
                Path dir = StereoTestCompat.gameDir(mc).resolve("shaderpacks");
                Files.createDirectories(dir);
                Files.copy(src, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(dir.resolve(name + ".txt"), packOptions); // Iris per-pack options
                IrisConfig cfg = Iris.getIrisConfig();
                cfg.setShaderPackName(name);
                cfg.setShadersEnabled(true);
                cfg.save();
                Iris.reload();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        context.waitTicks(100); // pack compile + pipeline build
        log("%s: %s [%s] active=%s", tag, name, packOptions.trim(), context.computeOnClient(mc -> Iris.getCurrentPack().isPresent()));
        if (depth == LOD_DEPTH) {
            // LOD terrain streams back in after the pipeline rebuild; wait
            // (mono) until it is drawn, and fail if it never is.
            List<Marker> absent = List.copyOf(TERRAIN);
            for (int attempt = 0; attempt < 6 && !absent.isEmpty(); attempt++) {
                if (attempt > 0) context.waitTicks(40);
                Shot mono = shot(context, tag + "_mono", StereoMode.OFF, IPD, CONV, false);
                absent = TERRAIN.stream().filter(m -> mono.whole().get(m) == null).toList();
            }
            boolean known = knownBroken(tag);
            if (!absent.isEmpty() && known) {
                // Listed in gradle.properties (test_known_broken) with the
                // baseline evidence: not drawn even with stereo never enabled.
                log("%s: KNOWN-BROKEN upstream — LOD terrain %s not drawn with the shader pack, even in mono; stereo not checkable", tag, absent);
                disableShaders(context);
                return;
            }
            if (!absent.isEmpty()) fail(tag + ": LOD terrain " + absent + " not drawn with the shader pack, even in mono");
            if (known) log("%s: listed as known-broken but now renders — remove it from test_known_broken", tag);
        }
        Shot half = shot(context, tag + "_half_conv4", StereoMode.SBS_HALF, IPD, CONV, false);
        Shot par = shot(context, tag + "_half_parallel", StereoMode.SBS_HALF, parallelIpd, 0f, false);
        checkEyesIdentical(context, tag + "_half_ipd0");
        if (Boolean.getBoolean("stereoscopic.test.probeTaa")) probeTaaHistory(context, tag);
        set(context, StereoMode.OFF, IPD, CONV, false);
        disableShaders(context);
        checkPresence(half, par);
        checkParallel(par);
        checkDepthScaling(par);
        checkConvergenceConsistent(half, par);
    }

    /**
     * LOD renderer pass (Distant Horizons / Voxy; issue #5 candidate). Turns
     * to the LOD scene and drops vanilla render distance so its pillars are
     * drawn only by {@code mod} (any other LOD mod's rendering off), while the
     * sheep and particle stay vanilla — the depth and convergence checks then
     * compare the LOD renderer's camera/projection path directly against
     * entities and particles. A control shot with {@code mod}'s rendering off
     * proves the pillars really come from it. Repeated with the shader pack.
     */
    private void lodPass(ClientGameTestContext context, TestSingleplayerContext sp, String mod) {
        for (String other : List.of("dh", "voxy")) lodRendering(context, other, other.equals(mod));
        int nearRenderDistance = context.computeOnClient(mc -> StereoTestCompat.renderDistance(mc));
        // Load the LOD scene's chunks client-side (LOD mods ingest loaded
        // chunks), then view it from the start position with vanilla render
        // distance too short to reach the pillars.
        server.runCommand("tp @p 0.5 -60 -80 180 0");
        StereoTestCompat.waitForChunksRender(sp);
        context.waitTicks(100);
        server.runCommand("tp @p 0.5 -60 0.5 180 0");
        setRenderDistance(context, LOD_RENDER_DISTANCE);
        StereoTestCompat.waitForChunksRender(sp);
        depth = LOD_DEPTH;
        particleAt = LOD_PARTICLE;
        parallelIpd = LOD_PARALLEL_IPD;
        context.waitTicks(100); // LOD build/upload
        log("%s: render distance %d -> %d", mod, nearRenderDistance, LOD_RENDER_DISTANCE);

        lodRendering(context, mod, false);
        Shot off = shot(context, mod + "_control_rendering_off", StereoMode.OFF, IPD, CONV, false);
        for (Marker m : TERRAIN) {
            if (off.whole().get(m) != null) fail(mod + " control: " + m + " visible with " + mod + " rendering off — the pass would not measure " + mod);
        }
        for (Marker m : EnumSet.complementOf(TERRAIN)) {
            if (off.whole().get(m) == null) fail(mod + " control: vanilla " + m + " missing");
        }
        lodRendering(context, mod, true);
        context.waitTicks(100); // LOD reload after re-enabling
        awaitLodSettled(context, mod);
        log("%s: LOD scene", mod);

        Shot half = shot(context, mod + "_half_conv4", StereoMode.SBS_HALF, IPD, CONV, false);
        Shot halfSwap = shot(context, mod + "_half_conv4_swap", StereoMode.SBS_HALF, IPD, CONV, true);
        Shot par = shot(context, mod + "_half_parallel", StereoMode.SBS_HALF, parallelIpd, 0f, false);
        Shot full = shot(context, mod + "_full_conv4", StereoMode.SBS_FULL, IPD, CONV, false);
        checkEyesIdentical(context, mod + "_half_ipd0");
        set(context, StereoMode.OFF, IPD, CONV, false);
        checkPresence(half, halfSwap, par, full);
        checkSwapMirrors(half, halfSwap);
        checkParallel(par);
        checkDepthScaling(par);
        checkConvergenceConsistent(half, par);

        shaderPass(context, mod + "_shaders", "");
        shaderPass(context, mod + "_shaders_noTAA", "TAA_DEFINE=0\n");

        depth = NEAR_DEPTH;
        particleAt = NEAR_PARTICLE;
        parallelIpd = PARALLEL_IPD;
        for (String other : List.of("dh", "voxy")) lodRendering(context, other, true);
        setRenderDistance(context, nearRenderDistance);
        server.runCommand("tp @p 0.5 -60 0.5 0 10");
        StereoTestCompat.waitForChunksRender(sp);
        context.waitTicks(20);
    }

    /**
     * LOD mods keep refining detail after (re)loading; a pillar whose LOD
     * changes between the shots of one check reads as a disparity error. Wait
     * (mono, up to ~16 s) until every LOD pillar's bounding box is identical
     * in two consecutive shots.
     */
    private void awaitLodSettled(ClientGameTestContext context, String mod) {
        set(context, StereoMode.OFF, IPD, CONV, false);
        String prev = null;
        for (int i = 0; i < 8; i++) {
            Shot s = capture(context, mod + "_settle");
            StringBuilder sb = new StringBuilder();
            for (Marker m : TERRAIN) {
                StereoMetrics.Blob b = s.whole().get(m);
                sb.append(b == null ? "-" : b.minX() + "," + b.maxX() + "," + b.minY() + "," + b.maxY()).append(';');
            }
            if (sb.toString().equals(prev)) return;
            prev = sb.toString();
            context.waitTicks(40);
        }
        log("%s: LOD terrain still changing after the settle wait", mod);
    }

    /** Like the video settings screen: Sodium only re-reads it on a renderer reload. */
    private static void setRenderDistance(ClientGameTestContext context, int chunks) {
        context.runOnClient(mc -> StereoTestCompat.setRenderDistance(mc, chunks));
        context.waitTicks(20);
    }

    private static void lodRendering(ClientGameTestContext context, String mod, boolean on) {
        context.runOnClient(mc -> {
            switch (mod) {
                case "dh" -> {
                    if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {
                        DhApi.Delayed.configs.graphics().renderingEnabled().setValue(on);
                    }
                }
                case "voxy" -> {
                    if (FabricLoader.getInstance().isModLoaded("voxy")) StereoTestCompat.setVoxyRendering(mc, on);
                }
                default -> throw new IllegalArgumentException(mod);
            }
        });
        context.waitTicks(20);
    }

    /** -Dstereoscopic.test.knownBroken=voxy_shaders,...: pass-tag prefixes broken upstream on this branch. */
    private static boolean knownBroken(String tag) {
        for (String k : System.getProperty("stereoscopic.test.knownBroken", "").split(",")) {
            if (!k.isBlank() && tag.startsWith(k.trim())) return true;
        }
        return false;
    }

    private static void disableShaders(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            try {
                Iris.getIrisConfig().setShadersEnabled(false);
                Iris.getIrisConfig().save();
                Iris.reload();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        context.waitTicks(20);
    }

    /** DIAGNOSTIC: compare LEFT/RIGHT bank colortex2 (Complementary TAA history). */
    private void probeTaaHistory(ClientGameTestContext context, String tag) {
        set(context, StereoMode.SBS_HALF, PARALLEL_IPD, 0f, false);
        context.waitTicks(20);
        context.runOnClient(mc -> {
            try {
                Object pipe = Iris.getPipelineManager().getPipelineNullable();
                java.lang.reflect.Field f = pipe.getClass().getDeclaredField("renderTargets");
                f.setAccessible(true);
                net.irisshaders.iris.targets.RenderTargets rt = (net.irisshaders.iris.targets.RenderTargets) f.get(pipe);
                int prev = com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks.getActiveEye();
                com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks.setActiveEye(0);
                net.irisshaders.iris.targets.RenderTarget l = rt.get(2);
                com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks.setActiveEye(1);
                net.irisshaders.iris.targets.RenderTarget r = rt.get(2);
                com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks.setActiveEye(prev);
                int w = l.getWidth(), h = l.getHeight();
                float[] lm = tex(l.getMainTexture(), w, h), la = tex(l.getAltTexture(), w, h);
                float[] rm = tex(r.getMainTexture(), w, h), ra = tex(r.getAltTexture(), w, h);
                log("%s TAA probe: L main/alt=%d/%d R main/alt=%d/%d %dx%d", tag, l.getMainTexture(), l.getAltTexture(), r.getMainTexture(), r.getAltTexture(), w, h);
                log("%s TAA probe: |Lmain-Rmain|=%.5f |Lalt-Ralt|=%.5f |Lmain-Lalt|=%.5f |Rmain-Ralt|=%.5f |Lmain-Ralt|=%.5f |Rmain-Lalt|=%.5f",
                    tag, mad(lm, rm), mad(la, ra), mad(lm, la), mad(rm, ra), mad(lm, ra), mad(rm, la));
            } catch (Exception e) {
                log("%s TAA probe failed: %s", tag, e);
            }
        });
    }

    private static float[] tex(int id, int w, int h) {
        float[] px = new float[w * h * 4];
        org.lwjgl.opengl.GL45.glGetTextureImage(id, 0, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_FLOAT, px);
        return px;
    }

    private static double mad(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return s / a.length;
    }

    // --- checks -------------------------------------------------------------

    /**
     * At ~zero IPD (parallel) both eyes must render the same image: catches
     * one-eye-only artifacts (stale per-eye history/targets, LEFT-consumed
     * per-frame state, wrong-eye texture routing).
     */
    private void checkEyesIdentical(ClientGameTestContext context, String name) {
        set(context, StereoMode.SBS_HALF, 0.0005f, 0f, false);
        Shot s = capture(context, name);
        double whole = StereoMetrics.asymmetry(s);
        log("%s: eye asymmetry %.3f%% of pixels", name, whole * 100);
        if (whole > 0.005) fail(String.format("%s: eyes differ at zero IPD (%.2f%% of pixels) — one-eye artifact", name, whole * 100));
        for (Marker m : Marker.values()) {
            double a = StereoMetrics.regionAsymmetry(s, m);
            if (Double.isNaN(a)) { fail(name + ": " + m + " missing"); continue; }
            log("%s: %s region asymmetry %.2f%%", name, m, a * 100);
            if (a > 0.03) fail(String.format("%s: %s renders differently per eye at zero IPD (%.1f%% of its pixels) — one-eye artifact", name, m, a * 100));
        }
    }

    private void checkPresence(Shot... shots) {
        for (Shot s : shots) {
            for (Marker m : Marker.values()) {
                if (s.left().get(m) == null) fail(s.name() + ": " + m + " missing in LEFT half");
                if (s.right().get(m) == null) fail(s.name() + ": " + m + " missing in RIGHT half");
            }
        }
    }

    private void checkSwapMirrors(Shot normal, Shot swapped) {
        for (Marker m : Marker.values()) {
            Double d = disp(normal, m), ds = disp(swapped, m);
            if (d == null || ds == null) continue;
            log("%s/%s %s: disp %+.2f vs swapped %+.2f", normal.name(), swapped.name(), m, d, ds);
            if (Math.abs(d) < 0.3) continue; // too small to tell a mirror from noise
            // Two separate renders: allow each ~0.3 px of measurement noise,
            // more on small far LOD targets (up to 25% of the disparity). A
            // no-op swap is off by 2|d|, a wrong sign by far more.
            if (Math.abs(d + ds) > Math.max(0.6, 0.25 * Math.abs(d))) {
                fail(String.format("%s: swapEyes does not mirror %s disparity (%+.2f vs %+.2f)", swapped.name(), m, d, ds));
            }
        }
    }

    /** Parallel cameras: every point has x_right < x_left, magnitude ~ 1/depth. */
    private void checkParallel(Shot par) {
        Double r = disp(par, Marker.RED), b = disp(par, Marker.BLUE), y = disp(par, Marker.YELLOW);
        if (r == null || b == null || y == null) return;
        log("%s: red %+.2f blue %+.2f yellow %+.2f", par.name(), r, b, y);
        if (!(r < -0.5 && b < 0 && y < 0)) {
            fail(String.format("%s: parallel disparity must be negative (eye offset not applied?): red %+.2f blue %+.2f yellow %+.2f", par.name(), r, b, y));
        }
        if (!(Math.abs(r) > Math.abs(b) && Math.abs(b) > Math.abs(y))) {
            fail(String.format("%s: parallel disparity must shrink with depth: red %+.2f blue %+.2f yellow %+.2f", par.name(), r, b, y));
        }
    }

    /**
     * Parallel disparity is f·IPD/depth for every point, whatever draws it, so
     * K = -d·depth must agree across terrain, entity and particle markers.
     * Catches a renderer applying a different eye separation (issue #5:
     * "entities & particles have exaggerated eye distance").
     */
    private void checkDepthScaling(Shot par) {
        Map<Marker, Double> k = new EnumMap<>(Marker.class);
        for (Marker m : Marker.values()) {
            Double d = disp(par, m);
            if (d != null) k.put(m, -d * depth.get(m));
        }
        if (k.size() < Marker.values().length) return; // presence check reports it
        double[] sorted = k.values().stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double median = sorted[sorted.length / 2];
        StringBuilder sb = new StringBuilder();
        k.forEach((m, v) -> sb.append(String.format(" %s=%.1f", m, v)));
        log("%s: disparity x depth per marker:%s", par.name(), sb);
        for (var e : k.entrySet()) {
            double ratio = e.getValue() / median;
            if (ratio < 0.7 || ratio > 1.4) {
                fail(String.format("%s: %s eye separation %.2fx the scene's (disparity x depth%s)", par.name(), e.getKey(), ratio, sb));
            }
        }
    }

    /**
     * Convergence adds the SAME constant screen shift to every object
     * regardless of depth or renderer: d_conv = d_parallel(ipd) + C. With the
     * parallel shot taken at a different IPD, C_i = d_conv_i - k * d_par_i
     * (k = IPD / parallel IPD) must agree across terrain and entity markers.
     */
    private void checkConvergenceConsistent(Shot conv, Shot par) {
        double k = IPD / parallelIpd;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        StringBuilder sb = new StringBuilder();
        for (Marker m : Marker.values()) {
            Double dc = disp(conv, m), dp = disp(par, m);
            if (dc == null || dp == null) return;
            double c = dc - k * dp;
            sb.append(String.format(" %s=%+.2f", m, c));
            min = Math.min(min, c);
            max = Math.max(max, c);
        }
        log("%s: convergence shift per marker:%s", conv.name(), sb);
        if (max - min > 0.8) {
            fail(String.format("%s: terrain and entities get different convergence shift (spread %.2f px):%s", conv.name(), max - min, sb));
        }
        if (min <= 0.5) {
            fail(String.format("%s: convergence shift missing or wrong sign (min %.2f px):%s", conv.name(), min, sb));
        }
    }

    /** FULL keeps the mono aspect per eye; HALF squeezes it to half. */
    private void checkAspect(Shot mono, Shot half, Shot full) {
        StereoMetrics.Blob m = mono.whole().get(Marker.BLUE);
        StereoMetrics.Blob h = half.left().get(Marker.BLUE);
        StereoMetrics.Blob f = full.left().get(Marker.BLUE);
        if (m == null || h == null || f == null) {
            fail("aspect: blue marker missing");
            return;
        }
        log("aspect blue w/h: mono %.3f half %.3f full %.3f", m.aspect(), h.aspect(), f.aspect());
        if (Math.abs(f.aspect() / m.aspect() - 1.0) > 0.12) {
            fail(String.format("SBS_FULL per-eye aspect %.3f != mono %.3f", f.aspect(), m.aspect()));
        }
        if (Math.abs(h.aspect() / m.aspect() - 0.5) > 0.08) {
            fail(String.format("SBS_HALF per-eye aspect %.3f != mono/2 %.3f", h.aspect(), m.aspect() / 2));
        }
    }

    // --- helpers ------------------------------------------------------------

    private static Double disp(Shot s, Marker m) {
        if (s.left().get(m) == null || s.right().get(m) == null) return null;
        return StereoMetrics.disparity(s, m);
    }

    private void fail(String msg) {
        failures.add(msg);
        Stereoscopic.LOG.error("[stereo-test] FAIL {}", msg);
    }

    private static void log(String fmt, Object... args) {
        Stereoscopic.LOG.info("[stereo-test] " + String.format(fmt, args));
    }

    /**
     * Applies options the way the in-game options page does: a mode change
     * goes through StereoOptionsPage.applyModeChange's sequence (assign, Iris
     * pipeline rebuild so the per-eye banks match, GUI re-layout). Waits for
     * the rebuild when the mode changed.
     */
    private void set(ClientGameTestContext context, StereoMode mode, float ipd, float conv, boolean swap) {
        boolean changed = context.computeOnClient(mc -> {
            StereoOptions o = StereoOptions.INSTANCE;
            o.ipd = ipd;
            o.convergence = conv;
            o.swapEyes = swap;
            if (o.mode == mode) return false;
            o.mode = mode;
            com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks.rebuildPipelineForStereoToggle();
            StereoTestCompat.resizeGui(mc);
            return true;
        });
        if (changed) {
            context.waitTicks(60);
            StereoTestCompat.waitForChunksRender(sp); // the rebuild reloads chunks
        }
    }

    private Shot shot(ClientGameTestContext context, String name, StereoMode mode, float ipd, float conv, boolean swap) {
        set(context, mode, ipd, conv, swap);
        return capture(context, name);
    }

    /**
     * Screenshot + measure. Re-spawns the particle marker first: a static
     * cyan-wool block_marker billboard (80-tick lifetime) in the active scene.
     */
    private Shot capture(ClientGameTestContext context, String name) {
        server.runCommand(StereoTestCompat.particleCommand(particleAt));
        context.waitTicks(10);
        return StereoMetrics.measure(name, context.takeScreenshot(name));
    }
}
