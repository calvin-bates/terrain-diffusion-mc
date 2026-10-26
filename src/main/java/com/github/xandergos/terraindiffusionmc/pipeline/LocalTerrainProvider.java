package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.hydrology.DeltaPlan;
import com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver;
import com.github.xandergos.terraindiffusionmc.hydrology.RiverMap;
import com.github.xandergos.terraindiffusionmc.hydrology.RiverService;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides terrain heightmap and biome data from the local WorldPipeline.
 *
 * <p>When scale=1 the pipeline is sampled at native model resolution directly.
 * When scale>1 the pipeline is sampled at native resolution and the result is
 * bilinearly upsampled, giving 1 block = nativeResolution/scale.
 */
public final class LocalTerrainProvider {
    private static final Logger LOG = LoggerFactory.getLogger(LocalTerrainProvider.class);

    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();

    private static final FastNoiseLite ELEV_NOISE_COARSE = makeFnl(99999, 1f/24f, 3, 2f, 0.5f);
    private static final FastNoiseLite ELEV_NOISE_FINE   = makeFnl(88888, 1f/6f,  2, 2f, 0.6f);
    /** Block-scale wobble of the shoreline (metres), so it doesn't follow the 30 m pixel grid. */
    private static final FastNoiseLite SHORE_NOISE = makeFnl(77773, 1f/22f, 2, 2f, 0.5f);
    /** Ground within this many metres of sea level is settled by {@link #settleShoreline}, fading out by the second. */
    private static final float SHORE_FULL_M = 2.5f, SHORE_FADE_M = 6f;
    /** Wobble of the shoreline (metres either way). */
    private static final float SHORE_WOBBLE_M = 0.9f;

    /** Wobble (in native pixels) for looking up ocean surface tags, so their edges don't follow the 30 m grid. */
    private static final FastNoiseLite TAG_JITTER_I = makeFnl(77771, 1f/9f, 2, 2f, 0.5f);
    private static final FastNoiseLite TAG_JITTER_J = makeFnl(77772, 1f/9f, 2, 2f, 0.5f);

    private static FastNoiseLite makeFnl(int seed, float freq, int oct, float lac, float gain) {
        FastNoiseLite fnl = new FastNoiseLite(seed);
        fnl.SetNoiseType(FastNoiseLite.NoiseType.Perlin);
        fnl.SetFrequency(freq);
        fnl.SetFractalType(FastNoiseLite.FractalType.FBm);
        fnl.SetFractalOctaves(oct);
        fnl.SetFractalLacunarity(lac);
        fnl.SetFractalGain(gain);
        return fnl;
    }

    public static final class HeightmapData {
        public final short[][] heightmap;
        public final short[][] biomeIds;
        public final int width;
        public final int height;
        /** Carved surface heights (top solid block Y) or {@link RiverCarver#NONE}; null if the tile has no rivers. */
        public short[][] surfaceY;
        /** Y of the highest inland water block or {@link RiverCarver#NONE}; null if the tile has no inland water. */
        public short[][] waterY;
        /** True where the water at that column is a lake rather than a river. */
        public boolean[][] waterIsLake;
        /** Glacier ice surface per column (metres, or {@link GlacierShaper#NO_ICE}); null if the tile has no glacier. */
        public short[][] iceTop;
        /** Glacier kind per column ({@link GlacierShaper#ICE_SNOW} and so on); null if the tile has no glacier. */
        public byte[][] glacier;
        /**
         * Ocean surface tag per column ({@link OceanFeatures#TAG_REEF} and so on: where coral, seagrass and limestone
         * go); null if the tile has none.
         */
        public byte[][] oceanTags;

        public HeightmapData(short[][] heightmap, short[][] biomeIds, int width, int height) {
            this.heightmap = heightmap;
            this.biomeIds  = biomeIds;
            this.width     = width;
            this.height    = height;
        }
    }

    private static record CacheKey(int i1, int j1, int i2, int j2) {}
    private static record CacheEntry(HeightmapData data, AtomicLong lastAccessed) {}

