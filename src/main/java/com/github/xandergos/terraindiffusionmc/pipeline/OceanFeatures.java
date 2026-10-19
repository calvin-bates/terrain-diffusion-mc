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
 *   <li><b>Hydrothermal vents</b>: vent fields along ridge axes and around active volcanoes, as a seed-only query
 *       ({@link #vents}) that chunk decoration turns into sulfur chimneys and geysers
 *       ({@code world.HydrothermalVents}). No height change.</li>
 * </ul>
 *
 * <p>Round 2 (islands and reefs) gives each hotspot volcano a life story: a young island with a crater lake, then
 * (some) a <b>flooded caldera</b>, then in warm water an island ringed by a <b>barrier reef</b>, then an atoll, and
 * finally a <b>raised coral island</b> (an atoll lifted over a trench's outer rise) or a sunken guyot. Island-arc
 * volcanoes can also be flooded calderas or carry barrier reefs. Around the chains: the <b>hotspot swell</b> (a broad
 * bulge of the sea floor) and the <b>moat</b> and arch around each big island, giant <b>landslide scars</b> on some
 * island flanks with debris fields below them, and <b>aseismic ridges</b> joining some chains into one long ridge.
 * In warm shallow water: <b>fringing reefs</b> along coasts, <b>barrier reefs</b> far out on wide shelves, and flat
 * <b>carbonate banks</b> in warm archipelago seas. {@link #apply} can also report a per-pixel surface tag (reef,
 * lagoon, bank, limestone) that the chunk generator turns into coral, seagrass and limestone
 * ({@code world.OceanSurfaces}).
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
        /** Archipelago zone strength 0..1 (see {@code SyntheticMapFactory.archipelagoZone}); 0 if unknown. */
        default float archipelago(double i, double j) { return 0f; }
    }

    // Surface tags reported per pixel by apply(): what the chunk generator should do with the floor or ground there.
    public static final byte TAG_NONE = 0;
    /** Coral reef flat or reef front (atoll, barrier and fringing reefs): dense coral under water. */
    public static final byte TAG_REEF = 1;
    /** Calm lagoon floor behind a reef: seagrass meadows. */
    public static final byte TAG_LAGOON = 2;
    /** Carbonate bank top: bright floor (calcite patches on the sand) and seagrass. */
    public static final byte TAG_BANK = 3;
    /** Raised coral island rock (rim, cliffs, terraces, old passes): limestone at the surface. */
    public static final byte TAG_LIMESTONE = 4;
    /** Raised coral island interior (the old lagoon floor): limestone under the soil. */
    public static final byte TAG_LIMESTONE_SOIL = 5;
    /** Fringing reef along a warm coast (coral, like {@link #TAG_REEF}). */
    public static final byte TAG_FRINGING = 6;
    /** Barrier reef far out on a warm shelf (coral, like {@link #TAG_REEF}). */
    public static final byte TAG_BARRIER = 7;

    /** Whether a surface tag is a coral reef of any kind. */
    public static boolean isReef(byte tag) {
        return tag == TAG_REEF || tag == TAG_FRINGING || tag == TAG_BARRIER;
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
    private static final double HOTSPOT_CELL = 450 * KM, HOTSPOT_CHANCE = 0.8;
    private static final double SEAMOUNT_CELL = 45 * KM, SEAMOUNT_CHANCE = 0.3;
    /** Volcanoes only stand where the coarse map is at least this deep (metres). */
    private static final float VOLCANO_MIN_DEPTH = 1800f;
    /** Coarse sea temperature (deg C) above which reefs build atolls. */
    private static final float REEF_TEMP = 22f;
    /**
     * Footprint scale for volcanoes that stay under water (guyots, seamounts, submerged arc volcanoes). Real ones
     * are 10–40 km across, but the deep sea is squashed ~10x vertically in game, which left them as 1–2 degree
     * swells nobody could see. At this scale they are 2–7 km across and read as steep, flat-topped or conical
     * mountains on the sea floor. Islands and atolls keep their real size.
     */
    private static final double SUBMERGED_SHRINK = 0.2;
    /** Largest distance (native pixels) from a volcano centre to the edge of its footprint. */
    public static final double MAX_VOLCANO_REACH = 42 * KM;
    /**
     * Largest distance (native pixels) from a volcano centre to anything that depends on it (moat, arch, debris
     * fields). The caller must cover this much around a request with the coarse map.
     */
    public static final double MAX_FEATURE_REACH = 80 * KM;

    // ---- Round 2: island stages ----
    /** Share of older hotspot islands (not the head) that collapsed into a flooded caldera. */
    private static final double CALDERA_CHANCE = 0.3;
    /** Share of the remaining older islands in warm water that are ringed by a barrier reef. */
    private static final double BARRIER_CHANCE = 0.6;
    /** Island-arc islands that are flooded calderas, and warm ones ringed by a barrier reef. */
    private static final double ARC_CALDERA_CHANCE = 0.2, ARC_REEF_CHANCE = 0.4;
    /** Outer-rise strength (0..1) at an atoll that lifts it into a raised coral island. */
    private static final double OUTER_RISE_LIFT = 0.5;
    /** Atolls lifted anyway (in reality by the flexural bulge of a nearby big island, as Tahiti lifted Makatea). */
    private static final double MAKATEA_CHANCE = 0.15;
    /** Islands (over 500 m high) with a giant landslide scar on one flank. */
    private static final double SCAR_CHANCE = 0.3;
    /** Hotspot chains whose volcanoes are joined into one continuous (aseismic) ridge. */
    private static final double ASEISMIC_CHANCE = 0.2;

    // ---- Round 2: swell, moat and arch ----
    /** Height (m) of the broad sea-floor swell at an active hotspot, its half-width, and how it fades. */
    private static final double SWELL_HEIGHT = 600, SWELL_WIDTH = 75 * KM;
    private static final double SWELL_DECAY = 260 * KM, SWELL_AHEAD = 60 * KM;
    /** Depth (m) of the moat right around a big young island; the arch beyond it is a quarter of this. */
    private static final double MOAT_DEPTH = 700;

    // ---- Round 2: shallow seas ----
    /** Coarse sea temperatures (deg C) over which reefs fade in. */
    private static final float REEF_TEMP_LO = 21.5f, REEF_TEMP_HI = 24f;
    /** Reef-zone noise levels: fringing reefs above the first, continental barrier reefs above the second. */
    private static final float FRINGING_ZONE = 0.05f, BARRIER_ZONE = 0.3f;
    /** Share of warm archipelago seas (by area) that are carbonate banks. */
    private static final float BANK_ZONE = -0.25f;
    private static final float MAX_DEPTH = -10900f;

    private final long seed;
    private final FastNoiseLite warpLargeX, warpLargeZ, warpSmallX, warpSmallZ;
    private final FastNoiseLite hills, trenchStrength, coastShape, gullies, motus;
    private final FastNoiseLite reefZone, reefPatch, bankZone, bankLevel, shoals, coastFine;

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
        reefZone = noise(s + 110, 1f / 260f);
        reefPatch = noise(s + 111, 1f / 2.5f);
        bankZone = noise(s + 112, 1f / 90f);
        bankLevel = noise(s + 113, 1f / 14f);
        shoals = noise(s + 114, 1f / 0.7f);
        coastFine = noise(s + 115, 1f / 1.8f);
        coastFine.SetFractalOctaves(5);
        coastFine.SetFractalGain(0.72f);
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
        apply(elev, i1, j1, H, W, coarse, null);
    }

    /**
     * Applies the features in place and, if {@code tags} is not null, writes each pixel's surface tag
     * ({@link #TAG_NONE}, {@link #TAG_REEF}, ...) into it.
     *
     * @param elev elevation in metres, row-major (H, W), top-left at native pixel (i1, j1)
     */
    public void apply(float[] elev, int i1, int j1, int H, int W, CoarseField coarse, byte[] tags) {
        if (tags != null) java.util.Arrays.fill(tags, TAG_NONE);
        boolean anyOcean = false;
        for (float v : elev) if (v < 0f) { anyOcean = true; break; }
        if (!anyOcean) return;

        Ctx ctx = new Ctx();
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int k = r * W + c;
                float raw = elev[k];
                if (raw >= 0f) continue;
                ctx.tag = TAG_NONE;
                ctx.reefOp = false;
                double v = raw < -30f ? shape(raw, i1 + r, j1 + c, coarse, ctx) : raw;
                // Reefs and banks in warm shallow water, away from the atolls and barrier reefs drawn above.
                if (!ctx.reefOp && v < 0 && v > -SHALLOW_MAX_M) v = shallowSeas(v, i1 + r, j1 + c, coarse, ctx);
                elev[k] = (float) v;
                if (tags != null) tags[k] = ctx.tag;
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
        /** Aseismic ridge height added at this pixel (combined with {@link #add} by a smooth max). */
        double ridge;
        /** Sea-floor relief from the hotspots (swell, moat, arch, debris fields), faded out up volcano flanks. */
        double flex;
        int nOps;
        final int[] opType = new int[8];
        final double[] opA = new double[8], opB = new double[8], opR = new double[8], opU = new double[8];
        final long[] opH = new long[8];
        final double[] opX = new double[8], opZ = new double[8];
        /** Ops placed in warped space (island-arc volcanoes): measured from (qx, qz) instead of the pixel. */
        final boolean[] opWarped = new boolean[8];
        /** Each op's own volcano height at this pixel (metres above the floor). */
        final double[] opC = new double[8];
        /** The two largest single-volcano heights at this pixel, and the last one added. */
        double top1, top2, lastC;
        /** This pixel in warped space. */
        double qx, qz;
        /** Surface tag for this pixel, and whether a reef ring (atoll, barrier reef, raised reef) covers it. */
        byte tag;
        boolean reefOp;
        /** The hotspot chains within two cells of the last hotspot cell asked for (rows of pixels share a cell). */
        final Chain[] nearChains = new Chain[25];
        int nearCellX = Integer.MIN_VALUE, nearCellZ = Integer.MIN_VALUE;
    }

    private static final int OP_GUYOT = 1, OP_ATOLL = 2;
    /** Variants of the reef-ring op (stored in opB). */
    private static final int REEF_ATOLL = 0, REEF_BARRIER = 1, REEF_RAISED = 2;
    /** Island profile styles (bit flags). */
    private static final int STYLE_CALDERA = 1, STYLE_SCAR = 2;

    double shape(double raw, int pi, int pj, CoarseField coarse, Ctx ctx) {
        double depth = -raw;
        double landFade = clamp01((depth - 30) / 300);
        double deepFade = smoothstep(1500, 3000, depth);
        double x = pj, z = pi;   // native pixels: x east, z south

        double total = raw;

        // ---- Plate boundaries (warped space) ----
        double qx = x + warpX(x, z), qz = z + warpZ(x, z);
        ctx.qx = qx;
        ctx.qz = qz;
        voronoi(qx, qz, PLATE, 0x51A7EL, ctx);
        long[] ids = {ctx.id1, ctx.id2, ctx.id3};
        double[] sx = {ctx.s1x, ctx.s2x, ctx.s3x}, sz = {ctx.s1z, ctx.s2z, ctx.s3z};
        double[] dd = {ctx.d1, ctx.d2, ctx.d3};
        double ridgeUp = Double.NEGATIVE_INFINITY, trenchDown = 0;
        ctx.add = 0;
        ctx.top1 = 0;
        ctx.top2 = 0;
        ctx.ridge = 0;
        ctx.flex = 0;
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
        double tectonic = trenchDown;
        if (ridgeUp > Double.NEGATIVE_INFINITY) tectonic += ridgeUp * deepFade;
        if (deepFade > 0) {
            tectonic += hills.GetNoise((float) (x / KM / 8), (float) (z / KM / 8)) * 90 * deepFade;
        }

        // ---- Volcanoes ----
        hotspots(x, z, coarse, ctx);
        seamounts(x, z, coarse, ctx);
        // Swell, moat, arch, debris fields and aseismic ridges around the hotspot chains.
        if (deepFade > 0) hotspotFlex(x, z, raw, deepFade, coarse, ctx);

        // Sea-floor relief (abyssal hills, ridges, swells) belongs to the deep floor, so it fades out up a volcano's
        // flanks (on an island it left hundreds of closed hollows that filled as lakes). Troughs are added and faded;
        // rises are not added onto a volcano at all: the volcano stands on them (smooth max), since adding a ridge or
        // a swell to a flank pushed the flank back up above the sea partway down, in a ring around the island.
        double add = ctx.add;
        if (ctx.ridge > 0) add = smoothMax(add, ctx.ridge, Math.min(150, 0.5 * ctx.ridge));
        double relief = tectonic + ctx.flex;
        double floorOnly = raw + relief * smoothstep(-200, -1500, raw);
        double lift = add * landFade;
        if (lift > 0) {
            double built = raw + lift;
            double cone = built + Math.min(0, relief) * smoothstep(-200, -1500, built);
            total = smoothMax(cone, floorOnly, Math.min(150, 0.3 * lift));
        } else {
            total = floorOnly;
        }
        for (int o = 0; o < ctx.nOps; o++) {
            total = applyOp(total, o, x, z, ctx);
        }
        return Math.max(MAX_DEPTH, total);
    }

    // =========================================================================
    // Warm shallow seas: reefs and carbonate banks
    // =========================================================================

    /** Deepest water (m) that reefs or banks reshape. */
    private static final double SHALLOW_MAX_M = 160;

    /**
     * Coral reefs and carbonate banks in warm, shallow water, as a change of depth that depends only on the depth
     * itself, the position and the coarse map (so it is seamless):
     * <ul>
     *   <li><b>Carbonate banks</b> (the Bahamas), only in warm archipelago seas: the shallow sea floor is levelled into
     *       broad, flat banks a metre or two under the surface, crossed by low sand shoals, ending in steep walls
     *       where the water deepens.</li>
     *   <li><b>Fringing reefs</b> along warm coasts: a reef flat just under the surface out to where the water is a
     *       few metres deep, then a steeper reef front, with gaps here and there.</li>
     *   <li><b>Barrier reefs</b> on wide warm shelves (the Great Barrier Reef): reefs rising to the surface from a
     *       band 35–70 m deep far offshore, broken into separate reefs, with the open shelf behind them as a lagoon,
     *       and the odd sand cay.</li>
     * </ul>
     * Both kinds of reef come and go along the coast in zones a few hundred km across.
     */
    private double shallowSeas(double v, int pi, int pj, CoarseField coarse, Ctx ctx) {
        float temp = coarse.temperature(pi, pj);
        double warmth = smoothstep(REEF_TEMP_LO, REEF_TEMP_HI, temp);
        if (warmth <= 0) return v;
        double d = -v;
        float kx = (float) (pj / KM), kz = (float) (pi / KM);

        // ---- Carbonate banks ----
        double bankW = 0;
        float zone = coarse.archipelago(pi, pj);
        if (zone > 0) bankW = zone * warmth * smoothstep(BANK_ZONE, BANK_ZONE + 0.25, bankZone.GetNoise(kx, kz));
        if (bankW > 0) {
            double edge = 55 + 45 * (0.5 + 0.5 * bankLevel.GetNoise(kx * 0.3f + 91, kz * 0.3f));
            double top = 2.5 + 5 * (0.5 + 0.5 * bankLevel.GetNoise(kx, kz));
            double shoal = Math.pow(1 - Math.abs(shoals.GetNoise(kx, kz)), 4) * 2.5;   // thin, winding sand ridges
            top = Math.max(0.6, top - shoal);
            double f = d <= edge ? top : (d < edge + 25 ? top + (d - edge) * (edge + 25 - top) / 25 : d);
            f = Math.min(d, f);
            double nd = d + (f - d) * bankW;
            if (bankW > 0.5 && nd < 15) ctx.tag = TAG_BANK;
            d = nd;
        }

        // ---- Reefs ----
        double reefW = warmth * (1 - smoothstep(0.1, 0.4, bankW));
        if (reefW <= 0 || d > 110) return -d;
        float rz = reefZone.GetNoise(kx, kz);
        double fringing = reefW * smoothstep(FRINGING_ZONE, FRINGING_ZONE + 0.15, rz);
        if (fringing > 0 && d > 0 && d < 25) {
            float pn = reefPatch.GetNoise(kx, kz);
            double width = 2.5 + 4 * (0.5 + 0.5 * pn);       // the reef flat reaches out to water this deep
            double f = d <= width ? 1.2 : (d < width + 8 ? 1.2 + (d - 1.2) * smoothstep(width, width + 8, d) : d);
            f = Math.min(d, f);
            double k = fringing * smoothstep(-0.55, -0.3, pn);   // gaps where the noise is low
            // Coral grows on the reef front and the outer part of the flat, not right up to the beach.
            if (k > 0.5 && d > 0.5 * width && d < width + 7) ctx.tag = TAG_FRINGING;
            d += (f - d) * k;
        }
        double barrier = reefW * smoothstep(BARRIER_ZONE, BARRIER_ZONE + 0.15, rz);
        if (barrier > 0 && d > 20) {
            double db = 35 + 35 * (0.5 + 0.5 * reefZone.GetNoise(kx * 4f + 17, kz * 4f));
            double wb = 4 + 5 * (0.5 + 0.5 * bankLevel.GetNoise(kx * 0.5f - 33, kz * 0.5f));
            double band = 1 - Math.abs(d - db) / wb;
            if (band > 0) {
                float blob = reefPatch.GetNoise(kx * 0.8f + 211, kz * 0.8f - 5);
                double s = band * smoothstep(-0.05, 0.3, blob);
                double k = smoothstep(0.18, 0.32, s) * barrier;
                // A low sand cay on the odd reef.
                double cay = smoothstep(0.6, 0.7, s) * smoothstep(0.74, 0.8, motus.GetNoise(kx * 1.3f + 400, kz * 1.3f));
                double target = 1.2 - 2.2 * cay;
                if (k > 0.4) ctx.tag = TAG_BARRIER;
                d += (target - d) * k;
            }
        }
        return -d;
    }

    /**
     * For the feature finder: whether native pixel (i, j) is a likely spot for a shallow-sea feature
     * ({@code "fringing_reef"}, {@code "barrier_reef"} or {@code "carbonate_bank"}), judged from the coarse map and
     * the zone noise alone. Whether the feature is really there depends on the model's own depths, so the finder
     * then computes the terrain around the candidates.
     */
    public boolean shallowCandidate(String kind, double i, double j, CoarseField coarse) {
        if (coarse.temperature(i, j) < REEF_TEMP_HI - 0.5f) return false;
        float e = coarse.elevation(i, j);
        float kx = (float) (j / KM), kz = (float) (i / KM);
        switch (kind) {
            case "carbonate_bank" -> {
                return e > -300 && e < 100 && coarse.archipelago(i, j) > 0.6f
                        && bankZone.GetNoise(kx, kz) > BANK_ZONE + 0.15f;
            }
            case "fringing_reef" -> {
                return e > -120 && e < 60 && coarse.archipelago(i, j) < 0.3f
                        && reefZone.GetNoise(kx, kz) > FRINGING_ZONE + 0.1f;
            }
            case "barrier_reef" -> {
                return e > -220 && e < -20 && coarse.archipelago(i, j) < 0.3f
                        && reefZone.GetNoise(kx, kz) > BARRIER_ZONE + 0.1f;
            }
            default -> {
                return false;
            }
        }
    }

    /** The surface tag a shallow-sea feature leaves (see {@link #shallowCandidate}), or -1 for an unknown kind. */
    public static byte shallowTag(String kind) {
        return switch (kind) {
            case "carbonate_bank" -> TAG_BANK;
            case "fringing_reef" -> TAG_FRINGING;
            case "barrier_reef" -> TAG_BARRIER;
            default -> -1;
        };
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
        ventLandmarks(i0, j0, i1, j1, coarse, out);
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
                    float floor = coarse.elevation(ch.cz[k], ch.cx[k]);
                    if (floor > -VOLCANO_MIN_DEPTH) continue;
                    boolean warm = coarse.temperature(ch.cz[k], ch.cx[k]) >= REEF_TEMP;
                    String stage = volcanoStage(ch.peak[k], warm, k, ch.hk[k], ch.lift[k]);
                    boolean island = ch.peak[k] > 40;
                    if (island && hasScar(ch.peak[k], ch.hk[k])) {
                        // Marker at the mouth of the scar, on the old coast.
                        double landR = islandLandU(stage, ch.hk[k]) * ch.radius[k], a = scarAngle(ch.hk[k]);
                        double si = ch.cz[k] + Math.sin(a) * landR, sj = ch.cx[k] + Math.cos(a) * landR;
                        if (inside(si, sj, i0, j0, i1, j1)) out.add(new Landmark("landslide_scar", si, sj, (float) ch.peak[k]));
                    }
                    if (!inside(ch.cz[k], ch.cx[k], i0, j0, i1, j1)) continue;
                    float value = stage.equals("raised_coral_island") ? (float) raisedLift(ch.hk[k]) : (float) ch.peak[k];
                    out.add(new Landmark(stage, ch.cz[k], ch.cx[k], value));
                }
                if (ch.ridge && ch.count >= 4) {
                    // One marker per aseismic ridge, on its crest between two volcanoes past the islands.
                    int k = ch.count / 2;
                    double mi = 0.5 * (ch.cz[k] + ch.cz[k + 1 < ch.count ? k + 1 : k]);
                    double mj = 0.5 * (ch.cx[k] + ch.cx[k + 1 < ch.count ? k + 1 : k]);
                    if (inside(mi, mj, i0, j0, i1, j1) && coarse.elevation(mi, mj) <= -VOLCANO_MIN_DEPTH) {
                        out.add(new Landmark("aseismic_ridge", mi, mj, (float) ridgeCrest(ch, k + 0.5)));
                    }
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
                    boolean warm = coarse.temperature(c[1], c[0]) >= REEF_TEMP;
                    int extra = arcExtra(peak, warm, h);
                    String type = extra == ARC_CALDERA ? "flooded_caldera" : (extra == ARC_REEF ? "barrier_reef_island" : "arc_island");
                    out.add(new Landmark(type, c[1], c[0], (float) peak));
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

    /** One marker per hydrothermal vent field on a mid-ocean ridge (see {@link #vents}). */
    private void ventLandmarks(double i0, double j0, double i1, double j1, CoarseField coarse, java.util.List<Landmark> out) {
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
        double margin = 60 * KM;
        double[][] corners = {{j0 - margin, i0 - margin}, {j1 + margin, i0 - margin}, {j0 - margin, i1 + margin}, {j1 + margin, i1 + margin}};
        for (int ia = 0; ia < nx * nz; ia++) {
            for (int ib = ia + 1; ib < nx * nz; ib++) {
                int ra = ia / nx, ca = ia % nx, rb = ib / nx, cb = ib % nx;
                if (Math.abs(ra - rb) > 2 || Math.abs(ca - cb) > 2) continue;
                long lo = Math.min(seedId[ia], seedId[ib]), hi = Math.max(seedId[ia], seedId[ib]);
                long pair = mix(lo * 0x9E3779B97F4A7C15L ^ hi);
                if (boundaryType(pair) != BOUNDARY_RIDGE) continue;
                int il = seedId[ia] == lo ? ia : ib, ih = il == ia ? ib : ia;
                double lx = seedX[il], lz = seedZ[il], hx = seedX[ih], hz = seedZ[ih];
                double ex = hx - lx, ez = hz - lz, el = Math.sqrt(ex * ex + ez * ez);
                ex /= el; ez /= el;
                double tx = -ez, tz = ex, mx = (lx + hx) * 0.5, mz = (lz + hz) * 0.5;
                double amin = Double.MAX_VALUE, amax = -Double.MAX_VALUE;
                for (double[] cp : corners) {
                    double a = (cp[0] - mx) * tx + (cp[1] - mz) * tz;
                    amin = Math.min(amin, a); amax = Math.max(amax, a);
                }
                if (amax - amin > 40 * PLATE) continue;
                double phase = unit(mix(pair ^ 0xF00DL)) * SEGMENT;
                for (long k = (long) Math.floor((amin + phase) / SEGMENT) - 1; k <= (long) Math.ceil((amax + phase) / SEGMENT) + 1; k++) {
                    long h = hash(pair, k, 0x7E47L);
                    int n = ventFieldCount(h);
                    double off = segmentOffset(pair, k);
                    for (int f = 0; f < n; f++) {
                        long hf = mix(h + 11L * f + 1);
                        double along = k * SEGMENT + ventFieldAt(hf) - phase;
                        double bx = mx + tx * along, bz = mz + tz * along;
                        if (edgeWeightAt(bx, bz, ia, ib, seedX, seedZ) < 0.5) continue;
                        double[] c = unwarp(bx + ex * off, bz + ez * off);
                        if (!inside(c[1], c[0], i0, j0, i1, j1)) continue;
                        // Mark it only where the chimneys really go (near triple junctions the ridge fades).
                        if (vents(c[0], c[1])[0] < 0.5) continue;
                        float floor = coarse.elevation(c[1], c[0]);
                        if (floor > -VENT_MIN_DEPTH) continue;
                        out.add(new Landmark("vent_field", c[1], c[0], (float) (floor + RIDGE_HEIGHT - RIFT_DEPTH)));
                    }
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

    // ---- Hydrothermal vents (from the seed alone, so chunk decoration can ask without the coarse map) ----

    /** Coarse depth (metres) a ridge must lie under for its vent fields to be marked. */
    private static final float VENT_MIN_DEPTH = 2000f;
    /** Half-width of a vent field across the ridge axis. */
    private static final double VENT_HALF_WIDTH = 0.25 * KM;

    private static int ventFieldCount(long h) {
        double u = unit(h);
        return u < 0.3 ? 0 : (u < 0.8 ? 1 : 2);
    }

    /** Distance of a vent field from the start of its ridge segment, kept away from the transform faults. */
    private static double ventFieldAt(long hf) {
        return (0.15 + 0.7 * unit(hf)) * SEGMENT;
    }

    private static double ventFieldLength(long hf) {
        return (0.4 + 0.4 * unit(mix(hf))) * KM;
    }

    /** Vent field strength (0..1) at a point (ds across, along) of a ridge boundary, in warped space. */
    private double ventField(double ds, double along, long pair) {
        double phase = unit(mix(pair ^ 0xF00DL)) * SEGMENT;
        double a = along + phase;
        double kf = Math.floor(a / SEGMENT);
        long k = (long) kf;
        double into = a - kf * SEGMENT;
        double xr = ds - segmentOffset(pair, k);
        if (Math.abs(xr) > 3 * VENT_HALF_WIDTH) return 0;
        long h = hash(pair, k, 0x7E47L);
        int n = ventFieldCount(h);
        double best = 0;
        for (int f = 0; f < n; f++) {
            long hf = mix(h + 11L * f + 1);
            double v = Math.exp(-sq((into - ventFieldAt(hf)) / ventFieldLength(hf))) * Math.exp(-sq(xr / VENT_HALF_WIDTH));
            best = Math.max(best, v);
        }
        return best;
    }

    /**
     * Hydrothermal activity at native pixel (x east, z south), from the seed alone: {@code [0]} vent fields on
     * mid-ocean ridge axes (black smokers), {@code [1]} closeness to an active volcano (the two youngest islands of a
     * hotspot chain or an island-arc volcano that breaks the surface), 1 over the island and fading out across its shallow shelf. Both are
     * 0..1. The caller checks the water: ridges only exist under deep sea, and the coarse-map checks the terrain uses
     * are skipped here, so a volcano near a continent can also count.
     */
    public double[] vents(double x, double z) {
        Ctx ctx = new Ctx();
        double ridge = 0, volcanic = 0;
        double qx = x + warpX(x, z), qz = z + warpZ(x, z);
        voronoi(qx, qz, PLATE, 0x51A7EL, ctx);
        long[] ids = {ctx.id1, ctx.id2, ctx.id3};
        double[] sx = {ctx.s1x, ctx.s2x, ctx.s3x}, sz = {ctx.s1z, ctx.s2z, ctx.s3z};
        double[] dd = {ctx.d1, ctx.d2, ctx.d3};
        for (int e = 0; e < 3; e++) {
            int iX = e == 2 ? 1 : 0, iY = e == 0 ? 1 : 2, iZ = 3 - iX - iY;
            if (dd[iZ] - Math.max(dd[iX], dd[iY]) < -PLATE) continue;
            long lo = Math.min(ids[iX], ids[iY]), hi = Math.max(ids[iX], ids[iY]);
            long pair = mix(lo * 0x9E3779B97F4A7C15L ^ hi);
            int type = boundaryType(pair);
            if (type == BOUNDARY_PASSIVE) continue;
            boolean xIsLo = ids[iX] == lo;
            double lx = xIsLo ? sx[iX] : sx[iY], lz = xIsLo ? sz[iX] : sz[iY];
            double hx = xIsLo ? sx[iY] : sx[iX], hz = xIsLo ? sz[iY] : sz[iX];
            double nx = hx - lx, nz = hz - lz, nl = Math.sqrt(nx * nx + nz * nz);
            nx /= nl; nz /= nl;
            double tx = -nz, tz = nx;
            double mx = (lx + hx) * 0.5, mz = (lz + hz) * 0.5;
            double ds = (qx - mx) * nx + (qz - mz) * nz;
            double along = (qx - mx) * tx + (qz - mz) * tz;
            double bx = qx - ds * nx, bz = qz - ds * nz;
            double beyond = Math.hypot(bx - sx[iZ], bz - sz[iZ]) - Math.hypot(bx - sx[iX], bz - sz[iX]);
            if (type == BOUNDARY_RIDGE) {
                double w = smoothstep(-JUNCTION_TAPER, 0, beyond);
                if (w > 0) ridge = Math.max(ridge, w * ventField(ds, along, pair));
            } else {
                double w = smoothstep(-JUNCTION_TAPER, JUNCTION_TAPER, beyond);
                boolean subHi = (pair & 1L) == 0;
                double xo = subHi ? -ds : ds;                  // positive into the overriding plate
                if (w <= 0 || Math.abs(xo - ARC_DISTANCE) > 30 * KM) continue;
                long kc = Math.round(along / ARC_SPACING);
                for (long k = kc - 1; k <= kc + 1; k++) {
                    long h = hash(pair, k, 0xA2C);
                    if (!arcExists(h)) continue;
                    double ak = arcAlong(k, h), across = arcAcross(h), radius = arcRadius(h);
                    double strength = trenchStrength(ak, pair) * w;
                    if (strength <= 0.05 || arcPeak(strength, h) <= 0) continue;
                    double r = Math.hypot(along - ak, xo - across);
                    volcanic = Math.max(volcanic, 1 - smoothstep(0.45, 0.9, r / radius));
                }
            }
        }
        int cx = (int) Math.floor(x / HOTSPOT_CELL), cz = (int) Math.floor(z / HOTSPOT_CELL);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Chain ch = chain(cx + dx, cz + dz);
                if (ch == null) continue;
                // Only the young end of the chain is still active: the head and the island behind it.
                for (int k = 0; k < Math.min(2, ch.count); k++) {
                    if (ch.peak[k] <= 40) continue;
                    double r = Math.hypot(x - ch.cx[k], z - ch.cz[k]);
                    volcanic = Math.max(volcanic, 1 - smoothstep(0.45, 0.9, r / ch.radius[k]));
                }
            }
        }
        return new double[]{ridge, volcanic};
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
                double ang = Math.atan2(xo - across, along - ak);
                boolean warm = coarse.temperature(c[1], c[0]) >= REEF_TEMP;
                int extra = arcExtra(peak, warm, h);
                double relief = peak - floor;
                int style = extra == ARC_CALDERA ? STYLE_CALDERA : 0;
                volcano(ctx, relief * islandCone(r, radius, -floor / relief, landU, ang, x, z, h, style, relief));
                if (extra == ARC_REEF) {
                    // The reef ring is placed in warped space too, so it stays centred on the island.
                    double landR = landU * radius;
                    double rim = Math.min(barrierRim(landR, h), 0.95 * radius);
                    pushOp(ctx, OP_ATOLL, rim, REEF_BARRIER, radius, landR, h, cqx, cqz);
                    ctx.opWarped[ctx.nOps - 1] = true;
                }
            } else {
                volcano(ctx, (peak - floor) * cone(r, radius * SUBMERGED_SHRINK, x, z, h, false));
            }
        }
    }

    private void hotspots(double x, double z, CoarseField coarse, Ctx ctx) {
        int cx = (int) Math.floor(x / HOTSPOT_CELL), cz = (int) Math.floor(z / HOTSPOT_CELL);
        boolean cached = cx == ctx.nearCellX && cz == ctx.nearCellZ;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Chain ch = cached ? ctx.nearChains[(dz + 2) * 5 + dx + 2] : chain(cx + dx, cz + dz);
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
                    String stage = volcanoStage(ch.peak[k], warm, k, ch.hk[k], ch.lift[k]);
                    addVolcano(x, z, ch.cx[k], ch.cz[k], r, ch.radius[k], ch.peak[k], floor, stage, ch.hk[k], ctx);
                }
            }
        }
    }

    /**
     * Sea-floor relief around the hotspot chains, from chains up to two cells away (their swells reach that far):
     * <ul>
     *   <li>the <b>swell</b>, a broad bulge of hot, buoyant mantle under the active hotspot, highest at the head and
     *       fading down the chain;</li>
     *   <li>the <b>moat</b> right around each big island, where its weight bends the plate down (the Hawaiian Deep),
     *       and the low <b>arch</b> beyond it;</li>
     *   <li><b>debris fields</b> of hummocky blocks spread across the floor below a landslide scar;</li>
     *   <li><b>aseismic ridges</b>: some chains are joined into one continuous ridge, its crest deepening with age.</li>
     * </ul>
     * The swell, moat, arch and debris go into {@code ctx.flex}; the ridge into {@code ctx.ridge}.
     */
    private void hotspotFlex(double x, double z, double raw, double deepFade, CoarseField coarse, Ctx ctx) {
        int cx = (int) Math.floor(x / HOTSPOT_CELL), cz = (int) Math.floor(z / HOTSPOT_CELL);
        if (cx != ctx.nearCellX || cz != ctx.nearCellZ) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) ctx.nearChains[(dz + 2) * 5 + dx + 2] = chain(cx + dx, cz + dz);
            }
            ctx.nearCellX = cx;
            ctx.nearCellZ = cz;
        }
        double ridgeGate = smoothstep(1500, 2500, -raw);
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                Chain ch = ctx.nearChains[(dz + 2) * 5 + dx + 2];
                if (ch == null) continue;
                double ox = x - ch.hx, oz = z - ch.hz;
                double t = ox * ch.vx + oz * ch.vz, across = -ox * ch.vz + oz * ch.vx;
                double aa = Math.abs(across);
                if (aa < 3 * SWELL_WIDTH && t > -4 * SWELL_AHEAD && t < 2.6 * SWELL_DECAY) {
                    double along = t >= 0
                            ? Math.exp(-t / SWELL_DECAY) * (1 - smoothstep(1.9 * SWELL_DECAY, 2.6 * SWELL_DECAY, t))
                            : Math.exp(-sq(t / SWELL_AHEAD));
                    double acr = Math.exp(-sq(across / SWELL_WIDTH)) * (1 - smoothstep(2.2 * SWELL_WIDTH, 3 * SWELL_WIDTH, aa));
                    ctx.flex += SWELL_HEIGHT * along * acr * deepFade;
                }
                double reach = MAX_FEATURE_REACH;
                if (aa > reach || t < -reach || t > (ch.count - 1) * ch.spacing + reach) continue;
                int kmin = Math.max(0, (int) Math.floor((t - reach) / ch.spacing) - 1);
                int kmax = Math.min(ch.count - 1, (int) Math.ceil((t + reach) / ch.spacing) + 1);
                for (int k = kmin; k <= kmax; k++) {
                    double r = Math.hypot(x - ch.cx[k], z - ch.cz[k]);
                    double u0 = r / ch.radius[k];
                    if (u0 > 2.9 || ch.peak[k] < -1500) continue;
                    float floor = coarse.elevation(ch.cz[k], ch.cx[k]);
                    if (floor > -VOLCANO_MIN_DEPTH) continue;
                    double peak = ch.peak[k];
                    // Moat and arch: the plate's bending under the island's weight, strongest under big young islands.
                    double load = smoothstep(-1500, 1500, peak) * Math.max(0.5, Math.min(1.2, (peak - floor) / 4500));
                    double bend = -Math.exp(-sq((u0 - 1.15) / 0.35)) + 0.25 * Math.exp(-sq((u0 - 2.2) / 0.45));
                    ctx.flex += MOAT_DEPTH * load * bend * smoothstep(0.55, 0.85, u0) * (1 - smoothstep(2.5, 2.9, u0)) * deepFade;
                    // Debris field below a landslide scar.
                    if (peak > 40 && hasScar(peak, ch.hk[k]) && u0 > 0.7 && u0 < 2.8) {
                        double ang = Math.atan2(z - ch.cz[k], x - ch.cx[k]), sAng = scarAngle(ch.hk[k]);
                        double da = Math.atan2(Math.sin(ang - sAng), Math.cos(ang - sAng));
                        double env = Math.exp(-sq(da / 0.3)) * smoothstep(0.7, 1.0, u0) * (1 - smoothstep(2.1, 2.8, u0));
                        if (env > 0.01) {
                            float n = gullies.GetNoise((float) (x / KM * 1.5), (float) (z / KM * 1.5), (ch.hk[k] & 0xFF) * 3.1f);
                            ctx.flex += 380 * env * smoothstep(0.1, 0.55, n) * deepFade;
                        }
                    }
                }
                if (ch.ridge && ridgeGate > 0) {
                    double best = 0;
                    for (int k = kmin; k <= kmax; k++) {
                        // Segment k -> k+1; past the last volcano the ridge runs on 30 km and tapers away.
                        double ax = ch.cx[k], az = ch.cz[k], bx, bz, taper;
                        if (k + 1 < ch.count) { bx = ch.cx[k + 1]; bz = ch.cz[k + 1]; taper = 0; }
                        else { bx = ax + ch.vx * 30 * KM; bz = az + ch.vz * 30 * KM; taper = 1; }
                        double ex = bx - ax, ez = bz - az, el2 = ex * ex + ez * ez;
                        double s = Math.max(0, Math.min(1, ((x - ax) * ex + (z - az) * ez) / el2));
                        double dist = Math.hypot(x - (ax + s * ex), z - (az + s * ez));
                        float kx = (float) (x / KM * 0.35), kz = (float) (z / KM * 0.35);
                        dist *= 1 + 0.18 * coastShape.GetNoise(kx + 503, kz + (ch.hk[0] & 0xFF));
                        double core = (2.6 + 1.2 * unit(mix(ch.hk[0] + 7))) * KM;
                        double prof = 1 - smoothstep(core, core + 1.6 * KM, dist);
                        if (prof <= 0) continue;
                        double crest = ridgeCrest(ch, k + s) - taper * s * 1500;
                        best = Math.max(best, Math.max(0, crest - raw) * prof * (1 - taper * smoothstep(0.5, 1, s)));
                    }
                    ctx.ridge = Math.max(ctx.ridge, best * ridgeGate);
                }
            }
        }
    }

    /** Crest height (m) of an aseismic ridge at fractional chain position kf: deeper with age, with saddles between volcanoes. */
    private static double ridgeCrest(Chain ch, double kf) {
        double age = Math.min(1, kf / Math.max(1, ch.count - 1));
        double frac = kf - Math.floor(kf);
        return -900 - 1500 * age - 300 * sq(Math.sin(Math.PI * frac));
    }

    /** One hotspot and its chain of volcanoes; everything here depends only on the seed. */
    static final class Chain {
        double hx, hz, vx, vz, spacing;
        int count;
        double[] cx, cz, radius, peak;
        long[] hk;
        /** How strongly each volcano sits on a trench's outer rise (0..1): lifted atolls become raised coral islands. */
        double[] lift;
        /** Whether the chain is an aseismic ridge (its volcanoes joined by a continuous ridge). */
        boolean ridge;
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
        ch.lift = new double[ch.count];
        for (int k = 0; k < ch.count; k++) ch.lift[k] = outerRiseAt(ch.cx[k], ch.cz[k]);
        ch.ridge = unit(mix(h + 6)) < ASEISMIC_CHANCE;
        return ch;
    }

    /**
     * How strongly native pixel (x, z) sits on the outer rise of a trench, the bulge where the subducting plate bends
     * before it dives (0..1, from the seed alone). Wider than the visible rise, since an atoll needs only a little
     * uplift to come out of the sea.
     */
    private double outerRiseAt(double x, double z) {
        Ctx ctx = new Ctx();
        double qx = x + warpX(x, z), qz = z + warpZ(x, z);
        voronoi(qx, qz, PLATE, 0x51A7EL, ctx);
        long[] ids = {ctx.id1, ctx.id2, ctx.id3};
        double[] sx = {ctx.s1x, ctx.s2x, ctx.s3x}, sz = {ctx.s1z, ctx.s2z, ctx.s3z};
        double[] dd = {ctx.d1, ctx.d2, ctx.d3};
        double best = 0;
        for (int e = 0; e < 3; e++) {
            int iX = e == 2 ? 1 : 0, iY = e == 0 ? 1 : 2, iZ = 3 - iX - iY;
            if (dd[iZ] - Math.max(dd[iX], dd[iY]) < -PLATE) continue;
            long lo = Math.min(ids[iX], ids[iY]), hi = Math.max(ids[iX], ids[iY]);
            long pair = mix(lo * 0x9E3779B97F4A7C15L ^ hi);
            if (boundaryType(pair) != BOUNDARY_TRENCH) continue;
            boolean xIsLo = ids[iX] == lo;
            double lx = xIsLo ? sx[iX] : sx[iY], lz = xIsLo ? sz[iX] : sz[iY];
            double hx = xIsLo ? sx[iY] : sx[iX], hz = xIsLo ? sz[iY] : sz[iX];
            double nx = hx - lx, nz = hz - lz, nl = Math.sqrt(nx * nx + nz * nz);
            nx /= nl; nz /= nl;
            double tx = -nz, tz = nx;
            double mx = (lx + hx) * 0.5, mz = (lz + hz) * 0.5;
            double ds = (qx - mx) * nx + (qz - mz) * nz;
            double along = (qx - mx) * tx + (qz - mz) * tz;
            double bx = qx - ds * nx, bz = qz - ds * nz;
            double beyond = Math.hypot(bx - sx[iZ], bz - sz[iZ]) - Math.hypot(bx - sx[iX], bz - sz[iX]);
            double w = smoothstep(-JUNCTION_TAPER, JUNCTION_TAPER, beyond);
            if (w <= 0) continue;
            boolean subHi = (pair & 1L) == 0;
            double xs = subHi ? ds : -ds;                      // positive into the subducting plate
            double rise = Math.exp(-sq((xs - OUTER_RISE_AT) / (1.4 * OUTER_RISE_WIDTH)));
            best = Math.max(best, rise * trenchStrength(along, pair) * w);
        }
        return best;
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
                double radius = (4 + 7 * unit(mix(h + 2))) * KM * SUBMERGED_SHRINK * 1.5;
                double r = Math.hypot(x - sx, z - sz);
                if (r > radius * 1.3) continue;
                float floor = coarse.elevation(sz, sx);
                if (floor > -SEAMOUNT_MIN_DEPTH) continue;
                double peak = seamountPeak(floor, h);
                boolean guyot = isGuyot(peak, h);
                if (guyot) {
                    // A guyot was planed flat at sea level, then sank: build it taller and cut the top off.
                    volcano(ctx, (peak + 600 - floor) * tableCone(r, radius, x, z, h));
                    pushOp(ctx, OP_GUYOT, peak, 0, radius, 0, h, sx, sz);
                } else {
                    volcano(ctx, (peak - floor) * cone(r, radius, x, z, h, false));
                }
            }
        }
    }

    /** A hotspot volcano at a stage of its life set by how far it has sunk (its peak height). */
    private void addVolcano(double x, double z, double vcx, double vcz, double r, double radius,
                            double peak, double floor, String stage, long h, Ctx ctx) {
        switch (stage) {
            case "volcanic_island", "flooded_caldera", "barrier_reef_island" -> {
                double landU = islandLandU(stage, h);
                double ang = Math.atan2(z - vcz, x - vcx);
                double relief = peak - floor;
                int style = (stage.equals("flooded_caldera") ? STYLE_CALDERA : 0) | (hasScar(peak, h) ? STYLE_SCAR : 0);
                volcano(ctx, relief * islandCone(r, radius, -floor / relief, landU, ang, x, z, h, style, relief));
                if (stage.equals("barrier_reef_island")) {
                    // The island is sinking and its fringing reef grew up into a ring offshore, with a lagoon between.
                    double landR = landU * radius;
                    pushOp(ctx, OP_ATOLL, barrierRim(landR, h), REEF_BARRIER, radius, landR, h, vcx, vcz);
                }
            }
            case "atoll", "raised_coral_island" -> {
                // The volcano has sunk out of sight and its fringing reef kept growing up into a ring.
                double sub = Math.min(peak, -350);
                volcano(ctx, (sub - floor) * tableCone(r, radius, x, z, h));
                double rim = radius * (0.22 + 0.13 * unit(mix(h + 9)));
                if (stage.equals("atoll")) {
                    pushOp(ctx, OP_ATOLL, rim, REEF_ATOLL, radius, 0, h, vcx, vcz);
                } else {
                    // Later the plate carried it up the outer rise of a trench and lifted the whole atoll out of the sea.
                    pushOp(ctx, OP_ATOLL, rim, REEF_RAISED, radius, raisedLift(h), h, vcx, vcz);
                }
            }
            case "guyot" -> {
                double top = Math.min(peak, -250);
                double rs = radius * SUBMERGED_SHRINK;
                volcano(ctx, (top + 600 - floor) * tableCone(r, rs, x, z, h));
                pushOp(ctx, OP_GUYOT, top, 0, rs, 0, h, vcx, vcz);
            }
            default -> volcano(ctx, (peak - floor) * cone(r, radius * SUBMERGED_SHRINK, x, z, h, false));
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

    private static final int ARC_PLAIN = 0, ARC_CALDERA = 1, ARC_REEF = 2;

    /** Extras on an island-arc island that breaks the surface: a flooded caldera, or in warm water a barrier reef. */
    private static int arcExtra(double peak, boolean warm, long h) {
        long he = mix(h + 70);
        if (peak > 250 && unit(he) < ARC_CALDERA_CHANCE) return ARC_CALDERA;
        // The reef ring needs room inside the volcano's footprint for a lagoon.
        if (warm && unit(mix(he)) < ARC_REEF_CHANCE) return ARC_REEF;
        return ARC_PLAIN;
    }

    private static boolean arcExists(long h) { return unit(h) <= 1.0; }
    private static double arcAlong(long k, long h) { return k * ARC_SPACING + (unit(mix(h)) - 0.5) * 14 * KM; }
    private static double arcAcross(long h) { return ARC_DISTANCE + (unit(mix(h + 1)) - 0.5) * 16 * KM; }
    private static double arcRadius(long h) { return (7 + 7 * unit(mix(h + 2))) * KM; }
    private static double arcPeak(double strength, long h) { return lerp(-2500, -350 + 2500 * unit(mix(h + 3)), strength); }

    /**
     * What a hotspot volcano has become by now, from its peak height (how far it has sunk), the water temperature,
     * its place in the chain ({@code k}, 0 is the active head), its hash and the outer-rise uplift under it.
     */
    static String volcanoStage(double peak, boolean warm, int k, long h, double lift) {
        if (peak > 40) {
            if (k == 0) return "volcanic_island";
            if (peak > 250 && unit(mix(h + 40)) < CALDERA_CHANCE) return "flooded_caldera";
            if (warm && unit(mix(h + 41)) < BARRIER_CHANCE) return "barrier_reef_island";
            return "volcanic_island";
        }
        if (warm && peak > -1600) {
            if (lift >= OUTER_RISE_LIFT || unit(mix(h + 42)) < MAKATEA_CHANCE) return "raised_coral_island";
            return "atoll";
        }
        if (peak > -1800) return "guyot";
        return "seamount";
    }

    /** Share of the radius that is above the sea for an island at this stage. */
    private static double islandLandU(String stage, long h) {
        if (stage.equals("barrier_reef_island")) return 0.24 + 0.14 * unit(mix(h + 8));   // smaller, worn down
        return 0.3 + 0.2 * unit(mix(h + 8));
    }

    /** Whether an island this high has a giant landslide scar on one flank. */
    private static boolean hasScar(double peak, long h) {
        return peak > 500 && unit(mix(h + 60)) < SCAR_CHANCE;
    }

    /** Direction (radians, from the island centre) the landslide scar faces. */
    private static double scarAngle(long h) {
        return unit(mix(h + 61)) * 2 * Math.PI;
    }

    /** Reef radius of a barrier reef around an island whose shore is about {@code landR} pixels out. */
    private static double barrierRim(double landR, long h) {
        // Mostly clear of the island (whose coast wobbles by ~14%); where the wobbly ring does touch it, the reef just
        // joins the shore as a fringing reef, as real barrier reefs do in places.
        return 1.12 * landR + (0.8 + 1.1 * unit(mix(h + 44))) * KM;
    }

    /** How far (metres) a raised coral island was lifted: its old reef rim now stands this high. */
    private static double raisedLift(long h) {
        return 30 + 70 * unit(mix(h + 43));
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
    private double islandCone(double r, double radius, double sea, double landU, double ang,
                              double x, double z, long h) {
        return islandCone(r, radius, sea, landU, ang, x, z, h, 0, 0);
    }

    /**
     * {@link #islandCone} with optional extras ({@code style} bit flags): {@link #STYLE_CALDERA} collapses the summit
     * into a flooded caldera, {@link #STYLE_SCAR} cuts a giant landslide scar into one flank. {@code relief} is the
     * volcano's height above the sea floor in metres (to place features at real depths).
     */
    private double islandCone(double r, double radius, double sea, double landU, double ang,
                              double x, double z, long h, int style, double relief) {
        double u = roughRadius(r, radius, x, z, h) / radius;
        // Ragged coastline: headlands and coves from ~150 m to ~3 km, up to ~1 km deep. The noise is sampled by direction around the
        // summit (on a circle sized to the coast, so its wavelengths are in km) and only slowly with distance, and
        // it fades in toward the coast, so along any line down from the summit the ground still only falls: no
        // closed hollows.
        {
            double coastKm = landU * radius / KM;
            double rc = coastKm / (2 * Math.PI);
            float cfa = (float) Math.cos(ang), sfa = (float) Math.sin(ang);
            double n = coastFine.GetNoise((float) rc * cfa + 37, (float) rc * sfa - 211, (float) (r / KM * 0.12) + (h & 0xFF));
            double du = 1.3 * KM / radius * n;
            u += du * smoothstep(0.3 * landU, landU, u);
        }
        if (u >= 1) return 0;
        double s;
        if (u < landU) {
            double t = u / landU;
            s = 1 - (1 - sea) * Math.pow(t, 1.6);
            // Gullies run straight down the flanks: the cut depends on the direction around the summit (sampled on
            // a circle, so there is no seam), barely on the distance. Each cut deepens toward the coast, so every
            // gully drains to the sea and the flanks hold no closed hollows; sharp ridges stand between them.
            double coastKm = landU * radius / KM;
            double rc = coastKm / (2 * Math.PI);
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang), rad = (float) (r / KM * 0.05) + (h & 0xFF);
            float broad = gullies.GetNoise((float) rc * ca, (float) rc * sa, rad);
            float fine = gullies.GetNoise((float) (4 * rc) * ca + 91, (float) (4 * rc) * sa, 3 * rad);
            double g = (0.45 * Math.abs(broad) + 0.55 * Math.abs(fine)) * smoothstep(0.1, 0.5, t);
            s = sea + (s - sea) * (1 - 0.45 * g);
            if ((style & STYLE_CALDERA) == 0) {
                s -= (1 - sea) * 0.12 * Math.exp(-sq(t / 0.08));   // summit caldera (holds a crater lake)
            }
        } else {
            s = sea * Math.pow((1 - u) / (1 - landU), 1.3);
        }
        if ((style & STYLE_CALDERA) != 0) s = floodedCaldera(s, u, r, radius, sea, landU, relief, ang, x, z, h);
        if ((style & STYLE_SCAR) != 0) s = landslideScar(s, r, radius, sea, landU, relief, ang, x, z, h);
        return s;
    }

    /**
     * A flooded caldera (Santorini, Deception Island): the summit has collapsed into a wide, flat-floored bay a few
     * hundred metres deep, walled by steep cliffs, and the sea has broken in through one or two gaps in the ring.
     * Sometimes a young lava dome rises from the bay as a small island. Works on the island profile {@code s}
     * (1 at the summit, {@code sea} at sea level). The bay and the gaps always lead down to the open sea, so nothing
     * here is a closed hollow.
     */
    private double floodedCaldera(double s, double u, double r, double radius, double sea, double landU,
                                  double relief, double ang, double x, double z, long h) {
        double cu = landU * (0.45 + 0.25 * unit(mix(h + 50)));
        // The bay sits off-centre, so the ring is broad on one side and thin on the other.
        double offAng = unit(mix(h + 66)) * 2 * Math.PI, off = 0.4 * cu * radius * unit(mix(h + 67));
        double px0 = r * Math.cos(ang) - off * Math.cos(offAng), pz0 = r * Math.sin(ang) - off * Math.sin(offAng);
        double rb = Math.hypot(px0, pz0);
        double ub = r > 1e-6 ? u * rb / r : rb / radius;               // same roughening as the island outline
        double angB = Math.atan2(pz0, px0);
        float ca = (float) Math.cos(angB), sa = (float) Math.sin(angB);
        // The rim wobbles with direction (sampled on a circle, so there is no seam).
        double cr = cu * (1 + 0.12 * coastShape.GetNoise(ca * 2.2f + 401 + (h & 0xFF), sa * 2.2f - 77));
        double w = 0.07 * landU;                                     // width of the inner cliffs
        double floorM = -(150 + 250 * unit(mix(h + 51)));
        double sFloor = sea + floorM / relief;
        if (ub < cr) s = sFloor + (s - sFloor) * smoothstep(cr - w, cr, ub);
        // Gaps in the ring where the sea broke in, carried out past the old coast; now and then one whole side of the
        // ring is gone.
        if (ub > cr - w && u < landU * 1.3) {
            int nGaps = unit(mix(h + 52)) < 0.35 ? 2 : 1;
            for (int g = 0; g < nGaps; g++) {
                double gapAng = unit(mix(h + 53 + g)) * 2 * Math.PI;
                double da = Math.abs(Math.atan2(Math.sin(angB - gapAng), Math.cos(angB - gapAng))) * rb;
                boolean wide = g == 0 && unit(mix(h + 68)) < 0.25;
                double gw = (wide ? 1.4 + 1.4 * unit(mix(h + 55)) : 0.45 + 0.6 * unit(mix(h + 55 + g))) * KM;
                double sGap = sea - (40 + 80 * unit(mix(h + 57 + g))) / relief;
                double gg = Math.exp(-sq(da / gw));
                if (s > sGap) s -= (s - sGap) * gg;
            }
        }
        // A young lava dome in the bay (Nea Kameni).
        if (unit(mix(h + 58)) < 0.5 && ub < cr) {
            double dAng = unit(mix(h + 59)) * 2 * Math.PI, dOff = 0.3 * cr * radius * unit(mix(h + 62));
            double px = px0 - dOff * Math.cos(dAng), pz = pz0 - dOff * Math.sin(dAng);
            double dr = 0.3 * cr * radius;
            float kx = (float) (x / KM * 1.7) + (h & 0xFF), kz = (float) (z / KM * 1.7);
            double dd = Math.hypot(px, pz) * (1 + 0.18 * coastShape.GetNoise(kx, kz));
            if (dd < dr) {
                double sTop = sea + (40 + 80 * unit(mix(h + 63))) / relief;
                s = Math.max(s, sFloor + (sTop - sFloor) * Math.pow(1 - sq(dd / dr), 1.5));
            }
        }
        return s;
    }

    /**
     * A giant landslide scar (El Golfo on El Hierro, Hawaii's Nuuanu slide): one flank of the island has slid into
     * the sea, leaving an amphitheatre open to the sea with a steep headwall and side walls, its floor sloping down to
     * the shore and on under the sea as a chute. The floor also rises a little toward the walls, so water runs down
     * the middle and out to sea.
     */
    private double landslideScar(double s, double r, double radius, double sea, double landU, double relief,
                                 double ang, double x, double z, long h) {
        double sAng = scarAngle(h);
        double landR = landU * radius;
        double dc = landR * (0.9 + 0.15 * unit(mix(h + 64)));        // centre of the amphitheatre, near the coast
        double rs = landR * (0.45 + 0.25 * unit(mix(h + 65)));       // its radius
        double px = r * Math.cos(ang), pz = r * Math.sin(ang);
        double ex = Math.cos(sAng), ez = Math.sin(sAng);
        double a = px * ex + pz * ez, b = -px * ez + pz * ex;          // along the scar (outward) and across it
        float kx = (float) (x / KM * 0.9) + 211, kz = (float) (z / KM * 0.9) - (h & 0xFF);
        double ds = Math.hypot(a - dc, b) * (1 + 0.1 * coastShape.GetNoise(kx, kz));
        double wall = 0.14 * rs;
        if (ds > rs || a < dc - rs - wall) return s;
        // Height of the old flank at the head of the scar, in metres above the sea.
        double headU = (dc - rs) / radius;
        double sHead = headU < landU ? 1 - (1 - sea) * Math.pow(headU / landU, 1.6) : sea;
        double headM = Math.max(0, (sHead - sea) * relief);
        double q = (a - (dc - rs)) / rs;                               // 0 at the headwall, 1 at the centre, 2 beyond
        double floorM = q < 1 ? 0.5 * headM * Math.pow(1 - q, 1.3) : -(q - 1) * 600;
        floorM += Math.abs(b) / KM * 8;                                // rise toward the side walls
        double sF = sea + floorM / relief;
        double k = smoothstep(rs - wall, rs, ds);
        return Math.min(s, sF + (s - sF) * k);
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

    /**
     * Adds one volcano's height (metres above the sea floor) at this pixel. Overlapping volcanoes do not stack: the
     * higher one wins, with the join rounded off over a few hundred metres so neighbours still merge with a saddle.
     * (Summing them put a seamount's whole 2-4 km cone on top of any island whose flank it sat on: tall, perfectly
     * conical spires.)
     */
    private static void volcano(Ctx ctx, double c) {
        ctx.lastC = c;
        if (c <= 0) return;
        if (c > ctx.top1) { ctx.top2 = ctx.top1; ctx.top1 = c; } else if (c > ctx.top2) ctx.top2 = c;
        ctx.add = smoothUnion(ctx.add, c);
    }

    /** max(a, b) for non-negative heights, rounded where they are close; exactly the max when either is 0. */
    private static double smoothUnion(double a, double b) {
        double k = Math.min(300, 0.5 * Math.min(a, b));
        double diff = Math.abs(a - b);
        if (k <= 0 || diff >= k) return Math.max(a, b);
        double t = (k - diff) / k;
        return Math.max(a, b) + t * t * k * 0.25;
    }

    private static void pushOp(Ctx ctx, int type, double a, double b, double radius, double u, long h,
                               double cx, double cz) {
        if (ctx.nOps >= ctx.opType.length) return;
        int o = ctx.nOps++;
        ctx.opType[o] = type; ctx.opA[o] = a; ctx.opB[o] = b; ctx.opR[o] = radius; ctx.opU[o] = u;
        ctx.opH[o] = h; ctx.opX[o] = cx; ctx.opZ[o] = cz; ctx.opWarped[o] = false; ctx.opC[o] = ctx.lastC;
    }

    private double applyOp(double v, int o, double x, double z, Ctx ctx) {
        boolean warped = ctx.opWarped[o];
        double px = warped ? ctx.qx : x, pz = warped ? ctx.qz : z;
        double r = Math.hypot(px - ctx.opX[o], pz - ctx.opZ[o]);
        if (ctx.opType[o] == OP_GUYOT) {
            // The planed-off top belongs to this guyot only: where another volcano stands higher (an island it sits
            // beside), leave that one alone instead of cutting a flat notch through it.
            double top = ctx.opA[o];
            if (r < ctx.opR[o] && v > top) {
                double own = ctx.opC[o], others = own >= ctx.top1 ? ctx.top2 : ctx.top1;
                double w = 1 - smoothstep(-100, 150, others - own);
                v -= (v - (top + (v - top) * 0.03)) * w;
            }
            return v;
        }
        ctx.reefOp = true;
        double ringed = reefRing(v, o, px, pz, x, z, ctx);
        // The op only reaches 1.3 radii; fade out before that so the talus apron meets the sea floor without a step.
        double fade = 1 - smoothstep(1.05, 1.3, r / ctx.opR[o]);
        return v + (ringed - v) * fade;
    }

    /**
     * A reef ring around a volcano: an atoll ({@link #REEF_ATOLL}), a barrier reef with a lagoon around a sinking
     * island ({@link #REEF_BARRIER}; {@code opU} is the island's land radius), or an atoll lifted out of the sea
     * ({@link #REEF_RAISED}; {@code opU} is the uplift in metres). {@code (px, pz)} is where the ring is measured
     * from (warped space for island-arc volcanoes), {@code (x, z)} the pixel itself (for noise).
     */
    private double reefRing(double v, int o, double px, double pz, double x, double z, Ctx ctx) {
        long h = ctx.opH[o];
        int variant = (int) ctx.opB[o];
        // Its ring is oval and wobbly: stretch along a random axis, then roughen at two scales, so the reef edge
        // wanders in lobes and bays instead of following a clean curve. A barrier reef is barely stretched, so it
        // stays clear of its island all the way round.
        double dx = px - ctx.opX[o], dz = pz - ctx.opZ[o];
        double axis = unit(mix(h + 12)) * Math.PI;
        double stretch = 1 + (variant == REEF_BARRIER ? 0.12 : 0.5) * unit(mix(h + 13));
        double ca = Math.cos(axis), sa = Math.sin(axis);
        double ux = (dx * ca + dz * sa) / stretch, uz = -dx * sa + dz * ca;
        float kx = (float) (x / KM), kz = (float) (z / KM);
        double re = Math.hypot(ux, uz) * (1 + 0.12 * coastShape.GetNoise(kx * 0.6f + 31, kz * 0.6f))
                + 0.25 * KM * coastShape.GetNoise(kx * 3.3f + 57, kz * 3.3f - 13);
        double rim = ctx.opA[o];
        double t = rim - re;                                  // distance in from the reef edge (pixels)

        // Where on the ring: coordinates that run along the rim (1 unit = 1 km, seamless all the way round), and
        // how exposed this side is. The trade winds blow from the east, so the eastern (windward) reef is broad,
        // shallow and lined with islets; the western (leeward) one is narrow, deeper and broken by passes.
        double ang = Math.atan2(uz, ux);
        double rimKm = rim / KM;
        float ax = (float) (Math.cos(ang) * rimKm), az = (float) (Math.sin(ang) * rimKm);
        float salt = (float) (h & 0xFF) * 7.3f;
        double windDir = (unit(mix(h + 14)) - 0.5) * 0.8;
        double windward = 0.5 + 0.5 * Math.cos(Math.atan2(dz, dx) - windDir);
        float broad = motus.GetNoise(ax * 0.35f + 77, az * 0.35f, salt);                    // ~4 km changes
        double flatW = (0.45 + 0.25 * unit(mix(h + 11))) * KM * (0.5 + 0.8 * windward) * (1 + 0.4 * broad);
        if (variant == REEF_BARRIER) flatW *= 0.7;
        flatW = Math.min(flatW, 0.6 * rim);

        if (variant == REEF_RAISED) {
            return raisedReef(v, t, re, rim, flatW, windward, ang, axis, stretch, windDir, dx, dz, h, ctx.opU[o], ctx);
        }

        if (t < 0) {
            // Fore-reef wall (~40 degrees) down to 600 m, then a talus apron that slopes away onto the volcano's own
            // flank instead of running on as a cone.
            double out = -t / KM;
            double reef = out < 0.66 ? -1.5 - out * 900 : -600 + (v + 600) * (1 - Math.exp(-(out - 0.66) / 2.5));
            double res = smoothMax(v, reef, 60);
            if (res > -40) ctx.tag = TAG_REEF;
            return res;
        }
        double a = t / flatW;                                 // 0 at the reef edge, 1 at the lagoon

        // Reef flat: awash on the windward side, a few metres down in places on the leeward side.
        float deep = motus.GetNoise(ax * 0.5f - 40, az * 0.5f, salt + 19);
        double flat = -1.2 - 5 * sq(1 - windward) * smoothstep(-0.3, 0.4, deep);
        double base;
        boolean lagoon = a > 1, patchReef = false;
        if (a <= 1) {
            base = flat;
        } else {
            // Lagoon: a sandy slope off the reef flat, then a floor deepest in the middle, dotted with patch
            // reefs and the odd sand cay. Behind a barrier reef it is deepest halfway to the island.
            double d = t - flatW, inner = Math.max(1, rim - flatW);
            double target = variant == REEF_BARRIER
                    ? -8 - 37 * smoothstep(0, Math.max(1, 0.5 * (inner - ctx.opU[o])), d)
                    : -10 - 40 * smoothstep(0, inner, d);
            base = flat + (target - flat) * smoothstep(0, 0.35 * KM, d);
            float patch = motus.GetNoise(kx * 2.3f, kz * 2.3f);
            float patchMin = variant == REEF_BARRIER ? 0.68f : 0.55f;   // fewer behind a barrier reef
            if (patch > patchMin) {
                double pr = -3 + 4.5 * smoothstep(0.78, 0.92, patch);
                if (pr > base) { base = pr; patchReef = true; }
            }
        }

        // Passes: deep channels through the reef into the lagoon, mostly on the leeward side.
        double pass = 0;
        int nPass = 1 + (int) (unit(mix(h + 23)) * 3);
        for (int p = 0; p < nPass; p++) {
            double pa = windDir + Math.PI + (unit(mix(h + 20 + p)) - 0.5) * 2.6 - axis;   // in the stretched frame
            double da = Math.abs(Math.atan2(Math.sin(ang - pa), Math.cos(ang - pa))) * re;
            double pw = (0.12 + 0.2 * unit(mix(h + 30 + p))) * KM;
            pass = Math.max(pass, Math.exp(-sq(da / pw)));
        }
        if (a < 1.4) base += (Math.min(base, -16) - base) * pass * (1 - smoothstep(1.0, 1.4, a));

        // Motus (islets): sand and rubble heaped up on the reef flat, long along the reef and narrow across it,
        // nearly continuous on the windward side and scattered on the leeward side. They sit back from the reef
        // edge, are highest on their seaward side (the storm ridge) and trail sand spits into the lagoon. Fewer on a
        // barrier reef.
        float spit = motus.GetNoise(kx * 1.7f + 13, kz * 1.7f - 29);
        float m = motus.GetNoise(ax * 0.55f, az * 0.55f, (float) (t / KM * 1.6) + salt);
        double field = m + 0.5 * windward - 0.3 + 0.2 * broad
                - 0.8 * (1 - smoothstep(0.08, 0.3, a))                          // bare reef crest at the edge
                - 1.2 * smoothstep(0.95, 1.4, a + 0.3 * spit)                   // shore on the lagoon side
                - 2.0 * pass
                - (variant == REEF_BARRIER ? 0.25 : 0);
        if (field > 0) {
            double top = 1 + 6 * Math.min(1, field / 0.45) * (0.55 + 0.45 * smoothstep(1.2, 0.2, a));
            double land = base + (top - base) * smoothstep(0, 0.12, field);
            base = Math.max(base, land);
        }
        double res = smoothMax(v, base, 60);
        if (v > base + 1) {
            ctx.tag = TAG_NONE;                               // the island itself rises here
        } else {
            ctx.tag = (lagoon && !patchReef && pass < 0.5) ? TAG_LAGOON : TAG_REEF;
        }
        return res;
    }

    /**
     * A raised coral island (makatea: Niue, Nauru, Henderson Island): a whole atoll lifted {@code lift} metres out
     * of the sea. The old reef rim is now a ring of limestone standing above sea cliffs, sometimes with a lower
     * terrace (an older shoreline) halfway down, and a new reef bench at sea level in front. Inside the rim, the old
     * lagoon floor is a dry, sunken basin. The basin floor tilts toward the old main pass, which is now a dry gorge
     * cut through the rim to the sea, so rain drains out through it instead of filling the basin as a lake.
     */
    private double raisedReef(double v, double t, double re, double rim, double flatW, double windward, double ang,
                              double axis, double stretch, double windDir, double dx, double dz, long h, double lift,
                              Ctx ctx) {
        double rimTop = lift + 2 + 4 * windward;              // the old storm ridge and reef flat
        // The old main pass, in the stretched frame (as for an atoll) and as a direction in the world.
        double pa = windDir + Math.PI + (unit(mix(h + 20)) - 0.5) * 2.6 - axis;
        double ca = Math.cos(axis), sa = Math.sin(axis);
        double pux = Math.cos(pa) * stretch, puz = Math.sin(pa);
        double gx = pux * ca - puz * sa, gz = pux * sa + puz * ca, gl = Math.hypot(gx, gz);
        gx /= gl; gz /= gl;
        // Basin floor: below the rim by about half the uplift, tilted down toward the gorge.
        double dIn = (0.35 + 0.25 * unit(mix(h + 46))) * lift, tilt = 0.25 * lift;
        double rimW = rim * Math.max(1, stretch);
        double plane = lift - dIn - tilt * (dx * gx + dz * gz) / rimW;
        double innerW = 0.35 * KM, tIn = flatW + innerW;
        double cliffZone;                                     // km from the reef edge to the foot of the cliffs
        double prof;
        boolean rock;
        if (t < 0) {
            double out = -t / KM;
            boolean terraced = unit(mix(h + 45)) < 0.4;
            double cliffW = 0.07, benchW = 0.12 + 0.12 * unit(mix(h + 47));
            if (terraced) {
                // Upper cliff, a terrace (an older shoreline) at ~45% of the height, lower cliff.
                double hT = 0.45 * rimTop, tw = 0.12 + 0.08 * unit(mix(h + 48));
                cliffZone = 0.05 + tw + cliffW;
                if (out < 0.05) prof = rimTop + (hT - rimTop) * smoothstep(0, 0.05, out);
                else if (out < 0.05 + tw) prof = hT;
                else prof = hT + (1.5 - hT) * smoothstep(0.05 + tw, cliffZone, out);
            } else {
                cliffZone = cliffW;
                prof = rimTop + (1.5 - rimTop) * smoothstep(0, cliffW, out);
            }
            rock = out < cliffZone;
            if (out >= cliffZone) {
                // A new reef bench at sea level, then the old fore-reef wall and talus.
                double ow = out - cliffZone - benchW;
                if (ow < 0) prof = -1.2;
                else prof = ow < 0.66 ? -1.5 - ow * 900 : -600 + (v + 600) * (1 - Math.exp(-(ow - 0.66) / 2.5));
            }
            // The gorge meets the bench.
            double gorge = gorge(ang, re, h);
            double chF = 1.0 - 2.2 * smoothstep(0, cliffZone, out);
            if (prof > chF) prof -= (prof - chF) * gorge;
        } else {
            if (t <= flatW) {
                prof = rimTop - 1.5 * smoothstep(0.5, 1, t / flatW);
                rock = true;
            } else {
                prof = (rimTop - 1.5) + (plane - (rimTop - 1.5)) * smoothstep(flatW, tIn, t);
                rock = t < flatW + 0.6 * innerW;
            }
            // The old pass, now a dry gorge: its floor runs from the basin floor down to the sea at the rim's edge.
            // It starts a little inside the basin, a couple of metres below the basin's lowest point, so the basin
            // always spills into it.
            double gorge = gorge(ang, re, h);
            double chIn = lift - dIn - tilt - 2;
            double chF = t < tIn ? 1.0 + (chIn - 1.0) * (t / tIn)
                    : chIn + (plane - chIn) * smoothstep(tIn, tIn + 0.5 * KM, t);
            if (prof > chF) prof -= (prof - chF) * gorge;
            if (gorge > 0.3 && t < tIn) rock = true;
        }
        double res = smoothMax(v, prof, 60);
        if (res > 0) ctx.tag = rock ? TAG_LIMESTONE : TAG_LIMESTONE_SOIL;
        else if (res > -40) ctx.tag = t < 0 && prof > -2 ? TAG_REEF : TAG_LIMESTONE;
        return res;
    }

    /** Closeness (0..1) to the axis of a raised coral island's gorge (its old main pass). */
    private static double gorge(double ang, double re, long h) {
        double windDir = (unit(mix(h + 14)) - 0.5) * 0.8;
        double axis = unit(mix(h + 12)) * Math.PI;
        double pa = windDir + Math.PI + (unit(mix(h + 20)) - 0.5) * 2.6 - axis;
        double da = Math.abs(Math.atan2(Math.sin(ang - pa), Math.cos(ang - pa))) * re;
        double pw = (0.12 + 0.2 * unit(mix(h + 30))) * KM * 1.4;
        return Math.exp(-sq(da / pw));
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
