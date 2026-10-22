package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Coastal currents (biomes plan v1.2). Terra's climate comes from noise, with no latitude, so the two big effects of
 * ocean currents on real coasts are added here, before biomes and rivers see the climate:
 * <ul>
 *   <li><b>Cold upwelling</b> (Atacama, Namib, Baja and California): on subtropical coasts the sea is cold, the air
 *   cooler and very dry, and the effect fades inland over tens of kilometres. Coastal deserts and scrub.</li>
 *   <li><b>Warm current</b> (Norway, British Columbia): on cold coasts the sea is warm, the land milder and wetter,
 *   and the sea stays open where it would otherwise freeze.</li>
 * </ul>
 * On Earth both reach the <i>west</i> sides of continents. Terra uses a fixed axis for that (Calvin's choice, Oct 21):
 * west is -X (decreasing native column j). Which effect a coast gets comes from its sea-level temperature, which
 * stands in for latitude: subtropical-warm coasts get upwelling, cold ones the warm current, mild ones neither.
 *
 * <p>The geometry is computed on the coarse grid (7.7 km pixels) from the coarse model's elevation: a land pixel's
 * <b>exposure</b> is how directly it looks west over open sea (rays fanned ±40° around -X, weighted toward due west),
 * fading with distance inland; a sea pixel's is how close it lies off such a coast, looking east to the land, fading
 * offshore. The sea beyond the coast must be open (mostly deeper than {@link #OPEN_DEPTH_M} for
 * {@link #OPEN_PX} pixels), so lakes, straits and enclosed gulfs don't count. Each coarse pixel's exposure depends
 * only on the coarse map around it, so overlapping requests agree; the caller samples it bilinearly, so it is
 * smooth at native resolution.
 */
public final class CoastalCurrents {

    /** How far (coarse pixels, 7.7 km) a land pixel looks west for the sea, and a sea pixel east for the land. */
    static final int RAY_PX = 12;
    /** Coarse pixels past the coast that must be open sea. */
    static final int OPEN_PX = 5;
    /** Open sea: deeper than this (metres) on the coarse map. */
    static final float OPEN_DEPTH_M = -150f;
    /** Inland fade: full within this many coarse pixels of the coast, gone by {@link #INLAND_END_PX} (~60 km). */
    static final float INLAND_FULL_PX = 1f, INLAND_END_PX = 8f;
    /** Offshore fade for the sea temperature: full near the coast, gone by {@link #OFFSHORE_END_PX} (~90 km). */
    static final float OFFSHORE_FULL_PX = 1.5f, OFFSHORE_END_PX = 12f;
    /** Ray directions (degrees from due west) and weights (cos²). */
    private static final float[] ANGLES = {-40f, -20f, 0f, 20f, 40f};
    /** Coarse pixels of margin the caller must provide around the pixels it wants exposure for. */
    public static final int PAD = RAY_PX + OPEN_PX + 2;

    // ---- Climate effects (at full exposure and full weight) ----
    /** Upwelling: sea-level temperature drop (°C), share of precipitation removed, cut in seasonality. */
    static final float UP_COOL_C = 4f, UP_DRY = 0.8f;
    /** Warm current: warming (°C) and extra precipitation (fraction). */
    static final float WARM_HEAT_C = 5f, WARM_WET = 0.6f;
    /** Both make the coast maritime: the temperature's seasonal swing shrinks by up to this share. */
    static final float MARITIME = 0.25f;

    private CoastalCurrents() {
    }

    /**
     * Exposure (0..1) for coarse pixels [r0, r0 + h) x [c0, c0 + w) of a coarse elevation map (metres, row-major,
     * cH x cW). The map must extend {@link #PAD} pixels beyond that block on every side.
     */
    public static float[] exposure(float[] elevM, int cH, int cW, int r0, int c0, int h, int w) {
        float[] out = new float[h * w];
        float wSum = 0f;
        float[] wt = new float[ANGLES.length], dx = new float[ANGLES.length], dy = new float[ANGLES.length];
        for (int a = 0; a < ANGLES.length; a++) {
            double rad = Math.toRadians(ANGLES[a]);
            wt[a] = (float) (Math.cos(rad) * Math.cos(rad));
            dx[a] = (float) -Math.cos(rad);   // west: decreasing column
            dy[a] = (float) Math.sin(rad);
            wSum += wt[a];
        }
        for (int r = 0; r < h; r++) {
            for (int c = 0; c < w; c++) {
                int R = r0 + r, C = c0 + c;
                boolean land = elevM[R * cW + C] >= 0f;
                float acc = 0f;
                for (int a = 0; a < ANGLES.length; a++) {
                    acc += wt[a] * (land ? landRay(elevM, cH, cW, R, C, dx[a], dy[a])
                                         : seaRay(elevM, cH, cW, R, C, dx[a], dy[a]));
                }
                out[r * w + c] = acc / wSum;
            }
        }
        return out;
    }

    /** Land: march west to the first sea pixel; scores if open sea lies beyond it, fading with the distance inland. */
    private static float landRay(float[] e, int cH, int cW, int R, int C, float dx, float dy) {
        for (int t = 1; t <= RAY_PX; t++) {
            if (sample(e, cH, cW, R, C, dy * t, dx * t) < 0f) {
                if (!openBeyond(e, cH, cW, R, C, dx, dy, t)) return 0f;
                return 1f - smoothstep(INLAND_FULL_PX, INLAND_END_PX, t - 1);
            }
        }
        return 0f;
    }

    /**
     * Sea: the water to the west must be open (this is the current's sea, not a lake or gulf); then march east to the
     * coast, fading with the distance offshore.
     */
    private static float seaRay(float[] e, int cH, int cW, int R, int C, float dx, float dy) {
        if (!openBeyond(e, cH, cW, R, C, dx, dy, 0)) return 0f;
        for (int t = 1; t <= RAY_PX; t++) {
            if (sample(e, cH, cW, R, C, -dy * t, -dx * t) >= 0f) {
                return 1f - smoothstep(OFFSHORE_FULL_PX, OFFSHORE_END_PX, t);
            }
        }
        return 0f;
    }

    /** At least OPEN_PX - 1 of the OPEN_PX samples past step t along the ray are open sea. */
    private static boolean openBeyond(float[] e, int cH, int cW, int R, int C, float dx, float dy, int t) {
        int open = 0;
        for (int k = 1; k <= OPEN_PX; k++) {
            if (sample(e, cH, cW, R, C, dy * (t + k), dx * (t + k)) < OPEN_DEPTH_M) open++;
        }
        return open >= OPEN_PX - 1;
    }

    /** How much of the upwelling effect a coast with this sea-level temperature (°C) gets: the subtropics. */
    public static float upwellingWeight(float seaLevelT) {
        return smoothstep(11f, 15f, seaLevelT) * (1f - smoothstep(25f, 29f, seaLevelT));
    }

    /** How much of the warm-current effect: cold coasts (full below 6 °C). */
    public static float warmWeight(float seaLevelT) {
        return 1f - smoothstep(6f, 11f, seaLevelT);
    }

    /**
     * Signed current at a point: negative for upwelling (down to -1), positive for a warm current (up to 1), from the
     * exposure and the sea-level temperature before the currents.
     */
    public static float signedCurrent(float exposure, float seaLevelT) {
        return exposure * (warmWeight(seaLevelT) - upwellingWeight(seaLevelT));
    }

    /** Temperature change (°C) for a signed current. */
    public static float deltaT(float current) {
        return current < 0f ? UP_COOL_C * current : WARM_HEAT_C * current;
    }

    /** Precipitation multiplier for a signed current. */
    public static float precipFactor(float current) {
        return current < 0f ? 1f + UP_DRY * current : 1f + WARM_WET * current;
    }

    /** Seasonality multiplier for a signed current. */
    public static float seasonFactor(float current) {
        return 1f - MARITIME * Math.abs(current);
    }

    /**
     * Bilinear sample at pixel (R, C) plus a fractional offset. The offset is split into whole and fractional parts on
     * its own, so the result doesn't depend on where the caller's block starts (adding it to R first rounds
     * differently for different R).
     */
    private static float sample(float[] e, int cH, int cW, int R, int C, float oy, float ox) {
        int fy = (int) Math.floor(oy), fx = (int) Math.floor(ox);
        float fr = oy - fy, fc = ox - fx;
        int r0 = R + fy, c0 = C + fx;
        r0 = Math.max(0, Math.min(cH - 2, r0));
        c0 = Math.max(0, Math.min(cW - 2, c0));
        float a = e[r0 * cW + c0], b = e[r0 * cW + c0 + 1], d = e[(r0 + 1) * cW + c0], f = e[(r0 + 1) * cW + c0 + 1];
        return (a * (1 - fc) + b * fc) * (1 - fr) + (d * (1 - fc) + f * fc) * fr;
    }

    static float smoothstep(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }
}
