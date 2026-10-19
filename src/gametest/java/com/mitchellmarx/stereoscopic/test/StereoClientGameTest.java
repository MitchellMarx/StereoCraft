package com.mitchellmarx.stereoscopic.test;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.core.StereoMode;
import com.mitchellmarx.stereoscopic.core.StereoOptions;
import com.mitchellmarx.stereoscopic.test.StereoMetrics.Marker;
import com.mitchellmarx.stereoscopic.test.StereoMetrics.Shot;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Automated stereo correctness test. Builds a fixed scene (wool pillars =
 * Sodium terrain at three depths, a NoAI sheep = entity), screenshots it under
 * each stereo configuration, measures per-eye marker positions from the
 * screenshot pixels ({@link StereoMetrics}) and fails on:
 * <ul>
 *   <li>any marker missing from either eye (dropped entities / terrain);</li>
 *   <li>swapEyes not mirroring every disparity;</li>
 *   <li>parallel-camera disparity with the wrong sign or not shrinking with depth;</li>
 *   <li>terrain and entities disagreeing on the convergence shift (issue #5);</li>
 *   <li>SBS_FULL / SBS_HALF per-eye aspect wrong;</li>
 *   <li>markers lost after a window resize mid-stereo.</li>
 * </ul>
 * Run: {@code ./gradlew runClientGameTest}. Screenshots stay in
 * {@code build/run/clientGameTest/screenshots} for inspection.
 */
@SuppressWarnings("UnstableApiUsage")
public class StereoClientGameTest implements FabricClientGameTest {

    private static final float IPD = 0.064f;
    private static final float CONV = 4f;
    private static final float PARALLEL_IPD = 0.1f;

    private final List<String> failures = new ArrayList<>();

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            TestServerContext server = sp.getServer();
            // Superflat: surface at y=-61, player feet at y=-60. Camera faces +Z.
            // Markers stay within the narrower SBS_FULL per-eye FOV.
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runCommand("fill -12 -60 2 12 -50 64 air");
            server.runCommand("fill -2 -60 6 -2 -56 6 red_wool");     // near
            server.runCommand("fill 0 -60 12 0 -55 12 blue_wool");    // mid
            server.runCommand("fill 5 -60 30 5 -52 30 yellow_wool");  // far
            server.runCommand("summon minecraft:sheep 2.5 -60 8.5 {NoAI:1b,Silent:1b,Color:2b,Rotation:[90f,0f]}");
            server.runCommand("tp @p 0.5 -60 0.5 0 10");
            StereoTestCompat.waitForChunksRender(sp);
            context.getInput().pressKey(o -> o.keyToggleGui); // hide HUD (F1)
            context.waitTicks(20);

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
            Shot resized = StereoMetrics.measure("half_conv4_after_resize",
                context.takeScreenshot("half_conv4_after_resize"));

            set(context, StereoMode.OFF, IPD, CONV, false);
            context.waitTicks(5);

            // Diagnostic: -Dstereoscopic.test.bisect="tag=OPT=V,OPT=V;tag2=..." runs
            // only these shader-option variants (to localise a failing feature).
            String bisect = System.getProperty("stereoscopic.test.bisect");
            if (bisect != null && !bisect.isBlank()) {
                for (String variant : bisect.split(";")) {
                    String[] kv = variant.split("=", 2);
                    shaderPass(context, "bisect_" + kv[0], kv.length > 1 ? kv[1].replace(',', '\n') + "\n" : "");
                }
            } else {
                shaderPass(context, "shaders", "");
                shaderPass(context, "shaders_noTAA", "TAA_DEFINE=0\n");
            }

            // After rendering with shaders, every target class is loaded.
            Path gameDir = context.computeOnClient(mc -> mc.gameDirectory.toPath());
            for (String f : StereoMixinAudit.audit(gameDir, "stereoscopic.mixins.json", "stereoscopic-iris.mixins.json")) fail("mixin: " + f);
            log("mixin audit: %d injectors/accessors verified", StereoMixinAudit.verified);
            if (StereoMixinAudit.verified < 20) fail("mixin audit verified only " + StereoMixinAudit.verified + " injectors — audit broken?");

            checkPresence(half, halfSwap, par, parSwap, full, resized);
            checkSwapMirrors(half, halfSwap);
            checkSwapMirrors(par, parSwap);
            checkParallel(par);
            checkConvergenceConsistent(half, par);
            checkAspect(mono, half, full);
        }
        if (!failures.isEmpty()) {
            throw new AssertionError("Stereo test failed:\n  " + String.join("\n  ", failures));
        }
        Stereoscopic.LOG.info("[stereo-test] ALL CHECKS PASSED");
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
                Path dir = mc.gameDirectory.toPath().resolve("shaderpacks");
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
        Shot half = shot(context, tag + "_half_conv4", StereoMode.SBS_HALF, IPD, CONV, false);
        Shot par = shot(context, tag + "_half_parallel", StereoMode.SBS_HALF, PARALLEL_IPD, 0f, false);
        checkEyesIdentical(context, tag + "_half_ipd0");
        if (Boolean.getBoolean("stereoscopic.test.probeTaa")) probeTaaHistory(context, tag);
        set(context, StereoMode.OFF, IPD, CONV, false);
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
        checkPresence(half, par);
        checkParallel(par);
        checkConvergenceConsistent(half, par);
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
        context.waitTicks(10);
        Shot s = StereoMetrics.measure(name, context.takeScreenshot(name));
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
            if (Math.abs(d + ds) > 0.6) {
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
     * Convergence adds the SAME constant screen shift to every object
     * regardless of depth or renderer: d_conv = d_parallel(ipd) + C. With the
     * parallel shot taken at a different IPD, C_i = d_conv_i - k * d_par_i
     * (k = IPD / PARALLEL_IPD) must agree across terrain and entity markers.
     */
    private void checkConvergenceConsistent(Shot conv, Shot par) {
        double k = IPD / PARALLEL_IPD;
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
    private static void set(ClientGameTestContext context, StereoMode mode, float ipd, float conv, boolean swap) {
        boolean changed = context.computeOnClient(mc -> {
            StereoOptions o = StereoOptions.INSTANCE;
            o.ipd = ipd;
            o.convergence = conv;
            o.swapEyes = swap;
            if (o.mode == mode) return false;
            o.mode = mode;
            com.mitchellmarx.stereoscopic.compat.iris.PerEyeRenderTargetHooks.rebuildPipelineForStereoToggle();
            mc.resizeGui();
            return true;
        });
        if (changed) context.waitTicks(60);
    }

    private static Shot shot(ClientGameTestContext context, String name, StereoMode mode, float ipd, float conv, boolean swap) {
        set(context, mode, ipd, conv, swap);
        context.waitTicks(10);
        return StereoMetrics.measure(name, context.takeScreenshot(name));
    }
}
