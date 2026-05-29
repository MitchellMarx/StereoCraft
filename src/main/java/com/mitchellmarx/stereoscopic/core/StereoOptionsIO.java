package com.mitchellmarx.stereoscopic.core;

import com.google.gson.*;
import com.mitchellmarx.stereoscopic.Stereoscopic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class StereoOptionsIO {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private StereoOptionsIO() {}

    public static void readInto(Path path, StereoOptions target) {
        if (!Files.exists(path)) return;
        try {
            String body = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();

            if (obj.has("mode")) {
                String s = obj.get("mode").getAsString();
                try { target.mode = StereoMode.valueOf(s); }
                catch (IllegalArgumentException ex) {
                    Stereoscopic.LOG.warn("Unknown stereo mode {}; keeping default", s);
                }
            }
            if (obj.has("ipd")) {
                float v = obj.get("ipd").getAsFloat();
                // Range matches the GUI slider in StereoOptionsPage (55-75 mm).
                // Older configs from before the slider was narrowed may carry
                // wider values; clamp and warn so the user notices the change
                // rather than silently losing their setting on the first save.
                float clamped = Math.max(0.055f, Math.min(0.075f, v));
                if (clamped != v) {
                    Stereoscopic.LOG.warn(
                        "ipd {} m in {} is outside the 0.055-0.075 m slider range; clamped to {} m",
                        v, path, clamped);
                }
                target.ipd = clamped;
            }
            if (obj.has("convergence")) {
                float v = obj.get("convergence").getAsFloat();
                target.convergence = Math.max(0f, Math.min(16f, v));
            }
            if (obj.has("swapEyes")) {
                target.swapEyes = obj.get("swapEyes").getAsBoolean();
            }
        } catch (IOException | JsonParseException e) {
            Stereoscopic.LOG.error("Failed to read {}; keeping in-memory defaults", path, e);
        }
    }

    public static void writeTo(Path path, StereoOptions source) {
        try {
            Files.createDirectories(path.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("mode", source.mode.name());
            obj.addProperty("ipd", source.ipd);
            obj.addProperty("convergence", source.convergence);
            obj.addProperty("swapEyes", source.swapEyes);
            Files.writeString(path, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Stereoscopic.LOG.error("Failed to write {}", path, e);
        }
    }
}
