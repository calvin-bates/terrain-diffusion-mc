package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Terra's own noise caves (caves plan v1.5 part one): big "cheese" caverns, long winding "spaghetti" tunnels and thin
 * "noodle" tunnels, in the spirit of vanilla's noise caves but measured from Terra's surface, so caves exist under any
 * terrain (inside a mountain at Y 1500 too), not only in vanilla's fixed Y range.
 *
 * <p>The cave field is a density: positive is rock, negative is cave air, roughly in blocks. Each family is evaluated
 * on a world-aligned lattice and interpolated trilinearly in between: caverns on {@link #CAVERN_XZ} x
 * {@link #CAVERN_Y} x {@link #CAVERN_XZ} cells (vanilla's noise cell size), tunnels on finer {@link #TUNNEL} cubes so
 * a tunnel a few blocks thick doesn't break into straight slivers along the lattice. Any block gets the same value
 * whichever chunk, tile or query computes it (seam-free by construction). The world density sampler adds a roof term
 * ({@link #roof}) so caves stay under the ground, and takes the minimum with the terrain density.
 *
 * <p>Pure Java (no Minecraft classes) so test harnesses can render it.
 */
public final class CaveShapes {
    public static final int CAVERN_XZ = 4, CAVERN_Y = 8, TUNNEL = 4;

    // ---- Tunables (harness: about 8% of the underground is cave air, as in vanilla's caves) ----
    /** Cheese caverns: air where the cavern noise exceeds this (higher = fewer, smaller caverns). */
    public static float CHEESE_THRESHOLD = 0.45f;
    /** How much the regional abundance noise moves the cheese threshold (cavern-rich and cavern-poor regions). */
    public static float CHEESE_REGION = 0.14f;
    /** Spaghetti tunnels: half-width in noise units, and how much a slow noise widens or narrows them. */
    public static float SPAGHETTI_WIDTH = 0.11f, SPAGHETTI_WIDTH_VAR = 0.035f;
    /** Noodle tunnels: half-width in noise units; they only run where their toggle noise is above the gate. */
    public static float NOODLE_WIDTH = 0.075f, NOODLE_GATE = -0.1f;

    /** Density units per noise unit for each family (sets how sharply walls form, in blocks). */
    private static final float CHEESE_GAIN = 45f, SPAGHETTI_GAIN = 70f, NOODLE_GAIN = 80f;
    /** Rock added per block of missing roof (see {@link #roof}). */
    public static final float ROOF_GAIN = 1.5f;
    /** The field is clamped to +-this, keeping the roof arithmetic simple. */
    private static final float CLAMP = 64f;

    private final FastNoiseLite cheese, region, spagA, spagB, spagWidth, noodleA, noodleB, noodleGate, entrance;
    private final long seed;

    private CaveShapes(long seed) {
        this.seed = seed;
        int s = (int) (seed ^ (seed >>> 32));
        cheese = fbm(s + 0x51c3, 1f / 128f, 3);
        region = simplex(s + 0x52a1, 1f / 640f);
        spagA = simplex(s + 0x53b7, 1f / 96f);
        spagB = simplex(s + 0x54d9, 1f / 96f);
        spagWidth = simplex(s + 0x55e1, 1f / 220f);
        noodleA = simplex(s + 0x56f3, 1f / 56f);
        noodleB = simplex(s + 0x5713, 1f / 56f);
        noodleGate = simplex(s + 0x5821, 1f / 160f);
        entrance = fbm(s + 0x5937, 1f / 260f, 2);
    }

    private static FastNoiseLite simplex(int seed, float freq) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        return n;
    }

    private static FastNoiseLite fbm(int seed, float freq, int octaves) {
        FastNoiseLite n = simplex(seed, freq);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(octaves);
        return n;
    }

    private static volatile CaveShapes current;

    /** The cave field for a world seed (built once per seed). */
    public static CaveShapes forSeed(long seed) {
        CaveShapes c = current;
        if (c == null || c.seed != seed) {
            c = new CaveShapes(seed);
            current = c;
        }
        return c;
    }

    /**
     * Caverns at a cavern lattice point: wider than tall, more of them in some regions than others, and bigger where
     * the cave biome asks for big rooms ({@code bias}, see {@link #chamberBias}).
     */
    public float cavernValue(int x, int y, int z, float bias) {
        float c = cheese.GetNoise(x, y * 1.6f, z);
        float t = CHEESE_THRESHOLD - bias - CHEESE_REGION * region.GetNoise(x, y * 0.5f, z);
        return clamp((t - c) * CHEESE_GAIN);
    }

    // ---- Chamber bias: cave biomes with big rooms (CaveBiomes.chamberBias), smoothed over their region edges ----

    /** Spacing of the chamber bias lattice (blocks), and how far around each point it looks to soften region edges. */
    private static final int BIAS_CELL = 32, BIAS_REACH = 16;

    /**
     * The chamber bias at a bias lattice point: the average of {@link CaveBiomes#chamberBias} over the point and four
     * points around it, so the bigger caverns fade in over about 40 blocks instead of stopping at a region's wall.
     */
    private float biasPoint(CaveBiomes layer, CaveBiomes.Surface surface, int x, int y, int z) {
        if (layer == null) return 0f;
        float b = CaveBiomes.chamberBias(layer.pick(x, y, z, surface))
                + CaveBiomes.chamberBias(layer.pick(x + BIAS_REACH, y, z, surface))
                + CaveBiomes.chamberBias(layer.pick(x - BIAS_REACH, y, z, surface))
                + CaveBiomes.chamberBias(layer.pick(x, y, z + BIAS_REACH, surface))
                + CaveBiomes.chamberBias(layer.pick(x, y, z - BIAS_REACH, surface));
        return b * 0.2f;
    }

    /** The chamber bias anywhere: trilinear between bias lattice points (the same value fill() uses). */
    private float biasAt(CaveBiomes layer, CaveBiomes.Surface surface, int x, int y, int z) {
        int gx = Math.floorDiv(x, BIAS_CELL), gy = Math.floorDiv(y, BIAS_CELL), gz = Math.floorDiv(z, BIAS_CELL);
        float[] l = new float[8];
        for (int iz = 0; iz < 2; iz++)
            for (int ix = 0; ix < 2; ix++)
                for (int iy = 0; iy < 2; iy++)
                    l[(iz * 2 + ix) * 2 + iy] = biasPoint(layer, surface, (gx + ix) * BIAS_CELL, (gy + iy) * BIAS_CELL, (gz + iz) * BIAS_CELL);
        float inv = 1f / BIAS_CELL;
        return trilinear(l, 0, 2, 4, 6, (x - gx * BIAS_CELL) * inv, (y - gy * BIAS_CELL) * inv, (z - gz * BIAS_CELL) * inv);
    }

    /** Tunnels at a tunnel lattice point: the union of spaghetti and (where gated in) noodles. */
    public float tunnelValue(int x, int y, int z) {
        // Spaghetti: tubes where two independent noises are both near zero. Isotropic on purpose: squashing y lays both
        // zero sheets flat, and where they lie nearly on top of each other their overlap is a thin pancake, not a tube.
        float a = Math.abs(spagA.GetNoise(x, y, z));
        float b = Math.abs(spagB.GetNoise(x, y, z));
        float w = SPAGHETTI_WIDTH + SPAGHETTI_WIDTH_VAR * spagWidth.GetNoise(x, y, z);
        float d = (Math.max(a, b) - w) * SPAGHETTI_GAIN;
        // Noodles: thinner and twistier, only in part of the world.
        if (noodleGate.GetNoise(x, y, z) > NOODLE_GATE) {
            float na = Math.abs(noodleA.GetNoise(x, y, z));
            float nb = Math.abs(noodleB.GetNoise(x, y, z));
            d = Math.min(d, (Math.max(na, nb) - NOODLE_WIDTH) * NOODLE_GAIN);
        }
        return clamp(d);
    }

    private static float clamp(float v) {
        return Math.max(-CLAMP, Math.min(CLAMP, v));
    }

    /** Scratch arrays for {@link #fill}; one per thread, passed back each call. */
    public static final class Scratch {
        float[] cavern, tunnel, bias;
    }

    /**
     * Fills {@code out} with the cave field for blocks x0..x0+sx-1, z0..z0+sz-1, y0..y0+sy-1, indexed
     * {@code (x + z * sx) * sy + y} (the same order as Minecraft's density volumes). {@code surface} is what the cave
     * biome layer reads for chamber sizes (null: no cave biomes).
     */
    public void fill(int x0, int z0, int sx, int sz, int y0, int sy, float[] out, Scratch scratch, CaveBiomes.Surface surface) {
        CaveBiomes layer = surface == null ? null : CaveBiomes.forSeed(seed);
        // Caverns first, written straight into out; then the tunnels' minimum on top.
        scratch.cavern = fillFamily(true, x0, z0, sx, sz, y0, sy, out, scratch.cavern, false, layer, surface, scratch);
        scratch.tunnel = fillFamily(false, x0, z0, sx, sz, y0, sy, out, scratch.tunnel, true, null, null, scratch);
    }

    private float[] fillFamily(boolean cavern, int x0, int z0, int sx, int sz, int y0, int sy, float[] out,
                               float[] lattice, boolean min, CaveBiomes layer, CaveBiomes.Surface surface, Scratch scratch) {
        int cxz = cavern ? CAVERN_XZ : TUNNEL, cy = cavern ? CAVERN_Y : TUNNEL;
        int lx0 = Math.floorDiv(x0, cxz), lx1 = Math.floorDiv(x0 + sx - 1, cxz) + 1;
        int lz0 = Math.floorDiv(z0, cxz), lz1 = Math.floorDiv(z0 + sz - 1, cxz) + 1;
        int ly0 = Math.floorDiv(y0, cy), ly1 = Math.floorDiv(y0 + sy - 1, cy) + 1;
        int nx = lx1 - lx0 + 1, nz = lz1 - lz0 + 1, ny = ly1 - ly0 + 1;
        int n = nx * ny * nz;
        if (lattice == null || lattice.length < n) lattice = new float[n];
        // Chamber bias for the caverns, on its own coarse lattice covering the cavern lattice.
        int bx0 = 0, by0 = 0, bz0 = 0, bnx = 0, bny = 0;
        float[] bias = null;
        boolean anyBias = false;
        if (cavern && layer != null) {
            bx0 = Math.floorDiv(lx0 * cxz, BIAS_CELL);
            by0 = Math.floorDiv(ly0 * cy, BIAS_CELL);
            bz0 = Math.floorDiv(lz0 * cxz, BIAS_CELL);
            bnx = Math.floorDiv(lx1 * cxz, BIAS_CELL) - bx0 + 2;
            bny = Math.floorDiv(ly1 * cy, BIAS_CELL) - by0 + 2;
            int bnz = Math.floorDiv(lz1 * cxz, BIAS_CELL) - bz0 + 2;
            int bn = bnx * bny * bnz;
            if (scratch.bias == null || scratch.bias.length < bn) scratch.bias = new float[bn];
            bias = scratch.bias;
            for (int iz = 0; iz < bnz; iz++)
                for (int ix = 0; ix < bnx; ix++)
                    for (int iy = 0; iy < bny; iy++) {
                        float b = biasPoint(layer, surface, (bx0 + ix) * BIAS_CELL, (by0 + iy) * BIAS_CELL, (bz0 + iz) * BIAS_CELL);
                        bias[(iz * bnx + ix) * bny + iy] = b;
                        anyBias |= b != 0f;
                    }
        }
        float binv = 1f / BIAS_CELL;
        for (int iz = 0; iz < nz; iz++)
            for (int ix = 0; ix < nx; ix++)
                for (int iy = 0; iy < ny; iy++) {
                    int bx = (lx0 + ix) * cxz, by = (ly0 + iy) * cy, bz = (lz0 + iz) * cxz;
                    float v;
                    if (!cavern) {
                        v = tunnelValue(bx, by, bz);
                    } else {
                        float b = 0f;
                        if (anyBias) {
                            int gx = Math.floorDiv(bx, BIAS_CELL), gy = Math.floorDiv(by, BIAS_CELL), gz = Math.floorDiv(bz, BIAS_CELL);
                            int jx = gx - bx0, jy = gy - by0, jz = gz - bz0;
                            b = trilinear(bias, (jz * bnx + jx) * bny + jy, (jz * bnx + jx + 1) * bny + jy,
                                    ((jz + 1) * bnx + jx) * bny + jy, ((jz + 1) * bnx + jx + 1) * bny + jy,
                                    (bx - gx * BIAS_CELL) * binv, (by - gy * BIAS_CELL) * binv, (bz - gz * BIAS_CELL) * binv);
                        }
                        v = cavernValue(bx, by, bz, b);
                    }
                    lattice[(iz * nx + ix) * ny + iy] = v;
                }
        float invXZ = 1f / cxz, invY = 1f / cy;
        for (int z = 0; z < sz; z++) {
            int bz = z0 + z, gz = Math.floorDiv(bz, cxz), iz = gz - lz0;
            float fz = (bz - gz * cxz) * invXZ;
            for (int x = 0; x < sx; x++) {
                int bx = x0 + x, gx = Math.floorDiv(bx, cxz), ix = gx - lx0;
                float fx = (bx - gx * cxz) * invXZ;
                int b00 = (iz * nx + ix) * ny, b10 = (iz * nx + ix + 1) * ny;
                int b01 = ((iz + 1) * nx + ix) * ny, b11 = ((iz + 1) * nx + ix + 1) * ny;
                int base = (x + z * sx) * sy;
                int cell = Integer.MIN_VALUE;
                boolean rock = false;
                for (int y = 0; y < sy; y++) {
                    int by = y0 + y, gy = Math.floorDiv(by, cy), iy = gy - ly0;
                    if (iy != cell) {
                        // A cell whose eight corners are all rock is rock throughout (trilinear weights are convex):
                        // skip the interpolation. Only the sign of the field is used, so the exact value doesn't matter.
                        cell = iy;
                        rock = lattice[b00 + iy] > 0 && lattice[b00 + iy + 1] > 0 && lattice[b10 + iy] > 0
                                && lattice[b10 + iy + 1] > 0 && lattice[b01 + iy] > 0 && lattice[b01 + iy + 1] > 0
                                && lattice[b11 + iy] > 0 && lattice[b11 + iy + 1] > 0;
                    }
                    if (rock) {
                        if (!min) out[base + y] = CLAMP;
                        continue;
                    }
                    float v = trilinear(lattice, b00 + iy, b10 + iy, b01 + iy, b11 + iy, fx, (by - gy * cy) * invY, fz);
                    out[base + y] = min ? Math.min(out[base + y], v) : v;
                }
            }
        }
        return lattice;
    }

    /** The cave field at one block (the same value {@link #fill} gives it with the same surface). */
    public float at(int x, int y, int z, CaveBiomes.Surface surface) {
        CaveBiomes layer = surface == null ? null : CaveBiomes.forSeed(seed);
        return Math.min(pointValue(true, x, y, z, layer, surface), pointValue(false, x, y, z, null, null));
    }

    private float pointValue(boolean cavern, int x, int y, int z, CaveBiomes layer, CaveBiomes.Surface surface) {
        int cxz = cavern ? CAVERN_XZ : TUNNEL, cy = cavern ? CAVERN_Y : TUNNEL;
        int gx = Math.floorDiv(x, cxz), gy = Math.floorDiv(y, cy), gz = Math.floorDiv(z, cxz);
        float[] l = new float[8];
        // Same index layout as fillFamily() with nx = nz = ny = 2.
        for (int iz = 0; iz < 2; iz++)
            for (int ix = 0; ix < 2; ix++)
                for (int iy = 0; iy < 2; iy++) {
                    int bx = (gx + ix) * cxz, by = (gy + iy) * cy, bz = (gz + iz) * cxz;
                    l[(iz * 2 + ix) * 2 + iy] = cavern
                            ? cavernValue(bx, by, bz, layer == null ? 0f : biasAt(layer, surface, bx, by, bz))
                            : tunnelValue(bx, by, bz);
                }
        boolean rock = true;
        for (float v : l) rock &= v > 0;
        if (rock) return CLAMP;   // as in fillFamily
        float invXZ = 1f / cxz, invY = 1f / cy;
        return trilinear(l, 0, 2, 4, 6, (x - gx * cxz) * invXZ, (y - gy * cy) * invY, (z - gz * cxz) * invXZ);
    }

    private static float trilinear(float[] l, int i00, int i10, int i01, int i11, float fx, float fy, float fz) {
        float c00 = l[i00] + (l[i00 + 1] - l[i00]) * fy;
        float c10 = l[i10] + (l[i10 + 1] - l[i10]) * fy;
        float c01 = l[i01] + (l[i01 + 1] - l[i01]) * fy;
        float c11 = l[i11] + (l[i11 + 1] - l[i11]) * fy;
        float c0 = c00 + (c10 - c00) * fx;
        float c1 = c01 + (c11 - c01) * fx;
        return c0 + (c1 - c0) * fz;
    }

    // ---- Roof and entrances ----

    /** Solid roof kept over caves, in blocks below the lowest nearby ground: dry land, the sea floor, inland water. */
    public static int ROOF_LAND = 10, ROOF_SEA = 6, ROOF_WATER = 16;
    /** Entrances: where the entrance noise is above the first value the roof starts thinning, gone at the second. */
    public static float ENTRANCE_START = 0.50f, ENTRANCE_FULL = 0.62f;

    /**
     * Roof depth over dry land at column (x, z): {@link #ROOF_LAND}, thinning to 0 in rare entrance patches where
     * caves may break the surface.
     */
    public float landRoof(int x, int z) {
        float e = entrance.GetNoise(x, z);
        if (e <= ENTRANCE_START) return ROOF_LAND;
        float t = Math.min(1f, (e - ENTRANCE_START) / (ENTRANCE_FULL - ENTRANCE_START));
        return ROOF_LAND * (1f - t * t * (3f - 2f * t));
    }

    /**
     * Rock added at {@code depth} blocks under the reference ground for a roof of {@code roofDepth} blocks; above the
     * reference ground (negative depth, the upper part of slopes and cliffs) always enough to stay solid, so caves
     * never break out of a cliff face or hillside, only through flat ground in entrance patches.
     */
    public static float roof(int depth, float roofDepth) {
        if (depth < 0) return CLAMP + 1f;
        float missing = roofDepth - depth;
        return missing > 0 ? missing * ROOF_GAIN : 0f;
    }

    /** Highest Y caves can reach under a reference ground top: the reference top itself (see {@link #roof}). */
    public static int caveTop(int refTop) {
        return refTop;
    }
}
