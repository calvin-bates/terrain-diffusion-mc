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

    private final FastNoiseLite islands, warpX, warpZ, direction;

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
     * @param syn  the synthetic map factory, for the archipelago zones
     * @param coarsePx native pixels per coarse pixel
     */
    public void apply(float[] elev, int i1, int j1, int H, int W, SyntheticMapFactory syn, int coarsePx) {
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
                elev[k] = shape(e, i1 + r, j1 + c, z);
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
        float w;
        if (e > FULL_HIGH_M) w = 1f - smoothstep(FULL_HIGH_M, FADE_HIGH_M, e);
        else if (e < FULL_LOW_M) w = smoothstep(FADE_LOW_M, FULL_LOW_M, e);
        else w = 1f;
        if (w <= 0f) return e;
        float drown = DROWN_BASE_M + DROWN_VARY_M * field(i, j);
        // Flatten toward sea level, then drown. Both keep new height rising with old height (the flattening
        // factor stays positive and its fade is gentle), so slopes are never reversed.
        float k = zone * w;
        return e * (1f - FLATTEN * k) - k * drown;
    }

    private static float smoothstep(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
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
