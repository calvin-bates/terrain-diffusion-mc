package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Sea caves (caves plan v1.5 part two): grottos cut into sea cliffs and rocky coasts at sea level, flooded by the sea.
 *
 * <p>Along stretches of coast picked by a slow 2D noise, columns of high ground near the sea get a horizontal tunnel
 * field: winding passages in plan (where a 2D noise is near zero) a few blocks above and below sea level, with a
 * rounded roof. Unlike the noise caves they may break out of a cliff face, which is the point: where a passage meets
 * the cliff it leaves an arch or grotto mouth at the waterline. A few blocks of rock are kept over every passage.
 * World/DryCaves treats these columns as sea, so the passages fill with sea water. Pure Java.
 */
public final class SeaCaves {
    /** Sea level (Terra's noise settings) and the band of the passages around it. */
    private static final int SEA = 63;
    /** Share of coast that gets sea caves: the coast noise must be above this (higher = rarer). */
    public static float COAST_GATE = 0.25f;
    /** Passage half-width in noise units, and how high (blocks) the passages rise above the water. */
    public static float WIDTH = 0.28f;
    private static final float GAIN = 40f;
    /** How far inland (blocks) passages reach from the sea, and the least ground height over the sea for them. */
    public static final int REACH = 24, MIN_GROUND_ABOVE_SEA = 5;
    /** Rock kept over a passage. */
    private static final int ROOF = 4;

    private final FastNoiseLite coast, path, height, grotto;
    private final long seed;

    private SeaCaves(long seed) {
        this.seed = seed;
        int s = (int) (seed ^ (seed >>> 32));
        coast = noise(s + 0x7a11, 1f / 320f);
        path = noise(s + 0x7b23, 1f / 56f);
        height = noise(s + 0x7c35, 1f / 60f);
        grotto = noise(s + 0x7d47, 1f / 18f);
    }

    private static FastNoiseLite noise(int seed, float freq) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        return n;
    }

    private static volatile SeaCaves current;

    public static SeaCaves forSeed(long seed) {
        SeaCaves c = current;
        if (c == null || c.seed != seed) {
            c = new SeaCaves(seed);
            current = c;
        }
        return c;
    }

    /** Ring of samples (8 directions at 8, 16 and 24 blocks) used to find the sea near a column. */
    private static final int[] RING = ring();

    private static int[] ring() {
        int[] r = new int[2 * 24];
        int k = 0;
        for (int d = 8; d <= REACH; d += 8)
            for (int a = 0; a < 8; a++) {
                double t = a * Math.PI / 4;
                r[k++] = (int) Math.round(d * Math.cos(t));
                r[k++] = (int) Math.round(d * Math.sin(t));
            }
        return r;
    }

    /** Whether column (x, z) is open sea at the surface: ground under sea level and no river or lake. */
    private static boolean sea(CaveBiomes.Surface s, int x, int z) {
        int g = s.groundTop(x, z);
        return g != Integer.MIN_VALUE && g < SEA - 1 && s.inlandWater(x, z) == Integer.MIN_VALUE;
    }

    /**
     * Whether column (x, z) can hold sea caves: on a sea-cave stretch of coast, ground at least a few blocks above the
     * sea, and open sea within {@link #REACH} blocks.
     */
    public boolean active(int x, int z, CaveBiomes.Surface s) {
        return seaDistance(x, z, s) > 0;
    }

    /** How far the sea is from an active column (8, 16 or 24 blocks), or 0 if the column can't hold sea caves. */
    public int seaDistance(int x, int z, CaveBiomes.Surface s) {
        if (coast.GetNoise(x, z) < COAST_GATE) return 0;
        int g = s.groundTop(x, z);
        if (g == Integer.MIN_VALUE || g < SEA + MIN_GROUND_ABOVE_SEA) return 0;
        if (s.inlandWater(x, z) != Integer.MIN_VALUE) return 0;
        for (int k = 0; k < RING.length; k += 2) if (sea(s, x + RING[k], z + RING[k + 1])) return 8 + 8 * (k / 16);
        return 0;
    }

    /** Lowest Y a passage reaches (its floor is under the water). */
    public static final int MIN_Y = SEA - 6;

    /**
     * Density of the grottos and passages in an active column (negative is air, in blocks), {@code groundTop} being
     * its top block and {@code seaDistance} how far the sea is ({@link #seaDistance}), or +inf outside the band.
     * Grottos are rounded chambers at the waterline, biggest and most frequent right at the cliff face and dying out
     * {@link #REACH} blocks inland; passages wind further in.
     */
    public float value(int x, int y, int z, int groundTop, int seaDistance) {
        float p = Math.abs(path.GetNoise(x, z));
        float plan = (p - WIDTH) * GAIN;
        // Grottos: a 3D blob noise above a threshold that rises with the distance from the sea.
        float near = 1f - (seaDistance - 8) / (float) (REACH - 8 + 8);   // 1 at the face, ~0.33 at the reach
        float g3 = grotto.GetNoise(x, (y - SEA) * 1.5f, z);
        float grot = (0.62f - 0.5f * near - g3) * 30f;
        plan = Math.min(plan, grot);
        if (plan > 8f) return Float.POSITIVE_INFINITY;
        float h = 4f + 3f * (height.GetNoise(x, z) + 1f);   // 4 to 10 blocks of headroom above the water
        int ceiling = Math.min(SEA + (int) h, groundTop - ROOF);
        float floor = SEA - 5 - 2f * Math.max(0f, -plan) / 8f;   // deeper in the middle of a passage
        if (y > ceiling + 2 || y < MIN_Y) return Float.POSITIVE_INFINITY;
        // Rounded roof: the passage narrows towards its ceiling.
        float up = y - SEA;
        float roofShape = up <= 0 ? 0f : up / Math.max(1f, ceiling - SEA) * 6f;
        float vert = Math.max(floor - y, y - ceiling);
        return Math.max(plan + roofShape, vert);
    }
}
