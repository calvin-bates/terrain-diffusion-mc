package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Shapes and classifies the shore of a tile at block resolution: beaches, stony shores, sea cliffs and mangroves.
 *
 * <p>Works on the smoothed, upsampled elevation, which must carry a clean margin of at least {@code 3 * scale}
 * blocks around the tile. A column looks at the ground within 60 m (two native pixels), and compares itself with
 * neighbours within 30 m that look 60 m further, so tiles agree across their edges. 60 m is also about the width
 * of a real beach, so the coast reads the same at every scale.
 *
 * <ul>
 *   <li><b>Beaches</b> on low, gentle shores, a noise-varied 20–60 m wide. Snowy beaches (gravel) where it is
 *       freezing.</li>
 *   <li><b>Mangroves</b> instead of beaches on hot, wet, low coasts.</li>
 *   <li><b>Sea cliffs</b> where the land within 60 m of the sea rises well above it, on about half of such coasts:
 *       the shore band is raised toward the nearby high ground so the land meets the sea in a wall, topped with
 *       stony shore. Valley floors are left alone, so rivers still reach the sea through gorges and coves.</li>
 *   <li><b>Stony shores</b> on scattered stretches of otherwise gentle coast.</li>
 * </ul>
 */
public final class CoastShaper {

    private static final FastNoiseLite BEACH_WIDTH = noise(7101, 1f / 180f);
    private static final FastNoiseLite CLIFF_MASK = noise(7102, 1f / 900f);
    private static final FastNoiseLite ROCKY_MASK = noise(7103, 1f / 400f);

    /** Highest elevation (metres) a beach can reach. */
    private static final float BEACH_MAX_M = 6f;
    /** Minimum height (metres) of the ground near the shore for a cliff. */
    private static final float CLIFF_MIN_M = 15f;

    private CoastShaper() {
    }

