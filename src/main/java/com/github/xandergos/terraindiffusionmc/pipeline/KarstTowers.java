package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Karst towers (biomes plan v1.4 round C): clusters of sheer limestone towers 50-200 m tall rising from hot, wet
 * lowland (Guilin, Yangshuo) and out of shallow warm sea (Ha Long Bay). One candidate per 140 m cell of the world grid,
 * at a random point in it, in karst zones; it stands if the climate at its centre is tropical and wet, the smooth
 * ground there is between 20 m under the sea and 450 m up, and no river runs within reach (rivers keep their valleys
 * between the towers). A tower is 40-120 m across with a ragged outline, near-vertical sides and a rounded top; it only
 * raises, and every test is made at its centre on world data, so tiles agree.
 */
public final class KarstTowers {

    private static final FastNoiseLite ZONE = noise(7501, 1f / 5000f, 2);
    private static final FastNoiseLite EDGE = noise(7502, 1f / 25f, 2);
    private static final FastNoiseLite TOP = noise(7503, 1f / 40f, 2);

    /** Zone level (higher = rarer), chance per cell, cell size (m). */
    static final float ZONE_LEVEL = 0.1f, CHANCE = 0.6f, CELL_M = 140f;

    /** Whether a river runs within {@code px} native pixels of native point (i, j). */
    public interface RiverNear {
        boolean test(float i, float j, float px);
    }

    private KarstTowers() {
    }

    private static FastNoiseLite noise(int seed, float freqPerMetre, int octaves) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freqPerMetre);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(octaves);
        return n;
    }

    /** Whether any column of the tile may lie in a karst zone (the zone noise moves little over 16 blocks). */
    public static boolean mayApply(int i1, int j1, int H, int W, float metresPerBlock) {
        int step = Math.max(1, Math.min(16, (int) (80f / metresPerBlock)));
        for (int r = -step; r <= H + step; r += step) {
            for (int c = -step; c <= W + step; c += step) {
                if (ZONE.GetNoise((j1 + c) * metresPerBlock, (i1 + r) * metresPerBlock) > ZONE_LEVEL - 0.05f) return true;
            }
        }
        return false;
    }

    public static void apply(float[] elev, short[] biomes, AridLandforms.Field field, RiverNear rivers, int i1, int j1,
                             int H, int W, int scale) {
        float metresPerBlock = WorldPipelineModelConfig.nativeResolution() / scale;
        int cell = Math.max(2, Math.round(CELL_M / metresPerBlock));
        float maxR = 60f * 1.2f / metresPerBlock + 1f;
        int m = (int) Math.ceil(maxR);
        for (int gi = Math.floorDiv(i1 - m, cell); gi <= Math.floorDiv(i1 + H + m, cell); gi++) {
            for (int gj = Math.floorDiv(j1 - m, cell); gj <= Math.floorDiv(j1 + W + m, cell); gj++) {
                long h = AridLandforms.hash(gi, gj, 0xCA25L);
                if (AridLandforms.unit(h, 1) > CHANCE) continue;
                float radius = (20f + 40f * AridLandforms.unit(h, 2)) / metresPerBlock;
                float inner = Math.max(0f, cell - 2f * radius * 1.2f);
                float cz = gi * cell + radius * 1.2f + inner * AridLandforms.unit(h, 3);
                float cx = gj * cell + radius * 1.2f + inner * AridLandforms.unit(h, 4);
                if (cz + maxR < i1 || cz - maxR > i1 + H || cx + maxR < j1 || cx - maxR > j1 + W) continue;
                float wx = cx * metresPerBlock, wz = cz * metresPerBlock;
                if (ZONE.GetNoise(wx, wz) < ZONE_LEVEL) continue;
                float t = field.temp(cz, cx), p = field.precip(cz, cx);
                if (t < 20f || p < 1100f) continue;
                float g0 = field.ground(cz, cx);
                if (g0 < -20f || g0 > 450f) continue;
                if (rivers != null && rivers.test(cz / scale, cx / scale, radius * 1.2f / scale + 2f)) continue;
                float height = 50f + 150f * AridLandforms.unit(h, 5) * (0.4f + 0.6f * AridLandforms.unit(h, 6));
                float top = Math.max(g0, 0f) + height;
                int rr0 = (int) Math.floor(cz - radius * 1.2f) - i1, rr1 = (int) Math.ceil(cz + radius * 1.2f) - i1;
                int cc0 = (int) Math.floor(cx - radius * 1.2f) - j1, cc1 = (int) Math.ceil(cx + radius * 1.2f) - j1;
                for (int r = Math.max(0, rr0); r <= Math.min(H - 1, rr1); r++) {
                    for (int c = Math.max(0, cc0); c <= Math.min(W - 1, cc1); c++) {
                        float bx = j1 + c + 0.5f, bz = i1 + r + 0.5f;
                        float rEff = radius * (1f + 0.18f * EDGE.GetNoise(bx * metresPerBlock, bz * metresPerBlock));
                        float d = (float) Math.sqrt((bx - cx) * (bx - cx) + (bz - cz) * (bz - cz));
                        if (d >= rEff) continue;
                        float f = d / rEff;
                        // Rounded top with knobs, sheer sides over the outer fifth down to the ground at the centre.
                        float v = top - 0.18f * height * f * f + 6f * TOP.GetNoise(bx * metresPerBlock, bz * metresPerBlock);
                        v -= (v - g0) * smoothstep(0.8f, 1f, f);
                        int k = r * W + c;
                        if (v > elev[k]) {
                            if (v - elev[k] > 4f) biomes[k] = RegionalBiomes.KARST;
                            elev[k] = v;
                        }
                    }
                }
            }
        }
    }

    private static float smoothstep(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }
}
