package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.Arrays;

/**
 * Noise-gated sub-biome patches over the biomes {@link BiomeClassifier} already produces (biomes plan v1.1).
 *
 * <p>Each patch biome lives only inside a parent biome, so the classifier's layout stays as it is: meadow is carved
 * out of plains and grove, birch forest out of forest, old growth out of dense taiga, and so on. Where a patch goes
 * is decided by two things:
 * <ul>
 *   <li><b>Suitability</b> (0..1) from the terrain and climate at the pixel (elevation, slope, temperature,
 *   moisture), with smooth ramps rather than hard cut-offs, so patches thin out toward unsuitable ground instead of
 *   stopping on a line.</li>
 *   <li><b>A patch noise</b> per biome (seeded from the world seed, sampled in world metres, so the same at every
 *   world scale and in the explorer). The pixel joins the patch where its noise is in the top
 *   {@code share × suitability} of the noise's range, so each {@code *_SHARE} constant is the fraction of fully
 *   suitable parent ground the patch takes.</li>
 * </ul>
 * Everything is pointwise (position, seed and the pixel's own values), so tiles always agree at their edges.
 */
final class BiomePatches {

    // ---- Shares: fraction of fully suitable parent ground each patch takes (tune these first) ----
    /**
     * Meadow inside mid-elevation grove and plains. Plan: about 25-30%. Terra's open grassland is almost all grove
     * (its dry-grassland biome): the classifier gives plains only to cold, snow-free, short-season ground, which is
     * rare, so meadows come mostly from the moister half of mid-elevation grove.
     */
    static final float MEADOW_SHARE = 0.28f;
    /** Sunflower plains inside the moister lowland temperate grove (prairie and steppe). */
    static final float SUNFLOWER_SHARE = 0.12f;
    /** Savanna plateau inside high, flat savanna. */
    static final float SAVANNA_PLATEAU_SHARE = 0.45f;
    /** Cherry grove inside moist, cool midland forest and at meadow edges. */
    static final float CHERRY_SHARE = 0.10f;
    /** Dark forest inside the densest temperate forest. */
    static final float DARK_SHARE = 0.20f;
    /** Pale garden inside dark forest patches (rare). */
    static final float PALE_SHARE = 0.07f;
    /**
     * Dappled forest (26.3's autumn poplar woodland) inside the cool end of sparse forest and the mild end of sparse
     * taiga. Vanilla 26.3 puts it in the cool, dry slot between plains and forest (biome temperature 0.6, downfall
     * 0.6, 6 tree tries per chunk to forest's 10), so it is open woodland, not dense forest.
     */
    static final float DAPPLED_SHARE = 0.18f;
    /** Birch forest inside cooler, moister forest. */
    static final float BIRCH_SHARE = 0.24f;
    /** Old growth birch forest inside birch forest on the wettest, flattest ground. */
    static final float OLD_BIRCH_SHARE = 0.40f;
    /** Flower forest inside sunny forest on gentle ground. */
    static final float FLOWER_SHARE = 0.10f;
    /** Old growth taiga (pine or spruce by climate) inside dense taiga. */
    static final float OLD_TAIGA_SHARE = 0.30f;
    /** Bamboo jungle inside the wettest jungle. */
    static final float BAMBOO_SHARE = 0.25f;
    /** Windswept gravelly hills inside the drier windswept hills (barren high ground). */
    static final float GRAVELLY_SHARE = 0.45f;
    /** Windswept forest inside steep sparse mountain taiga. */
    static final float WINDSWEPT_FOREST_SHARE = 0.30f;

    // ---- Patch sizes: noise base wavelength in metres (features come out about half that across) ----
    private static final float MEADOW_SIZE_M = 7500f;
    private static final float SMALL_SIZE_M = 4500f;
    private static final float FOREST_SIZE_M = 6500f;
    private static final float PALE_SIZE_M = 2400f;

    private final Patch meadow, sunflower, cherry, dark, pale, dappled, birch, oldBirch, flower, oldTaiga, bamboo,
            gravelly, windsweptForest, savannaPlateau;
    /** Unshared jitter fields for climate edges (pine/spruce split, sparse jungle, savanna plateau, jagged peaks). */
    private final FastNoiseLite jitterA, jitterB;

    private static volatile BiomePatches cached;

    private final long seed;

