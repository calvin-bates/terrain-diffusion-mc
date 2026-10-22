package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Rule-based biome classifier port of _classify_biome in minecraft_api.py.
 *
 * <p>Uses fixed-seed FastNoiseLite instances for climate and elevation noise perturbations.
 * Biome IDs match the Python server's _BIOME_ID mapping.
 */
public final class BiomeClassifier {

    // Fixed-seed noise instances (matching Python's module-level _TEMP_NOISE etc.)
    private static final FastNoiseLite TEMP_NOISE, TEMP_NOISE_FINE;
    private static final FastNoiseLite PRECIP_NOISE;
    private static final FastNoiseLite SNOW_NOISE, SNOW_NOISE_FINE;

    static {
        TEMP_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        TEMP_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
        PRECIP_NOISE = makeFnl(12345, 1f/500f, 5, 2f, 0.5f);
        SNOW_NOISE = makeFnl(12345, 1f/500f, 3, 2f, 0.5f);
        SNOW_NOISE_FINE = makeFnl(54321, 1f/128f, 2, 2f, 0.5f);
    }

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    // Biome IDs
    static final short PLAINS = 1, SNOWY_PLAINS = 3, DESERT = 5, SWAMP = 6;
    static final short FOREST = 8, TAIGA = 15, SNOWY_TAIGA = 16, SAVANNA = 17;
    static final short WINDSWEPT_HILLS = 19, JUNGLE = 23, BADLANDS = 26, MEADOW = 29;
    static final short GROVE = 31, SNOWY_SLOPES = 32, FROZEN_PEAKS = 33, STONY_PEAKS = 35;
    static final short WARM_OCEAN = 41, OCEAN = 44, COLD_OCEAN = 46, FROZEN_OCEAN = 48;
    static final short LUKEWARM_OCEAN = 42, DEEP_LUKEWARM_OCEAN = 43, DEEP_OCEAN = 45;
    static final short DEEP_COLD_OCEAN = 47, DEEP_FROZEN_OCEAN = 49;

    /** Upwelling (0..1) above which a rainless, treeless coast at 10-20 °C is desert rather than dry grassland. */
    static final float UPWELLING_DESERT = 0.3f;
    /** Water deeper than this (metres) gets the deep ocean variant: roughly past the continental slope's top. */
    static final float DEEP_OCEAN_M = 600f;
    /** Warm water shallower than this (metres) is reef water (warm ocean, with coral); deeper warm water is lukewarm. */
    static final float REEF_DEPTH_M = 80f;
    static final short FOREST_SPARSE = 108, TAIGA_SPARSE = 115, SNOWY_TAIGA_SPARSE = 116;
    /**
     * Patch biomes (biomes plan v1.1), placed by {@link BiomePatches} inside the biomes above. Ids follow the vanilla
     * biome order the rest of the table uses; pale garden and dappled forest are new in that order (36, 37).
     */
    static final short SUNFLOWER_PLAINS = 2, FLOWER_FOREST = 9, BIRCH_FOREST = 10, DARK_FOREST = 11;
    static final short OLD_GROWTH_BIRCH_FOREST = 12, OLD_GROWTH_PINE_TAIGA = 13, OLD_GROWTH_SPRUCE_TAIGA = 14;
    static final short SAVANNA_PLATEAU = 18, WINDSWEPT_GRAVELLY_HILLS = 20, WINDSWEPT_FOREST = 21;
    static final short WINDSWEPT_SAVANNA = 22, SPARSE_JUNGLE = 24, BAMBOO_JUNGLE = 25, CHERRY_GROVE = 30;
    static final short JAGGED_PEAKS = 34, PALE_GARDEN = 36, DAPPLED_FOREST = 37;
    /** Shore biomes, assigned by {@link CoastShaper} after classification. */
    static final short BEACH = 130, SNOWY_BEACH = 131, STONY_SHORE = 132, MANGROVE_SWAMP = 134;

