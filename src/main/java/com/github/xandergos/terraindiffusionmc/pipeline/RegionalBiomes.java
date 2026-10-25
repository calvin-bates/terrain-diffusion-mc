package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.github.xandergos.terraindiffusionmc.pipeline.BiomePatches.ramp;

/**
 * Real-world land biomes (biomes plan v1.2), carved out of the biomes {@link BiomeClassifier} produces, after the
 * v1.1 patches ({@link BiomePatches}) and only where those left the classifier's biome as it was, so the v1.1
 * layout stays. Each biome is a {@link Rule}: the parent biomes it can replace, a suitability (0..1, smooth ramps
 * over the pixel's terrain and climate) and a share of fully suitable ground, placed with its own patch noise in
 * world metres (seamless, the same at every scale). Rules are tried in order; the first that takes the pixel wins.
 *
 * <p>Riverside and floodplain forests need the river network, so they are placed afterwards by
 * {@link #riverForest}, from the distance to the nearest river.
 *
 * <p>The biome files, trees, tags and surface rules come from {@code tools/biomes/gen_biomes_v12.py}; the ids here must
 * match its table.
 */
public final class RegionalBiomes {

    static final short TUNDRA = 50, POLAR_DESERT = 51, TREELINE = 52, BOREAL_FOREST = 53, SIBERIAN_TAIGA = 54;
    static final short BIRCH_TAIGA = 55, TAIGA_MOUNTAIN = 56, TAIGA_CLEARING = 57, BOG = 58, MOORLAND = 59;
    static final short TEMPERATE_RAINFOREST = 60, ASPEN_PARKLAND = 61, TALLGRASS_PRAIRIE = 62, STEPPE = 63, MARSH = 64;
    static final short RIVERSIDE_FOREST = 65, FLOODPLAIN_FOREST = 66, REDWOODS = 67, MEDITERRANEAN_SCRUB = 68;
    static final short FORESTED_HIGHLAND = 69, TEMPERATE_HIGHLAND = 70, ROCKY_DESERT = 71, CLOUD_FOREST = 72;
    static final short TROPICAL_DRY_FOREST = 73, ASPEN_GROVE = 74;
    /** v1.3: shore biomes (placed by {@link CoastShaper}), rare land biomes (here) and salt flats (by the carver). */
    static final short SHINGLE_BEACH = 75, SALT_MARSH = 76, TIDAL_FLATS = 77, WHITE_CLIFFS = 78;
    static final short PAINTED_MOUNTAINS = 79, DESERT_OASIS = 80, BLOOMING_PLATEAU = 81, BLOOMING_VALLEY = 82;
    static final short LUSH_VALLEY = 83, SALT_FLATS = 84;
    /** Ice-capped islands in frozen seas (v1.3 round C), set by the classifier. */
    public static final short ARCTIC = 85;
    /** Volcano slopes and craters, and lava fields (v1.4 round A), set from the landform stage's tags. */
    public static final short VOLCANO = 86, LAVA_FIELDS = 87;

    /** Id → biome name in the {@code terra} namespace, for the biome source and the explorer. */
    public static final Map<Short, String> NAMES = new LinkedHashMap<>();
    static {
        String[] n = {"tundra", "polar_desert", "treeline", "boreal_forest", "siberian_taiga", "birch_taiga",
                "taiga_mountain", "taiga_clearing", "bog", "moorland", "temperate_rainforest", "aspen_parkland",
                "tallgrass_prairie", "steppe", "marsh", "riverside_forest", "floodplain_forest", "redwoods",
                "mediterranean_scrub", "forested_highland", "temperate_highland", "rocky_desert", "cloud_forest",
                "tropical_dry_forest", "aspen_grove", "shingle_beach", "salt_marsh", "tidal_flats", "white_cliffs",
                "painted_mountains", "desert_oasis", "blooming_plateau", "blooming_valley", "lush_valley", "salt_flats", "arctic", "volcano", "lava_fields"};
        for (int k = 0; k < n.length; k++) NAMES.put((short) (TUNDRA + k), n[k]);
    }

