package com.mitchellmarx.stereoscopic.selftest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Pixel measurements on harness screenshots: per-eye-half centroid and bbox
 * of each coloured scene marker. Pure Java (ImageIO) so it runs inside the
 * self-test and can fail it.
 */
final class StereoMetrics {

    enum Marker { RED, BLUE, YELLOW, SHEEP }

    record Blob(double cx, int w, int h, int n) {
        double aspect() { return (double) w / h; }
    }

    /** Markers found in one image region; absent markers map to null. */
    record Region(Map<Marker, Blob> blobs) {
        Blob get(Marker m) { return blobs.get(m); }
    }

    record Shot(String name, int width, int height, Region left, Region right, Region whole,
                int darkTopRightLeft, int darkTopRightRight) {}

    private StereoMetrics() {}

    static Shot measure(String name, Path png) {
        BufferedImage img;
        try {
            img = ImageIO.read(png.toFile());
        } catch (IOException e) {
            throw new AssertionError("could not read screenshot " + png, e);
        }
        int w = img.getWidth(), h = img.getHeight(), half = w / 2;
        return new Shot(name, w, h, region(img, 0, half), region(img, half, w), region(img, 0, w),
            darkTopRight(img, 0, half), darkTopRight(img, half, w));
    }

    private static boolean matches(Marker m, int r, int g, int b) {
        return switch (m) {
            case RED -> r > 110 && g < 60 && b < 60;
            case BLUE -> b > 90 && r < 70 && g < 80;
            // Ratio test: 1.7.10 lighting renders yellow wool olive (~130,120,25).
            case YELLOW -> r > 100 && g > 0.8 * r && g <= r + 10 && b < 0.45 * r;
            case SHEEP -> r > 110 && b > 110 && g < 70; // magenta wool
        };
    }

    private static Region region(BufferedImage img, int x0, int x1) {
        Map<Marker, Blob> out = new EnumMap<>(Marker.class);
        for (Marker m : Marker.values()) {
            long sx = 0; int n = 0, minX = Integer.MAX_VALUE, maxX = -1, minY = Integer.MAX_VALUE, maxY = -1;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = x0; x < x1; x++) {
                    int rgb = img.getRGB(x, y);
                    if (!matches(m, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) continue;
                    int lx = x - x0;
                    sx += lx; n++;
                    minX = Math.min(minX, lx); maxX = Math.max(maxX, lx);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
            out.put(m, n < 15 ? null : new Blob((double) sx / n, maxX - minX + 1, maxY - minY + 1, n));
        }
        return new Region(out);
    }

    /**
     * Near-black pixels in the top-right corner of a half (the popup box; the
     * sky/clouds behind it are bright).
     */
    private static int darkTopRight(BufferedImage img, int x0, int x1) {
        int n = 0;
        int yMax = img.getHeight() / 7;
        for (int y = 0; y < yMax; y++) {
            for (int x = x0 + (x1 - x0) / 2; x < x1; x++) {
                int rgb = img.getRGB(x, y);
                if (Math.max((rgb >> 16) & 0xFF, Math.max((rgb >> 8) & 0xFF, rgb & 0xFF)) < 50) n++;
            }
        }
        return n;
    }

    /** Right-half centroid minus left-half centroid, in half-local px. */
    static double disparity(Shot s, Marker m) {
        Blob l = s.left().get(m), r = s.right().get(m);
        if (l == null || r == null) {
            throw new AssertionError(s.name() + ": " + m + " missing from "
                + (l == null ? "LEFT" : "") + (l == null && r == null ? "+" : "") + (r == null ? "RIGHT" : "") + " half");
        }
        return r.cx() - l.cx();
    }
}
