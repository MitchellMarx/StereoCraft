package com.mitchellmarx.stereoscopic.test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Pixel measurements on harness screenshots. Colour masks only LOCATE each
 * scene marker per eye half; disparity is measured by cross-correlating the
 * marker's patch between the halves (sub-pixel), which is robust to shader
 * post-processing (bloom, grading) that shifts colour-blob centroids. Pure
 * Java (ImageIO) so it runs inside the client gametest and can fail it.
 */
final class StereoMetrics {

    enum Marker { RED, BLUE, YELLOW, SHEEP, PARTICLE }

    record Blob(double cx, int minX, int maxX, int minY, int maxY, int n) {
        int w() { return maxX - minX + 1; }
        int h() { return maxY - minY + 1; }
        double aspect() { return (double) w() / h(); }
    }

    /** Markers found in one image region; absent markers map to null. */
    record Region(Map<Marker, Blob> blobs) {
        Blob get(Marker m) { return blobs.get(m); }
    }

    record Shot(String name, BufferedImage img, Region left, Region right, Region whole) {
        int half() { return img.getWidth() / 2; }
    }

    private StereoMetrics() {}

    static Shot measure(String name, Path png) {
        BufferedImage img = read(png);
        int w = img.getWidth(), half = w / 2;
        return new Shot(name, img, region(img, 0, half), region(img, half, w), region(img, 0, w));
    }

    private static BufferedImage read(Path png) {
        try {
            return ImageIO.read(png.toFile());
        } catch (IOException e) {
            throw new AssertionError("could not read screenshot " + png, e);
        }
    }

    private static boolean matches(Marker m, int r, int g, int b) {
        return switch (m) {
            // Ratio test: distant red wool under shader haze is ~(195,60,60).
            case RED -> r > 100 && g < 0.45 * r && b < 0.45 * r;
            // Ratio test: lit by shaders blue wool reaches ~(75,85,180); sky
            // and the cyan particle have g close to b.
            case BLUE -> b > 90 && r < 0.55 * b && g < 0.6 * b;
            // Ratio test: yellow wool is pale in vanilla, olive under 1.7.10
            // lighting (~130,120,25), warm under Complementary (~190,170,80);
            // grass has g > r, so it never matches.
            case YELLOW -> r > 100 && g > 0.75 * r && g <= r + 10 && b < 0.55 * r;
            // Magenta wool: ~(100,40,100) vanilla-lit, ~(130,60,130) shaded.
            case SHEEP -> r > 80 && b > 80 && g < 0.6 * Math.min(r, b) && Math.abs(r - b) < 40;
            // Cyan wool particle: ~(21,137,145) vanilla, ~(93,178,195) under
            // Complementary on 1.7.10; sky has b >> g, haze r close to g.
            case PARTICLE -> g > 70 && b > 70 && r < 0.6 * Math.min(g, b) && Math.abs(g - b) < 0.25 * Math.max(g, b);
        };
    }

