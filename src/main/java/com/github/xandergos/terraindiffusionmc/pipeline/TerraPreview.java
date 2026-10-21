package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Renders terrain previews outside the game, so changes to the model-drawn landforms can be compared without
 * flying around in Minecraft (run with {@code .\gradlew.bat terraPreview}; images and numbers go to run/terra-preview).
 *
 * <ul>
 *   <li>Archipelagos: the nearest few archipelago seas to the origin, each rendered at native resolution (30 m per
 *       pixel) with {@code oceans.model_archipelagos} on ({@code _model}) and off ({@code _old}).</li>
 *   <li>Rift seas and active margins: the coarse model's own elevation (7.7 km per pixel) around the nearest few of each,
 *       with the landforms ({@code _on}) and without ({@code _off}).</li>
 * </ul>
 * For each image, summary.txt lists land share, speckle (single land pixels in the sea and single sea pixels on land, per
 * thousand coast pixels) and texture (mean absolute Laplacian of the ground below 300 m, metres): numbers to compare
 * runs by. Arguments: {@code seed [archipelagoCount] [windowPx]}.
 */
public final class TerraPreview {

    private TerraPreview() {
    }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int win = args.length > 2 ? Integer.parseInt(args[2]) : 1024;
        Path out = Path.of(System.getProperty("terra.previewDir", "terra-preview"));
        Files.createDirectories(out);
        TerrainDiffusionConfig.setForTools("validate_model", "false");
        List<String> summary = new ArrayList<>();
        summary.add("seed " + seed + ", archipelago window " + win + " native px (" + win * 30 / 1000 + " km)");

        // Find landmarks with the default settings.
        List<OceanFeatures.Landmark> marks;
        try (WorldPipeline p = new WorldPipeline(seed)) {
            int R = 40000;   // native pixels: 1200 km around the origin
            marks = p.oceanLandmarks(-R, -R, R, R);
        }
        List<OceanFeatures.Landmark> arch = nearest(marks, "archipelago", count);
        List<OceanFeatures.Landmark> rifts = nearest(marks, "rift_sea", 2);
        List<OceanFeatures.Landmark> margins = nearest(marks, "active_margin", 2);
        summary.add("found within 1200 km: " + count(marks, "archipelago") + " archipelago markers, "
                + count(marks, "rift_sea") + " rift seas, " + count(marks, "active_margin") + " active-margin markers");

        // Archipelagos, model-drawn and old.
        for (String variant : new String[]{"model", "old"}) {
            TerrainDiffusionConfig.setForTools("oceans.model_archipelagos", variant.equals("model") ? "true" : "false");
            try (WorldPipeline p = new WorldPipeline(seed)) {
                for (int k = 0; k < arch.size(); k++) {
                    OceanFeatures.Landmark m = arch.get(k);
                    int i1 = (int) m.i() - win / 2, j1 = (int) m.j() - win / 2;
                    long t0 = System.nanoTime();
                    float[] e = p.get(i1, j1, i1 + win, j1 + win, false)[0];
                    String name = "archipelago" + k + "_" + variant;
                    write(render(e, win, win), out.resolve(name + ".png"));
                    summary.add(String.format("%s at /tp %d ~ %d (scale 6): %s, %.1f s", name, Math.round(m.j() * 6),
                            Math.round(m.i() * 6), stats(e, win, win), (System.nanoTime() - t0) / 1e9));
                }
            }
        }
        TerrainDiffusionConfig.setForTools("oceans.model_archipelagos", "true");

