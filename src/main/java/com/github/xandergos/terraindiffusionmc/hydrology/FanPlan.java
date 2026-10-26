package com.github.xandergos.terraindiffusionmc.hydrology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * An alluvial fan (biomes plan v1.4 round C): where a desert stream or river leaves steep mountains for a gentle
 * basin, it drops its gravel in a fan-shaped apron spreading from the valley's mouth (Death Valley, the Basin and
 * Range). Found in the hydrology: a channel pixel in arid land whose channel falls steeply just upstream (over 4% over
 * 120-180 m) and gently just downstream (under 3% over 240 m), at the foot of high ground. The fan is a cone sector
 * around the downstream direction, its surface falling from up to 20 m over the apex, a little concave (steeper near
 * the apex, gentler at the toe), to meet the basin floor at its radius; ground under it is raised to it (up to {@link #MAX_RAISE_M}), never
 * lowered. From the region owning the apex, so every tile gets the same fans.
 *
 * <p>Coordinates are native pixels (x east, z south), pixel centres at {@code +0.5}.
 */
public final class FanPlan {

    /** Share of qualifying mountain fronts with a fan. */
    static final float CHANCE = 0.75f;
    /** Fans closer than this (pixels) to a bigger one in the same region are dropped. */
    static final float SPACING_PX = 30f;
    /** Most a fan raises the ground (m). */
    static final float MAX_RAISE_M = 35f;
    /** Largest fan radius (pixels); tiles look for fans this far around them. */
    public static final int MAX_RADIUS_PX = 48;

    public final float apexX, apexZ;
    /** Seaward-pointing axis (unit), half-angle (radians), radius (pixels), apex height and total fall (m). */
    final float axisX, axisZ, halfAngle, radius, apexE, drop;

    private FanPlan(float apexX, float apexZ, float axisX, float axisZ, float halfAngle, float radius, float apexE, float drop) {
        this.apexX = apexX; this.apexZ = apexZ; this.axisX = axisX; this.axisZ = axisZ;
        this.halfAngle = halfAngle; this.radius = radius; this.apexE = apexE; this.drop = drop;
    }

    /** Whether native point (x, z) is within reach of this fan, with {@code margin} pixels to spare. */
    public boolean near(float x, float z, float margin) {
        return Math.abs(x - apexX) <= radius + margin && Math.abs(z - apexZ) <= radius + margin;
    }

    /** How much (m, ≥ 0) the fan raises ground {@code e} at native point (x, z). */
    public float raise(float x, float z, float e) {
        float dx = x - apexX, dz = z - apexZ;
        float r = (float) Math.sqrt(dx * dx + dz * dz);
        if (r >= radius) return 0f;
        float wr = 1f - smooth(0.75f * radius, radius, r);
        float wa;
        if (r < 1e-3f) wa = 1f;
        else {
            float cos = (dx * axisX + dz * axisZ) / r;
            float a = (float) Math.acos(Math.max(-1f, Math.min(1f, cos)));
            // Narrow near the apex (still in the valley's mouth), opening out to the full sector.
            float half = halfAngle * (0.35f + 0.65f * smooth(0f, 0.3f * radius, r));
            wa = 1f - smooth(0.7f * half, half, a);
        }
        if (wa <= 0f) return 0f;
        float t = r / radius;
        float s = apexE - drop * (1.4f * t - 0.4f * t * t);
        float up = Math.max(0f, s - e);
        return Math.min(MAX_RAISE_M, up) * wa * wr;
    }

    // ---- Finding fans ----

    private static final Map<RiverService.Region, List<FanPlan>> CACHE = new WeakHashMap<>();

    /** The fans whose apexes lie in region (ri, rj)'s own square, from that region. Cached per region object. */
    public static List<FanPlan> of(RiverService.Region reg, int ri, int rj, int regionSize) {
        synchronized (CACHE) {
            List<FanPlan> c = CACHE.get(reg);
            if (c != null) return c;
        }
        List<FanPlan> result = Collections.unmodifiableList(find(reg, ri, rj, regionSize));
        synchronized (CACHE) {
            CACHE.put(reg, result);
        }
        return result;
    }

    private static List<FanPlan> find(RiverService.Region reg, int ri, int rj, int size) {
        List<float[]> cand = new ArrayList<>();      // {score, k, i, j}
        if (reg.elev == null || reg.temp == null || reg.precip == null) return List.of();
        for (int i = ri * size; i < (ri + 1) * size; i++) {
            for (int j = rj * size; j < (rj + 1) * size; j++) {
                int k = reg.index(i, j);
                if (k < 0) continue;
                byte c = reg.cls[k];
                if (c < RiverMap.STREAM || c > RiverMap.MAIN) continue;
                if (reg.precip[k] > 450f || reg.temp[k] < 0f || reg.elev[k] < 80f) continue;
                int up = k, nUp = 0;
                for (; nUp < 6; nUp++) {
                    int u = upstream(reg, up);
                    if (u < 0) break;
                    up = u;
                }
                int dn = k, nDn = 0;
                for (; nDn < 8; nDn++) {
                    int d = reg.downstream[dn];
                    if (d < 0) break;
                    dn = d;
                }
                if (nUp < 4 || nDn < 8) continue;
                float slopeUp = (reg.elev[up] - reg.elev[k]) / (nUp * 30f);
                float slopeDn = (reg.elev[k] - reg.elev[dn]) / (nDn * 30f);
                if (slopeUp < 0.04f || slopeDn > 0.03f || slopeDn < -0.005f) continue;
                // At the foot of high ground: something 150 m above the apex within 10 pixels.
                float hi = reg.elev[k];
                int kr = k / reg.width, kc = k % reg.width;
                for (int dr = -10; dr <= 10; dr += 2)
                    for (int dc = -10; dc <= 10; dc += 2) {
                        int rr = kr + dr, cc = kc + dc;
                        if (rr >= 0 && cc >= 0 && rr < reg.height && cc < reg.width) hi = Math.max(hi, reg.elev[rr * reg.width + cc]);
                    }
                if (hi < reg.elev[k] + 150f) continue;
                float sz = reg.size != null ? reg.size[k] : c;
                cand.add(new float[]{sz + slopeUp, k, i, j, dn});
            }
        }
        cand.sort((a, b) -> a[0] != b[0] ? Float.compare(b[0], a[0]) : Float.compare(a[1], b[1]));
        List<FanPlan> out = new ArrayList<>();
        List<float[]> kept = new ArrayList<>();
        for (float[] cd : cand) {
            boolean close = false;
            for (float[] q : kept) if (Math.hypot(q[2] - cd[2], q[3] - cd[3]) < SPACING_PX) { close = true; break; }
            if (close) continue;
            kept.add(cd);
            int i = (int) cd[2], j = (int) cd[3], k = (int) cd[1], dn = (int) cd[4];
            long h = (long) i * 73856093L ^ (long) j * 19349663L ^ 0xFA4L;
            if (u(h, 0) > CHANCE) continue;
            float ax = (dn % reg.width) - (k % reg.width), az = (dn / reg.width) - (k / reg.width);
            float al = (float) Math.sqrt(ax * ax + az * az);
            if (al < 1e-3f) continue;
            ax /= al; az /= al;
            float sz = reg.size != null ? reg.size[k] : reg.cls[k];
            float radius = Math.min(MAX_RADIUS_PX, 16f + 8f * Math.max(1f, Math.min(3f, sz)) + 10f * u(h, 1));
            // The basin floor at the fan's toe, along the axis.
            int er = Math.round(k / reg.width + az * radius), ec = Math.round(k % reg.width + ax * radius);
            float eEnd = er >= 0 && ec >= 0 && er < reg.height && ec < reg.width ? reg.elev[er * reg.width + ec] : reg.elev[dn];
            // The fan starts a little over the channel at its apex (a mound there dammed the canyon's mouth) and stands
            // above the basin where the basin floor falls away faster than the fan's surface.
            float apexE = reg.elev[k] + Math.min(20f, 0.02f * radius * 30f);
            float drop = Math.max(10f, apexE - eEnd);
            float half = (float) Math.toRadians(50f + 25f * u(h, 2));
            out.add(new FanPlan(j + 0.5f, i + 0.5f, ax, az, half, radius, apexE, drop));
        }
        return out;
    }

    /** The upstream neighbour of k on the main stem (largest size, then lowest ground), or -1. */
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
