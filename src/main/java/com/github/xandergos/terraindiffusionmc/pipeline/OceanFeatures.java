package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Tectonic sea-floor features and oceanic islands, stamped into the diffusion model's elevation.
 *
 * <p>Runs inside {@link WorldPipeline#get} on native 30 m pixels, before climate, so everything downstream (rivers,
 * biomes, the terrain explorer) sees the islands. Every output pixel depends only on its own input elevation, its
 * world position and the coarse map, so any two requests agree exactly where they overlap.
 *
 * <p>Features, all scaled to this world's ocean basins (100–300 km across):
 * <ul>
 *   <li><b>Plates.</b> A domain-warped Voronoi tiling (~500 km cells). Each plate boundary is a spreading ridge,
 *       a subduction zone, or passive.</li>
 *   <li><b>Mid-ocean ridges</b>: a broad rise with an axial rift valley, cut into segments offset at transform
 *       faults, with fracture-zone troughs running off the offsets and ridge-parallel abyssal hills.</li>
 *   <li><b>Trenches</b>: a deep, asymmetric trough with an outer rise on the subducting plate and a volcanic
 *       <b>island arc</b> ~90 km into the overriding plate.</li>
 *   <li><b>Hotspot chains</b>: a young high volcanic island at the head, older volcanoes behind it sinking with age.
 *       In warm water the sunken ones become <b>atolls</b> (reef rim, motus, passes, lagoon); in cold water, or once
 *       too deep, flat-topped <b>guyots</b> and then plain seamounts.</li>
 *   <li><b>Seamounts and guyots</b> scattered over the abyssal plains, plus low abyssal hills.</li>
 * </ul>
 * Tectonic relief only appears in deep water (it fades out between 1500 and 3000 m), and volcanoes are only placed
 * where the coarse map is deep ocean, so continents and shelves are never touched.
 */
public final class OceanFeatures {

    /** Coarse-map view used to decide where volcanoes may stand. Coordinates are native pixels (row i, col j). */
    public interface CoarseField {
        /** Smoothed elevation in metres (ocean negative). */
        float elevation(double i, double j);
        /** Mean annual temperature in degrees C. */
        float temperature(double i, double j);
    }

    /** Native pixels per kilometre (30 m pixels). */
    private static final double KM = 1000.0 / 30.0;

    // Plates
    private static final double PLATE = 500 * KM;
    /** Distance (native pixels) over which plate-boundary features fade past a triple junction. */
    private static final double JUNCTION_TAPER = 30 * KM;
    private static final int BOUNDARY_RIDGE = 0, BOUNDARY_TRENCH = 1, BOUNDARY_PASSIVE = 2;

    // Ridges
    private static final double RIDGE_HEIGHT = 1800, RIDGE_WIDTH = 45 * KM;
    private static final double RIFT_DEPTH = 550, RIFT_WIDTH = 3 * KM;
    private static final double SEGMENT = 70 * KM, MAX_OFFSET = 18 * KM, TRANSFORM_BLEND = 1.5 * KM;
    private static final double FZ_DEPTH = 350, FZ_WIDTH = 2 * KM, FZ_REACH = 120 * KM;

    // Trenches and arcs
    private static final double TRENCH_DEPTH = 3800, TRENCH_AXIS = 8 * KM;
    private static final double TRENCH_OUTER_WIDTH = 13 * KM, TRENCH_INNER_WIDTH = 6 * KM;
    private static final double OUTER_RISE = 250, OUTER_RISE_AT = 60 * KM, OUTER_RISE_WIDTH = 28 * KM;
    private static final double ARC_DISTANCE = 90 * KM, ARC_SPACING = 32 * KM;

    // Hotspots and seamounts
    private static final double HOTSPOT_CELL = 450 * KM, HOTSPOT_CHANCE = 0.4;
    private static final double SEAMOUNT_CELL = 45 * KM, SEAMOUNT_CHANCE = 0.3;
    /** Volcanoes only stand where the coarse map is at least this deep (metres). */
    private static final float VOLCANO_MIN_DEPTH = 1800f;
    /** Coarse sea temperature (deg C) above which reefs build atolls. */
    private static final float REEF_TEMP = 22f;
    /** Largest distance (native pixels) from a volcano centre to the edge of its footprint. */
    public static final double MAX_VOLCANO_REACH = 42 * KM;
    private static final float MAX_DEPTH = -10900f;

    private final long seed;
    private final FastNoiseLite warpLargeX, warpLargeZ, warpSmallX, warpSmallZ;
    private final FastNoiseLite hills, trenchStrength, coastShape, gullies, motus;

    public OceanFeatures(long seed) {
        this.seed = seed;
        int s = (int) (seed ^ (seed >>> 32));
        warpLargeX = noise(s + 101, 1f / 500f);
        warpLargeZ = noise(s + 102, 1f / 500f);
        warpSmallX = noise(s + 103, 1f / 150f);
        warpSmallZ = noise(s + 104, 1f / 150f);
        hills = noise(s + 105, 1f);            // sampled in stretched coordinates
        trenchStrength = noise(s + 106, 1f / 220f);
        coastShape = noise(s + 107, 1f / 4f);
        gullies = noise(s + 108, 1f / 1.6f);
        motus = noise(s + 109, 1f / 1.3f);
    }