    /**
     * Per marker: bbox over the densest contiguous band of columns (columns
     * with >= 15% of the peak column's count, contiguous with the peak allowing
     * gaps of up to 2 columns). Rejects stray same-coloured pixels such as
     * shaded edges of other markers under shader packs.
     */
    private static Region region(BufferedImage img, int x0, int x1) {
        Map<Marker, Blob> out = new EnumMap<>(Marker.class);
        int w = x1 - x0, h = img.getHeight();
        for (Marker m : Marker.values()) {
            boolean[][] hit = new boolean[w][h];
            int[] col = new int[w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x0 + x, y);
                    if (matches(m, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF)) {
                        hit[x][y] = true;
                        col[x]++;
                    }
                }
            }
            int peak = 0;
            for (int x = 1; x < w; x++) if (col[x] > col[peak]) peak = x;
            int cut = Math.max(1, col[peak] * 15 / 100);
            int lo = peak, hi = peak;
            while (lo > 0 && bandContinues(col, lo, -1, cut)) lo--;
            while (hi < w - 1 && bandContinues(col, hi, +1, cut)) hi++;
            long sx = 0; int n = 0, minY = Integer.MAX_VALUE, maxY = -1;
            for (int x = lo; x <= hi; x++) {
                for (int y = 0; y < h; y++) {
                    if (!hit[x][y]) continue;
                    sx += x; n++;
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                }
            }
            out.put(m, n < 15 ? null : new Blob((double) sx / n, lo, hi, minY, maxY, n));
        }
        return new Region(out);
    }

    /** Next column (dir = +/-1) is in the band, bridging gaps of up to 2 columns. */
    private static boolean bandContinues(int[] col, int x, int dir, int cut) {
        for (int k = 1; k <= 3; k++) {
            int nx = x + dir * k;
            if (nx < 0 || nx >= col.length) return false;
            if (col[nx] >= cut) return true;
        }
        return false;
    }

    private static double luma(int rgb) {
        return 0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF);
    }

    private static final int MAX_SHIFT = 30;
    private static final int PAD = 6;

    /**
     * Right-half position minus left-half position of {@code m}, in half-local
     * px: the horizontal shift minimising the luma SSD between the marker's
     * left-half patch (bbox + padding) and the right half, refined to sub-pixel
     * with a parabola through the minimum. Throws if the marker is missing.
     */
    static double disparity(Shot s, Marker m) {
        Blob l = s.left().get(m), r = s.right().get(m);
        if (l == null || r == null) {
            throw new AssertionError(s.name() + ": " + m + " missing from "
                + (l == null ? "LEFT" : "") + (l == null && r == null ? "+" : "") + (r == null ? "RIGHT" : "") + " half");
        }
        BufferedImage img = s.img();
        int half = s.half();
        int x0 = Math.max(0, l.minX() - PAD), x1 = Math.min(half - 1, l.maxX() + PAD);
        double[] cost = new double[2 * MAX_SHIFT + 1];
        for (int k = 0; k < cost.length; k++) {
            int shift = k - MAX_SHIFT;
            double sum = 0; int n = 0;
            for (int y = l.minY(); y <= l.maxY(); y++) {
                for (int x = x0; x <= x1; x++) {
                    int xr = x + shift;
                    if (xr < 0 || xr >= half) continue;
                    double d = luma(img.getRGB(x, y)) - luma(img.getRGB(half + xr, y));
                    sum += d * d; n++;
                }
            }
            cost[k] = n == 0 ? Double.MAX_VALUE : sum / n;
        }
        int best = 0;
        for (int k = 1; k < cost.length; k++) if (cost[k] < cost[best]) best = k;
        double sub = 0;
        if (best > 0 && best < cost.length - 1) {
            double a = cost[best - 1], b = cost[best], c = cost[best + 1];
            double den = a - 2 * b + c;
            if (den > 1e-9) sub = 0.5 * (a - c) / den;
        }
        return best - MAX_SHIFT + sub;
    }

    /**
     * Fraction of pixels in {@code m}'s left-half bbox (+padding) that differ
     * from the same pixels in the right half by more than 40 in any channel.
     * At (near-)zero IPD both eyes should be identical there.
     */
    static double regionAsymmetry(Shot s, Marker m) {
        Blob l = s.left().get(m);
        if (l == null) return Double.NaN;
        BufferedImage img = s.img();
        int half = s.half(), bad = 0, total = 0;
        for (int y = l.minY(); y <= l.maxY(); y++) {
            for (int x = Math.max(0, l.minX() - PAD); x <= Math.min(half - 1, l.maxX() + PAD); x++) {
                if (channelDiff(img.getRGB(x, y), img.getRGB(x + half, y)) > 40) bad++;
                total++;
            }
        }
        return (double) bad / total;
    }

    /** Whole-image version of {@link #regionAsymmetry}. */
    static double asymmetry(Shot s) {
        BufferedImage img = s.img();
        int half = s.half(), bad = 0, total = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < half; x++) {
                if (channelDiff(img.getRGB(x, y), img.getRGB(x + half, y)) > 40) bad++;
                total++;
            }
        }
        return (double) bad / total;
    }

    private static int channelDiff(int a, int b) {
        return Math.max(Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)),
            Math.max(Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)), Math.abs((a & 0xFF) - (b & 0xFF))));
    }
}
