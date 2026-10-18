package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver;
import com.github.xandergos.terraindiffusionmc.hydrology.RiverMap;
import com.github.xandergos.terraindiffusionmc.hydrology.RiverService;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.world.HeightConverter;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
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
            CACHE.clear();
            PENDING.clear();
            clearRivers();
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

    public static void clearCache() {
        CACHE.clear();
        PENDING.clear();
        clearRivers();
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
            CACHE.clear();
            PENDING.clear();
            clearRivers();
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
                        RiverService.Region region = svc.region(ri, rj, source);
                        if (!cached) {
                            LOG.info("Terra rivers: region ({}, {}) ready in {} ms", ri, rj,
                                    (System.nanoTime() - t0) / 1_000_000L);
                        }
                        return region;
                    });
            if (res != null) {
                data.surfaceY = res.surfaceY;
                data.waterY = res.waterY;
                data.waterIsLake = res.isLake;
            }
        } catch (RuntimeException e) {
            LOG.error("Terra rivers failed for tile at ({}, {}); generating it without rivers", j1, i1, e);
        }
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

    private HeightmapData genHeightmap(CacheKey key, int i1, int j1, int i2, int j2) {
        int scale = WorldScaleManager.getCurrentScale();
        FutureTask<HeightmapData> task = new FutureTask<>(() -> {
            long computedWindowCountBefore = pipeline.getTotalComputedWindowCount();
            HeightmapData data = scale <= 1
                    ? handle1x(i1, j1, i2, j2)
                    : handleUpsampled(i1, j1, i2, j2, scale);
            applyRivers(data, i1, j1, Math.max(1, scale));
            long computedWindowCountAfter = pipeline.getTotalComputedWindowCount();

            long newlyComputedWindowCount = computedWindowCountAfter - computedWindowCountBefore;
            int regionWidth = j2 - j1;
            int regionHeight = i2 - i1;
            LOG.info(
                    "Terrain Diffusion ({}) finished generating region {}x{} ({} newly computed windows)",
                    OnnxModel.getResolvedInferenceProvider(), regionWidth, regionHeight, newlyComputedWindowCount);
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
        try {
            return toRun.get();
        } catch (Exception e) {
            PENDING.remove(key);
            throw new RuntimeException("Terrain tile failed: " + key, e);
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

        // Three pixels of margin: one for the slope, three for the shore pass (see CoastShaper).
        float[] elevMargin = pipeline.get(i1 - 3, j1 - 3, i2 + 3, j2 + 3, false)[0];
        float[][] elevMargin2D = to2D(elevMargin, H + 6, W + 6);
        float[] elevPadded = cropFlat(elevMargin2D, 2, 2, H + 2, W + 2, H + 6, W + 6);
        float[][] out = pipeline.get(i1, j1, i2, j2, true);
        float[] elevFlat = out[0];
        float[] climate  = out[1];

        short[] biomeFlat = BiomeClassifier.classify(elevFlat, climate, i1, j1, elevPadded, H, W, NATIVE_RESOLUTION);
        CoastShaper.apply(elevMargin2D, 3, 3, H, W, 1, i1, j1, climate, elevFlat, biomeFlat,
                riverMouthMask(elevFlat, i1, j1, H, W, 1));
        return buildHeightmapData(elevFlat, biomeFlat, H, W);
    }

    // =========================================================================
    // Scale > 1: pipeline at native res → bilinear upsample to block res
    // =========================================================================

    private HeightmapData handleUpsampled(int i1, int j1, int i2, int j2, int scale) {
        int H = i2 - i1, W = j2 - j1;
        float pixelSizeM = NATIVE_RESOLUTION / scale;

        // Convert block coords to native pixel coords
        int i1n = Math.floorDiv(i1, scale);
        int j1n = Math.floorDiv(j1, scale);
        int i2n = -Math.floorDiv(-i2, scale);
        int j2n = -Math.floorDiv(-j2, scale);

        // 5-pixel native padding. Bicubic resampling clamps at the array edge, which bends the outer ~1.5 native
        // pixels; the shore pass looks up to 3 native pixels outside the tile, so it needs clean values out there
        // for neighbouring tiles to agree. (The resize is translation-consistent, so the padding does not change
        // the values inside.)
        int i1p = i1n - 5, j1p = j1n - 5;
        int i2p = i2n + 5, j2p = j2n + 5;
        int nH = i2p - i1p, nW = j2p - j1p;

        float[][] out = pipeline.get(i1p, j1p, i2p, j2p, true);
        float[] elevNativeFlat    = out[0];
        float[] climateNativeFlat = out[1];

        // Bilinear upsample elevation: (nH, nW) → (nH*scale, nW*scale)
        float[][] elevNative2D = to2D(elevNativeFlat, nH, nW);
        // Bicubic, not bilinear: bilinear leaves creases along the 30 m pixel grid, which shows as square shores and terraces.
        float[][] elevUp = LaplacianUtils.bicubicResize(elevNative2D, nH * scale, nW * scale);

        // Crop offsets in the upsampled array
        int padUp   = 5 * scale;
        int offsetI = i1 - i1n * scale;
        int offsetJ = j1 - j1n * scale;
        int cropI1  = padUp + offsetI;
        int cropJ1  = padUp + offsetJ;

        float[] elevSmooth = cropFlat(elevUp, cropI1,     cropJ1,     H,   W,   nH * scale, nW * scale);
        float[] elevPadded = cropFlat(elevUp, cropI1 - 1, cropJ1 - 1, H+2, W+2, nH * scale, nW * scale);

        // Upsample climate (4, nH, nW) → (4, H, W)
        float[] climate = upsampleClimate(climateNativeFlat, nH, nW, cropI1, cropJ1, H, W, scale, nH * scale, nW * scale);

        float[] elevOut = addElevationNoise(elevSmooth, elevPadded, i1, j1, H, W, pixelSizeM);

        short[] biomeFlat = BiomeClassifier.classify(elevSmooth, climate, i1, j1, elevPadded, H, W, pixelSizeM);
        CoastShaper.apply(elevUp, cropI1, cropJ1, H, W, scale, i1, j1, climate, elevOut, biomeFlat,
                riverMouthMask(elevOut, i1, j1, H, W, scale));
        return buildHeightmapData(elevOut, biomeFlat, H, W);
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
        float[] result = new float[4 * H * W];
        for (int ch = 0; ch < 4; ch++) {
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