    private static FastNoiseLite noise(int seed, float freqPerKm) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freqPerKm);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(2);
        return n;
    }

    /**
     * Applies the features in place.
     *
     * @param elev elevation in metres, row-major (H, W), top-left at native pixel (i1, j1)
     */
    public void apply(float[] elev, int i1, int j1, int H, int W, CoarseField coarse) {
        boolean anyOcean = false;
        for (float v : elev) if (v < -30f) { anyOcean = true; break; }
        if (!anyOcean) return;

        Ctx ctx = new Ctx();
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int k = r * W + c;
                float raw = elev[k];
                if (raw >= -30f) continue;
                elev[k] = (float) shape(raw, i1 + r, j1 + c, coarse, ctx);
            }
        }
    }

    /** Scratch values for one pixel, reused to avoid allocation. */
    static final class Ctx {
        // Voronoi result
        long id1, id2, id3;
        double s1x, s1z, s2x, s2z, s3x, s3z, d1, d2, d3;
        // Volcano accumulation
        double add;
        int nOps;
        final int[] opType = new int[8];
        final double[] opA = new double[8], opB = new double[8], opR = new double[8], opU = new double[8];
        final long[] opH = new long[8];
        final double[] opX = new double[8], opZ = new double[8];
    }

    private static final int OP_GUYOT = 1, OP_ATOLL = 2;

    double shape(double raw, int pi, int pj, CoarseField coarse, Ctx ctx) {
        double depth = -raw;
        double landFade = clamp01((depth - 30) / 300);
        double deepFade = smoothstep(1500, 3000, depth);
        double x = pj, z = pi;   // native pixels: x east, z south

        double total = raw;

        // ---- Plate boundaries (warped space) ----
        double qx = x + warpX(x, z), qz = z + warpZ(x, z);
        voronoi(qx, qz, PLATE, 0x51A7EL, ctx);
        long[] ids = {ctx.id1, ctx.id2, ctx.id3};
        double[] sx = {ctx.s1x, ctx.s2x, ctx.s3x}, sz = {ctx.s1z, ctx.s2z, ctx.s3z};
        double[] dd = {ctx.d1, ctx.d2, ctx.d3};
        double ridgeUp = Double.NEGATIVE_INFINITY, trenchDown = 0;
        ctx.add = 0;
        ctx.nOps = 0;
        // All three edges among the three nearest plates. Each edge is weighted by whether it really exists at
        // this point along it: `beyond` (measured on the boundary line itself) is positive along the real edge
        // and negative past the triple junction where it stops. The weights are symmetric in the three plates,
        // so nothing jumps where pixel ownership switches.
        for (int e = 0; e < 3; e++) {
            int iX = e == 2 ? 1 : 0, iY = e == 0 ? 1 : 2, iZ = 3 - iX - iY;
            if (dd[iZ] - Math.max(dd[iX], dd[iY]) < -PLATE) continue;
            long lo = Math.min(ids[iX], ids[iY]), hi = Math.max(ids[iX], ids[iY]);
            long pair = mix(lo * 0x9E3779B97F4A7C15L ^ hi);
            boolean xIsLo = ids[iX] == lo;
            double lx = xIsLo ? sx[iX] : sx[iY], lz = xIsLo ? sz[iX] : sz[iY];
            double hx = xIsLo ? sx[iY] : sx[iX], hz = xIsLo ? sz[iY] : sz[iX];
            double nx = hx - lx, nz = hz - lz, nl = Math.sqrt(nx * nx + nz * nz);
            nx /= nl; nz /= nl;
            double tx = -nz, tz = nx;
            double mx = (lx + hx) * 0.5, mz = (lz + hz) * 0.5;
            double ds = (qx - mx) * nx + (qz - mz) * nz;     // signed distance, negative toward the lo plate
            double along = (qx - mx) * tx + (qz - mz) * tz;   // position along the boundary
            double bx = qx - ds * nx, bz = qz - ds * nz;      // nearest point on the boundary line
            double beyond = Math.hypot(bx - sx[iZ], bz - sz[iZ]) - Math.hypot(bx - sx[iX], bz - sz[iX]);
            if (beyond < -JUNCTION_TAPER) continue;
            int type = boundaryType(pair);
            if (type == BOUNDARY_RIDGE) {
                // Ridges run at full height right up to the junction and join there.
                double w = smoothstep(-JUNCTION_TAPER, 0, beyond);
                if (deepFade > 0 && w > 0) ridgeUp = Math.max(ridgeUp, ridge(ds, along, pair) * w);
            } else if (type == BOUNDARY_TRENCH) {
                // Trenches die out across the junction.
                double w = smoothstep(-JUNCTION_TAPER, JUNCTION_TAPER, beyond);
                boolean subHi = (pair & 1L) == 0;
                double xs = subHi ? ds : -ds;                  // positive into the subducting plate
                double strength = trenchStrength(along, pair) * w;
                if (strength > 0) {
                    double trenchFade = smoothstep(500, 2500, depth);
                    trenchDown = Math.min(trenchDown, trench(xs) * strength * trenchFade);
                    double xo = -xs;                           // positive into the overriding plate
                    if (Math.abs(xo - ARC_DISTANCE) < 30 * KM) {
                        arcVolcanoes(x, z, xo, along, pair, subHi, mx, mz, nx, nz, tx, tz, w, coarse, ctx);
                    }
                }
            }
        }
        if (ridgeUp > Double.NEGATIVE_INFINITY) total += ridgeUp * deepFade;
        total += trenchDown;
        if (deepFade > 0) {
            total += hills.GetNoise((float) (x / KM / 8), (float) (z / KM / 8)) * 90 * deepFade;
        }

        // ---- Volcanoes ----
        hotspots(x, z, coarse, ctx);
        seamounts(x, z, coarse, ctx);

        total += ctx.add * landFade;
        for (int o = 0; o < ctx.nOps; o++) {
            total = applyOp(total, o, x, z, ctx);
        }
        return Math.max(MAX_DEPTH, total);
    }

    // =========================================================================
    // Feature finder
    // =========================================================================

    /**
     * A place worth visiting, at native pixel (i, j). {@code value} is the summit height in metres for volcanoes
     * (negative when submerged) and the expected floor depth for trenches.
     */
    public record Landmark(String type, double i, double j, float value) {}

    /**
     * Lists the ocean features whose centres fall in the native-pixel window [i0, i1) x [j0, j1): volcanic islands,
     * atolls, guyots and seamounts (hotspot chains and scattered ones), island-arc volcanoes that break the surface,
     * and the deepest stretches of trenches. Uses the same placement rules as the terrain, so a marker sits where the
     * feature is generated (the model's own sea floor can shift a coastline a little).
     */
    public java.util.List<Landmark> landmarks(double i0, double j0, double i1, double j1, CoarseField coarse) {
        java.util.List<Landmark> out = new java.util.ArrayList<>();
        hotspotLandmarks(i0, j0, i1, j1, coarse, out);
        seamountLandmarks(i0, j0, i1, j1, coarse, out);
        subductionLandmarks(i0, j0, i1, j1, coarse, out);
        return out;
    }

    private static boolean inside(double i, double j, double i0, double j0, double i1, double j1) {
        return i >= i0 && i < i1 && j >= j0 && j < j1;
    }

    private void hotspotLandmarks(double i0, double j0, double i1, double j1, CoarseField coarse, java.util.List<Landmark> out) {
        // A chain reaches at most ~13 x 38 km from its hotspot, so one extra cell on each side covers it.
        int gx0 = (int) Math.floor(j0 / HOTSPOT_CELL) - 2, gx1 = (int) Math.floor(j1 / HOTSPOT_CELL) + 2;
        int gz0 = (int) Math.floor(i0 / HOTSPOT_CELL) - 2, gz1 = (int) Math.floor(i1 / HOTSPOT_CELL) + 2;
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                Chain ch = chain(gx, gz);
                if (ch == null) continue;
                for (int k = 0; k < ch.count; k++) {
                    if (!inside(ch.cz[k], ch.cx[k], i0, j0, i1, j1)) continue;
                    float floor = coarse.elevation(ch.cz[k], ch.cx[k]);
                    if (floor > -VOLCANO_MIN_DEPTH) continue;
                    boolean warm = coarse.temperature(ch.cz[k], ch.cx[k]) >= REEF_TEMP;
                    out.add(new Landmark(volcanoStage(ch.peak[k], warm), ch.cz[k], ch.cx[k], (float) ch.peak[k]));
                }
            }
        }
    }

    private void seamountLandmarks(double i0, double j0, double i1, double j1, CoarseField coarse, java.util.List<Landmark> out) {
        int gx0 = (int) Math.floor(j0 / SEAMOUNT_CELL), gx1 = (int) Math.floor(j1 / SEAMOUNT_CELL);
        int gz0 = (int) Math.floor(i0 / SEAMOUNT_CELL), gz1 = (int) Math.floor(i1 / SEAMOUNT_CELL);
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                long h = hash(0x5EAL, gx, gz);
                if (unit(h) > SEAMOUNT_CHANCE) continue;
                double sx = (gx + 0.1 + 0.8 * unit(mix(h))) * SEAMOUNT_CELL;
                double sz = (gz + 0.1 + 0.8 * unit(mix(h + 1))) * SEAMOUNT_CELL;
                if (!inside(sz, sx, i0, j0, i1, j1)) continue;
                float floor = coarse.elevation(sz, sx);
                if (floor > -SEAMOUNT_MIN_DEPTH) continue;
                double peak = seamountPeak(floor, h);
                out.add(new Landmark(isGuyot(peak, h) ? "guyot" : "seamount", sz, sx, (float) peak));
            }
        }
    }

    /** Island-arc volcanoes that break the surface, and one marker at the deepest part of each trench arc. */
    private void subductionLandmarks(double i0, double j0, double i1, double j1, CoarseField coarse, java.util.List<Landmark> out) {
        // Plate seeds in warped space around the window.
        double pad = 2 * PLATE;
        int gx0 = (int) Math.floor((j0 - pad) / PLATE), gx1 = (int) Math.floor((j1 + pad) / PLATE);
        int gz0 = (int) Math.floor((i0 - pad) / PLATE), gz1 = (int) Math.floor((i1 + pad) / PLATE);
        int nx = gx1 - gx0 + 1, nz = gz1 - gz0 + 1;
        double[] seedX = new double[nx * nz], seedZ = new double[nx * nz];
        long[] seedId = new long[nx * nz];
        for (int a = 0; a < nz; a++) {
            for (int b = 0; b < nx; b++) {
                int gx = gx0 + b, gz = gz0 + a;
                long h = hash(0x51A7EL, gx, gz);
                seedX[a * nx + b] = (gx + 0.1 + 0.8 * unit(h)) * PLATE;
                seedZ[a * nx + b] = (gz + 0.1 + 0.8 * unit(mix(h))) * PLATE;
                seedId[a * nx + b] = hash(0x51A7EL ^ 0x1D, gx, gz);
            }
        }
        double margin = 60 * KM;   // the warp moves things at most ~43 km
        double[][] corners = {{j0 - margin, i0 - margin}, {j1 + margin, i0 - margin}, {j0 - margin, i1 + margin}, {j1 + margin, i1 + margin}};
        for (int ia = 0; ia < nx * nz; ia++) {
            for (int ib = ia + 1; ib < nx * nz; ib++) {
                int ra = ia / nx, ca = ia % nx, rb = ib / nx, cb = ib % nx;
                if (Math.abs(ra - rb) > 2 || Math.abs(ca - cb) > 2) continue;
                long lo = Math.min(seedId[ia], seedId[ib]), hi = Math.max(seedId[ia], seedId[ib]);
                long pair = mix(lo * 0x9E3779B97F4A7C15L ^ hi);
                if (boundaryType(pair) != BOUNDARY_TRENCH) continue;
                int il = seedId[ia] == lo ? ia : ib, ih = il == ia ? ib : ia;
                double lx = seedX[il], lz = seedZ[il], hx = seedX[ih], hz = seedZ[ih];
                double ex = hx - lx, ez = hz - lz, el = Math.sqrt(ex * ex + ez * ez);
                ex /= el; ez /= el;
                double tx = -ez, tz = ex, mx = (lx + hx) * 0.5, mz = (lz + hz) * 0.5;
                boolean subHi = (pair & 1L) == 0;
                double ox = subHi ? -ex : ex, oz = subHi ? -ez : ez;           // into the overriding plate
                double amin = Double.MAX_VALUE, amax = -Double.MAX_VALUE;
                for (double[] cp : corners) {
                    double a = (cp[0] - mx) * tx + (cp[1] - mz) * tz;
                    amin = Math.min(amin, a); amax = Math.max(amax, a);
                }
                if (amax - amin > 40 * PLATE) continue;
                // Arc volcanoes.
                for (long k = (long) Math.floor(amin / ARC_SPACING) - 1; k <= (long) Math.ceil(amax / ARC_SPACING) + 1; k++) {
                    long h = hash(pair, k, 0xA2C);
                    if (!arcExists(h)) continue;
                    double ak = arcAlong(k, h);
                    double w = edgeWeightAt(mx + tx * ak, mz + tz * ak, ia, ib, seedX, seedZ);
                    double strength = trenchStrength(ak, pair) * w;
                    if (strength <= 0.05) continue;
                    double peak = arcPeak(strength, h);
                    if (peak <= 0) continue;
                    double across = arcAcross(h);
                    double[] c = unwarp(mx + tx * ak + ox * across, mz + tz * ak + oz * across);
                    if (!inside(c[1], c[0], i0, j0, i1, j1)) continue;
                    if (coarse.elevation(c[1], c[0]) > -VOLCANO_MIN_DEPTH) continue;
                    out.add(new Landmark("arc_island", c[1], c[0], (float) peak));
                }
                // Trench: the strongest point of each stretch where it runs deep.
                double step = 15 * KM, prev = 0, prev2 = 0;
                for (double a = amin - step; a <= amax + 2 * step; a += step) {
                    double w = edgeWeightAt(mx + tx * a, mz + tz * a, ia, ib, seedX, seedZ);
                    double st = trenchStrength(a, pair) * w;
                    double am = a - step;                                      // is the previous step a peak?
                    if (prev >= 0.55 && prev >= prev2 && prev > st) {
                        double[] c = unwarp(mx + tx * am - ox * TRENCH_AXIS, mz + tz * am - oz * TRENCH_AXIS);
                        if (inside(c[1], c[0], i0, j0, i1, j1)) {
                            float floor = coarse.elevation(c[1], c[0]);
                            if (floor <= -2500f) {
                                out.add(new Landmark("trench", c[1], c[0], (float) Math.max(MAX_DEPTH, floor - TRENCH_DEPTH * prev)));
                            }
                        }
                    }
                    prev2 = prev;
                    prev = st;
                }
            }
        }
    }

    /** How much of the A-B plate edge exists at boundary point (bx, bz): 0 past a triple junction, 1 along it. */
    private static double edgeWeightAt(double bx, double bz, int ia, int ib, double[] seedX, double[] seedZ) {
        double da = Math.hypot(bx - seedX[ia], bz - seedZ[ia]);
        double nearestOther = Double.MAX_VALUE;
        for (int c = 0; c < seedX.length; c++) {
            if (c == ia || c == ib) continue;
            nearestOther = Math.min(nearestOther, Math.hypot(bx - seedX[c], bz - seedZ[c]));
        }
        return smoothstep(-JUNCTION_TAPER, JUNCTION_TAPER, nearestOther - da);
    }

    // =========================================================================
    // Plates
    // =========================================================================

    private double warpX(double x, double z) {
        float kx = (float) (x / KM), kz = (float) (z / KM);
        return (warpLargeX.GetNoise(kx, kz) * 35 + warpSmallX.GetNoise(kx, kz) * 8) * KM;
    }

    private double warpZ(double x, double z) {
        float kx = (float) (x / KM), kz = (float) (z / KM);
        return (warpLargeZ.GetNoise(kx, kz) * 35 + warpSmallZ.GetNoise(kx, kz) * 8) * KM;
    }

    /** Inverse of the warp by fixed-point iteration (the warp is a contraction). */
    private double[] unwarp(double qx, double qz) {
        double px = qx, pz = qz;
        for (int it = 0; it < 6; it++) {
            px = qx - warpX(px, pz);
            pz = qz - warpZ(px, pz);
        }
        return new double[]{px, pz};
    }

    /** Three nearest jittered seeds of a grid of the given cell size. */
    private void voronoi(double x, double z, double cell, long salt, Ctx ctx) {
        int cx = (int) Math.floor(x / cell), cz = (int) Math.floor(z / cell);
        ctx.d1 = ctx.d2 = ctx.d3 = Double.MAX_VALUE;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int gx = cx + dx, gz = cz + dz;
                long h = hash(salt, gx, gz);
                double sx = (gx + 0.1 + 0.8 * unit(h)) * cell;
                double sz = (gz + 0.1 + 0.8 * unit(mix(h))) * cell;
                double d = Math.hypot(x - sx, z - sz);
                long id = hash(salt ^ 0x1D, gx, gz);
                if (d < ctx.d1) {
                    ctx.d3 = ctx.d2; ctx.id3 = ctx.id2; ctx.s3x = ctx.s2x; ctx.s3z = ctx.s2z;
                    ctx.d2 = ctx.d1; ctx.id2 = ctx.id1; ctx.s2x = ctx.s1x; ctx.s2z = ctx.s1z;
                    ctx.d1 = d; ctx.id1 = id; ctx.s1x = sx; ctx.s1z = sz;
                } else if (d < ctx.d2) {
                    ctx.d3 = ctx.d2; ctx.id3 = ctx.id2; ctx.s3x = ctx.s2x; ctx.s3z = ctx.s2z;
                    ctx.d2 = d; ctx.id2 = id; ctx.s2x = sx; ctx.s2z = sz;
                } else if (d < ctx.d3) {
                    ctx.d3 = d; ctx.id3 = id; ctx.s3x = sx; ctx.s3z = sz;
                }
            }
        }
    }

    private static int boundaryType(long pair) {
        double u = unit(mix(pair ^ 0x7E57L));
        if (u < 0.5) return BOUNDARY_RIDGE;
        if (u < 0.8) return BOUNDARY_TRENCH;
        return BOUNDARY_PASSIVE;
    }

    // =========================================================================
    // Ridges and trenches
    // =========================================================================

    private double ridge(double ds, double along, long pair) {
        double phase = unit(mix(pair ^ 0xF00DL)) * SEGMENT;
        double a = along + phase;
        double kf = Math.floor(a / SEGMENT);
        long k = (long) kf;
        double into = a - kf * SEGMENT;                       // distance past the segment start
        double off = segmentOffset(pair, k);
        // Blend into the neighbouring segment's offset right at the transform fault: a steep scarp, not a seam.
        if (into < TRANSFORM_BLEND) {
            off = lerp(segmentOffset(pair, k - 1), off, smoothstep(-TRANSFORM_BLEND, TRANSFORM_BLEND, into));
        } else if (SEGMENT - into < TRANSFORM_BLEND) {
            off = lerp(off, segmentOffset(pair, k + 1), smoothstep(-TRANSFORM_BLEND, TRANSFORM_BLEND, into - SEGMENT));
        }
        double xr = Math.abs(ds - off);
        double h = RIDGE_HEIGHT * Math.exp(-xr / RIDGE_WIDTH)
                - RIFT_DEPTH * Math.exp(-sq(xr / RIFT_WIDTH));
        // Abyssal hills run parallel to the ridge axis: long along it, short across it.
        float hl = hills.GetNoise((float) (along / KM / 22), (float) (ds / KM / 3.5));
        h += hl * 130 * (0.4 + 0.6 * Math.exp(-xr / (60 * KM)));
        // Fracture zones: narrow troughs running away from each transform offset.
        double toFault = Math.min(into, SEGMENT - into);
        if (Math.abs(ds) > 4 * KM) {
            h -= FZ_DEPTH * Math.exp(-sq(toFault / FZ_WIDTH)) * Math.exp(-Math.abs(ds) / FZ_REACH);
        }
        return h;
    }

    private double segmentOffset(long pair, long k) {
        return (unit(hash(pair, k, 0x5E6)) - 0.5) * 2 * MAX_OFFSET;
    }

    private double trench(double xs) {
        double w = xs > TRENCH_AXIS ? TRENCH_OUTER_WIDTH : TRENCH_INNER_WIDTH;
        double h = -TRENCH_DEPTH * Math.exp(-sq((xs - TRENCH_AXIS) / w));
        if (xs > 0) h += OUTER_RISE * Math.exp(-sq((xs - OUTER_RISE_AT) / OUTER_RISE_WIDTH));
        return h;
    }

    /** 0..1 along the trench, so subduction zones break into separate arcs. */
    private double trenchStrength(double along, long pair) {
        float n = trenchStrength.GetNoise((float) (along / KM), (float) ((pair & 0xFFFF) * 7.0));
        return smoothstep(-0.15, 0.35, n);
    }

    // =========================================================================
    // Volcanoes
    // =========================================================================

    private void arcVolcanoes(double x, double z, double xo, double along, long pair, boolean subHi,
                              double mx, double mz, double nx, double nz, double tx, double tz,
                              double edgeWeight, CoarseField coarse, Ctx ctx) {
        // Unit vector pointing into the overriding plate.
        double ox = subHi ? -nx : nx, oz = subHi ? -nz : nz;
        long kc = Math.round(along / ARC_SPACING);
        for (long k = kc - 1; k <= kc + 1; k++) {
            long h = hash(pair, k, 0xA2C);
            if (!arcExists(h)) continue;
            double ak = arcAlong(k, h), across = arcAcross(h), radius = arcRadius(h);
            double r = Math.hypot(along - ak, xo - across);
            if (r > radius * 1.3) continue;
            double strength = trenchStrength(ak, pair) * edgeWeight;
            if (strength <= 0.05) continue;
            double peak = arcPeak(strength, h);
            // Centre in world space, for the coarse depth check.
            double cqx = mx + tx * ak + ox * across, cqz = mz + tz * ak + oz * across;
            double[] c = unwarp(cqx, cqz);
            float floor = coarse.elevation(c[1], c[0]);
            if (floor > -VOLCANO_MIN_DEPTH) continue;
            // r is measured in warped space; close enough for the shape.
            if (peak > 0) {
                double landU = 0.25 + 0.2 * unit(mix(h + 4));
                ctx.add += (peak - floor) * islandCone(r, radius, -floor / (peak - floor), landU, x, z, h);
            } else {
                ctx.add += (peak - floor) * cone(r, radius, x, z, h, false);
            }
        }
    }

    private void hotspots(double x, double z, CoarseField coarse, Ctx ctx) {
        int cx = (int) Math.floor(x / HOTSPOT_CELL), cz = (int) Math.floor(z / HOTSPOT_CELL);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Chain ch = chain(cx + dx, cz + dz);
                if (ch == null) continue;
                double t = (x - ch.hx) * ch.vx + (z - ch.hz) * ch.vz;
                double across = -(x - ch.hx) * ch.vz + (z - ch.hz) * ch.vx;
                if (Math.abs(across) > MAX_VOLCANO_REACH + 6 * KM) continue;
                if (t < -MAX_VOLCANO_REACH || t > ch.count * ch.spacing + MAX_VOLCANO_REACH) continue;
                int kmin = Math.max(0, (int) Math.floor(t / ch.spacing) - 2);
                int kmax = Math.min(ch.count - 1, kmin + 4);
                for (int k = kmin; k <= kmax; k++) {
                    double r = Math.hypot(x - ch.cx[k], z - ch.cz[k]);
                    if (r > ch.radius[k] * 1.3) continue;
                    float floor = coarse.elevation(ch.cz[k], ch.cx[k]);
                    if (floor > -VOLCANO_MIN_DEPTH) continue;
                    boolean warm = coarse.temperature(ch.cz[k], ch.cx[k]) >= REEF_TEMP;
                    addVolcano(x, z, ch.cx[k], ch.cz[k], r, ch.radius[k], ch.peak[k], floor, warm, ch.hk[k], ctx);
                }
            }
        }
    }

    /** One hotspot and its chain of volcanoes; everything here depends only on the seed. */
    static final class Chain {
        double hx, hz, vx, vz, spacing;
        int count;
        double[] cx, cz, radius, peak;
        long[] hk;
    }

    private static final Chain NO_CHAIN = new Chain();
    private final java.util.concurrent.ConcurrentHashMap<Long, Chain> chains = new java.util.concurrent.ConcurrentHashMap<>();

    /** The hotspot chain of hotspot cell (gx, gz), or null if the cell has none. Cached. */
    Chain chain(int gx, int gz) {
        long key = ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
        Chain c = chains.computeIfAbsent(key, k -> buildChain(gx, gz));
        return c == NO_CHAIN ? null : c;
    }

    private Chain buildChain(int gx, int gz) {
        long h = hash(0x407L, gx, gz);
        if (unit(h) > HOTSPOT_CHANCE) return NO_CHAIN;
        Chain ch = new Chain();
        ch.hx = (gx + 0.15 + 0.7 * unit(mix(h))) * HOTSPOT_CELL;
        ch.hz = (gz + 0.15 + 0.7 * unit(mix(h + 1))) * HOTSPOT_CELL;
        // The chain trails the way the plate under the hotspot moves.
        double plateDir = plateMotionAngle(ch.hx, ch.hz);
        ch.vx = Math.cos(plateDir);
        ch.vz = Math.sin(plateDir);
        ch.count = 5 + (int) (unit(mix(h + 2)) * 8);
        ch.spacing = (24 + 14 * unit(mix(h + 3))) * KM;
        double headPeak = 900 + 1900 * unit(mix(h + 4));
        double baseRadius = (11 + 9 * unit(mix(h + 5))) * KM;
        ch.cx = new double[ch.count]; ch.cz = new double[ch.count];
        ch.radius = new double[ch.count]; ch.peak = new double[ch.count]; ch.hk = new long[ch.count];
        for (int k = 0; k < ch.count; k++) {
            long hk = hash(h, k, 0x407B);
            double along = k * ch.spacing + (unit(hk) - 0.5) * 0.4 * ch.spacing;
            double side = (unit(mix(hk)) - 0.5) * 12 * KM;
            ch.cx[k] = ch.hx + ch.vx * along - ch.vz * side;
            ch.cz[k] = ch.hz + ch.vz * along + ch.vx * side;
            ch.radius[k] = baseRadius * (1 - 0.03 * k) * (k == 0 ? 1.3 : 1.0);
            ch.peak[k] = headPeak - k * (320 + 280 * unit(mix(hk + 1))) + (unit(mix(hk + 2)) - 0.5) * 400;
            ch.hk[k] = hk;
        }
        return ch;
    }

    private void seamounts(double x, double z, CoarseField coarse, Ctx ctx) {
        int cx = (int) Math.floor(x / SEAMOUNT_CELL), cz = (int) Math.floor(z / SEAMOUNT_CELL);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int gx = cx + dx, gz = cz + dz;
                long h = hash(0x5EAL, gx, gz);
                if (unit(h) > SEAMOUNT_CHANCE) continue;
                double sx = (gx + 0.1 + 0.8 * unit(mix(h))) * SEAMOUNT_CELL;
                double sz = (gz + 0.1 + 0.8 * unit(mix(h + 1))) * SEAMOUNT_CELL;
                double radius = (4 + 7 * unit(mix(h + 2))) * KM;
                double r = Math.hypot(x - sx, z - sz);
                if (r > radius * 1.3) continue;
                float floor = coarse.elevation(sz, sx);
                if (floor > -SEAMOUNT_MIN_DEPTH) continue;
                double peak = seamountPeak(floor, h);
                boolean guyot = isGuyot(peak, h);
                if (guyot) {
                    // A guyot was planed flat at sea level, then sank: build it taller and cut the top off.
                    ctx.add += (peak + 600 - floor) * tableCone(r, radius, x, z, h);
                    pushOp(ctx, OP_GUYOT, peak, 0, radius, 0, h, sx, sz);
                } else {
                    ctx.add += (peak - floor) * cone(r, radius, x, z, h, false);
                }
            }
        }
    }

    /** A hotspot volcano at a stage of its life set by how far it has sunk (its peak height). */
    private void addVolcano(double x, double z, double vcx, double vcz, double r, double radius,
                            double peak, double floor, boolean warm, long h, Ctx ctx) {
        switch (volcanoStage(peak, warm)) {
            case "volcanic_island" -> {
                double landU = 0.3 + 0.2 * unit(mix(h + 8));
                ctx.add += (peak - floor) * islandCone(r, radius, -floor / (peak - floor), landU, x, z, h);
            }
            case "atoll" -> {
                // The volcano has sunk out of sight and its fringing reef kept growing up into a ring.
                double sub = Math.min(peak, -350);
                ctx.add += (sub - floor) * tableCone(r, radius, x, z, h);
                double rim = radius * (0.22 + 0.13 * unit(mix(h + 9)));
                pushOp(ctx, OP_ATOLL, rim, 0, radius, 0, h, vcx, vcz);
            }
            case "guyot" -> {
                double top = Math.min(peak, -250);
                ctx.add += (top + 600 - floor) * tableCone(r, radius, x, z, h);
                pushOp(ctx, OP_GUYOT, top, 0, radius, 0, h, vcx, vcz);
            }
            default -> ctx.add += (peak - floor) * cone(r, radius, x, z, h, false);
        }
    }

    // ---- Shared placement rules (the terrain and the feature finder must agree) ----

    private static final float SEAMOUNT_MIN_DEPTH = 2500f;

    private static double seamountPeak(float floor, long h) {
        return Math.min(-300, floor + 800 + 2200 * unit(mix(h + 3)));
    }

    private static boolean isGuyot(double peak, long h) {
        return unit(mix(h + 4)) < 0.25 && peak > -2200;
    }

    private static boolean arcExists(long h) { return unit(h) <= 0.75; }
    private static double arcAlong(long k, long h) { return k * ARC_SPACING + (unit(mix(h)) - 0.5) * 14 * KM; }
    private static double arcAcross(long h) { return ARC_DISTANCE + (unit(mix(h + 1)) - 0.5) * 16 * KM; }
    private static double arcRadius(long h) { return (7 + 7 * unit(mix(h + 2))) * KM; }
    private static double arcPeak(double strength, long h) { return lerp(-2500, -800 + 2400 * unit(mix(h + 3)), strength); }

    /** What a hotspot volcano has become by now, from its peak height and the water temperature. */
    static String volcanoStage(double peak, boolean warm) {
        if (peak > 40) return "volcanic_island";
        if (warm && peak > -1600) return "atoll";
        if (peak > -1800) return "guyot";
        return "seamount";
    }

    /**
     * Seamount profile, 1 at the summit and 0 at the foot, with concave flanks. The outline is roughened so the
     * footprint is not a circle.
     */
    private double cone(double r, double radius, double x, double z, long h, boolean unused) {
        double u = roughRadius(r, radius, x, z, h) / radius;
        if (u >= 1) return 0;
        return sq(1 - u);
    }

    /**
     * Volcanic island profile: a gentle shield above the sea out to {@code landU} of the radius, where it meets
     * a steeper submarine flank (islands drop off fast below the shore, as real volcanic islands do). {@code sea}
     * is the profile value at sea level, so the coast lands near {@code landU}. Gullies cut the land part and the
     * summit has a small caldera.
     */
    private double islandCone(double r, double radius, double sea, double landU, double x, double z, long h) {
        double u = roughRadius(r, radius, x, z, h) / radius;
        if (u >= 1) return 0;
        double s;
        if (u < landU) {
            double t = u / landU;
            s = 1 - (1 - sea) * Math.pow(t, 1.6);
            float kx = (float) (x / KM), kz = (float) (z / KM);
            double g = Math.abs(gullies.GetNoise(kx, kz)) * smoothstep(0.15, 0.6, t);
            s = sea + (s - sea) * (1 - 0.35 * g);
            s += (1 - sea) * 0.06 * motus.GetNoise(kx * 0.7f + 57, kz * 0.7f);   // bays and headlands
            s -= (1 - sea) * 0.12 * Math.exp(-sq(t / 0.08));
        } else {
            s = sea * Math.pow((1 - u) / (1 - landU), 1.3);
        }
        return s;
    }

    /** Volcano with a broad, flat-topped summit (the base of an atoll or a guyot). */
    private double tableCone(double r, double radius, double x, double z, long h) {
        double u = roughRadius(r, radius, x, z, h) / radius;
        if (u >= 1) return 0;
        return Math.pow(1 - Math.pow(u, 1.3), 1.8);
    }

    /**
     * Distance from the centre, roughened by noise whose wavelength scales with the volcano, so outlines wobble
     * into lobes and bays without folding the flanks.
     */
    private double roughRadius(double r, double radius, double x, double z, long h) {
        double scale = KM * 4 / radius;   // noise has a 4 km wavelength; this makes it ~1 radius
        float kx = (float) (x / KM * scale) + (h & 0xFF), kz = (float) (z / KM * scale) + ((h >>> 8) & 0xFF);
        return r * (1 + 0.14 * coastShape.GetNoise(kx, kz));
    }

    private static void pushOp(Ctx ctx, int type, double a, double b, double radius, double u, long h,
                               double cx, double cz) {
        if (ctx.nOps >= ctx.opType.length) return;
        int o = ctx.nOps++;
        ctx.opType[o] = type; ctx.opA[o] = a; ctx.opB[o] = b; ctx.opR[o] = radius; ctx.opU[o] = u;
        ctx.opH[o] = h; ctx.opX[o] = cx; ctx.opZ[o] = cz;
    }

    private double applyOp(double v, int o, double x, double z, Ctx ctx) {
        double r = Math.hypot(x - ctx.opX[o], z - ctx.opZ[o]);
        long h = ctx.opH[o];
        if (ctx.opType[o] == OP_GUYOT) {
            double top = ctx.opA[o];
            if (r < ctx.opR[o] && v > top) v = top + (v - top) * 0.03;
            return v;
        }
        // Atoll. Its ring is oval and wobbly: stretch along a random axis, then roughen.
        double dx = x - ctx.opX[o], dz = z - ctx.opZ[o];
        double axis = unit(mix(h + 12)) * Math.PI;
        double stretch = 1 + 0.5 * unit(mix(h + 13));
        double ca = Math.cos(axis), sa = Math.sin(axis);
        double ux = (dx * ca + dz * sa) / stretch, uz = -dx * sa + dz * ca;
        float kx = (float) (x / KM), kz = (float) (z / KM);
        double re = Math.hypot(ux, uz) * (1 + 0.12 * coastShape.GetNoise(kx * 0.6f + 31, kz * 0.6f));
        double rim = ctx.opA[o];
        double rimWidth = (0.45 + 0.25 * unit(mix(h + 11))) * KM;
        double inner = rim - rimWidth;
        double reef;
        if (re <= inner) {
            // Lagoon: shallow near the rim, deepest in the middle, dotted with patch reefs.
            reef = -12 - 38 * smoothstep(0, inner, inner - re);
            float patch = motus.GetNoise(kx * 2.3f, kz * 2.3f);
            if (patch > 0.55f) reef = Math.max(reef, -3);
        } else if (re <= rim) {
            float m = motus.GetNoise(kx + (h & 0xFF), kz);
            reef = m > 0.2f ? 1.5 + 6 * (m - 0.2) : -1.5;    // motus (islets) on a submerged reef flat
            double ang = Math.atan2(uz, ux);
            for (int p = 0; p < 2; p++) {                     // passes through the reef into the lagoon
                double pa = unit(mix(h + 20 + p)) * 2 * Math.PI;
                double da = Math.abs(Math.atan2(Math.sin(ang - pa), Math.cos(ang - pa))) * re;
                if (da < 0.25 * KM) reef = -18;
            }
        } else {
            // Fore-reef wall (~40 degrees) down to 600 m, then a talus apron that flattens out onto the
            // volcano's own flank instead of running on as a cone.
            double out = (re - rim) / KM;
            reef = out < 0.66 ? -1.5 - out * 900 : -600 - 1500 * (1 - Math.exp(-(out - 0.66) / 2.5));
        }
        return smoothMax(v, reef, 60);
    }

    /** max(a, b) with the corner rounded over about k; at most k / 2 above the true max, and only where a ~ b. */
    private static double smoothMax(double a, double b, double k) {
        return 0.5 * (a + b + Math.sqrt(sq(a - b) + k * k));
    }

    private double plateMotionAngle(double x, double z) {
        Ctx tmp = new Ctx();
        voronoi(x + warpX(x, z), z + warpZ(x, z), PLATE, 0x51A7EL, tmp);
        return unit(mix(tmp.id1 ^ 0xD1FL)) * 2 * Math.PI;
    }

    // =========================================================================
    // Math
    // =========================================================================

    private long hash(long salt, long a, long b) {
        return mix(seed ^ mix(salt * 0x9E3779B97F4A7C15L ^ mix(a * 0xC2B2AE3D27D4EB4FL ^ b * 0x165667B19E3779F9L)));
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double sq(double v) { return v * v; }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    private static double smoothstep(double e0, double e1, double v) {
        double t = clamp01((v - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }
}
