package com.github.xandergos.terraindiffusionmc.pipeline;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;

import com.github.xandergos.terraindiffusionmc.infinitetensor.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Java port of terrain_diffusion/inference/world_pipeline.py WorldPipeline.
 *
 * <p>Three stages: coarse (20-step DPM-Solver++), latent (2 flow-matching steps),
 * decoder (1 flow-matching step).  All pixel coordinates are native-resolution space.
 */
public final class WorldPipeline implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WorldPipeline.class);

    static final int LATENT_COMPRESSION = WorldPipelineModelConfig.latentCompression();
    static final float SIGMA_DATA = EDMScheduler.SIGMA_DATA;

    static final int COARSE_TILE_SIZE   = 64;
    static final int COARSE_TILE_STRIDE = 48;
    static final int LATENT_TILE_SIZE   = 64;
    static final int LATENT_TILE_STRIDE = 32;
    static final int DECODER_TILE_SIZE  = 256;
    static final int DECODER_TILE_STRIDE = 192;

    static final float[] MODEL_MEANS = WorldPipelineModelConfig.coarseMeans();
    static final float[] MODEL_STDS = WorldPipelineModelConfig.coarseStds();

    static final float[] COND_MEANS = {14.99f, 11.65f, 15.87f, 619.26f, 833.12f, 69.40f, 0.66f};
    static final float[] COND_STDS  = {21.72f, 21.78f, 10.40f, 452.29f, 738.09f, 34.59f, 0.47f};

    static final float LOWFREQ_MEAN  = -31.4f;
    static final float LOWFREQ_STD   = 38.6f;
    static final float RESIDUAL_MEAN = WorldPipelineModelConfig.residualMean();
    static final float RESIDUAL_STD  = WorldPipelineModelConfig.residualStd();

    static final float[] COND_SNR  = WorldPipelineModelConfig.conditioningSnr();
    static final int COARSE_POOLING = WorldPipelineModelConfig.coarsePooling();
    static final float[] COND_VALS;  // log(COND_SNR / 8)
    static {
        if (COARSE_POOLING != 1) {
            throw new IllegalStateException("coarse_pooling=" + COARSE_POOLING + " is not supported in the Java pipeline yet");
        }
        COND_VALS = new float[COND_SNR.length];
        for (int i = 0; i < COND_SNR.length; i++) COND_VALS[i] = (float) Math.log(COND_SNR[i] / 8.0);
    }

    // mp_concat scales for 6 tensors of sizes [16, 16, 4, 16, 5, 1] → 58 total
    static final int[] COND_DIMS = {16, 16, 4, 16, 5, 1};
    static final float[] MP_CONCAT_SCALES;
    static final float[] HISTOGRAM_RAW;
    static {
        int sumN = 0; for (int n : COND_DIMS) sumN += n;
        int k = COND_DIMS.length;
        float C = (float) Math.sqrt((double) sumN * k);
        MP_CONCAT_SCALES = new float[k];
        for (int i = 0; i < k; i++) MP_CONCAT_SCALES[i] = C / (float) Math.sqrt(COND_DIMS[i]) / k;
        float[] configuredHistogramRaw = WorldPipelineModelConfig.histogramRaw();
        HISTOGRAM_RAW = configuredHistogramRaw != null ? configuredHistogramRaw : new float[]{0f, 0f, 0f, 0f, 0f};
    }

    private final OnnxModel coarseModel;
    private final OnnxModel baseModel;
    private final OnnxModel decoderModel;
    private final boolean ownModels;
    private volatile SyntheticMapFactory syntheticMapFactory;
    private volatile OceanFeatures oceanFeatures;
    private volatile ArchipelagoShaper archipelagoShaper;
    private volatile long seed;

    private final MemoryTileStore tileStore;
    private final long cacheLimitBytes = 100L * 1024 * 1024;

    final InfiniteTensor coarse;
    final InfiniteTensor latents;
    final InfiniteTensor residual;

    /** Uses shared models from PipelineModels (e.g. from mod init). Does not close models on close(). Seed is 64-bit (Python: seed & 0xFFFFFFFFFFFFFFFF). */
    public WorldPipeline(long seed, PipelineModels models) {
        this.seed = seed & 0xFFFFFFFFFFFFFFFFL;
        this.coarseModel = models.getCoarseModel();
        this.baseModel = models.getBaseModel();
        this.decoderModel = models.getDecoderModel();
        this.ownModels = false;
        this.syntheticMapFactory = new SyntheticMapFactory(this.seed);
        this.oceanFeatures = newOceanFeatures(this.seed);
        this.archipelagoShaper = new ArchipelagoShaper(this.seed);
        this.tileStore = new MemoryTileStore();
        this.coarse = buildCoarseStage();
        this.latents = buildLatentStage();
        this.residual = buildDecoderStage();
    }

    /** Loads its own models (e.g. for tests). Caller must close. */
    public WorldPipeline(long seed) {
        this.seed = seed & 0xFFFFFFFFFFFFFFFFL;
        ModelAssetManager.ensureAssetsReady();
        this.coarseModel = new OnnxModel(ModelAssetManager.resolveAssetPath("coarse_model.onnx"), "coarse");
        this.baseModel = new OnnxModel(ModelAssetManager.resolveAssetPath("base_model.onnx"), "base");
        this.decoderModel = new OnnxModel(ModelAssetManager.resolveAssetPath("decoder_model.onnx"), "decoder");
        this.ownModels = true;
        this.syntheticMapFactory = new SyntheticMapFactory(this.seed);
        this.oceanFeatures = newOceanFeatures(this.seed);
        this.archipelagoShaper = new ArchipelagoShaper(this.seed);
        this.tileStore = new MemoryTileStore();
        this.coarse = buildCoarseStage();
        this.latents = buildLatentStage();
        this.residual = buildDecoderStage();
    }

    /** Lightweight seed change (Python change_seed): update seed and synthetic map, clear tile caches. Models stay loaded. */
    public void setSeed(long newSeed) {
        long s = newSeed & 0xFFFFFFFFFFFFFFFFL;
        if (s == this.seed) return;
        this.seed = s;
        this.syntheticMapFactory = new SyntheticMapFactory(s);
        this.oceanFeatures = newOceanFeatures(s);
        this.archipelagoShaper = new ArchipelagoShaper(s);
        tileStore.clearAllCaches();
    }

    private static OceanFeatures newOceanFeatures(long seed) {
        OceanFeatures of = new OceanFeatures(seed);
        of.modelIslands = TerrainDiffusionConfig.oceanFeatures() && TerrainDiffusionConfig.modelIslands();
        return of;
    }

    // =========================================================================
    // Coarse Stage
    // =========================================================================

    private InfiniteTensor buildCoarseStage() {
        int S = COARSE_TILE_SIZE, ST = COARSE_TILE_STRIDE;
        float[] ww = linearWeightWindow(S);
        TensorWindow outWin = new TensorWindow(new int[]{7, S, S}, new int[]{7, ST, ST});
        return tileStore.getOrCreate("base_coarse_map", new Integer[]{7, null, null},
                (wi, args) -> coarseTile(wi, ww), outWin,
                new InfiniteTensor[]{}, new TensorWindow[]{}, cacheLimitBytes);
    }

    private FloatTensor coarseTile(int[] wi, float[] ww) {
        int S = COARSE_TILE_SIZE, ST = COARSE_TILE_STRIDE;
        int i = wi[1], j = wi[2];
        int i1 = i * ST, j1 = j * ST;

        // Synthetic map conditioning: channels [elev_sqrt, temp, tempStd, precip, precipStd]
        // Python call: synthetic_map_factory(j1, i1, j2, i2)
        // Coordinates are intentionally swapped
        float[][][] syn = syntheticMapFactory.sample(j1, i1, j1 + S, i1 + S);

        // Modify temp channel (index 1): where <= 20, scale toward 20
        for (int r = 0; r < S; r++)
            for (int c = 0; c < S; c++) {
                float v = syn[1][r][c];
                if (v <= 20f) syn[1][r][c] = (v - 20f) * 1.25f + 20f;
            }

        // Normalize with MODEL_MEANS/STDS indices [0,2,3,4,5]
        int[] meanIdx = {0, 2, 3, 4, 5};
        float[] condImg = new float[5 * S * S];
        for (int ch = 0; ch < 5; ch++) {
            float mean = MODEL_MEANS[meanIdx[ch]], std = MODEL_STDS[meanIdx[ch]];
            for (int px = 0; px < S * S; px++)
                condImg[ch * S * S + px] = (syn[ch][px / S][px % S] - mean) / std;
        }

        // Conditioning noise: Gaussian noise (5, S, S)
        float[] condNoise = flatten3D(GaussianNoisePatch.generate(seed, i1, j1, S, S, 5, S, S));

        // cond_img_mixed = cos(t_cond) * normalized + sin(t_cond) * noise
        float[] condMixed = new float[5 * S * S];
        for (int ch = 0; ch < 5; ch++) {
            float cosT = (float) Math.cos(Math.atan(COND_SNR[ch]));
            float sinT = (float) Math.sin(Math.atan(COND_SNR[ch]));
            for (int px = 0; px < S * S; px++) {
                condMixed[ch * S * S + px] = cosT * condImg[ch * S * S + px]
                        + sinT * condNoise[ch * S * S + px];
            }
        }

        // Initial sample: (6, S, S) noise * sigma_max
        EDMScheduler sched = new EDMScheduler(20);
        float[] sample = flatten3D(GaussianNoisePatch.generate(seed + 1, i1, j1, S, S, 6, S, S));
        for (int k = 0; k < sample.length; k++) sample[k] *= sched.sigmas[0];

        // 20-step DPM-Solver++
        float[][] condInputs = new float[5][1];
        long[][] condShapes  = new long[5][1];
        for (int ci = 0; ci < 5; ci++) { condInputs[ci] = new float[]{COND_VALS[ci]}; condShapes[ci] = new long[]{1}; }

        LOG.debug("Coarse model called for chunk ({}, {}) tile pixels [{}, {}]-[{}, {}] (20 steps)", i, j, i1, j1, i1 + S, j1 + S);
        for (int step = 0; step < 20; step++) {
            float sigma  = sched.sigmas[step];
            float cnoise = EDMScheduler.trigflowPreconditionNoise(sigma);
            float[] scaledIn = EDMScheduler.preconditionInputs(sample, sigma);

            float[] xIn = new float[11 * S * S];
            System.arraycopy(scaledIn, 0, xIn, 0, 6 * S * S);
            System.arraycopy(condMixed, 0, xIn, 6 * S * S, 5 * S * S);

            float[] modelOut = coarseModel.runModel(
                    xIn, new long[]{1, 11, S, S}, new float[]{cnoise}, condInputs, condShapes);
            sample = sched.step(modelOut, sample);
        }

        // Denormalize: sample / sigma_data → raw, then * STDS + MEANS
        float[] out = new float[6 * S * S];
        for (int ch = 0; ch < 6; ch++)
            for (int px = 0; px < S * S; px++)
                out[ch * S * S + px] = (sample[ch * S * S + px] / SIGMA_DATA) * MODEL_STDS[ch] + MODEL_MEANS[ch];

        // ch1 = ch0 - ch1 (convert to p5)
        for (int px = 0; px < S * S; px++)
            out[S * S + px] = out[px] - out[S * S + px];

        // Output: (7, S, S) = [6 channels * weight | weight]
        FloatTensor result = new FloatTensor(new int[]{7, S, S});
        for (int ch = 0; ch < 6; ch++)
            for (int px = 0; px < S * S; px++)
                result.data[ch * S * S + px] = out[ch * S * S + px] * ww[px];
        System.arraycopy(ww, 0, result.data, 6 * S * S, S * S);
        return result;
    }

    // =========================================================================
    // Latent Stage
    // =========================================================================

    private InfiniteTensor buildLatentStage() {
        int S = LATENT_TILE_SIZE, ST = LATENT_TILE_STRIDE;
        TensorWindow outWin = new TensorWindow(new int[]{6, S, S}, new int[]{6, ST, ST});
        TensorWindow coarseWin = new TensorWindow(new int[]{7, 4, 4}, new int[]{7, 1, 1}, new int[]{0, -1, -1});
        float[] ww = linearWeightWindow(S);
        float tInit = (float) Math.atan(EDMScheduler.SIGMA_MAX / SIGMA_DATA);

        InfiniteTensor initLatent = tileStore.getOrCreateBatched(
                "init_latent_map", new Integer[]{6, null, null},
                (wis, args) -> latentBatch(wis, null, args.get(0), tInit, 5819, ww, STAGE_INIT),
                outWin, new InfiniteTensor[]{coarse}, new TensorWindow[]{coarseWin},
                cacheLimitBytes, 4);

        // Islands: tiles with a model-drawn island get an extra, noisier pass (see islandGuide); others pass through.
        float islandT = (float) Math.atan(ISLAND_SIGMA / SIGMA_DATA);
        InfiniteTensor islandLatent = tileStore.getOrCreateBatched(
                "island_latent_map", new Integer[]{6, null, null},
                (wis, args) -> latentBatch(wis, args.get(0), args.get(1), islandT, 5821, ww, STAGE_ISLAND),
                outWin, new InfiniteTensor[]{initLatent, coarse}, new TensorWindow[]{outWin, coarseWin},
                cacheLimitBytes, 4);

        float interT = (float) Math.atan(0.35f / SIGMA_DATA);
        return tileStore.getOrCreateBatched(
                "step_latent_map_0", new Integer[]{6, null, null},
                (wis, args) -> latentBatch(wis, args.get(0), args.get(1), interT, 5820, ww, STAGE_STEP),
                outWin, new InfiniteTensor[]{islandLatent, coarse}, new TensorWindow[]{outWin, coarseWin},
                cacheLimitBytes, 4);
    }

    private static final int STAGE_INIT = 0, STAGE_ISLAND = 1, STAGE_STEP = 2;

    private List<FloatTensor> latentBatch(List<int[]> wis, List<FloatTensor> prevSamples,
                                           List<FloatTensor> coarseSlices, float t,
                                           int seedOffset, float[] ww, int stage) {
        int S = LATENT_TILE_SIZE, ST = LATENT_TILE_STRIDE;
        int batch = wis.size();
        float cosT = (float) Math.cos(t), sinT = (float) Math.sin(t);
        OceanFeatures of = oceanFeatures;

        // Each tile's input sample (unnormalized prev output, or zeros) and, for tiles the model runs on, its x_t.
        float[][] samples = new float[batch][];
        float[][] xTArr = new float[batch][];
        float[][] conds = new float[batch][];
        int run = 0;
        for (int b = 0; b < batch; b++) {
            int[] ctx = wis.get(b);
            int i1 = ctx[1] * ST, j1 = ctx[2] * ST;

            float[] sample = new float[5 * S * S];
            if (prevSamples != null) {
                FloatTensor ps = prevSamples.get(b);
                for (int ch = 0; ch < 5; ch++)
                    for (int px = 0; px < S * S; px++) {
                        float w = ps.data[5 * S * S + px];
                        sample[ch * S * S + px] = (w > 1e-6f) ? ps.data[ch * S * S + px] / w * SIGMA_DATA : 0f;
                    }
            }
            samples[b] = sample;

            // Islands for the model to draw (see islandGuide).
            IslandGuide guide = stage != STAGE_INIT && of.modelIslands ? islandGuide(of, ctx[1], ctx[2]) : null;
            if (stage == STAGE_ISLAND) {
                if (guide == null || !guide.inTile()) continue;     // passes through unchanged
                guide.prepareIslandPass(sample, S);
            } else if (stage == STAGE_STEP && guide != null) {
                guide.pullLowfreq(sample, S, ISLAND_STEP_PULL);
            }

            // Build conditioning from coarse slice (7, 4, 4)
            conds[b] = buildLatentConditioning(coarseSlices.get(b), guide);

            // z = noise * sigma_data; x_t = cos(t)*sample + sin(t)*z
            float[] noise = flatten3D(GaussianNoisePatch.generate(seed + seedOffset, i1, j1, S, S, 5, S, S));
            float[] xT = new float[5 * S * S];
            for (int k = 0; k < 5 * S * S; k++) {
                float z = noise[k] * SIGMA_DATA;
                xT[k] = cosT * sample[k] + sinT * z;
            }
            xTArr[b] = xT;
            run++;
        }

        float[] predBatch = null;
        int[] slot = new int[batch];
        if (run > 0) {
            float[] modelInBatch   = new float[run * 5 * S * S];
            float[] condInputBatch = new float[run * 58];
            int n = 0;
            for (int b = 0; b < batch; b++) {
                if (xTArr[b] == null) continue;
                slot[b] = n;
                for (int k = 0; k < 5 * S * S; k++) modelInBatch[n * 5 * S * S + k] = xTArr[b][k] / SIGMA_DATA;
                System.arraycopy(conds[b], 0, condInputBatch, n * 58, 58);
                n++;
            }
            String chunkList = wis.stream().map(w -> "(" + w[1] + "," + w[2] + ")").collect(Collectors.joining(", "));
            LOG.debug("Base model called for {} of {} chunks: {}", run, batch, chunkList);

            float[] noiseLabels = new float[run];
            java.util.Arrays.fill(noiseLabels, t);
            predBatch = baseModel.runModel(
                    modelInBatch, new long[]{run, 5, S, S},
                    noiseLabels, new float[][]{condInputBatch}, new long[][]{{run, 58}});
        }

        // Build outputs: pred = -raw_model_out; sample = cos(t)*xT - sin(t)*sigma_data*pred
        List<FloatTensor> results = new ArrayList<>(batch);
        for (int b = 0; b < batch; b++) {
            float[] newSample = new float[5 * S * S];
            float[] xT = xTArr[b];
            if (xT == null) {
                for (int k = 0; k < 5 * S * S; k++) newSample[k] = samples[b][k] / SIGMA_DATA;   // passed through
            } else {
                int base = slot[b] * 5 * S * S;
                for (int k = 0; k < 5 * S * S; k++) {
                    float pred = -predBatch[base + k];  // base model output is negated
                    newSample[k] = (cosT * xT[k] - sinT * SIGMA_DATA * pred) / SIGMA_DATA;
                }
            }

            FloatTensor out = new FloatTensor(new int[]{6, S, S});
            for (int ch = 0; ch < 5; ch++)
                for (int px = 0; px < S * S; px++)
                    out.data[ch * S * S + px] = newSample[ch * S * S + px] * ww[px];
            System.arraycopy(ww, 0, out.data, 5 * S * S, S * S);
            results.add(out);
        }
        return results;
    }

    // ---- Model-drawn islands ----

    /** How far apart (sqrt-metres) the model's low-frequency floor and an island guide blend smoothly. */
    private static final float ISLAND_BLEND = 1.5f;
    /** Noise level of the island pass: high enough that the model redraws the terrain under the island. */
    private static final float ISLAND_SIGMA = 1.0f;
    /** How far (sqrt-metres) above the floor an island must stand to clear the old floor detail fully. */
    private static final float ISLAND_CLEAR = 4f;
    /** Share of the way the refinement step's input is pulled back up to an island the island pass lowered. */
    private static final float ISLAND_STEP_PULL = 0.65f;
    /** Guide samples per side of each coarse pixel, for the conditioning. */
    private static final int ISLAND_COARSE_SAMPLES = 8;

    /**
     * The islands {@link OceanFeatures#islandGuide} wants drawn in one latent tile: their surface in signed-sqrt
     * metres at each latent pixel of the tile ({@code tile}, NaN where there is none) and at a grid of points in each
     * of the 16 coarse pixels its conditioning covers ({@code coarse}).
     */
    private static final class IslandGuide {
        final float[] tile;
        final float[][] coarse;

        IslandGuide(float[] tile, float[][] coarse) { this.tile = tile; this.coarse = coarse; }

        /** Whether any island reaches into the tile itself (not just its conditioning's surroundings). */
        boolean inTile() {
            for (float g : tile) if (!Float.isNaN(g)) return true;
            return false;
        }

        /**
         * Input for the island pass: the low-frequency channel (4) is raised to the islands (smooth max), and where an
         * island stands clear of the floor the detail channels (0-3), which still describe open ocean floor there,
         * fade to zero, so the model makes up its own island terrain from the noise.
         */
        void prepareIslandPass(float[] sample, int S) {
            int off = 4 * S * S;
            for (int px = 0; px < S * S; px++) {
                float g = tile[px];
                if (Float.isNaN(g)) continue;
                float l = sample[off + px] / SIGMA_DATA * LOWFREQ_STD + LOWFREQ_MEAN;
                float w = smoothstep(0f, ISLAND_CLEAR, g - l);
                for (int ch = 0; ch < 4; ch++) sample[ch * S * S + px] *= 1f - w;
                sample[off + px] = (target(l, g) - LOWFREQ_MEAN) / LOWFREQ_STD * SIGMA_DATA;
            }
        }

        /**
         * Refinement input: pulls the low-frequency channel {@code pull} of the way up to the islands where the island
         * pass left it lower, so a small island is not lost, while the model keeps its own shape for the rest.
         */
        void pullLowfreq(float[] sample, int S, float pull) {
            int off = 4 * S * S;
            for (int px = 0; px < S * S; px++) {
                float g = tile[px];
                if (Float.isNaN(g)) continue;
                float l = sample[off + px] / SIGMA_DATA * LOWFREQ_STD + LOWFREQ_MEAN;
                sample[off + px] = (l + pull * (target(l, g) - l) - LOWFREQ_MEAN) / LOWFREQ_STD * SIGMA_DATA;
            }
        }

        /**
         * Where low-frequency height {@code l} should go for island surface {@code g} (both signed sqrt): up to the
         * island (smooth max), and on the island's upper part (above ~600 m deep) also down to it, so calderas,
         * bays and landslide scars the model filled in are cut back out.
         */
        static float target(float l, float g) {
            float up = smoothMax(l, g, ISLAND_BLEND);
            float down = smoothstep(-30f, -20f, g);
            return up + (Math.min(up, g) - up) * down;
        }

        /** Lifts the conditioning's mean and 5th-percentile elevation (signed sqrt, unweighted) to the islands. */
        void liftConditioning(float[] condFlat, int N) {
            for (int px = 0; px < N; px++) {
                float[] g = coarse[px];
                float mean = condFlat[px], p5 = condFlat[N + px];
                double lift = 0;
                float[] lows = new float[g.length];
                for (int k = 0; k < g.length; k++) {
                    boolean none = Float.isNaN(g[k]);
                    lift += none ? 0 : Math.max(0f, g[k] - mean);
                    lows[k] = none ? p5 : Math.max(p5, g[k]);
                }
                if (lift <= 0) continue;
                java.util.Arrays.sort(lows);
                condFlat[px] = (float) (mean + lift / g.length);
                condFlat[N + px] = Math.min(lows[(int) (0.05f * lows.length)], condFlat[px]);
            }
        }
    }

    /**
     * Where the islands of {@link OceanFeatures} go in latent tile (ti, tj), or null if there are none near it.
     * Instead of stamping procedural cones onto the finished terrain, the refinement step of the latent model is
     * told about each island (its conditioning says the coarse pixel holds land, and its low-frequency input already
     * rises to the island), so the model draws the island itself in its own style.
     */
    private IslandGuide islandGuide(OceanFeatures of, int ti, int tj) {
        int S = LATENT_TILE_SIZE, ST = LATENT_TILE_STRIDE, lc = LATENT_COMPRESSION, CS = 32 * lc, G = ISLAND_COARSE_SAMPLES;
        // The tile's conditioning covers coarse pixels (ti - 1 .. ti + 2, tj - 1 .. tj + 2).
        int ni0 = (ti - 1) * CS, nj0 = (tj - 1) * CS;
        int pad = (int) Math.ceil(Math.max(2 * OceanFeatures.MAX_VOLCANO_REACH, OceanFeatures.MAX_FEATURE_REACH));
        OceanFeatures.CoarseField field = coarseField(ni0 - pad, nj0 - pad, ni0 + 4 * CS + pad, nj0 + 4 * CS + pad);
        OceanFeatures.Ctx octx = new OceanFeatures.Ctx();
        float[][] coarseG = new float[16][G * G];
        boolean any = false;
        for (int a = 0; a < 4; a++)
            for (int b = 0; b < 4; b++)
                for (int u = 0; u < G; u++)
                    for (int v = 0; v < G; v++) {
                        int i = ni0 + a * CS + (2 * u + 1) * CS / (2 * G), j = nj0 + b * CS + (2 * v + 1) * CS / (2 * G);
                        float g = signedSqrtOrNaN(of.islandGuide(i, j, field, octx));
                        coarseG[a * 4 + b][u * G + v] = g;
                        any |= !Float.isNaN(g);
                    }
        if (!any) return null;
        float[] tile = new float[S * S];
        int li = ti * ST, lj = tj * ST;
        for (int r = 0; r < S; r++)
            for (int c = 0; c < S; c++)
                tile[r * S + c] = signedSqrtOrNaN(of.islandGuide((li + r) * lc + lc / 2, (lj + c) * lc + lc / 2, field, octx));
        return new IslandGuide(tile, coarseG);
    }

    private static float signedSqrtOrNaN(double e) {
        if (e == Double.NEGATIVE_INFINITY) return Float.NaN;
        return (float) (Math.signum(e) * Math.sqrt(Math.abs(e)));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    /** max(a, b), rounded over {@code k} where they are close. */
    private static float smoothMax(float a, float b, float k) {
        float d = Math.abs(a - b);
        if (d >= k) return Math.max(a, b);
        float t = (k - d) / k;
        return Math.max(a, b) + t * t * k * 0.25f;
    }

    /** Build 58-dim conditioning vector from a (7,4,4) coarse tile slice. */
    private float[] buildLatentConditioning(FloatTensor coarseSlice) {
        return buildLatentConditioning(coarseSlice, null);
    }

    /** {@link #buildLatentConditioning(FloatTensor)}, with the coarse elevation lifted to the islands of {@code guide}. */
    private float[] buildLatentConditioning(FloatTensor coarseSlice, IslandGuide guide) {
        int N = 4 * 4;
        // Unnormalize: cond[:-1] / cond[-1] for each pixel
        float[] condFlat = new float[6 * N];
        for (int ch = 0; ch < 6; ch++)
            for (int px = 0; px < N; px++) {
                float w = coarseSlice.data[6 * N + px];
                condFlat[ch * N + px] = (w > 1e-6f) ? coarseSlice.data[ch * N + px] / w : 0f;
            }
        if (guide != null) guide.liftConditioning(condFlat, N);

        // Append mask channel (all ones = (1 - mean) / std normalized)
        float[] condImg7 = new float[7 * N];
        System.arraycopy(condFlat, 0, condImg7, 0, 6 * N);
        float maskNorm = (1.0f - COND_MEANS[6]) / COND_STDS[6];
        for (int px = 0; px < N; px++) condImg7[6 * N + px] = maskNorm;

        // Normalize all 7 channels
        for (int ch = 0; ch < 6; ch++)
            for (int px = 0; px < N; px++) {
                float v = (condFlat[ch * N + px] - COND_MEANS[ch]) / COND_STDS[ch];
                condImg7[ch * N + px] = Float.isNaN(v) ? 0f : v;
            }

        // Extract components
        float[] meansCrop    = new float[16]; System.arraycopy(condImg7, 0,      meansCrop, 0, 16);
        float[] p5Crop       = new float[16]; System.arraycopy(condImg7, 16,     p5Crop,    0, 16);
        float[] maskCrop     = new float[16]; System.arraycopy(condImg7, 6 * 16, maskCrop,  0, 16);
        float[] climateMeans = new float[4];
        for (int ch = 0; ch < 4; ch++) {
            float sum = 0;
            for (int r = 1; r < 3; r++) for (int c = 1; c < 3; c++)
                sum += condImg7[(2 + ch) * 16 + r * 4 + c];
            climateMeans[ch] = sum / 4f;
            if (Float.isNaN(climateMeans[ch])) climateMeans[ch] = 0f;
        }

        float noiseLevelNorm = (0f - 0.5f) * (float) Math.sqrt(12.0);
        float[] histRaw = HISTOGRAM_RAW;

        // mp_concat
        float[] out = new float[58];
        int off = 0;
        off = appendScaled(out, off, meansCrop,    MP_CONCAT_SCALES[0]);
        off = appendScaled(out, off, p5Crop,       MP_CONCAT_SCALES[1]);
        off = appendScaled(out, off, climateMeans, MP_CONCAT_SCALES[2]);
        off = appendScaled(out, off, maskCrop,     MP_CONCAT_SCALES[3]);
        off = appendScaled(out, off, histRaw,      MP_CONCAT_SCALES[4]);
        out[off] = noiseLevelNorm * MP_CONCAT_SCALES[5];
        return out;
    }

    // =========================================================================
    // Decoder Stage
    // =========================================================================

    private InfiniteTensor buildDecoderStage() {
        int S = DECODER_TILE_SIZE, ST = DECODER_TILE_STRIDE, lc = LATENT_COMPRESSION;
        TensorWindow outWin  = new TensorWindow(new int[]{2, S, S},  new int[]{2, ST, ST});
        TensorWindow inpWin  = new TensorWindow(new int[]{6, S/lc, S/lc}, new int[]{6, ST/lc, ST/lc});
        float[] ww = linearWeightWindow(S);
        float t = (float) Math.atan(EDMScheduler.SIGMA_MAX / SIGMA_DATA);

        return tileStore.getOrCreate("init_residual_map", new Integer[]{2, null, null},
                (wi, args) -> decoderTile(wi, args.get(0), t, ww),
                outWin, new InfiniteTensor[]{latents}, new TensorWindow[]{inpWin}, cacheLimitBytes);
    }

    private FloatTensor decoderTile(int[] wi, FloatTensor latentSlice, float t, float[] ww) {
        int S = DECODER_TILE_SIZE, ST = DECODER_TILE_STRIDE, lc = LATENT_COMPRESSION;
        int Slc = S / lc;
        int i1 = wi[1] * ST, j1 = wi[2] * ST;
        float cosT = (float) Math.cos(t), sinT = (float) Math.sin(t);

        // Unnormalize latents channels 0..3 (4 channels)
        float[] latFlat = new float[4 * Slc * Slc];
        for (int ch = 0; ch < 4; ch++)
            for (int px = 0; px < Slc * Slc; px++) {
                float w = latentSlice.data[5 * Slc * Slc + px];
                latFlat[ch * Slc * Slc + px] = (w > 1e-6f) ? latentSlice.data[ch * Slc * Slc + px] / w : 0f;
            }

        // Nearest-neighbor upsample (4, Slc, Slc) → (4, S, S)
        float[] upsampled = nearestUpsample(latFlat, 4, Slc, Slc, S, S);

        // One flow-matching step (sample starts at zero)
        float[] noise = flatten3D(GaussianNoisePatch.generate(seed + 5819, i1, j1, S, S, 1, S, S));
        float[] xT = new float[S * S];
        for (int k = 0; k < S * S; k++) xT[k] = sinT * noise[k] * SIGMA_DATA;  // sample=0

        // model_in = concat([xT/sigma_data (1,S,S), upsampled (4,S,S)]) → (5,S,S)
        float[] modelIn = new float[5 * S * S];
        for (int k = 0; k < S * S; k++) modelIn[k] = xT[k] / SIGMA_DATA;
        System.arraycopy(upsampled, 0, modelIn, S * S, 4 * S * S);

        LOG.debug("Decoder model called for chunk ({}, {}) tile pixels [{}, {}]-[{}, {}]", wi[1], wi[2], i1, j1, i1 + S, j1 + S);
        float[] rawPred = decoderModel.runModel(modelIn, new long[]{1, 5, S, S}, new float[]{t}, null, null);

        // sample = cos(t)*xT - sin(t)*sigma_data*(-rawPred); then / sigma_data
        float[] newSample = new float[S * S];
        for (int k = 0; k < S * S; k++) {
            float pred = -rawPred[k];  // decoder model output is negated
            newSample[k] = (cosT * xT[k] - sinT * SIGMA_DATA * pred) / SIGMA_DATA;
        }
        FloatTensor result = new FloatTensor(new int[]{2, S, S});
        for (int px = 0; px < S * S; px++) result.data[px] = newSample[px] * ww[px];
        System.arraycopy(ww, 0, result.data, S * S, S * S);
        return result;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /** Returns the current world seed. */
    public long getSeed() {
        return seed;
    }

    /** Returns the total count of newly computed tensor windows since startup. */
    public long getTotalComputedWindowCount() {
        return tileStore.getTotalComputedWindowCount();
    }

    /**
     * Returns a coarse tensor slice with shape [7, ci1-ci0, cj1-cj0].
     * Coordinates are in coarse index units (1 unit = 256 native pixels).
     * Channel 6 is the blend weight; channels 0–5 are weighted sums.
     */
    public FloatTensor getCoarseSlice(int ci0, int cj0, int ci1, int cj1) {
        return coarse.getSlice(new int[]{0, ci0, cj0}, new int[]{7, ci1, cj1});
    }

    /**
     * Get elevation and climate for a bounding box.
     *
     * @return float[2]: [0] = elev (H*W flat), [1] = climate (5*H*W flat, or null)
     */
    public float[][] get(int i1, int j1, int i2, int j2, boolean withClimate) {
        return get(i1, j1, i2, j2, withClimate, null);
    }

    /**
     * {@link #get(int, int, int, int, boolean)}, also writing each pixel's ocean surface tag (reef, lagoon, bank,
     * limestone; see {@link OceanFeatures#TAG_NONE}) into {@code tagsOut} (H*W) when it is not null. All zero when
     * ocean features are off.
     */
    public float[][] get(int i1, int j1, int i2, int j2, boolean withClimate, byte[] tagsOut) {
        int H = i2 - i1, W = j2 - j1;
        // A margin of the model's elevation around the request, so the archipelago pass can measure the ground's
        // slope at the request's edges the same way as inside it (computeElev is translation-consistent).
        int m = ArchipelagoShaper.SLOPE_MARGIN, pW = W + 2 * m;
        float[] padded = computeElev(i1 - m, j1 - m, i2 + m, j2 + m);
        float[] elevFlat = new float[H * W];
        for (int r = 0; r < H; r++) System.arraycopy(padded, (r + m) * pW + m, elevFlat, r * W, W);
        // Islands at native resolution inside archipelago zones (off when oceans.archipelago_strength is 0).
        archipelagoShaper.apply(elevFlat, padded, i1, j1, H, W, syntheticMapFactory, 32 * LATENT_COMPRESSION);
        if (tagsOut != null) java.util.Arrays.fill(tagsOut, OceanFeatures.TAG_NONE);
        if (TerrainDiffusionConfig.oceanFeatures()) {
            applyOceanFeatures(elevFlat, i1, j1, i2, j2, tagsOut);
        }
        float[] climate = withClimate ? computeClimate(i1, j1, i2, j2, elevFlat, H, W) : null;
        return new float[][]{elevFlat, climate};
    }

    // =========================================================================
    // Ocean features
    // =========================================================================

    /**
     * Stamps tectonic sea-floor relief and oceanic islands into the elevation (see {@link OceanFeatures}). Runs
     * before climate so island peaks get the lapse-rate cooling of any other high ground.
     */
    private void applyOceanFeatures(float[] elev, int i1, int j1, int i2, int j2, byte[] tags) {
        boolean anyOcean = false;
        for (float v : elev) if (v < 0f) { anyOcean = true; break; }
        if (!anyOcean) return;
        // Volcano centres can lie a footprint away from the request (twice that through the plate warp), and a
        // hotspot volcano's moat and debris field reach MAX_FEATURE_REACH from it.
        int padPx = (int) Math.ceil(Math.max(2 * OceanFeatures.MAX_VOLCANO_REACH, OceanFeatures.MAX_FEATURE_REACH));
        oceanFeatures.apply(elev, i1, j1, i2 - i1, j2 - j1, coarseField(i1 - padPx, j1 - padPx, i2 + padPx, j2 + padPx), tags);
    }

    /** Smoothed coarse elevation and temperature covering the native-pixel window, sampled bilinearly. */
    private OceanFeatures.CoarseField coarseField(int i1, int j1, int i2, int j2) {
        int S = 32 * LATENT_COMPRESSION;   // native pixels per coarse pixel
        int ci0 = Math.floorDiv(i1, S) - 1, cj0 = Math.floorDiv(j1, S) - 1;
        int ci1 = -Math.floorDiv(-i2, S) + 1, cj1 = -Math.floorDiv(-j2, S) + 1;
        FloatTensor slice = coarse.getSlice(new int[]{0, ci0, cj0}, new int[]{7, ci1, cj1});
        int cH = ci1 - ci0, cW = cj1 - cj0, n = cH * cW;
        float[] cElev = new float[n], cTemp = new float[n];
        for (int px = 0; px < n; px++) {
            float w = slice.data[6 * n + px];
            float e = w > 1e-6f ? slice.data[px] / w : 0f;
            cElev[px] = Math.signum(e) * e * e;                      // undo the signed square root
            cTemp[px] = w > 1e-6f ? slice.data[2 * n + px] / w : 15f;
        }
        float[][] elev2 = to2D(cElev, cH, cW), temp2 = to2D(cTemp, cH, cW);
        SyntheticMapFactory syn = syntheticMapFactory;
        return new OceanFeatures.CoarseField() {
            @Override public float elevation(double i, double j) { return sample(elev2, i, j); }
            @Override public float temperature(double i, double j) { return sample(temp2, i, j); }
            // Same zones (and the same coordinates) as ArchipelagoShaper uses.
            @Override public float archipelago(double i, double j) { return syn.archipelagoZone((float) (j / S), (float) (i / S)); }

            private float sample(float[][] a, double i, double j) {
                // Coarse pixel k covers native rows [k*S, (k+1)*S), with its centre at (k + 0.5) * S.
                float gy = (float) (i / S - 0.5 - ci0), gx = (float) (j / S - 0.5 - cj0);
                return bilinearSample2D(a, cH, cW, gy, gx);
            }
        };
    }

    /**
     * Ocean features whose centres lie in the native-pixel window, for the terrain explorer and /terra-find:
     * the volcanoes, atolls and trenches from {@link OceanFeatures#landmarks}, plus one marker per archipelago sea
     * (the strongest point of each 24-coarse-pixel cell that lies well inside an archipelago zone).
     */
    /** The ocean features of the current seed, or null when they are switched off. Safe from any thread. */
    public OceanFeatures oceanFeatures() {
        return TerrainDiffusionConfig.oceanFeatures() ? oceanFeatures : null;
    }

    /**
     * Likely spots (native pixel {i, j}) for a shallow-sea feature in the window, on a grid of {@code step} pixels
     * (see {@link OceanFeatures#shallowCandidate}); empty when ocean features are off.
     */
    public List<double[]> shallowCandidates(String kind, int i1, int j1, int i2, int j2, int step) {
        List<double[]> out = new ArrayList<>();
        if (!TerrainDiffusionConfig.oceanFeatures()) return out;
        OceanFeatures.CoarseField field = coarseField(i1, j1, i2, j2);
        for (int i = Math.floorDiv(i1, step) * step; i < i2; i += step) {
            for (int j = Math.floorDiv(j1, step) * step; j < j2; j += step) {
                if (i < i1 || j < j1) continue;
                if (oceanFeatures.shallowCandidate(kind, i, j, field)) out.add(new double[]{i, j});
            }
        }
        return out;
    }

    public List<OceanFeatures.Landmark> oceanLandmarks(int i1, int j1, int i2, int j2) {
        OceanFeatures.CoarseField field = coarseField(i1, j1, i2, j2);
        List<OceanFeatures.Landmark> out = new ArrayList<>();
        if (TerrainDiffusionConfig.oceanFeatures()) {
            out.addAll(oceanFeatures.landmarks(i1, j1, i2, j2, field));
        }
        int S = 32 * LATENT_COMPRESSION, cell = 24;
        SyntheticMapFactory syn = syntheticMapFactory;
        for (int gy = Math.floorDiv(Math.floorDiv(i1, S), cell); gy <= Math.floorDiv(Math.floorDiv(i2, S), cell); gy++) {
            for (int gx = Math.floorDiv(Math.floorDiv(j1, S), cell); gx <= Math.floorDiv(Math.floorDiv(j2, S), cell); gx++) {
                float best = 0f, bx = 0f, by = 0f;
                for (int a = 0; a < 6; a++) {
                    for (int b = 0; b < 6; b++) {
                        float x = gx * cell + (b + 0.5f) * cell / 6f, y = gy * cell + (a + 0.5f) * cell / 6f;
                        float z = syn.archipelagoZone(x, y);
                        if (z > best) { best = z; bx = x; by = y; }
                    }
                }
                if (best < 0.9f) continue;
                double i = by * S, j = bx * S;
                if (i < i1 || i >= i2 || j < j1 || j >= j2) continue;
                if (field.elevation(i, j) > 300f) continue;   // the zone only reshapes low ground and sea
                out.add(new OceanFeatures.Landmark("archipelago", i, j, best));
            }
        }
        return out;
    }

    // =========================================================================
    // Elevation
    // =========================================================================

    private float[] computeElev(int i1, int j1, int i2, int j2) {
        int lc = LATENT_COMPRESSION;
        float sigma = 5.0f;
        int ks = ((int) (sigma * 2) / 2) * 2 + 1;
        int padLr = ks / 2 + 1;
        int padHr = padLr * lc;

        // Align padding to lc-pixel grid
        int pi1 = Math.floorDiv(i1 - padHr, lc) * lc;
        int pj1 = Math.floorDiv(j1 - padHr, lc) * lc;
        int pi2 = -Math.floorDiv(-(i2 + padHr), lc) * lc;
        int pj2 = -Math.floorDiv(-(j2 + padHr), lc) * lc;
        int pH = pi2 - pi1, pW = pj2 - pj1;

        // Residual slice (2, pH, pW)
        FloatTensor resSlice = residual.getSlice(new int[]{0, pi1, pj1}, new int[]{2, pi2, pj2});
        float[][] residualP = new float[pH][pW];
        for (int r = 0; r < pH; r++)
            for (int c = 0; c < pW; c++) {
                float w = resSlice.data[pH * pW + r * pW + c];
                float v = (w > 1e-6f) ? resSlice.data[r * pW + c] / w : 0f;
                residualP[r][c] = v * RESIDUAL_STD + RESIDUAL_MEAN;
            }

        // Latent slice (6, lH, lW)
        int lH = pH / lc, lW = pW / lc;
        FloatTensor latSlice = latents.getSlice(
                new int[]{0, pi1 / lc, pj1 / lc}, new int[]{6, pi2 / lc, pj2 / lc});
        float[][] lowfreqP = new float[lH][lW];
        for (int r = 0; r < lH; r++)
            for (int c = 0; c < lW; c++) {
                float w = latSlice.data[5 * lH * lW + r * lW + c];
                float v = (w > 1e-6f) ? latSlice.data[4 * lH * lW + r * lW + c] / w : 0f;
                lowfreqP[r][c] = v * LOWFREQ_STD + LOWFREQ_MEAN;
            }

        float[][] newLowres = LaplacianUtils.laplacianDenoise(residualP, lowfreqP, sigma);
        float[][] elevP = LaplacianUtils.laplacianDecode(residualP, newLowres);

        int oi = i1 - pi1, oj = j1 - pj1, H = i2 - i1, W = j2 - j1;
        float[] flat = new float[H * W];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++) {
                float es = elevP[oi + r][oj + c];
                flat[r * W + c] = (float) (Math.signum(es) * es * es);
            }
        return flat;
    }

    // =========================================================================
    // Climate
    // =========================================================================

    private float[] computeClimate(int i1, int j1, int i2, int j2,
                                    float[] elevFlat, int H, int W) {
        int lc = LATENT_COMPRESSION;
        int S = 32 * lc;  // native pixels per coarse pixel in stride sense

        int ci1 = Math.floorDiv(i1, S);
        int cj1 = Math.floorDiv(j1, S);
        int ci2 = -Math.floorDiv(-i2, S);
        int cj2 = -Math.floorDiv(-j2, S);

        int win = 15, pad = (win - 1) / 2 + 1;

        FloatTensor coarseSlice = coarse.getSlice(
                new int[]{0, ci1 - pad, cj1 - pad}, new int[]{7, ci2 + pad, cj2 + pad});
        int cH = ci2 + pad - (ci1 - pad);
        int cW = cj2 + pad - (cj1 - pad);

        // Unnormalize all 6 coarse channels
        float[][] coarseMap = new float[6][cH * cW];
        for (int ch = 0; ch < 6; ch++)
            for (int px = 0; px < cH * cW; px++) {
                float w = coarseSlice.data[6 * cH * cW + px];
                coarseMap[ch][px] = (w > 1e-6f) ? coarseSlice.data[ch * cH * cW + px] / w : 0f;
            }

        // Coarse elevation (undo sqrt): max(0, v)^2  — ocean pixels clamp to 0, matching Python
        float[] coarseElev = new float[cH * cW];
        for (int px = 0; px < cH * cW; px++) {
            float v = Math.max(0f, coarseMap[0][px]);
            coarseElev[px] = v * v;
        }

        // Windowed lapse-rate regression
        float[][][] lbt = LaplacianUtils.localBaselineTemperature(
                to2D(coarseMap[2], cH, cW), to2D(coarseElev, cH, cW), win, 0.02f);
        int lH = lbt[0].length, lW = lbt[0][0].length;

        // Central coarse (crop pad pixels from each side)
        int cenPad = win / 2;
        int cenH = cH - 2 * cenPad, cenW = cW - 2 * cenPad;
        float[][][] centralCoarse = new float[6][cenH][cenW];
        for (int ch = 0; ch < 6; ch++) {
            float[][] full = to2D(coarseMap[ch], cH, cW);
            centralCoarse[ch] = cropArray(full, cenPad, cenPad, cenH, cenW);
        }

        // Bilinear upsample to native resolution
        float[] climate = new float[5 * H * W];
        for (int r = 0; r < H; r++) {
            // fractional index into lbt/centralCoarse arrays (matches Python's u = (ii+0.5)/S - ci1 + 0.5)
            float gridY    = (i1 + r + 0.5f) / S - ci1 + 0.5f;
            float cenGridY = gridY;
            for (int c = 0; c < W; c++) {
                float gridX    = (j1 + c + 0.5f) / S - cj1 + 0.5f;
                float cenGridX = gridX;

                float tBase = bilinearSample2D(lbt[0], lH, lW, gridY, gridX);
                float beta  = bilinearSample2D(lbt[1], lH, lW, gridY, gridX);
                float tempReal = tBase + beta * Math.max(0f, elevFlat[r * W + c]);

                climate[r * W + c]             = tempReal;
                climate[H * W + r * W + c]     = bilinearSample2D(centralCoarse[3], cenH, cenW, cenGridY, cenGridX);
                climate[2 * H * W + r * W + c] = bilinearSample2D(centralCoarse[4], cenH, cenW, cenGridY, cenGridX);
                climate[3 * H * W + r * W + c] = bilinearSample2D(centralCoarse[5], cenH, cenW, cenGridY, cenGridX);
                climate[4 * H * W + r * W + c] = beta;
            }
        }
        return climate;
    }

    // =========================================================================
    // Static helpers
    // =========================================================================

    static float[] linearWeightWindow(int size) {
        float[] w = new float[size * size];
        float mid = (size - 1) / 2.0f, eps = 1e-3f;
        for (int r = 0; r < size; r++) {
            float wy = 1f - (1f - eps) * Math.min(1f, Math.abs(r - mid) / mid);
            for (int c = 0; c < size; c++) {
                float wx = 1f - (1f - eps) * Math.min(1f, Math.abs(c - mid) / mid);
                w[r * size + c] = wy * wx;
            }
        }
        return w;
    }

    static float[] flatten3D(float[][][] arr) {
        int C = arr.length, H = arr[0].length, W = arr[0][0].length;
        float[] out = new float[C * H * W];
        for (int c = 0; c < C; c++)
            for (int r = 0; r < H; r++)
                System.arraycopy(arr[c][r], 0, out, c * H * W + r * W, W);
        return out;
    }

    static int appendScaled(float[] out, int off, float[] arr, float scale) {
        for (float v : arr) out[off++] = v * scale;
        return off;
    }

    static float[] nearestUpsample(float[] src, int C, int sH, int sW, int dH, int dW) {
        float[] dst = new float[C * dH * dW];
        for (int c = 0; c < C; c++)
            for (int r = 0; r < dH; r++) {
                int sr = r * sH / dH;
                for (int col = 0; col < dW; col++)
                    dst[c * dH * dW + r * dW + col] = src[c * sH * sW + sr * sW + col * sW / dW];
            }
        return dst;
    }

    static float[][] to2D(float[] flat, int H, int W) {
        float[][] a = new float[H][W];
        for (int r = 0; r < H; r++) System.arraycopy(flat, r * W, a[r], 0, W);
        return a;
    }

    static float[][] cropArray(float[][] src, int r0, int c0, int H, int W) {
        float[][] out = new float[H][W];
        for (int r = 0; r < H; r++) System.arraycopy(src[r + r0], c0, out[r], 0, W);
        return out;
    }

    static float bilinearSample2D(float[][] src, int H, int W, float gy, float gx) {
        float y = Math.max(0f, Math.min(H - 1f, gy));
        float x = Math.max(0f, Math.min(W - 1f, gx));
        int y0 = (int) y, y1 = Math.min(H - 1, y0 + 1);
        int x0 = (int) x, x1 = Math.min(W - 1, x0 + 1);
        float wy = y - y0, wx = x - x0;
        return (1-wy)*(1-wx)*src[y0][x0] + (1-wy)*wx*src[y0][x1]
             + wy*(1-wx)*src[y1][x0] + wy*wx*src[y1][x1];
    }

    @Override
    public void close() {
        if (ownModels) {
            coarseModel.close();
            baseModel.close();
            decoderModel.close();
        }
    }
}
