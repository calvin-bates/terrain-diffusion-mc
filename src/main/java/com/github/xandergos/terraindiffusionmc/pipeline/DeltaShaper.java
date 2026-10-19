package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.hydrology.DeltaPlan;

import java.util.List;

/**
 * Builds river deltas into a tile at block resolution: the lobe of new land a river has pushed out into the sea, with
 * marsh between its channels, ponds, low natural levees along the channels, a sandy rim on its front and sand bars off
 * the channel mouths. The channels themselves are cut afterwards by the river carver, from the same {@link DeltaPlan}.
 *
 * <p>The lobe only builds over shallow water and never lowers existing ground, so it reads as part of the coast. It builds fully over water up to 25 m deep, fading out by 60 m. Delta land gets marsh biomes: mangrove swamp where it is hot and wet, swamp where it
 * is mild, the land's own biome where it is cold; the rim and the bars get beach. Every column depends only on the
 * plans and its own position, so tiles agree.
 */
public final class DeltaShaper {

    private static final FastNoiseLite PONDS = noise(7301, 1f / 5f);
    private static final FastNoiseLite ROUGH = noise(7302, 1f / 3f);

    /** Lobe height (metres) near the apex and at the front; the last stretch before the front slopes to the rim. */
    private static final float APEX_M = 1.3f, FRONT_M = 0.4f, RIM_M = -0.6f, RIM_START = 0.92f;
    /** The front slopes down into the sea at this many metres per native pixel. */
    private static final float FRONT_SLOPE_M = 0.8f;

    private DeltaShaper() {
    }

    private static FastNoiseLite noise(int seed, float freq) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(2);
        return n;
    }

    /**
     * @param plans   deltas that may reach this tile
     * @param elev    tile elevation (metres), (H, W); raised in place
     * @param biomes  tile biome ids, (H, W); delta land is re-assigned in place
     * @param climate (4, H, W): temperature, temperature seasonality, precipitation, precipitation CV; may be null
     */
    public static void apply(List<DeltaPlan> plans, float[] elev, short[] biomes, float[] climate,
                             int i1, int j1, int H, int W, int scale) {
        if (plans.isEmpty()) return;
        int HW = H * W;
        for (int r = 0; r < H; r++) {
            float z = (i1 + r + 0.5f) / scale;
            for (int c = 0; c < W; c++) {
                int k = r * W + c;
                float orig = elev[k];
                if (orig > 12f || orig < -60f) continue;
                float x = (j1 + c + 0.5f) / scale;
                float best = Float.NEGATIVE_INFINITY;
                boolean lobe = false, sand = false;
                for (DeltaPlan p : plans) {
                    if (!p.near(x, z, 6f)) continue;
                    float u = p.lobeU(x, z);
                    float target = Float.NEGATIVE_INFINITY;
                    boolean onLobe = false, rim = false;
                    if (u < 1f) {
                        onLobe = true;
                        if (u > RIM_START) {
                            target = FRONT_M + (RIM_M - FRONT_M) * (u - RIM_START) / (1f - RIM_START);
                            rim = true;
                        } else {
                            target = APEX_M + (FRONT_M - APEX_M) * u + 0.3f * ROUGH.GetNoise(x, z);
                            float cd = p.channelDistance(x, z);
                            if (cd < 2.5f) target += 1.0f * (1f - cd / 2.5f);                    // natural levees
                            else if (cd > 3.5f && u > 0.15f && PONDS.GetNoise(x, z) > 0.35f) target = -0.8f;   // ponds
                        }
                    } else if (u < 1.8f) {
                        float rr = (float) Math.hypot(x - p.apexX, z - p.apexZ);
                        target = RIM_M - (rr - rr / u) * FRONT_SLOPE_M;                       // delta front
                    }
                    // Sand bars just off each channel mouth.
                    for (int b = 0; b < p.barX.length; b++) {
                        float dx = x - p.barX[b], dz = z - p.barZ[b];
                        float along = dx * p.barDX[b] + dz * p.barDZ[b], across = -dx * p.barDZ[b] + dz * p.barDX[b];
                        float q = (along / 2.2f) * (along / 2.2f) + (across / 3.5f) * (across / 3.5f);
                        if (q < 1f && 0.6f * (1f - q) > target) { target = 0.6f * (1f - q); rim = true; }
                    }
                    if (target > best) { best = target; lobe = onLobe; sand = rim; }
                }
                if (best == Float.NEGATIVE_INFINITY) continue;
                // Builds only over shallow water, and never lowers what is there.
                float w = smoothstep(-60f, -25f, orig);
                float nv = orig + (best - orig) * w;
                boolean raised = nv > orig + 0.01f;
                if (raised) elev[k] = nv;
                if (!(raised || (lobe && orig < 4f))) continue;
                if (elev[k] < 0f && !lobe) continue;                 // still sea: keep the ocean biome
                if (sand && orig >= 1f) sand = false;                // the rim is sand only where it meets the sea
                float temp = climate != null ? climate[k] : 15f;
                float precip = climate != null ? climate[2 * HW + k] : 800f;
                if (sand) {
                    biomes[k] = temp < 0f ? BiomeClassifier.SNOWY_BEACH : BiomeClassifier.BEACH;
                } else if (temp >= 24f && precip >= 1200f) {
                    biomes[k] = BiomeClassifier.MANGROVE_SWAMP;
                } else if (temp >= 2f) {
                    biomes[k] = BiomeClassifier.SWAMP;
                } else if (BiomeClassifier.isOceanBiome(biomes[k])) {
                    biomes[k] = BiomeClassifier.SNOWY_BEACH;          // cold delta land that was sea: bare and snowy
                }
            }
        }
    }

    private static float smoothstep(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }
}