    private BiomePatches(long seed) {
        this.seed = seed;
        int s = (int) (seed ^ (seed >>> 32));
        meadow = new Patch(s + 101, MEADOW_SIZE_M);
        sunflower = new Patch(s + 102, SMALL_SIZE_M);
        cherry = new Patch(s + 103, SMALL_SIZE_M);
        dark = new Patch(s + 104, FOREST_SIZE_M);
        pale = new Patch(s + 105, PALE_SIZE_M);
        dappled = new Patch(s + 106, FOREST_SIZE_M);
        birch = new Patch(s + 107, FOREST_SIZE_M);
        oldBirch = new Patch(s + 108, SMALL_SIZE_M);
        flower = new Patch(s + 109, SMALL_SIZE_M);
        oldTaiga = new Patch(s + 110, FOREST_SIZE_M);
        bamboo = new Patch(s + 111, SMALL_SIZE_M);
        gravelly = new Patch(s + 112, FOREST_SIZE_M);
        windsweptForest = new Patch(s + 113, SMALL_SIZE_M);
        savannaPlateau = new Patch(s + 114, FOREST_SIZE_M);
        jitterA = fbm(s + 120, 1f / 2500f, 2);
        jitterB = fbm(s + 121, 1f / 1200f, 2);
    }

    static BiomePatches forSeed(long seed) {
        BiomePatches p = cached;
        if (p == null || p.seed != seed) {
            p = new BiomePatches(seed);
            cached = p;
        }
        return p;
    }

