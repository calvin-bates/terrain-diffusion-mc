package com.github.xandergos.terraindiffusionmc.hydrology;

import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;

/**
 * Turns an elevation + climate window into river classes using {@link DrainageNetwork}.
 *
 * <p>Pure Java (no Minecraft types). All coordinates are native pixels. The caller should pass a
 * window with a generous margin around the area of interest and crop the result, because flow
 * accumulation is truncated at the window border.
 */
public final class RiverMap {
    public static final byte NONE = 0, STREAM = 1, RIVER = 2, MAIN = 3;

    /** Discharge thresholds in "cell equivalents" of runoff. */
    private static final float STREAM_Q = 350f, RIVER_Q = 2500f, MAIN_Q = 15000f;

    private static final FastNoiseLite JITTER = new FastNoiseLite(99);
    static {
        JITTER.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        JITTER.SetFrequency(1f / 9f);
    }

    private RiverMap() { }

    /**
     * @param elev    elevation in metres, row-major H*W
     * @param climate 5*H*W climate (temp, t_season, precip, p_cv, ...) or null
     * @param i0      world row of the window's first row (for position-stable jitter noise)
     * @param j0      world column of the window's first column
     * @return class per cell: NONE / STREAM / RIVER / MAIN
     */
    public static byte[] compute(float[] elev, float[] climate, int H, int W, int i0, int j0) {
        int n = H * W;
        float[] routing = elev.clone();
        float[] runoff = new float[n];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int i = r * W + c;
                if (elev[i] > 0f) {
                    // Small routing-only jitter breaks up straight diagonal D8 flow on smooth ramps.
                    routing[i] += JITTER.GetNoise(j0 + c, i0 + r) * 6f;
                }
                float precip = climate != null ? Math.max(0f, climate[2 * n + i]) : 800f;
                runoff[i] = Math.max(0.05f, Math.min(3f, precip / 800f));
            }
        }

        DrainageNetwork net = DrainageNetwork.compute(routing, runoff, H, W, 0f, true);
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            float e = elev[i];
            if (e <= 0f) continue;
            float q = net.discharge[i];
            byte cls = q >= MAIN_Q ? MAIN : q >= RIVER_Q ? RIVER : q >= STREAM_Q ? STREAM : NONE;
            if (cls == NONE) continue;

            // Altitude caps: big rivers only in lowlands, small streams in the mountains.
            if (e > 1500f && cls > STREAM) cls = STREAM;
            else if (e > 700f && cls > RIVER) cls = RIVER;

            // No rivers on snowy / stony high ground.
            float temp = climate != null ? climate[i] : 10f;
            if (temp < -4f || e > 2800f) cls = NONE;
            out[i] = cls;
        }
        return out;
    }
}
