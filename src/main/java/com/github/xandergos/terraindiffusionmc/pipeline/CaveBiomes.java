package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The cave biome layer (caves plan v1.5): which cave biome a point underground belongs to.
 *
 * <p>Underground space is split into regions: boxes of {@link #REGION_XZ} x {@link #REGION_Y} blocks, their edges
 * bent by a slow 3D warp so they look like irregular pockets rather than cubes. Each region draws one number from a
 * hash of its position and the world seed, and that number picks its tier and cave biome from the tier shares below.
 * Everything is a pure function of world position and seed, so it is the same whichever tile or chunk asks.
 *
 * <p>Plain stone caves are the default: a region that draws plain stone, or one whose biome does not suit its depth,
 * returns {@link #NONE}, and the biome source keeps the surface biome there. Cave biomes only start
 * {@link #START_DEPTH} blocks under the lowest nearby ground, so the surface biome always wins at the surface (snow,
 * grass, trees, mob spawns) and on cliff faces.
 *
 * <p>Draws are stable: a region's number never changes, and each tier has its own slice of the range, so adding the
 * uncommon, rare and very rare caves later (parts two and three) does not move any common cave. Until a tier has
 * caves, its slice falls back to plain stone.
 *
 * <p>Pure Java (no Minecraft classes) so test harnesses can render it; ids must match {@code gen_biomes_v12.py}.
 */
public final class CaveBiomes {
    /** No cave biome: keep the surface biome (plain stone caves). */
    public static final short NONE = -1;
    // Part one: the common caves.
    public static final short ANDESITE_CAVES = 140, DIORITE_CAVES = 141, GRANITE_CAVES = 142, TUFF_CAVES = 143;
    /** Vanilla's dripstone caves biome, used as is. */
    public static final short DRIPSTONE_CAVES = 144;
    // Part two round A: the dry uncommon caves. Lush caves and thermal caves (vanilla 26.3's sulfur caves) are vanilla's.
    public static final short LUSH_CAVES = 145, FUNGAL_CAVES = 146, UNDERGROUND_JUNGLE = 147, ICY_CAVES = 148;
    public static final short FROSTFIRE_CAVES = 149, THERMAL_CAVES = 150, INFESTED_CAVES = 151, SPIDER_NEST = 152;
    public static final short COPPER_CAVES = 153, SALT_CAVES = 154, MUD_CAVES = 155, BREAKDOWN_CHAMBERS = 156;
    // Part two round B: water caves (world/CaveWater fills them up to their region's water level).
    public static final short UNDERGROUND_LAKES = 157, GLOWWORM_GROTTO = 158, KARST_CAVES = 159;

    /** Id → biome name ({@code terra} namespace unless it has its own), for the biome source and the finder. */
    public static final Map<Short, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put(ANDESITE_CAVES, "terra:andesite_caves");
        NAMES.put(DIORITE_CAVES, "terra:diorite_caves");
        NAMES.put(GRANITE_CAVES, "terra:granite_caves");
        NAMES.put(TUFF_CAVES, "terra:tuff_caves");
        NAMES.put(DRIPSTONE_CAVES, "minecraft:dripstone_caves");
        NAMES.put(LUSH_CAVES, "minecraft:lush_caves");
        NAMES.put(FUNGAL_CAVES, "terra:fungal_caves");
        NAMES.put(UNDERGROUND_JUNGLE, "terra:underground_jungle");
        NAMES.put(ICY_CAVES, "terra:icy_caves");
        NAMES.put(FROSTFIRE_CAVES, "terra:frostfire_caves");
        NAMES.put(THERMAL_CAVES, "minecraft:sulfur_caves");
        NAMES.put(INFESTED_CAVES, "terra:infested_caves");
        NAMES.put(SPIDER_NEST, "terra:spider_nest");
        NAMES.put(COPPER_CAVES, "terra:copper_caves");
        NAMES.put(SALT_CAVES, "terra:salt_caves");
        NAMES.put(MUD_CAVES, "terra:mud_caves");
        NAMES.put(BREAKDOWN_CHAMBERS, "terra:breakdown_chambers");
        NAMES.put(UNDERGROUND_LAKES, "terra:underground_lakes");
        NAMES.put(GLOWWORM_GROTTO, "terra:glowworm_grotto");
        NAMES.put(KARST_CAVES, "terra:karst_caves");
    }

    // ---- Tier shares (fractions of cave regions; tune in testing) ----
    /** Plain stone caves: the default and the most common single type. Part of the common tier. */
    public static float PLAIN_SHARE = 0.35f;
    /** The whole common tier, plain stone included. */
    public static float COMMON_SHARE = 0.70f;
    public static float UNCOMMON_SHARE = 0.21f;
    public static float RARE_SHARE = 0.07f;
    public static float VERY_RARE_SHARE = 0.02f;

    /** Weights of the non-plain common caves (tuff and granite a little rarer than andesite and diorite). */
    private static final short[] COMMON = {ANDESITE_CAVES, DIORITE_CAVES, GRANITE_CAVES, TUFF_CAVES, DRIPSTONE_CAVES};
    private static final float[] COMMON_WEIGHT = {8f, 8f, 6f, 6f, 7f};

    // ---- Shape and depth ----
    /** Region size in blocks: horizontal, vertical. */
    public static final int REGION_XZ = 112, REGION_Y = 56;
    /** How far the region edges are pushed around (blocks), and the size of those bends. */
    private static final float WARP = 34f, WARP_FREQ = 1f / 80f;
    /** Cave biomes start this many blocks under the lowest nearby ground. */
    public static int START_DEPTH = 20;
    /** Andesite, diorite and granite caves stay above the deepslate (vanilla's gradient runs from Y 8 to Y 0). */
    public static int STONE_VARIANT_MIN_Y = -8;
    /** Tuff caves are mid to deep. */
    public static int TUFF_MAX_Y = 40;
    /** Vanilla's dripstone features only place up to Y 256. */
    public static int DRIPSTONE_MAX_Y = 240;

    private final FastNoiseLite warpX, warpY, warpZ;
    private final long seed;

    private CaveBiomes(long seed) {
        this.seed = seed;
        int s = (int) (seed ^ (seed >>> 32));
        warpX = simplex(s + 0x6a11);
        warpY = simplex(s + 0x6b23);
        warpZ = simplex(s + 0x6c35);
    }

    private static FastNoiseLite simplex(int seed) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(WARP_FREQ);
        return n;
    }

    private static volatile CaveBiomes current;

    public static CaveBiomes forSeed(long seed) {
        CaveBiomes c = current;
        if (c == null || c.seed != seed) {
            c = new CaveBiomes(seed);
            current = c;
        }
        return c;
    }

    /**
     * What the cave layer needs to know about the surface: the surface biome id (Terra's ids, as in the tiles) of the
     * column at (x, z). Gated caves (lush, icy, mud...) look at the surface above their region's anchor, the centre of
     * its unwarped cell, so a whole region agrees and every chunk computes the same answer.
     */
    public interface Surface {
        short biomeAt(int x, int z);

        /** Top solid block of the column (rivers carved), or {@link Integer#MIN_VALUE} if unknown. */
        default int groundTop(int x, int z) {
            return Integer.MIN_VALUE;
        }

        /** Y of the column's river or lake water, or {@link Integer#MIN_VALUE} if it has none. */
        default int inlandWater(int x, int z) {
            return Integer.MIN_VALUE;
        }

        /** Whether a river or lake runs within about 48 blocks of (x, z) (a 5 x 5 grid of samples 24 blocks apart). */
        default boolean nearInlandWater(int x, int z) {
            for (int dz = -48; dz <= 48; dz += 24)
                for (int dx = -48; dx <= 48; dx += 24)
                    if (inlandWater(x + dx, z + dz) != Integer.MIN_VALUE) return true;
            return false;
        }
    }

    /**
     * The cave biome at block (x, y, z), {@code refTop} being the top block of the lowest ground nearby (the same
     * reference the cave roof uses), or {@link #NONE} for plain stone caves and everything above the cave layer.
     */
    public short at(int x, int y, int z, int refTop, Surface surface) {
        if (refTop - y < START_DEPTH) return NONE;
        return pick(x, y, z, surface);
    }

    /** The cave biome a point's region draws, after its gates (no start depth check). */
    public short pick(int x, int y, int z, Surface surface) {
        long cell = regionCell(x, y, z);
        float u = draw(cell);
        float total = COMMON_SHARE + UNCOMMON_SHARE + RARE_SHARE + VERY_RARE_SHARE;
        u *= total;
        if (u < PLAIN_SHARE) return NONE;
        if (u < COMMON_SHARE) {
            float t = (u - PLAIN_SHARE) / Math.max(1e-6f, COMMON_SHARE - PLAIN_SHARE);
            return gate(weighted(COMMON, COMMON_WEIGHT, t), y);
        }
        if (u < COMMON_SHARE + UNCOMMON_SHARE) {
            float t = (u - COMMON_SHARE) / UNCOMMON_SHARE;
            return uncommon(t, y, cell, surface);
        }
        // Rare and very rare tiers: part three. Plain stone until then.
        return NONE;
    }

    /** The region's draw in [0, 1). */
    public float regionDraw(int x, int y, int z) {
        return draw(regionCell(x, y, z));
    }

    /** The warped region cell holding a point, packed (21 bits per axis). */
    public long regionCell(int x, int y, int z) {
        float wx = x + WARP * warpX.GetNoise(x, y, z);
        float wy = y + WARP * 0.5f * warpY.GetNoise(x, y, z);
        float wz = z + WARP * warpZ.GetNoise(x, y, z);
        long cx = (long) Math.floor(wx / REGION_XZ) & 0x1FFFFF;
        long cy = (long) Math.floor(wy / REGION_Y) & 0x1FFFFF;
        long cz = (long) Math.floor(wz / REGION_XZ) & 0x1FFFFF;
        return (cx << 42) | (cy << 21) | cz;
    }

    private static int unpack(long cell, int shift) {
        return (int) (((cell >>> shift) & 0x1FFFFF) << 43 >> 43);   // sign-extend 21 bits
    }

    /** Block x and z of a region cell's anchor (the centre of the unwarped cell). */
    public static int anchorX(long cell) {
        return unpack(cell, 42) * REGION_XZ + REGION_XZ / 2;
    }

    public static int anchorZ(long cell) {
        return unpack(cell, 0) * REGION_XZ + REGION_XZ / 2;
    }

    private float draw(long cell) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h = mix(h + unpack(cell, 42) * 0xC2B2AE3D27D4EB4FL);
        h = mix(h + unpack(cell, 21) * 0x165667B19E3779F9L);
        h = mix(h + unpack(cell, 0) * 0x27D4EB2F165667C5L);
        return (h >>> 40) / (float) (1L << 24);
    }

    /** A second, independent draw for the same region (sub-choices that mustn't correlate with the tier draw). */
    public float draw2(long cell) {
        long h = seed ^ 0x2545F4914F6CDD1DL;
        h = mix(h + unpack(cell, 42) * 0xC2B2AE3D27D4EB4FL);
        h = mix(h + unpack(cell, 21) * 0x165667B19E3779F9L);
        h = mix(h + unpack(cell, 0) * 0x27D4EB2F165667C5L);
        return (h >>> 40) / (float) (1L << 24);
    }

    // ---- Uncommon tier (part two) ----

    /**
     * The uncommon caves built so far and their weights among the caves that fit a region. Gated caves (lush, jungle,
     * icy, frostfire, thermal, salt, mud) only fit under the right surface; the others fit anywhere, so the tier keeps
     * its share everywhere and gated caves take a large part of it where they fit. The fungal cave is uncommon on
     * purpose, not a flagship.
     */
    private static final short[] UNCOMMON = {LUSH_CAVES, FUNGAL_CAVES, UNDERGROUND_JUNGLE, ICY_CAVES, FROSTFIRE_CAVES,
            THERMAL_CAVES, INFESTED_CAVES, SPIDER_NEST, COPPER_CAVES, SALT_CAVES, MUD_CAVES, BREAKDOWN_CHAMBERS,
            UNDERGROUND_LAKES, GLOWWORM_GROTTO, KARST_CAVES};
    private static final float[] UNCOMMON_WEIGHT = {7f, 4f, 7f, 7f, 3f, 12f, 3f, 3f, 3f, 7f, 7f, 4f, 10f, 5f, 8f};

    // ---- Surface groups (Terra's surface biome ids: BiomeClassifier, BiomePatches, RegionalBiomes) ----
    private static final boolean[] COLD = ids(3, 16, 116, 31, 32, 33, 34, 121, 131, 48, 49, 50, 51, 52, 85, 88);
    private static final boolean[] HUMID = ids(6, 8, 9, 10, 11, 12, 13, 14, 29, 30, 36, 37, 108, 60, 61, 65, 66, 67, 69,
            72, 74, 81, 82, 83);
    private static final boolean[] TROPICAL = ids(23, 24, 25, 72, 73, 92, 95, 134);
    private static final boolean[] HOT_GROUND = ids(86, 87, 93);
    private static final boolean[] DRY = ids(5, 26, 63, 71, 79, 80, 84, 89, 90, 91);
    private static final boolean[] WET = ids(6, 58, 64, 66, 76, 77, 134);

    private static boolean[] ids(int... v) {
        boolean[] b = new boolean[256];
        for (int i : v) b[i] = true;
        return b;
    }

    private static boolean in(boolean[] group, short id) {
        return id >= 0 && id < group.length && group[id];
    }

    /**
     * Picks among the uncommon caves whose gates pass for this region (so the tier keeps its share wherever any of
     * them fits), or plain stone if none does.
     */
    private short uncommon(float t, int y, long cell, Surface surface) {
        if (UNCOMMON.length == 0) return NONE;
        short anchorBiome = surface == null ? -1 : surface.biomeAt(anchorX(cell), anchorZ(cell));
        int nearWater = -1;   // looked up only if a water cave asks
        float sum = 0;
        float[] w = new float[UNCOMMON.length];
        for (int i = 0; i < UNCOMMON.length; i++) {
            short id = UNCOMMON[i];
            boolean ok = eligible(id, y, anchorBiome);
            if (ok && needsInlandWater(id, anchorBiome)) {
                if (nearWater < 0) nearWater = surface != null && surface.nearInlandWater(anchorX(cell), anchorZ(cell)) ? 1 : 0;
                ok = nearWater == 1;
            }
            w[i] = ok ? UNCOMMON_WEIGHT[i] : 0f;
            sum += w[i];
        }
        if (sum <= 0) return NONE;
        float target = t * sum, acc = 0;
        for (int i = 0; i < UNCOMMON.length; i++) {
            acc += w[i];
            if (target < acc && w[i] > 0) return UNCOMMON[i];
        }
        return NONE;
    }

    /** Vanilla's lush and sulfur cave features place only up to Y 256, like dripstone's. */
    public static int VANILLA_FEATURE_MAX_Y = 240;

    /** Whether an uncommon cave suits this depth and the surface above its region. */
    private static boolean eligible(short id, int y, short anchorBiome) {
        return switch (id) {
            case LUSH_CAVES -> y <= VANILLA_FEATURE_MAX_Y && in(HUMID, anchorBiome);
            case UNDERGROUND_JUNGLE -> in(TROPICAL, anchorBiome);
            case ICY_CAVES -> in(COLD, anchorBiome);
            case FROSTFIRE_CAVES -> in(COLD, anchorBiome) && y <= 40;
            case THERMAL_CAVES -> y <= VANILLA_FEATURE_MAX_Y && in(HOT_GROUND, anchorBiome);
            case SALT_CAVES -> in(DRY, anchorBiome);
            case MUD_CAVES -> in(WET, anchorBiome) && y >= -16;
            case INFESTED_CAVES -> y <= 200;
            // Water caves: above the lava of the deepest caves.
            case UNDERGROUND_LAKES -> y >= WATER_MIN_Y;
            case GLOWWORM_GROTTO -> y >= WATER_MIN_Y && (in(HUMID, anchorBiome) || in(TROPICAL, anchorBiome));
            case KARST_CAVES -> y >= WATER_MIN_Y && (anchorBiome == KARST_SURFACE || in(TROPICAL, anchorBiome)
                    || in(HUMID, anchorBiome));
            default -> true;   // fungal, spider nest, copper, breakdown chambers: anywhere
        };
    }

    /** Terra's karst towers surface biome. */
    private static final short KARST_SURFACE = 92;

    /** Water caves need a river or lake nearby, except karst caves under karst towers. */
    private static boolean needsInlandWater(short id, short anchorBiome) {
        return switch (id) {
            case UNDERGROUND_LAKES, GLOWWORM_GROTTO -> true;
            case KARST_CAVES -> anchorBiome != KARST_SURFACE;
            default -> false;
        };
    }

    // ---- Water caves ----

    /** No water caves below this (the disabled aquifer puts lava in every cave below Y -54). */
    public static int WATER_MIN_Y = -40;

    public static boolean isWaterCave(short id) {
        return id == UNDERGROUND_LAKES || id == GLOWWORM_GROTTO || id == KARST_CAVES;
    }

    /**
     * The water level of a region (top water block): 12 to 32 blocks above the bottom of its unwarped cell, so water
     * caves are part flooded, part dry above. Every block of cave air in the region at or below it is water.
     */
    public int waterLevel(long cell) {
        int cy = unpack(cell, 21);
        return cy * REGION_Y + 12 + (int) (draw2(cell) * 20f);
    }

    // ---- Cave shapes per biome ----

    /**
     * How much a cave biome opens its caverns up (subtracted from {@code CaveShapes.CHEESE_THRESHOLD}): fungal caves and
     * breakdown chambers are big rooms. Zero for everything else.
     */
    public static float chamberBias(short id) {
        return switch (id) {
            case FUNGAL_CAVES -> 0.10f;
            case BREAKDOWN_CHAMBERS -> 0.17f;
            default -> 0f;
        };
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static short weighted(short[] ids, float[] weights, float t) {
        float sum = 0;
        for (float w : weights) sum += w;
        float acc = 0, target = t * sum;
        for (int i = 0; i < ids.length; i++) {
            acc += weights[i];
            if (target < acc) return ids[i];
        }
        return ids[ids.length - 1];
    }

    /** Depth gates: a cave that doesn't suit this depth falls back to plain stone. */
    private static short gate(short id, int y) {
        return switch (id) {
            case ANDESITE_CAVES, DIORITE_CAVES, GRANITE_CAVES -> y >= STONE_VARIANT_MIN_Y ? id : NONE;
            case TUFF_CAVES -> y <= TUFF_MAX_Y ? id : NONE;
            case DRIPSTONE_CAVES -> y <= DRIPSTONE_MAX_Y ? id : NONE;
            default -> id;
        };
    }
}