    private static FastNoiseLite noise(int seed, float freqPerMetre) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freqPerMetre);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(2);
        return n;
    }

    /**
     * @param smooth   smoothed elevation (metres) at block resolution including the margin, [upH][upW]
     * @param cropI    row in {@code smooth} of the tile's first row
     * @param cropJ    column in {@code smooth} of the tile's first column
     * @param H        tile height in blocks
     * @param W        tile width in blocks
     * @param scale    blocks per native pixel
     * @param i1       world block Z of the tile's first row
     * @param j1       world block X of the tile's first column
     * @param climate  (4, H, W): temperature, temperature seasonality, precipitation, precipitation CV; may be null
     * @param elevOut  final elevation (metres) of the tile, (H, W); cliffs raise it in place
     * @param biomes   biome ids of the tile, (H, W); shore biomes are written in place
     */
    public static void apply(float[][] smooth, int cropI, int cropJ, int H, int W, int scale, int i1, int j1,
                             float[] climate, float[] elevOut, short[] biomes) {
        int upH = smooth.length, upW = smooth[0].length;
        int R = 2 * scale;                       // look-around radius in blocks (60 m)
        int B = scale;                           // radius for comparing a column with its along-shore neighbours
        float metresPerBlock = WorldPipelineModelConfig.nativeResolution() / scale;
        int HW = H * W;

        // Distance to the sea (blocks, up to R) for the tile plus a margin of B, so every tile column can look at
        // its neighbours' distances. Needs R + B = 3 * scale of clean margin around the tile.
        int dH = H + 2 * B, dW = W + 2 * B;
        float[] dist = new float[dH * dW];
        for (int r = 0; r < dH; r++) {
            for (int c = 0; c < dW; c++) {
                int si = cropI - B + r, sj = cropJ - B + c;
                dist[r * dW + c] = smooth[si][sj] < 0f ? 0f : seaDistance(smooth, upH, upW, si, sj, R);
            }
        }

        for (int r = 0; r < H; r++) {
            int si = cropI + r;
            for (int c = 0; c < W; c++) {
                int sj = cropJ + c;
                int k = r * W + c;
                float e = smooth[si][sj];
                if (e < 0f || BiomeClassifier.isOceanBiome(biomes[k])) continue;
                float d = dist[(r + B) * dW + (c + B)];
                if (d == Float.MAX_VALUE) continue;        // not within 60 m of the sea

                float high = highest(smooth, upH, upW, si, sj, R, e);
                float wx = (j1 + c) * metresPerBlock, wz = (i1 + r) * metresPerBlock;
                float temp = climate != null ? climate[k] : 15f;
                float precip = climate != null ? climate[2 * HW + k] : 600f;

                // ---- Sea cliffs ----
                if (high > CLIFF_MIN_M) {
                    float mask = smoothstep(-0.05f, 0.25f, CLIFF_MASK.GetNoise(wx, wz));
                    float near = smoothstep(R, R * 0.5f, d);
                    if (mask > 0f && near > 0f) {
                        // A valley floor sits well below the ground at the same distance from the sea beside it;
                        // leave those low so rivers keep their mouths (they become coves and gorges).
                        float band = Math.max(e, 0f);
                        for (int dy = -B; dy <= B; dy++) {
                            for (int dx = -B; dx <= B; dx++) {
                                if (dx * dx + dy * dy > B * B) continue;
                                float dq = dist[(r + B + dy) * dW + (c + B + dx)];
                                if (dq == 0f || dq == Float.MAX_VALUE || Math.abs(dq - d) > 1.5f) continue;
                                band = Math.max(band, smooth[si + dy][sj + dx]);
                            }
                        }
                        float valley = (band - Math.max(e, 0f)) / high;
                        float w = mask * near * (1f - smoothstep(0.2f, 0.45f, valley));
                        if (w > 0.02f) {
                            elevOut[k] = Math.max(elevOut[k], elevOut[k] + (high - elevOut[k]) * w);
                            if (w > 0.5f && d <= 0.4f * R + 1) biomes[k] = BiomeClassifier.STONY_SHORE;
                            if (w > 0.3f) continue;
                        }
                    }
                }

                // ---- Mangroves ----
                boolean hotWet = temp >= 24f && precip >= 1200f;
                if (hotWet && e < 4f) {
                    biomes[k] = BiomeClassifier.MANGROVE_SWAMP;
                    continue;
                }

                // ---- Beaches and stony shores ----
                float width = (0.7f + 1.3f * (0.5f + 0.5f * BEACH_WIDTH.GetNoise(wx, wz))) * scale;   // 20–60 m
                if (d > width || e > BEACH_MAX_M) continue;
                // Steep ground right behind the shore makes a rocky shore rather than a beach.
                float steep = (high - e) / (R * metresPerBlock);
                if (steep > 0.35f || ROCKY_MASK.GetNoise(wx, wz) > 0.5f) {
                    biomes[k] = BiomeClassifier.STONY_SHORE;
                } else if (temp < 0f || isSnowy(biomes[k])) {
                    biomes[k] = BiomeClassifier.SNOWY_BEACH;
                } else {
                    biomes[k] = BiomeClassifier.BEACH;
                }
            }
        }
    }

    /** Blocks to the nearest sea column within radius R, or {@link Float#MAX_VALUE}. */
    private static float seaDistance(float[][] a, int h, int w, int si, int sj, int R) {
        float best = Float.MAX_VALUE;
        for (int dy = -R; dy <= R; dy++) {
            int y = si + dy;
            if (y < 0 || y >= h) continue;
            for (int dx = -R; dx <= R; dx++) {
                int x = sj + dx;
                if (x < 0 || x >= w) continue;
                int d2 = dx * dx + dy * dy;
                if (d2 > R * R || d2 >= best) continue;
                if (a[y][x] < 0f) best = d2;
            }
        }
        return best == Float.MAX_VALUE ? best : (float) Math.sqrt(best);
    }

    /** Highest ground within radius R (at least {@code floor}). */
    private static float highest(float[][] a, int h, int w, int si, int sj, int R, float floor) {
        float high = floor;
        for (int dy = -R; dy <= R; dy++) {
            int y = si + dy;
            if (y < 0 || y >= h) continue;
            for (int dx = -R; dx <= R; dx++) {
                int x = sj + dx;
                if (x < 0 || x >= w || dx * dx + dy * dy > R * R) continue;
                if (a[y][x] > high) high = a[y][x];
            }
        }
        return high;
    }

    private static boolean isSnowy(short b) {
        return b == BiomeClassifier.SNOWY_PLAINS || b == BiomeClassifier.SNOWY_TAIGA
                || b == BiomeClassifier.SNOWY_TAIGA_SPARSE || b == BiomeClassifier.SNOWY_SLOPES
                || b == BiomeClassifier.FROZEN_PEAKS;
    }

    private static float smoothstep(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }
}