    /**
     * The patch biome for one pixel, or {@code base} unchanged.
     *
     * @param base          the classifier's biome
     * @param xm            world X in metres
     * @param zm            world Z in metres
     * @param altM          elevation above sea level (metres, ≥ 0)
     * @param slope         slope ratio (rise over run)
     * @param temp          temperature (°C, with the classifier's noise)
     * @param treeMoisture  the classifier's tree moisture (aridity × season penalty)
     * @param effMoisture   tree moisture × growing season factor
     * @param bareThreshold slope above which ground is bare
     */
    short apply(short base, float xm, float zm, float altM, float slope, float temp,
                float treeMoisture, float effMoisture, float bareThreshold) {
        switch (base) {
            case BiomeClassifier.GROVE:
            case BiomeClassifier.PLAINS: {
                // Meadow first: it is the big carve-out of mid-elevation grassland, with the odd cherry grove in it.
                float sm = meadowSuit(altM, slope, temp, treeMoisture);
                if (sm > 0f && meadow.in(xm, zm, MEADOW_SHARE * sm)) return cherryInMeadow(xm, zm, altM, slope, temp);
                // Sunflowers on the moister lowland temperate grassland (prairie and steppe).
                float ss = (1f - ramp(altM, 700f, 1100f)) * ramp(temp, 9f, 12f) * (1f - ramp(temp, 21f, 24f))
                        * ramp(treeMoisture, 0.10f, 0.16f) * (1f - ramp(slope, 0.15f, 0.3f));
                if (ss > 0f && sunflower.in(xm, zm, SUNFLOWER_SHARE * ss)) return BiomeClassifier.SUNFLOWER_PLAINS;
                return base;
            }
            case BiomeClassifier.FOREST: {
                float sc = cherrySuit(altM, slope, temp, treeMoisture);
                if (sc > 0f && cherry.in(xm, zm, CHERRY_SHARE * sc)) return BiomeClassifier.CHERRY_GROVE;
                float sd = ramp(effMoisture, 0.85f, 1.05f) * ramp(temp, 11f, 13f) * (1f - ramp(temp, 19f, 21f))
                        * (1f - ramp(slope, 0.3f, 0.5f));
                if (sd > 0f && dark.in(xm, zm, DARK_SHARE * sd)) {
                    return pale.in(xm, zm, PALE_SHARE) ? BiomeClassifier.PALE_GARDEN : BiomeClassifier.DARK_FOREST;
                }
                float sb = (1f - ramp(temp, 15f, 18f)) * ramp(treeMoisture, 0.6f, 0.8f);
                if (sb > 0f && birch.in(xm, zm, BIRCH_SHARE * sb)) {
                    float so = ramp(treeMoisture, 1.0f, 1.3f) * (1f - ramp(slope, 0.15f, 0.3f));
                    return so > 0f && oldBirch.in(xm, zm, OLD_BIRCH_SHARE * so)
                            ? BiomeClassifier.OLD_GROWTH_BIRCH_FOREST : BiomeClassifier.BIRCH_FOREST;
                }
                float sf = ramp(temp, 13f, 15f) * (1f - ramp(slope, 0.15f, 0.3f));
                if (sf > 0f && flower.in(xm, zm, FLOWER_SHARE * sf)) return BiomeClassifier.FLOWER_FOREST;
                return base;
            }
            case BiomeClassifier.FOREST_SPARSE: {
                float sc = cherrySuit(altM, slope, temp, treeMoisture);
                if (sc > 0f && cherry.in(xm, zm, CHERRY_SHARE * sc)) return BiomeClassifier.CHERRY_GROVE;
                float sp = dappledSuit(altM, slope, temp, effMoisture);
                if (sp > 0f && dappled.in(xm, zm, DAPPLED_SHARE * sp)) return BiomeClassifier.DAPPLED_FOREST;
                return base;
            }
            case BiomeClassifier.TAIGA: {
                float so = ramp(effMoisture, 0.9f, 1.1f) * (1f - ramp(slope, 0.35f, 0.55f));
                if (so > 0f && oldTaiga.in(xm, zm, OLD_TAIGA_SHARE * so)) {
                    // Pine on milder, lower ground; spruce where colder or higher. The split wanders by ~2 °C.
                    float mild = temp - altM / 600f + 2f * jitterA.GetNoise(xm, zm);
                    return mild > 3f ? BiomeClassifier.OLD_GROWTH_PINE_TAIGA : BiomeClassifier.OLD_GROWTH_SPRUCE_TAIGA;
                }
                return base;
            }
            case BiomeClassifier.TAIGA_SPARSE: {
                // Mountain sparse taiga on steep ground: windswept forest.
                float sw = ramp(altM, 2300f, 2700f) * ramp(slope, 0.4f, 0.55f);
                if (sw > 0f && windsweptForest.in(xm, zm, WINDSWEPT_FOREST_SHARE * sw)) return BiomeClassifier.WINDSWEPT_FOREST;
                float sp = dappledSuit(altM, slope, temp, effMoisture);
                if (sp > 0f && dappled.in(xm, zm, DAPPLED_SHARE * sp)) return BiomeClassifier.DAPPLED_FOREST;
                return base;
            }
            case BiomeClassifier.JUNGLE: {
                // Sparse jungle at the drier edges (a wandering moisture line), bamboo on the wettest ground.
                if (effMoisture < 0.55f + 0.12f * jitterB.GetNoise(xm, zm)) return BiomeClassifier.SPARSE_JUNGLE;
                float sb = ramp(effMoisture, 1.0f, 1.3f) * (1f - ramp(slope, 0.25f, 0.45f));
                if (sb > 0f && bamboo.in(xm, zm, BAMBOO_SHARE * sb)) return BiomeClassifier.BAMBOO_JUNGLE;
                return base;
            }
            case BiomeClassifier.SAVANNA: {
                // Plateau patches on high, flat savanna; windswept on rough, high savanna.
                float j = jitterA.GetNoise(xm, zm);
                if (altM > 500f + 200f * j && slope > 0.35f) return BiomeClassifier.WINDSWEPT_SAVANNA;
                float sq = ramp(altM, 600f, 1000f) * (1f - ramp(slope, 0.12f, 0.22f));
                if (sq > 0f && savannaPlateau.in(xm, zm, SAVANNA_PLATEAU_SHARE * sq)) return BiomeClassifier.SAVANNA_PLATEAU;
                return base;
            }
            case BiomeClassifier.WINDSWEPT_HILLS: {
                // Barren high ground: gravel where dry (hot or cold), bare windswept hills where moister but too cold.
                float sg = 1f - ramp(treeMoisture, 0.12f, 0.3f);
                if (sg > 0f && gravelly.in(xm, zm, GRAVELLY_SHARE * sg)) return BiomeClassifier.WINDSWEPT_GRAVELLY_HILLS;
                return base;
            }
            case BiomeClassifier.FROZEN_PEAKS: {
                // The sharpest snowy ridges: the steepest bare high ground.
                if (altM > 2800f && slope > bareThreshold * (1.35f + 0.2f * jitterB.GetNoise(xm, zm))) {
                    return BiomeClassifier.JAGGED_PEAKS;
                }
                return base;
            }
            default:
                return base;
        }
    }

