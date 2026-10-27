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
    private final FastNoiseLite chasmNoise, slotNoise, tubeNoise;
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
        chasmNoise = simplex(s + 0x5a49, 1f / 260f);
        slotNoise = simplex(s + 0x5b5b, 1f / 40f);
        tubeNoise = simplex(s + 0x5c6d, 1f / 140f);
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

    // ---- Region shapes: what the cave biome layer asks of the cave field (CaveBiomes.chamberBias, shapeFamily) ----
    //
    // A coarse lattice ({@link #SHAPE_CELL} blocks) of shape channels, each the average over a point and four points
    // around it, so shapes fade in over region edges instead of stopping at a wall. Channels: 0 chamber bias; 1-2
    // chasm weight and weighted half-width; 3-4 slot; 5-7 tube weight, weighted centre height and half-width; 8-11 hall
    // weight and weighted centre. Where a shape's weight is below 1 it is pushed back by (1 - weight) * FADE blocks.

    private static final int SHAPE_CELL = 32, SHAPE_CELL_Y = 16, SHAPE_REACH = 16, CH = 12;
    private static final float FADE = 60f;
    /** Blocks per noise unit for the region shapes (about one over each noise's gradient). */
    private static final float CHASM_GAIN = 26f, SLOT_GAIN = 4f, TUBE_GAIN = 14f;
    /** Giant hall radii (blocks). */
    public static final float HALL_RX = 24f, HALL_RY = 16f;
    private static final int[] SHAPE_OFFSETS = {0, 0, SHAPE_REACH, 0, -SHAPE_REACH, 0, 0, SHAPE_REACH, 0, -SHAPE_REACH};

    /** The shape channels at a shape lattice point, written to out[off..off+CH). Returns whether any is non-zero. */
    private boolean shapePoint(CaveBiomes layer, CaveBiomes.Surface surface, int x, int y, int z, float[] out, int off) {
        for (int k = 0; k < CH; k++) out[off + k] = 0f;
        boolean any = false;
        for (int k = 0; k < SHAPE_OFFSETS.length; k += 2) {
            int px = x + SHAPE_OFFSETS[k], pz = z + SHAPE_OFFSETS[k + 1];
            short id = layer.pick(px, y, pz, surface);
            float b = CaveBiomes.chamberBias(id);
            out[off] += b;
            any |= b != 0f;
            int fam = CaveBiomes.shapeFamily(id);
            if (fam == CaveBiomes.SHAPE_NONE) continue;
            any = true;
            long cell = layer.regionCell(px, y, pz);
            float w = CaveBiomes.shapeWidth(id);
            switch (fam) {
                case CaveBiomes.SHAPE_CHASM -> { out[off + 1] += 1f; out[off + 2] += w; }
                case CaveBiomes.SHAPE_SLOT -> { out[off + 3] += 1f; out[off + 4] += w; }
                case CaveBiomes.SHAPE_TUBE -> { out[off + 5] += 1f; out[off + 6] += layer.tubeY(cell); out[off + 7] += w; }
                case CaveBiomes.SHAPE_HALL -> {
                    out[off + 8] += 1f;
                    out[off + 9] += CaveBiomes.anchorX(cell);
                    out[off + 10] += CaveBiomes.hallY(cell);
                    out[off + 11] += CaveBiomes.anchorZ(cell);
                }
                default -> { }
            }
        }
        for (int k = 0; k < CH; k++) out[off + k] *= 0.2f;
        return any;
    }

    /** A block of shape lattice points covering [x0, x1] x [y0, y1] x [z0, z1]. */
    private static final class ShapeGrid {
        int gx0, gy0, gz0, nx, ny, nz;
        float[] ch;
        boolean any;
        final float[] tmp = new float[CH];

        int index(int ix, int iy, int iz) {
            return ((iz * nx + ix) * ny + iy) * CH;
        }

        /** The channels at a block, trilinear between lattice points, into tmp. */
        float[] at(int x, int y, int z) {
            int gx = Math.floorDiv(x, SHAPE_CELL), gy = Math.floorDiv(y, SHAPE_CELL_Y), gz = Math.floorDiv(z, SHAPE_CELL);
            float fx = (x - gx * SHAPE_CELL) / (float) SHAPE_CELL, fy = (y - gy * SHAPE_CELL_Y) / (float) SHAPE_CELL_Y;
            float fz = (z - gz * SHAPE_CELL) / (float) SHAPE_CELL;
            int ix = gx - gx0, iy = gy - gy0, iz = gz - gz0;
            int a = index(ix, iy, iz), b = index(ix + 1, iy, iz), c = index(ix, iy, iz + 1), d = index(ix + 1, iy, iz + 1);
            for (int k = 0; k < CH; k++) {
                float c00 = ch[a + k] + (ch[a + CH + k] - ch[a + k]) * fy;
                float c10 = ch[b + k] + (ch[b + CH + k] - ch[b + k]) * fy;
                float c01 = ch[c + k] + (ch[c + CH + k] - ch[c + k]) * fy;
                float c11 = ch[d + k] + (ch[d + CH + k] - ch[d + k]) * fy;
                float c0 = c00 + (c10 - c00) * fx, c1 = c01 + (c11 - c01) * fx;
                tmp[k] = c0 + (c1 - c0) * fz;
            }
            return tmp;
        }
    }

    private ShapeGrid shapeGrid(CaveBiomes layer, CaveBiomes.Surface surface, int x0, int y0, int z0, int x1, int y1, int z1,
                                ShapeGrid g) {
        if (g == null) g = new ShapeGrid();
        g.gx0 = Math.floorDiv(x0, SHAPE_CELL);
        g.gy0 = Math.floorDiv(y0, SHAPE_CELL_Y);
        g.gz0 = Math.floorDiv(z0, SHAPE_CELL);
        g.nx = Math.floorDiv(x1, SHAPE_CELL) - g.gx0 + 2;
        g.ny = Math.floorDiv(y1, SHAPE_CELL_Y) - g.gy0 + 2;
        g.nz = Math.floorDiv(z1, SHAPE_CELL) - g.gz0 + 2;
        int n = g.nx * g.ny * g.nz * CH;
        if (g.ch == null || g.ch.length < n) g.ch = new float[n];
        g.any = false;
        for (int iz = 0; iz < g.nz; iz++)
            for (int ix = 0; ix < g.nx; ix++)
                for (int iy = 0; iy < g.ny; iy++)
                    g.any |= shapePoint(layer, surface, (g.gx0 + ix) * SHAPE_CELL, (g.gy0 + iy) * SHAPE_CELL_Y,
                            (g.gz0 + iz) * SHAPE_CELL, g.ch, g.index(ix, iy, iz));
        return g;
    }

    /** The fade for a shape's weight: none once the weight is over 0.8 (inside its region), full below 0.2. */
    private static float fade(float w) {
        float t = Math.max(0f, Math.min(1f, (w - 0.2f) / 0.6f));
        return (1f - t) * FADE;
    }

    /** The giant hall's value at a cavern lattice point (+inf where none), from interpolated channels. */
    private static float hallValue(float[] c, int x, int y, int z) {
        float w = c[8];
        if (w < 0.01f) return Float.POSITIVE_INFINITY;
        float dx = (x - c[9] / w) / HALL_RX, dy = (y - c[10] / w) / HALL_RY, dz = (z - c[11] / w) / HALL_RX;
        return ((float) Math.sqrt(dx * dx + dy * dy + dz * dz) - 1f) * HALL_RY + fade(w);
    }

    /** Chasms, slots and tubes at a tunnel lattice point (+inf where none), from interpolated channels. */
    private float regionTunnels(float[] c, int x, int y, int z) {
        float v = Float.POSITIVE_INFINITY;
        if (c[1] > 0.01f) {
            float w = c[1];
            v = Math.min(v, (Math.abs(chasmNoise.GetNoise(x, z)) - c[2] / w) * CHASM_GAIN + fade(w));
        }
        if (c[3] > 0.01f) {
            float w = c[3];
            // Slots lean and wander as they go down.
            float n = slotNoise.GetNoise(x + y * 0.25f, z - y * 0.18f);
            v = Math.min(v, (Math.abs(n) - c[4] / w) * SLOT_GAIN + fade(w));
        }
        if (c[5] > 0.01f) {
            float w = c[5];
            float plan = (Math.abs(tubeNoise.GetNoise(x, z)) - c[7] / w) * TUBE_GAIN;
            float vert = (Math.abs(y - c[6] / w) - 4.5f) * 1.5f;
            v = Math.min(v, Math.max(plan, vert) + fade(w));
        }
        return v;
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
        float[] cavern, tunnel;
        ShapeGrid shapes;
    }

    /**
     * Fills {@code out} with the cave field for blocks x0..x0+sx-1, z0..z0+sz-1, y0..y0+sy-1, indexed
     * {@code (x + z * sx) * sy + y} (the same order as Minecraft's density volumes). {@code surface} is what the cave
     * biome layer reads for chamber sizes (null: no cave biomes).
     */
    public void fill(int x0, int z0, int sx, int sz, int y0, int sy, float[] out, Scratch scratch, CaveBiomes.Surface surface) {
        ShapeGrid g = null;
        if (surface != null) {
            // Covers both lattices: the cavern lattice reaches one cell past the volume (8 blocks up, 4 sideways).
            g = shapeGrid(CaveBiomes.forSeed(seed), surface, Math.floorDiv(x0, 4) * 4, Math.floorDiv(y0, 8) * 8,
                    Math.floorDiv(z0, 4) * 4, x0 + sx + 4, y0 + sy + 8, z0 + sz + 4, scratch.shapes);
            scratch.shapes = g;
            if (!g.any) g = null;
        }
        // Caverns first, written straight into out; then the tunnels' minimum on top.
        scratch.cavern = fillFamily(true, x0, z0, sx, sz, y0, sy, out, scratch.cavern, false, g);
        scratch.tunnel = fillFamily(false, x0, z0, sx, sz, y0, sy, out, scratch.tunnel, true, g);
    }

    private float[] fillFamily(boolean cavern, int x0, int z0, int sx, int sz, int y0, int sy, float[] out,
                               float[] lattice, boolean min, ShapeGrid g) {
        int cxz = cavern ? CAVERN_XZ : TUNNEL, cy = cavern ? CAVERN_Y : TUNNEL;
        int lx0 = Math.floorDiv(x0, cxz), lx1 = Math.floorDiv(x0 + sx - 1, cxz) + 1;
        int lz0 = Math.floorDiv(z0, cxz), lz1 = Math.floorDiv(z0 + sz - 1, cxz) + 1;
        int ly0 = Math.floorDiv(y0, cy), ly1 = Math.floorDiv(y0 + sy - 1, cy) + 1;
        int nx = lx1 - lx0 + 1, nz = lz1 - lz0 + 1, ny = ly1 - ly0 + 1;
        int n = nx * ny * nz;
        if (lattice == null || lattice.length < n) lattice = new float[n];
        for (int iz = 0; iz < nz; iz++)
            for (int ix = 0; ix < nx; ix++)
                for (int iy = 0; iy < ny; iy++) {
                    int bx = (lx0 + ix) * cxz, by = (ly0 + iy) * cy, bz = (lz0 + iz) * cxz;
                    lattice[(iz * nx + ix) * ny + iy] = latticePoint(cavern, bx, by, bz, g);
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

    /** One lattice point of a family, with the region shapes. */
    private float latticePoint(boolean cavern, int bx, int by, int bz, ShapeGrid g) {
        if (cavern) {
            if (g == null) return cavernValue(bx, by, bz, 0f);
            float[] c = g.at(bx, by, bz);
            return clamp(Math.min(cavernValue(bx, by, bz, c[0]), hallValue(c, bx, by, bz)));
        }
        float v = tunnelValue(bx, by, bz);
        if (g == null) return v;
        return clamp(Math.min(v, regionTunnels(g.at(bx, by, bz), bx, by, bz)));
    }

    /** The cave field at one block (the same value {@link #fill} gives it with the same surface). */
    public float at(int x, int y, int z, CaveBiomes.Surface surface) {
        ShapeGrid g = null;
        if (surface != null) {
            g = shapeGrid(CaveBiomes.forSeed(seed), surface, Math.floorDiv(x, 4) * 4, Math.floorDiv(y, 8) * 8,
                    Math.floorDiv(z, 4) * 4, x + 4, y + 8, z + 4, null);
            if (!g.any) g = null;
        }
        return Math.min(pointValue(true, x, y, z, g), pointValue(false, x, y, z, g));
    }

    private float pointValue(boolean cavern, int x, int y, int z, ShapeGrid g) {
        int cxz = cavern ? CAVERN_XZ : TUNNEL, cy = cavern ? CAVERN_Y : TUNNEL;
        int gx = Math.floorDiv(x, cxz), gy = Math.floorDiv(y, cy), gz = Math.floorDiv(z, cxz);
        float[] l = new float[8];
        // Same index layout as fillFamily() with nx = nz = ny = 2.
        for (int iz = 0; iz < 2; iz++)
            for (int ix = 0; ix < 2; ix++)
                for (int iy = 0; iy < 2; iy++) {
                    int bx = (gx + ix) * cxz, by = (gy + iy) * cy, bz = (gz + iz) * cxz;
                    l[(iz * 2 + ix) * 2 + iy] = latticePoint(cavern, bx, by, bz, g);
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

    /** Openings in the sea floor: rarer patches than the land entrances (the sea floor is mostly sealed). */
    public static float SEA_OPENING_START = 0.56f, SEA_OPENING_FULL = 0.66f;

    /** Roof depth under the sea floor at column (x, z): {@link #ROOF_SEA}, thinning to 0 in rare opening patches. */
    public float seaRoof(int x, int z) {
        float e = entrance.GetNoise(x + 7919, z - 6151);
        if (e <= SEA_OPENING_START) return ROOF_SEA;
        float t = Math.min(1f, (e - SEA_OPENING_START) / (SEA_OPENING_FULL - SEA_OPENING_START));
        return ROOF_SEA * (1f - t * t * (3f - 2f * t));
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
