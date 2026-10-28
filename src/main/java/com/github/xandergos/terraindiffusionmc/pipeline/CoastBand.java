package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * The coastal band where caves below sea level hold sea water (Oct 27).
 *
 * <p>Caves under land are kept dry and caves by the sea flood; a cave running from one into the other used to show a
 * standing wall of water where the flooded columns ended. Here the band is a smooth distance from the open sea (on an
 * 8-block world grid, bilinear between grid points, so it is the same for every chunk): inside {@link #RADIUS} caves
 * flood up to sea level; and in a rim {@link #PLUG} blocks wide just inside its edge the rock stays solid up to Y 63,
 * so a cave that crosses the edge meets a rock sill (a sump) instead of a wall of water. Pure Java.
 */
public final class CoastBand {
    private CoastBand() {
    }

    /** How far (blocks) from the open sea caves flood, and how wide the rock sill at the band's edge is. */
    public static float RADIUS = 96f, PLUG = 6f;
    /** Sill top: caves stay sealed up to this Y in the sill (one over the sea's top water block). */
    public static final int SILL_TOP = 63;

    private static final int GRID = 8, MAX = 160;

    /** Distance class to the open sea at grid point (gx, gz): the first ring (8 to 160, every 8) with sea, else 168. */
    private static float gridDistance(CaveBiomes.Surface s, int gx, int gz) {
        int x = gx * GRID, z = gz * GRID;
        if (sea(s, x, z)) return 0f;
        for (int r = 8; r <= MAX; r += 8) {
            int n = Math.max(8, r / 3);
            for (int k = 0; k < n; k++) {
                double t = 2 * Math.PI * k / n;
                if (sea(s, x + (int) Math.round(r * Math.cos(t)), z + (int) Math.round(r * Math.sin(t)))) return r;
            }
        }
        return MAX + 8f;
    }

    private static boolean sea(CaveBiomes.Surface s, int x, int z) {
        int g = s.groundTop(x, z);
        return g != Integer.MIN_VALUE && g < 61 && s.inlandWater(x, z) == Integer.MIN_VALUE;
    }

    // Grid memo (pure values; cleared when the tiles change).
    private static final int MEMO = 4096;
    private static final long[] memoKey = new long[MEMO];
    private static final float[] memoVal = new float[MEMO];
    private static final int[] memoGen = new int[MEMO];
    /** Set by the mod from LocalTerrainProvider.cacheGeneration(), so a new world forgets the memo. */
    public static volatile int generation;

    static {
        java.util.Arrays.fill(memoKey, Long.MIN_VALUE);
    }

    private static float grid(CaveBiomes.Surface s, int gx, int gz) {
        long key = ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> 52);
        int gen = generation;
        synchronized (memoKey) {
            if (memoKey[slot] == key && memoGen[slot] == gen) return memoVal[slot];
        }
        float v = gridDistance(s, gx, gz);
        synchronized (memoKey) {
            memoKey[slot] = key;
            memoGen[slot] = gen;
            memoVal[slot] = v;
        }
        return v;
    }

    /** Smooth distance (blocks, roughly) from (x, z) to the open sea, capped at 168. */
    public static float distance(int x, int z, CaveBiomes.Surface s) {
        int gx = Math.floorDiv(x, GRID), gz = Math.floorDiv(z, GRID);
        float fx = (x - gx * GRID) / (float) GRID, fz = (z - gz * GRID) / (float) GRID;
        float a = grid(s, gx, gz), b = grid(s, gx + 1, gz), c = grid(s, gx, gz + 1), d = grid(s, gx + 1, gz + 1);
        return (a * (1 - fx) + b * fx) * (1 - fz) + (c * (1 - fx) + d * fx) * fz;
    }

    /** Whether caves below sea level in column (x, z) hold sea water. */
    public static boolean flooded(int x, int z, CaveBiomes.Surface s) {
        return distance(x, z, s) < RADIUS;
    }

    /** Whether column (x, z) is in the rock sill at the band's edge. */
    public static boolean sill(int x, int z, CaveBiomes.Surface s) {
        float d = distance(x, z, s);
        return d >= RADIUS - PLUG && d < RADIUS;
    }
}
