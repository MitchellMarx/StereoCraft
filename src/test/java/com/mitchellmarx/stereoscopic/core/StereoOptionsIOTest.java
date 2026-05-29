package com.mitchellmarx.stereoscopic.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class StereoOptionsIOTest {

    @Test
    void roundTrip(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("opts.json");

        StereoOptions o = new StereoOptions();
        o.mode = StereoMode.SBS_HALF;
        o.ipd = 0.075f;
        o.convergence = 8.0f;

        StereoOptionsIO.writeTo(file, o);

        StereoOptions loaded = new StereoOptions();
        StereoOptionsIO.readInto(file, loaded);

        assertEquals(StereoMode.SBS_HALF, loaded.mode);
        assertEquals(0.075f, loaded.ipd, 1e-6f);
        assertEquals(8.0f, loaded.convergence, 1e-6f);
    }

    @Test
    void missingFileLeavesDefaults(@TempDir Path tmp) throws Exception {
        StereoOptions loaded = new StereoOptions();
        StereoOptionsIO.readInto(tmp.resolve("nope.json"), loaded);
        assertEquals(StereoMode.OFF, loaded.mode);
        assertEquals(0.064f, loaded.ipd, 1e-6f);
        assertEquals(4.0f, loaded.convergence, 1e-6f);
    }

    @Test
    void partialJsonKeepsDefaultsForMissingKeys(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("partial.json");
        Files.writeString(file, "{ \"ipd\": 0.070 }");

        StereoOptions loaded = new StereoOptions();
        StereoOptionsIO.readInto(file, loaded);
        assertEquals(StereoMode.OFF, loaded.mode);
        assertEquals(0.070f, loaded.ipd, 1e-6f);
        assertEquals(4.0f, loaded.convergence, 1e-6f,
            "convergence missing from JSON, default preserved");
    }

    @Test
    void ipdIsClampedOnRead(@TempDir Path tmp) throws Exception {
        Path hi = tmp.resolve("hi.json");
        Files.writeString(hi, "{ \"ipd\": 99.0 }");
        StereoOptions loadedHi = new StereoOptions();
        StereoOptionsIO.readInto(hi, loadedHi);
        assertEquals(0.075f, loadedHi.ipd, 1e-6f, "IPD clamped to [0.055, 0.075]");

        // Older configs predating the narrowed range; clamp not preserve.
        Path legacy = tmp.resolve("legacy.json");
        Files.writeString(legacy, "{ \"ipd\": 0.080 }");
        StereoOptions loadedLegacy = new StereoOptions();
        StereoOptionsIO.readInto(legacy, loadedLegacy);
        assertEquals(0.075f, loadedLegacy.ipd, 1e-6f,
            "legacy 0.080 IPD clamped down to slider max");

        Path lo = tmp.resolve("lo.json");
        Files.writeString(lo, "{ \"ipd\": 0.0 }");
        StereoOptions loadedLo = new StereoOptions();
        StereoOptionsIO.readInto(lo, loadedLo);
        assertEquals(0.055f, loadedLo.ipd, 1e-6f, "IPD clamped up to slider min");
    }

    @Test
    void convergenceIsClampedOnRead(@TempDir Path tmp) throws Exception {
        Path hi = tmp.resolve("hi.json");
        Files.writeString(hi, "{ \"convergence\": 99.0 }");
        StereoOptions loadedHi = new StereoOptions();
        StereoOptionsIO.readInto(hi, loadedHi);
        assertEquals(16.0f, loadedHi.convergence, 1e-6f,
            "convergence clamped to [0, 16]");

        Path lo = tmp.resolve("lo.json");
        Files.writeString(lo, "{ \"convergence\": -5.0 }");
        StereoOptions loadedLo = new StereoOptions();
        StereoOptionsIO.readInto(lo, loadedLo);
        assertEquals(0.0f, loadedLo.convergence, 1e-6f,
            "convergence clamped to [0, 16]; 0 = parallel-axis");
    }
}
