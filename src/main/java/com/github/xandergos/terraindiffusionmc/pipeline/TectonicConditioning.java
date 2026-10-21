package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Oceans round 5: large tectonic landforms laid into the model's elevation conditioning, so the coarse model draws
 * them itself (nothing is stamped onto its terrain afterwards).
 * <ul>
 *   <li><b>Young rift seas</b> (the Red Sea, the Gulf of California, the Gulf of Aden): a continent split along a
 *       gently curving line into a long, narrow sea, 45-110 km wide and 185-460 km long, open to the ocean at one or
 *       both ends and narrowing to a point at a closed end. The floor falls to an axial trough 1.6-2.6 km deep (a
 *       young spreading axis: pillow lava, see {@link #riftAxis}); the coasts have only a narrow shelf, and behind
 *       them the rift shoulders stand 0.6-1.5 km high (the Red Sea Hills, the Yemen highlands).</li>
 *   <li><b>Active continental margins</b> (the Andes, Japan, Cascadia): along stretches of continental coast, a
 *       coastal mountain range 1.8-3.3 km high peaking ~30-55 km inland, a shelf only a few km wide dropping steeply to
 *       the deep, and a trench ~3.8 km deeper than the slope's foot 45-65 km offshore, with an outer rise beyond it
 *       (closer than on Earth: this world's ocean basins are only a few hundred km across). Only
 *       where the ocean in front is wide enough to hold the trench. Volcanoes on the range come later (biomes v1.4).</li>
 * </ul>
 *
 * <p>Everything is a function of the coarse position (x = column, y = row, in coarse pixels of 7.7 km) and the seed,
 * reading the synthetic base elevation ({@link Base}, before any nudges) to find continents and coasts, so it is
 * seamless. Both start rare.
 */
public final class TectonicConditioning {

    /** The synthetic elevation (metres) at a coarse position, before any nudges. */
    public interface Base {
        float elev(float x, float y);
    }

    // ---- Rift seas ----
    /** Cell size (coarse pixels, ~460 km) and the share of cells that try to hold a rift (most then fail the tests). */
    private static final float RIFT_CELL = 60f;
    private static final double RIFT_CHANCE = 0.15;
    /** Half-length (coarse pixels) range, half-width at the middle, axial depth (m), shoulder height (m). */
    private static final float RIFT_LEN_MIN = 12f, RIFT_LEN_MAX = 30f, RIFT_HW_MIN = 3f, RIFT_HW_MAX = 7f;
    private static final float RIFT_DEPTH_MIN = 1600f, RIFT_DEPTH_MAX = 2600f, SHOULDER_MIN = 600f, SHOULDER_MAX = 1500f;
    /** How far (coarse pixels) past an open end the rift runs on, fading out, to meet the ocean. */
    private static final float OPEN_RUN = 8f;

    // ---- Active margins ----
    /** Zone level on a ~1150 km noise (higher = rarer); the soft edge spans ~5-10 coarse pixels. */
    private static final float MARGIN_ZONE_LOW = 0.42f, MARGIN_ZONE_HIGH = 0.66f;
    /** Farthest (coarse pixels) the coast is searched for, and inland / offshore reach of the edits. */
    private static final int COAST_SEARCH = 24;
    /** Coastal range: peak distance inland (coarse pixels) and width; height range (m). */
    private static final float RANGE_AT = 6f, RANGE_WIDTH = 4f, RANGE_MIN = 3500f, RANGE_MAX = 5500f;
    /** Trench: distance offshore (coarse pixels) range, depth below the slope's foot (m), outer rise (m). */
    private static final float TRENCH_AT_MIN = 7f, TRENCH_AT_MAX = 9.5f, TRENCH_DEPTH = 5000f, OUTER_RISE = 400f;

    private final long seed;
    private final FastNoiseLite marginMask, marginVary, riftWobble;
    private final boolean rifts, margins;
    private final ConcurrentHashMap<Long, Rift> riftCache = new ConcurrentHashMap<>();
    private static final Rift NONE = new Rift(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, 0);

    public TectonicConditioning(long seed, boolean rifts, boolean margins) {
        this.seed = seed;
        this.rifts = rifts;
        this.margins = margins;
        int s = (int) (seed ^ (seed >>> 32));
        marginMask = new FastNoiseLite(s + 501);
        marginMask.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        marginMask.SetFrequency(1f / 150f);                // per coarse pixel: ~1150 km, so a zone holds a whole margin
        marginMask.SetFractalType(FastNoiseLite.FractalType.FBm);
        marginMask.SetFractalOctaves(2);
        marginVary = new FastNoiseLite(s + 502);
        marginVary.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        marginVary.SetFrequency(1f / 20f);                 // along-strike variation, ~150 km
        riftWobble = new FastNoiseLite(s + 503);
        riftWobble.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        riftWobble.SetFrequency(1f / 9f);
        riftWobble.SetFractalType(FastNoiseLite.FractalType.FBm);
        riftWobble.SetFractalOctaves(2);
    }

    /** The elevation (metres) at coarse position (x, y) after the rift seas and active margins, given the elevation now. */
    public float apply(float elev, float x, float y, Base base) {
        if (rifts) elev = applyRifts(elev, x, y, base);
        if (margins) elev = applyMargin(elev, x, y, base);
        return elev;
    }

    // =========================================================================
    // Rift seas
    // =========================================================================

    /**
     * One rift: centre (cx, cy), unit direction along it (dx, dy), half-length, half-width at the middle, axial depth,
     * shoulder height, bend amplitude and wavenumber, phase, which ends open to the ocean (a: -dir end, b: +dir end).
     */
    record Rift(float cx, float cy, float dx, float dy, float halfLen, float halfWidth, float depth, float shoulder,
                float bend, float bendK, float phase, boolean openA, boolean openB, long h) {
        boolean exists() { return halfLen > 0; }
    }

    /** The rift seeded in cell (cxi, cyi), or {@link #NONE}. Cached; depends only on the seed and the base map. */
    Rift rift(int cxi, int cyi, Base base) {
        long key = ((long) cxi << 32) ^ (cyi & 0xFFFFFFFFL);
        Rift r = riftCache.get(key);
        if (r != null) return r;
        r = makeRift(cxi, cyi, base);
        riftCache.put(key, r);
        if (riftCache.size() > 4096) riftCache.clear();
        return r;
    }

    private Rift makeRift(int cxi, int cyi, Base base) {
        long h = mix(seed ^ ((long) cxi * 0x9E3779B97F4A7C15L) ^ ((long) cyi * 0xC2B2AE3D27D4EB4FL) ^ 0x51F7L);
        if (unit(h) > RIFT_CHANCE) return NONE;
        float halfLen = RIFT_LEN_MIN + (RIFT_LEN_MAX - RIFT_LEN_MIN) * (float) unit(mix(h + 4));
        float hw = RIFT_HW_MIN + (RIFT_HW_MAX - RIFT_HW_MIN) * (float) unit(mix(h + 5));
        float depth = RIFT_DEPTH_MIN + (RIFT_DEPTH_MAX - RIFT_DEPTH_MIN) * (float) unit(mix(h + 6));
        float shoulder = SHOULDER_MIN + (SHOULDER_MAX - SHOULDER_MIN) * (float) unit(mix(h + 7));
        float bend = (0.6f + 1.4f * (float) unit(mix(h + 8))) * hw;
        float bendK = (float) (Math.PI * (0.5 + 0.8 * unit(mix(h + 9))) / halfLen);
        float phase = (float) (unit(mix(h + 10)) * 2 * Math.PI);
        // Rifts split continents: try the highest ground among a 4x4 grid of spots in the cell first (well inside a
        // continent), each in 4 directions from a random start, and keep the first that splits land and reaches the sea.
        float[][] spots = new float[16][];
        for (int k = 0; k < 16; k++) {
            float sx = (cxi + (k % 4 + 0.2f + 0.6f * (float) unit(mix(h + 20 + k))) / 4f) * RIFT_CELL;
            float sy = (cyi + (k / 4 + 0.2f + 0.6f * (float) unit(mix(h + 40 + k))) / 4f) * RIFT_CELL;
            spots[k] = new float[]{sx, sy, base.elev(sx, sy)};
        }
        java.util.Arrays.sort(spots, (p, q) -> Float.compare(q[2], p[2]));
        double ang0 = unit(mix(h + 3)) * Math.PI;
        for (int k = 0; k < 6; k++) {
            if (spots[k][2] < 150f) break;
            for (int t = 0; t < 4; t++) {
                double ang = ang0 + t * Math.PI / 4;
                float dx = (float) Math.cos(ang), dy = (float) Math.sin(ang);
                Rift r = new Rift(spots[k][0], spots[k][1], dx, dy, halfLen, hw, depth, shoulder, bend, bendK, phase, false, false, h);
                int land = 0, n = 0;
                for (float s = -halfLen; s <= halfLen; s += 2f) {
                    float[] p = axisPoint(r, s);
                    n++;
                    if (base.elev(p[0], p[1]) > 50f) land++;
                }
                if (land < 0.55f * n) continue;
                // It reaches the ocean at one end at least (an enclosed rift would be a sea-level hole in a continent).
                boolean openA = endOpen(r, -1, base), openB = endOpen(r, 1, base);
                if (!openA && !openB) continue;
                return new Rift(r.cx, r.cy, dx, dy, halfLen, hw, depth, shoulder, bend, bendK, phase, openA, openB, h);
            }
        }
        return NONE;
    }

    /** Whether the ocean (deeper than 300 m) lies within OPEN_RUN past end {@code sign} of the rift. */
    private static boolean endOpen(Rift r, int sign, Base base) {
        for (float t = 0; t <= OPEN_RUN; t += 1f) {
            float[] p = axisPoint(r, sign * (r.halfLen + t));
            if (base.elev(p[0], p[1]) < -300f) return true;
        }
        return false;
    }

    /** The rift's axis at arc position s (coarse pixels from the centre, along its direction). */
    private static float[] axisPoint(Rift r, float s) {
        float off = r.bend * (float) Math.sin(r.bendK * s + r.phase);
        return new float[]{r.cx + r.dx * s - r.dy * off, r.cy + r.dy * s + r.dx * off};
    }

    /**
     * Where (x, y) sits relative to rift {@code r}: {s, distance from the axis (coarse pixels), half-width there,
     * strength along it (0..1)}, or null if it is out of reach.
     */
    private float[] riftFrame(Rift r, float x, float y) {
        float px = x - r.cx, py = y - r.cy;
        float s = px * r.dx + py * r.dy;
        float lim = r.halfLen + OPEN_RUN;
        if (Math.abs(s) > lim + 2) return null;
        float off = r.bend * (float) Math.sin(r.bendK * s + r.phase);
        float q = -px * r.dy + py * r.dx - off;
        // A ragged coastline at ~70 km.
        float wob = riftWobble.GetNoise(x, y);
        float d = Math.abs(q) + 0.9f * wob;
        boolean open = s < 0 ? r.openA : r.openB;
        float u = Math.abs(s) / r.halfLen;
        float width, along;
        if (u <= 1f) {
            // Narrowing toward a closed end (the Gulf of Suez); at an open end it stays wide.
            width = open ? r.halfWidth : r.halfWidth * (float) Math.sqrt(Math.max(0f, 1f - u * u * u * u));
            along = 1f;
        } else {
            if (!open) return null;
            width = r.halfWidth;
            along = 1f - smoothstep(r.halfLen, lim, Math.abs(s));    // runs on into the ocean, fading
        }
        if (d > width + 9f) return null;
        return new float[]{s, d, width, along};
    }

    private float applyRifts(float elev, float x, float y, Base base) {
        int cxi = (int) Math.floor(x / RIFT_CELL), cyi = (int) Math.floor(y / RIFT_CELL);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                Rift r = rift(cxi + a, cyi + b, base);
                if (!r.exists()) continue;
                float[] f = riftFrame(r, x, y);
                if (f == null) continue;
                float d = f[1], w = f[2], along = f[3];
                if (w < 0.5f) continue;
                float u = d / w;
                // The sea: an axial trough falling from a narrow shelf at the coast (u = 1) to the full depth at the axis.
                float trough = -r.depth * (1f - u * u) * (float) Math.sqrt(Math.max(0f, 1f - u * u)) + 120f * u * u;
                float inner = 1f - smoothstep(0.85f, 1.1f, u);
                float e = elev + (Math.min(elev, trough) - elev) * along * inner;
                // The rift shoulders: uplifted flanks just behind the coasts, on land only.
                float xo = d - w;
                float sh = r.shoulder * (float) Math.exp(-sq((xo - 2.5f) / 3f)) * smoothstep(-0.5f, 1.5f, xo);
                if (elev > 0f) e += sh * along * smoothstep(0f, 300f, elev + 300f);
                elev = e;
            }
        }
        return elev;
    }

    /**
     * Distance (coarse pixels) from (x, y) to the axis of the deep part of a rift sea, or {@code Float.MAX_VALUE}:
     * its young spreading axis, where the ocean features lay pillow lava.
     */
    public float riftAxis(float x, float y, Base base) {
        if (!rifts) return Float.MAX_VALUE;
        int cxi = (int) Math.floor(x / RIFT_CELL), cyi = (int) Math.floor(y / RIFT_CELL);
        float best = Float.MAX_VALUE;
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                Rift r = rift(cxi + a, cyi + b, base);
                if (!r.exists()) continue;
                float px = x - r.cx, py = y - r.cy;
                float s = px * r.dx + py * r.dy;
                if (Math.abs(s) > r.halfLen * 0.9f) continue;          // not out at the narrowing ends
                float off = r.bend * (float) Math.sin(r.bendK * s + r.phase);
                float q = Math.abs(-px * r.dy + py * r.dx - off);
                best = Math.min(best, q);
            }
        }
        return best;
    }

    /** Rift seas whose centres lie in the coarse window: {x, y, depth} each, for the finder and the explorer. */
    public java.util.List<float[]> riftCentres(float x0, float y0, float x1, float y1, Base base) {
        java.util.List<float[]> out = new java.util.ArrayList<>();
        if (!rifts) return out;
        for (int cy = (int) Math.floor(y0 / RIFT_CELL) - 1; cy <= (int) Math.floor(y1 / RIFT_CELL) + 1; cy++) {
            for (int cx = (int) Math.floor(x0 / RIFT_CELL) - 1; cx <= (int) Math.floor(x1 / RIFT_CELL) + 1; cx++) {
                Rift r = rift(cx, cy, base);
                if (!r.exists()) continue;
                float off = r.bend * (float) Math.sin(r.phase);
                float mx = r.cx - r.dy * off, my = r.cy + r.dx * off;
                if (mx >= x0 && mx < x1 && my >= y0 && my < y1) out.add(new float[]{mx, my, r.depth});
            }
        }
        return out;
    }

    // =========================================================================
    // Active continental margins
    // =========================================================================

    /** Active-margin zone strength (0..1) at a coarse position. */
    public float marginZone(float x, float y) {
        if (!margins) return 0f;
        return smoothstep(MARGIN_ZONE_LOW, MARGIN_ZONE_HIGH, marginMask.GetNoise(x, y));
    }

    /**
     * The coast near (x, y) on the base map: {signed distance (coarse pixels; positive inland, negative offshore),
     * unit direction toward the coast's other side}, or null if no coast lies within {@link #COAST_SEARCH}.
     */
    float[] coast(float x, float y, Base base) {
        float e0 = base.elev(x, y);
        boolean land = e0 >= 0f;
        final int DIRS = 16;
        float prev = e0;
        float[] lastE = new float[DIRS];
        java.util.Arrays.fill(lastE, e0);
        for (int r = 1; r <= COAST_SEARCH; r++) {
            float bestT = Float.MAX_VALUE;
            int bestK = -1;
            for (int k = 0; k < DIRS; k++) {
                double a = k * 2 * Math.PI / DIRS;
                float ex = base.elev(x + (float) Math.cos(a) * r, y + (float) Math.sin(a) * r);
                if ((ex >= 0f) != land) {
                    // Where between r-1 and r the sign changed (linear).
                    float t = (r - 1) + lastE[k] / (lastE[k] - ex);
                    if (t < bestT) { bestT = t; bestK = k; }
                }
                lastE[k] = ex;
            }
            if (bestK >= 0) {
                double a = bestK * 2 * Math.PI / DIRS;
                return new float[]{land ? bestT : -bestT, (float) Math.cos(a), (float) Math.sin(a)};
            }
        }
        return null;
    }

    private float applyMargin(float elev, float x, float y, Base base) {
        float z = marginZone(x, y);
        if (z <= 0f) return elev;
        float[] c = coast(x, y, base);
        if (c == null) return elev;
        float dist = c[0];
        float v = 0.5f + 0.5f * marginVary.GetNoise(x, y);          // along-strike variation, 0..1
        if (dist >= 0f) {
            // Inland: the coastal range.
            float h = RANGE_MIN + (RANGE_MAX - RANGE_MIN) * v;
            float at = RANGE_AT + 1.5f * (v - 0.5f);
            float u = h * (float) Math.exp(-sq((dist - at) / RANGE_WIDTH));
            return elev + z * u;
        }
        float ds = -dist;
        // Offshore: the ocean in front must be wide enough to hold a trench (look past it, away from the coast).
        float sx = -c[1], sy = -c[2];
        float reach = Math.max(0f, 12f - ds);
        float far = base.elev(x + sx * reach, y + sy * reach);
        float wide = smoothstep(-800f, -2500f, far);
        float k = z * wide;
        if (k <= 0f) return elev;
        float at = TRENCH_AT_MIN + (TRENCH_AT_MAX - TRENCH_AT_MIN) * v;
        // A shelf of a few km, a steep slope to ~3 km, then the trench (steeper on its landward wall) and the outer rise.
        float slope = -20f - 130f * smoothstep(0f, 0.5f, ds) - 3000f * smoothstep(0.5f, 3.5f, ds);
        // Wide enough (~35 km) for the coarse model (7.7 km pixels) to draw it: at 2-3 px it only deepened ~300 m.
        float wall = ds < at ? sq((ds - at) / 3f) : sq((ds - at) / 4.5f);
        float trench = -TRENCH_DEPTH * (float) Math.exp(-wall);
        float rise = OUTER_RISE * (float) Math.exp(-sq((ds - at - 8f) / 3.5f));
        float target = slope + trench + rise;
        float fade = 1f - smoothstep(at + 8f, at + 14f, ds);
        // Never raise the sea floor above the slope profile's own depth (the trench and slope only deepen it),
        // except for the gentle outer rise.
        float shaped = Math.min(elev, slope + trench) + rise;
        float t = k * fade;
        return elev + (Math.min(shaped, target) - elev) * t;
    }

    /**
     * A marker on each active margin's trench in the coarse window, one per ~230 km cell where the trench is strongest:
     * {x, y, strength}.
     */
    public java.util.List<float[]> marginMarkers(float x0, float y0, float x1, float y1, Base base) {
        java.util.List<float[]> out = new java.util.ArrayList<>();
        if (!margins) return out;
        final float CELL = 30f;
        for (float cy = (float) Math.floor(y0 / CELL) * CELL; cy < y1; cy += CELL) {
            for (float cx = (float) Math.floor(x0 / CELL) * CELL; cx < x1; cx += CELL) {
                float best = 0.3f, bx = 0, by = 0;
                for (float y = Math.max(cy, y0); y < Math.min(cy + CELL, y1); y += 2f) {
                    for (float x = Math.max(cx, x0); x < Math.min(cx + CELL, x1); x += 2f) {
                        float z = marginZone(x, y);
                        if (z <= best) continue;
                        if (base.elev(x, y) > -1000f) continue;
                        float[] c = coast(x, y, base);
                        if (c == null || c[0] >= 0) continue;
                        float v = 0.5f + 0.5f * marginVary.GetNoise(x, y);
                        float at = TRENCH_AT_MIN + (TRENCH_AT_MAX - TRENCH_AT_MIN) * v;
                        if (Math.abs(-c[0] - at) > 1.5f) continue;
                        float far = base.elev(x - c[1] * 6f, y - c[2] * 6f);
                        float s = z * smoothstep(-800f, -2500f, far);
                        if (s > best) { best = s; bx = x; by = y; }
                    }
                }
                if (best > 0.3f) out.add(new float[]{bx, by, best});
            }
        }
        return out;
    }

    // =========================================================================
    // Math
    // =========================================================================

    private static float sq(float v) { return v * v; }

    private static float smoothstep(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