    /**
     * Mid elevation, gentle slope, not hot, and the moister part of the grassland. Grove (dry grassland) above 5 °C
     * always has tree moisture under 0.2 (more would grow trees), so the ramp sits inside that range; cold grove and
     * plains are moister and pass.
     */
    private static float meadowSuit(float altM, float slope, float temp, float treeMoisture) {
        return ramp(altM, 400f, 900f) * (1f - ramp(slope, 0.35f, 0.6f)) * ramp(treeMoisture, 0.08f, 0.16f)
                * (1f - ramp(temp, 18f, 23f));
    }

    /** Midland, cool-temperate, moist, gentle. */
    private static float cherrySuit(float altM, float slope, float temp, float treeMoisture) {
        return ramp(altM, 500f, 900f) * ramp(temp, 8f, 10f) * (1f - ramp(temp, 16f, 18f))
                * (1f - ramp(slope, 0.3f, 0.5f)) * ramp(treeMoisture, 0.5f, 0.7f);
    }

    /**
     * Cool-to-mild open woodland below the mountains: about 8-15 °C (the cool end of sparse forest, the mild end of
     * sparse taiga), not the driest woodland, not steep.
     */
    private static float dappledSuit(float altM, float slope, float temp, float effMoisture) {
        return ramp(temp, 7f, 9f) * (1f - ramp(temp, 14f, 16f)) * ramp(effMoisture, 0.35f, 0.5f)
                * (1f - ramp(altM, 1500f, 2000f)) * (1f - ramp(slope, 0.35f, 0.55f));
    }

    /**
     * Meadows with cherry groves in them, as in the Wythers style: inside a meadow patch, midland cool-temperate
     * ground can take a cherry patch where the cherry noise is high (the meadow itself stands in for the moisture
     * test). Otherwise meadow.
     */
    private short cherryInMeadow(float xm, float zm, float altM, float slope, float temp) {
        float sc = ramp(altM, 500f, 900f) * ramp(temp, 8f, 10f) * (1f - ramp(temp, 16f, 18f))
                * (1f - ramp(slope, 0.3f, 0.5f));
        if (sc > 0f && cherry.in(xm, zm, 0.5f * CHERRY_SHARE * sc)) return BiomeClassifier.CHERRY_GROVE;
        return BiomeClassifier.MEADOW;
    }

    static float ramp(float x, float a, float b) {
        float t = (x - a) / (b - a);
        return t <= 0f ? 0f : t >= 1f ? 1f : t;
    }

    private static FastNoiseLite fbm(int seed, float freq, int octaves) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(octaves);
        n.SetFractalLacunarity(2f);
        n.SetFractalGain(PATCH_GAIN);
        return n;
    }

    /** Fractal gain of the patch noises: lower gives smoother patch edges with fewer stray specks. */
    private static final float PATCH_GAIN = 0.4f;

    /**
     * One patch field: a 3-octave noise and the empirical distribution of its values, so a share converts to a
     * threshold exactly. The distribution doesn't depend on the seed, so it is sampled once for all patches.
     */
    private static final class Patch {
        private final FastNoiseLite noise;

        Patch(int seed, float sizeM) {
            noise = fbm(seed, 1f / sizeM, 3);
        }

        /** True if the noise at (x, z) is in the top {@code share} of its range. */
        boolean in(float xm, float zm, float share) {
            if (share <= 0f) return false;
            return noise.GetNoise(xm, zm) > threshold(share);
        }
    }

    /** Sorted samples of a 3-octave OpenSimplex2 FBm field (unit frequency), for share → threshold. */
    private static final float[] QUANTILES;

    static {
        FastNoiseLite n = fbm(1, 1f, 3);
        int side = 160;
        float[] v = new float[side * side];
        // Sample on a jittered grid spanning many wavelengths so the histogram is the field's, not a few blobs'.
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                v[i * side + j] = n.GetNoise(j * 0.731f + 0.17f * (i % 7), i * 0.693f + 0.13f * (j % 5));
            }
        }
        Arrays.sort(v);
        QUANTILES = v;
    }

    static float threshold(float share) {
        if (share >= 1f) return -Float.MAX_VALUE;
        int k = (int) ((1f - share) * (QUANTILES.length - 1));
        return QUANTILES[Math.max(0, Math.min(QUANTILES.length - 1, k))];
    }
}