    /** One pixel's terrain and climate, filled by the classifier. */
    static final class Px {
        float xm, zm;          // world position, metres
        float altM, slope;     // elevation above sea level (m), slope ratio
        float temp;            // mean temperature (°C, with the classifier's noise)
        float tSeason;         // temperature seasonality (std × 100)
        float precip, pCV;     // precipitation (mm/yr) and its monthly coefficient of variation (%)
        float treeMoisture, effMoisture, growingSeason;
    }

    private interface Suit {
        float at(Px p);
    }

    /** A biome, the parents it can replace, how suitable a pixel is, and the share of fully suitable ground it takes. */
    private record Rule(short id, short[] parents, Suit suit, float share, BiomePatches.Patch patch) {
        boolean parent(short b) {
            for (short s : parents) if (s == b) return true;
            return false;
        }
    }

    // ---- Shares (fraction of fully suitable parent ground) and patch sizes (metres; patches ~half that across) ----
    static final float POLAR_DESERT_SHARE = 0.7f, TUNDRA_SHARE = 0.75f, TREELINE_SHARE = 0.85f;
    static final float BOG_SHARE = 0.4f, MARSH_SHARE = 0.35f, TAIGA_MOUNTAIN_SHARE = 0.5f;
    static final float FORESTED_HIGHLAND_SHARE = 0.35f, TEMPERATE_HIGHLAND_SHARE = 0.35f, TAIGA_CLEARING_SHARE = 0.1f;
    /** Aspen groves on cool mountainsides (Calvin, Oct 22). */
    static final float ASPEN_GROVE_SHARE = 0.3f;
    static final float BOREAL_SHARE = 0.55f, SIBERIAN_SHARE = 0.55f, BIRCH_TAIGA_SHARE = 0.22f;
    static final float REDWOODS_SHARE = 0.18f, RAINFOREST_SHARE = 0.6f, CLOUD_FOREST_SHARE = 0.6f;
    static final float ASPEN_SHARE = 0.45f, MOORLAND_SHARE = 0.4f, STEPPE_SHARE = 0.5f, PRAIRIE_SHARE = 0.5f;
    static final float MEDITERRANEAN_SHARE = 0.65f, TROPICAL_DRY_SHARE = 0.6f, ROCKY_DESERT_SHARE = 0.7f;
    /** v1.3 rare land biomes. */
    static final float PAINTED_SHARE = 0.15f, OASIS_SHARE = 0.06f, BLOOMING_PLATEAU_SHARE = 0.15f;
    static final float BLOOMING_VALLEY_SHARE = 0.12f, LUSH_VALLEY_SHARE = 0.15f;
    private static final float BROAD_M = 12000f, MID_M = 6500f, SMALL_M = 3500f;
    /** Oases are small: ~1.2 km wavelength after the biome size factor, patches a few hundred metres across. */
    private static final float OASIS_M = 500f;

    private static final short PLAINS = BiomeClassifier.PLAINS, SNOWY_PLAINS = BiomeClassifier.SNOWY_PLAINS;
    private static final short GROVE = BiomeClassifier.GROVE, DESERT = BiomeClassifier.DESERT;
    private static final short SWAMP = BiomeClassifier.SWAMP, FOREST = BiomeClassifier.FOREST;
    private static final short FOREST_SPARSE = BiomeClassifier.FOREST_SPARSE, TAIGA = BiomeClassifier.TAIGA;
    private static final short TAIGA_SPARSE = BiomeClassifier.TAIGA_SPARSE, SNOWY_TAIGA = BiomeClassifier.SNOWY_TAIGA;
    private static final short SNOWY_TAIGA_SPARSE = BiomeClassifier.SNOWY_TAIGA_SPARSE;
    private static final short SAVANNA = BiomeClassifier.SAVANNA, JUNGLE = BiomeClassifier.JUNGLE;
    private static final short WINDSWEPT_HILLS = BiomeClassifier.WINDSWEPT_HILLS;

    private final Rule[] rules;
    private final FastNoiseLite riverJitter;
    private final long seed;
    private static volatile RegionalBiomes cached;

