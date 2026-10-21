package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Sand landforms built by waves and longshore drift on gentle coasts, laid out per coastal region:
 * <ul>
 *   <li><b>Spits</b> (Spurn Head, Farewell Spit, Cape Cod's Provincetown hook): a sand bar growing from a headland in
 *       the direction the drift runs along the coast, 70-150 m wide and a few metres high, curving landward at its tip
 *       into a hook, with sheltered water behind it. It only grows over shallow water, and where it reaches land again
 *       it closes off a lagoon.</li>
 *   <li><b>Tombolos</b> (Chesil Beach, St Ninian's Isle, Monte Argentario): a sand neck tying a small island close
 *       offshore to the mainland, bowed a little by the drift.</li>
 *   <li><b>Cuspate forelands</b> (Dungeness, Cape Hatteras): a triangular point of sand built out from a long, straight,
 *       gentle coast.</li>
 * </ul>
 *
 * <p>The region stage: region (ri, rj) owns native pixels {@code [ri*SIZE, (ri+1)*SIZE)} on both axes and analyses its
 * square plus a {@link #MARGIN} on every side ({@link Analysis}: the land blurred at two scales for shore normals and
 * headlands, land masses, a drift direction). Features are rooted in the region's own square and reach at most
 * {@link #REACH_PX} from their roots, so a tile asks every region within that distance and all tiles agree. The analysis
 * is generic, so other coastal landforms (barrier islands) can reuse it. Coordinates are native pixels (x east,
 * z south), pixel centres at {@code +0.5}.
 */
public final class CoastPlan {

    /** Region side and margin (native pixels). */
    public static final int SIZE = 256, MARGIN = 160;
    /** Farthest any feature reaches from its root (pixels). */
    public static final int REACH_PX = 150;

    /** Share of coastal regions inside sand-landform zones (noise level; higher = rarer). */
    private static final float ZONE_LEVEL = 0.25f;
    /** Chances per candidate: spit (per headland cell), tombolo (per small island), cuspate foreland (per straight cell). */
    private static final double SPIT_CHANCE = 0.5, TOMBOLO_CHANCE = 0.5, FORELAND_CHANCE = 0.2;
    /** Cells (pixels, ~2.9 km) within which one spit or foreland may root. */
    private static final int CELL = 96;
    /** Deepest water (metres) a tombolo or foreland is built over. */
    private static final float MAX_BAR_DEPTH = 15f;
    /**
     * Spits (Oct 21, after Calvin found none in game): deepest water (m) along the line a spit grows on, highest ground
     * (m) within 120 m behind its root, how far out (blurred land fraction, lower = farther) the coast's line must run
     * within ~1.2 km, and by how much more than it does updrift. The first values (15 m, 12 m, 0.15, 0.25) suited only
     * a very gentle, very ragged test coast.
     */
    private static final float SPIT_MAX_DEPTH = 20f, SPIT_MAX_BEHIND = 20f, SPIT_FAR = 0.25f, SPIT_TURN = 0.2f;
    /** Farthest (pixels, 2.4 km) along the coast's line that the turn test looks; at least 40 (1.2 km). */
    private static final int SPIT_LOOK_PX = 80;
    /**
     * Ground (metres) a plan can change: nothing outside [RAISE_LOW, RAISE_HIGH] is ever touched, so a tile with no
     * ground in that range can skip the plans and still agree with its neighbours ({@link #touches}).
     */
    public static final float RAISE_LOW = -SPIT_MAX_DEPTH - 5.5f, RAISE_HIGH = 5.5f;

    public static final int SPIT = 0, TOMBOLO = 1, FORELAND = 2;

    /** Supplies {elevation, climate} for a native window, as {@link WorldPipeline#get} does. */
    public interface Source {
        float[][] get(int i1, int j1, int i2, int j2);
    }

    /** One sand bar: a polyline with a half-width (pixels) and crest height (metres) at each point. */
    public static final class Bar {
        public final int kind;
        final float[] x, z, halfW, crest;
        final float bx0, bz0, bx1, bz1;
        private volatile float[] gridD;
        private float[] gridW, gridC;
        private int gx0, gz0, gw, gh;

        Bar(int kind, List<float[]> pts) {
            this.kind = kind;
            int n = pts.size();
            x = new float[n]; z = new float[n]; halfW = new float[n]; crest = new float[n];
            float a = Float.MAX_VALUE, b = Float.MAX_VALUE, c = -Float.MAX_VALUE, d = -Float.MAX_VALUE, mw = 0;
            for (int i = 0; i < n; i++) {
                float[] p = pts.get(i);
                x[i] = p[0]; z[i] = p[1]; halfW[i] = p[2]; crest[i] = p[3];
                a = Math.min(a, x[i]); c = Math.max(c, x[i]); b = Math.min(b, z[i]); d = Math.max(d, z[i]);
                mw = Math.max(mw, halfW[i]);
            }
            bx0 = a - mw - 3; bz0 = b - mw - 3; bx1 = c + mw + 3; bz1 = d + mw + 3;
        }

        /** The middle of the bar (for the finder). */
        public float midX() { return x[x.length / 2]; }
        public float midZ() { return z[z.length / 2]; }

        float raise(float px, float pz, float e) {
            if (px < bx0 || px > bx1 || pz < bz0 || pz > bz1) return e;
            if (gridD == null) build();
            float fx = px - 0.5f - gx0, fz = pz - 0.5f - gz0;
            int c0 = (int) Math.floor(fx), r0 = (int) Math.floor(fz);
            if (c0 < 0 || r0 < 0 || c0 + 1 >= gw || r0 + 1 >= gh) return e;
            float tx = fx - c0, tz = fz - r0;
            float d = lerp2(gridD, r0, c0, tx, tz), w = lerp2(gridW, r0, c0, tx, tz), cr = lerp2(gridC, r0, c0, tx, tz);
            if (w <= 0 || d >= w) return e;
            if (e < -SPIT_MAX_DEPTH - 5) return e;
            // A flat crest over the middle of the bar, its shoulders falling to the old ground at the edges.
            float shoulder = smooth(0.45f, 1f, d / w);
            float top = cr + (Math.min(e, cr) - cr) * shoulder;
            return Math.max(e, top);
        }

        private synchronized void build() {
            if (gridD != null) return;
            gx0 = (int) Math.floor(bx0); gz0 = (int) Math.floor(bz0);
            gw = (int) Math.ceil(bx1) - gx0 + 2; gh = (int) Math.ceil(bz1) - gz0 + 2;
            float[] gd = new float[gw * gh], gwd = new float[gw * gh], gc = new float[gw * gh];
            Arrays.fill(gd, Float.MAX_VALUE);
            for (int i = 0; i + 1 < x.length; i++) {
                float ax = x[i], az = z[i], ex = x[i + 1] - ax, ez = z[i + 1] - az, l2 = ex * ex + ez * ez;
                float mw = Math.max(halfW[i], halfW[i + 1]) + 2;
                int c0 = (int) Math.floor(Math.min(ax, ax + ex) - mw) - gx0, c1 = (int) Math.ceil(Math.max(ax, ax + ex) + mw) - gx0;
                int r0 = (int) Math.floor(Math.min(az, az + ez) - mw) - gz0, r1 = (int) Math.ceil(Math.max(az, az + ez) + mw) - gz0;
                for (int r = Math.max(0, r0); r <= Math.min(gh - 1, r1); r++) {
                    for (int c = Math.max(0, c0); c <= Math.min(gw - 1, c1); c++) {
                        float qx = gx0 + c + 0.5f, qz = gz0 + r + 0.5f;
                        float t = l2 > 0 ? Math.max(0f, Math.min(1f, ((qx - ax) * ex + (qz - az) * ez) / l2)) : 0f;
                        float dx = ax + t * ex - qx, dz = az + t * ez - qz;
                        float dd = (float) Math.sqrt(dx * dx + dz * dz);
                        int k = r * gw + c;
                        if (dd < gd[k]) {
                            gd[k] = dd;
                            gwd[k] = halfW[i] + (halfW[i + 1] - halfW[i]) * t;
                            gc[k] = crest[i] + (crest[i + 1] - crest[i]) * t;
                        }
                    }
                }
            }
            for (int k = 0; k < gd.length; k++) if (gd[k] == Float.MAX_VALUE) { gd[k] = 99f; gwd[k] = 0f; }
            gridW = gwd;
            gridC = gc;
            gridD = gd;
        }

        private float lerp2(float[] g, int r0, int c0, float tx, float tz) {
            float a = g[r0 * gw + c0], b = g[r0 * gw + c0 + 1], c = g[(r0 + 1) * gw + c0], d = g[(r0 + 1) * gw + c0 + 1];
            return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz;
        }
    }

    /**
     * A cuspate foreland: a point of sand built out from the shore, its sides concave (the cusp) and its edge ragged.
     * Laid out from the middle of its base on the old shore (mx, mz), pointing along (ux, uz) to an apex {@code len}
     * pixels out, {@code base} pixels to either side at the shore.
     */
    public static final class Foreland {
        final float mx, mz, ux, uz, len, base, crest, ox, oz;

        Foreland(float mx, float mz, float ux, float uz, float len, float base, float crest, float ox, float oz) {
            this.mx = mx; this.mz = mz; this.ux = ux; this.uz = uz; this.len = len; this.base = base; this.crest = crest;
            this.ox = ox; this.oz = oz;
        }

        public float apexX() { return mx + ux * len; }
        public float apexZ() { return mz + uz * len; }

        float raise(float px, float pz, float e) {
            if (e < -MAX_BAR_DEPTH - 5 || e > crest) return e;
            float qx = px - mx, qz = pz - mz;
            float out = qx * ux + qz * uz, across = Math.abs(qx * uz - qz * ux);
            if (out < -6 || out > len + 6 || across > base + 6) return e;      // the soft edge reaches 3 + 1.6 px out
            float u = Math.max(0f, out / len);
            // Half-width at this distance out: concave sides closing to a cusp, plus a ragged edge.
            float hw = u >= 1 ? 0f : base * (float) Math.pow(1 - u, 1.3);
            float wob = 1.6f * edgeNoise().GetNoise(px + ox, pz + oz);
            float edge = smooth(-3f, 2f, hw - across + wob) * smooth(-6f, 0f, out);
            if (edge <= 0) return e;
            // Highest along the old shore, falling gently toward the apex.
            float top = crest * (1 - 0.55f * u * u);
            return Math.max(e, e + (top - e) * edge);
        }
    }

    private static volatile FastNoiseLite edgeNoise;

    private static FastNoiseLite edgeNoise() {
        FastNoiseLite n = edgeNoise;
        if (n == null) {
            n = new FastNoiseLite(3907);
            n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
            n.SetFrequency(1f / 9f);
            n.SetFractalType(FastNoiseLite.FractalType.FBm);
            n.SetFractalOctaves(2);
            edgeNoise = n;
        }
        return n;
    }

    public final List<Bar> bars;
    public final List<Foreland> forelands;

    /**
     * Why shore pixels in this region's own square did not grow a spit (counts; see {@link #SPIT_REASONS}), for the
     * finder's report; all zero when the region has no coast.
     */
    public final int[] spitStats;

    /** Names of the {@link #spitStats} entries. */
    public static final String[] SPIT_REASONS = {
            "shore pixels checked", "drift runs across the coast", "the coast's line runs onto land",
            "water too deep along the line", "the coast doesn't turn away", "land behind too high or mangrove coast",
            "spit sites", "sites skipped by chance", "spits too short (deep water or land ahead)", "spits built"};

    private CoastPlan(List<Bar> bars, List<Foreland> forelands, int[] spitStats) {
        this.bars = bars;
        this.forelands = forelands;
        this.spitStats = spitStats;
    }

    static final CoastPlan EMPTY = new CoastPlan(List.of(), List.of(), new int[SPIT_REASONS.length]);

    public boolean isEmpty() {
        return bars.isEmpty() && forelands.isEmpty();
    }

    /** The ground (metres) at native point (x, z) after the sand landforms, given the ground {@code e}. Only raises. */
    public float apply(float x, float z, float e) {
        for (Bar b : bars) e = b.raise(x, z, e);
        for (Foreland f : forelands) e = f.raise(x, z, e);
        return e;
    }

    /**
     * Whether any ground in {@code grid} lies where a plan could change it. A tile whose ground doesn't is left exactly as
     * the plans would leave it, so it can skip looking them up (and its neighbours still agree with it).
     */
    public static boolean touches(float[][] grid) {
        for (float[] row : grid) for (float e : row) if (e >= RAISE_LOW && e <= RAISE_HIGH) return true;
        return false;
    }

    /** Whether region (ri, rj) lies in a sand-landform zone (a few hundred km across); others have no plan. */
    public static boolean zoneActive(long seed, int ri, int rj) {
        FastNoiseLite n = zoneNoise(seed);
        float cx = (rj + 0.5f) * SIZE * 0.03f, cz = (ri + 0.5f) * SIZE * 0.03f;   // km
        return n.GetNoise(cx, cz) > ZONE_LEVEL;
    }

    private static volatile FastNoiseLite zoneNoise;
    private static volatile long zoneSeed;

    private static FastNoiseLite zoneNoise(long seed) {
        FastNoiseLite n = zoneNoise;
        if (n == null || zoneSeed != seed) {
            n = new FastNoiseLite((int) (seed ^ (seed >>> 32)) + 7741);
            n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
            n.SetFrequency(1f / 220f);
            n.SetFractalType(FastNoiseLite.FractalType.FBm);
            n.SetFractalOctaves(2);
            zoneSeed = seed;
            zoneNoise = n;
        }
        return n;
    }

    // =========================================================================
    // The coastal region analysis (generic: barrier islands can reuse it)
    // =========================================================================

    /** A coastal region's window: ground, climate, and the shore geometry derived from it. */
    public static final class Analysis {
        public final int i0, j0, H, W;
        public final float[] elev, temp, precip;
        /** Land fraction blurred over ~240 m and ~600 m (0..1). */
        public final float[] landNear, landFar;
        /** Land mass label per pixel (0 = sea), and each label's area in pixels. */
        public final int[] label;
        public final int[] area;
        /** Longshore drift direction (unit vector), turning slowly across the world. */
        public final float driftX, driftZ;

        Analysis(int i0, int j0, int H, int W, float[] elev, float[] climate, float driftAngle) {
            this.i0 = i0; this.j0 = j0; this.H = H; this.W = W; this.elev = elev;
            int n = H * W;
            temp = climate != null ? Arrays.copyOfRange(climate, 0, n) : null;
            precip = climate != null ? Arrays.copyOfRange(climate, 2 * n, 3 * n) : null;
            float[] land = new float[n];
            for (int k = 0; k < n; k++) land[k] = elev[k] >= 0f ? 1f : 0f;
            landNear = boxBlur(land, H, W, 8);
            landFar = boxBlur(land, H, W, 20);
            label = new int[n];
            List<Integer> areas = new ArrayList<>();
            areas.add(0);
            int[] stack = new int[n];
            for (int k = 0; k < n; k++) {
                if (land[k] == 0f || label[k] != 0) continue;
                int id = areas.size(), sp = 0, count = 0;
                stack[sp++] = k;
                label[k] = id;
                while (sp > 0) {
                    int q = stack[--sp];
                    count++;
                    int r = q / W, c = q % W;
                    if (r > 0 && land[q - W] != 0f && label[q - W] == 0) { label[q - W] = id; stack[sp++] = q - W; }
                    if (r < H - 1 && land[q + W] != 0f && label[q + W] == 0) { label[q + W] = id; stack[sp++] = q + W; }
                    if (c > 0 && land[q - 1] != 0f && label[q - 1] == 0) { label[q - 1] = id; stack[sp++] = q - 1; }
                    if (c < W - 1 && land[q + 1] != 0f && label[q + 1] == 0) { label[q + 1] = id; stack[sp++] = q + 1; }
                }
                areas.add(count);
            }
            area = areas.stream().mapToInt(Integer::intValue).toArray();
            driftX = (float) Math.cos(driftAngle);
            driftZ = (float) Math.sin(driftAngle);
        }

        public int index(int pi, int pj) {
            int r = pi - i0, c = pj - j0;
            return r < 0 || c < 0 || r >= H || c >= W ? -1 : r * W + c;
        }

        /** Ground at native point (x, z) (nearest pixel), or NaN outside the window. */
        public float elevAt(float x, float z) {
            int k = index((int) Math.floor(z), (int) Math.floor(x));
            return k < 0 ? Float.NaN : elev[k];
        }

        /** Seaward unit normal of the shore at pixel k (down the blurred land), or null where it is flat. */
        public float[] normal(int k) {
            int r = k / W, c = k % W;
            if (r < 1 || c < 1 || r >= H - 1 || c >= W - 1) return null;
            float gx = landNear[k + 1] - landNear[k - 1], gz = landNear[k + W] - landNear[k - W];
            float g = (float) Math.hypot(gx, gz);
            return g < 1e-3f ? null : new float[]{-gx / g, -gz / g};
        }

        /** Seaward unit normal of the coast's general line at pixel k (down the land blurred over ~1.2 km), or null. */
        public float[] normalFar(int k) {
            int r = k / W, c = k % W;
            if (r < 3 || c < 3 || r >= H - 3 || c >= W - 3) return null;
            float gx = landFar[k + 3] - landFar[k - 3], gz = landFar[k + 3 * W] - landFar[k - 3 * W];
            float g = (float) Math.hypot(gx, gz);
            return g < 1e-3f ? null : new float[]{-gx / g, -gz / g};
        }

        /** Land blurred over ~1.2 km at native point (x, z), or NaN outside the window. */
        public float landFarAt(float x, float z) {
            int k = index((int) Math.floor(z), (int) Math.floor(x));
            return k < 0 ? Float.NaN : landFar[k];
        }

        /** Whether pixel k is land touching the sea. */
        public boolean isShore(int k) {
            int r = k / W, c = k % W;
            if (elev[k] < 0f || r < 1 || c < 1 || r >= H - 1 || c >= W - 1) return false;
            return elev[k - 1] < 0f || elev[k + 1] < 0f || elev[k - W] < 0f || elev[k + W] < 0f;
        }

        /**
         * Whether the shore at pixel k (normal n) is a gentle, sandy one: low ground behind it, shallow water in front,
         * and not a hot, wet mangrove coast.
         */
        public boolean gentleSandy(int k, float[] n) {
            return gentleSandy(k, n, 12f, 12f);
        }

        /** As {@link #gentleSandy(int, float[])}, with the highest ground behind and deepest water in front given. */
        public boolean gentleSandy(int k, float[] n, float maxBehind, float maxDepth) {
            if (temp != null && temp[k] >= 24f && precip[k] >= 1200f) return false;
            float x = j0 + k % W + 0.5f, z = i0 + k / W + 0.5f;
            for (int s = 1; s <= 4; s++) {
                float e = elevAt(x - n[0] * s, z - n[1] * s);
                if (Float.isNaN(e) || e > maxBehind) return false;
            }
            float off = elevAt(x + n[0] * 8, z + n[1] * 8);
            return !Float.isNaN(off) && off > -maxDepth;
        }
    }

    // =========================================================================
    // Building a region's plan
    // =========================================================================

    /**
     * Analyses region (ri, rj) and lays out its sand landforms. {@code archipelago} gives the archipelago zone strength
     * at a native pixel (these coasts are left alone for now).
     */
    public static CoastPlan build(long seed, int ri, int rj, Source source, java.util.function.BiFunction<Double, Double, Float> archipelago) {
        int i0 = ri * SIZE - MARGIN, j0 = rj * SIZE - MARGIN, span = SIZE + 2 * MARGIN;
        float[][] data = source.get(i0, j0, i0 + span, j0 + span);
        float[] elev = data[0];
        boolean land = false, sea = false;
        for (float e : elev) { if (e >= 0) land = true; else sea = true; if (land && sea) break; }
        if (!land || !sea) return EMPTY;
        float drift = (float) (zoneNoise(seed).GetNoise((rj + 0.5f) * SIZE * 0.03f * 0.5f + 999, (ri + 0.5f) * SIZE * 0.03f * 0.5f) * 2 * Math.PI);
        Analysis an = new Analysis(i0, j0, span, span, elev, data[1], drift);
        List<Bar> bars = new ArrayList<>();
        List<Foreland> forelands = new ArrayList<>();
        int own0i = ri * SIZE, own0j = rj * SIZE;
        int[] stats = new int[SPIT_REASONS.length];

        // Spits and forelands: at most one each per cell, from the shore pixels that suit them.
        for (int ci = 0; ci < SIZE / CELL + 1; ci++) {
            for (int cj = 0; cj < SIZE / CELL + 1; cj++) {
                int bestSpit = -1, bestFore = -1;
                long bestSpitH = Long.MAX_VALUE, bestForeH = Long.MAX_VALUE;
                for (int i = own0i + ci * CELL; i < Math.min(own0i + SIZE, own0i + (ci + 1) * CELL); i++) {
                    for (int j = own0j + cj * CELL; j < Math.min(own0j + SIZE, own0j + (cj + 1) * CELL); j++) {
                        int k = an.index(i, j);
                        if (k < 0 || !an.isShore(k)) continue;
                        if (archipelago.apply((double) i, (double) j) > 0.3f) continue;
                        float far = an.landFar[k];
                        long h = mix(seed ^ ((long) i * 0x9E3779B97F4A7C15L) ^ ((long) j * 0xC2B2AE3D27D4EB4FL));
                        int why = spitSite(an, k);
                        stats[0]++;
                        if (why > 0) stats[why]++;
                        else if (h < bestSpitH) { bestSpitH = h; bestSpit = k; }                          // coast turns away
                        if (Math.abs(far - 0.5f) < 0.04f && Math.abs(an.landNear[k] - 0.5f) < 0.12f && h < bestForeH) {
                            bestForeH = h; bestFore = k;                                                   // straight
                        }
                    }
                }
                long cellH = mix(seed ^ ((long) (own0i / CELL + ci) * 0x632BE59BD9B4E019L) ^ ((long) (own0j / CELL + cj) * 0x85EBCA77C2B2AE63L));
                if (bestSpit >= 0) {
                    stats[6]++;
                    if (unit(cellH) >= SPIT_CHANCE) stats[7]++;
                    else {
                        Bar b = spit(an, bestSpit, cellH);
                        if (b != null) { bars.add(b); stats[9]++; } else stats[8]++;
                    }
                }
                if (bestFore >= 0 && unit(mix(cellH + 1)) < FORELAND_CHANCE) {
                    Foreland f = foreland(an, bestFore, mix(cellH + 2));
                    if (f != null) forelands.add(f);
                }
            }
        }
        // Tombolos: small islands whose middle lies in this region's own square.
        tombolos(seed, an, own0i, own0j, archipelago, bars);
        return new CoastPlan(Collections.unmodifiableList(bars), Collections.unmodifiableList(forelands), stats);
    }

    /** Unit vector along the coast's general line at pixel k, pointing the way the drift runs, or null if it runs across. */
    private static float[] downdrift(Analysis an, float[] n) {
        float tx = -n[1], tz = n[0];
        float along = tx * an.driftX + tz * an.driftZ;
        if (Math.abs(along) < 0.3f) return null;
        return along < 0 ? new float[]{-tx, -tz} : new float[]{tx, tz};
    }

    /**
     * Whether shore pixel k is where a spit grows: carried on downdrift in a straight line, the coast's general line runs
     * out over shallow water, because the coast turns away landward there (the end of a headland, the mouth of a bay).
     * Returns 0 if so, else the first reason it is not (an index into {@link #SPIT_REASONS}).
     */
    private static int spitSite(Analysis an, int k) {
        float[] n = an.normalFar(k);
        if (n == null) return 1;
        float[] t = downdrift(an, n);
        if (t == null) return 1;
        float x = an.j0 + k % an.W + 0.5f, z = an.i0 + k / an.W + 0.5f;
        // Along the line over shallow water until it is clearly away from the coast, within 1.2-2.4 km (a broad headland
        // turns away more slowly than a sharp one; a coast that merely wobbles stays near its line). Asymmetric: carried
        // back updrift the same distance, the line stays along the coast, so this is a bend, not a bump on a straight coast.
        for (int s = 10; s <= SPIT_LOOK_PX; s += 10) {
            float e = an.elevAt(x + t[0] * s, z + t[1] * s);
            if (Float.isNaN(e) || e >= 0f) return 2;
            if (e < -SPIT_MAX_DEPTH) return 3;
            if (s < 40) continue;
            float far = an.landFarAt(x + t[0] * s, z + t[1] * s), back = an.landFarAt(x - t[0] * s, z - t[1] * s);
            if (Float.isNaN(far) || Float.isNaN(back)) return 4;
            if (far < SPIT_FAR && back - far > SPIT_TURN) return an.gentleSandy(k, n, SPIT_MAX_BEHIND, SPIT_MAX_DEPTH) ? 0 : 5;
        }
        return 4;
    }

    /**
     * A spit from shore pixel k (a {@link #spitSite}): it carries the updrift coast's line on downdrift over the shallow
     * water, bending gently landward, then hooks landward at its tip.
     */
    private static Bar spit(Analysis an, int k, long h) {
        float[] n = an.normalFar(k);
        float[] t = downdrift(an, n);
        float tx = t[0], tz = t[1];
        // Which way is landward from the spit's heading: turning by +angle points along (-tz, tx).
        float side = Math.signum(-(n[0] * -tz + n[1] * tx));
        if (side == 0) side = 1;
        float x = an.j0 + k % an.W + 0.5f - n[0] * 2, z = an.i0 + k / an.W + 0.5f - n[1] * 2;
        float len = 40 + 100 * (float) unit(mix(h + 3));
        float halfW = 2f + 2.5f * (float) unit(mix(h + 4)), crest = 1.5f + 1.5f * (float) unit(mix(h + 5));
        float bend = 0.15f + 0.3f * (float) unit(mix(h + 6)), hook = 0.9f + 0.6f * (float) unit(mix(h + 7));
        double heading = Math.atan2(tz, tx);
        List<float[]> pts = new ArrayList<>();
        pts.add(new float[]{x, z, halfW, crest});
        int built = 0;
        for (int st = 0; st < (int) len; st++) {
            float f = st / len;
            heading += f < 0.85f ? side * bend / (0.85f * len) : side * hook / (0.15f * len);
            heading += 0.05 * (unit(mix(h + 100 + st)) - 0.5);
            x += (float) Math.cos(heading);
            z += (float) Math.sin(heading);
            float e = an.elevAt(x, z);
            if (Float.isNaN(e) || e < -SPIT_MAX_DEPTH) break;              // only over shallow water
            float taper = 1 - 0.4f * f;
            pts.add(new float[]{x, z, halfW * taper, crest * (1 - 0.35f * f)});
            built++;
            if (e > 1.5f && built > 10) break;                             // reached land again: a bay bar
        }
        return built >= 20 ? new Bar(SPIT, pts) : null;
    }

    /** A cuspate foreland built out from straight shore pixel k. */
    private static Foreland foreland(Analysis an, int k, long h) {
        float[] n = an.normalFar(k);
        if (n == null || !an.gentleSandy(k, n)) return null;
        // The coast must be straight for a good way either side.
        float tx = -n[1], tz = n[0];
        float x = an.j0 + k % an.W + 0.5f, z = an.i0 + k / an.W + 0.5f;
        for (int s = -30; s <= 30; s += 15) {
            int q = an.index((int) Math.floor(z + tz * s), (int) Math.floor(x + tx * s));
            if (q < 0) return null;
            if (Math.abs(an.landFar[q] - 0.5f) > 0.08f) return null;
        }
        float apexLen = 15 + 25 * (float) unit(mix(h + 1));
        float baseLen = apexLen * (1.2f + 0.8f * (float) unit(mix(h + 2)));
        float ax = x + n[0] * apexLen, az = z + n[1] * apexLen;
        float apexE = an.elevAt(ax, az);
        if (Float.isNaN(apexE) || apexE < -12f) return null;
        float off = 1000f * (float) unit(mix(h + 4));
        return new Foreland(x - n[0] * 2, z - n[1] * 2, n[0], n[1], apexLen + 2, baseLen,
                1.5f + 1.5f * (float) unit(mix(h + 3)), off, -off);
    }

    /** Tombolos for the small islands (20-3000 pixels) centred in the region's own square. */
    private static void tombolos(long seed, Analysis an, int own0i, int own0j,
                                 java.util.function.BiFunction<Double, Double, Float> archipelago, List<Bar> out) {
        int nl = an.area.length;
        double[] si = new double[nl], sj = new double[nl];
        for (int k = 0; k < an.label.length; k++) {
            int l = an.label[k];
            if (l == 0) continue;
            si[l] += k / an.W; sj[l] += k % an.W;
        }
        for (int l = 1; l < nl; l++) {
            int a = an.area[l];
            if (a < 20 || a > 3000) continue;
            double ci = an.i0 + si[l] / a, cj = an.j0 + sj[l] / a;
            if (ci < own0i || ci >= own0i + SIZE || cj < own0j || cj >= own0j + SIZE) continue;
            if (archipelago.apply(ci, cj) > 0.3f) continue;
            long h = mix(seed ^ ((long) Math.floor(ci) * 0x9E3779B97F4A7C15L) ^ ((long) Math.floor(cj) * 0x165667B19E3779F9L) ^ 0x70B0L);
            if (unit(h) > TOMBOLO_CHANCE) continue;
            // Nearest mainland (a land mass of over 20000 pixels) within 66 pixels of the island.
            int r0 = Math.max(0, (int) (ci - an.i0) - 110), r1 = Math.min(an.H - 1, (int) (ci - an.i0) + 110);
            int c0 = Math.max(0, (int) (cj - an.j0) - 110), c1 = Math.min(an.W - 1, (int) (cj - an.j0) + 110);
            int bestIsl = -1, bestMain = -1;
            double best = 66 * 66;
            List<Integer> islandEdge = new ArrayList<>(), mainEdge = new ArrayList<>();
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    int k = r * an.W + c;
                    if (!an.isShore(k)) continue;
                    int lk = an.label[k];
                    if (lk == l) islandEdge.add(k);
                    else if (lk > 0 && an.area[lk] > 20000) mainEdge.add(k);
                }
            }
            for (int ki : islandEdge) {
                int ri = ki / an.W, rci = ki % an.W;
                for (int km : mainEdge) {
                    double d2 = (double) (km / an.W - ri) * (km / an.W - ri) + (double) (km % an.W - rci) * (km % an.W - rci);
                    if (d2 < best) { best = d2; bestIsl = ki; bestMain = km; }
                }
            }
            if (bestIsl < 0) continue;
            float ax = an.j0 + bestIsl % an.W + 0.5f, az = an.i0 + bestIsl / an.W + 0.5f;
            float bx = an.j0 + bestMain % an.W + 0.5f, bz = an.i0 + bestMain / an.W + 0.5f;
            float len = (float) Math.hypot(bx - ax, bz - az);
            if (len < 3) continue;
            // Shallow all the way across.
            boolean shallow = true;
            for (int s = 1; s < 10 && shallow; s++) {
                float e = an.elevAt(ax + (bx - ax) * s / 10f, az + (bz - az) * s / 10f);
                if (Float.isNaN(e) || e < -MAX_BAR_DEPTH) shallow = false;
            }
            if (!shallow) continue;
            // Bowed a little downdrift, and carried 2 pixels onto each shore.
            float ux = (bx - ax) / len, uz = (bz - az) / len, px = -uz, pz = ux;
            float bow = 0.15f * len * Math.signum(px * an.driftX + pz * an.driftZ);
            float halfW = 2f + 2f * (float) unit(mix(h + 1)), crest = 1.2f + 1f * (float) unit(mix(h + 2));
            List<float[]> pts = new ArrayList<>();
            int steps = Math.max(4, (int) len);
            for (int s = -2; s <= steps + 2; s++) {
                float t = s / (float) steps;
                float mid = 4 * Math.max(0f, t) * Math.max(0f, 1 - t);
                float b = bow * mid;
                // Widening where it meets each shore (the salients a tombolo grows from).
                pts.add(new float[]{ax + (bx - ax) * t + px * b, az + (bz - az) * t + pz * b, halfW * (1.9f - 0.9f * mid), crest});
            }
            out.add(new Bar(TOMBOLO, pts));
        }
    }

    // =========================================================================
    // Math
    // =========================================================================

    private static float[] boxBlur(float[] a, int H, int W, int R) {
        float[] tmp = new float[H * W], out = new float[H * W];
        for (int r = 0; r < H; r++) {
            float s = 0; int n = 0;
            for (int c = 0; c <= Math.min(W - 1, R); c++) { s += a[r * W + c]; n++; }
            for (int c = 0; c < W; c++) {
                tmp[r * W + c] = s / n;
                int add = c + R + 1, rem = c - R;
                if (add < W) { s += a[r * W + add]; n++; }
                if (rem >= 0) { s -= a[r * W + rem]; n--; }
            }
        }
        for (int c = 0; c < W; c++) {
            float s = 0; int n = 0;
            for (int r = 0; r <= Math.min(H - 1, R); r++) { s += tmp[r * W + c]; n++; }
            for (int r = 0; r < H; r++) {
                out[r * W + c] = s / n;
                int add = r + R + 1, rem = r - R;
                if (add < H) { s += tmp[add * W + c]; n++; }
                if (rem >= 0) { s -= tmp[rem * W + c]; n--; }
            }
        }
        return out;
    }

    private static float smooth(float e0, float e1, float v) {
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
