package com.mitchellmarx.stereoscopic.selftest;

import com.mitchellmarx.stereoscopic.Stereoscopic;
import com.mitchellmarx.stereoscopic.selftest.StereoMetrics.Marker;
import com.mitchellmarx.stereoscopic.selftest.StereoMetrics.Shot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Stereo correctness checks over measured screenshots — same thresholds as the
 * Fabric branches' client gametest (StereoClientGameTest).
 */
final class StereoChecks {

    final float ipd;
    final float parallelIpd;
    final List<String> failures = new ArrayList<>();

    StereoChecks(float ipd, float parallelIpd) {
        this.ipd = ipd;
        this.parallelIpd = parallelIpd;
    }

    void presence(Shot... shots) {
        for (Shot s : shots) {
            for (Marker m : Marker.values()) {
                if (s.left().get(m) == null) fail(s.name() + ": " + m + " missing in LEFT half");
                if (s.right().get(m) == null) fail(s.name() + ": " + m + " missing in RIGHT half");
            }
        }
    }

    void swapMirrors(Shot normal, Shot swapped) {
        for (Marker m : Marker.values()) {
            Double d = disp(normal, m), ds = disp(swapped, m);
            if (d == null || ds == null) continue;
            log("%s/%s %s: disp %+.2f vs swapped %+.2f", normal.name(), swapped.name(), m, d, ds);
            if (Math.abs(d) < 0.3) continue;
            // Two separate renders: allow each ~0.3 px of measurement noise,
            // more on small far LOD targets (up to 25% of the disparity).
            if (Math.abs(d + ds) > Math.max(0.6, 0.25 * Math.abs(d))) {
                fail(String.format("%s: swapEyes does not mirror %s disparity (%+.2f vs %+.2f)", swapped.name(), m, d, ds));
            }
        }
    }

    /** Parallel cameras: every point has x_right < x_left, magnitude ~ 1/depth. */
    void parallel(Shot par) {
        Double r = disp(par, Marker.RED), b = disp(par, Marker.BLUE), y = disp(par, Marker.YELLOW);
        if (r == null || b == null || y == null) return;
        log("%s: red %+.2f blue %+.2f yellow %+.2f", par.name(), r, b, y);
        if (!(r < -0.5 && b < 0 && y < 0)) {
            fail(String.format("%s: parallel disparity must be negative (eye offset not applied / reversed?): red %+.2f blue %+.2f yellow %+.2f", par.name(), r, b, y));
        }
        if (!(Math.abs(r) > Math.abs(b) && Math.abs(b) > Math.abs(y))) {
            fail(String.format("%s: parallel disparity must shrink with depth: red %+.2f blue %+.2f yellow %+.2f", par.name(), r, b, y));
        }
    }

    /**
     * Parallel disparity is f·IPD/depth for every point, whatever draws it, so
     * K = -d·depth must agree across terrain, entity and particle markers.
     * Catches a renderer applying a different eye separation (issue #5).
     */
    void depthScaling(Shot par, Map<Marker, Double> depth) {
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

    /** No LOD-scene pillar may be visible when the LOD renderer is off (proves the pass measures it). */
    void lodControl(Shot off, String mod) {
        for (Marker m : new Marker[] {Marker.RED, Marker.BLUE, Marker.YELLOW}) {
            if (off.whole().get(m) != null) fail(off.name() + ": " + m + " visible with " + mod + " rendering off — the pass would not measure " + mod);
        }
        for (Marker m : new Marker[] {Marker.SHEEP, Marker.PARTICLE}) {
            if (off.whole().get(m) == null) fail(off.name() + ": vanilla " + m + " missing");
        }
    }

    /** d_conv = k * d_parallel + C with the same C for terrain and entities. */
    void convergenceConsistent(Shot conv, Shot par) {
        convergenceConsistent(conv, par, parallelIpd);
    }

    void convergenceConsistent(Shot conv, Shot par, float parallelIpd) {
        double k = ipd / parallelIpd;
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

    void aspect(Shot mono, Shot half, Shot full) {
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

    /** At ~zero IPD both eyes must render the same image (whole frame + per marker). */
    void eyesIdentical(Shot s) {
        double whole = StereoMetrics.asymmetry(s);
        log("%s: eye asymmetry %.3f%% of pixels", s.name(), whole * 100);
        if (whole > 0.005) fail(String.format("%s: eyes differ at zero IPD (%.2f%% of pixels) — one-eye artifact", s.name(), whole * 100));
        for (Marker m : Marker.values()) {
            double a = StereoMetrics.regionAsymmetry(s, m);
            if (Double.isNaN(a)) { fail(s.name() + ": " + m + " missing"); continue; }
            log("%s: %s region asymmetry %.2f%%", s.name(), m, a * 100);
            if (a > 0.03) fail(String.format("%s: %s renders differently per eye at zero IPD (%.1f%% of its pixels) — one-eye artifact", s.name(), m, a * 100));
        }
    }

    private static Double disp(Shot s, Marker m) {
        if (s.left().get(m) == null || s.right().get(m) == null) return null;
        return StereoMetrics.disparity(s, m);
    }

    void fail(String msg) {
        failures.add(msg);
        Stereoscopic.LOG.error("[stereo-test] FAIL {}", msg);
    }

    static void log(String fmt, Object... args) {
        Stereoscopic.LOG.info("[stereo-test] " + String.format(fmt, args));
    }
}
