package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Breaks coastlines into islands inside archipelago zones, at the model's native 30 m resolution.
 *
 * <p>The archipelago nudge in {@link SyntheticMapFactory} only acts on the coarse conditioning (7.7 km pixels), so
 * the smallest "islands" it can ask for are ~25 km across, and the model decides whether real island clusters
 * appear. This pass makes sure they do: near sea level (within a few hundred metres either way), it adds a
 * fine-scale drowning, so low coastal land is flooded into channels and bays and its hills become islands,
 * 1–6 km across. The field is stretched along a slowly turning direction, so islands come out
 * elongated and roughly parallel, like the Dalmatian coast or the Aegean, rather than round blobs.
 *
 * <p>It only adds a smooth height change, so the model's own detail stays on top. Deep ocean and high ground are
 * untouched. Each pixel depends only on its elevation and position, so overlapping requests agree. Runs inside
 * {@link WorldPipeline#get} before climate, so rivers, biomes and the explorer all see the islands.
 */
public final class ArchipelagoShaper {

    /** Native pixels per kilometre. */
    private static final double KM = 1000.0 / 30.0;
    /** Average drowning (metres) at the core of a zone. */
    private static final float DROWN_BASE_M = 45f;
    /** How much the drowning varies from place to place (metres either way). */
    private static final float DROWN_VARY_M = 110f;
    /** Full effect between these heights (metres); fades to nothing by FADE_* beyond them. */
    private static final float FULL_LOW_M = -300f, FULL_HIGH_M = 200f, FADE_LOW_M = -800f, FADE_HIGH_M = 1000f;
    /** Relief near sea level is flattened by up to this fraction, so a wide belt of hills sits near the water. */
    private static final float FLATTEN = 0.5f;
    /** How much longer islands are than wide. */
    private static final float STRETCH = 1.8f;
    /**
     * Fine coastline roughness (metres either way) near sea level. The flattening leaves low ground gentle, so the
     * coast followed the smooth 7 km field and islands came out with soft, rounded shores. This small fine-scale term
     * breaks them into coves, points and islets. It fades out by {@link #DETAIL_FADE_M} metres above sea level, so
     * hills keep the model's own detail.
     */
    private static final float DETAIL_M = 30f, DETAIL_FADE_M = 110f;
    /**
     * The detail scales with the slope of the model's ground (metres per 30 m pixel, measured over 180 m): none on
     * flat ground below {@link #SLOPE_SMOOTH}, full on hilly ground above {@link #SLOPE_ROUGH}. Flat, shallow coasts
     * stay smooth and sandy, as real ones are; on them the detail used to poke its peaks through the shallow floor as
     * rows of same-sized sand islets (Calvin, Oct 19, X 113488 Z -40171). Rugged coasts get the coves and points.
     */
    private static final float SLOPE_SMOOTH = 0.8f, SLOPE_ROUGH = 3.5f;
    /** Native pixels of model elevation needed around a request to measure the slope ({@link #apply}). */
    public static final int SLOPE_MARGIN = 3;

    private final FastNoiseLite islands, warpX, warpZ, direction, detail, detailWarpX, detailWarpZ;

    public ArchipelagoShaper(long seed) {
        int s = (int) (seed ^ (seed >>> 32));
        islands = new FastNoiseLite(s + 301);
        islands.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        islands.SetFrequency(1f / 7f);                  // per km: island groups ~7 km apart, finer octaves below
        islands.SetFractalType(FastNoiseLite.FractalType.FBm);
        islands.SetFractalOctaves(3);
        islands.SetFractalGain(0.45f);
        warpX = simple(s + 302, 1f / 12f);
        warpZ = simple(s + 303, 1f / 12f);
        direction = simple(s + 304, 1f / 250f);
        detail = new FastNoiseLite(s + 305);
        detail.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        detail.SetFrequency(1f / 2.2f);                 // per km: headlands of ~2 km down to coves of ~150 m
        detail.SetFractalType(FastNoiseLite.FractalType.FBm);
        detail.SetFractalOctaves(5);
        detail.SetFractalGain(0.55f);
        detailWarpX = simple(s + 306, 1f / 2.5f);
        detailWarpZ = simple(s + 307, 1f / 2.5f);
    }

    private static FastNoiseLite simple(int seed, float freqPerKm) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freqPerKm);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(2);
        return n;
    }

    /**
     * Applies the pass in place.
     *
     * @param elev elevation in metres, row-major (H, W), top-left at native pixel (i1, j1)
     * @param padded the same model elevation with {@link #SLOPE_MARGIN} pixels more on every side, for the slope
     * @param syn  the synthetic map factory, for the archipelago zones
     * @param coarsePx native pixels per coarse pixel
     */
    public void apply(float[] elev, float[] padded, int i1, int j1, int H, int W, SyntheticMapFactory syn, int coarsePx) {
        final int m = SLOPE_MARGIN, pW = W + 2 * m;
        // The zones are ~460 km across: sample the zone strength on a coarse lattice and interpolate.
        final int STEP = 64;
        int gh = H / STEP + 2, gw = W / STEP + 2;
        float[] zone = new float[gh * gw];
        boolean any = false;
        for (int a = 0; a < gh; a++) {
            for (int b = 0; b < gw; b++) {
                float z = syn.archipelagoZone((float) (j1 + b * STEP) / coarsePx, (float) (i1 + a * STEP) / coarsePx);
                zone[a * gw + b] = z;
                any |= z > 0f;
            }
        }
        if (!any) return;
        for (int r = 0; r < H; r++) {
            float fa = (float) r / STEP;
            int a0 = (int) fa;
            float ta = fa - a0;
            for (int c = 0; c < W; c++) {
                int k = r * W + c;
                float e = elev[k];
                if (e > FADE_HIGH_M || e < FADE_LOW_M) continue;
                float fb = (float) c / STEP;
                int b0 = (int) fb;
                float tb = fb - b0;
                float z = (zone[a0 * gw + b0] * (1 - tb) + zone[a0 * gw + b0 + 1] * tb) * (1 - ta)
                        + (zone[(a0 + 1) * gw + b0] * (1 - tb) + zone[(a0 + 1) * gw + b0 + 1] * tb) * ta;
                if (z <= 0f) continue;
                int i = i1 + r, j = j1 + c;
                float bh = base(e, i, j, z);
                if (bh >= DETAIL_FADE_M || bh <= -260f) { elev[k] = bh; continue; }   // no coastline detail here
                // Slope of the reshaped ground (flattened and drowned, before the detail), which is what decides whether
                // the new coast is flat or steep.
                int p = (r + m) * pW + c + m;
                float gx = (base(padded[p + m], i, j + m, z) - base(padded[p - m], i, j - m, z)) / (2f * m);
                float gz = (base(padded[p + m * pW], i + m, j, z) - base(padded[p - m * pW], i - m, j, z)) / (2f * m);
                float rough = smoothstep(SLOPE_SMOOTH, SLOPE_ROUGH, (float) Math.sqrt(gx * gx + gz * gz));
                elev[k] = shape(e, i, j, z, rough);
            }
        }
    }

    /**
     * Elevation after the pass for ground at {@code e} metres, native pixel (i, j), zone strength 0..1. The land is
     * drowned: lowered relative to the sea by an amount that varies smoothly from place to place, so valleys become
     * channels and bays and hills become islands, the way real archipelagos formed when the sea rose over hilly
     * coasts. The model's terrain keeps its shape (new height always rises with old height), so there are no rims,
     * seams or plateaus. Mountains and deep ocean are left alone.
     */
    float shape(float e, int i, int j, float zone) {
        return shape(e, i, j, zone, 1f);
    }

    /** The reshaped height before the coastline detail (the first part of {@link #shape}). */
    float base(float e, int i, int j, float zone) {
        float w;
        if (e > FULL_HIGH_M) w = 1f - smoothstep(FULL_HIGH_M, FADE_HIGH_M, e);
        else if (e < FULL_LOW_M) w = smoothstep(FADE_LOW_M, FULL_LOW_M, e);
        else w = 1f;
        if (w <= 0f) return e;
        float k = zone * w;
        return e * (1f - FLATTEN * k) - k * (DROWN_BASE_M + DROWN_VARY_M * field(i, j));
    }

    /** {@link #shape(float, int, int, float)} with the coastline detail scaled by {@code rough} (0..1). */
    float shape(float e, int i, int j, float zone, float rough) {
        float w;
        if (e > FULL_HIGH_M) w = 1f - smoothstep(FULL_HIGH_M, FADE_HIGH_M, e);
        else if (e < FULL_LOW_M) w = smoothstep(FADE_LOW_M, FULL_LOW_M, e);
        else w = 1f;
        if (w <= 0f) return e;
        float drown = DROWN_BASE_M + DROWN_VARY_M * field(i, j);
        // Flatten toward sea level, then drown. Both keep new height rising with old height (the flattening
        // factor stays positive and its fade is gentle), so slopes are never reversed.
        float k = zone * w;
        float base = e * (1f - FLATTEN * k) - k * drown;
        // Coastline detail, centred on the new shoreline: full from 160 m deep up to 5 m above the sea, fading out by
        // DETAIL_FADE_M on land, so hills keep the model's own surface. The fades are gentle enough that new height
        // still rises with old height.
        float near = base >= 5f ? 1f - smoothstep(5f, DETAIL_FADE_M, base) : smoothstep(-260f, -160f, base);
        if (near > 0f && rough > 0f) base -= k * DETAIL_M * rough * near * detail(i, j);
        return base;
    }

    private static float smoothstep(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    /** Fine, warped coastline noise in about [-1, 1] at native pixel (i, j). */
    float detail(double i, double j) {
        float xk = (float) (j / KM), zk = (float) (i / KM);
        float x = xk + 0.45f * detailWarpX.GetNoise(xk, zk), z = zk + 0.45f * detailWarpZ.GetNoise(xk, zk);
        return detail.GetNoise(x, z);
    }

    /**
     * Island field in about [-1, 1] at native pixel (i, j): positive drowns more, negative less.
     *
     * <p>The islands are stretched along a direction that turns slowly across the world. Rotating the world
     * coordinates by that varying angle would shear the field (far from the origin the rotated coordinates sweep
     * past many noise cells in a short distance, which drew long thin streaks), so instead the field is a blend of
     * four copies at fixed orientations 45 degrees apart. At any point only the two orientations nearest the local
     * direction contribute, with weights that sum (in variance) to one.
     */
    float field(double i, double j) {
        float xk = (float) (j / KM), zk = (float) (i / KM);
        float x = xk + 1.5f * warpX.GetNoise(xk, zk), z = zk + 1.5f * warpZ.GetNoise(xk, zk);
        double ang = direction.GetNoise(xk, zk) * Math.PI;
        double sum = 0, wsq = 0;
        for (int k = 0; k < 4; k++) {
            double theta = k * Math.PI / 4;
            double w = Math.cos(2 * (ang - theta));
            if (w <= 0) continue;
            w *= w;                                      // adjacent orientations: cos^2 + sin^2 = 1
            float ca = (float) Math.cos(theta), sa = (float) Math.sin(theta);
            float u = (ca * x + sa * z) / STRETCH, v = -sa * x + ca * z;
            sum += w * islands.GetNoise(u + 113 * k, v - 71 * k);
            wsq += w * w;
        }
        float n = (float) (sum / Math.sqrt(Math.max(wsq, 1e-6)));
        return Math.max(-1f, Math.min(1f, 1.8f * n));
    }
}
