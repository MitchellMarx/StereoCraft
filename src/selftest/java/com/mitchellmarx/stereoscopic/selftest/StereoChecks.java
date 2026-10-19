package com.mitchellmarx.stereoscopic.selftest;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.mitchellmarx.stereoscopic.selftest.StereoMetrics.Marker;
import com.mitchellmarx.stereoscopic.selftest.StereoMetrics.Shot;

import java.util.ArrayList;
import java.util.List;

/**
 * Stereo correctness checks over measured screenshots — same thresholds as the
 * Fabric branches' client gametest and the NeoForge self-test.
 */
final class StereoChecks {

    static final Logger LOG = LogManager.getLogger("StereoSelfTest");

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
            if (Math.abs(d + ds) > 0.6) {
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

    /** d_conv = k * d_parallel + C with the same C for terrain and entities. */
    void convergenceConsistent(Shot conv, Shot par) {
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

    /** HUD-style overlays (achievement popup) must appear in each eye half. */
    void popupInBothHalves(Shot s) {
        if (s == null) { fail("popup: no screenshot"); return; }
        log("%s: popup dark px top-right LEFT=%d RIGHT=%d", s.name(), s.darkTopRightLeft(), s.darkTopRightRight());
        if (s.darkTopRightLeft() < 200 || s.darkTopRightRight() < 200) {
            fail(String.format("%s: achievement popup not drawn in both eye halves (dark px LEFT=%d RIGHT=%d)",
                s.name(), s.darkTopRightLeft(), s.darkTopRightRight()));
        }
    }

    private static Double disp(Shot s, Marker m) {
        if (s.left().get(m) == null || s.right().get(m) == null) return null;
        return StereoMetrics.disparity(s, m);
    }

    private void fail(String msg) {
        failures.add(msg);
        LOG.error("[stereo-test] FAIL {}", msg);
    }

    static void log(String fmt, Object... args) {
        LOG.info("[stereo-test] " + String.format(fmt, args));
    }
}