    private RegionalBiomes(long seed) {
        this.seed = seed;
        int s = (int) (seed ^ (seed >>> 32)) + 200;
        rules = new Rule[]{
                // Banded terracotta mountains in hot, arid land (sea-level temperature, so high ground counts).
                rule(s, PAINTED_MOUNTAINS, MID_M, PAINTED_SHARE, p -> ramp(p.temp + 0.0065f * p.altM, 20f, 24f)
                        * ramp(p.altM, 700f, 1100f) * (1f - ramp(p.treeMoisture, 0.12f, 0.2f)),
                        DESERT, WINDSWEPT_HILLS, BiomeClassifier.STONY_PEAKS, GROVE),
                // Small water-and-palm clusters on flat, low desert.
                rule(s, DESERT_OASIS, OASIS_M, OASIS_SHARE, p -> (1f - ramp(p.altM, 600f, 900f))
                        * (1f - ramp(p.slope, 0.05f, 0.1f)), DESERT),
                // Flower-heavy gentle ground: high plateaus and low valleys; lush valleys in wet, warm lowland forest.
                rule(s, BLOOMING_PLATEAU, MID_M, BLOOMING_PLATEAU_SHARE, p -> ramp(p.altM, 900f, 1200f)
                        * (1f - ramp(p.altM, 2000f, 2400f)) * (1f - ramp(p.slope, 0.08f, 0.15f)) * ramp(p.temp, 6f, 9f)
                        * (1f - ramp(p.temp, 17f, 20f)) * ramp(p.treeMoisture, 0.12f, 0.18f), GROVE, PLAINS, FOREST_SPARSE),
                rule(s, BLOOMING_VALLEY, MID_M, BLOOMING_VALLEY_SHARE, p -> ramp(p.altM, 50f, 150f)
                        * (1f - ramp(p.altM, 700f, 1000f)) * (1f - ramp(p.slope, 0.06f, 0.12f)) * ramp(p.temp, 9f, 12f)
                        * (1f - ramp(p.temp, 20f, 23f)) * ramp(p.treeMoisture, 0.14f, 0.2f)
                        * (1f - ramp(p.treeMoisture, 0.8f, 1f)), GROVE, PLAINS, FOREST_SPARSE, FOREST),
                rule(s, LUSH_VALLEY, MID_M, LUSH_VALLEY_SHARE, p -> ramp(p.altM, 50f, 150f)
                        * (1f - ramp(p.altM, 900f, 1300f)) * (1f - ramp(p.slope, 0.1f, 0.18f))
                        * ramp(p.effMoisture, 0.8f, 1f) * ramp(p.temp, 10f, 13f) * (1f - ramp(p.temp, 22f, 25f)),
                        FOREST, FOREST_SPARSE, JUNGLE),
                // The coldest, driest land: gravel and stone.
                rule(s, POLAR_DESERT, BROAD_M, POLAR_DESERT_SHARE, p -> (1f - ramp(p.temp, -10f, -6f))
                        * (1f - ramp(p.precip, 200f, 350f)), SNOWY_PLAINS, PLAINS, GROVE, WINDSWEPT_HILLS),
                // Cold, treeless lowland: too short a growing season for trees, not the very coldest.
                rule(s, TUNDRA, BROAD_M, TUNDRA_SHARE, p -> (1f - ramp(p.growingSeason, 70f, 110f))
                        * ramp(p.temp, -11f, -7f) * (1f - ramp(p.altM, 1500f, 2200f)),
                        SNOWY_PLAINS, PLAINS, GROVE, WINDSWEPT_HILLS),
                // The upper edge of mountain forest, where the growing season runs out.
                rule(s, TREELINE, MID_M, TREELINE_SHARE, p -> ramp(p.altM, 1000f, 1500f)
                        * (1f - ramp(p.growingSeason, 85f, 120f)),
                        TAIGA, TAIGA_SPARSE, SNOWY_TAIGA, SNOWY_TAIGA_SPARSE),
                // Cold, wet, flat lowland.
                rule(s, BOG, SMALL_M, BOG_SHARE, p -> (1f - ramp(p.altM, 250f, 450f)) * (1f - ramp(p.slope, 0.03f, 0.07f))
                        * ramp(p.treeMoisture, 0.8f, 1.1f) * ramp(p.temp, -5f, -3f) * (1f - ramp(p.temp, 7f, 10f)),
                        TAIGA, TAIGA_SPARSE, PLAINS, SWAMP, FOREST, FOREST_SPARSE),
                // Temperate, wet, flat lowland without trees.
                rule(s, MARSH, SMALL_M, MARSH_SHARE, p -> (1f - ramp(p.altM, 40f, 120f)) * (1f - ramp(p.slope, 0.02f, 0.05f))
                        * ramp(p.treeMoisture, 0.6f, 0.9f) * ramp(p.temp, 4f, 7f) * (1f - ramp(p.temp, 22f, 25f)),
                        SWAMP, PLAINS, FOREST, FOREST_SPARSE, GROVE),
                // Tropical mountain forest in the clouds (before the taiga rules: the classifier calls forested mountains
                // above 2500 m taiga even in the tropics).
                rule(s, CLOUD_FOREST, MID_M, CLOUD_FOREST_SHARE, p -> (1f - ramp(p.tSeason, 300f, 450f))
                        * ramp(p.altM, 900f, 1300f) * ramp(p.temp, 9f, 12f) * (1f - ramp(p.temp, 22f, 25f))
                        * ramp(p.treeMoisture, 0.8f, 1.1f), JUNGLE, FOREST, FOREST_SPARSE, TAIGA, TAIGA_SPARSE),
                // Taiga on mountain slopes.
                rule(s, TAIGA_MOUNTAIN, MID_M, TAIGA_MOUNTAIN_SHARE, p -> ramp(p.altM, 1300f, 1700f)
                        * ramp(p.slope, 0.25f, 0.4f), TAIGA, TAIGA_SPARSE),
                // Dense aspen stands on cool, moderately moist mountainsides (the Rockies), not steep.
                rule(s, ASPEN_GROVE, MID_M, ASPEN_GROVE_SHARE, p -> ramp(p.altM, 1400f, 1900f)
                        * (1f - ramp(p.altM, 2900f, 3300f)) * ramp(p.temp, 0f, 2f) * (1f - ramp(p.temp, 9f, 12f))
                        * ramp(p.treeMoisture, 0.3f, 0.45f) * (1f - ramp(p.treeMoisture, 1.1f, 1.4f))
                        * (1f - ramp(p.slope, 0.3f, 0.45f)), TAIGA, TAIGA_SPARSE, FOREST, FOREST_SPARSE),
                // Mid-to-high forest bands on gentler ground.
                rule(s, FORESTED_HIGHLAND, MID_M, FORESTED_HIGHLAND_SHARE, p -> ramp(p.altM, 1100f, 1500f)
                        * (1f - ramp(p.altM, 2300f, 2700f)) * ramp(p.temp, 2f, 5f) * (1f - ramp(p.temp, 14f, 17f))
                        * (1f - ramp(p.slope, 0.3f, 0.45f)), FOREST, FOREST_SPARSE, TAIGA, TAIGA_SPARSE),
                rule(s, TEMPERATE_HIGHLAND, MID_M, TEMPERATE_HIGHLAND_SHARE, p -> ramp(p.altM, 1000f, 1400f)
                        * (1f - ramp(p.altM, 2300f, 2700f)) * ramp(p.temp, 3f, 6f) * (1f - ramp(p.temp, 15f, 18f))
                        * ramp(p.treeMoisture, 0.12f, 0.2f) * (1f - ramp(p.slope, 0.35f, 0.5f)), PLAINS, GROVE, FOREST_SPARSE),
                // Grassy gaps anywhere in taiga.
                rule(s, TAIGA_CLEARING, SMALL_M, TAIGA_CLEARING_SHARE, p -> 1f - ramp(p.slope, 0.25f, 0.4f), TAIGA),
                // The cold forest family: boreal where the climate is maritime, Siberian where continental.
                rule(s, BOREAL_FOREST, BROAD_M, BOREAL_SHARE, p -> (1f - ramp(p.temp, 3f, 6f))
                        * (1f - ramp(p.tSeason, 1100f, 1400f)), TAIGA),
                rule(s, SIBERIAN_TAIGA, BROAD_M, SIBERIAN_SHARE, p -> (1f - ramp(p.temp, 3f, 6f))
                        * ramp(p.tSeason, 1100f, 1400f), TAIGA, TAIGA_SPARSE),
                rule(s, BIRCH_TAIGA, MID_M, BIRCH_TAIGA_SHARE, p -> ramp(p.temp, 4f, 6f) * (1f - ramp(p.temp, 10f, 12f))
                        * ramp(p.treeMoisture, 0.6f, 0.8f), TAIGA, TAIGA_SPARSE, FOREST),
                // Wet, cool, maritime forests: redwoods (rare) and temperate rainforest.
                rule(s, REDWOODS, SMALL_M, REDWOODS_SHARE, p -> ramp(p.temp, 8f, 10f) * (1f - ramp(p.temp, 15f, 17f))
                        * ramp(p.treeMoisture, 0.9f, 1.15f) * (1f - ramp(p.altM, 1200f, 1600f))
                        * (1f - ramp(p.tSeason, 800f, 1100f)), FOREST, TAIGA),
                rule(s, TEMPERATE_RAINFOREST, MID_M, RAINFOREST_SHARE, p -> ramp(p.effMoisture, 1.2f, 1.5f)
                        * ramp(p.temp, 4f, 7f) * (1f - ramp(p.temp, 14f, 17f)) * (1f - ramp(p.tSeason, 800f, 1100f)),
                        FOREST, TAIGA),
                // Groves of aspen on the cool grassland edge of the boreal forest.
                rule(s, ASPEN_PARKLAND, MID_M, ASPEN_SHARE, p -> ramp(p.temp, -1f, 1f) * (1f - ramp(p.temp, 7f, 10f))
                        * ramp(p.treeMoisture, 0.12f, 0.2f) * (1f - ramp(p.treeMoisture, 0.45f, 0.6f))
                        * (1f - ramp(p.altM, 1200f, 1700f)), TAIGA_SPARSE, FOREST_SPARSE, GROVE, PLAINS),
                // Cool, windy, treeless upland.
                rule(s, MOORLAND, MID_M, MOORLAND_SHARE, p -> ramp(p.altM, 300f, 600f) * (1f - ramp(p.altM, 1800f, 2300f))
                        * ramp(p.temp, 1f, 4f) * (1f - ramp(p.temp, 10f, 13f)) * ramp(p.treeMoisture, 0.1f, 0.18f),
                        GROVE, PLAINS, WINDSWEPT_HILLS),
                // Grasslands: cool and dry steppe, moister tallgrass prairie.
                // Grasslands (Terra's dry grassland is grove, treeless because it is too dry): cool grassland is
                // steppe; where warmer, the drier half is steppe and the moister half tallgrass prairie. (By rainfall
                // alone Terra's grassland is all steppe-dry, so the split uses tree moisture.)
                rule(s, STEPPE, BROAD_M, STEPPE_SHARE, p -> ramp(p.temp, -3f, 0f) * (1f - ramp(p.temp, 13f, 16f))
                        * (1f - ramp(p.treeMoisture, 0.12f, 0.16f) * ramp(p.temp, 4f, 7f))
                        * (1f - ramp(p.altM, 2000f, 2500f)), GROVE),
                rule(s, TALLGRASS_PRAIRIE, BROAD_M, PRAIRIE_SHARE, p -> (1f - ramp(p.altM, 600f, 1000f))
                        * ramp(p.temp, 5f, 8f) * (1f - ramp(p.temp, 20f, 24f)) * ramp(p.treeMoisture, 0.12f, 0.16f),
                        GROVE, PLAINS),
                // Warm with dry summers (rain concentrated in a few months).
                rule(s, MEDITERRANEAN_SCRUB, BROAD_M, MEDITERRANEAN_SHARE, p -> ramp(p.temp, 11f, 14f)
                        * (1f - ramp(p.temp, 21f, 24f)) * ramp(p.pCV, 50f, 70f) * ramp(p.treeMoisture, 0.08f, 0.14f)
                        * (1f - ramp(p.treeMoisture, 0.7f, 0.9f)) * (1f - ramp(p.altM, 1500f, 2000f)),
                        FOREST_SPARSE, SAVANNA, GROVE, PLAINS),
                // Hot with a long dry season: more open than jungle, more trees than savanna.
                rule(s, TROPICAL_DRY_FOREST, BROAD_M, TROPICAL_DRY_SHARE, p -> ramp(p.temp, 20f, 22f)
                        * ramp(p.pCV, 70f, 90f) * ramp(p.treeMoisture, 0.28f, 0.38f) * (1f - ramp(p.treeMoisture, 0.85f, 1.05f)),
                        SAVANNA, JUNGLE, FOREST_SPARSE),
                // Gravel and stone desert on rougher or higher ground.
                rule(s, ROCKY_DESERT, MID_M, ROCKY_DESERT_SHARE, p -> Math.max(ramp(p.slope, 0.06f, 0.15f),
                        0.7f * ramp(p.altM, 500f, 900f)), DESERT),
        };
        riverJitter = BiomePatches.fbm(s + 99, 1f / 900f, 2);
    }