        // Rift seas and active margins at coarse resolution, with and without.
        for (String variant : new String[]{"on", "off"}) {
            TerrainDiffusionConfig.setForTools("oceans.rift_seas", variant.equals("on") ? "true" : "false");
            TerrainDiffusionConfig.setForTools("oceans.active_margins", variant.equals("on") ? "true" : "false");
            try (WorldPipeline p = new WorldPipeline(seed)) {
                List<OceanFeatures.Landmark> both = new ArrayList<>(rifts);
                both.addAll(margins);
                for (int k = 0; k < both.size(); k++) {
                    OceanFeatures.Landmark m = both.get(k);
                    int S = 256, C = 72;
                    int ci0 = Math.floorDiv((int) m.i(), S) - C / 2, cj0 = Math.floorDiv((int) m.j(), S) - C / 2;
                    float[] e = p.coarseElevation(ci0, cj0, ci0 + C, cj0 + C);
                    String name = m.type() + k + "_" + variant;
                    write(render(e, C, C), out.resolve(name + ".png"));
                    summary.add(String.format("%s at /tp %d ~ %d (scale 6): coarse, %d x %d px of 7.7 km", name,
                            Math.round(m.j() * 6), Math.round(m.i() * 6), C, C));
                }
            }
        }
        try (PrintWriter w = new PrintWriter(out.resolve("summary.txt").toFile())) {
            for (String s : summary) w.println(s);
        }
        for (String s : summary) System.out.println(s);
        System.exit(0);
    }

    private static List<OceanFeatures.Landmark> nearest(List<OceanFeatures.Landmark> all, String type, int n) {
        return all.stream().filter(l -> l.type().equals(type))
                .sorted(Comparator.comparingDouble(l -> Math.hypot(l.i(), l.j()))).limit(n).toList();
    }

    private static long count(List<OceanFeatures.Landmark> all, String type) {
        return all.stream().filter(l -> l.type().equals(type)).count();
    }

    /** Land share, speckle per thousand coast pixels, texture of low ground. */
    private static String stats(float[] e, int H, int W) {
        int land = 0, coast = 0, speck = 0, lowN = 0;
        double lap = 0;
        for (int r = 1; r < H - 1; r++) {
            for (int c = 1; c < W - 1; c++) {
                int k = r * W + c;
                boolean l = e[k] >= 0;
                if (l) land++;
                int same = 0, other = 0;
                for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++) {
                    if (dr == 0 && dc == 0) continue;
                    if ((e[k + dr * W + dc] >= 0) == l) same++; else other++;
                }
                if (other > 0) coast++;
                if (same == 0) speck++;
                if (l && e[k] < 300) {
                    lap += Math.abs(e[k - 1] + e[k + 1] + e[k - W] + e[k + W] - 4 * e[k]);
                    lowN++;
                }
            }
        }
        int n = (H - 2) * (W - 2);
        return String.format("land %.0f%%, speckle %.1f per 1000 coast px, low-ground texture %.2f m",
                100.0 * land / n, coast == 0 ? 0 : 1000.0 * speck / coast, lowN == 0 ? 0 : lap / lowN);
    }

    /** Hypsometric tint with hillshade, like the explorer. */
    private static BufferedImage render(float[] e, int H, int W) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        float rng = 0;
        for (float v : e) rng = Math.max(rng, Math.abs(v));
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float v = e[r * W + c];
                float gx = e[r * W + Math.min(W - 1, c + 1)] - e[r * W + Math.max(0, c - 1)];
                float gz = e[Math.min(H - 1, r + 1) * W + c] - e[Math.max(0, r - 1) * W + c];
                double sh = 0.75 + 0.25 * Math.max(-1, Math.min(1, (-gx - gz) / (H > 200 ? 60.0 : 1500.0)));
                int R, G, B;
                if (v >= 0) {
                    double t = Math.min(1, v / 1500);
                    if (t < 0.35) { R = (int) (40 + 300 * t); G = (int) (170 + 60 * t); B = (int) (80 + 60 * t); }
                    else { R = (int) (145 + 80 * t); G = (int) (230 - 120 * t); B = (int) (100 + 40 * t); }
                } else {
                    double t = Math.min(1, -v / 5000);
                    R = (int) (170 - 140 * t); G = (int) (220 - 130 * t); B = (int) (250 - 90 * t);
                }
                img.setRGB(c, r, (clamp(R * sh) << 16) | (clamp(G * sh) << 8) | clamp(B * sh));
            }
        }
        return img;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, v));
    }

    private static void write(BufferedImage img, Path p) throws Exception {
        ImageIO.write(img, "png", p.toFile());
    }
}
