package com.github.xandergos.terraindiffusionmc.hydrology;

import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;

/**
 * Turns an elevation + climate window into river / lake classes using {@link DrainageNetwork}.
 *
 * <p>Pure Java (no Minecraft types). All coordinates are native pixels. The caller should pass a
 * window with a margin of at least {@link Params#flowSteps} pixels around the area of interest;
 * results inside that area are then identical no matter how the windows are cut.
 */
public final class RiverMap {
    public static final byte NONE = 0, STREAM = 1, RIVER = 2, MAIN = 3, LAKE = 4;

    public static final class Params {
        /** Discharge thresholds in "cell equivalents" of runoff (1 cell = one native pixel of ~800 mm rain). */
        public float streamQ = 3500f, riverQ = 10000f, mainQ = 25000f;
        /** Only upstream area within this many flow steps counts (keeps regions seamless). */
        public int flowSteps = 80;
        /** Minimum lake size (native pixels) and depth (metres of fill). */
        public int lakeMinArea = 150;
        /** Larger depressions are artefacts of the generated terrain (a basin the sea would drain), not lakes. */
        public int lakeMaxArea = 2500;
        public float lakeMinDepth = 2f;
    }

    /** Per-cell results for the whole window. */
    public static final class Result {
        public final byte[] cls;
        /** Downstream cell index, or -1. */
        public final int[] downstream;
        /** Water surface elevation in metres (depression-filled terrain). */
        public final float[] surface;

        Result(byte[] cls, int[] downstream, float[] surface) {
            this.cls = cls;
            this.downstream = downstream;
            this.surface = surface;
        }
    }

    private static final FastNoiseLite JITTER = new FastNoiseLite(99);
    static {
        JITTER.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        JITTER.SetFrequency(1f / 9f);
    }

    private RiverMap() { }

    /** Convenience for callers that only need the classes. */
    public static byte[] compute(float[] elev, float[] climate, int H, int W, int i0, int j0, Params p) {
        return computeFull(elev, climate, H, W, i0, j0, p).cls;
    }

    /**
     * @param elev    elevation in metres, row-major H*W
     * @param climate 5*H*W climate (temp, t_season, precip, p_cv, ...) or null
     * @param i0      world row of the window's first row (for position-stable jitter noise)
     * @param j0      world column of the window's first column
     */
    public static Result computeFull(float[] elev, float[] climate, int H, int W, int i0, int j0, Params p) {
        int n = H * W;
        float[] routing = new float[n];
        float[] runoff = new float[n];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int i = r * W + c;
                float e = elev[i];
                if (e > 0f) {
                    // Tiny tie-breaking jitter only; land must never be pushed to or below sea level,
                    // or the coast gets fake "ocean" cells that make rivers stop short of the sea.
                    e = Math.max(0.05f, e + JITTER.GetNoise(j0 + c, i0 + r) * 0.3f);
                }
                routing[i] = e;
                float precip = climate != null ? Math.max(0f, climate[2 * n + i]) : 800f;
                runoff[i] = Math.max(0.05f, Math.min(3f, precip / 800f));
            }
        }

        DrainageNetwork net = DrainageNetwork.compute(routing, runoff, H, W, 0f, true);
        float[] q = net.limitedDischarge(runoff, p.flowSteps);
        byte[] out = new byte[n];

        // --- lakes: connected groups of depression cells that are big and deep enough
        boolean[] lake = new boolean[n];
        boolean[] seen = new boolean[n];
        int[] stack = new int[n];
        int[] members = new int[n];
        for (int s = 0; s < n; s++) {
            if (seen[s] || !net.inDepression[s] || elev[s] <= 0f) continue;
            int sp = 0, count = 0;
            float maxDepth = 0f;
            stack[sp++] = s; seen[s] = true;
            while (sp > 0) {
                int i = stack[--sp];
                members[count++] = i;
                maxDepth = Math.max(maxDepth, net.filled[i] - routing[i]);
                int r = i / W, c = i % W;
                if (r > 0)     { int j = i - W; if (!seen[j] && net.inDepression[j] && elev[j] > 0f) { seen[j] = true; stack[sp++] = j; } }
                if (r < H - 1) { int j = i + W; if (!seen[j] && net.inDepression[j] && elev[j] > 0f) { seen[j] = true; stack[sp++] = j; } }
                if (c > 0)     { int j = i - 1; if (!seen[j] && net.inDepression[j] && elev[j] > 0f) { seen[j] = true; stack[sp++] = j; } }
                if (c < W - 1) { int j = i + 1; if (!seen[j] && net.inDepression[j] && elev[j] > 0f) { seen[j] = true; stack[sp++] = j; } }
            }
            if (count >= p.lakeMinArea && count <= p.lakeMaxArea && maxDepth >= p.lakeMinDepth) {
                for (int k = 0; k < count; k++) lake[members[k]] = true;
                // One flat water level per lake (the fill adds tiny random steps that would otherwise
                // show up as one-block ledges in the water).
                float level = 0f;
                for (int k = 0; k < count; k++) level = Math.max(level, net.filled[members[k]]);
                for (int k = 0; k < count; k++) net.filled[members[k]] = level;
            }
        }

        for (int i = 0; i < n; i++) {
            float e = elev[i];
            if (e <= 0f) continue;
            // No water on snowy / stony high ground.
            float temp = climate != null ? climate[i] : 10f;
            if (temp < -4f || e > 2800f) continue;

            if (lake[i]) { out[i] = LAKE; continue; }

            float d = q[i];
            byte cls = d >= p.mainQ ? MAIN : d >= p.riverQ ? RIVER : d >= p.streamQ ? STREAM : NONE;
            if (cls == NONE) continue;
            // Altitude caps: big rivers only in lowlands, small streams in the mountains.
            if (e > 1500f && cls > STREAM) cls = STREAM;
            else if (e > 700f && cls > RIVER) cls = RIVER;
            out[i] = cls;
        }
        return new Result(out, net.downstream, net.filled);
    }
}