    private static Rule rule(int s, short id, float sizeM, float share, Suit suit, short... parents) {
        return new Rule(id, parents, suit, share, new BiomePatches.Patch(s + id, sizeM));
    }

    static RegionalBiomes forSeed(long seed) {
        RegionalBiomes r = cached;
        if (r == null || r.seed != seed) {
            r = new RegionalBiomes(seed);
            cached = r;
        }
        return r;
    }

    /** The v1.2 biome for a pixel whose classifier biome is {@code base}, or {@code base}. */
    short apply(short base, Px p) {
        for (Rule r : rules) {
            if (!r.parent(base)) continue;
            float s = r.suit.at(p);
            if (s > 0f && r.patch.in(p.xm, p.zm, r.share * s)) return r.id;
        }
        return base;
    }

    // ---- River forests ----

    /**
     * Riverside forest reaches this far (native pixels, ~90 m) from any channel, plus up to
     * {@link #RIVERSIDE_JITTER_PX}. Channels through dry land are mostly streams (the hydrology sizes rivers by
     * rain-weighted runoff), so streams count too (Calvin found none along rivers only, Oct 21).
     */
    static final float RIVERSIDE_PX = 3f, RIVERSIDE_JITTER_PX = 1.5f;
    /** Floodplain forest reaches this far (native pixels, ~210 m) from a river or main river, plus jitter. */
    static final float FLOODPLAIN_PX = 7f, FLOODPLAIN_JITTER_PX = 3f;

