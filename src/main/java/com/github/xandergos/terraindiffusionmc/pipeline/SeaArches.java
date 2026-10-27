package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.List;

/**
 * Sea arches and coastal overhangs (caves plan v1.5, the last cave features). Terra's surface is one height per column,
 * so rock over open water can't come from the terrain; these are cut into it like the other shapes that reach the
 * surface ({@link CaveSites}), and flood with sea water (world/DryCaves counts their columns as sea).
 *
 * <ul>
 *   <li><b>Sea arches:</b> where a high, narrow headland or a sea stack has the sea on both sides within a short
 *       distance, a tunnel at the waterline runs straight through it from sea to sea: a round-topped arch 6-12 wide
 *       and 6-13 tall over the water, with rock kept over it.</li>
 *   <li><b>Coastal overhangs:</b> wave-cut notches along the foot of sea cliffs: the cliff is undercut 3-7 blocks deep
 *       just above the water, the notch's roof sloping down to its back, so the cliff hangs out over the sea.</li>
 * </ul>
 *
 * <p>Everything is a pure function of world position and the surface's ground heights, so chunks agree. Pure Java.
 */
public final class SeaArches {
    private SeaArches() {
    }

    /** Sea level (Terra's noise settings). */
    public static final int SEA = 63;

    // ---- Tunables ----

    /** Arch candidates: one search per cell (blocks), at this chance. */
    public static final int ARCH_CELL = 64;
    public static float ARCH_CHANCE = 0.7f;
    /** Widest headland (blocks, sea to sea) an arch goes through, and the least ground over the sea along it. */
    public static int ARCH_MAX_SPAN = 26, ARCH_MIN_GROUND = 12;
    /** Share of sea cliffs with notches (a slow noise along the coast must be above this; -1 = all). */
    public static float NOTCH_GATE = -0.15f;
    /** Least ground over the sea (blocks) for a notch's cliff. */
    public static int NOTCH_MIN_GROUND = 8;
    /** The farthest an arch reaches from its middle (half its length plus its width), for finding arches near an area. */
    public static final int ARCH_REACH = ARCH_MAX_SPAN / 2 + 12;

    private static final FastNoiseLite NOTCH = noise(0x5EA7C4, 1f / 140f), DEPTH = noise(0x5EA7C5, 1f / 23f),
            HIGH = noise(0x5EA7C6, 1f / 17f);

