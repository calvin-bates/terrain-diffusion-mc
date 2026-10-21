package com.github.xandergos.terraindiffusionmc.hydrology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A ria: a river valley drowned by the sea (Galicia's rías, the Breton abers, Chesapeake Bay). Where a river reaches
 * the sea on a warm, hilly coast, the lower few kilometres of its valley are sunk below sea level, so the sea runs up
 * it as a long, winding inlet that narrows inland and branches into its side valleys.
 *
 * <p>The plan follows the river's main stem upstream from its mouth for 3-5.5 km (from the hydrology region that owns the
 * mouth, so every tile gets the same plan). Along it, the valley floor is lowered by up to 10-40 m at the mouth,
 * tapering to nothing upstream, and so is the ground beside it up to a height above the river that also tapers; the
 * inlet's head is where the lowered floor comes back above sea level, and the river carries on from there (the river
 * carver never carves below sea level, and takes its water level from the ground). Ground more than ~0.9 km from the
 * stem, or behind a ridge from it, is left alone. A ria and a delta never share a mouth: deltas form on flat coastal
 * plains, rias on hilly coasts.
 *
 * <p>Coordinates are native pixels (x east, z south), pixel centres at {@code +0.5}.
 */
public final class RiaPlan {

    /** Share of qualifying river mouths that are rias. */
    public static final float RIA_CHANCE = 0.35f;
    /** Mouths colder than this (deg C) are left for fjords. */
    private static final float MIN_TEMP = 8f;
    /**
     * Average height (m) of the valley sides over the river (the highest ground within ~540 m) along the lower 3 km of
     * the valley that makes a coast "hilly".
     */
    private static final float MIN_RELIEF = 50f;
    /**
     * Shortest inlet (pixels, 600 m): the valley floor this far up from the mouth must lie no higher than the drowning
     * depth there, so the sea really runs up it. A steep valley would only get a notch at its mouth.
     */
    private static final float MIN_INLET_PX = 20f;
    /**
     * Over how many metres of valley floor above the drowning depth the lowering fades out. Above that the floor is not
     * drowned at all: lowering it there only dug a dry gorge up the valley (the floor of a steep valley climbs far
     * above the depth within a pixel or two).
     */
    private static final float DRY_FADE_M = 10f;
    /** How far (pixels) the drowning reaches sideways from the stem. */
    private static final float REACH_PX = 30f;
    /** Longest ria (pixels). */
    private static final int MAX_LENGTH_PX = 183;
    /**
     * Farthest a ria reaches from its mouth (pixels): its length plus its sideways reach. Every tile near a coast looks
     * for rias in all hydrology regions within this distance, like deltas.
     */
    public static final int MAX_REACH_PX = MAX_LENGTH_PX + (int) REACH_PX + 10;

    /** How far (pixels) the edges of the drowned area wander in and out. */
    private static final float EDGE_WOBBLE_PX = 5f;
    /** Low banks and hollows on a drowned valley floor (native pixels; ~300 m features). */
    private static final com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite FLOOR_NOISE = floorNoise();