    /** River forests only below this temperature (°C, ± ~2 by noise). */
    static final float RIVER_FOREST_MAX_TEMP = 20f;

    /** Biomes dry enough that rivers through them grow a strip of trees. */
    private static boolean dryLand(short b) {
        return b == SAVANNA || b == GROVE || b == DESERT || b == STEPPE || b == ROCKY_DESERT || b == MEDITERRANEAN_SCRUB
                || b == TROPICAL_DRY_FOREST || b == BiomeClassifier.SAVANNA_PLATEAU || b == BiomeClassifier.SUNFLOWER_PLAINS
                || b == TALLGRASS_PRAIRIE;
    }

    /** Wet-climate lowland biomes where big rivers get a floodplain forest. */
    private static boolean wetLowland(short b) {
        return b == FOREST || b == FOREST_SPARSE || b == PLAINS || b == SWAMP || b == TAIGA || b == TAIGA_SPARSE
                || b == BiomeClassifier.BIRCH_FOREST || b == BiomeClassifier.FLOWER_FOREST
                || b == BOREAL_FOREST || b == BIRCH_TAIGA || b == TALLGRASS_PRAIRIE || b == MARSH;
    }

    /**
     * Riverside or floodplain forest for a pixel {@code distRiver} native pixels from the nearest channel (stream,
     * river or main river) and {@code distMain} from the nearest river or main river (either may be infinite), or
     * {@code b} unchanged. Riverside forest lines channels through dry land; floodplain forest fills a broad strip
     * beside rivers in wet lowland. The edge wanders with a noise in world metres.
     */
    short riverForest(short b, float xm, float zm, float distRiver, float distMain, float altM, float slope,
                      float treeMoisture, float temp) {
        if (temp < 0f) return b;
        float j = riverJitter.GetNoise(xm, zm);
        // Both are temperate woods (oak, poplar, swamp oak, birch): none in the tropics, where they looked pasted into
        // jungle and savanna (Calvin, Oct 25). The limit wanders by a couple of degrees.
        if (temp + 2f * j >= RIVER_FOREST_MAX_TEMP) return b;
        if (dryLand(b) && distRiver <= RIVERSIDE_PX + RIVERSIDE_JITTER_PX * j) return RIVERSIDE_FOREST;
        if (wetLowland(b) && treeMoisture > 0.5f && altM < 300f && slope < 0.08f
                && distMain <= FLOODPLAIN_PX + FLOODPLAIN_JITTER_PX * j) return FLOODPLAIN_FOREST;
        return b;
    }
}