    private static final int MAX_CACHE_SIZE = 64;
    private static final int MAX_CACHE_SIZE_HEADROOM = 8;
    private static final Map<CacheKey, CacheEntry> CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_CLOCK = new AtomicLong();
    private static final Map<CacheKey, Future<HeightmapData>> PENDING = new ConcurrentHashMap<>();
    /** Single thread for pipeline.get() so MemoryTileStore is not accessed concurrently. */
    private static final ExecutorService INFERENCE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "terrain-diffusion-inference");
        t.setDaemon(true);
        return t;
    });

    private static volatile LocalTerrainProvider INSTANCE;
    private static long instanceSeed;

    private final WorldPipeline pipeline;

    private static final Object INIT_LOCK = new Object();

    private LocalTerrainProvider(long seed, PipelineModels models) {
        this.pipeline = new WorldPipeline(seed, models);
    }

    /** Seed is 64-bit world seed. Creates provider once; later worlds only update seed and clear caches (lightweight). */
    public static synchronized void init(long seed) {
        PipelineModels.awaitLoad();
        PipelineModels models = PipelineModels.getInstance();
        if (models == null) throw new IllegalStateException("PipelineModels failed to load");
        if (INSTANCE == null) {
            INSTANCE = new LocalTerrainProvider(seed, models);
            instanceSeed = seed;
        } else if (instanceSeed != seed) {
            INSTANCE.pipeline.setSeed(seed);
            instanceSeed = seed;
            cacheGeneration++;
            CACHE.clear();
            PENDING.clear();
            clearRivers();
            COAST_PLANS.clear();
        }
    }

    public static LocalTerrainProvider getInstance() {
        if (INSTANCE != null) return INSTANCE;

        synchronized(INIT_LOCK) {
            if (INSTANCE != null) return INSTANCE;
            PipelineModels.awaitLoad();
            PipelineModels models = PipelineModels.getInstance();
            if (models == null) throw new IllegalStateException("PipelineModels failed to load");
            INSTANCE = new LocalTerrainProvider(0L, models);
            instanceSeed = 0L;
        }

        return INSTANCE;
    }

    /**
     * The current seed's ocean features for seed-only queries (hydrothermal vents), or null if the provider is not
     * up yet or the features are off. Never blocks, so chunk decoration can call it.
     */
    public static OceanFeatures oceanFeatures() {
        LocalTerrainProvider p = INSTANCE;
        return p == null ? null : p.pipeline.oceanFeatures();
    }

    /**
     * Bumped whenever the tile cache is cleared (new seed, new world), so per-thread copies of tiles
     * ({@code TerrainDiffusionDensityFunction.TileCache}) know to reload.
     */
    private static volatile int cacheGeneration;

    public static int cacheGeneration() {
        return cacheGeneration;
    }

    public static void clearCache() {
        cacheGeneration++;
        CACHE.clear();
        PENDING.clear();
        clearRivers();
        COAST_PLANS.clear();
    }

    // =========================================================================
    // Explorer API — all pipeline calls routed through INFERENCE_EXECUTOR
    // =========================================================================

    /** Returns the current world seed used by the pipeline. */
    public static long getSeed() {
        return instanceSeed;
    }

    /**
     * Run elevation and climate inference on the inference thread.
     *
     * @return float[2]: [0] = elev (H*W), [1] = climate (5*H*W, or null)
     */
    public static float[][] getPipelineData(int i1, int j1, int i2, int j2, boolean withClimate) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.get(i1, j1, i2, j2, withClimate));
    }

    /**
     * Biome ids at native resolution for the explorer's Biomes map and {@code /terra-find biome}: the same classifier
     * (with the patch biomes) and shore pass the game uses, on the inference thread. See {@link BiomeMap}.
     */
    public static BiomeMap.Result getBiomeMap(int i1, int j1, int i2, int j2) throws Exception {
        return submitToInferenceThread(() -> BiomeMap.compute(getInstance().pipeline, i1, j1, i2, j2));
    }

    /**
     * Fetch a coarse tensor slice on the inference thread.
     * Coordinates are in coarse index units (1 unit = 256 native pixels).
     *
     * @return FloatTensor with shape [7, ci1-ci0, cj1-cj0]
     */
    public static FloatTensor getPipelineCoarse(int ci0, int cj0, int ci1, int cj1) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.getCoarseSlice(ci0, cj0, ci1, cj1));
    }

    /**
     * Change the world seed used by the pipeline and clear all caches.
     * Note: this also affects terrain generation for new Minecraft chunks.
     */
    public static void changeSeedFromExplorer(long newSeed) throws Exception {
        submitToInferenceThread(() -> {
            LocalTerrainProvider provider = getInstance();
            provider.pipeline.setSeed(newSeed);
            instanceSeed = newSeed;
            cacheGeneration++;
            CACHE.clear();
            PENDING.clear();
            clearRivers();
            COAST_PLANS.clear();
            return null;
        });
    }

    /** Ocean features centred in a native-pixel window (see {@link WorldPipeline#oceanLandmarks}). Inference thread. */
    public static java.util.List<OceanFeatures.Landmark> getOceanLandmarks(int i1, int j1, int i2, int j2) throws Exception {
        return submitToInferenceThread(() -> getInstance().pipeline.oceanLandmarks(i1, j1, i2, j2));
    }

    /**
     * The nearest ocean feature of a type to native pixel (pi, pj), searching squares of growing size up to
     * {@code maxRadiusPx}; null if none. Blocks while the coarse map is computed, so never call it on the server
     * thread.
     */
    public static OceanFeatures.Landmark findNearestLandmark(String type, double pi, double pj, int maxRadiusPx) throws Exception {
        int radius = Math.min(maxRadiusPx, 10_000);   // 300 km
        while (true) {
            int i1 = (int) Math.floor(pi) - radius, j1 = (int) Math.floor(pj) - radius;
            java.util.List<OceanFeatures.Landmark> found = getOceanLandmarks(i1, j1, i1 + 2 * radius, j1 + 2 * radius);
            OceanFeatures.Landmark best = null;
            double bestD = Double.MAX_VALUE;
            for (OceanFeatures.Landmark l : found) {
                if (!l.type().equals(type)) continue;
                double d = Math.hypot(l.i() - pi, l.j() - pj);
                if (d < bestD) { bestD = d; best = l; }
            }
            // Anything within the square's inner circle is certainly the nearest one.
            if (best != null && bestD <= radius) return best;
            if (radius >= maxRadiusPx) return best;
            radius = Math.min(maxRadiusPx, radius * 2);
        }
    }

    /** Result of {@link #findNearestBiome}: the spot (native pixels), windows classified, how far was searched. */
    public record BiomeSearch(boolean found, boolean inside, double i, double j, float elevM, int windows, int searchedRadiusPx) {}

    /** Side of the windows {@link #findNearestBiome} classifies (native pixels, 7.7 km). */
    private static final int BIOME_WINDOW_PX = 256;
    /** A spot this many native pixels (90 m) inside a patch on every side is preferred over the patch's edge. */
    private static final int BIOME_INSIDE_PX = 3;

    /**
     * Nearest native pixel classified as biome {@code id} (vanilla's {@code /locate biome} can't search Terra's layout).
     * Classifies 7.7 km windows ring by ring around the player with the game's classifier ({@link BiomeMap}), each on
     * the inference thread by itself, and stops once no unsearched ring can hold anything closer. Prefers pixels
     * {@link #BIOME_INSIDE_PX} inside the patch, so the teleport lands in the biome rather than on its edge.
     */
    public static BiomeSearch findNearestBiome(short id, double pi, double pj, int maxRings,
                                               java.util.function.IntConsumer progress) throws Exception {
        final int Wn = BIOME_WINDOW_PX, m = BIOME_INSIDE_PX;
        int ci = Math.floorDiv((int) Math.floor(pi), Wn), cj = Math.floorDiv((int) Math.floor(pj), Wn);
        double bestIn = Double.MAX_VALUE, bestAny = Double.MAX_VALUE;
        double inI = 0, inJ = 0, anyI = 0, anyJ = 0;
        float inE = 0f, anyE = 0f;
        int windows = 0, searched = 0;
        for (int k = 0; k <= maxRings; k++) {
            // Every pixel of ring k is at least (k - 1) windows from the player.
            double stopAt = Math.min(bestIn, bestAny + 2.0 * Wn);
            if ((k - 1) * (double) Wn > stopAt) break;
            for (int di = -k; di <= k; di++) {
                for (int dj = -k; dj <= k; dj++) {
                    if (Math.max(Math.abs(di), Math.abs(dj)) != k) continue;
                    int i1 = (ci + di) * Wn, j1 = (cj + dj) * Wn;
                    BiomeMap.Result r = getBiomeMap(i1 - m, j1 - m, i1 + Wn + m, j1 + Wn + m);
                    windows++;
                    int RW = Wn + 2 * m;
                    short[] b = r.biomes();
                    float[] e = r.elev();
                    for (int rr = m; rr < Wn + m; rr++) {
                        for (int cc = m; cc < Wn + m; cc++) {
                            if (b[rr * RW + cc] != id) continue;
                            double gi = i1 - m + rr + 0.5, gj = j1 - m + cc + 0.5;
                            double d = Math.hypot(gi - pi, gj - pj);
                            if (d < bestAny) { bestAny = d; anyI = gi; anyJ = gj; anyE = e[rr * RW + cc]; }
                            if (d < bestIn && inside(b, RW, rr, cc, m, id)) {
                                bestIn = d; inI = gi; inJ = gj; inE = e[rr * RW + cc];
                            }
                        }
                    }
                }
            }
            searched = (2 * k + 1) * Wn / 2;
            progress.accept(searched);
        }
        if (bestIn < Double.MAX_VALUE) return new BiomeSearch(true, true, inI, inJ, inE, windows, searched);
        if (bestAny < Double.MAX_VALUE) return new BiomeSearch(true, false, anyI, anyJ, anyE, windows, searched);
        return new BiomeSearch(false, false, 0, 0, 0f, windows, searched);
    }

    private static boolean inside(short[] b, int RW, int r, int c, int m, short id) {
        for (int dr = -m; dr <= m; dr++) for (int dc = -m; dc <= m; dc++) if (b[(r + dr) * RW + c + dc] != id) return false;
        return true;
    }

    /** Result of a reef or bank search: the spot found (native pixels) if any, spots checked, how far it reached. */
    public record ShallowSearch(boolean found, double i, double j, int checked, int searchedRadiusPx) {}

    /**
     * A nearby fringing reef, continental barrier reef or carbonate bank ({@code kind} as in
     * {@link OceanFeatures#shallowCandidate}). These depend on the model's own sea depths, so likely spots are picked
     * from the coarse map in squares of growing size around native pixel (pi, pj), and the terrain is then computed
     * around the nearest ones (a few seconds each the first time) until one really has the feature. Stops after
     * {@code maxChecks} spots or at {@code maxRadiusPx}. Never call this on the server thread.
     */
    public static ShallowSearch findNearestShallow(String kind, double pi, double pj, int maxRadiusPx, int maxChecks,
                                                   java.util.function.IntConsumer progress) throws Exception {
        byte tag = OceanFeatures.shallowTag(kind);
        final int step = 96, win = 160;
        java.util.Set<Long> done = new java.util.HashSet<>();
        int checked = 0;
        int radius = Math.min(500, maxRadiusPx);
        while (true) {
            final int i1 = (int) Math.floor(pi) - radius, j1 = (int) Math.floor(pj) - radius, r2 = 2 * radius;
            List<double[]> cands = submitToInferenceThread(
                    () -> getInstance().pipeline.shallowCandidates(kind, i1, j1, i1 + r2, j1 + r2, step));
            cands.sort(Comparator.comparingDouble(c -> Math.hypot(c[0] - pi, c[1] - pj)));
            for (double[] c : cands) {
                if (Math.hypot(c[0] - pi, c[1] - pj) > radius) break;   // the rest wait for a bigger square
                int wi = Math.floorDiv((int) c[0], win) * win, wj = Math.floorDiv((int) c[1], win) * win;
                if (!done.add(((long) wi << 32) ^ (wj & 0xFFFFFFFFL))) continue;
                byte[] tags = new byte[win * win];
                submitToInferenceThread(() -> getInstance().pipeline.get(wi, wj, wi + win, wj + win, false, tags));
                checked++;
                double best = Double.MAX_VALUE;
                int bi = -1, bj = -1;
                for (int k = 0; k < tags.length; k++) {
                    if (tags[k] != tag) continue;
                    int ti = wi + k / win, tj = wj + k % win;
                    double dd = Math.hypot(ti - pi, tj - pj);
                    if (dd < best) { best = dd; bi = ti; bj = tj; }
                }
                if (bi >= 0) return new ShallowSearch(true, bi + 0.5, bj + 0.5, checked, radius);
                if (checked >= maxChecks) return new ShallowSearch(false, 0, 0, checked, radius);
            }
            progress.accept(radius);
            if (radius >= maxRadiusPx) return new ShallowSearch(false, 0, 0, checked, radius);
            radius = Math.min(maxRadiusPx, radius * 2);
        }
    }

    /** A river delta: where it splits (head) and where its main channel meets the sea (mouth), in native pixels. */
    public record Delta(int headI, int headJ, int mouthI, int mouthJ, byte riverClass) {}

    /** Result of a delta search: the nearest delta (or null), how many were seen, and how far the search reached. */
    public record DeltaSearch(Delta nearest, int count, int searchedRadiusPx) {}

    /**
     * The nearest river delta to native pixel (pi, pj), searching the hydrology regions ring by ring out to
     * {@code maxRings} regions from the one containing the point. Deltas come from the river regions, so finding them
     * means computing those regions (seconds each), which is why the search is short-range. Each region is computed on
     * the inference thread on its own, so chunk generation keeps going in between; never call this on the server
     * thread. {@code progress} gets the radius (native pixels) searched so far after each ring.
     */
    public static DeltaSearch findNearestDelta(double pi, double pj, int maxRings, java.util.function.IntConsumer progress)
            throws Exception {
        int size = rivers().regionSize();
        int ci = Math.floorDiv((int) Math.floor(pi), size), cj = Math.floorDiv((int) Math.floor(pj), size);
        Delta best = null;
        double bestD = Double.MAX_VALUE;
        int count = 0, reached = 0;
        for (int ring = 0; ring <= maxRings; ring++) {
            for (int ri = ci - ring; ri <= ci + ring; ri++) {
                for (int rj = cj - ring; rj <= cj + ring; rj++) {
                    if (Math.max(Math.abs(ri - ci), Math.abs(rj - cj)) != ring) continue;
                    final int fri = ri, frj = rj;
                    RiverService.Region reg = submitToInferenceThread(() -> rivers().region(fri, frj,
                            (a, b, c, d) -> getInstance().pipeline.get(a, b, c, d, true)));
                    if (reg.elev == null) continue;
                    // Only the region's own square, not its margin, so no delta is counted twice.
                    for (int i = ri * size; i < (ri + 1) * size; i++) {
                        for (int j = rj * size; j < (rj + 1) * size; j++) {
                            int k = reg.index(i, j);
                            if (k < 0 || !RiverCarver.isDeltaHead(reg, k)) continue;
                            int m = RiverCarver.deltaMouth(reg, k);
                            if (m < 0) continue;
                            count++;
                            int mi = reg.i0 + m / reg.width, mj = reg.j0 + m % reg.width;
                            double d = Math.hypot(mi - pi, mj - pj);
                            if (d < bestD) { bestD = d; best = new Delta(i, j, mi, mj, reg.cls[k]); }
                        }
                    }
                }
            }
            reached = ring * size + size / 2;
            progress.accept(reached);
            // Everything within `reached` of the point has been searched (roughly; the point can sit anywhere in its
            // region, so this errs on the side of searching one more ring).
            if (best != null && bestD <= ring * size) break;
        }
        return new DeltaSearch(best, count, reached);
    }

    /**
     * The nearest volcano marker of {@code type} ("stratovolcano", "shield_volcano", "caldera", "lava_field", or
     * "volcano" for any of the first three) to native pixel (pi, pj), searching landform cells ring by ring out to
     * {@code maxRings}. Each new cell computes the model's terrain around its candidate (seconds), on the inference
     * thread by itself. Never call this on the server thread. {@code progress} gets the radius searched (pixels).
     */
    public static OceanFeatures.Landmark findNearestVolcano(String type, double pi, double pj, int maxRings,
                                                            java.util.function.IntConsumer progress) throws Exception {
        int span = Landforms.cellPx();
        int ci = Math.floorDiv((int) Math.floor(pi), span), cj = Math.floorDiv((int) Math.floor(pj), span);
        OceanFeatures.Landmark best = null;
        double bestD = Double.MAX_VALUE;
        for (int ring = 0; ring <= maxRings; ring++) {
            if ((ring - 1) * (double) span > bestD) break;
            for (int gi = ci - ring; gi <= ci + ring; gi++) {
                for (int gj = cj - ring; gj <= cj + ring; gj++) {
                    if (Math.max(Math.abs(gi - ci), Math.abs(gj - cj)) != ring) continue;
                    final int a = gi, b = gj;
                    List<OceanFeatures.Landmark> ms = submitToInferenceThread(() -> getInstance().pipeline.landforms().cellMarkers(a, b));
                    for (OceanFeatures.Landmark m : ms) {
                        boolean ok = type.equals("volcano") ? !m.type().equals("lava_field") : m.type().equals(type);
                        if (!ok) continue;
                        double d = Math.hypot(m.i() - pi, m.j() - pj);
                        if (d < bestD) { bestD = d; best = m; }
                    }
                }
            }
            progress.accept(ring * span + span / 2);
        }
        return best;
    }

    /** Change to a random new seed; returns the new seed value. */
    public static long generateRandomSeedFromExplorer() throws Exception {
        long newSeed = new Random().nextLong();
        changeSeedFromExplorer(newSeed);
        return newSeed;
    }

    private static <T> T submitToInferenceThread(Callable<T> task) throws Exception {
        return INFERENCE_EXECUTOR.submit(task).get();
    }

    /**
     * Fetch heightmap for a block-coordinate region (i=Z, j=X).
     * Coordinates are in block space; scale from config determines blocks per native pixel.
     * Blocks the calling thread until the tile is ready (one tile can take 10–30+ seconds).
     * If the caller is the server or a chunk worker, the game will stall until this returns.
     */
    public HeightmapData fetchHeightmap(int i1, int j1, int i2, int j2) {
        CacheKey key = new CacheKey(i1, j1, i2, j2);
        CacheEntry cached = CACHE.get(key);
        if (cached != null) {
            cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
            return cached.data;
        }

        return this.genHeightmap(key, i1, j1, i2, j2);
    }

    /** Returns the cached tile for this block region without generating it, or null. */
    public static HeightmapData peekHeightmap(int i1, int j1, int i2, int j2) {
        CacheEntry cached = CACHE.get(new CacheKey(i1, j1, i2, j2));
        if (cached == null) return null;
        cached.lastAccessed.set(CACHE_CLOCK.incrementAndGet());
        return cached.data;
    }

    // =========================================================================
    // Rivers and lakes
    // =========================================================================

    private static RiverService riverService;

    private static synchronized RiverService rivers() {
        if (riverService == null) {
            RiverMap.Params p = new RiverMap.Params();
            p.streamQ = TerrainDiffusionConfig.riverStreamThreshold();
            p.riverQ = TerrainDiffusionConfig.riverRiverThreshold();
            p.mainQ = TerrainDiffusionConfig.riverMainThreshold();
            p.maxElevM = TerrainDiffusionConfig.riverMaxHeightMetres();
            p.lakeMinArea = TerrainDiffusionConfig.lakeMinArea();
            p.lakeMinDepth = TerrainDiffusionConfig.lakeMinDepth();
            int margin = Math.max(TerrainDiffusionConfig.riverMargin(), p.flowSteps + 8);
            riverService = new RiverService(TerrainDiffusionConfig.riverRegionSize(), margin, p);
        }
        return riverService;
    }

    /**
     * Debug: the hydrology class of the native pixel under block (x, z): 0 none, 1 stream, 2 river, 3 main river, 4 lake,
     * or -1 if unavailable. Runs the region on the inference thread if it is not cached yet.
     */
    public static byte hydrologyClassAt(int blockX, int blockZ) {
        try {
            int scale = Math.max(1, WorldScaleManager.getCurrentScale());
            int pi = Math.floorDiv(blockZ, scale), pj = Math.floorDiv(blockX, scale);
            RiverService.Region reg = rivers().regionOfPixel(pi, pj, (a, b, c, d) -> getPipelineData(a, b, c, d, true));
            int k = reg.index(pi, pj);
            return k < 0 ? (byte) -1 : reg.cls[k];
        } catch (Exception e) {
            return (byte) -1;
        }
    }

    private static synchronized void clearRivers() {
        if (riverService != null) riverService.clear();
    }

    /** Carves rivers into a freshly built tile and records where inland water goes. Runs on the inference thread. */
    private void applyRivers(HeightmapData data, int i1, int j1, int scale) {
        if (!TerrainDiffusionConfig.riversEnabled()) return;
        // Open-ocean tiles never get water, so don't pay for hydrology there.
        boolean anyLand = false;
        for (short[] row : data.heightmap) {
            for (short v : row) {
                if (v > 0) { anyLand = true; break; }
            }
            if (anyLand) break;
        }
        if (!anyLand) return;
        try {
            RiverService svc = rivers();
            RiverService.Source source = (a, b, c, d) -> pipeline.get(a, b, c, d, true);
            RiverCarver.Result res = RiverCarver.apply(
                    data.heightmap, data.biomeIds, i1, j1, scale,
                    // The terrain density is (height - y), so a block is solid only strictly below the converted height:
                    // the top solid block is one lower. The river code works with real top blocks.
                    m -> HeightConverter.convertToMinecraftHeight((short) m, scale) - 1,
                    svc.regionSize(), svc.trust(),
                    (ri, rj) -> {
                        boolean cached = svc.isCached(ri, rj);
                        if (!cached) LOG.info("Terra rivers: computing region ({}, {})...", ri, rj);
                        long t0 = System.nanoTime();
                        long tp = cached ? 0L : TerraPerf.start();
                        RiverService.Region region = svc.region(ri, rj, source);
                        TerraPerf.end("riverRegion (part of tile.rivers)", tp);
                        if (!cached) {
                            LOG.info("Terra rivers: region ({}, {}) ready in {} ms", ri, rj,
                                    (System.nanoTime() - t0) / 1_000_000L);
                        }
                        return region;
                    });
            if (res != null) {
                saltFlats(res, data.biomeIds);
                data.surfaceY = res.surfaceY;
                data.waterY = res.waterY;
                data.waterIsLake = res.isLake;
            }
        } catch (RuntimeException e) {
            LOG.error("Terra rivers failed for tile at ({}, {}); generating it without rivers", j1, i1, e);
        }
    }

    /**
     * Salt flats (biomes plan v1.3): lakes the hydrology places in arid land (desert, rocky desert, painted mountains)
     * dry out into flat white ground at the lake's level instead of holding water (Bonneville, Uyuni). The hydrology
     * is unchanged (streams still end there); only the carver's output is: the lake column's ground rises to its
     * water level, the water goes, and the biome becomes salt flats. Lakes have one flat level, so the flat is level.
     */
    private static void saltFlats(RiverCarver.Result res, short[][] biomes) {
        if (res.isLake == null || res.waterY == null || res.surfaceY == null) return;
        int H = biomes.length, W = biomes[0].length;
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                if (!res.isLake[r][c] || res.waterY[r][c] == Short.MIN_VALUE) continue;
                short b = biomes[r][c];
                if (b != BiomeClassifier.DESERT && b != RegionalBiomes.ROCKY_DESERT && b != RegionalBiomes.PAINTED_MOUNTAINS) continue;
                res.surfaceY[r][c] = res.waterY[r][c];
                res.waterY[r][c] = Short.MIN_VALUE;
                res.isLake[r][c] = false;
                biomes[r][c] = RegionalBiomes.SALT_FLATS;
            }
        }
    }

    /**
     * River deltas whose lobe or channels may reach this tile (see {@link DeltaPlan}), from the regions that own their
     * heads; empty if rivers are off, the tile has no ground near sea level, or the hydrology is unavailable. Runs on
     * the inference thread, like the rest of tile building.
     */
    private List<DeltaPlan> deltaPlansNear(float[] elev, int i1, int j1, int H, int W, int scale) {
        if (!TerrainDiffusionConfig.riversEnabled()) return List.of();
        boolean near = false;
        for (float e : elev) if (e > -45f && e < 15f) { near = true; break; }
        if (!near) return List.of();
        try {
            RiverService svc = rivers();
            int size = svc.regionSize();
            RiverService.Source source = (a, b, c, d) -> pipeline.get(a, b, c, d, true);
            int pad = DeltaPlan.MAX_REACH_PX;
            int ri0 = Math.floorDiv(Math.floorDiv(i1, scale) - pad, size), ri1 = Math.floorDiv(Math.floorDiv(i1 + H - 1, scale) + pad, size);
            int rj0 = Math.floorDiv(Math.floorDiv(j1, scale) - pad, size), rj1 = Math.floorDiv(Math.floorDiv(j1 + W - 1, scale) + pad, size);
            float cx = (j1 + W / 2f) / scale, cz = (i1 + H / 2f) / scale, half = Math.max(H, W) / 2f / scale;
            List<DeltaPlan> out = new ArrayList<>();
            for (int ri = ri0; ri <= ri1; ri++) {
                for (int rj = rj0; rj <= rj1; rj++) {
                    for (DeltaPlan p : DeltaPlan.of(svc.region(ri, rj, source), ri, rj, size)) {
                        if (p.near(cx, cz, half + 4)) out.add(p);
                    }
                }
            }
            return out;
        } catch (RuntimeException e) {
            LOG.warn("Terra: deltas unavailable for tile at ({}, {}): {}", j1, i1, e.toString());
            return List.of();
        }
    }

    // ---- Spits, tombolos and cuspate forelands (CoastPlan) ----

    /** Coastal region plans, most recently used last. Plans are small (their distance grids are built on demand). */
    private static final Map<Long, CoastPlan> COAST_PLANS = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Long, CoastPlan> e) { return size() > 64; }
            });

    /** The plan of coastal region (ri, rj), computing it if needed (empty outside sand-landform zones). Inference thread. */
    private CoastPlan coastPlan(int ri, int rj) {
        long seed = instanceSeed;
        if (!CoastPlan.zoneActive(seed, ri, rj)) return CoastPlan.EMPTY;
        long key = ((long) ri << 32) ^ (rj & 0xFFFFFFFFL);
        CoastPlan p = COAST_PLANS.get(key);
        if (p != null) return p;
        long tp = TerraPerf.start();
        p = CoastPlan.build(seed, ri, rj, (a, b, c, d) -> pipeline.get(a, b, c, d, true), pipeline::archipelagoAt);
        TerraPerf.end("coastPlan (part of tile.shape)", tp);
        COAST_PLANS.put(key, p);
        return p;
    }

    /**
     * The coastal region plans (sand landforms) that may reach the native window [pi0, pi1) x [pj0, pj1), whose ground
     * (at any resolution) is {@code ground}; empty if ocean features are off or no ground in the window
     * is low enough for a plan to change ({@link CoastPlan#touches}). Runs on the inference thread.
     */
    private List<CoastPlan> coastPlansNear(float[][] ground, int pi0, int pj0, int pi1, int pj1) {
        if (!TerrainDiffusionConfig.oceanFeatures() || !CoastPlan.touches(ground)) return List.of();
        try {
            List<CoastPlan> out = new ArrayList<>();
            int R = CoastPlan.REACH_PX, S = CoastPlan.SIZE;
            for (int ri = Math.floorDiv(pi0 - R, S); ri <= Math.floorDiv(pi1 + R, S); ri++) {
                for (int rj = Math.floorDiv(pj0 - R, S); rj <= Math.floorDiv(pj1 + R, S); rj++) {
                    CoastPlan p = coastPlan(ri, rj);
                    if (!p.isEmpty()) out.add(p);
                }
            }
            return out;
        } catch (RuntimeException e) {
            LOG.warn("Terra: coastal landforms unavailable for ({}, {}): {}", pj0, pi0, e.toString());
            return List.of();
        }
    }

    /** Builds the sand landforms into block-resolution ground, in place. */
    private static void applyCoastPlans(List<CoastPlan> plans, float[][] grid, int bi0, int bj0, int scale) {
        if (plans.isEmpty()) return;
        for (int r = 0; r < grid.length; r++) {
            float z = (bi0 + r + 0.5f) / scale;
            float[] row = grid[r];
            for (int c = 0; c < row.length; c++) {
                float x = (bj0 + c + 0.5f) / scale;
                for (CoastPlan p : plans) row[c] = p.apply(x, z, row[c]);
            }
        }
    }

    /** A sand landform found near a point: kind (CoastPlan.SPIT, TOMBOLO, FORELAND) and where (native pixels). */
    public record CoastFeature(int kind, float i, float j) {}

    /**
     * Result of a sand landform search: the nearest one of the kind (or null), how many seen, how far it reached, how many
     * regions were searched, how many of them lie in sand-landform zones and how many of those have shore, and the spit
     * rejection counts summed over them ({@link CoastPlan#SPIT_REASONS}).
     */
    public record CoastSearch(CoastFeature nearest, int count, int searchedRadiusPx, int regions, int zoneRegions,
                              int shoreRegions, int[] spitStats) {}

    /**
     * The nearest spit, tombolo or cuspate foreland ({@code kind} as in {@link CoastPlan}) to native pixel (pi, pj),
     * searching coastal regions ring by ring out to {@code maxRings}. Each new region in a zone computes the terrain over
     * a 19 km window, so this is short-range. Never call this on the server thread.
     */
    public static CoastSearch findNearestCoastFeature(int kind, double pi, double pj, int maxRings,
                                                      java.util.function.IntConsumer progress) throws Exception {
        int S = CoastPlan.SIZE;
        int ci = Math.floorDiv((int) Math.floor(pi), S), cj = Math.floorDiv((int) Math.floor(pj), S);
        CoastFeature best = null;
        double bestD = Double.MAX_VALUE;
        int count = 0, reached = 0, regions = 0, zoneRegions = 0, shoreRegions = 0;
        int[] stats = new int[CoastPlan.SPIT_REASONS.length];
        for (int ring = 0; ring <= maxRings; ring++) {
            for (int ri = ci - ring; ri <= ci + ring; ri++) {
                for (int rj = cj - ring; rj <= cj + ring; rj++) {
                    if (Math.max(Math.abs(ri - ci), Math.abs(rj - cj)) != ring) continue;
                    final int fri = ri, frj = rj;
                    CoastPlan plan = submitToInferenceThread(() -> getInstance().coastPlan(fri, frj));
                    regions++;
                    if (CoastPlan.zoneActive(instanceSeed, fri, frj)) zoneRegions++;
                    if (plan.spitStats[0] > 0) shoreRegions++;
                    for (int q = 0; q < stats.length; q++) stats[q] += plan.spitStats[q];
                    List<float[]> spots = new ArrayList<>();
                    for (CoastPlan.Bar b : plan.bars) if (b.kind == kind) spots.add(new float[]{b.midZ(), b.midX()});
                    if (kind == CoastPlan.FORELAND) for (CoastPlan.Foreland f : plan.forelands) spots.add(new float[]{f.apexZ(), f.apexX()});
                    for (float[] sp : spots) {
                        count++;
                        double d = Math.hypot(sp[0] - pi, sp[1] - pj);
                        if (d < bestD) { bestD = d; best = new CoastFeature(kind, sp[0], sp[1]); }
                    }
                }
            }
            reached = ring * S + S / 2;
            progress.accept(reached);
            if (best != null && bestD <= ring * S) break;
        }
        return new CoastSearch(best, count, reached, regions, zoneRegions, shoreRegions, stats);
    }

    /** Native pixels around a river mouth within which the coast keeps no cliff (fading out to RIVER_MOUTH_FADE_PX). */
    private static final float RIVER_MOUTH_CLEAR_PX = 1.5f, RIVER_MOUTH_FADE_PX = 3.5f;

    /**
     * Where cliffs must not form because a river meets the sea there: 1 on and within about 45 m of a stream or river's
     * last 900 m to the sea, fading to 0 by about 100 m, per tile column; null if rivers are off, the tile is all sea
     * or all land well above the shore, or the hydrology is unavailable. Uses the same river regions as the carver,
     * which are computed from the terrain before coast shaping, so there is no feedback.
     */
    private float[] riverMouthMask(float[] elev, int i1, int j1, int H, int W, int scale) {
        if (!TerrainDiffusionConfig.riversEnabled()) return null;
        boolean land = false, sea = false;
        for (float e : elev) {
            if (e > 0f) land = true; else sea = true;
            if (land && sea) break;
        }
        if (!land) return null;
        try {
            int pad = (int) Math.ceil(RIVER_MOUTH_FADE_PX) + 1;
            int pi0 = Math.floorDiv(i1, scale) - pad, pj0 = Math.floorDiv(j1, scale) - pad;
            int pi1 = Math.floorDiv(i1 + H - 1, scale) + pad, pj1 = Math.floorDiv(j1 + W - 1, scale) + pad;
            int nh = pi1 - pi0 + 1, nw = pj1 - pj0 + 1;
            boolean[] mouth = new boolean[nh * nw];
            boolean any = false;
            RiverService svc = rivers();
            RiverService.Source source = (a, b, c, d) -> pipeline.get(a, b, c, d, true);
            for (int r = 0; r < nh; r++) {
                for (int c = 0; c < nw; c++) {
                    RiverService.Region reg = svc.regionOfPixel(pi0 + r, pj0 + c, source);
                    int k = reg.index(pi0 + r, pj0 + c);
                    if (k >= 0 && RiverCarver.isNearMouth(reg, k)) { mouth[r * nw + c] = true; any = true; }
                }
            }
            if (!any) return null;
            // Fade with distance from the nearest mouth pixel, at native resolution.
            float[] fade = new float[nh * nw];
            int R = (int) Math.ceil(RIVER_MOUTH_FADE_PX);
            for (int r = 0; r < nh; r++) {
                for (int c = 0; c < nw; c++) {
                    float best = Float.MAX_VALUE;
                    for (int dr = -R; dr <= R; dr++) {
                        int rr = r + dr;
                        if (rr < 0 || rr >= nh) continue;
                        for (int dc = -R; dc <= R; dc++) {
                            int cc = c + dc;
                            if (cc < 0 || cc >= nw || !mouth[rr * nw + cc]) continue;
                            best = Math.min(best, dr * dr + dc * dc);
                        }
                    }
                    float d = best == Float.MAX_VALUE ? Float.MAX_VALUE : (float) Math.sqrt(best);
                    float t = Math.max(0f, Math.min(1f, (d - RIVER_MOUTH_CLEAR_PX) / (RIVER_MOUTH_FADE_PX - RIVER_MOUTH_CLEAR_PX)));
                    fade[r * nw + c] = 1f - t * t * (3f - 2f * t);
                }
            }
            // Bilinear onto the tile's blocks (pixel centres at (p + 0.5) * scale).
            float[] out = new float[H * W];
            for (int r = 0; r < H; r++) {
                float fr = (i1 + r + 0.5f) / scale - 0.5f - pi0;
                int r0 = Math.max(0, Math.min(nh - 2, (int) Math.floor(fr)));
                float tr = Math.max(0f, Math.min(1f, fr - r0));
                for (int c = 0; c < W; c++) {
                    float fc = (j1 + c + 0.5f) / scale - 0.5f - pj0;
                    int c0 = Math.max(0, Math.min(nw - 2, (int) Math.floor(fc)));
                    float tc = Math.max(0f, Math.min(1f, fc - c0));
                    out[r * W + c] = (fade[r0 * nw + c0] * (1 - tc) + fade[r0 * nw + c0 + 1] * tc) * (1 - tr)
                            + (fade[(r0 + 1) * nw + c0] * (1 - tc) + fade[(r0 + 1) * nw + c0 + 1] * tc) * tr;
                }
            }
            return out;
        } catch (RuntimeException e) {
            LOG.warn("Terra: river mouths unavailable for the coast at ({}, {}): {}", j1, i1, e.toString());
            return null;
        }
    }

    /** How far (native pixels) river distances are measured for the river forests; farther counts as infinite. */
    private static final int RIVER_FOREST_REACH_PX =
            (int) Math.ceil(RegionalBiomes.FLOODPLAIN_PX + RegionalBiomes.FLOODPLAIN_JITTER_PX) + 1;

    /**
     * Distances (native pixels) from each block of a tile to the nearest channel (stream, river or main river) and
     * to the nearest river or main river, from the same river regions the carver uses, for the riverside and floodplain forests
     * ({@link RegionalBiomes#riverForest}); null when rivers are off, the tile has no land, or no river is near.
     * Distances are measured between native pixel centres and interpolated bilinearly onto the blocks.
     */
    float[][] riverDistances(float[] elev, int i1, int j1, int H, int W, int scale) {
        if (!TerrainDiffusionConfig.riversEnabled()) return null;
        boolean land = false;
        for (float e : elev) if (e > 0f) { land = true; break; }
        if (!land) return null;
        try {
            int R = RIVER_FOREST_REACH_PX;
            int pi0 = Math.floorDiv(i1, scale) - R - 1, pj0 = Math.floorDiv(j1, scale) - R - 1;
            int pi1 = Math.floorDiv(i1 + H - 1, scale) + R + 1, pj1 = Math.floorDiv(j1 + W - 1, scale) + R + 1;
            int nh = pi1 - pi0 + 1, nw = pj1 - pj0 + 1;
            byte[] cls = new byte[nh * nw];
            boolean any = false;
            RiverService svc = rivers();
            RiverService.Source source = (a, b, c, d) -> pipeline.get(a, b, c, d, true);
            for (int r = 0; r < nh; r++) {
                for (int c = 0; c < nw; c++) {
                    RiverService.Region reg = svc.regionOfPixel(pi0 + r, pj0 + c, source);
                    int k = reg.index(pi0 + r, pj0 + c);
                    byte v = k >= 0 ? reg.cls[k] : 0;
                    if (v >= com.github.xandergos.terraindiffusionmc.hydrology.RiverMap.STREAM
                            && v <= com.github.xandergos.terraindiffusionmc.hydrology.RiverMap.MAIN) {
                        cls[r * nw + c] = v;
                        any = true;
                    }
                }
            }
            if (!any) return null;
            // Squared distances stamped outward from each river pixel (rivers are sparse, so this is cheap).
            int[] d2r = new int[nh * nw], d2m = new int[nh * nw];
            java.util.Arrays.fill(d2r, Integer.MAX_VALUE);
            java.util.Arrays.fill(d2m, Integer.MAX_VALUE);
            for (int r = 0; r < nh; r++) {
                for (int c = 0; c < nw; c++) {
                    byte v = cls[r * nw + c];
                    if (v == 0) continue;
                    boolean main = v >= com.github.xandergos.terraindiffusionmc.hydrology.RiverMap.RIVER;
                    for (int a = Math.max(0, r - R); a <= Math.min(nh - 1, r + R); a++) {
                        for (int b = Math.max(0, c - R); b <= Math.min(nw - 1, c + R); b++) {
                            int d2 = (a - r) * (a - r) + (b - c) * (b - c), k = a * nw + b;
                            if (d2 < d2r[k]) d2r[k] = d2;
                            if (main && d2 < d2m[k]) d2m[k] = d2;
                        }
                    }
                }
            }
            float[] dr = new float[nh * nw], dm = new float[nh * nw];
            final float FAR = 1e6f;
            for (int k = 0; k < nh * nw; k++) {
                dr[k] = d2r[k] == Integer.MAX_VALUE ? FAR : (float) Math.sqrt(d2r[k]);
                dm[k] = d2m[k] == Integer.MAX_VALUE ? FAR : (float) Math.sqrt(d2m[k]);
            }
            float[] outR = new float[H * W], outM = new float[H * W];
            for (int r = 0; r < H; r++) {
                float fr = (i1 + r + 0.5f) / scale - 0.5f - pi0;
                int r0 = Math.max(0, Math.min(nh - 2, (int) Math.floor(fr)));
                float tr = Math.max(0f, Math.min(1f, fr - r0));
                for (int c = 0; c < W; c++) {
                    float fc = (j1 + c + 0.5f) / scale - 0.5f - pj0;
                    int c0 = Math.max(0, Math.min(nw - 2, (int) Math.floor(fc)));
                    float tc = Math.max(0f, Math.min(1f, fc - c0));
                    outR[r * W + c] = bilerp(dr, nw, r0, c0, tr, tc);
                    outM[r * W + c] = bilerp(dm, nw, r0, c0, tr, tc);
                }
            }
            return new float[][]{outR, outM};
        } catch (RuntimeException e) {
            LOG.warn("Terra: river distances unavailable for the biomes at ({}, {}): {}", j1, i1, e.toString());
            return null;
        }
    }

    private static float bilerp(float[] a, int w, int r0, int c0, float tr, float tc) {
        return (a[r0 * w + c0] * (1 - tc) + a[r0 * w + c0 + 1] * tc) * (1 - tr)
                + (a[(r0 + 1) * w + c0] * (1 - tc) + a[(r0 + 1) * w + c0 + 1] * tc) * tr;
    }

    private HeightmapData genHeightmap(CacheKey key, int i1, int j1, int i2, int j2) {
        int scale = WorldScaleManager.getCurrentScale();
        FutureTask<HeightmapData> task = new FutureTask<>(() -> {
            long computedWindowCountBefore = pipeline.getTotalComputedWindowCount();
            long tBuild = System.nanoTime();
            HeightmapData data = scale <= 1
                    ? handle1x(i1, j1, i2, j2)
                    : handleUpsampled(i1, j1, i2, j2, scale);
            long tr = TerraPerf.start();
            applyRivers(data, i1, j1, Math.max(1, scale));
            noWaterOnLava(data);
            noWaterUnderIce(data);
            TerraPerf.end("tile.rivers", tr);
            long computedWindowCountAfter = pipeline.getTotalComputedWindowCount();

            long newlyComputedWindowCount = computedWindowCountAfter - computedWindowCountBefore;
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.info(
                    "Terrain Diffusion ({}) finished generating region {}x{} ({} newly computed windows) in {} ms",
                    OnnxModel.getResolvedInferenceProvider(), regionWidth, regionHeight, newlyComputedWindowCount,
                    (System.nanoTime() - tBuild) / 1_000_000L);
            CACHE.put(key, new CacheEntry(data, new AtomicLong(CACHE_CLOCK.incrementAndGet())));
            evictLruTo(MAX_CACHE_SIZE);
            PENDING.remove(key);
            return data;
        });
        Future<HeightmapData> existing = PENDING.putIfAbsent(key, task);
        FutureTask<HeightmapData> toRun = (existing == null) ? task : (FutureTask<HeightmapData>) existing;
        if (existing == null) {
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.info(
                    "Terrain Diffusion ({}) uncached region requested: ({}, {})-({}, {}) size {}x{}",
                    OnnxModel.getResolvedInferenceProvider(), j1, i1, j2, i2, regionWidth, regionHeight);
            INFERENCE_EXECUTOR.submit(toRun);
        }
        long tw = TerraPerf.start();
        try {
            return toRun.get();
        } catch (Exception e) {
            PENDING.remove(key);
            throw new RuntimeException("Terrain tile failed: " + key, e);
        } finally {
            TerraPerf.end("tile.wait", tw);
        }
    }

    private static void evictLruTo(int maxSize) {
        int headroomHalf = MAX_CACHE_SIZE_HEADROOM / 2;
        if (CACHE.size() > maxSize + headroomHalf) {
            CACHE.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessed.get()))
                .limit(MAX_CACHE_SIZE_HEADROOM)
                .map(Map.Entry::getKey)
                .forEach(CACHE::remove);
        }
    }

    // =========================================================================
    // Scale == 1: block coords == native pixel coords
    // =========================================================================

    private HeightmapData handle1x(int i1, int j1, int i2, int j2) {
        int H = i2 - i1, W = j2 - j1;

        // Margin: one pixel for the slope, five for the shore pass (cliffs look 3 pixels out, wave-cut platforms 5; see
        // CoastShaper).
        final int M = CoastShaper.PLATFORM_LOOK_PX;
        float[] elevMargin = pipeline.get(i1 - M, j1 - M, i2 + M, j2 + M, false)[0];
        float[][] elevMargin2D = to2D(elevMargin, H + 2 * M, W + 2 * M);
        List<CoastPlan> sand = coastPlansNear(elevMargin2D, i1 - M, j1 - M, i2 + M, j2 + M);
        applyCoastPlans(sand, elevMargin2D, i1 - M, j1 - M, 1);
        float[] elevPadded = cropFlat(elevMargin2D, M - 1, M - 1, H + 2, W + 2, H + 2 * M, W + 2 * M);
        byte[] tags = new byte[H * W];
        float[][] out = pipeline.get(i1, j1, i2, j2, true, tags);
        float[] elevFlat = out[0];
        float[] climate  = out[1];
        if (!sand.isEmpty()) {
            for (int r = 0; r < H; r++) System.arraycopy(elevMargin2D[r + M], M, elevFlat, r * W, W);
        }

        float[][] rd = riverDistances(elevFlat, i1, j1, H, W, 1);
        short[] biomeFlat = BiomeClassifier.classify(elevFlat, climate, i1, j1, elevPadded, H, W, NATIVE_RESOLUTION, getSeed(),
                rd == null ? null : rd[0], rd == null ? null : rd[1]);
        CoastShaper.apply(elevMargin2D, M, M, H, W, 1, i1, j1, climate, elevFlat, biomeFlat,
                riverMouthMask(elevFlat, i1, j1, H, W, 1));
        DeltaShaper.apply(deltaPlansNear(elevFlat, i1, j1, H, W, 1), elevFlat, biomeFlat, climate, i1, j1, H, W, 1);
        landformsRoundC(elevFlat, biomeFlat, climate, elevMargin2D, M, M, i1 - M, j1 - M, climate, i1, j1, H, W,
                i1, j1, H, W, 1, true, rd == null ? null : rd[0]);
        byte[][] blockTags1 = blockTags(tags, i1, j1, H, W, i1, j1, H, W, 1);
        mushroomFields(blockTags1, elevFlat, biomeFlat, W);
        volcanicBiomes(blockTags1, elevFlat, biomeFlat, W);
        GlacierShaper.Result ice = TerrainDiffusionConfig.glaciers()
                ? GlacierShaper.apply(elevMargin2D, M, M, H, W, 1, i1, j1, climate, elevFlat, biomeFlat, blockTags1)
                : null;
        HeightmapData data = buildHeightmapData(elevFlat, biomeFlat, H, W);
        data.oceanTags = blockTags1;
        if (ice != null) {
            data.iceTop = ice.iceTop();
            data.glacier = ice.kind();
        }
        return data;
    }

    // =========================================================================
    // Scale > 1: pipeline at native res → bilinear upsample to block res
    // =========================================================================

    /** Native pixels of margin around an upsampled tile (see {@link #handleUpsampled}). */
    private static final int PAD_PX = CoastShaper.PLATFORM_LOOK_PX + 3;

    private HeightmapData handleUpsampled(int i1, int j1, int i2, int j2, int scale) {
        int H = i2 - i1, W = j2 - j1;
        float pixelSizeM = NATIVE_RESOLUTION / scale;

        // Convert block coords to native pixel coords
        int i1n = Math.floorDiv(i1, scale);
        int j1n = Math.floorDiv(j1, scale);
        int i2n = -Math.floorDiv(-i2, scale);
        int j2n = -Math.floorDiv(-j2, scale);

        // 8-pixel native padding. Bicubic resampling clamps at the array edge, which bends the outer ~1.5 native
        // pixels; settling the shoreline averages over 1 more; the shore pass looks up to 5 native pixels outside the
        // tile (wave-cut platforms), so it needs clean values out there for neighbouring tiles to agree. (The resize is
        // translation-consistent, so the padding does not change the values inside.)
        int i1p = i1n - PAD_PX, j1p = j1n - PAD_PX;
        int i2p = i2n + PAD_PX, j2p = j2n + PAD_PX;
        int nH = i2p - i1p, nW = j2p - j1p;

        byte[] tagsNative = new byte[nH * nW];
        long t0 = TerraPerf.start();
        float[][] out = pipeline.get(i1p, j1p, i2p, j2p, true, tagsNative);
        TerraPerf.end("tile.model", t0);
        t0 = TerraPerf.start();
        float[] elevNativeFlat    = out[0];
        float[] climateNativeFlat = out[1];

        // Bilinear upsample elevation: (nH, nW) → (nH*scale, nW*scale)
        float[][] elevNative2D = to2D(elevNativeFlat, nH, nW);
        // Bicubic, not bilinear: bilinear leaves creases along the 30 m pixel grid, which shows as square shores and terraces.
        float[][] elevUp = LaplacianUtils.bicubicResize(elevNative2D, nH * scale, nW * scale);
        settleShoreline(elevUp, i1p * scale, j1p * scale, scale);
        applyCoastPlans(coastPlansNear(elevUp, i1p, j1p, i2p, j2p), elevUp, i1p * scale, j1p * scale, scale);

        // Crop offsets in the upsampled array
        int padUp   = PAD_PX * scale;
        int offsetI = i1 - i1n * scale;
        int offsetJ = j1 - j1n * scale;
        int cropI1  = padUp + offsetI;
        int cropJ1  = padUp + offsetJ;

        float[] elevSmooth = cropFlat(elevUp, cropI1,     cropJ1,     H,   W,   nH * scale, nW * scale);
        float[] elevPadded = cropFlat(elevUp, cropI1 - 1, cropJ1 - 1, H+2, W+2, nH * scale, nW * scale);

        // Upsample climate (6, nH, nW) → (6, H, W)
        float[] climate = upsampleClimate(climateNativeFlat, nH, nW, cropI1, cropJ1, H, W, scale, nH * scale, nW * scale);

        float[] elevOut = addElevationNoise(elevSmooth, elevPadded, i1, j1, H, W, pixelSizeM);
        TerraPerf.end("tile.shape", t0);

        t0 = TerraPerf.start();
        float[][] rd = riverDistances(elevSmooth, i1, j1, H, W, scale);
        short[] biomeFlat = BiomeClassifier.classify(elevSmooth, climate, i1, j1, elevPadded, H, W, pixelSizeM, getSeed(),
                rd == null ? null : rd[0], rd == null ? null : rd[1]);
        TerraPerf.end("tile.biomes", t0);
        t0 = TerraPerf.start();
        CoastShaper.apply(elevUp, cropI1, cropJ1, H, W, scale, i1, j1, climate, elevOut, biomeFlat,
                riverMouthMask(elevOut, i1, j1, H, W, scale));
        marineTerraces(elevOut, biomeFlat, i1, j1, H, W, scale);
        TerraPerf.end("tile.coast", t0);
        t0 = TerraPerf.start();
        DeltaShaper.apply(deltaPlansNear(elevOut, i1, j1, H, W, scale), elevOut, biomeFlat, climate, i1, j1, H, W, scale);
        landformsRoundC(elevOut, biomeFlat, climate, elevUp, cropI1, cropJ1, i1p * scale, j1p * scale,
                climateNativeFlat, i1p, j1p, nH, nW, i1, j1, H, W, scale, true, rd == null ? null : rd[0]);
        byte[][] blockTagsUp = blockTags(tagsNative, i1p, j1p, nH, nW, i1, j1, H, W, scale);
        mushroomFields(blockTagsUp, elevOut, biomeFlat, W);
        volcanicBiomes(blockTagsUp, elevOut, biomeFlat, W);
        GlacierShaper.Result ice = TerrainDiffusionConfig.glaciers()
                ? GlacierShaper.apply(elevUp, cropI1, cropJ1, H, W, scale, i1, j1, climate, elevOut, biomeFlat, blockTagsUp)
                : null;
        HeightmapData data = buildHeightmapData(elevOut, biomeFlat, H, W);
        data.oceanTags = blockTagsUp;
        if (ice != null) {
            data.iceTop = ice.iceTop();
            data.glacier = ice.kind();
        }
        TerraPerf.end("tile.deltas", t0);
        return data;
    }

    /**
     * The dry-land and karst landforms of v1.4 round C on a tile's final ground: badlands mesas, canyons, alluvial fans,
     * desert dunes and hoodoos ({@link AridLandforms}, {@link com.github.xandergos.terraindiffusionmc.hydrology.FanPlan})
     * where it is arid, karst towers ({@link KarstTowers}) where it is hot and wet. {@code full} = false (the explorer's
     * biome map) runs only the passes that change biomes.
     *
     * @param up        smooth upsampled ground with the tile's margin; {@code up[0][0]} is world block (upI0, upJ0)
     * @param climNative climate at native pixels, {@code nH x nW} from native pixel (ni0, nj0), channels stacked
     * @param riverDist  per tile column, native pixels to the nearest river, stream or lake channel (far: large), or
     *                   null: the passes that raise or terrace ground fade out near channels, so the river carver (which
     *                   keeps the hydrology's levels) never has to cut a slot through them
     */
    public void landformsRoundC(float[] elev, short[] biomes, float[] climate, float[][] up, int cropI, int cropJ,
                                int upI0, int upJ0, float[] climNative, int ni0, int nj0, int nH, int nW,
                                int i1, int j1, int H, int W, int scale, boolean full, float[] riverDist) {
        if (!TerrainDiffusionConfig.aridLandforms() || climate == null || climNative == null) return;
        int HW = H * W, nN = nH * nW;
        if (climNative.length < 3 * nN) return;
        boolean arid = false, tropic = false;
        for (int k = 0; k < nN; k++) {
            float t = climNative[k], p = climNative[2 * nN + k];
            if (p < 450f && t > 2f) arid = true;
            if (t >= 19f && p >= 1000f) tropic = true;
        }
        float mpb = NATIVE_RESOLUTION / scale;
        AridLandforms.Field field = new AridLandforms.Field() {
            @Override public float ground(float bi, float bj) {
                return bilinearClamped(up, bi - upI0 - 0.5f, bj - upJ0 - 0.5f);
            }
            @Override public float temp(float bi, float bj) {
                return nativeClimate(climNative, nH, nW, 0, bi / scale - 0.5f - ni0, bj / scale - 0.5f - nj0);
            }
            @Override public float precip(float bi, float bj) {
                return nativeClimate(climNative, nH, nW, 2, bi / scale - 0.5f - ni0, bj / scale - 0.5f - nj0);
            }
        };
        try {
            if (arid) {
                AridLandforms.mesas(elev, biomes, climate, riverDist, i1, j1, H, W, mpb);
                if (TerrainDiffusionConfig.riversEnabled()) {
                    AridLandforms.canyons(elev, biomes, canyonSegments(i1, j1, H, W, scale), i1, j1, H, W, scale);
                    alluvialFans(elev, biomes, riverDist, i1, j1, H, W, scale);
                }
                if (full) {
                    AridLandforms.dunes(elev, biomes, riverDist, up, cropI, cropJ, i1, j1, H, W, mpb);
                    AridLandforms.hoodoos(elev, biomes, riverDist, field, i1, j1, H, W, scale);
                }
            }
            if (tropic && KarstTowers.mayApply(i1, j1, H, W, mpb)) {
                KarstTowers.RiverNear rivers = TerrainDiffusionConfig.riversEnabled() ? this::riverNear : null;
                KarstTowers.apply(elev, biomes, field, rivers, i1, j1, H, W, scale);
            }
        } catch (RuntimeException e) {
            LOG.warn("Terra: round C landforms unavailable for ({}, {}): {}", j1, i1, e.toString());
        }
    }

    private static float bilinearClamped(float[][] a, float r, float c) {
        int h = a.length, w = a[0].length;
        r = Math.max(0f, Math.min(h - 1.001f, r));
        c = Math.max(0f, Math.min(w - 1.001f, c));
        int r0 = (int) r, c0 = (int) c;
        float fr = r - r0, fc = c - c0;
        return (a[r0][c0] * (1 - fc) + a[r0][c0 + 1] * fc) * (1 - fr) + (a[r0 + 1][c0] * (1 - fc) + a[r0 + 1][c0 + 1] * fc) * fr;
    }

    /** Channel {@code ch} of a native climate grid at fractional native pixel (fi, fj) (pixel centres at integers). */
    private static float nativeClimate(float[] clim, int nH, int nW, int ch, float fi, float fj) {
        fi = Math.max(0f, Math.min(nH - 1.001f, fi));
        fj = Math.max(0f, Math.min(nW - 1.001f, fj));
        int r0 = (int) fi, c0 = (int) fj, o = ch * nH * nW;
        float fr = fi - r0, fc = fj - c0;
        return (clim[o + r0 * nW + c0] * (1 - fc) + clim[o + r0 * nW + c0 + 1] * fc) * (1 - fr)
                + (clim[o + (r0 + 1) * nW + c0] * (1 - fc) + clim[o + (r0 + 1) * nW + c0 + 1] * fc) * fr;
    }

    /** River segments (channel pixel to the next downstream) whose canyons may reach the tile's blocks. */
    private List<AridLandforms.Segment> canyonSegments(int i1, int j1, int H, int W, int scale) {
        int R = AridLandforms.CANYON_REACH_PX + 1;
        int pi0 = Math.floorDiv(i1, scale) - R, pj0 = Math.floorDiv(j1, scale) - R;
        int pi1 = Math.floorDiv(i1 + H - 1, scale) + R, pj1 = Math.floorDiv(j1 + W - 1, scale) + R;
        RiverService svc = rivers();
        RiverService.Source source = (a, b, c, d) -> pipeline.get(a, b, c, d, true);
        List<AridLandforms.Segment> out = new ArrayList<>();
        Map<Long, float[]> shapes = new java.util.HashMap<>();
        for (int i = pi0; i <= pi1; i++) {
            for (int j = pj0; j <= pj1; j++) {
                RiverService.Region reg = svc.regionOfPixel(i, j, source);
                int k = reg.index(i, j);
                if (k < 0 || reg.temp == null || reg.precip == null) continue;
                byte cls = reg.cls[k];
                if (cls < RiverMap.STREAM || cls > RiverMap.MAIN) continue;
                int d = reg.downstream[k];
                if (d < 0) continue;
                int di = reg.i0 + d / reg.width, dj = reg.j0 + d % reg.width;
                float wA = AridLandforms.canyonWeight(i, j, reg.temp[k], reg.precip[k]);
                float wB = AridLandforms.canyonWeight(di, dj, reg.temp[d], reg.precip[d]);
                if (wA <= 0f && wB <= 0f) continue;
                float sA = reg.size != null ? reg.size[k] : cls;
                byte cd = reg.cls[d];
                float sB = cd >= RiverMap.STREAM && cd <= RiverMap.MAIN ? (reg.size != null ? reg.size[d] : cd) : sA;
                float[] shA = canyonShape(shapes, reg, i, j, AridLandforms.halfWidth(sA, i, j));
                RiverService.Region regB = svc.regionOfPixel(di, dj, source);
                float[] shB = canyonShape(shapes, regB, di, dj, AridLandforms.halfWidth(sB, di, dj));
                out.add(new AridLandforms.Segment(j + 0.5f, i + 0.5f, dj + 0.5f, di + 0.5f, reg.surface[k], reg.surface[d],
                        shA[1], shB[1], wA, wB, shA[0], shB[0], (long) i * 0x9E3779B97F4A7C15L ^ (long) j * 0xC2B2AE3D27D4EB4FL));
            }
        }
        return out;
    }

    /**
     * A canyon's rim (m) and half-width (native pixels) at channel pixel (i, j): the highest model ground within
     * {@link AridLandforms#CANYON_MAX_HW_PX} (rings of 16 samples every 2 pixels), and the radius where the ground
     * first comes within a quarter of the canyon's depth of that rim (so the canyon spans the valley it is cut into),
     * at least the channel's own half-width {@code minHw}. From the region that owns the pixel (its margin covers the
     * rings), so it is the same in every tile.
     */
    private static float[] canyonShape(Map<Long, float[]> cache, RiverService.Region reg, int i, int j, float minHw) {
        long key = ((long) i << 32) ^ (j & 0xFFFFFFFFL);
        float[] v = cache.get(key);
        if (v != null) return v;
        int k0 = reg.index(i, j);
        float level = k0 >= 0 && reg.elev != null ? reg.elev[k0] : 0f;
        int maxR = (int) AridLandforms.CANYON_MAX_HW_PX;
        float[] ring = new float[maxR / 2 + 1];
        float rim = level;
        for (int q = 1; q <= maxR / 2; q++) {
            int rr = 2 * q;
            float m = -Float.MAX_VALUE;
            for (int a = 0; a < 16; a++) {
                double ang = a * Math.PI / 8;
                int ii = i + (int) Math.round(rr * Math.sin(ang)), jj = j + (int) Math.round(rr * Math.cos(ang));
                int k = reg.index(ii, jj);
                if (k >= 0 && reg.elev != null) m = Math.max(m, reg.elev[k]);
            }
            ring[q] = m == -Float.MAX_VALUE ? level : m;
            rim = Math.max(rim, ring[q]);
        }
        rim = Math.min(rim, level + AridLandforms.CANYON_MAX_DEPTH_M);
        float need = level + 0.75f * (rim - level);
        float hw = AridLandforms.CANYON_MAX_HW_PX;
        for (int q = 1; q < ring.length; q++) {
            if (ring[q] >= need) { hw = 2 * q * 1.15f; break; }
        }
        hw = Math.max(minHw, Math.min(AridLandforms.CANYON_MAX_HW_PX, hw));
        v = new float[]{rim, hw};
        cache.put(key, v);
        return v;
    }

    /** Raises the alluvial fans near the tile ({@link com.github.xandergos.terraindiffusionmc.hydrology.FanPlan}). */
    private void alluvialFans(float[] elev, short[] biomes, float[] riverDist, int i1, int j1, int H, int W, int scale) {
        int pad = com.github.xandergos.terraindiffusionmc.hydrology.FanPlan.MAX_RADIUS_PX + 2;
        int pi0 = Math.floorDiv(i1, scale), pj0 = Math.floorDiv(j1, scale);
        int pi1 = Math.floorDiv(i1 + H - 1, scale) + 1, pj1 = Math.floorDiv(j1 + W - 1, scale) + 1;
        RiverService svc = rivers();
        int size = svc.regionSize();
        RiverService.Source source = (a, b, c, d) -> pipeline.get(a, b, c, d, true);
        float cx = (pj0 + pj1) / 2f, cz = (pi0 + pi1) / 2f, half = Math.max(pi1 - pi0, pj1 - pj0) / 2f;
        List<com.github.xandergos.terraindiffusionmc.hydrology.FanPlan> fans = new ArrayList<>();
        for (int ri = Math.floorDiv(pi0 - pad, size); ri <= Math.floorDiv(pi1 + pad, size); ri++) {
            for (int rj = Math.floorDiv(pj0 - pad, size); rj <= Math.floorDiv(pj1 + pad, size); rj++) {
                // Only regions the rivers already need nearby, or that the fans can reach.
                for (var f : com.github.xandergos.terraindiffusionmc.hydrology.FanPlan.of(svc.region(ri, rj, source), ri, rj, size)) {
                    if (f.near(cx, cz, half + 2)) fans.add(f);
                }
            }
        }
        if (fans.isEmpty()) return;
        for (int r = 0; r < H; r++) {
            float z = (i1 + r + 0.5f) / scale;
            for (int c = 0; c < W; c++) {
                float x = (j1 + c + 0.5f) / scale;
                int k = r * W + c;
                float e = elev[k], best = 0f;
                for (var f : fans) best = Math.max(best, f.raise(x, z, e));
                // The fan's channels stay at the river's level in a shallow trench (a fanhead trench), not a slot.
                if (riverDist != null) best *= AridLandforms.awayFromChannel(riverDist[k], 1f, 4f);
                if (best <= 0f) continue;
                elev[k] = e + best;
                if (best > 1.5f && !BiomeClassifier.isOceanBiome(biomes[k])) biomes[k] = RegionalBiomes.ALLUVIAL_FAN;
            }
        }
    }

    /** Whether a river, stream or lake runs within {@code px} native pixels of native point (i, j). */
    private boolean riverNear(float i, float j, float px) {
        RiverService svc = rivers();
        RiverService.Source source = (a, b, c, d) -> pipeline.get(a, b, c, d, true);
        int r = (int) Math.ceil(px);
        int ci = (int) Math.floor(i), cj = (int) Math.floor(j);
        for (int a = ci - r; a <= ci + r; a++) {
            for (int b = cj - r; b <= cj + r; b++) {
                if ((a + 0.5f - i) * (a + 0.5f - i) + (b + 0.5f - j) * (b + 0.5f - j) > px * px) continue;
                RiverService.Region reg = svc.regionOfPixel(a, b, source);
                int k = reg.index(a, b);
                if (k >= 0 && reg.cls[k] >= RiverMap.STREAM) return true;
            }
        }
        return false;
    }

    /** Raised marine terraces ({@link MarineTerraces}) on the tile's ground, where a terrace zone meets the sea. */
    private void marineTerraces(float[] elevOut, short[] biomes, int i1, int j1, int H, int W, int scale) {
        if (!TerrainDiffusionConfig.riversEnabled() || !TerrainDiffusionConfig.oceanFeatures()) return;
        float mpb = WorldPipelineModelConfig.nativeResolution() / scale;
        if (!MarineTerraces.mayApply(i1, j1, H, W, mpb)) return;
        boolean land = false;
        for (float e : elevOut) if (e > 5f) { land = true; break; }
        if (!land) return;
        try {
            // Sea distance on the native pixels under the tile (plus one for interpolation), from the hydrology regions.
            int pi0 = Math.floorDiv(i1, scale) - 1, pj0 = Math.floorDiv(j1, scale) - 1;
            int pi1 = Math.floorDiv(i1 + H - 1, scale) + 2, pj1 = Math.floorDiv(j1 + W - 1, scale) + 2;
            int nh = pi1 - pi0 + 1, nw = pj1 - pj0 + 1;
            float[] nat = new float[nh * nw];
            RiverService svc = rivers();
            RiverService.Source source = (a, b, c, d) -> pipeline.get(a, b, c, d, true);
            boolean near = false;
            for (int r = 0; r < nh; r++) {
                for (int c = 0; c < nw; c++) {
                    RiverService.Region reg = svc.regionOfPixel(pi0 + r, pj0 + c, source);
                    int k = reg.index(pi0 + r, pj0 + c);
                    float d = k >= 0 ? reg.seaDistance()[k] : RiverService.SEA_DIST_CAP;
                    nat[r * nw + c] = d;
                    if (d < MarineTerraces.REACH_PX) near = true;
                }
            }
            if (!near) return;
            float[] dist = new float[H * W];
            for (int r = 0; r < H; r++) {
                float fz = (i1 + r + 0.5f) / scale - 0.5f - pi0;
                int z0 = (int) Math.floor(fz);
                float tz = fz - z0;
                for (int c = 0; c < W; c++) {
                    float fx = (j1 + c + 0.5f) / scale - 0.5f - pj0;
                    int x0 = (int) Math.floor(fx);
                    float tx = fx - x0;
                    float a = nat[z0 * nw + x0], b = nat[z0 * nw + x0 + 1], cc = nat[(z0 + 1) * nw + x0], dd = nat[(z0 + 1) * nw + x0 + 1];
                    dist[r * W + c] = (a * (1 - tx) + b * tx) * (1 - tz) + (cc * (1 - tx) + dd * tx) * tz;
                }
            }
            MarineTerraces.apply(elevOut, dist, biomes, i1, j1, H, W, mpb);
        } catch (RuntimeException e) {
            LOG.warn("Terra: marine terraces unavailable for ({}, {}): {}", j1, i1, e.toString());
        }
    }

    /**
     * Settles ground lying within a few metres of sea level, at block resolution, in place. Where the shore is very
     * flat, the model's own one-pixel bumps decide what is land, and after upsampling each 30 m pixel just above the
     * sea became its own sandy islet: rows of same-sized ovals spaced on the pixel grid (Calvin, Oct 19, X 113455
     * Z -40212). Near sea level the height is now taken from the ground averaged over one native pixel either side
     * (so a lone pixel above or below the water joins its surroundings), plus a little block-scale noise so the
     * shoreline wanders instead of following the grid. Ground more than {@link #SHORE_FADE_M} from sea level, and
     * any evenly sloping shore, is unchanged. Each block depends only on the upsampled ground within one native
     * pixel of it, which the tile margin covers, so neighbouring tiles agree.
     *
     * @param up    upsampled elevation (metres), changed in place
     * @param bi0   world block Z of {@code up[0][*]}
     * @param bj0   world block X of {@code up[*][0]}
     */
    private static void settleShoreline(float[][] up, int bi0, int bj0, int scale) {
        int h = up.length, w = up[0].length, R = scale;
        boolean any = false;
        for (float[] row : up) {
            for (float v : row) if (Math.abs(v) < SHORE_FADE_M) { any = true; break; }
            if (any) break;
        }
        if (!any) return;
        // Box average over (2R+1)^2 blocks, separable, clamped at the array edges (inside the margin).
        float[][] tmp = new float[h][w], avg = new float[h][w];
        for (int r = 0; r < h; r++) {
            float[] row = up[r];
            for (int c = 0; c < w; c++) {
                float s = 0f; int n = 0;
                for (int q = Math.max(0, c - R); q <= Math.min(w - 1, c + R); q++) { s += row[q]; n++; }
                tmp[r][c] = s / n;
            }
        }
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                float s = 0f; int n = 0;
                for (int q = Math.max(0, r - R); q <= Math.min(h - 1, r + R); q++) { s += tmp[q][c]; n++; }
                avg[r][c] = s / n;
            }
        }
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                float e = up[r][c];
                float a = Math.abs(e);
                if (a >= SHORE_FADE_M) continue;
                float t = a <= SHORE_FULL_M ? 1f : 1f - smooth01((a - SHORE_FULL_M) / (SHORE_FADE_M - SHORE_FULL_M));
                float settled = avg[r][c] + SHORE_WOBBLE_M * SHORE_NOISE.GetNoise(bj0 + c, bi0 + r);
                up[r][c] = e + (settled - e) * t;
            }
        }
    }

    private static float smooth01(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    /**
     * Ocean surface tags for the tile's blocks from the native-pixel tags (top-left native pixel (ni0, nj0), nH x nW),
     * or null if there are none. Each block takes the tag of the native pixel under it, looked up at a point that
     * wobbles smoothly by up to ~0.7 pixel, so tagged patches get natural edges. The wobble depends only on the
     * block position, so neighbouring tiles agree.
     */
    /**
     * Mushroom islands (biomes plan v1.3): land the ocean features tagged {@link OceanFeatures#TAG_MUSHROOM} becomes
     * mushroom fields, shores included (vanilla's mushroom islands have mycelium to the water).
     */
    static void mushroomFields(byte[][] tags, float[] elev, short[] biomes, int W) {
        if (tags == null) return;
        for (int r = 0; r < tags.length; r++) {
            for (int c = 0; c < W; c++) {
                int k = r * W + c;
                if (tags[r][c] == OceanFeatures.TAG_MUSHROOM && elev[k] >= 0f) biomes[k] = BiomeClassifier.MUSHROOM_FIELDS;
            }
        }
    }

    /**
     * Volcanoes (biomes plan v1.4): the bare upper slopes and craters become the volcano biome, lava flows and lava
     * lakes the lava fields biome, from the landform stage's tags. Snowy ground keeps its snowy biome (a snow cap).
     */
    static void volcanicBiomes(byte[][] tags, float[] elev, short[] biomes, int W) {
        if (tags == null) return;
        for (int r = 0; r < tags.length; r++) {
            for (int c = 0; c < W; c++) {
                int k = r * W + c;
                byte t = tags[r][c];
                if (elev[k] < 0f || t < Landforms.TAG_LAVA) continue;
                boolean cone = t == Landforms.TAG_VOLCANO || t == Landforms.TAG_CRATER || t == Landforms.TAG_HOT_CRATER;
                boolean lava = t == Landforms.TAG_LAVA || t == Landforms.TAG_LAVA_CHANNEL || t == Landforms.TAG_LAVA_LAKE;
                if (!cone && !lava) continue;
                if (CoastShaper.isSnowy(biomes[k]) && t != Landforms.TAG_LAVA_LAKE && t != Landforms.TAG_LAVA_CHANNEL) continue;
                biomes[k] = cone ? RegionalBiomes.VOLCANO : RegionalBiomes.LAVA_FIELDS;
            }
        }
    }

    /**
     * Live lava sits in active craters and along fresh flows: the hydrology must not put a lake or a stream there
     * (an active crater is a closed hollow it would fill).
     */
    private static void noWaterOnLava(HeightmapData data) {
        if (data.oceanTags == null || data.waterY == null) return;
        // Per column (the active crater's tag covers its rim, so the whole crater lake the hydrology made goes), so
        // neighbouring tiles agree.
        for (int r = 0; r < data.height; r++) {
            for (int c = 0; c < data.width; c++) {
                byte t = data.oceanTags[r][c];
                if (t != Landforms.TAG_LAVA_LAKE && t != Landforms.TAG_LAVA_CHANNEL && t != Landforms.TAG_HOT_CRATER) continue;
                data.waterY[r][c] = RiverCarver.NONE;
                if (data.waterIsLake != null) data.waterIsLake[r][c] = false;
            }
        }
    }

    /** Rivers and lakes run under a glacier's ice, not through it: no water where ice lies (the river leaves the snout). */
    private static void noWaterUnderIce(HeightmapData data) {
        if (data.glacier == null || data.waterY == null) return;
        for (int r = 0; r < data.height; r++) {
            for (int c = 0; c < data.width; c++) {
                byte g = data.glacier[r][c];
                if (g != GlacierShaper.ICE_SNOW && g != GlacierShaper.ICE_BARE) continue;
                data.waterY[r][c] = RiverCarver.NONE;
                if (data.waterIsLake != null) data.waterIsLake[r][c] = false;
            }
        }
    }

    private static byte[][] blockTags(byte[] tags, int ni0, int nj0, int nH, int nW, int i1, int j1, int H, int W, int scale) {
        boolean any = false;
        for (byte t : tags) if (t != OceanFeatures.TAG_NONE) { any = true; break; }
        if (!any) return null;
        byte[][] out = new byte[H][W];
        any = false;
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int bi = i1 + r, bj = j1 + c;
                float fi = (bi + 0.5f) / scale - 0.5f + 0.7f * TAG_JITTER_I.GetNoise(bj, bi);
                float fj = (bj + 0.5f) / scale - 0.5f + 0.7f * TAG_JITTER_J.GetNoise(bj, bi);
                int ni = Math.max(0, Math.min(nH - 1, Math.round(fi) - ni0));
                int nj = Math.max(0, Math.min(nW - 1, Math.round(fj) - nj0));
                byte t = tags[ni * nW + nj];
                out[r][c] = t;
                any |= t != OceanFeatures.TAG_NONE;
            }
        }
        return any ? out : null;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private float[] addElevationNoise(float[] elevSmooth, float[] elevPadded,
                                       int i1, int j1, int H, int W, float pixelSizeM) {
        float[] slopeGradient = sobelGradient(elevPadded, H + 2, W + 2, H, W);
        float[] elevOut = elevSmooth.clone();
        float normFactor = 40f * pixelSizeM / NATIVE_RESOLUTION;
        float ampC = 100f * pixelSizeM / NATIVE_RESOLUTION;
        float ampF = 70f  * pixelSizeM / NATIVE_RESOLUTION;

        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float e = elevSmooth[idx];
                if (e < 0f) continue;

                float grad = slopeGradient[idx];
                float sf = Math.min(1f, grad / normFactor);
                sf = sf * sf * (float) Math.sqrt(sf);

                float nx = j1 + c, ny = i1 + r;
                elevOut[idx] = e
                        + ELEV_NOISE_COARSE.GetNoise(nx, ny) * ampC * sf
                        + ELEV_NOISE_FINE.GetNoise(nx, ny)   * ampF * sf;
            }
        }
        return elevOut;
    }

    private static float[] sobelGradient(float[] padded, int pH, int pW, int H, int W) {
        final float[] SOBEL_X = {-1,0,1, -2,0,2, -1,0,1};
        final float[] SOBEL_Y = {-1,-2,-1, 0,0,0, 1,2,1};
        float[] result = new float[H * W];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float dx = 0, dy = 0;
                for (int k = 0; k < 9; k++) {
                    float v = padded[(r + k/3) * pW + (c + k%3)];
                    dx += v * SOBEL_X[k];
                    dy += v * SOBEL_Y[k];
                }
                dx /= 8f; dy /= 8f;
                result[r * W + c] = (float) Math.sqrt(dx * dx + dy * dy);
            }
        }
        return result;
    }

    private static float[] upsampleClimate(float[] climNative, int nH, int nW,
                                            int cropI1, int cropJ1, int H, int W,
                                            int scale, int upH, int upW) {
        if (climNative == null) return null;
        // Every channel the pipeline gives (temperature, seasonality, precipitation, its CV, lapse rate, coastal current).
        int nCh = climNative.length / (nH * nW);
        float[] result = new float[nCh * H * W];
        for (int ch = 0; ch < nCh; ch++) {
            if (ch == 4) continue;   // the lapse rate isn't used at block resolution
            float[][] chNative = new float[nH][nW];
            for (int r = 0; r < nH; r++)
                System.arraycopy(climNative, ch * nH * nW + r * nW, chNative[r], 0, nW);
            float[][] chUp = LaplacianUtils.bilinearResize(chNative, upH, upW);
            for (int r = 0; r < H; r++)
                for (int c = 0; c < W; c++)
                    result[ch * H * W + r * W + c] = chUp[cropI1 + r][cropJ1 + c];
        }
        return result;
    }

    private static float[] cropFlat(float[][] src, int r0, int c0, int H, int W, int srcH, int srcW) {
        float[] out = new float[H * W];
        for (int r = 0; r < H; r++) {
            int sr = Math.max(0, Math.min(srcH - 1, r0 + r));
            for (int c = 0; c < W; c++)
                out[r * W + c] = src[sr][Math.max(0, Math.min(srcW - 1, c0 + c))];
        }
        return out;
    }

    private static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];
        for (int r = 0; r < H; r++) System.arraycopy(flat, r * W, a[r], 0, W);
        return a;
    }

    private static HeightmapData buildHeightmapData(float[] elevFlat, short[] biomeFlat, int H, int W) {
        short[][] heightmap = new short[H][W];
        short[][] biomeIds  = new short[H][W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                float e = elevFlat[r * W + c];
                heightmap[r][c] = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                biomeIds[r][c]  = biomeFlat[r * W + c];
            }
        return new HeightmapData(heightmap, biomeIds, W, H);
    }
}
