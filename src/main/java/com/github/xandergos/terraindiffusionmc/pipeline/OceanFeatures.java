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
        /** Distance (native pixels) to a rift sea's young spreading axis (round 5), or {@code Double.MAX_VALUE}. */
        default double riftAxis(double i, double j) { return Double.MAX_VALUE; }
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
    /** Floor of a submarine canyon (no special blocks; lets the finder see canyons). */
    public static final byte TAG_CANYON = 8;
    /** Floor of a deep-sea fan channel (no special blocks; lets the finder see channels). */
    public static final byte TAG_FAN_CHANNEL = 9;
    /** Mud volcano: mud on the floor. */
    public static final byte TAG_MUD = 10;
    /** Sand wave field on the shelf (no special blocks; lets the finder see them). */
    public static final byte TAG_SAND_WAVES = 11;
    /** Pockmark (no special blocks; lets the finder see them). */
    public static final byte TAG_POCKMARK = 12;
    /** Salt dome province on the slope (no special blocks; lets the finder see it). */
    public static final byte TAG_SALT = 13;
    /** Pale carbonate ooze on open-ocean floors above ~4 km: calcite and diorite. */
    public static final byte TAG_OOZE = 14;
    /** Red-brown clay on the deepest open-ocean floors: terracotta. */
    public static final byte TAG_RED_CLAY = 15;
    /** Young crust near a mid-ocean ridge axis: pillow lava (basalt and blackstone). */
    public static final byte TAG_PILLOW = 16;
    /** Rim and walls of a tuff ring or maar on a young island's coast: tuff, with some smooth basalt. */
    public static final byte TAG_TUFF = 17;
    // 18 and 19 were lava deltas (removed Oct 21; they return with the v1.4 volcano landforms): don't reuse them.
    /** Floor of a glacial trough across a cold shelf (no special blocks; lets the finder see troughs). */
    public static final byte TAG_TROUGH = 20;
    /** Strandflat: a low rock plain on a cold coast, strewn with skerries: bare stone, gravel and moss. */
    public static final byte TAG_STRANDFLAT = 21;
    /** Land of a mushroom island (biomes plan v1.3): the tile turns it into mushroom fields. */
    public static final byte TAG_MUSHROOM = 22;
    /**
     * Mushroom islands: this share of hotspot islands (past the chain's head) and island-arc islands whose sea is at
     * least {@link #MUSHROOM_MIN_TEMP} °C (never cold or frozen seas). They are already isolated (islands keep clear of
     * continents, shelves and archipelagos). Plan: about 1 in 8.
     */
    static final double MUSHROOM_CHANCE = 0.125;
    static final float MUSHROOM_MIN_TEMP = 5f;

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
    /** How far across the arc (native pixels) its volcanoes reach, debris fields below landslide scars included. */
    private static final double ARC_REACH = 50 * KM;

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
    /**
     * Whether the diffusion model draws the big islands (hotspot islands, island-arc islands, forearc islands) from
     * {@link #islandGuide}: then their cones are left out here and only the reef rings around them stay procedural.
     */
    volatile boolean modelIslands;
    private final FastNoiseLite warpLargeX, warpLargeZ, warpSmallX, warpSmallZ;
    private final FastNoiseLite hills, trenchStrength, coastShape, gullies, motus;
    private final FastNoiseLite reefZone, reefPatch, bankZone, bankLevel, shoals, coastFine;
    private final FastNoiseLite canyonZone, canyonNoise, tributaryNoise, fanNoise, meander;
    private final FastNoiseLite shelfZone, currentDir, sandWaves, drowned, saltLumps;
    private final FastNoiseLite islandCoast, islandCoastWarpX, islandCoastWarpZ;
    private final FastNoiseLite troughNoise, troughZone;
    private final FastNoiseLite strandZone, strandPatch, strandRough, skerries, skerryCluster;

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
        canyonZone = noise(s + 116, 1f / 300f);
        canyonNoise = single(s + 117);
        tributaryNoise = single(s + 120);
        shelfZone = noise(s + 121, 1f / 120f);
        currentDir = single(s + 122);
        sandWaves = single(s + 123);
        drowned = single(s + 124);
        saltLumps = noise(s + 125, 1f);
        fanNoise = single(s + 118);
        meander = single(s + 119);
        coastFine.SetFractalOctaves(3);
        coastFine.SetFractalGain(0.5f);
        islandCoast = noise(s + 126, 1f / 2.2f);
        islandCoast.SetFractalOctaves(4);
        islandCoastWarpX = noise(s + 127, 1f / 1.5f);
        islandCoastWarpZ = noise(s + 128, 1f / 1.5f);
        troughNoise = single(s + 129);
        troughZone = noise(s + 130, 1f / 350f);
        strandZone = noise(s + 131, 1f / 300f);
        strandPatch = noise(s + 132, 1f / 40f);
        strandRough = noise(s + 133, 1f / STRAND_ROUGH_KM);
        skerries = noise(s + 134, 1f / SKERRY_KM);
        skerries.SetFractalGain(0.35f);
        skerryCluster = noise(s + 135, 1f / SKERRY_CLUSTER_KM);
    }

    /** Single-octave noise at frequency 1 (callers scale their coordinates). */
    private static FastNoiseLite single(int seed) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(1f);
        return n;
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
        // A tile inside a model-drawn island has no sea but still needs the island's crater.
        if (!anyOcean && !modelIslands) return;

        // Every pixel is independent, so big windows (river regions, coast and iceberg plans: up to ~250,000 pixels) are
        // shared out over the cores in bands of rows, each with its own context.
        if ((long) H * W < PARALLEL_MIN_PX) {
            applyRows(elev, i1, j1, W, coarse, tags, 0, H);
        } else {
            int bands = (H + PARALLEL_BAND - 1) / PARALLEL_BAND;
            java.util.stream.IntStream.range(0, bands).parallel().forEach(b ->
                    applyRows(elev, i1, j1, W, coarse, tags, b * PARALLEL_BAND, Math.min(H, (b + 1) * PARALLEL_BAND)));
        }
    }

    /** Windows at least this big (pixels) are applied in parallel, in bands of this many rows. */
    private static final int PARALLEL_MIN_PX = 16384, PARALLEL_BAND = 8;

    /** {@link #apply} for rows [r0, r1) of the window. */
    private void applyRows(float[] elev, int i1, int j1, int W, CoarseField coarse, byte[] tags, int r0, int r1) {
        Ctx ctx = new Ctx();
        for (int r = r0; r < r1; r++) {
            for (int c = 0; c < W; c++) {
                int k = r * W + c;
                float raw = elev[k];
                byte coastTag = TAG_NONE;
                boolean mushroomShore = false;
                if (modelIslands && raw > -ISLAND_FALLBACK_DEEP && coarse.elevation(i1 + r, j1 + c) < -500f) {
                    // Model-drawn islands: a summit crater and a ragged coast (both too small for the model to draw),
                    // tuff rings on young islands' coasts, and on land their island rock (forearc
                    // islands' limestone).
                    islandGuide(i1 + r, j1 + c, coarse, ctx);
                    raw = (float) islandFallback(raw, ctx);
                    mushroomShore = ctx.surfaceTag == TAG_MUSHROOM && ctx.guideTop > -30;
                    if (ctx.guideTop > -1500 && raw > -ISLAND_EDIT_DEEP) {
                        raw -= (float) ctx.guideCrater;
                        if (raw < ISLAND_COAST_HIGH) raw = (float) islandCoast(raw, i1 + r, j1 + c, smoothstep(-1500, -500, ctx.guideTop));
                        if (hasCoastEdit(ctx)) {
                            raw = (float) coastEdit(raw, ctx);
                            coastTag = ctx.coastTag;
                        }
                        elev[k] = raw;
                        if (raw >= 0f && tags != null && ctx.surfaceTag != TAG_NONE && ctx.guideTop > -30) tags[k] = ctx.surfaceTag;
                        if (raw >= 0f && tags != null && coastTag != TAG_NONE) tags[k] = coastTag;
                    }
                    elev[k] = raw;
                }
                // A mushroom island is mycelium down to the water, as in vanilla: its land and the shallows around it
                // (so the blocks along the shore, which may take a sea pixel's tag, are included) carry the tag.
                if (mushroomShore && tags != null && raw > -30f) {
                    tags[k] = TAG_MUSHROOM;
                    if (raw >= 0f) continue;
                }
                if (raw >= 0f) continue;
                ctx.tag = TAG_NONE;
                ctx.reefOp = false;
                double v = raw < -30f ? shape(raw, i1 + r, j1 + c, coarse, ctx) : raw;
                // Reefs and banks in warm shallow water, away from the atolls and barrier reefs drawn above.
                if (!ctx.reefOp && v < 0 && v > -SHALLOW_MAX_M && coastTag == TAG_NONE) v = shallowSeas(v, i1 + r, j1 + c, coarse, ctx);
                elev[k] = (float) v;
                if (tags != null) tags[k] = mushroomShore && v > -30 ? TAG_MUSHROOM : coastTag != TAG_NONE ? coastTag : ctx.tag;
            }
        }
    }

    /** Model ground (m) deeper than this is never part of a model-drawn island, not even a failed one. */
    private static final float ISLAND_FALLBACK_DEEP = 6000f;
    /**
     * Where the model failed to draw a guided island (it sometimes leaves a low or sinking island under the sea: a
     * flooded caldera vanished on Oct 20, a mushroom island on Oct 25), the island is put back from its guide: below
     * {@link #FALLBACK_LOW_M} the guide's own shape, above it a quarter of the guide's height, so a failed island comes
     * out as a low, gentle island rather than one of the old tall cones.
     */
    private static final double FALLBACK_LOW_M = 20, FALLBACK_SQUASH = 0.25;

    /**
     * {@code raw} raised toward the fallback island where the model reached less than about a third of the guide's
     * relief (fully below a fifth), on guide islands standing over 300 m above their floor (fully over 800 m, so
     * there is no ring at the guide's edge); unchanged elsewhere and wherever the model drew the island itself. Never
     * lowers. Pointwise in the model's ground and the guide, so tiles agree.
     */
    private static double islandFallback(double raw, Ctx ctx) {
        double g = ctx.guideTop, c = ctx.guideC;
        if (g == Double.NEGATIVE_INFINITY || c <= 300) return raw;
        double target = g <= FALLBACK_LOW_M ? g : FALLBACK_LOW_M + FALLBACK_SQUASH * (g - FALLBACK_LOW_M);
        if (target <= raw) return raw;
        double missing = (g - raw) / c;   // share of the island's relief the model left out here
        double w = smoothstep(0.65, 0.8, missing) * smoothstep(300, 800, c);
        return raw + w * (target - raw);
    }

    // =========================================================================
    // Round 4: strandflats
    // =========================================================================

    /** Coarse temperatures (deg C) below which strandflats form (fully below the first). */
    private static final float STRAND_TEMP_FULL = 2f, STRAND_TEMP = 4.5f;
    /** Share of cold coasts in strandflat zones (noise level; higher = rarer), and of those, the patches. */
    private static final float STRAND_ZONE = 0.15f, STRAND_PATCH = -0.15f;
    /**
     * Scale (km) of the plain's knolls and hollows (two octaves: the smallest are ~7 pixels across, so the waterline
     * wanders in coves and points rather than breaking into single-pixel islets and pools).
     */
    private static final float STRAND_ROUGH_KM = 0.4f;
    /** Skerries: the knob noise's scale (km), the scale of their clusters (km), and the knob threshold outside and
     *  inside a cluster (noise level; higher = fewer, smaller). */
    private static final float SKERRY_KM = 0.75f, SKERRY_CLUSTER_KM = 3.5f;
    private static final double SKERRY_THRESHOLD = 0.5, SKERRY_THRESHOLD_CLUSTER = 0.22, SKERRY_EDGE = 0.18;

    /**
     * <b>Strandflats</b> (the coast of Norway): on cold, once-glaciated coasts, a broad low rock plain cut across the
     * shore by frost and waves, half drowned, strewn with thousands of skerries, ending at the foot of steep mountains.
     * A remap of height alone, in place, before the other ocean features (so the hydrology and biomes follow): land
     * from the shore up to ~60 m and sea floor down to ~30 m are flattened toward a plain from -10 to +15 m, the land
     * behind steepens to meet the old ground by 100 m, and the floor in front drops back to its own depth by 60 m. The
     * remap is monotone (no slope reverses, so no new hollows); then the plain gets a little roughness and skerry knobs
     * rise from its drowned part. Patchy: zones a few hundred km across on cold coasts, broken into stretches inside.
     *
     * @return per-pixel 1 where the result is strandflat rock (for the surface tag), or null if none
     */
    public byte[] strandflats(float[] elev, int i1, int j1, int H, int W, CoarseField coarse) {
        byte[] out = null;
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int k = r * W + c;
                float h = elev[k];
                if (h < -62f || h > 102f) continue;
                int pi = i1 + r, pj = j1 + c;
                float temp = coarse.temperature(pi, pj);
                if (temp >= STRAND_TEMP) continue;
                float ce = coarse.elevation(pi, pj);
                double coastW = smoothstep(-700, -150, ce) * (1 - smoothstep(200, 700, ce));
                if (coastW <= 0) continue;
                float kx = (float) (pj / KM), kz = (float) (pi / KM);
                double z = (1 - smoothstep(STRAND_TEMP_FULL, STRAND_TEMP, temp)) * coastW
                        * smoothstep(STRAND_ZONE, STRAND_ZONE + 0.2, strandZone.GetNoise(kx, kz))
                        * smoothstep(STRAND_PATCH, STRAND_PATCH + 0.3, strandPatch.GetNoise(kx, kz));
                if (z <= 0) continue;
                double f = strandRemap(h);
                double v = h + (f - h) * z;
                // A little roughness on the plain (ice-scoured knolls and hollows, metres high).
                double plain = smoothstep(-12, -6, v) * (1 - smoothstep(10, 16, v));
                v += 2.5 * strandRough.GetNoise(kx, kz) * plain * z;
                // Skerries: rounded knobs of ice-smoothed rock rising from the drowned plain, gathered in clusters
                // (thick swarms of islets with open water between), from rocks a few pixels across to islets of
                // several hundred metres; a few just break the surface.
                double inSea = smoothstep(-14, -9, v) * (1 - smoothstep(-2, 0, v));
                if (inSea > 0) {
                    double n = skerries.GetNoise(kx, kz);
                    double cl = smoothstep(-0.1, 0.4, skerryCluster.GetNoise(kx, kz));
                    double thr = SKERRY_THRESHOLD + (SKERRY_THRESHOLD_CLUSTER - SKERRY_THRESHOLD) * cl;
                    double knob = smoothstep(thr, thr + SKERRY_EDGE, n) * inSea * z;
                    double top = 1 + 6 * smoothstep(thr + 0.1, thr + 0.45, n);   // islets mostly 1-7 m high
                    if (top > v) v += (top - v) * knob;
                }
                elev[k] = (float) v;
                if (z > 0.4 && v > -3 && v < 18) {
                    if (out == null) out = new byte[H * W];
                    out[k] = 1;
                }
            }
        }
        return out;
    }

    /** The strandflat height remap (metres): monotone, identity outside -60..100 m. */
    private static double strandRemap(double h) {
        if (h <= -60 || h >= 100) return h;
        if (h < -30) return -60 + (h + 60) * (50.0 / 30);        // the floor rises steeply to the plain's edge
        if (h < 60) return -10 + (h + 30) * (25.0 / 90);         // the plain: -30..60 m squeezed into -10..15 m
        return 15 + (h - 60) * (85.0 / 40);                       // the land behind rises steeply to the old ground
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
        /** The volcanoes' combined surface here as an absolute elevation (metres), or -infinity if none. */
        double absTop;
        /** This pixel in warped space. */
        double qx, qz;
        /** How much of the deep-floor relief applies here (0 shallower than 1500 m, 1 deeper than 3000 m). */
        double deepFade;
        /** Abyssal plain strength (0..1) and closeness to a continent (0..1) at this pixel, from {@link #margin}. */
        double plain, continent;
        /** Distance (native pixels) to the nearest mid-ocean ridge axis, or MAX_VALUE. */
        double ridgeAxis;
        /** Surface tag of the island landform contributing {@code surfaceC} metres here (forearc islands). */
        byte surfaceTag;
        double surfaceC;
        /** Surface tag for this pixel, and whether a reef ring (atoll, barrier reef, raised reef) covers it. */
        byte tag;
        boolean reefOp;
        /** Model-drawn islands' combined surface (absolute metres) here, or -infinity; see {@link #islandGuide}. */
        double guideTop;
        /** The tallest model-drawn island's height above its own floor here (metres), for fading its edge. */
        double guideC;
        /** How far (metres) a model-drawn island's summit crater lowers the ground here (negative on its rim). */
        double guideCrater;
        /** Only the island guide is wanted: skip everything else in {@link #shape}. */
        boolean guideOnly;
        /** The island about to be added is a mushroom island: tag its land {@link #TAG_MUSHROOM}. */
        boolean mushroomNext;
        // Coastal cones on young islands (round 4), recorded by the island code; see #coastEdit.
        /** Distance from the nearest tuff ring's centre over its radius (wobbly), or infinity if none is near. */
        double tuffU;
        /** That ring's radius (metres), rim height (m), floor (absolute m), gap half-width (radians), kind. */
        double tuffR, tuffRim, tuffFloor, tuffGapW;
        int tuffKind;
        /** Angle (radians) from that ring's seaward direction to this pixel, seen from the ring's centre. */
        double tuffDa;
        /** Surface tag left by {@link #coastEdit}. */
        byte coastTag;
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
        double x = pj, z = pi;   // native pixels: x east, z south
        // ---- Continental margins: canyons, fans and channels, abyssal plains (round 3) ----
        ctx.plain = 0;
        ctx.continent = 0;
        ctx.guideTop = Double.NEGATIVE_INFINITY;
        ctx.guideC = 0;
        ctx.guideCrater = 0;
        ctx.tuffU = Double.POSITIVE_INFINITY;
        if (!ctx.guideOnly) {
            if (raw < -SHELF_TOP_M && raw > -SHELF_BASE_M) raw = shelf(raw, x, z, coarse, ctx);
            if (raw < -CANYON_TOP_M) raw = margin(raw, x, z, coarse, ctx);
        }
        double depth = -raw;
        double deepFade = smoothstep(1500, 3000, depth);
        ctx.deepFade = deepFade;

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
        ctx.absTop = Double.NEGATIVE_INFINITY;
        ctx.ridgeAxis = Double.MAX_VALUE;
        ctx.surfaceTag = TAG_NONE;
        ctx.surfaceC = 0;
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
            double beyond = len(bx - sx[iZ], bz - sz[iZ]) - len(bx - sx[iX], bz - sz[iX]);
            if (beyond < -JUNCTION_TAPER) continue;
            int type = boundaryType(pair);
            if (type == BOUNDARY_RIDGE) {
                // Ridges run at full height right up to the junction and join there.
                double w = smoothstep(-JUNCTION_TAPER, 0, beyond);
                if (deepFade > 0 && w > 0) {
                    ridgeUp = Math.max(ridgeUp, ridge(ds, along, pair) * w);
                    if (w > 0.5) ctx.ridgeAxis = Math.min(ctx.ridgeAxis, ridgeAxisDistance(ds, along, pair));
                }
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
                    if (Math.abs(xo - ARC_DISTANCE) < ARC_REACH) {
                        arcVolcanoes(x, z, xo, along, pair, subHi, mx, mz, nx, nz, tx, tz, w, coarse, ctx);
                    }
                    // Forearc wedge between the trench and the arc; back-arc basin behind the arc.
                    if (xo > 0 && xo < FOREARC_OUTER) {
                        double deep = smoothstep(800, 2500, depth);
                        trenchDown += forearcWedge(xo, along, pair) * strength * deep;
                        if (Math.abs(xo - FOREARC_AT) < 25 * KM) {
                            forearcIslands(x, z, xo, along, pair, subHi, mx, mz, nx, nz, tx, tz, w, coarse, ctx);
                        }
                    }
                }
                // Back-arc basin: follows the trench's strength averaged along the arc, so it is not gated by it here.
                double xob = subHi ? -ds : ds;
                if (xob > BACKARC_AT - 90 * KM && xob < BACKARC_AT + 90 * KM && hasBackArc(pair)) {
                    double bs = backArcStrength(along, pair) * w;
                    if (bs > 0) {
                        trenchDown += backArc(xob - BACKARC_AT, along, pair) * bs * deepFade;
                        if (bs > 0.4) ctx.ridgeAxis = Math.min(ctx.ridgeAxis, Math.abs(xob - BACKARC_AT - backArcOffset(along, pair)));
                    }
                }
            }
        }
        // Big stamps on the deep floor: oceanic plateaus.
        if (!ctx.guideOnly) plateaus(x, z, coarse, ctx);
        double tectonic = trenchDown;
        if (ridgeUp > Double.NEGATIVE_INFINITY) tectonic += ridgeUp * deepFade;
        if (deepFade > 0) {
            // Abyssal plains have buried the abyssal hills.
            tectonic += hills.GetNoise((float) (x / KM / 8), (float) (z / KM / 8)) * 90 * deepFade * (1 - 0.9 * ctx.plain);
        }

        // ---- Volcanoes ----
        hotspots(x, z, coarse, ctx);
        if (ctx.guideOnly) return ctx.guideTop;
        seamounts(x, z, coarse, ctx);
        // Swell, moat, arch, debris fields and aseismic ridges around the hotspot chains.
        if (deepFade > 0) hotspotFlex(x, z, raw, deepFade, coarse, ctx);

        // Sea-floor relief (abyssal hills, ridges, swells) belongs to the deep floor, so it fades out up a volcano's
        // flanks (on an island it left hundreds of closed hollows that filled as lakes). Troughs are added and faded;
        // rises are not added onto a volcano at all: the volcano stands on them (smooth max), since adding a ridge or
        // a swell to a flank pushed the flank back up above the sea partway down, in a ring around the island.
        double relief = tectonic + ctx.flex;
        double floorOnly = raw + relief * smoothstep(-200, -1500, raw);
        double surf = ctx.absTop;
        if (ctx.ridge > 0) {
            double rr = raw + ctx.ridge;   // the aseismic ridge's crest (absolute)
            surf = surf == Double.NEGATIVE_INFINITY ? rr : smoothMax(surf, rr, Math.min(150, 0.5 * ctx.ridge));
        }
        if (surf > Double.NEGATIVE_INFINITY) {
            // Volcanoes rise out of the model's terrain (a smooth max), so they never stack onto shallow ground.
            double cone = surf + Math.min(0, relief) * smoothstep(-200, -1500, surf);
            // Fade in over the volcanoes' first 200 m, so where the model's floor lies below the volcano's own floor
            // the edge of its footprint is not a step.
            cone = floorOnly + (cone - floorOnly) * smoothstep(0, 200, Math.max(ctx.top1, ctx.ridge));
            total = smoothMax(cone, floorOnly, Math.min(150, 0.3 * Math.max(ctx.top1, ctx.ridge)));
            // Canyons, fan channels and the other margin floors are buried where a volcano stands on them.
            if (cone > floorOnly + 30 && ctx.tag >= TAG_CANYON && ctx.tag <= TAG_SALT) ctx.tag = TAG_NONE;
        } else {
            total = floorOnly;
        }
        // Tuff rings on the procedural islands' coasts (model-drawn islands get them in apply()).
        byte coastTag = TAG_NONE;
        if (!modelIslands && hasCoastEdit(ctx)) {
            total = coastEdit(total, ctx);
            coastTag = ctx.coastTag;
        }
        for (int o = 0; o < ctx.nOps; o++) {
            total = applyOp(total, o, x, z, ctx);
        }
        if (coastTag != TAG_NONE) ctx.tag = coastTag;
        // Island rock from the landform that stands highest here (forearc islands).
        if (ctx.surfaceTag != TAG_NONE && total > -30 && ctx.surfaceC >= ctx.top1 - 1 && !ctx.reefOp) ctx.tag = ctx.surfaceTag;
        // Sea floor materials on open-ocean floors that nothing else has claimed (a rift sea's axis counts as a ridge).
        if (ctx.tag == TAG_NONE && total < -900) {
            ctx.ridgeAxis = Math.min(ctx.ridgeAxis, coarse.riftAxis(z, x));
            ctx.tag = floorMaterial(total, x, z, ctx);
        }
        return Math.max(MAX_DEPTH, total);
    }

    // =========================================================================
    // Continental margins (round 3)
    // =========================================================================

    /** Canyons cut the slope from this depth (metres) down to {@link #CANYON_BASE_M}. */
    private static final double CANYON_TOP_M = 120, CANYON_BASE_M = 3200;
    /** Deepest canyon cut (metres, mid-slope), and spacing (km) between canyons across the slope. */
    private static final double CANYON_DEPTH = 650, CANYON_SPACING_KM = 9;
    /** Share of continental margins with canyons (noise level; lower = more). */
    private static final float CANYON_ZONE = -0.05f;
    /** Fan channels: depth range (m), cut, levee height (m), and spacing (km) across the fan. */
    private static final double FAN_TOP_M = 2600, FAN_BASE_M = 4900, CHANNEL_DEPTH = 330, LEVEE_HEIGHT = 140, CHANNEL_SPACING_KM = 24;
    /** Share of continental rises with fan channels (noise level; lower = more). */
    private static final float FAN_ZONE = -0.15f;
    /** Distance (native pixels) over which the coarse map's slope is measured. */
    private static final double SLOPE_SPAN = 8 * KM;

    /**
     * Continental margin features at native pixel (x, z) for raw elevation {@code raw} (below -120 m); returns the new
     * raw elevation and sets {@code ctx.plain} and {@code ctx.continent}.
     * <ul>
     *   <li><b>Submarine canyons</b> cut the continental slope from the shelf break down to its foot: V-shaped valleys
     *       up to ~650 m deep, ~9 km apart, running straight down the slope and wandering a little, on about half the
     *       margins.</li>
     *   <li><b>Deep-sea fans</b>: below the canyons, on the continental rise, meandering channels with raised banks
     *       (levees) wind on down toward the abyss.</li>
     *   <li><b>Abyssal plains</b>: within ~80 km of a continent, deep floors are buried flat by sediment, and the
     *       abyssal hills fade out (in {@link #shape}).</li>
     * </ul>
     * "Down the slope" comes from the coarse map's slope measured over 16 km (continuous everywhere, so nothing jumps
     * between pixels). Valleys run along it by blending four fixed orientations (rotating world coordinates by a
     * varying angle shears the noise into streaks far from the origin; see {@code ArchipelagoShaper}).
     */
    double margin(double raw, double x, double z, CoarseField coarse, Ctx ctx) {
        double depth = -raw;
        float ce = coarse.elevation(z, x);
        // Closeness to a continent: any shelf or land within 40 or 80 km.
        double cont = smoothstep(-1500, -200, ce);
        if (depth > 800 && cont < 1) {
            for (int k = 0; k < 8 && cont < 1; k++) {
                double a = k * Math.PI / 4, ca = Math.cos(a), sa = Math.sin(a);
                cont = Math.max(cont, smoothstep(-1500, -200, coarse.elevation(z + sa * 40 * KM, x + ca * 40 * KM)));
                cont = Math.max(cont, 0.7 * smoothstep(-1500, -200, coarse.elevation(z + sa * 80 * KM, x + ca * 80 * KM)));
            }
        }
        ctx.continent = cont;

        // Abyssal plain: sediment buries the model's own small relief, leaving the smooth coarse floor.
        double plain = cont * smoothstep(3300, 4300, depth);
        if (plain > 0) {
            raw += (Math.min(ce, -3300) - raw) * 0.85 * plain;
            depth = -raw;
        }
        ctx.plain = plain;

        if (depth > 300 && depth < 3400) {
            raw = saltProvince(raw, x, z, cont, ctx);
            raw = mudVolcanoes(raw, x, z, ctx);
            depth = -raw;
        }

        boolean inCanyons = depth < CANYON_BASE_M + 400, inFans = depth > FAN_TOP_M - 200 && depth < FAN_BASE_M + 300;
        if (!inCanyons && !inFans) return raw;
        float kx = (float) (x / KM), kz = (float) (z / KM);
        // Canyons and fans come and go along the margins in their own broad zones (fans are wider, so they get a
        // broader zone that does not stop partway down the rise).
        double canyonW = inCanyons ? smoothstep(CANYON_ZONE, CANYON_ZONE + 0.25, canyonZone.GetNoise(kx, kz)) : 0;
        double fanW = inFans ? cont * smoothstep(FAN_ZONE, FAN_ZONE + 0.25, canyonZone.GetNoise(kx * 0.4f + 300, kz * 0.4f)) : 0;
        if (canyonW <= 0 && fanW <= 0) return raw;
        double gx = coarse.elevation(z, x + SLOPE_SPAN) - coarse.elevation(z, x - SLOPE_SPAN);
        double gz = coarse.elevation(z + SLOPE_SPAN, x) - coarse.elevation(z - SLOPE_SPAN, x);
        double g = len(gx, gz) / (2 * SLOPE_SPAN);                    // metres per pixel
        if (g < 0.05) return raw;
        double down = Math.atan2(-gz, -gx);                                     // downslope direction

        if (inCanyons) {
            double band = smoothstep(CANYON_TOP_M, CANYON_TOP_M + 150, depth) * (1 - smoothstep(CANYON_BASE_M - 600, CANYON_BASE_M + 400, depth));
            double steep = smoothstep(0.25, 0.9, g);
            double w = canyonW * band * steep;
            if (w > 0) {
                // Deepest mid-slope; shallow near the shelf break, where every metre is a big step in blocks.
                double cutMax = 60 + (CANYON_DEPTH - 60) * smoothstep(250, 1200, depth);
                // Distance (km) to the canyon's axis, from the noise and its gradient, so canyons keep their width.
                double s = lineDistance(canyonNoise, kx, kz, down, CANYON_SPACING_KM, 140, 0.7) / (0.6 + 0.9 * smoothstep(250, 1500, depth));
                double cut = s < 1 ? cutMax * Math.pow(1 - s, 1.3) : 0;
                // Tributaries branch from the canyon heads on the upper slope, closer together and shallower.
                double upper = 1 - smoothstep(900, 1800, depth);
                if (upper > 0) {
                    double st = lineDistance(tributaryNoise, kx, kz, down, 3.2, 30, 0.5) / 0.45;
                    if (st < 1) cut = Math.max(cut, 0.45 * cutMax * upper * Math.pow(1 - st, 1.3));
                }
                if (cut > 0) {
                    raw -= cut * w;
                    if (cut * w > 0.35 * cutMax) ctx.tag = TAG_CANYON;
                }
            }
        }
        if (inFans) {
            double band = smoothstep(FAN_TOP_M - 200, FAN_TOP_M + 400, depth) * (1 - smoothstep(FAN_BASE_M - 700, FAN_BASE_M + 300, depth));
            double w = fanW * band * smoothstep(0.05, 0.25, g);
            if (w > 0) {
                double s = lineDistance(fanNoise, kx, kz, down, CHANNEL_SPACING_KM, 160, 3.2) / 0.45;
                if (s < 1) {
                    raw -= CHANNEL_DEPTH * (1 - s * s) * w;
                    if (w > 0.5) ctx.tag = TAG_FAN_CHANNEL;
                } else if (s < 3) {
                    raw += LEVEE_HEIGHT * Math.sin(Math.PI * (s - 1) / 2) * w;
                }
            }
        }
        return raw;
    }

    // ---- Shelf ----

    /** Shelf texture is added between these depths (metres). */
    private static final double SHELF_TOP_M = 22, SHELF_BASE_M = 215;
    /** Sand waves: crest height (m either way), spacing (km), and share of shelves with fields (noise level). */
    private static final double SAND_WAVE_M = 6, SAND_WAVE_KM = 0.9;
    private static final float SAND_WAVE_ZONE = 0.15f;
    /** Drowned river channels: depth (m), spacing (km). */
    private static final double DROWNED_DEPTH = 14, DROWNED_SPACING_KM = 22;
    /** The ice-age shoreline: a step this high (m) at this depth (m), on shelves where {@link #PALEO_ZONE} allows. */
    private static final double PALEO_STEP_M = 9, PALEO_DEPTH_M = 121;
    private static final float PALEO_ZONE = -0.2f;
    /** Glacial troughs: coarse sea temperature (deg C) below which they appear (fully below the first). */
    private static final float TROUGH_TEMP_FULL = 1f, TROUGH_TEMP = 3f;
    /** Share of cold shelves with troughs (noise level; lower = more), spacing (km), deepest cut (m). */
    private static final float TROUGH_ZONE = -0.1f;
    private static final double TROUGH_SPACING_KM = 45, TROUGH_DEPTH = 300;
    /** Pockmarks: fields (noise level), grid (km), chance per cell, crater depth (m). */
    private static final float POCK_ZONE = 0.35f;
    private static final double POCK_CELL_KM = 0.45, POCK_CHANCE = 0.35, POCK_DEPTH = 16;

    /**
     * Texture on the continental shelf at native pixel (x, z), for raw depths between {@link #SHELF_TOP_M} and
     * {@link #SHELF_BASE_M}; returns the new raw elevation. All of it is a metre-scale change on a floor where one
     * block is ~8 m, so it is drawn a little larger than life to show.
     * <ul>
     *   <li><b>Sand waves</b>: fields of long, regular sand ridges ~0.9 km apart, crests lined up across a current
     *       whose direction turns slowly over hundreds of km.</li>
     *   <li><b>Drowned river channels</b>: shallow meandering channels across the outer shelf, cut by rivers when the
     *       sea was lower, running down toward the shelf edge (and often the canyon heads).</li>
     *   <li><b>The ice-age shoreline</b>: a low step about 120 m down, where the coast stood 20,000 years ago, with a
     *       slightly flatter terrace above it.</li>
     *   <li><b>Pockmarks</b>: fields of round craters ~100-300 m across where gas has bubbled out of the mud.</li>
     *   <li><b>Glacial troughs</b> on cold shelves (the Norwegian Channel, the troughs off Antarctica and Greenland):
     *       broad U-shaped valleys 5-15 km wide, gouged across the shelf by ice streams in the ice ages, up to 300 m
     *       deeper than the shelf around them, deepest on the inner shelf and shoaling to a low sill at its edge.</li>
     * </ul>
     */
    double shelf(double raw, double x, double z, CoarseField coarse, Ctx ctx) {
        double depth = -raw;
        double band = smoothstep(SHELF_TOP_M, SHELF_TOP_M + 15, depth) * (1 - smoothstep(SHELF_BASE_M - 30, SHELF_BASE_M, depth));
        if (band <= 0) return raw;
        float kx = (float) (x / KM), kz = (float) (z / KM);
        double out = raw;

        // The ice-age shoreline (a monotone remap of depth: shallower terrace above, a step down at the old coast).
        double paleo = smoothstep(PALEO_ZONE, PALEO_ZONE + 0.25, shelfZone.GetNoise(kx * 0.7f - 211, kz * 0.7f));
        if (paleo > 0 && depth > 80 && depth < 170) {
            double shift = PALEO_STEP_M * (smoothstep(PALEO_DEPTH_M - 3, PALEO_DEPTH_M + 3, depth) - smoothstep(PALEO_DEPTH_M - 25, PALEO_DEPTH_M + 25, depth));
            out -= shift * paleo;
        }

        // Sand waves: crests across a slowly turning current.
        double sw = smoothstep(SAND_WAVE_ZONE, SAND_WAVE_ZONE + 0.2, shelfZone.GetNoise(kx, kz)) * band;
        if (sw > 0) {
            double current = currentDir.GetNoise(kx / 400f, kz / 400f) * Math.PI * 2;
            double n = orientedNoise(sandWaves, kx, kz, current + Math.PI / 2, SAND_WAVE_KM, 7, 0.25);
            // Sharp crests, broad troughs.
            double crest = 1 - 2 * Math.abs(n);
            out += SAND_WAVE_M * crest * sw;
            if (sw > 0.6) ctx.tag = TAG_SAND_WAVES;
        }

        // Drowned river channels across the outer shelf, down toward the shelf edge.
        double outer = smoothstep(45, 75, depth);
        if (outer > 0) {
            double zone = smoothstep(CANYON_ZONE, CANYON_ZONE + 0.25, canyonZone.GetNoise(kx, kz));
            if (zone > 0) {
                double gx = coarse.elevation(z, x + SLOPE_SPAN) - coarse.elevation(z, x - SLOPE_SPAN);
                double gz = coarse.elevation(z + SLOPE_SPAN, x) - coarse.elevation(z - SLOPE_SPAN, x);
                if (Math.abs(gx) + Math.abs(gz) > 1) {
                    double down = Math.atan2(-gz, -gx);
                    double s = lineDistance(drowned, kx, kz, down, DROWNED_SPACING_KM, 60, 2.5) / 0.3;
                    if (s < 1) out -= DROWNED_DEPTH * (1 - s * s) * zone * outer * band;
                }
            }
        }

        // Glacial troughs across cold shelves.
        double trough = troughCut(depth, x, z, coarse);
        if (trough > 0) {
            out -= trough * band;
            if (trough * band > 40) ctx.tag = TAG_TROUGH;
        }

        // Pockmarks.
        double pz = smoothstep(POCK_ZONE, POCK_ZONE + 0.15, shelfZone.GetNoise(kx * 3f + 77, kz * 3f)) * smoothstep(60, 90, depth) * band;
        if (pz > 0) {
            double cell = POCK_CELL_KM * KM;
            int cx = (int) Math.floor(x / cell), cz = (int) Math.floor(z / cell);
            double best = 0;
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    long h = hash(0x90C4L, cx + dx, cz + dz);
                    if (unit(h) > POCK_CHANCE) continue;
                    double px = (cx + dx + 0.15 + 0.7 * unit(mix(h))) * cell, pzz = (cz + dz + 0.15 + 0.7 * unit(mix(h + 1))) * cell;
                    double rad = (0.05 + 0.1 * unit(mix(h + 2))) * KM;
                    double rr = len(x - px, z - pzz) / rad;
                    if (rr < 1.6) {
                        // A bowl with a faint rim.
                        double v = rr < 1 ? (1 - rr * rr) : -0.12 * Math.sin(Math.PI * (rr - 1) / 0.6);
                        if (Math.abs(v) > Math.abs(best)) best = v;
                    }
                }
            }
            if (best != 0) {
                out -= POCK_DEPTH * best * pz;
                if (best > 0.3 && pz > 0.5) ctx.tag = TAG_POCKMARK;
            }
        }
        return out;
    }

    /**
     * How much (metres) a glacial trough deepens the shelf at native pixel (x, z), where it lies {@code depth} metres
     * down: a U-shaped valley (flat floor, steep walls) running down the shelf, fading in on the inner shelf and
     * shoaling toward the shelf edge, so its mouth is a low sill.
     */
    private double troughCut(double depth, double x, double z, CoarseField coarse) {
        float temp = coarse.temperature(z, x);
        if (temp >= TROUGH_TEMP || depth < 30) return 0;
        float kx = (float) (x / KM), kz = (float) (z / KM);
        double zone = (1 - smoothstep(TROUGH_TEMP_FULL, TROUGH_TEMP, temp))
                * smoothstep(TROUGH_ZONE, TROUGH_ZONE + 0.2, troughZone.GetNoise(kx, kz));
        if (zone <= 0) return 0;
        double gx = coarse.elevation(z, x + SLOPE_SPAN) - coarse.elevation(z, x - SLOPE_SPAN);
        double gz = coarse.elevation(z + SLOPE_SPAN, x) - coarse.elevation(z - SLOPE_SPAN, x);
        if (Math.abs(gx) + Math.abs(gz) < 1) return 0;
        double down = Math.atan2(-gz, -gx);
        // Half-width 2.5-7.5 km, varying slowly along the coast.
        double halfKm = 2.5 + 5 * (0.5 + 0.5 * troughZone.GetNoise(kx * 3f + 711, kz * 3f));
        double s = lineDistance(troughNoise, kx, kz, down, TROUGH_SPACING_KM, 400, 0.8) / halfKm;
        if (s >= 1) return 0;
        double u = 1 - smoothstep(0.55, 1.0, s);                     // flat floor, steep walls
        double along = smoothstep(30, 90, depth) * (1 - 0.85 * smoothstep(110, 200, depth));
        double deep = TROUGH_DEPTH * (0.55 + 0.45 * (0.5 + 0.5 * troughZone.GetNoise(kx * 2f - 90, kz * 2f + 13)));
        return deep * u * along * zone;
    }

    // ---- Slope: salt domes, mud volcanoes ----

    /** Share of margins that are salt provinces (noise level; higher = rarer), dome relief (m), escarpment (m). */
    private static final float SALT_ZONE = 0.42f;
    private static final double SALT_RELIEF = 320, SALT_ESCARPMENT = 450, SALT_TOE_M = 2950;
    /** Mud volcanoes: grid (km), chance per cell, height range (m). */
    private static final double MUD_CELL_KM = 30, MUD_CHANCE = 0.22;

    /**
     * A salt province (the northern Gulf of Mexico): salt squeezed out under the slope has pushed up domes and left
     * small basins between them, so the slope is lumpy instead of smooth, and it ends at its foot in a steep
     * escarpment where the salt sheet stops (the Sigsbee Escarpment). Only on continental margins, in rare zones.
     */
    private double saltProvince(double raw, double x, double z, double cont, Ctx ctx) {
        float kx = (float) (x / KM), kz = (float) (z / KM);
        double zone = cont * smoothstep(SALT_ZONE, SALT_ZONE + 0.12, canyonZone.GetNoise(kx * 0.6f - 500, kz * 0.6f + 77));
        if (zone <= 0) return raw;
        double depth = -raw;
        // Lumps: domes (positive) and minibasins (negative), ~6-12 km across.
        double lump = saltLumps.GetNoise(kx / 7f, kz / 7f);
        double band = smoothstep(350, 700, depth) * (1 - smoothstep(SALT_TOE_M - 400, SALT_TOE_M, depth));
        double out = raw + SALT_RELIEF * lump * Math.abs(lump) * 1.6 * band * zone;
        // The escarpment: the slope above the toe is held up by the salt (shallower), then drops at the toe. This is a
        // monotone remap of depth, so it never makes a ridge or a hollow.
        double up = smoothstep(1400, SALT_TOE_M - 150, depth) * (1 - smoothstep(SALT_TOE_M - 150, SALT_TOE_M + 120, depth));
        out += SALT_ESCARPMENT * up * zone;
        if (zone > 0.5 && depth > 600 && depth < SALT_TOE_M) ctx.tag = TAG_SALT;
        return out;
    }

    /**
     * Mud volcanoes on the continental slope (the Gulf of Cadiz, the Black Sea): broad, low cones of mud that erupted
     * from buried, over-pressured sediment, with a small summit crater. Mud on the floor around them.
     */
    private double mudVolcanoes(double raw, double x, double z, Ctx ctx) {
        double cell = MUD_CELL_KM * KM;
        int cx = (int) Math.floor(x / cell), cz = (int) Math.floor(z / cell);
        double add = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                MudVolcano mv = mudVolcano(cx + dx, cz + dz);
                if (mv == null) continue;
                double r = len(x - mv.x, z - mv.z) / mv.radius;
                if (r >= 1.6) continue;
                double depth = -raw;
                if (depth < 400 || depth > 3000) continue;
                double cone = r < 1 ? Math.pow(1 - r, 1.6) : 0;
                double crater = Math.exp(-sq(r / 0.12));
                double v = mv.height * (cone - 0.35 * crater * cone);
                add = Math.max(add, v);
                if (r < 1.35) ctx.tag = TAG_MUD;
            }
        }
        return raw + add;
    }

    record MudVolcano(double x, double z, double radius, double height) {}

    private MudVolcano mudVolcano(int gx, int gz) {
        long h = hash(0x30DL, gx, gz);
        if (unit(h) > MUD_CHANCE) return null;
        double cell = MUD_CELL_KM * KM;
        double mx = (gx + 0.15 + 0.7 * unit(mix(h))) * cell, mz = (gz + 0.15 + 0.7 * unit(mix(h + 1))) * cell;
        return new MudVolcano(mx, mz, (0.8 + 1.4 * unit(mix(h + 2))) * KM, 90 + 190 * unit(mix(h + 3)));
    }

    /**
     * Distance (km, roughly) from (kx, kz) to the nearest zero line of {@link #orientedNoise}: the noise value over
     * the size of its gradient. Lines drawn where this is small keep a constant width, instead of swelling into wide
     * flat patches where the noise lingers near zero.
     */
    private double lineDistance(FastNoiseLite n, float kx, float kz, double dir, double acrossKm, double alongKm, double wanderKm) {
        final float h = 0.12f;
        double v = orientedNoise(n, kx, kz, dir, acrossKm, alongKm, wanderKm);
        double vx = orientedNoise(n, kx + h, kz, dir, acrossKm, alongKm, wanderKm);
        double vz = orientedNoise(n, kx, kz + h, dir, acrossKm, alongKm, wanderKm);
        double g = len(vx - v, vz - v) / h;
        return g < 1e-6 ? Double.MAX_VALUE : Math.abs(v) / g;
    }

    /**
     * Noise in about [-1, 1] whose features run along {@code dir} (radians): {@code acrossKm} between them, stretched
     * {@code alongKm} along them, meandering by {@code wanderKm}. A blend of four fixed orientations 45 degrees apart,
     * weighted by how close each is to {@code dir} (weights summing to one in variance), so the result is seamless.
     */
    private double orientedNoise(FastNoiseLite n, float kx, float kz, double dir, double acrossKm, double alongKm, double wanderKm) {
        double sum = 0, wsq = 0;
        for (int k = 0; k < 4; k++) {
            double theta = k * Math.PI / 4;
            double w = Math.cos(2 * (dir - theta));
            if (w <= 0) continue;
            w *= w;
            float ca = (float) Math.cos(theta), sa = (float) Math.sin(theta);
            float u = ca * kx + sa * kz, v = -sa * kx + ca * kz;              // along and across this orientation
            float wv = (float) (wanderKm * meander.GetNoise(u / 9f + 31 * k, v / 9f));
            sum += w * n.GetNoise((float) (u / alongKm) + 57 * k, (float) ((v + wv) / acrossKm) - 23 * k);
            wsq += w * w;
        }
        return sum / Math.sqrt(Math.max(wsq, 1e-9));
    }

    // =========================================================================
    // Round 3: plateaus, arcs, floor materials
    // =========================================================================

    /**
     * Oceanic plateaus: one chance per cell (native pixels). (Microcontinents with granite islands were tried here in
     * round 3 and removed at Calvin's request, Oct 20; plateaus kept the frequency they had then.)
     */
    private static final double PLATEAU_CELL = 420 * KM, PLATEAU_CHANCE = 0.3;
    /** Coarse depth (metres) a plateau needs at its centre. */
    private static final float PLATEAU_MIN_DEPTH = 2500f;

    /** An oceanic plateau; all from the seed. */
    record Plateau(double x, double z, double radius, double top, long h) {}

    private static final Plateau NO_PLATEAU = new Plateau(0, 0, 0, 0, 0);
    private final java.util.concurrent.ConcurrentHashMap<Long, Plateau> plateauCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** The plateau of cell (gx, gz), or null. Cached. */
    Plateau plateau(int gx, int gz) {
        long key = cellKey(gx, gz);
        Plateau p = plateauCache.get(key);
        if (p == null) p = plateauCache.computeIfAbsent(key, k -> buildPlateau(gx, gz));
        return p == NO_PLATEAU ? null : p;
    }

    private Plateau buildPlateau(int gx, int gz) {
        long h = hash(0x91A7L, gx, gz);
        if (unit(h) > PLATEAU_CHANCE) return NO_PLATEAU;
        double px = (gx + 0.2 + 0.6 * unit(mix(h + 2))) * PLATEAU_CELL, pz = (gz + 0.2 + 0.6 * unit(mix(h + 3))) * PLATEAU_CELL;
        double radius = (30 + 30 * unit(mix(h + 4))) * KM;
        double top = -(1300 + 1100 * unit(mix(h + 5)));
        return new Plateau(px, pz, radius, top, h);
    }

    /**
     * <b>Oceanic plateaus</b> (Ontong Java, Kerguelen): huge, flat-topped volcanic plateaus 60-120 km across,
     * rising from the abyss to 1.3-2.4 km below the sea, with lobed outlines and low knolls on top. They need a deep
     * floor (coarse 2.5 km) at their centre and combine with volcanoes like volcanoes do.
     */
    private void plateaus(double x, double z, CoarseField coarse, Ctx ctx) {
        int cx = (int) Math.floor(x / PLATEAU_CELL), cz = (int) Math.floor(z / PLATEAU_CELL);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Plateau p = plateau(cx + dx, cz + dz);
                if (p == null) continue;
                double r = len(x - p.x, z - p.z);
                if (r > p.radius * 1.35) continue;
                float floor = coarse.elevation(p.z, p.x);
                if (floor > -PLATEAU_MIN_DEPTH || !clearOfLand(p.x, p.z, p.radius, coarse)) continue;
                // Lobed outline, at the plateau's own scale.
                double scale = KM * 4 / p.radius;
                float kx = (float) (x / KM * scale) + (p.h & 0xFF), kz = (float) (z / KM * scale) + ((p.h >>> 8) & 0xFF);
                double u = r * (1 + 0.22 * coastShape.GetNoise(kx, kz) + 0.08 * coastShape.GetNoise(kx * 3.1f + 40, kz * 3.1f)) / p.radius;
                if (u >= 1) continue;
                double prof = Math.pow(1 - smoothstep(0.55, 1.0, u), 1.3);        // broad top, gentle flanks
                // Low volcanic knolls and swales on the plateau top.
                double top = p.top + 180 * hills.GetNoise((float) (x / KM / 6) + 500, (float) (z / KM / 6));
                volcano(ctx, (top - floor) * prof, floor);
            }
        }
    }

    // ---- Forearc and back-arc ----

    /** Forearc wedge: from the trench (xo = 0) to here (native pixels), peaking at {@link #FOREARC_AT}. */
    private static final double FOREARC_OUTER = 85 * KM, FOREARC_AT = 42 * KM, FOREARC_HEIGHT = 1300;
    /** Forearc islands: spacing along the arc, chance per slot, needed trench strength. */
    private static final double FOREARC_SPACING = 75 * KM, FOREARC_CHANCE = 0.3, FOREARC_MIN_STRENGTH = 0.7;
    /** Back-arc basin: distance behind the trench (native pixels), basin depth (m), share of trenches with one. */
    private static final double BACKARC_AT = 185 * KM, BACKARC_DEPTH = 1100, BACKARC_CHANCE = 0.55;

    /**
     * The forearc: the wedge of sediment scraped off the sinking plate and stacked against the overriding one, a
     * lumpy ridge between the trench and the island arc (the Barbados Ridge, the Mentawai Islands).
     */
    private double forearcWedge(double xo, double along, long pair) {
        double prof = Math.sin(Math.PI * clamp01(xo / FOREARC_OUTER));
        prof *= prof;
        float lump = hills.GetNoise((float) (along / KM / 9) + (pair & 0xFF), (float) (xo / KM / 5) + 300);
        return FOREARC_HEIGHT * prof * (0.75 + 0.45 * lump);
    }

    /**
     * Low islands where the forearc wedge is thickest and breaks the surface (Barbados): broad, gentle domes with a
     * ragged coast, capped with coral limestone. Placed in warped space along the arc like the arc volcanoes.
     */
    private void forearcIslands(double x, double z, double xo, double along, long pair, boolean subHi,
                                double mx, double mz, double nx, double nz, double tx, double tz,
                                double edgeWeight, CoarseField coarse, Ctx ctx) {
        double ox = subHi ? -nx : nx, oz = subHi ? -nz : nz;
        long kc = Math.round(along / FOREARC_SPACING);
        for (long k = kc - 1; k <= kc + 1; k++) {
            long h = hash(pair, k, 0xF0AEL);
            if (unit(h) > FOREARC_CHANCE) continue;
            double ak = k * FOREARC_SPACING + (unit(mix(h)) - 0.5) * 30 * KM;
            double strength = trenchStrength(ak, pair) * edgeWeight;
            if (strength < FOREARC_MIN_STRENGTH) continue;
            double across = FOREARC_AT + (unit(mix(h + 1)) - 0.5) * 12 * KM;
            double radius = (6 + 8 * unit(mix(h + 2))) * KM;
            double r = len(along - ak, xo - across);
            if (r > radius * 1.3) continue;
            double[] c = unwarp(mx + tx * ak + ox * across, mz + tz * ak + oz * across);
            float floor = coarse.elevation(c[1], c[0]);
            if (floor > -VOLCANO_MIN_DEPTH || !clearOfLand(c[0], c[1], radius, coarse)) continue;
            double peak = 50 + 260 * unit(mix(h + 3));
            double ang = Math.atan2(xo - across, along - ak);
            double rc = radius / KM / (2 * Math.PI);
            float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
            double n = coastFine.GetNoise((float) rc * ca - 300, (float) rc * sa + 77, (float) (r / KM * 0.12) + (h & 0xFF));
            double wob = Math.min(0.8 * KM, 0.3 * radius) / radius * n * smoothstep(0, 1, r / radius);
            double u = r / radius * (1 + 0.15 * coastShape.GetNoise(ca * 1.2f + (h & 0x7F), sa * 1.2f)) + wob;
            if (u >= 1) continue;
            // Land out to ~45% of the radius, a gentle dome; a steeper submarine flank down to the wedge.
            double landU = 0.42, sea = -floor / (peak - floor);
            double s = u < landU ? sea + (1 - sea) * (1 - Math.pow(u / landU, 1.8)) : sea * Math.pow((1 - u) / (1 - landU), 1.5);
            double cc = (peak - floor) * s;
            island(ctx, cc, floor);
            if (cc > ctx.surfaceC) { ctx.surfaceC = cc; ctx.surfaceTag = TAG_LIMESTONE_SOIL; }
        }
    }

    /** Whether the island with hash {@code h} in a sea of {@code seaTemp} °C is a mushroom island. */
    static boolean isMushroom(long h, float seaTemp) {
        return seaTemp >= MUSHROOM_MIN_TEMP && unit(mix(h + 0x3C3CL)) < MUSHROOM_CHANCE;
    }

    private static boolean isIslandStage(String stage) {
        return stage.equals("volcanic_island") || stage.equals("flooded_caldera") || stage.equals("barrier_reef_island");
    }

    /** The island just added ({@code c} metres above its floor here) is a mushroom island: its land takes the tag. */
    private static void tagMushroom(Ctx ctx, double c) {
        if (c > ctx.surfaceC) {
            ctx.surfaceC = c;
            ctx.surfaceTag = TAG_MUSHROOM;
        }
    }

    private boolean hasBackArc(long pair) { return unit(mix(pair ^ 0xBACCL)) < BACKARC_CHANCE; }

    /**
     * How strongly a back-arc basin is open at this point along the trench (0..1): the trench's strength averaged
     * over ~300 km, so the basin runs the length of an arc instead of following each short strong stretch of trench.
     */
    private double backArcStrength(double along, long pair) {
        double s = 0;
        for (int k = -3; k <= 3; k++) s += trenchStrength(along + k * 50 * KM, pair);
        return smoothstep(0.2, 0.55, s / 7);
    }

    private double backArcOffset(double along, long pair) {
        double phase = unit(mix(pair ^ 0xBA5EL)) * SEGMENT;
        long k = (long) Math.floor((along + phase) / (0.6 * SEGMENT));
        return (unit(hash(pair, k, 0xBA0F)) - 0.5) * 12 * KM;
    }

    /**
     * A back-arc basin (the Lau Basin, the Mariana Trough): behind the island arc the overriding plate is pulled
     * apart into a small, deep basin with its own short spreading ridge, offset in segments. {@code xb} is the
     * distance (native pixels) from the basin's centre line, positive away from the trench.
     */
    private double backArc(double xb, double along, long pair) {
        double basin = -BACKARC_DEPTH * Math.exp(-sq(xb / (45 * KM)));
        double xa = xb - backArcOffset(along, pair), xr = Math.abs(xa);
        double axis = 900 * Math.exp(-xr / (12 * KM)) - 480 * Math.exp(-sq(xr / (2.2 * KM)));
        // Fresh crust: ridges and troughs parallel to the axis, like a mid-ocean ridge's abyssal hills.
        float fabric = hills.GetNoise((float) (along / KM / 20) + (pair & 0xFF), (float) (xa / KM / 2.8));
        return basin + (axis + 220 * fabric) * Math.exp(-sq(xb / (40 * KM)));
    }

    /** Distance (native pixels) from a ridge-boundary point (ds across, along) to the ridge's (offset) axis. */
    private double ridgeAxisDistance(double ds, double along, long pair) {
        double phase = unit(mix(pair ^ 0xF00DL)) * SEGMENT;
        long k = (long) Math.floor((along + phase) / SEGMENT);
        return Math.abs(ds - segmentOffset(pair, k));
    }

    // ---- Floor materials ----

    /** Depths (m) of the carbonate compensation depth band: ooze above, red clay below. */
    private static final double CCD_TOP = 4000, CCD_BOTTOM = 4600;
    /** Young crust: pillow lava out to this distance from a ridge axis (native pixels). */
    private static final double PILLOW_REACH = 10 * KM;

    /**
     * What covers an open-ocean floor at elevation {@code total}: pillow lava on young crust by a spreading axis
     * (mid-ocean and back-arc ridges), pale carbonate ooze above the carbonate compensation depth (~4.3 km; deeper,
     * the shells dissolve before they settle), red-brown clay below it. Near continents the floor keeps its sand,
     * gravel and clay (mud washed off the land buries everything else). Patchy edges, so no hard lines.
     */
    private byte floorMaterial(double total, double x, double z, Ctx ctx) {
        float kx = (float) (x / KM), kz = (float) (z / KM);
        // Broad patches (~10 km), so the boundaries wander instead of speckling.
        float patch = shelfZone.GetNoise(kx * 12f + 900, kz * 12f);
        if (ctx.ridgeAxis < PILLOW_REACH * (1 + 0.5 * patch)) return TAG_PILLOW;
        if (ctx.continent > 0.45 + 0.2 * patch) return TAG_NONE;
        double ccd = CCD_TOP + (CCD_BOTTOM - CCD_TOP) * (0.5 + 0.5 * patch);
        return -total < ccd ? TAG_OOZE : TAG_RED_CLAY;
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
        float e = coarse.elevation(i, j);
        float kx = (float) (j / KM), kz = (float) (i / KM);
        // Sea floor features (round 3): any climate.
        switch (kind) {
            case "submarine_canyon" -> {
                return e > -2500 && e < -300 && canyonZone.GetNoise(kx, kz) > CANYON_ZONE + 0.15f;
            }
            case "deep_sea_fan" -> {
                return e > -4800 && e < -2700 && canyonZone.GetNoise(kx * 0.4f + 300, kz * 0.4f) > FAN_ZONE + 0.15f;
            }
            case "sand_waves" -> {
                return e > -190 && e < -35 && shelfZone.GetNoise(kx, kz) > SAND_WAVE_ZONE + 0.15f;
            }
            case "pockmarks" -> {
                return e > -190 && e < -75 && shelfZone.GetNoise(kx * 3f + 77, kz * 3f) > POCK_ZONE + 0.1f;
            }
            case "salt_domes" -> {
                return e > -2700 && e < -700 && canyonZone.GetNoise(kx * 0.6f - 500, kz * 0.6f + 77) > SALT_ZONE + 0.08f;
            }
            case "glacial_trough" -> {
                return e > -200 && e < -40 && coarse.temperature(i, j) < TROUGH_TEMP - 0.5f
                        && troughZone.GetNoise(kx, kz) > TROUGH_ZONE + 0.15f;
            }
            case "strandflat" -> {
                return e > -300 && e < 300 && coarse.temperature(i, j) < STRAND_TEMP - 0.5f
                        && strandZone.GetNoise(kx, kz) > STRAND_ZONE + 0.15f && strandPatch.GetNoise(kx, kz) > STRAND_PATCH + 0.25f;
            }
            default -> { }
        }
        if (coarse.temperature(i, j) < REEF_TEMP_HI - 0.5f) return false;
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
            case "submarine_canyon" -> TAG_CANYON;
            case "deep_sea_fan" -> TAG_FAN_CHANNEL;
            case "sand_waves" -> TAG_SAND_WAVES;
            case "pockmarks" -> TAG_POCKMARK;
            case "salt_domes" -> TAG_SALT;
            case "glacial_trough" -> TAG_TROUGH;
            case "strandflat" -> TAG_STRANDFLAT;
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
        plateauLandmarks(i0, j0, i1, j1, coarse, out);
        mudVolcanoLandmarks(i0, j0, i1, j1, coarse, out);
        return out;
    }

    private void plateauLandmarks(double i0, double j0, double i1, double j1, CoarseField coarse, java.util.List<Landmark> out) {
        int gx0 = (int) Math.floor(j0 / PLATEAU_CELL), gx1 = (int) Math.floor(j1 / PLATEAU_CELL);
        int gz0 = (int) Math.floor(i0 / PLATEAU_CELL), gz1 = (int) Math.floor(i1 / PLATEAU_CELL);
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                Plateau p = plateau(gx, gz);
                if (p == null || !inside(p.z, p.x, i0, j0, i1, j1)) continue;
                if (coarse.elevation(p.z, p.x) > -PLATEAU_MIN_DEPTH || !clearOfLand(p.x, p.z, p.radius, coarse)) continue;
                out.add(new Landmark("oceanic_plateau", p.z, p.x, (float) p.top));
            }
        }
    }

    private void mudVolcanoLandmarks(double i0, double j0, double i1, double j1, CoarseField coarse, java.util.List<Landmark> out) {
        double cell = MUD_CELL_KM * KM;
        int gx0 = (int) Math.floor(j0 / cell), gx1 = (int) Math.floor(j1 / cell);
        int gz0 = (int) Math.floor(i0 / cell), gz1 = (int) Math.floor(i1 / cell);
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                MudVolcano mv = mudVolcano(gx, gz);
                if (mv == null || !inside(mv.z(), mv.x(), i0, j0, i1, j1)) continue;
                float ce = coarse.elevation(mv.z(), mv.x());
                if (ce > -400 || ce < -3000) continue;
                // Only where the terrain puts one: on a continental slope 400-3000 m down. The coarse map stands in for
                // the model's depth here, so a marker can sit on a spot the slope just misses.
                out.add(new Landmark("mud_volcano", mv.z(), mv.x(), (float) mv.height()));
            }
        }
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
                    if (floor > -VOLCANO_MIN_DEPTH || !clearOfLand(ch.cx[k], ch.cz[k], ch.radius[k], coarse)) continue;
                    boolean warm = coarse.temperature(ch.cz[k], ch.cx[k]) >= REEF_TEMP;
                    String stage = volcanoStage(ch.peak[k], warm, k, ch.hk[k], ch.lift[k]);
                    boolean island = ch.peak[k] > 40;
                    if (island && hasScar(ch.peak[k], ch.hk[k])) {
                        // Marker at the mouth of the scar, on the old coast.
                        double landR = islandLandU(stage, ch.hk[k]) * ch.radius[k], a = scarAngle(ch.hk[k]);
                        double si = ch.cz[k] + Math.sin(a) * landR, sj = ch.cx[k] + Math.cos(a) * landR;
                        if (inside(si, sj, i0, j0, i1, j1)) out.add(new Landmark("landslide_scar", si, sj, (float) ch.peak[k]));
                    }
                    boolean mushroomIsle = k > 0 && isIslandStage(stage)
                            && isMushroom(ch.hk[k], coarse.temperature(ch.cz[k], ch.cx[k]));
                    if (stage.equals("volcanic_island") && k <= 1 && !mushroomIsle) {
                        coneLandmarks(ch.hk[k], ch.peak[k], ch.radius[k], islandLandU(stage, ch.hk[k]), ch.cx[k], ch.cz[k],
                                IDENTITY, i0, j0, i1, j1, out);
                    }
                    if (!inside(ch.cz[k], ch.cx[k], i0, j0, i1, j1)) continue;
                    float value = stage.equals("raised_coral_island") ? (float) raisedLift(ch.hk[k]) : (float) ch.peak[k];
                    out.add(new Landmark(stage, ch.cz[k], ch.cx[k], value));
                    if (k > 0 && isIslandStage(stage) && isMushroom(ch.hk[k], coarse.temperature(ch.cz[k], ch.cx[k]))) {
                        out.add(new Landmark("mushroom_island", ch.cz[k], ch.cx[k], value));
                    }
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
                    // Coastal cones can lie a little outside the window when the island does not.
                    double reach = arcRadius(h) + 3 * KM;
                    if (!inside(c[1], c[0], i0 - reach, j0 - reach, i1 + reach, j1 + reach)) continue;
                    if (coarse.elevation(c[1], c[0]) > -VOLCANO_MIN_DEPTH) continue;
                    if (!clearOfLand(c[0], c[1], arcRadius(h), coarse)) continue;
                    boolean warm = coarse.temperature(c[1], c[0]) >= REEF_TEMP;
                    int extra = arcExtra(peak, warm, h);
                    if (hasScar(peak, h)) {
                        // Marker at the mouth of the scar, on the old coast (in the arc's frame, like the island).
                        double landR = arcLandU(h) * arcRadius(h), sa = scarAngle(h);
                        double[] sw = unwarp(mx + tx * (ak + Math.cos(sa) * landR) + ox * (across + Math.sin(sa) * landR),
                                mz + tz * (ak + Math.cos(sa) * landR) + oz * (across + Math.sin(sa) * landR));
                        if (inside(sw[1], sw[0], i0, j0, i1, j1)) out.add(new Landmark("landslide_scar", sw[1], sw[0], (float) peak));
                    }
                    if (extra == ARC_PLAIN && !isMushroom(h, coarse.temperature(c[1], c[0]))) {
                        // In the arc's frame (along, across), as the island is shaped.
                        coneLandmarks(h, peak, arcRadius(h), arcLandU(h), ak, across, arcFrame(mx, mz, tx, tz, ox, oz),
                                i0, j0, i1, j1, out);
                    }
                    if (!inside(c[1], c[0], i0, j0, i1, j1)) continue;
                    String type = extra == ARC_CALDERA ? "flooded_caldera" : (extra == ARC_REEF ? "barrier_reef_island" : "arc_island");
                    out.add(new Landmark(type, c[1], c[0], (float) peak));
                    if (isMushroom(h, coarse.temperature(c[1], c[0]))) out.add(new Landmark("mushroom_island", c[1], c[0], (float) peak));
                }
                // Forearc islands.
                for (long k = (long) Math.floor(amin / FOREARC_SPACING) - 1; k <= (long) Math.ceil(amax / FOREARC_SPACING) + 1; k++) {
                    long h = hash(pair, k, 0xF0AEL);
                    if (unit(h) > FOREARC_CHANCE) continue;
                    double ak = k * FOREARC_SPACING + (unit(mix(h)) - 0.5) * 30 * KM;
                    double w = edgeWeightAt(mx + tx * ak, mz + tz * ak, ia, ib, seedX, seedZ);
                    if (trenchStrength(ak, pair) * w < FOREARC_MIN_STRENGTH) continue;
                    double across = FOREARC_AT + (unit(mix(h + 1)) - 0.5) * 12 * KM;
                    double[] c = unwarp(mx + tx * ak + ox * across, mz + tz * ak + oz * across);
                    if (!inside(c[1], c[0], i0, j0, i1, j1)) continue;
                    if (coarse.elevation(c[1], c[0]) > -VOLCANO_MIN_DEPTH) continue;
                    if (!clearOfLand(c[0], c[1], (6 + 8 * unit(mix(h + 2))) * KM, coarse)) continue;
                    out.add(new Landmark("forearc_island", c[1], c[0], (float) (50 + 260 * unit(mix(h + 3)))));
                }
                // Back-arc basins: one marker on the spreading axis where the trench is strongest.
                if (hasBackArc(pair)) {
                    double bstep = 25 * KM, bprev = 0, bprev2 = 0;
                    for (double a = amin - bstep; a <= amax + 2 * bstep; a += bstep) {
                        double st = backArcStrength(a, pair) * edgeWeightAt(mx + tx * a, mz + tz * a, ia, ib, seedX, seedZ);
                        double am = a - bstep;
                        if (bprev >= 0.6 && bprev >= bprev2 && bprev > st) {
                            double across = BACKARC_AT + backArcOffset(am, pair);
                            double[] c = unwarp(mx + tx * am + ox * across, mz + tz * am + oz * across);
                            if (inside(c[1], c[0], i0, j0, i1, j1) && coarse.elevation(c[1], c[0]) <= -2500f) {
                                out.add(new Landmark("back_arc_basin", c[1], c[0], (float) (coarse.elevation(c[1], c[0]) - BACKARC_DEPTH * bprev + 400)));
                            }
                        }
                        bprev2 = bprev;
                        bprev = st;
                    }
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

    /**
     * Markers for a young island's tuff rings ({@code tuff_ring}; value is the rim height, negative for a flooded
     * ring, rim + 1000 for a maar), placed by the same rules as the terrain. {@code (cx, cz)} is the island centre in its own frame and {@code toWorld}
     * maps that frame to native pixels (x, z).
     */
    private void coneLandmarks(long h, double peak, double radius, double landU, double cx, double cz,
                               java.util.function.BiFunction<Double, Double, double[]> toWorld,
                               double i0, double j0, double i1, double j1, java.util.List<Landmark> out) {
        if (peak < 100) return;
        for (int n = 0; n < tuffCount(h); n++) {
            TuffRing t = tuffRing(h, n);
            double[] c = ringCentre(t, n, cx, cz, radius, landU, h, toWorld);
            double[] w = toWorld.apply(c[0], c[1]);
            if (!inside(w[1], w[0], i0, j0, i1, j1)) continue;
            float v = (float) (t.kind == TUFF_MAAR ? 1000 + t.rim : (t.kind == TUFF_FLOODED ? -t.rim : t.rim));
            out.add(new Landmark("tuff_ring", w[1], w[0], v));
        }
    }

    /** The identity frame (hotspot islands are shaped in world space). */
    private static final java.util.function.BiFunction<Double, Double, double[]> IDENTITY = (a, b) -> new double[]{a, b};

    /** An island arc's frame (along, across into the overriding plate) to world pixels. */
    private java.util.function.BiFunction<Double, Double, double[]> arcFrame(double mx, double mz, double tx, double tz,
                                                                          double ox, double oz) {
        return (a, b) -> unwarp(mx + tx * a + ox * b, mz + tz * a + oz * b);
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
        double da = len(bx - seedX[ia], bz - seedZ[ia]);
        double nearestOther = Double.MAX_VALUE;
        for (int c = 0; c < seedX.length; c++) {
            if (c == ia || c == ib) continue;
            nearestOther = Math.min(nearestOther, len(bx - seedX[c], bz - seedZ[c]));
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
                double d = len(x - sx, z - sz);
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
            double beyond = len(bx - sx[iZ], bz - sz[iZ]) - len(bx - sx[iX], bz - sz[iX]);
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
                    double r = len(along - ak, xo - across);
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
                    double r = len(x - ch.cx[k], z - ch.cz[k]);
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
            double r = len(along - ak, xo - across);
            if (r > radius * 2.8) continue;
            double strength = trenchStrength(ak, pair) * edgeWeight;
            if (strength <= 0.05) continue;
            double peak = arcPeak(strength, h);
            if (r > radius * 1.3 && !(peak > 0 && hasScar(peak, h))) continue;
            // Centre in world space, for the coarse depth check.
            double cqx = mx + tx * ak + ox * across, cqz = mz + tz * ak + oz * across;
            double[] c = unwarp(cqx, cqz);
            float floor = coarse.elevation(c[1], c[0]);
            if (floor > -VOLCANO_MIN_DEPTH) continue;
            if (peak > 0 && !clearOfLand(c[0], c[1], radius, coarse)) continue;
            double ang0 = Math.atan2(xo - across, along - ak);
            if (peak > 0 && hasScar(peak, h)) {
                // Debris field on the deep floor below the landslide scar, as for hotspot islands.
                ctx.flex += scarDebris(r / radius, ang0, x, z, h) * ctx.deepFade;
            }
            if (r > radius * 1.3) continue;
            // r is measured in warped space; close enough for the shape.
            if (peak > 0) {
                double landU = arcLandU(h);
                double ang = Math.atan2(xo - across, along - ak);
                boolean warm = coarse.temperature(c[1], c[0]) >= REEF_TEMP;
                int extra = arcExtra(peak, warm, h);
                double relief = peak - floor;
                int style = (extra == ARC_CALDERA ? STYLE_CALDERA : 0) | (hasScar(peak, h) ? STYLE_SCAR : 0);
                double cc = relief * islandCone(r, radius, -floor / relief, landU, ang, x, z, h, style, relief);
                island(ctx, cc, floor);
                boolean mushroom = isMushroom(h, coarse.temperature(c[1], c[0]));
                if (mushroom) tagMushroom(ctx, cc);
                if (extra == ARC_PLAIN && peak > 150) {
                    crater(ctx, r, ang, (0.3 + 0.5 * unit(mix(h + 120))) * KM, Math.min(0.2 * peak, 50 + 80 * unit(mix(h + 121))), h);
                }
                if (extra == ARC_PLAIN && !mushroom) coastalCones(ctx, along, xo, ak, across, x, z, radius, landU, h, peak, arcFrame(mx, mz, tx, tz, ox, oz));
                if (extra == ARC_REEF) {
                    // The reef ring is placed in warped space too, so it stays centred on the island.
                    double landR = landU * radius;
                    double rim = Math.min(barrierRim(landR * 1.25, h), 0.95 * radius);
                    pushOp(ctx, OP_ATOLL, rim, REEF_BARRIER, radius, landR, h, cqx, cqz);
                    ctx.opWarped[ctx.nOps - 1] = true;
                }
            } else {
                volcano(ctx, (peak - floor) * cone(r, radius * SUBMERGED_SHRINK, x, z, h, false), floor);
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
                    double r = len(x - ch.cx[k], z - ch.cz[k]);
                    if (r > ch.radius[k] * 1.3) continue;
                    float floor = coarse.elevation(ch.cz[k], ch.cx[k]);
                    if (floor > -VOLCANO_MIN_DEPTH || !clearOfLand(ch.cx[k], ch.cz[k], ch.radius[k], coarse)) continue;
                    boolean warm = coarse.temperature(ch.cz[k], ch.cx[k]) >= REEF_TEMP;
                    String stage = volcanoStage(ch.peak[k], warm, k, ch.hk[k], ch.lift[k]);
                    ctx.mushroomNext = k > 0 && isMushroom(ch.hk[k], coarse.temperature(ch.cz[k], ch.cx[k]));
                    addVolcano(x, z, ch.cx[k], ch.cz[k], r, ch.radius[k], ch.peak[k], floor, stage, ch.hk[k], k, ctx);
                    ctx.mushroomNext = false;
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
                    double r = len(x - ch.cx[k], z - ch.cz[k]);
                    double u0 = r / ch.radius[k];
                    if (u0 > 2.9 || ch.peak[k] < -1500) continue;
                    float floor = coarse.elevation(ch.cz[k], ch.cx[k]);
                    if (floor > -VOLCANO_MIN_DEPTH || !clearOfLand(ch.cx[k], ch.cz[k], ch.radius[k], coarse)) continue;
                    double peak = ch.peak[k];
                    // Moat and arch: the plate's bending under the island's weight, strongest under big young islands.
                    double load = smoothstep(-1500, 1500, peak) * Math.max(0.5, Math.min(1.2, (peak - floor) / 4500));
                    double bend = -Math.exp(-sq((u0 - 1.15) / 0.35)) + 0.25 * Math.exp(-sq((u0 - 2.2) / 0.45));
                    ctx.flex += MOAT_DEPTH * load * bend * smoothstep(0.55, 0.85, u0) * (1 - smoothstep(2.5, 2.9, u0)) * deepFade;
                    // Debris field below a landslide scar.
                    if (peak > 40 && hasScar(peak, ch.hk[k])) {
                        ctx.flex += scarDebris(u0, Math.atan2(z - ch.cz[k], x - ch.cx[k]), x, z, ch.hk[k]) * deepFade;
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
                        double dist = len(x - (ax + s * ex), z - (az + s * ez));
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

    /**
     * Height (m) of the debris field below a landslide scar at {@code u0} radii from the island's centre in direction
     * {@code ang}: hummocky blocks (~1 km, up to 380 m) in a fan 0.7 to 2.8 radii out.
     */
    private double scarDebris(double u0, double ang, double x, double z, long h) {
        if (u0 <= 0.7 || u0 >= 2.8) return 0;
        double sAng = scarAngle(h);
        double da = Math.atan2(Math.sin(ang - sAng), Math.cos(ang - sAng));
        double env = Math.exp(-sq(da / 0.3)) * smoothstep(0.7, 1.0, u0) * (1 - smoothstep(2.1, 2.8, u0));
        if (env <= 0.01) return 0;
        float n = gullies.GetNoise((float) (x / KM * 1.5), (float) (z / KM * 1.5), (h & 0xFF) * 3.1f);
        return 380 * env * smoothstep(0.1, 0.55, n);
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
        long key = cellKey(gx, gz);
        Chain c = chains.get(key);
        if (c == null) c = chains.computeIfAbsent(key, k -> buildChain(gx, gz));
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
            double beyond = len(bx - sx[iZ], bz - sz[iZ]) - len(bx - sx[iX], bz - sz[iX]);
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
                double r = len(x - sx, z - sz);
                if (r > radius * 1.3) continue;
                float floor = coarse.elevation(sz, sx);
                if (floor > -SEAMOUNT_MIN_DEPTH) continue;
                double peak = seamountPeak(floor, h);
                boolean guyot = isGuyot(peak, h);
                if (guyot) {
                    // A guyot was planed flat at sea level, then sank: build it taller and cut the top off.
                    volcano(ctx, (peak + 600 - floor) * tableCone(r, radius, x, z, h), floor);
                    pushOp(ctx, OP_GUYOT, peak, 0, radius, 0, h, sx, sz);
                } else {
                    volcano(ctx, (peak - floor) * cone(r, radius, x, z, h, false), floor);
                }
            }
        }
    }

    /** A hotspot volcano at a stage of its life set by how far it has sunk (its peak height). */
    private void addVolcano(double x, double z, double vcx, double vcz, double r, double radius,
                            double peak, double floor, String stage, long h, int k, Ctx ctx) {
        switch (stage) {
            case "volcanic_island", "flooded_caldera", "barrier_reef_island" -> {
                double landU = islandLandU(stage, h);
                double ang = Math.atan2(z - vcz, x - vcx);
                double relief = peak - floor;
                int style = (stage.equals("flooded_caldera") ? STYLE_CALDERA : 0) | (hasScar(peak, h) ? STYLE_SCAR : 0);
                double cc = relief * islandCone(r, radius, -floor / relief, landU, ang, x, z, h, style, relief);
                island(ctx, cc, floor);
                if (ctx.mushroomNext) tagMushroom(ctx, cc);
                if (stage.equals("volcanic_island") && peak > 150) {
                    crater(ctx, r, ang, (0.5 + 0.8 * unit(mix(h + 120))) * KM, Math.min(0.2 * peak, 70 + 110 * unit(mix(h + 121))), h);
                }
                // Young islands (the head and the one behind it) have tuff rings on their coasts (not mushroom islands:
                // their whole island is mycelium, as in vanilla).
                if (stage.equals("volcanic_island") && k <= 1 && !ctx.mushroomNext) {
                    coastalCones(ctx, x, z, vcx, vcz, x, z, radius, landU, h, peak, IDENTITY);
                }
                if (stage.equals("barrier_reef_island")) {
                    // The island is sinking and its fringing reef grew up into a ring offshore, with a lagoon between.
                    double landR = landU * radius;
                    pushOp(ctx, OP_ATOLL, barrierRim(landR * 1.25, h), REEF_BARRIER, radius, landR, h, vcx, vcz);
                }
            }
            case "atoll", "raised_coral_island" -> {
                // The volcano has sunk out of sight and its fringing reef kept growing up into a ring.
                double sub = Math.min(peak, -350);
                volcano(ctx, (sub - floor) * tableCone(r, radius, x, z, h), floor);
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
                volcano(ctx, (top + 600 - floor) * tableCone(r, rs, x, z, h), floor);
                pushOp(ctx, OP_GUYOT, top, 0, rs, 0, h, vcx, vcz);
            }
            default -> volcano(ctx, (peak - floor) * cone(r, radius * SUBMERGED_SHRINK, x, z, h, false), floor);
        }
    }

    // ---- Shared placement rules (the terrain and the feature finder must agree) ----

    /**
     * Whether a volcano or plateau centred at native pixel (cx, cz) with footprint {@code reach} stands clear of land:
     * the coarse map is at least 800 m deep one footprint out in all four directions, and it is not in an archipelago
     * sea. Volcanoes that overlapped continents, shelves or archipelagos merged into the land around them.
     */
    private static boolean clearOfLand(double cx, double cz, double reach, CoarseField coarse) {
        if (coarse.archipelago(cz, cx) > 0.3f) return false;
        for (int k = 0; k < 4; k++) {
            double a = k * Math.PI / 2 + 0.4;
            if (coarse.elevation(cz + Math.sin(a) * reach, cx + Math.cos(a) * reach) > -800f) return false;
        }
        return true;
    }

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
    private static double arcLandU(long h) { return 0.25 + 0.2 * unit(mix(h + 4)); }

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
        double u = islandU(r, radius, landU, ang, x, z, h);
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
            // Faded out well before the summit, where every direction meets (it drew a starburst there).
            double g = (0.45 * Math.abs(broad) + 0.55 * Math.abs(fine)) * smoothstep(0.3, 0.7, t);
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
     * Distance from an island's centre over its footprint radius, roughened as its outline is: the coast lies where this
     * equals {@code landU}. Ragged coastline: headlands and coves from ~150 m to ~3 km, up to ~1 km deep. The noise is
     * sampled by direction around the summit (on a circle sized to the coast, so its wavelengths are in km) and only
     * slowly with distance, and it fades in toward the coast, so along any line down from the summit the ground still
     * only falls: no closed hollows. {@code (x, z)} is the pixel in world space (for the outline noise).
     */
    private double islandU(double r, double radius, double landU, double ang, double x, double z, long h) {
        double u = roughRadius(r, radius, x, z, h) / radius / riftStretch(ang, h);
        return u + coastWobble(r, radius, landU, ang, h) * smoothstep(0.3 * landU, landU, u);
    }

    private double coastWobble(double r, double radius, double landU, double ang, long h) {
        double rc = landU * radius / KM / (2 * Math.PI);
        float cfa = (float) Math.cos(ang), sfa = (float) Math.sin(ang);
        double n = coastFine.GetNoise((float) rc * cfa + 37, (float) rc * sfa - 211, (float) (r / KM * 0.12) + (h & 0xFF));
        return 0.9 * KM / radius * n;
    }

    /**
     * Distance (native pixels, in the island's own frame) from an island's centre to its coast in direction {@code ang}:
     * where {@link #islandU} reaches {@code landU}, found by a few fixed-point steps. {@code toWorld} maps the frame to
     * world pixels (x, z), for the outline noise.
     */
    private double coastRadius(double ang, double cx, double cz, double radius, double landU, long h,
                               java.util.function.BiFunction<Double, Double, double[]> toWorld) {
        double rift = riftStretch(ang, h), ca = Math.cos(ang), sa = Math.sin(ang);
        double r = landU * radius * rift;
        double scale = KM * 4 / radius;
        for (int it = 0; it < 5; it++) {
            double[] w = toWorld.apply(cx + ca * r, cz + sa * r);
            float kx = (float) (w[0] / KM * scale) + (h & 0xFF), kz = (float) (w[1] / KM * scale) + ((h >>> 8) & 0xFF);
            double n = coastShape.GetNoise(kx, kz);
            r = (landU - coastWobble(r, radius, landU, ang, h)) * radius * rift / (1 + 0.14 * n);
        }
        return r;
    }

    /**
     * Rift zones: a shield volcano grows along two or three ridges running out from its summit (Hawaii's Puna and
     * Kona rifts), so an island reaches out in lobes and capes instead of being a round cone. Returns how far the
     * profile is stretched in direction {@code ang} (0.8 to 1.25); it depends on the direction only, so the ground
     * still falls all the way down every line from the summit.
     */
    private static double riftStretch(double ang, long h) {
        int n = unit(mix(h + 90)) < 0.45 ? 2 : 3;
        double a0 = unit(mix(h + 91)) * 2 * Math.PI, f = 0;
        for (int k = 0; k < n; k++) {
            double ak = a0 + k * 2 * Math.PI / n + (unit(mix(h + 92 + k)) - 0.5) * 0.9;
            double amp = 0.25 + 0.2 * unit(mix(h + 96 + k)), width = 0.3 + 0.25 * unit(mix(h + 99 + k));
            double da = Math.atan2(Math.sin(ang - ak), Math.cos(ang - ak));
            f = Math.max(f, amp * Math.exp(-sq(da / width)));
        }
        return 0.8 + f;
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
        double rb = len(px0, pz0);
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
            double dd = len(px, pz) * (1 + 0.18 * coastShape.GetNoise(kx, kz));
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
        double ds = len(a - dc, b) * (1 + 0.1 * coastShape.GetNoise(kx, kz));
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
    private static void volcano(Ctx ctx, double c, double floor) {
        ctx.lastC = c;
        if (c <= 0) return;
        double prevTop = ctx.top1;
        if (c > ctx.top1) { ctx.top2 = ctx.top1; ctx.top1 = c; } else if (c > ctx.top2) ctx.top2 = c;
        ctx.add = smoothUnion(ctx.add, c);
        // The volcano's surface as an absolute height (it rises from its own floor). Combined with the model's terrain
        // by a max in shape(), never added onto it: added, a volcano or plateau whose footprint reached a continental
        // slope or an archipelago piled kilometres onto shallow ground (Calvin, Oct 20).
        double a = floor + c;
        if (ctx.absTop == Double.NEGATIVE_INFINITY) {
            ctx.absTop = a;
        } else {
            double k = Math.min(300, 0.5 * Math.min(c, prevTop));
            double diff = Math.abs(a - ctx.absTop), m = Math.max(a, ctx.absTop);
            ctx.absTop = (k <= 0 || diff >= k) ? m : m + sq((k - diff) / k) * k * 0.25;
        }
    }

    /** Model-drawn island coasts: detail amplitude (m), and the heights (m) it fades out by below and above the sea. */
    private static final float ISLAND_COAST_M = 40f, ISLAND_COAST_DEEP = 260f, ISLAND_COAST_HIGH = 110f;
    /** Deepest model ground (m) that model-island edits reach: the coast detail and tuff rings. */
    private static final float ISLAND_EDIT_DEEP = 260f;

    /**
     * A model-drawn island's coast, roughened into coves, points and stacks from ~150 m to ~2 km (the model's own
     * coastline is smooth below ~1 km). Like the archipelago coasts: centred on the shoreline, fading out by
     * {@link #ISLAND_COAST_HIGH} on land and {@link #ISLAND_COAST_DEEP} under the sea, gently enough that the ground
     * still rises with the model's height. {@code w} fades it out toward the island's footprint edge.
     */
    private double islandCoast(double raw, int pi, int pj, double w) {
        float xk = pj / (float) KM, zk = pi / (float) KM;
        float x = xk + 0.45f * islandCoastWarpX.GetNoise(xk, zk), z = zk + 0.45f * islandCoastWarpZ.GetNoise(xk, zk);
        double near = raw >= 5 ? 1 - smoothstep(5, ISLAND_COAST_HIGH, raw) : smoothstep(-ISLAND_COAST_DEEP, -160, raw);
        return raw - ISLAND_COAST_M * w * near * islandCoast.GetNoise(x, z);
    }

    /**
     * An island's cone ({@code c} metres above its floor): a volcano like any other, or, when the model draws the
     * islands, its share of the island guide instead (combined the same way).
     */
    private void island(Ctx ctx, double c, double floor) {
        if (!modelIslands && !ctx.guideOnly) { volcano(ctx, c, floor); return; }
        ctx.lastC = c;
        if (c <= 0) return;
        ctx.guideC = Math.max(ctx.guideC, c);
        double a = floor + c;
        if (ctx.guideTop == Double.NEGATIVE_INFINITY) { ctx.guideTop = a; return; }
        double k = 300, diff = Math.abs(a - ctx.guideTop), m = Math.max(a, ctx.guideTop);
        ctx.guideTop = diff >= k ? m : m + sq((k - diff) / k) * k * 0.25;
    }

    /**
     * A model-drawn island's summit crater (Haleakala, Teide's Las Cañadas on a small scale): a flat-floored pit
     * {@code depth} metres deep and {@code cr} pixels across the floor's edge, with steep walls and a low raised rim,
     * cut into whatever summit the model drew (it is far too small for the model's low-frequency latent). Measured
     * from the guide's summit at distance {@code r}; it holds a crater lake.
     */
    private void crater(Ctx ctx, double r, double ang, double cr, double depth, long h) {
        if (!modelIslands && !ctx.guideOnly) return;      // the procedural cone has its own summit crater
        if (r > 2.6 * cr) return;
        // Oval (stretched along a rift), with a wobbly, scalloped wall: sampled by direction on a circle, no seam.
        double axis = unit(mix(h + 122)) * Math.PI, stretch = 1 + 0.6 * unit(mix(h + 123));
        double da = ang - axis;
        double ell = len(Math.cos(da) / stretch, Math.sin(da));
        float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
        double wob = 0.18 * coastShape.GetNoise(ca * 1.6f + (h & 0xFF), sa * 1.6f - 59)
                + 0.08 * coastShape.GetNoise(ca * 5f - 17, sa * 5f + (h & 0x7F));
        double u = r * ell / (cr * (1 + wob));
        if (u > 2) return;
        double cut = depth * (1 - smoothstep(0.7, 1.0, u)) - 0.15 * depth * Math.exp(-sq((u - 1.15) / 0.3));
        if (Math.abs(cut) > Math.abs(ctx.guideCrater)) ctx.guideCrater = cut;
    }

    // =========================================================================
    // Round 4: coastal cones (lava deltas were removed Oct 21; see git 9e44ebd for the volcano landforms)
    // =========================================================================

    /** Share of young islands (hotspot heads, the island behind them, plain arc islands) with tuff rings on the coast. */
    private static final double TUFF_CHANCE = 0.45;
    /** Kinds of coastal cone: a tuff ring breached toward the sea, a flooded one (a bay), a maar (holds a lake). */
    static final int TUFF_RING = 0, TUFF_FLOODED = 1, TUFF_MAAR = 2;

    /** One tuff ring or maar, from the island's hash: where it sits and its shape. */
    record TuffRing(double a, double offset, double radius, double rim, double floor, double gapW, int kind, long h) {}

    /** How many tuff rings or maars a young island has on its coast (0 to 3). */
    static int tuffCount(long h) {
        if (unit(mix(h + 130)) > TUFF_CHANCE) return 0;
        double u = unit(mix(h + 131));
        return 1 + (u < 0.4 ? 1 : 0) + (u < 0.15 ? 1 : 0);
    }

    /**
     * Tuff ring {@code n} of an island: its direction from the centre, offset from the coast (within half a km either
     * way), radius (250-600 m), rim height (25-60 m), floor (absolute metres) and gap.
     */
    static TuffRing tuffRing(long h, int n) {
        long t = mix(h + 133 + 7L * n);
        double a = unit(t) * 2 * Math.PI;
        double offset = (unit(mix(t + 1)) - 0.5) * 1.0 * KM;
        double radius = (0.25 + 0.35 * unit(mix(t + 2))) * KM;
        double rim = 25 + 35 * unit(mix(t + 3));
        double k = unit(mix(t + 4));
        int kind = k < 0.25 ? TUFF_MAAR : (k < 0.55 ? TUFF_FLOODED : TUFF_RING);
        double floor = switch (kind) {
            case TUFF_MAAR -> -(2 + 10 * unit(mix(t + 5)));
            case TUFF_FLOODED -> -(4 + 8 * unit(mix(t + 5)));
            default -> 3 + 5 * unit(mix(t + 5));
        };
        double gapW = 0.35 + 0.3 * unit(mix(t + 6));
        return new TuffRing(a, offset, radius, rim, floor, gapW, kind, t);
    }

    /** Tuff ring centres (in their island's frame), keyed by island hash and ring number. */
    private final java.util.concurrent.ConcurrentHashMap<Long, double[]> ringCentres = new java.util.concurrent.ConcurrentHashMap<>();

    /** Centre (frame coordinates) of tuff ring {@code t} (number {@code n}) of an island; cached. */
    private double[] ringCentre(TuffRing t, int n, double cx, double cz, double radius, double landU, long h,
                                java.util.function.BiFunction<Double, Double, double[]> toWorld) {
        return ringCentres.computeIfAbsent(mix(h ^ (0x7EF0L + n)), key -> {
            double d = coastRadius(t.a, cx, cz, radius, landU, h, toWorld) + t.offset;
            return new double[]{cx + Math.cos(t.a) * d, cz + Math.sin(t.a) * d};
        });
    }

    /**
     * Records the tuff rings of a young island at pixel (px, pz), in the frame the island is shaped in
     * (world space for hotspot islands, along/across the arc for island-arc ones), with its centre at (cx, cz);
     * {@code (wx, wz)} is the pixel in world space and {@code toWorld} maps the frame to world space. Everything is
     * placed against the island's real (roughened) coast, which the model follows.
     */
    private void coastalCones(Ctx ctx, double px, double pz, double cx, double cz, double wx, double wz,
                              double radius, double landU, long h, double peak,
                              java.util.function.BiFunction<Double, Double, double[]> toWorld) {
        if (peak < 100) return;
        double dx = px - cx, dz = pz - cz, r = len(dx, dz);
        double landR = landU * radius;
        if (r > landR * 1.6 + 2.5 * KM) return;
        int n = tuffCount(h);
        for (int i = 0; i < n; i++) {
            TuffRing t = tuffRing(h, i);
            double[] c = ringCentre(t, i, cx, cz, radius, landU, h, toWorld);
            double ex = px - c[0], ez = pz - c[1];
            double d = len(ex, ez);
            if (d > 2.3 * t.radius) continue;
            // A wobbly, slightly lobed ring: sampled by direction around its centre, so there is no seam.
            double pa = Math.atan2(ez, ex);
            float ca = (float) Math.cos(pa), sa = (float) Math.sin(pa);
            double wob = 0.1 * coastShape.GetNoise(ca * 1.4f + (t.h & 0xFF), sa * 1.4f - 31)
                    + 0.05 * coastShape.GetNoise(ca * 4f - 7, sa * 4f + (t.h & 0x7F));
            double u = d / (t.radius * (1 + wob));
            if (u >= ctx.tuffU) continue;
            ctx.tuffU = u;
            ctx.tuffR = t.radius * 30;
            ctx.tuffRim = t.rim;
            ctx.tuffFloor = t.floor;
            ctx.tuffGapW = t.gapW;
            ctx.tuffKind = t.kind;
            ctx.tuffDa = Math.atan2(Math.sin(pa - t.a), Math.cos(pa - t.a));
        }
    }

    private static boolean hasCoastEdit(Ctx ctx) {
        return ctx.tuffU < 2.3;
    }

    /**
     * Cuts the recorded tuff ring into ground {@code v} (absolute metres); leaves a surface tag in {@code ctx.coastTag}.
     * <ul>
     *   <li><b>Tuff rings and maars</b> (Diamond Head, Koko Crater, Hanauma Bay): a steep-walled crater with a flat floor
     *       and a low ring of tuff around it, built up from the shore. The rim stands 25-60 m above the ground (or the
     *       sea), with outer slopes of ~20 degrees. Tuff rings are breached on their seaward side: the dry ones drain
     *       through the gap, the flooded ones are bays open to the sea. Maars have no gap and hold a lake.</li>
     * </ul>
     */
    double coastEdit(double v, Ctx ctx) {
        ctx.coastTag = TAG_NONE;
        double out = v;
        if (ctx.tuffU < 2.3) {
            double u = ctx.tuffU, R = ctx.tuffR, F = ctx.tuffFloor;
            double crest = Math.max(v, 0) + ctx.tuffRim;
            double res;
            if (u >= 1) {
                // The outer flank, faded back to the old ground before the ring's reach ends (no step there).
                double flank = crest - (u - 1) * R * 0.38;
                if (flank < 0) flank *= 3;                    // below the sea the waves have cut it back steeply
                res = flank > v ? v + (flank - v) * (1 - smoothstep(1.15, 2.2, u)) : v;
            } else {
                res = F + (crest - F) * smoothstep(0.75, 1.0, u);
            }
            if (ctx.tuffKind != TUFF_MAAR && u > 0.6 && u < 2.2) {
                double g = Math.exp(-sq(ctx.tuffDa / ctx.tuffGapW));
                if (g > 0.01) {
                    double gap = ctx.tuffKind == TUFF_FLOODED ? F - 1 : F - 0.5 - (u - 0.62) * (F + 4) / 0.9;
                    double lower = u >= 1 ? Math.max(gap, v) : gap;   // outside the ring, never below the old ground
                    if (res > lower) res -= (res - lower) * g;
                }
            }
            if (u > 0.6 && u < 1.9 && (res > v + 1.5 || u < 1.0) && res > -14) ctx.coastTag = TAG_TUFF;
            out = res;
        }
        // Model-drawn islands are only edited down to ISLAND_EDIT_DEEP: fade out before it, so there is no step there.
        if (modelIslands) out = v + (out - v) * smoothstep(-ISLAND_EDIT_DEEP, -ISLAND_EDIT_DEEP + 60, v);
        return out;
    }

    /**
     * The surface (absolute metres) of the model-drawn islands at native pixel (pi, pj), or -infinity where there are
     * none: the same cones the islands would have here, which the pipeline blends into the model's coarse
     * conditioning and its low-frequency latent so the model draws each island itself, with its own ridges, valleys
     * and coasts. Placement uses the same rules as the terrain, so the reef rings land around the islands.
     */
    public double islandGuide(int pi, int pj, CoarseField coarse, Ctx ctx) {
        ctx.guideOnly = true;
        try {
            return shape(coarse.elevation(pi, pj), pi, pj, coarse, ctx);
        } finally {
            ctx.guideOnly = false;
        }
    }

    /**
     * sqrt(x² + z²). Math.hypot guards against overflow these distances never reach, at many times the cost: it was
     * a third of the inference thread's time (Calvin's profile, Oct 25).
     */
    private static double len(double x, double z) {
        return Math.sqrt(x * x + z * z);
    }

    /** A well-mixed map key for cell (gx, gz) (Long's own hash collides for cells along diagonals). */
    private static long cellKey(int gx, int gz) {
        return mix(((long) gx << 32) ^ (gz & 0xFFFFFFFFL));
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
        double r = len(px - ctx.opX[o], pz - ctx.opZ[o]);
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
        double re = len(ux, uz) * (1 + 0.12 * coastShape.GetNoise(kx * 0.6f + 31, kz * 0.6f))
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
        double gx = pux * ca - puz * sa, gz = pux * sa + puz * ca, gl = len(gx, gz);
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
