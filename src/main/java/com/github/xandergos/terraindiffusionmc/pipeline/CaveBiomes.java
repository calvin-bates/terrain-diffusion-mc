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

    /** Id → biome name ({@code terra} namespace unless it has its own), for the biome source and the finder. */
    public static final Map<Short, String> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put(ANDESITE_CAVES, "terra:andesite_caves");
        NAMES.put(DIORITE_CAVES, "terra:diorite_caves");
        NAMES.put(GRANITE_CAVES, "terra:granite_caves");
        NAMES.put(TUFF_CAVES, "terra:tuff_caves");
        NAMES.put(DRIPSTONE_CAVES, "minecraft:dripstone_caves");
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
     * The cave biome at block (x, y, z), {@code refTop} being the top block of the lowest ground nearby (the same
     * reference the cave roof uses), or {@link #NONE} for plain stone caves and everything above the cave layer.
     */
    public short at(int x, int y, int z, int refTop) {
        if (refTop - y < START_DEPTH) return NONE;
        return pick(x, y, z);
    }

    /** The cave biome a point's region draws, after the depth gates (no surface check). */
    public short pick(int x, int y, int z) {
        float u = regionDraw(x, y, z);
        float total = COMMON_SHARE + UNCOMMON_SHARE + RARE_SHARE + VERY_RARE_SHARE;
        u *= total;
        if (u < PLAIN_SHARE) return NONE;
        if (u < COMMON_SHARE) {
            float t = (u - PLAIN_SHARE) / Math.max(1e-6f, COMMON_SHARE - PLAIN_SHARE);
            return gate(weighted(COMMON, COMMON_WEIGHT, t), y);
        }
        // Uncommon, rare and very rare tiers: parts two and three. Plain stone until then.
        return NONE;
    }

    /** The region's draw in [0, 1): a hash of the warped region cell and the seed. */
    public float regionDraw(int x, int y, int z) {
        float wx = x + WARP * warpX.GetNoise(x, y, z);
        float wy = y + WARP * 0.5f * warpY.GetNoise(x, y, z);
        float wz = z + WARP * warpZ.GetNoise(x, y, z);
        int cx = (int) Math.floor(wx / REGION_XZ);
        int cy = (int) Math.floor(wy / REGION_Y);
        int cz = (int) Math.floor(wz / REGION_XZ);
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h = mix(h + cx * 0xC2B2AE3D27D4EB4FL);
        h = mix(h + cy * 0x165667B19E3779F9L);
        h = mix(h + cz * 0x27D4EB2F165667C5L);
        return (h >>> 40) / (float) (1L << 24);
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
