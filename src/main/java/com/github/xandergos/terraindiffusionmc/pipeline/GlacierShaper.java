package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Land glaciers (biomes plan v1.4 round B): valley glaciers filling cold mountain valleys, and ice caps over cold, high,
 * gentle ground, computed per tile at block resolution as an ice surface over the ground. The ground itself is not
 * changed; {@code world.Glaciers} lays the ice blocks between the ground and the ice surface in the terrain pass.
 *
 * <p>The ice surface is the ground's floor (the lowest ground within {@link #R1_PX} native pixels, smoothed over
 * {@link #R2_PX}) plus a thickness. The floor sits at a valley's bottom across the whole valley, so ice of a given
 * thickness fills the valley from wall to wall up to that height, thins out over gentle ground, and leaves slopes
 * steeper than about the thickness over the floor radius bare (ridges, peaks and headwalls stand out of the ice like
 * nunataks). The thickness grows with the cold (annual mean temperature well below freezing; valleys take ice ~3 deg C
 * warmer, so tongues reach further down them than the ice does on open slopes), needs enough snowfall, and is up to
 * ~90 m in deep mountain valleys and ~35 m on high plateaus. Everything is smooth noise or filters of ground within
 * {@code 5 * scale} blocks, which the tile's clean margin holds, so neighbouring tiles agree.
 *
 * <p>Around the ice, a band of ground just above its surface is moraine: gravel and cobble the ice has pushed aside.
 */
public final class GlacierShaper {

    /** Kinds of glacier column. */
    public static final byte NONE = 0, ICE_SNOW = 1, ICE_BARE = 2, MORAINE = 3;
    /** Marker for no ice in {@link Result#iceTop}. */
    public static final short NO_ICE = Short.MIN_VALUE;

    /** Floor: lowest ground within this many native pixels (square). */
    private static final int R1_PX = 2;
    /** Floor smoothing and the small blur of the valley test (native pixels). */
    private static final int R2_PX = 2;
    /** The wide blur of the valley test (native pixels): R1 + R2. With the interpolation's pixel, the most the margin allows. */
    private static final int R3_PX = R1_PX + R2_PX;

    /** Thickest ice (m) in a deep, very cold valley, and on a high plateau. */
    private static final float VALLEY_ICE_M = 70f, CAP_ICE_M = 35f, MAX_ICE_M = 90f;
    /** Ice thinner than this (m) is not laid. */
    private static final float MIN_ICE_M = 2.5f;
    /** Moraine on ground up to this far (m) above a nearby ice surface. */
    private static final float MORAINE_M = 6f;

    private static final FastNoiseLite SURFACE = noise(7301, 1f / 220f);

    private GlacierShaper() {
    }

    private static FastNoiseLite noise(int seed, float freqPerMetre) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freqPerMetre);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(2);
        return n;
    }

    /** Per tile column: ice surface (metres, or {@link #NO_ICE}) and kind; null when the tile has no glacier. */
    public record Result(short[][] iceTop, byte[][] kind) {}

    /**
     * @param up      upsampled ground (metres) at block resolution with the tile's margin, before block noise
     * @param cropI   row in {@code up} of the tile's first row (at least {@code 5 * scale} rows of margin on each side)
     * @param cropJ   column in {@code up} of the tile's first column
     * @param climate the tile's climate (temperature at 0, precipitation at {@code 2 * H * W})
     * @param elevOut the tile's final ground (metres)
     * @param biomes  the tile's biomes; ice columns become {@link RegionalBiomes#GLACIER}
     * @param tags    the tile's surface tags or null; volcanic ground gets no ice
     */
    public static Result apply(float[][] up, int cropI, int cropJ, int H, int W, int scale, int i1, int j1,
                               float[] climate, float[] elevOut, short[] biomes, byte[][] tags) {
        if (climate == null) return null;
        int HW = H * W;
        float minTemp = Float.MAX_VALUE, maxGround = -Float.MAX_VALUE;
        for (int k = 0; k < HW; k++) {
            minTemp = Math.min(minTemp, climate[k]);
            maxGround = Math.max(maxGround, elevOut[k]);
        }
        if (minTemp > 2f || maxGround < 300f) return null;

        // The smooth fields are filtered at native resolution (one sample per native pixel) and interpolated to blocks:
        // they vary over hundreds of metres, and this is ~scale^2 times cheaper than filtering every block.
        int upH = up.length, upW = up[0].length;
        // Native pixels around the tile the fields need: one for the interpolation, plus the filters' reach.
        int P = R3_PX + 1;
        int ni0 = Math.floorDiv(i1, scale) - P, nj0 = Math.floorDiv(j1, scale) - P;
        int nh = Math.floorDiv(i1 + H - 1, scale) + P - ni0 + 1, nw = Math.floorDiv(j1 + W - 1, scale) + P - nj0 + 1;
        int half = scale / 2;
        // Row/column in `up` of native pixel (ni0, nj0)'s sample.
        int ur0 = ni0 * scale + half - i1 + cropI, uc0 = nj0 * scale + half - j1 + cropJ;
        if (ur0 < 0 || uc0 < 0 || ur0 + (nh - 1) * scale >= upH || uc0 + (nw - 1) * scale >= upW) return null;
        float[] nat = new float[nh * nw];
        for (int r = 0; r < nh; r++) {
            float[] row = up[ur0 + r * scale];
            for (int c = 0; c < nw; c++) nat[r * nw + c] = row[uc0 + c * scale];
        }
        float metresPerBlock = WorldPipelineModelConfig.nativeResolution() / scale;
        // Floor: lowest sample within R1 (square), then blurred over R2. Valley test: wide blur minus small blur.
        float[] minF = new float[nh * nw];
        java.util.Arrays.fill(minF, Float.NaN);
        for (int r = R1_PX; r < nh - R1_PX; r++) {
            for (int c = R1_PX; c < nw - R1_PX; c++) {
                float m = Float.MAX_VALUE;
                for (int a = r - R1_PX; a <= r + R1_PX; a++)
                    for (int b = c - R1_PX; b <= c + R1_PX; b++) m = Math.min(m, nat[a * nw + b]);
                minF[r * nw + c] = m;
            }
        }
        float[] floorN = blurNative(minF, nh, nw, R2_PX, R1_PX);
        float[] smallN = blurNative(nat, nh, nw, R2_PX, 0);
        float[] wideN = blurNative(nat, nh, nw, R3_PX, 0);
        float[] floor = new float[HW], small = new float[HW], wide = new float[HW];
        for (int r = 0; r < H; r++) {
            float fz = (i1 + r + 0.5f) / scale - 0.5f - ni0;
            int z0 = (int) Math.floor(fz);
            float tz = fz - z0;
            for (int c = 0; c < W; c++) {
                float fx = (j1 + c + 0.5f) / scale - 0.5f - nj0;
                int x0 = (int) Math.floor(fx);
                float tx = fx - x0;
                int k = r * W + c;
                floor[k] = bilerp(floorN, nw, z0, x0, tz, tx);
                small[k] = bilerp(smallN, nw, z0, x0, tz, tx);
                wide[k] = bilerp(wideN, nw, z0, x0, tz, tx);
            }
        }

        short[][] iceTop = null;
        byte[][] kind = null;
        boolean any = false;
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int k = r * W + c;
                float e = elevOut[k];
                if (e < 1f || BiomeClassifier.isOceanBiome(biomes[k])) continue;
                if (tags != null && volcanic(tags[r][c])) continue;
                float temp = climate[k], precip = climate[2 * HW + k];
                float ground = small[k];
                float valley = smoothstep(8f, 50f, wide[k] - small[k]) * smoothstep(300f, 800f, ground);
                float cap = smoothstep(600f, 1000f, ground);
                float threshold = -1f + 3f * valley;
                float cold = smoothstep(threshold, threshold - 5f, temp);
                float wet = smoothstep(200f, 400f, precip);
                float T = cold * wet * Math.min(MAX_ICE_M, VALLEY_ICE_M * valley + CAP_ICE_M * cap);
                if (T <= 0.5f) continue;
                float wx = (j1 + c) * metresPerBlock, wz = (i1 + r) * metresPerBlock;
                float top = floor[k] + T + 1.5f * SURFACE.GetNoise(wx, wz) * Math.min(1f, T / 10f);
                float thick = top - e;
                byte kd;
                if (thick >= MIN_ICE_M) {
                    kd = cold > 0.7f ? ICE_SNOW : ICE_BARE;
                    biomes[k] = RegionalBiomes.GLACIER;
                } else if (T > 3f && thick > -MORAINE_M) {
                    kd = MORAINE;
                } else {
                    continue;
                }
                if (iceTop == null) {
                    iceTop = new short[H][W];
                    kind = new byte[H][W];
                    for (short[] row : iceTop) java.util.Arrays.fill(row, NO_ICE);
                }
                kind[r][c] = kd;
                if (kd != MORAINE) iceTop[r][c] = (short) Math.max(-32768, Math.min(32767, Math.round(top)));
                any = true;
            }
        }
        return any ? new Result(iceTop, kind) : null;
    }

    /**
     * Box blur (radius R native pixels) of a native field, for pixels at least {@code R + edge} from the window's edge
     * (NaN elsewhere: never read). Each value is summed afresh in a fixed order, so it is the same in every tile.
     */
    private static float[] blurNative(float[] a, int h, int w, int R, int edge) {
        float[] out = new float[h * w];
        java.util.Arrays.fill(out, Float.NaN);
        int m = R + edge;
        double n = (2.0 * R + 1) * (2.0 * R + 1);
        for (int r = m; r < h - m; r++) {
            for (int c = m; c < w - m; c++) {
                double s = 0;
                for (int y = r - R; y <= r + R; y++)
                    for (int x = c - R; x <= c + R; x++) s += a[y * w + x];
                out[r * w + c] = (float) (s / n);
            }
        }
        return out;
    }

    private static float bilerp(float[] g, int w, int r0, int c0, float tr, float tc) {
        float a = g[r0 * w + c0], b = g[r0 * w + c0 + 1], c = g[(r0 + 1) * w + c0], d = g[(r0 + 1) * w + c0 + 1];
        return (a * (1 - tc) + b * tc) * (1 - tr) + (c * (1 - tc) + d * tc) * tr;
    }

    private static boolean volcanic(byte t) {
        return t == Landforms.TAG_LAVA || t == Landforms.TAG_LAVA_CHANNEL || t == Landforms.TAG_VOLCANO
                || t == Landforms.TAG_CRATER || t == Landforms.TAG_LAVA_LAKE || t == Landforms.TAG_HOT_CRATER;
    }

    private static float smoothstep(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }
}