    private static FastNoiseLite noise(int seed, float freq) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        return n;
    }

    /** Whether column (x, z) is open sea: ground under sea level and no river or lake. */
    private static boolean sea(CaveBiomes.Surface s, int x, int z) {
        int g = s.groundTop(x, z);
        return g != Integer.MIN_VALUE && g < SEA - 1 && s.inlandWater(x, z) == Integer.MIN_VALUE;
    }

    private static final float[] DX = new float[8], DZ = new float[8];

    static {
        for (int k = 0; k < 8; k++) {
            DX[k] = (float) Math.cos(k * Math.PI / 4);
            DZ[k] = (float) Math.sin(k * Math.PI / 4);
        }
    }

    // ---- Coastal overhangs (wave-cut notches) ----

    /**
     * The notch of column (x, z), packed as {@code d | depth << 8 | height << 16} (d the distance to the sea, 1-8), or
     * -1 if the column has none: a notched stretch of coast, ground at least {@link #NOTCH_MIN_GROUND} over the sea and
     * the notch's height plus 4 more, the sea within the notch's depth, and the ground staying high all the way to the
     * sea (a cliff, not a slope).
     */
    public static int notch(int x, int z, CaveBiomes.Surface s) {
        if (NOTCH.GetNoise(x, z) < NOTCH_GATE) return -1;
        int g = s.groundTop(x, z);
        if (g == Integer.MIN_VALUE || g < SEA + NOTCH_MIN_GROUND || s.inlandWater(x, z) != Integer.MIN_VALUE) return -1;
        int depth = 3 + Math.round(2f * (DEPTH.GetNoise(x, z) + 1f));   // 3 to 7
        int height = 3 + Math.round(1.5f * (HIGH.GetNoise(x, z) + 1f));   // 3 to 6
        if (g < SEA + height + 4) return -1;
        // Quick reject: the sea at the notch's depth in some direction.
        boolean any = false;
        for (int k = 0; k < 8 && !any; k++) any = sea(s, x + Math.round(depth * DX[k]), z + Math.round(depth * DZ[k]));
        if (!any) return -1;
        for (int r = 1; r <= depth; r++)
            for (int k = 0; k < 8; k++) {
                if (!sea(s, x + Math.round(r * DX[k]), z + Math.round(r * DZ[k]))) continue;
                // A cliff, not a slope: the ground between here and the sea stays high (rock over the notch).
                for (int j = 1; j < r; j++) {
                    int gj = s.groundTop(x + Math.round(j * DX[k]), z + Math.round(j * DZ[k]));
                    if (gj == Integer.MIN_VALUE || gj < SEA + height + 3) return -1;
                }
                return r | depth << 8 | height << 16;
            }
        return -1;
    }

    /** Density of a notch at a block of its column (negative is air), +inf where it doesn't reach. */
    public static float notchValue(int packed, int y) {
        if (packed < 0) return Float.POSITIVE_INFINITY;
        int d = packed & 0xFF, depth = (packed >> 8) & 0xFF, height = (packed >> 16) & 0xFF;
        float t = (d - 1f) / depth;   // 0 at the cliff face, nearly 1 at the back
        float ceiling = SEA + 0.5f + height * (1f - (float) Math.pow(t, 1.3));
        float floor = SEA - 3.5f;
        if (y < floor - 1 || y > ceiling + 1) return Float.POSITIVE_INFINITY;
        return Math.max(floor - y, y - ceiling);
    }

    /** Highest and lowest Y a notch reaches. */
    public static int notchTop(int packed) {
        return packed < 0 ? Integer.MIN_VALUE : SEA + 2 + ((packed >> 16) & 0xFF);
    }

    public static final int NOTCH_MIN_Y = SEA - 5;

    // ---- Sea arches ----

    /**
     * A sea arch: a straight tunnel from (ax, az) to (bx, bz) (both out at sea), half-width {@code width}, its floor
     * under the water and its round top {@code height} blocks over the sea.
     */
    public record Arch(float ax, float az, float bx, float bz, float width, float height, int ground, float phase)
            implements CaveSites.Cut {
        @Override
        public float value(int px, int py, int pz) {
            float floor = SEA - 5f;
            if (py < floor - 1 || py > SEA + height + 2) return Float.POSITIVE_INFINITY;
            float dx = bx - ax, dz = bz - az, len = (float) Math.sqrt(dx * dx + dz * dz);
            dx /= len;
            dz /= len;
            float ox = px - ax, oz = pz - az;
            float t = ox * dx + oz * dz;
            if (t < -2f || t > len + 2f) return Float.POSITIVE_INFINITY;
            float across = -ox * dz + oz * dx;
            // A little wider at the ends (the sea has worn the mouths), round on top.
            float m = Math.abs(2f * t / len - 1f);
            float w = width * (1f + 0.25f * m * m + 0.08f * (float) Math.sin(t / 2.7f + phase));
            float up = Math.max(0f, py - SEA);
            float qa = across / w, qy = up / height;
            float e = ((float) Math.sqrt(qa * qa + qy * qy) - 1f) * Math.min(w, height);
            return Math.max(e, floor - py);
        }

        @Override
        public boolean covers(int px, int pz) {
            float r = reach() + 1;
            float dx = px - x(), dz = pz - z();
            return dx * dx + dz * dz <= r * r;
        }

        @Override
        public float reach() {
            return (float) Math.hypot(bx - ax, bz - az) / 2f + width * 1.3f + 3f;
        }

        @Override
        public int top() {
            return SEA + (int) Math.ceil(height) + 2;
        }

        @Override
        public int minY() {
            return SEA - 6;
        }

        @Override
        public int x() {
            return Math.round((ax + bx) / 2f);
        }

        @Override
        public int z() {
            return Math.round((az + bz) / 2f);
        }

        @Override
        public String kind() {
            return "sea_arch";
        }

        /** Whether column (px, pz) lies in the arch's footprint (its columns hold sea water). */
        public boolean wet(int px, int pz) {
            return value(px, SEA - 2, pz) < 0f;
        }
    }

    /** The arches reaching the block rectangle, added to out. */
    public static void arches(int x0, int z0, int x1, int z1, CaveBiomes.Surface s, List<CaveSites.Cut> out) {
        int gx0 = Math.floorDiv(x0 - ARCH_REACH, ARCH_CELL), gx1 = Math.floorDiv(x1 + ARCH_REACH, ARCH_CELL);
        int gz0 = Math.floorDiv(z0 - ARCH_REACH, ARCH_CELL), gz1 = Math.floorDiv(z1 + ARCH_REACH, ARCH_CELL);
        for (int gz = gz0; gz <= gz1; gz++)
            for (int gx = gx0; gx <= gx1; gx++) {
                Arch a = arch(gx, gz, s);
                if (a == null) continue;
                float r = a.reach();
                if (a.x() + r < x0 || a.x() - r > x1 || a.z() + r < z0 || a.z() - r > z1) continue;
                out.add(a);
            }
    }

    // A small memo: arch searches read many ground heights, and every chunk near a cell asks for it again.
    private static final int MEMO = 256;
    private static final long[] memoKey = new long[MEMO];
    private static final Arch[] memoVal = new Arch[MEMO];
    private static final int[] memoGen = new int[MEMO];
    private static final Arch NONE = new Arch(0, 0, 1, 0, 0, 0, 0, 0);
    /** Bumped by the mod when tiles are reloaded (a new world), so the memo forgets. */
    public static volatile int generation;

    static {
        java.util.Arrays.fill(memoKey, Long.MIN_VALUE);
    }

    /** The arch of a cell, or null. */
    public static Arch arch(int gx, int gz, CaveBiomes.Surface s) {
        long key = ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
        int slot = (int) Math.floorMod(key * 0x9E3779B97F4A7C15L >>> 40, (long) MEMO);
        int gen = generation;
        synchronized (memoKey) {
            if (memoKey[slot] == key && memoGen[slot] == gen) return memoVal[slot] == NONE ? null : memoVal[slot];
        }
        Arch a = search(gx, gz, s);
        synchronized (memoKey) {
            memoKey[slot] = key;
            memoGen[slot] = gen;
            memoVal[slot] = a == null ? NONE : a;
        }
        return a;
    }

    /**
     * Searches a cell for a narrow headland or stack: points 4 blocks apart (in an order shuffled by the cell's hash)
     * with high ground and the sea within 13 blocks on two opposite sides along one of four axes; the first one whose
     * span is at most {@link #ARCH_MAX_SPAN} and whose ground stays high enough over the arch all the way across.
     */
    private static Arch search(int gx, int gz, CaveBiomes.Surface s) {
        long h = mix(gx * 0x632BE59BD9B4E019L ^ gz * 0x85EBCA77C2B2AE63L ^ 0xA5C4L);
        if (unit(h, 0) >= ARCH_CHANCE) return null;
        int n = ARCH_CELL / 4;
        int off = (int) (unit(h, 8) * n * n), stride = 37;   // 37 is coprime with 256
        for (int k = 0; k < n * n; k++) {
            int i = Math.floorMod(off + k * stride, n * n);
            int x = gx * ARCH_CELL + (i % n) * 4 + 2, z = gz * ARCH_CELL + (i / n) * 4 + 2;
            int g = s.groundTop(x, z);
            if (g == Integer.MIN_VALUE || g < SEA + ARCH_MIN_GROUND || s.inlandWater(x, z) != Integer.MIN_VALUE) continue;
            // Quick reject: the sea 12 blocks away in some direction.
            boolean near = false;
            for (int d = 0; d < 8 && !near; d++) near = sea(s, x + Math.round(12 * DX[d]), z + Math.round(12 * DZ[d]));
            if (!near) continue;
            Arch best = null;
            float bestSpan = Float.MAX_VALUE;
            for (int d = 0; d < 4; d++) {
                int p = reachSea(s, x, z, DX[d], DZ[d]), q = reachSea(s, x, z, -DX[d], -DZ[d]);
                if (p < 0 || q < 0) continue;
                int span = p + q;
                if (span > ARCH_MAX_SPAN || span >= bestSpan) continue;
                // The lowest ground across the headland between the two shores sets the arch's height.
                int low = Integer.MAX_VALUE;
                for (int t = -q + 2; t <= p - 2; t++) {
                    int gt = s.groundTop(x + Math.round(t * DX[d]), z + Math.round(t * DZ[d]));
                    if (gt == Integer.MIN_VALUE) { low = Integer.MIN_VALUE; break; }
                    low = Math.min(low, gt);
                }
                if (low == Integer.MIN_VALUE || low == Integer.MAX_VALUE) continue;
                float height = Math.min(6f + 7f * unit(h, 16), low - SEA - 5f);
                if (height < 5f) continue;
                float width = Math.min(3f + 3f * unit(h, 24), height * 0.7f);
                // Widthwise the headland must hold too: ground high beside the arch's middle.
                float px = -DZ[d], pz = DX[d];
                int side1 = s.groundTop(x + Math.round((width + 2) * px), z + Math.round((width + 2) * pz));
                int side2 = s.groundTop(x - Math.round((width + 2) * px), z - Math.round((width + 2) * pz));
                if (side1 < SEA + height + 3 || side2 < SEA + height + 3) continue;
                bestSpan = span;
                best = new Arch(x - (q + 3) * DX[d], z - (q + 3) * DZ[d], x + (p + 3) * DX[d], z + (p + 3) * DZ[d],
                        width, height, g, unit(h, 32) * 6.28f);
            }
            if (best != null) return best;
        }
        return null;
    }

    /** Blocks from (x, z) to the first sea column along a direction, within 13, or -1. */
    private static int reachSea(CaveBiomes.Surface s, int x, int z, float dx, float dz) {
        for (int t = 2; t <= 13; t++)
            if (sea(s, x + Math.round(t * dx), z + Math.round(t * dz))) return t;
        return -1;
    }

    private static float unit(long h, int shift) {
        return ((h >>> shift) & 0xFF) / 256f;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