    private static com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite floorNoise() {
        var n = new com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite(6211);
        n.SetNoiseType(com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(1f / 10f);
        n.SetFractalType(com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(3);
        return n;
    }

    /** The mouth (last land pixel centre) and the stem upstream from it. */
    public final float mouthX, mouthZ;
    /** Where the stem traced upstream ends (native pixel centre), past the inlet's head. */
    public float headX() { return px[px.length - 1]; }
    public float headZ() { return pz[pz.length - 1]; }
    private final float[] px, pz, cum, surf;
    private final float length, depth0, height0;
    /** Bounding box of everything the ria can touch (pixels). */
    private final float bx0, bz0, bx1, bz1;

    // Lazily built grid (pixel resolution over the bounding box): distance to the stem, arc length of the nearest
    // point, and how far past the mouth (out to sea) the pixel lies.
    private volatile float[] gridD;
    private float[] gridS, gridBeyond;
    private int gx0, gz0, gw, gh;
    /** The owning region's ground, for the ridge test (not the region itself: the cache holds regions weakly). */
    private final float[] regElev;
    private final int regI0, regJ0, regW, regH;

    private RiaPlan(RiverService.Region reg, List<int[]> stem, long h) {
        regElev = reg.elev;
        regI0 = reg.i0; regJ0 = reg.j0; regW = reg.width; regH = reg.height;
        int n = stem.size();
        px = new float[n]; pz = new float[n]; cum = new float[n]; surf = new float[n];
        float run = 0;
        for (int i = 0; i < n; i++) {
            int k = stem.get(i)[0];
            px[i] = reg.j0 + k % reg.width + 0.5f;
            pz[i] = reg.i0 + k / reg.width + 0.5f;
            if (i > 0) cum[i] = cum[i - 1] + (float) Math.hypot(px[i] - px[i - 1], pz[i] - pz[i - 1]);
            // The valley floor, never falling upstream (the raw ground has pixel bumps).
            run = Math.max(run, Math.max(0f, reg.elev[k]));
            surf[i] = run;
        }
        mouthX = px[0];
        mouthZ = pz[0];
        length = cum[n - 1];
        depth0 = 10f + 30f * u(h, 1);
        height0 = depth0 + 10f + 15f * u(h, 2);
        float x0 = Float.MAX_VALUE, z0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, z1 = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            x0 = Math.min(x0, px[i]); x1 = Math.max(x1, px[i]);
            z0 = Math.min(z0, pz[i]); z1 = Math.max(z1, pz[i]);
        }
        float pad = REACH_PX + EDGE_WOBBLE_PX + 2;      // the wobbled edge reaches EDGE_WOBBLE_PX past REACH_PX
        bx0 = x0 - pad; bz0 = z0 - pad; bx1 = x1 + pad; bz1 = z1 + pad;
    }

    /** Whether (x, z) could be changed by this ria, with {@code margin} pixels to spare. */
    public boolean near(float x, float z, float margin) {
        return x >= bx0 - margin && x <= bx1 + margin && z >= bz0 - margin && z <= bz1 + margin;
    }

    /**
     * The ground (metres) at native point (x, z) after drowning, given the ground {@code e} there now. Only ever
     * lowers it.
     */
    public float drown(float x, float z, float e) {
        if (!near(x, z, 0)) return e;
        if (gridD == null) buildGrid();
        float fx = x - 0.5f - gx0, fz = z - 0.5f - gz0;
        int c0 = (int) Math.floor(fx), r0 = (int) Math.floor(fz);
        if (c0 < 0 || r0 < 0 || c0 + 1 >= gw || r0 + 1 >= gh) return e;
        float tx = fx - c0, tz = fz - r0;
        // Distances wobbled by noise, so the edges where the drowning fades out don't run in straight lines along a
        // straight stem or straight across the mouth.
        float d = bilerp(gridD, r0, c0, tx, tz) + EDGE_WOBBLE_PX * FLOOR_NOISE.GetNoise(x * 0.6f + 400, z * 0.6f - 170);
        if (d >= REACH_PX) return e;
        d = Math.max(0f, d);
        float s = bilerp(gridS, r0, c0, tx, tz);
        float beyond = Math.max(0f, bilerp(gridBeyond, r0, c0, tx, tz) + 3f * FLOOR_NOISE.GetNoise(x * 0.6f - 900, z * 0.6f + 35));
        float t = Math.max(0f, Math.min(1f, s / length));
        float floor = surfAt(s);
        // Deep most of the way up, shoaling toward the head.
        float D = depth0 * (1 - (float) Math.pow(t, 1.6));
        float hv = height0 * (1 - 0.7f * (float) Math.pow(t, 1.6));
        float rel = e - floor;
        if (rel >= hv) return e;
        float w = (1 - smooth(0.55f * hv, hv, rel)) * (1 - smooth(0.65f * REACH_PX, REACH_PX, d))
                * (1 - smooth(0f, 8f, beyond)) * (1 - smooth(D, D + DRY_FADE_M, floor));
        if (w <= 0f) return e;
        // Never below the drowned valley floor unless it is already lower (the sea floor off the mouth). That floor is
        // deepest along the old river and shoals toward the inlet's sides, with low mud banks and hollows: one level per
        // distance along the river made a flat, tilted plate whose block steps ran in straight lines across the inlet
        // (Calvin, Oct 21, X -3324 Z -6301).
        float across = Math.min(1f, d / REACH_PX);
        float bumps = FLOOR_NOISE.GetNoise(x, z);
        float bed = floor - D * (1 - 0.6f * across * across) - 0.25f * D * Math.max(0f, 1 - d / 2.5f)
                + (1.5f + 0.12f * D) * bumps;
        float lowered = Math.max(e - D * w + 0.6f * w * (1.5f + 0.12f * D) * bumps, Math.min(e, bed));
        return Math.min(e, lowered);
    }

    /** How far (pixels) up the stem the valley floor stays at or below the drowning depth: the inlet's length. */
    float inletLength() {
        for (int i = 0; i < cum.length; i++) {
            float t = Math.max(0f, Math.min(1f, cum[i] / length));
            if (surf[i] > depth0 * (1 - (float) Math.pow(t, 1.6))) return cum[i];
        }
        return length;
    }

    private float surfAt(float s) {
        if (s <= 0) return surf[0];
        for (int i = 1; i < cum.length; i++) {
            if (cum[i] >= s) {
                float f = (s - cum[i - 1]) / Math.max(1e-3f, cum[i] - cum[i - 1]);
                return surf[i - 1] + (surf[i] - surf[i - 1]) * f;
            }
        }
        return surf[surf.length - 1];
    }

    private synchronized void buildGrid() {
        if (gridD != null) return;
        gx0 = (int) Math.floor(bx0); gz0 = (int) Math.floor(bz0);
        gw = (int) Math.ceil(bx1) - gx0 + 2; gh = (int) Math.ceil(bz1) - gz0 + 2;
        float[] gd = new float[gw * gh], gs = new float[gw * gh], gb = new float[gw * gh];
        float dirX = px.length > 1 ? px[0] - px[1] : 1, dirZ = pz.length > 1 ? pz[0] - pz[1] : 0;
        float dl = (float) Math.hypot(dirX, dirZ);
        dirX /= Math.max(1e-3f, dl); dirZ /= Math.max(1e-3f, dl);   // seaward, at the mouth
        for (int r = 0; r < gh; r++) {
            for (int c = 0; c < gw; c++) {
                float x = gx0 + c + 0.5f, z = gz0 + r + 0.5f;
                float best = Float.MAX_VALUE, bs = 0, bxp = 0, bzp = 0;
                for (int i = 0; i + 1 < px.length; i++) {
                    float ax = px[i], az = pz[i], ex = px[i + 1] - ax, ez = pz[i + 1] - az;
                    float l2 = ex * ex + ez * ez;
                    float tt = l2 > 0 ? Math.max(0f, Math.min(1f, ((x - ax) * ex + (z - az) * ez) / l2)) : 0f;
                    float qx = ax + tt * ex, qz = az + tt * ez;
                    float dd = (float) Math.hypot(x - qx, z - qz);
                    if (dd < best) { best = dd; bs = cum[i] + tt * (cum[i + 1] - cum[i]); bxp = qx; bzp = qz; }
                }
                // Past the mouth, out to sea: how far along the river's seaward direction.
                float beyond = Math.max(0f, (x - px[0]) * dirX + (z - pz[0]) * dirZ);
                if (bs > 0.5f) beyond = 0f;
                // Ground behind a ridge from the stem is a different valley: leave it alone.
                if (best < REACH_PX && blocked(x, z, bxp, bzp, bs)) best = Float.MAX_VALUE;
                gd[r * gw + c] = Math.min(best, 2 * REACH_PX);
                gs[r * gw + c] = bs;
                gb[r * gw + c] = beyond;
            }
        }
        gridS = gs;
        gridBeyond = gb;
        gridD = gd;
    }

    /** Whether higher ground than the drowning reaches stands between (x, z) and the stem point (qx, qz). */
    private boolean blocked(float x, float z, float qx, float qz, float s) {
        float t = Math.max(0f, Math.min(1f, s / length));
        float limit = surfAt(s) + 1.2f * height0 * (1 - 0.7f * (float) Math.pow(t, 1.6));
        for (int k = 1; k <= 5; k++) {
            float f = k / 6f;
            int rr = (int) Math.floor(qz + (z - qz) * f) - regI0, cc = (int) Math.floor(qx + (x - qx) * f) - regJ0;
            if (rr >= 0 && cc >= 0 && rr < regH && cc < regW && regElev[rr * regW + cc] > limit) return true;
        }
        return false;
    }

    private float bilerp(float[] g, int r0, int c0, float tx, float tz) {
        float a = g[r0 * gw + c0], b = g[r0 * gw + c0 + 1], c = g[(r0 + 1) * gw + c0], d = g[(r0 + 1) * gw + c0 + 1];
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz;
    }

    // ---- Finding rias ----

    private static final Map<RiverService.Region, List<RiaPlan>> CACHE = new WeakHashMap<>();

    /** The rias whose mouths lie in region (ri, rj)'s own square, from that region. Cached per region object. */
    public static List<RiaPlan> of(RiverService.Region reg, int ri, int rj, int regionSize) {
        synchronized (CACHE) {
            List<RiaPlan> c = CACHE.get(reg);
            if (c != null) return c;
        }
        List<RiaPlan> list = new ArrayList<>();
        if (reg.elev != null && reg.temp != null) {
            for (int i = ri * regionSize; i < (ri + 1) * regionSize; i++) {
                for (int j = rj * regionSize; j < (rj + 1) * regionSize; j++) {
                    int k = reg.index(i, j);
                    if (k < 0) continue;
                    RiaPlan p = tryMouth(reg, k, i, j);
                    if (p != null) list.add(p);
                }
            }
        }
        List<RiaPlan> result = Collections.unmodifiableList(list);
        synchronized (CACHE) {
            CACHE.put(reg, result);
        }
        return result;
    }

    /** Names of the {@link #stats} entries: why river mouths did not become rias. */
    public static final String[] REASONS = {"river mouths", "too cold (fjord country)", "skipped by chance",
            "river too short", "coast not hilly", "valley too steep for an inlet", "rias"};

    /**
     * Counts, for the river mouths in region (ri, rj)'s own square, why each did or did not become a ria (indices into
     * {@link #REASONS}), for the finder's report.
     */
    public static int[] stats(RiverService.Region reg, int ri, int rj, int regionSize) {
        int[] out = new int[REASONS.length];
        if (reg.elev == null || reg.temp == null) return out;
        for (int i = ri * regionSize; i < (ri + 1) * regionSize; i++) {
            for (int j = rj * regionSize; j < (rj + 1) * regionSize; j++) {
                int k = reg.index(i, j);
                if (k < 0) continue;
                int[] why = new int[1];
                tryMouth(reg, k, i, j, why);
                if (why[0] < 0) continue;
                out[0]++;
                if (why[0] > 0) out[why[0]]++;
                else out[REASONS.length - 1]++;
            }
        }
        return out;
    }

    private static RiaPlan tryMouth(RiverService.Region reg, int k, int i, int j) {
        return tryMouth(reg, k, i, j, new int[1]);
    }

    /** As above; {@code why[0]} gets -1 if k is not a river mouth, 0 for a ria, else the reason (see {@link #REASONS}). */
    private static RiaPlan tryMouth(RiverService.Region reg, int k, int i, int j, int[] why) {
        why[0] = -1;
        byte c = reg.cls[k];
        if (c != RiverMap.RIVER && c != RiverMap.MAIN) return null;
        if (reg.elev[k] <= 0f) return null;
        int d = reg.downstream[k];
        if (d < 0 || reg.elev[d] > 0f) return null;                          // not the last land pixel
        long h = (long) i * 73856093L ^ (long) j * 19349663L ^ 0x41A5L;
        if (reg.temp[k] < MIN_TEMP) { why[0] = 1; return null; }
        if (u(h, 0) > RIA_CHANCE) { why[0] = 2; return null; }
        int target = 100 + (int) (83 * u(h, 3));
        List<int[]> stem = new ArrayList<>();
        stem.add(new int[]{k});
        int cur = k;
        float len = 0;
        while (len < target) {
            int up = upstream(reg, cur);
            if (up < 0) break;
            len += (float) Math.hypot(up % reg.width - cur % reg.width, up / reg.width - cur / reg.width);
            stem.add(new int[]{up});
            cur = up;
        }
        if (len < 40) { why[0] = 3; return null; }
        // Hilly: the valley sides stand well above the river along the lower valley.
        float relief = 0;
        int samples = 0;
        for (int s = 0; s < Math.min(stem.size(), 100); s += 5) {
            int q = stem.get(s)[0];
            int qr = q / reg.width, qc = q % reg.width;
            float hi = reg.elev[q];
            for (int dr = -18; dr <= 18; dr += 3) {
                for (int dc = -18; dc <= 18; dc += 3) {
                    int rr = qr + dr, cc = qc + dc;
                    if (rr < 0 || cc < 0 || rr >= reg.height || cc >= reg.width) continue;
                    hi = Math.max(hi, reg.elev[rr * reg.width + cc]);
                }
            }
            relief += hi - reg.elev[q];
            samples++;
        }
        if (samples == 0 || relief / samples < MIN_RELIEF) { why[0] = 4; return null; }
        RiaPlan plan = new RiaPlan(reg, stem, h);
        if (plan.inletLength() < MIN_INLET_PX) { why[0] = 5; return null; }
        why[0] = 0;
        return plan;
    }

    /**
     * Whether region pixel {@code m} (where a river reaches the sea: its last land pixel) is a ria's mouth. A delta whose
     * main channel ends there gives way to the ria, so the two never share a mouth.
     */
    public static boolean claims(RiverService.Region reg, int m) {
        if (reg.elev == null || reg.temp == null || m < 0) return false;
        return tryMouth(reg, m, reg.i0 + m / reg.width, reg.j0 + m % reg.width) != null;
    }

    /** The upstream neighbour of k on the main stem (largest class, then lowest ground), or -1. */
    private static int upstream(RiverService.Region reg, int k) {
        int kr = k / reg.width, kc = k % reg.width;
        int best = -1;
        for (int dr = -1; dr <= 1; dr++) {
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                int rr = kr + dr, cc = kc + dc;
                if (rr < 0 || cc < 0 || rr >= reg.height || cc >= reg.width) continue;
                int q = rr * reg.width + cc;
                if (reg.downstream[q] != k || reg.cls[q] < RiverMap.STREAM || reg.cls[q] > RiverMap.MAIN) continue;
                if (best < 0 || reg.cls[q] > reg.cls[best] || (reg.cls[q] == reg.cls[best] && reg.elev[q] < reg.elev[best])) best = q;
            }
        }
        return best;
    }

    // ---- Math ----

    private static float smooth(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    private static float u(long h, long salt) {
        long z = h + salt * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 40) / (float) (1L << 24);
    }
}
