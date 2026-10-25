package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Raised marine terraces (biomes plan v1.4): on rising coasts, old wave-cut platforms lifted out of the sea one after
 * another, so the land behind the shore climbs in a staircase of broad, nearly flat benches with steep risers between
 * them (the coast of Santa Cruz, California; Kaikoura; the Huon Peninsula).
 *
 * <p>Applied to the final ground of a tile, block by block, as a remapping of height: between two terrace levels the
 * lower ~80% of the height range is squeezed into a gently rising bench and the rest becomes the riser. The mapping
 * only depends on the height and slowly varying noise, and never changes the order of heights at a point, so slopes
 * still run the same way (no pits or rims), and ground below the first level (beaches, cliffs' feet) is untouched. It
 * fades in over terrace zones and out with distance from the sea (the hydrology region's sea distance, measured on
 * the model's ground, the same for every tile) and above the top level.
 */
public final class MarineTerraces {

    private static final FastNoiseLite ZONE = noise(7201, 1f / 4500f, 2);
    private static final FastNoiseLite LEVELS = noise(7202, 1f / 2500f, 1);
    private static final FastNoiseLite SPACING = noise(7203, 1f / 3000f, 1);

    /** Zone noise level (higher = rarer). */
    static final float ZONE_LEVEL = 0.2f;
    /** Terraces reach this far inland (native pixels, ~1.35 km), fading out over the last 40%. */
    static final float REACH_PX = 45f;
    /** Number of terrace levels. */
    private static final int LEVEL_COUNT = 5;
    /** Share of each step's height range that is bench rather than riser, and how much of the step the bench climbs. */
    private static final float BENCH = 0.8f, BENCH_RISE = 0.1f;

    private MarineTerraces() {
    }

    private static FastNoiseLite noise(int seed, float freqPerMetre, int octaves) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freqPerMetre);
        if (octaves > 1) {
            n.SetFractalType(FastNoiseLite.FractalType.FBm);
            n.SetFractalOctaves(octaves);
        }
        return n;
    }

    /** Whether any column of the tile [i1, i1+H) x [j1, j1+W) (world blocks) may lie in a terrace zone. */
    public static boolean mayApply(int i1, int j1, int H, int W, float metresPerBlock) {
        // The zone noise changes by well under 0.05 over 16 blocks (~80 m at the default scale).
        int step = Math.max(1, Math.min(16, (int) (80f / metresPerBlock)));
        for (int r = 0; r <= H; r += step) {
            for (int c = 0; c <= W; c += step) {
                if (ZONE.GetNoise((j1 + c) * metresPerBlock, (i1 + r) * metresPerBlock) > ZONE_LEVEL - 0.05f) return true;
            }
        }
        return false;
    }

    /**
     * Terraces the tile's ground in place.
     *
     * @param elev     final ground (metres), (H, W), world block (i1 + r, j1 + c) at r * W + c
     * @param seaDist  per column, native pixels to the sea (capped), (H, W)
     * @param biomes   biome ids, (H, W): ocean columns and rivers' and lakes' beds are left alone
     */
    public static void apply(float[] elev, float[] seaDist, short[] biomes, int i1, int j1, int H, int W,
                             float metresPerBlock) {
        float[] lv = new float[LEVEL_COUNT + 1];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int k = r * W + c;
                float e = elev[k];
                if (e < 3f || BiomeClassifier.isOceanBiome(biomes[k])) continue;
                float wx = (j1 + c) * metresPerBlock, wz = (i1 + r) * metresPerBlock;
                float zone = smoothstep(ZONE_LEVEL, ZONE_LEVEL + 0.12f, ZONE.GetNoise(wx, wz));
                if (zone <= 0f) continue;
                float near = 1f - smoothstep(0.6f * REACH_PX, REACH_PX, seaDist[k]);
                if (near <= 0f) continue;
                // Levels: the first 6-14 m up, then steps of 15-35 m, a little taller each time.
                float first = 10f + 4f * LEVELS.GetNoise(wx, wz);
                float step = 25f + 10f * SPACING.GetNoise(wx, wz);
                lv[0] = first;
                for (int q = 1; q <= LEVEL_COUNT; q++) lv[q] = lv[q - 1] + step * (1f + 0.15f * q);
                float top = lv[LEVEL_COUNT];
                if (e <= first || e >= top + 25f) continue;
                float w = zone * near * (1f - smoothstep(top - 10f, top + 25f, e));
                elev[k] = e + (remap(e, lv) - e) * w;
            }
        }
    }

    /** The terraced height for height e between the levels (monotone; identity below the first and above the last). */
    static float remap(float e, float[] lv) {
        for (int q = 0; q + 1 < lv.length; q++) {
            if (e >= lv[q + 1]) continue;
            float span = lv[q + 1] - lv[q];
            float f = (e - lv[q]) / span;
            float g = f < BENCH ? BENCH_RISE * f / BENCH
                    : BENCH_RISE + (1f - BENCH_RISE) * smoothstep(BENCH, 1f, f);
            return lv[q] + span * g;
        }
        return e;
    }

    private static float smoothstep(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }
}
