package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;

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

        // Archipelagos: the old pass, and model-drawn in each mode being tuned.
        // Variants: old, or mode@finalPull@detail@steerIn@steerOut@steerIsland.
        String[] variants = System.getProperty("terra.archVariants",
                "old,keep@1.0@0.4").split(",");
        for (String variant : variants) {
            TerrainDiffusionConfig.setForTools("oceans.model_archipelagos", variant.equals("old") ? "false" : "true");
            if (!variant.equals("old")) {
                String[] mv = variant.split("@");
                TerrainDiffusionConfig.setForTools("oceans.archipelago_mode", mv[0]);
                TerrainDiffusionConfig.setForTools("oceans.archipelago_final_pull", mv.length > 1 ? mv[1] : "1.0");
                TerrainDiffusionConfig.setForTools("oceans.archipelago_detail", mv.length > 2 ? mv[2] : "0.4");
                TerrainDiffusionConfig.setForTools("oceans.archipelago_steer_in", mv.length > 3 ? mv[3] : "0");
                TerrainDiffusionConfig.setForTools("oceans.archipelago_steer_out", mv.length > 4 ? mv[4] : "0");
                TerrainDiffusionConfig.setForTools("oceans.archipelago_steer_island", mv.length > 5 ? mv[5] : "0");
            }
            try (WorldPipeline p = new WorldPipeline(seed)) {
                for (int k = 0; k < arch.size(); k++) {
                    OceanFeatures.Landmark m = arch.get(k);
                    int i1 = (int) m.i() - win / 2, j1 = (int) m.j() - win / 2;
                    long t0 = System.nanoTime();
                    p.captureStages = true;
                    byte[] tags = new byte[win * win];
                    float[] e = p.get(i1, j1, i1 + win, j1 + win, false, tags)[0];
                    p.captureStages = false;
                    // Stages of a central 256 px patch (model, after the archipelago pass, final; float32 LE) and its tags.
                    dumpPatch(out.resolve("archipelago" + k + "_" + variant.replace("@", "_") + "_stages.bin"), win,
                            p.debugModel, p.debugShaped, e, tags);
                    String name = "archipelago" + k + "_" + variant.replace("@", "_");
                    if (Boolean.getBoolean("terra.dumpLatents") && k < 2) dumpLatents(out.resolve(name + "_latent.bin"), p, i1, j1, win);
                    write(render(e, win, win), out.resolve(name + ".png"));
                    summary.add(String.format("%s at /tp %d ~ %d (scale 6): %s, %.1f s", name, Math.round(m.j() * 6),
                            Math.round(m.i() * 6), stats(e, win, win), (System.nanoTime() - t0) / 1e9));
                    summary.add("    " + speckSources(p.debugModel, p.debugShaped, e, tags, win));
                    summary.add("    " + islands(e, win));
                }
            }
        }
        TerrainDiffusionConfig.setForTools("oceans.model_archipelagos", "true");
        TerrainDiffusionConfig.setForTools("oceans.archipelago_mode", "keep");
        TerrainDiffusionConfig.setForTools("oceans.archipelago_final_pull", "1.0");
        TerrainDiffusionConfig.setForTools("oceans.archipelago_detail", "0.4");
        TerrainDiffusionConfig.setForTools("oceans.archipelago_steer_in", "0");
        TerrainDiffusionConfig.setForTools("oceans.archipelago_steer_out", "0");
        TerrainDiffusionConfig.setForTools("oceans.archipelago_steer_island", "0");

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
                    // Deepest and highest within 12 coarse px (92 km) of the marker, and land share.
                    float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
                    int landN = 0, n = 0;
                    for (int r = C / 2 - 12; r < C / 2 + 12; r++) for (int c = C / 2 - 12; c < C / 2 + 12; c++) {
                        float v = e[r * C + c];
                        lo = Math.min(lo, v); hi = Math.max(hi, v); n++;
                        if (v >= 0) landN++;
                    }
                    summary.add(String.format("%s at /tp %d ~ %d (scale 6): within 92 km deepest %.0f m, highest %.0f m, land %d%%",
                            name, Math.round(m.j() * 6), Math.round(m.i() * 6), lo, hi, 100 * landN / n));
                }
            }
        }
        try (PrintWriter w = new PrintWriter(out.resolve("summary.txt").toFile())) {
            for (String s : summary) w.println(s);
        }
        for (String s : summary) System.out.println(s);
        System.exit(0);
    }

    /**
     * The latent (channels 0-4, unweighted, as the model outputs them) and the decoder's residual (native pixels,
     * signed-sqrt metres) over the window: to find how the detail channels map to the terrain at island scale.
     * Layout: int lH, int lW, int H, int W (LE), then 5*lH*lW floats, then H*W floats.
     */
    private static void dumpLatents(Path file, WorldPipeline p, int i1, int j1, int win) throws Exception {
        int lc = WorldPipeline.LATENT_COMPRESSION;
        int li1 = Math.floorDiv(i1, lc), lj1 = Math.floorDiv(j1, lc), lH = win / lc, lW = win / lc;
        int ri1 = li1 * lc, rj1 = lj1 * lc, H = lH * lc, W = lW * lc;
        FloatTensor lat = p.latents.getSlice(new int[]{0, li1, lj1}, new int[]{6, li1 + lH, lj1 + lW});
        FloatTensor res = p.residual.getSlice(new int[]{0, ri1, rj1}, new int[]{2, ri1 + H, rj1 + W});
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16 + 4 * (5 * lH * lW + H * W)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putInt(lH).putInt(lW).putInt(H).putInt(W);
        int n = lH * lW;
        for (int ch = 0; ch < 5; ch++) for (int q = 0; q < n; q++) {
            float w = lat.data[5 * n + q];
            buf.putFloat(w > 1e-6f ? lat.data[ch * n + q] / w : 0f);
        }
        int m = H * W;
        for (int q = 0; q < m; q++) {
            float w = res.data[m + q];
            buf.putFloat((w > 1e-6f ? res.data[q] / w : 0f) * WorldPipeline.RESIDUAL_STD + WorldPipeline.RESIDUAL_MEAN);
        }
        Files.write(file, buf.array());
    }

    /** Writes a 256x256 patch (rows 150-405, columns 400-655 of the window) of each stage as float32 LE, then the tags. */
    private static void dumpPatch(Path file, int win, float[] model, float[] shaped, float[] fin, byte[] tags) throws Exception {
        int r0 = Math.min(150, win - 256), c0 = Math.min(400, win - 256), N = 256;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(3 * N * N * 4 + N * N).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float[] a : new float[][]{model, shaped, fin})
            for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) buf.putFloat(a == null ? Float.NaN : a[(r0 + r) * win + c0 + c]);
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) buf.put(tags[(r0 + r) * win + c0 + c]);
        Files.write(file, buf.array());
    }

    /** Lone pixels (no 8-neighbour on the same side of sea level) at each stage, and in the final by surface tag. */
    private static String speckSources(float[] model, float[] shaped, float[] fin, byte[] tags, int W) {
        java.util.TreeMap<Integer, Integer> byTag = new java.util.TreeMap<>();
        int nm = 0, ns = 0, nf = 0;
        for (int r = 1; r < W - 1; r++) for (int c = 1; c < W - 1; c++) {
            int k = r * W + c;
            if (model != null && lone(model, k, W)) nm++;
            if (shaped != null && lone(shaped, k, W)) ns++;
            if (lone(fin, k, W)) { nf++; byTag.merge((int) tags[k], 1, Integer::sum); }
        }
        return "lone pixels: model " + nm + ", after archipelago pass " + ns + ", final " + nf + " (by tag " + byTag + ")";
    }

    /** Islands wholly inside the window (land not touching its edge), by area: under 0.1 km2, 0.1-1, 1-10, over 10. */
    private static String islands(float[] e, int W) {
        int[] lab = new int[W * W];
        int[] stack = new int[W * W];
        int[] bins = new int[4];
        List<Float> tops = new ArrayList<>();
        int next = 0;
        for (int s0 = 0; s0 < W * W; s0++) {
            if (e[s0] < 0 || lab[s0] != 0) continue;
            next++;
            int sp = 0, area = 0;
            float top = 0f;
            boolean edge = false;
            stack[sp++] = s0; lab[s0] = next;
            while (sp > 0) {
                int k = stack[--sp]; area++;
                top = Math.max(top, e[k]);
                int r = k / W, c = k % W;
                if (r == 0 || c == 0 || r == W - 1 || c == W - 1) edge = true;
                int[] nb = {k - W, k + W, c > 0 ? k - 1 : -1, c < W - 1 ? k + 1 : -1};
                for (int q : nb) if (q >= 0 && q < W * W && lab[q] == 0 && e[q] >= 0) { lab[q] = next; stack[sp++] = q; }
            }
            if (edge) continue;
            double km2 = area * 0.0009;
            bins[km2 < 0.1 ? 0 : km2 < 1 ? 1 : km2 < 10 ? 2 : 3]++;
            if (km2 >= 1) tops.add(top);
        }
        tops.sort(null);
        String topStr = tops.isEmpty() ? "none" : String.format("%.0f m", tops.get(tops.size() / 2));
        return String.format("islands inside the window: %d (<0.1 km2 %d, 0.1-1 %d, 1-10 %d, >10 %d); median top of those over 1 km2: %s",
                bins[0] + bins[1] + bins[2] + bins[3], bins[0], bins[1], bins[2], bins[3], topStr);
    }

    private static boolean lone(float[] e, int k, int W) {
        boolean l = e[k] >= 0;
        for (int dr = -1; dr <= 1; dr++) for (int dc = -1; dc <= 1; dc++)
            if ((dr | dc) != 0 && (e[k + dr * W + dc] >= 0) == l) return false;
        return true;
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
