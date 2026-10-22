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
 *   <li><b>Sea cliffs</b> on about half of the coasts that rise from the sea: the waves have cut the slope back,
 *       so the shore band is steepened. Each column takes the height of the ground a little further uphill, so
 *       the land keeps its own shape and meets the sea in a face whose height follows the terrain (no flat tops,
 *       no raised rims) and fades in and out along the shore. Valley floors are left alone, and cliffs fade out
 *       around river mouths (from the hydrology), so rivers reach the sea at sea level.</li>
 *   <li><b>Stony shores</b> on scattered stretches of otherwise gentle coast.</li>
 *   <li><b>Wave-cut platforms</b> (round 4) at the foot of about half the sea cliffs: a flat bench of bare rock
 *       30-90 m wide right at sea level, awash, where the waves have planed the old slope off as the cliff retreated.
 *       Only sea floor is raised (never land); the bench fades out at its seaward edge and around river mouths.</li>
 * </ul>
 *
 * <p>The platform pass looks up to {@link #PLATFORM_LOOK_PX} native pixels landward from a sea column, so the tile
 * margin must be that clean.
 */
public final class CoastShaper {

    private static final FastNoiseLite BEACH_WIDTH = noise(7101, 1f / 180f);
    private static final FastNoiseLite CLIFF_MASK = noise(7102, 1f / 900f);
    private static final FastNoiseLite ROCKY_MASK = noise(7103, 1f / 400f);
    private static final FastNoiseLite PLATFORM_ZONE = noise(7104, 1f / 1200f);
    private static final FastNoiseLite PLATFORM_WIDTH = noise(7105, 1f / 250f);
    private static final FastNoiseLite PLATFORM_ROUGH = noise(7106, 1f / 35f);

    /** Share of sea cliffs with a wave-cut platform in front (noise level; lower = more). */
    private static final float PLATFORM_ZONE_LEVEL = 0f;
    /** Native pixels a sea column looks landward for a cliff: the widest bench (3) plus the cliff test's band (2). */
    public static final int PLATFORM_LOOK_PX = 5;
    /** Deepest sea floor (metres) a platform is built over. */
    private static final float PLATFORM_MAX_DEPTH_M = 30f;

    /** Highest elevation (metres) a beach can reach. */
    private static final float BEACH_MAX_M = 6f;
    /** How far (fraction of the 60 m shore band) the slope is cut back at the strongest cliffs. */
    private static final float CLIFF_CUT = 0.6f;
    /** A column raised by more than this (metres) is cliff, not beach. */
    private static final float CLIFF_MIN_RAISE_M = 2f;
    /** A cliff column right at the water raised by more than this (metres) gets a bare stony top. */
    private static final float STONY_RAISE_M = 8f;

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
     * @param riverMouth 0..1 per tile column, (H, W): 1 on and right beside a river's last stretch to the sea, where
     *                 no cliff is built (the river reaches the sea at sea level instead of cutting a gorge through a
     *                 raised shore and falling into the sea); may be null
     */
    public static void apply(float[][] smooth, int cropI, int cropJ, int H, int W, int scale, int i1, int j1,
                             float[] climate, float[] elevOut, short[] biomes, float[] riverMouth) {
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
                // Cliffs only where the land already rises steeply from the sea (about 1 in 5 or more over the
                // shore band); gentle shores keep their slope and get beaches or stony shores instead.
                float rise = (high - Math.max(e, 0f)) / (R * metresPerBlock);
                float mask = smoothstep(-0.05f, 0.25f, CLIFF_MASK.GetNoise(wx, wz)) * smoothstep(0.12f, 0.35f, rise);
                if (riverMouth != null) mask *= 1f - riverMouth[k];
                if (mask > 0f && high > e + CLIFF_MIN_RAISE_M) {
                    // Uphill direction from the smoothed ground, over one native pixel either side.
                    int g = Math.max(1, scale);
                    float gx = (smooth[si][Math.min(upW - 1, sj + g)] - smooth[si][Math.max(0, sj - g)]) / (2f * g);
                    float gz = (smooth[Math.min(upH - 1, si + g)][sj] - smooth[Math.max(0, si - g)][sj]) / (2f * g);
                    float glen = (float) Math.sqrt(gx * gx + gz * gz);
                    if (glen > 1e-3f) {
                        // Cut the slope back: the column at distance d from the sea takes the height found at
                        // d + shift(d) uphill, with shift falling from `cut` at the water to 0 at the band's inner
                        // edge. The mapping is monotone, so a rising slope stays rising: no rim and no plateau.
                        float cut = CLIFF_CUT * R * mask;
                        float shift = cut * Math.max(0f, 1f - d / R);
                        float up = bilinear(smooth, upH, upW, si + gz / glen * shift, sj + gx / glen * shift);
                        float raise = Math.max(0f, up - e);
                        if (raise > 0f) {
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
                            float valley = (band - Math.max(e, 0f)) / Math.max(1f, high);
                            raise *= 1f - smoothstep(0.2f, 0.45f, valley);
                            elevOut[k] += raise;
                            if (raise > CLIFF_MIN_RAISE_M) {
                                if (raise > STONY_RAISE_M && d <= 0.4f * R + 1) biomes[k] = BiomeClassifier.STONY_SHORE;
                                continue;   // no beach or mangrove on a cliff
                            }
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
        platforms(smooth, upH, upW, cropI, cropJ, H, W, scale, i1, j1, elevOut, biomes, riverMouth, metresPerBlock);
    }

    /**
     * Wave-cut platforms: sea columns within a bench's width (30-90 m) of land that rises as a cliff (the same test as
     * the cliffs above: steep enough over the 60 m shore band and inside the cliff mask) are raised to a rough, flat
     * bench at sea level. Only ever raises the sea floor, and only where it is shallow.
     */
    private static void platforms(float[][] smooth, int upH, int upW, int cropI, int cropJ, int H, int W, int scale,
                                  int i1, int j1, float[] elevOut, short[] biomes, float[] riverMouth, float metresPerBlock) {
        int R = 2 * scale, P = 3 * scale;
        for (int r = 0; r < H; r++) {
            int si = cropI + r;
            for (int c = 0; c < W; c++) {
                int sj = cropJ + c, k = r * W + c;
                float e = smooth[si][sj];
                if (e >= 0f || e < -PLATFORM_MAX_DEPTH_M) continue;
                float wx = (j1 + c) * metresPerBlock, wz = (i1 + r) * metresPerBlock;
                float mask = platformMask(wx, wz);
                if (riverMouth != null) mask *= 1f - riverMouth[k];
                if (mask <= 0f) continue;
                float width = (1f + 2f * (0.5f + 0.5f * PLATFORM_WIDTH.GetNoise(wx, wz))) * scale;   // 30-90 m
                float dl = landDistance(smooth, upH, upW, si, sj, Math.min(P, (int) Math.ceil(width) + 1));
                if (dl == Float.MAX_VALUE || dl > width) continue;
                // Is the land behind a cliff? The same rise the cliff test uses, seen from here.
                float high = highest(smooth, upH, upW, si, sj, (int) Math.ceil(dl) + R, 0f);
                float rise = high / (R * metresPerBlock);
                float w = mask * smoothstep(0.12f, 0.35f, rise);
                if (w <= 0f) continue;
                // Flat at sea level, a little rough, fading out over the outer quarter of the bench.
                float top = -0.6f + 1.4f * PLATFORM_ROUGH.GetNoise(wx, wz);
                w *= 1f - smoothstep(0.75f * width, width, dl);
                float raised = e + (top - e) * w;
                if (raised > elevOut[k]) elevOut[k] = raised;
                if (w > 0.5f && elevOut[k] > -3f) biomes[k] = BiomeClassifier.STONY_SHORE;   // bare rock, awash
            }
        }
    }

    /** 0..1: how strongly wave-cut platforms form at world position (metres): the platform zones times the cliff mask. */
    public static float platformMask(float wx, float wz) {
        return smoothstep(PLATFORM_ZONE_LEVEL, PLATFORM_ZONE_LEVEL + 0.2f, PLATFORM_ZONE.GetNoise(wx, wz))
                * smoothstep(-0.05f, 0.25f, CLIFF_MASK.GetNoise(wx, wz));
    }

    /** Blocks to the nearest land column (at or above sea level) within radius R, or {@link Float#MAX_VALUE}. */
    private static float landDistance(float[][] a, int h, int w, int si, int sj, int R) {
        float best = Float.MAX_VALUE;
        for (int dy = -R; dy <= R; dy++) {
            int y = si + dy;
            if (y < 0 || y >= h) continue;
            for (int dx = -R; dx <= R; dx++) {
                int x = sj + dx;
                if (x < 0 || x >= w) continue;
                int d2 = dx * dx + dy * dy;
                if (d2 > R * R || d2 >= best) continue;
                if (a[y][x] >= 0f) best = d2;
            }
        }
        return best == Float.MAX_VALUE ? best : (float) Math.sqrt(best);
    }

    /** Bilinear sample of a [h][w] grid at fractional (row, column), clamped at the edges. */
    private static float bilinear(float[][] a, int h, int w, float r, float c) {
        r = Math.max(0f, Math.min(h - 1f, r));
        c = Math.max(0f, Math.min(w - 1f, c));
        int r0 = (int) r, c0 = (int) c, r1 = Math.min(h - 1, r0 + 1), c1 = Math.min(w - 1, c0 + 1);
        float fr = r - r0, fc = c - c0;
        return (a[r0][c0] * (1 - fc) + a[r0][c1] * fc) * (1 - fr) + (a[r1][c0] * (1 - fc) + a[r1][c1] * fc) * fr;
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
                || b == BiomeClassifier.FROZEN_PEAKS || b == BiomeClassifier.JAGGED_PEAKS
                || b == RegionalBiomes.TUNDRA || b == RegionalBiomes.POLAR_DESERT;
    }

    private static float smoothstep(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }
}