    /**
     * Classify biomes for a grid of pixels.
     *
     * @param elev       elevation in meters, (H, W) row-major
     * @param climate    climate data (5, H, W) row-major or null
     * @param i0         top-left row in world space (for noise sampling)
     * @param j0         top-left col in world space
     * @param elevPadded elevation with 1-pixel padding, (H+2, W+2) row-major
     * @param H          height
     * @param W          width
     * @param pixelSizeM physical size of one pixel in meters
     * @param seed       world seed (places the patch biomes, see {@link BiomePatches})
     * @return short array (H, W) with biome IDs
     */
    public static short[] classify(float[] elev, float[] climate, int i0, int j0,
                                    float[] elevPadded, int H, int W, float pixelSizeM, long seed) {
        return classify(elev, climate, i0, j0, elevPadded, H, W, pixelSizeM, seed, null, null);
    }

    /**
     * {@link #classify(float[], float[], int, int, float[], int, int, float, long)} with the river network: for each
     * pixel the distance (native pixels) to the nearest river or main river and to the nearest main river, or null
     * when unknown (then no riverside or floodplain forests).
     */
    public static short[] classify(float[] elev, float[] climate, int i0, int j0,
                                    float[] elevPadded, int H, int W, float pixelSizeM, long seed,
                                    float[] riverDist, float[] mainDist) {
        short[] out = new short[H * W];
        for (int i = 0; i < H * W; i++) out[i] = PLAINS;

        if (climate == null || climate.length < 4 * H * W) {
            return out;
        }

        // Generate Perlin noise perturbations
        float[] tempNoise = new float[H * W];
        float[] precipNoiseFact = new float[H * W];
        float[] snowNoise = new float[H * W];

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float nx = j0 + c, ny = i0 + r;
                float tnc = TEMP_NOISE.GetNoise(nx, ny);
                float tnf = TEMP_NOISE_FINE.GetNoise(nx, ny);
                tempNoise[idx] = 0.4f * tnc + 0.2f * tnf;

                float pn = PRECIP_NOISE.GetNoise(nx, ny);
                precipNoiseFact[idx] = 1.0f + 0.2f * pn;

                float snc = SNOW_NOISE.GetNoise(nx, ny);
                float snf = SNOW_NOISE_FINE.GetNoise(nx, ny);
                snowNoise[idx] = 3.0f * snc + 2.0f * snf;
            }
        }

        // Compute slope from padded elevation using Sobel (divide by pixelSizeM for ratio)
        float[] slopeRatio = computeSlopeRatio(elevPadded, H, W, pixelSizeM);
        boolean hasCurrents = climate.length >= 6 * H * W;
        BiomePatches patches = BiomePatches.forSeed(seed);
        RegionalBiomes regional = RegionalBiomes.forSeed(seed);
        RegionalBiomes.Px px = new RegionalBiomes.Px();

        // Process per-pixel
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float elevVal   = elev[idx];
                float altM      = Math.max(0f, elevVal);
                float slope     = slopeRatio[idx];

                // Climate channels: [0]=temp, [1]=t_season, [2]=precip, [3]=p_cv
                float temp     = climate[idx] + tempNoise[idx];
                float tSeason  = climate[H * W + idx];
                float precip   = Math.max(0f, climate[2 * H * W + idx]) * precipNoiseFact[idx];
                float pCV      = climate[3 * H * W + idx];
                // Cold upwelling off this coast (0..1), from the coastal currents (biomes plan v1.2).
                float upwelling = hasCurrents ? Math.max(0f, -climate[5 * H * W + idx]) : 0f;

                // Derived climate variables
                float tStd     = tSeason / 100f;
                float tEff     = Math.max(0f, temp + 0.5f * tStd);
                float pet      = Math.max(250f, 250f + 25f * tEff + 0.7f * tEff * tEff);
                float aridity  = precip / Math.max(1f, pet);
                float seasonPenalty = 1f - 0.35f * Math.min(1f, pCV / 100f);
                float treeMoisture = aridity * seasonPenalty;

                // Growing season
                float amplitude = tStd * 1.414f;
                float growingSeason;
                if (amplitude < 0.1f) {
                    growingSeason = temp > 5f ? 365f : 0f;
                } else {
                    float x = (5f - temp) / amplitude;
                    if (x <= -1f) growingSeason = 365f;
                    else if (x >= 1f) growingSeason = 0f;
                    else growingSeason = 365f * (0.5f - (float) Math.asin(Math.max(-1f, Math.min(1f, x))) / (float) Math.PI);
                }

                float gsFactor = Math.max(0f, Math.min(1f, (growingSeason - 60f) / (150f - 60f)));
                float effTreeMoisture = treeMoisture * gsFactor;

                // Slope-dependent bare threshold
                float moistureFactor = Math.max(0f, Math.min(1f, (treeMoisture - 0.35f) / 0.45f));
                float bareThreshold = 0.7f + (1.19f - 0.7f) * moistureFactor;

                // Tree coverage classification
                boolean treesNone = effTreeMoisture < 0.2f;
                boolean tooArid   = treeMoisture < 0.05f;
                boolean tooCold   = growingSeason < 60f;
                boolean barren    = tooArid || tooCold;
                boolean treesSparse    = !treesNone && effTreeMoisture < 0.5f;
                boolean treesForest    = !treesNone && effTreeMoisture >= 0.5f && effTreeMoisture < 0.8f;
                boolean treesDense     = !treesNone && effTreeMoisture >= 0.8f && effTreeMoisture < 1.3f;
                boolean treesRainforest = !treesNone && effTreeMoisture >= 1.3f;

                // Slope overrides
                boolean slopeMedium = slope >= 0.62f && slope < bareThreshold;
                boolean slopeBare   = slope >= bareThreshold;
                if (slopeMedium) {
                    if (treesForest || treesDense || treesRainforest) { treesSparse = true; }
                    treesForest = treesForest && false; treesDense = false; treesRainforest = false;
                }
                if (slopeBare) {
                    treesNone = true; treesSparse = false; treesForest = false;
                    treesDense = false; treesRainforest = false;
                }

                // Snow classification
                float snowTemp = temp + snowNoise[idx];
                boolean isSteep = slope > 0.78f;
                boolean hasSnow = snowTemp < 0f && precip > 150f && !isSteep;

                // Elevation/temp bands
                boolean isOcean   = elevVal < 0f;
                boolean mountains = altM > 2500f;
                boolean lowland   = altM < 200f;
                boolean frozen    = temp < -5f;
                boolean cold      = temp >= -5f && temp < 5f;
                boolean cool      = temp >= 5f  && temp < 12f;
                boolean temperate = temp >= 12f && temp < 20f;
                boolean warm      = temp >= 20f && temp < 26f;
                boolean hot       = temp >= 26f;

                short biome = PLAINS;

                if (isOcean) {
                    biome = oceanBiome(-elevVal, temp);
                } else if (mountains) {
                    if (slopeBare) {
                        biome = hasSnow ? FROZEN_PEAKS : STONY_PEAKS;
                    } else if (hasSnow) {
                        if (treesNone) biome = SNOWY_SLOPES;
                        else if (treesSparse || treesForest) biome = SNOWY_TAIGA_SPARSE;
                        else biome = SNOWY_TAIGA;
                    } else if (treesNone) {
                        if (barren) biome = WINDSWEPT_HILLS;
                        else if (treeMoisture < 0.35f || precip < 350f) biome = GROVE;
                        else biome = PLAINS;
                    } else if (treesSparse || treesForest) {
                        biome = TAIGA_SPARSE;
                    } else {
                        biome = TAIGA;
                    }
                } else {
                    // Lowland/midland
                    if (hasSnow && treesNone) {
                        biome = SNOWY_PLAINS;
                    } else if (hasSnow) {
                        biome = (treesSparse || treesForest) ? SNOWY_TAIGA_SPARSE : SNOWY_TAIGA;
                    } else if (treesNone) {
                        if (warm || hot) biome = DESERT;
                        // Coastal fog deserts (Atacama, Namib): cool but rainless where upwelling chills the coast.
                        else if (upwelling > UPWELLING_DESERT && treeMoisture < 0.08f && temp >= 10f) biome = DESERT;
                        else if (barren && !lowland && (cold || cool || temperate)) biome = GROVE;
                        else if (treeMoisture < 0.35f || precip < 350f) biome = GROVE;
                        else biome = PLAINS;
                    } else if (treesSparse || treesForest) {
                        if (hot) biome = JUNGLE;
                        else if (warm && treesSparse && !slopeMedium) biome = SAVANNA;
                        else if (warm && treesForest) biome = FOREST_SPARSE;
                        else if (temperate) biome = FOREST_SPARSE;
                        else biome = TAIGA_SPARSE;
                    } else if (treesDense) {
                        if (hot) biome = JUNGLE;
                        else if (warm && lowland) biome = SWAMP;
                        else if (cool || cold) biome = TAIGA;
                        else biome = FOREST;
                    } else { // rainforest
                        if (hot || (warm && temp >= 18f && tStd < 5f)) biome = JUNGLE;
                        else if (lowland) biome = SWAMP;
                        else if (cool || cold) biome = TAIGA;
                        else biome = FOREST;
                    }
                }

                // Bare slope override for lowland/non-mountain cliffs
                if (slopeBare && !isOcean && !mountains) {
                    biome = hasSnow ? FROZEN_PEAKS : STONY_PEAKS;
                }

                // Sub-biome patches inside the biome chosen above (world metres, so the same at every scale).
                if (!isOcean) {
                    float xm = (j0 + c) * pixelSizeM, zm = (i0 + r) * pixelSizeM;
                    short base = biome;
                    biome = patches.apply(biome, xm, zm, altM, slope, temp, treeMoisture, effTreeMoisture, bareThreshold);
                    // Real-world biomes (v1.2) where the v1.1 patches left the classifier's biome.
                    if (biome == base) {
                        px.xm = xm; px.zm = zm; px.altM = altM; px.slope = slope; px.temp = temp; px.tSeason = tSeason;
                        px.precip = precip; px.pCV = pCV; px.treeMoisture = treeMoisture;
                        px.effMoisture = effTreeMoisture; px.growingSeason = growingSeason;
                        biome = regional.apply(biome, px);
                    }
                    if (riverDist != null && mainDist != null) {
                        biome = regional.riverForest(biome, xm, zm, riverDist[idx], mainDist[idx], altM, slope,
                                treeMoisture, temp);
                    }
                }

                out[idx] = biome;
            }
        }
        return out;
    }

    /**
     * Ocean biome for water of the given depth (metres, positive) and surface temperature (deg C).
     * Coral (warm ocean) only grows in shallow warm water; deeper warm water is lukewarm. Water past
     * {@link #DEEP_OCEAN_M} gets the deep variant (vanilla has no deep warm ocean, so that is deep lukewarm).
     */
    public static short oceanBiome(float depthM, float temp) {
        boolean deep = depthM > DEEP_OCEAN_M;
        if (temp < -5f) return deep ? DEEP_FROZEN_OCEAN : FROZEN_OCEAN;
        if (temp < 5f) return deep ? DEEP_COLD_OCEAN : COLD_OCEAN;
        if (temp >= 20f) {
            if (depthM < REEF_DEPTH_M) return WARM_OCEAN;
            return deep ? DEEP_LUKEWARM_OCEAN : LUKEWARM_OCEAN;
        }
        if (temp >= 13f) return deep ? DEEP_LUKEWARM_OCEAN : LUKEWARM_OCEAN;
        return deep ? DEEP_OCEAN : OCEAN;
    }

    /** True for any of the ocean biome ids this classifier produces. */
    public static boolean isOceanBiome(short id) {
        return id >= WARM_OCEAN && id <= DEEP_FROZEN_OCEAN;
    }

    private static float[] computeSlopeRatio(float[] elevPadded, int H, int W, float pixelSizeM) {
        // Sobel kernels / 8 applied to (H+2, W+2) padded array → (H, W) output
        float[] slope = new float[H * W];
        int PW = W + 2;
        float[] sx = {-1,0,1, -2,0,2, -1,0,1};
        float[] sy = {-1,-2,-1, 0,0,0, 1,2,1};
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int kr = 0; kr < 3; kr++)
                    for (int kc = 0; kc < 3; kc++) {
                        float v = elevPadded[(r + kr) * PW + (c + kc)];
                        dx += v * sx[kr * 3 + kc];
                        dy += v * sy[kr * 3 + kc];
                    }
                dx /= 8f; dy /= 8f;
                slope[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy) / pixelSizeM;
            }
        }
        return slope;
    }
}
