package com.github.xandergos.terraindiffusionmc.hydrology;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The layout of one river delta: the fan of new land the river has built out into the sea, and the distributary
 * channels that cross it.
 *
 * <p>A delta forms where a river or main river crosses a low, flat coastal plain to the sea (see
 * {@link RiverCarver#isDeltaHead}). From the head, where the river first splits, the plan lays out:
 * <ul>
 *   <li><b>The lobe:</b> a fan of land at sea level spreading from the head out past the old coastline, wider and
 *       longer for bigger rivers, with a wobbly front. The terrain only builds it over shallow water.</li>
 *   <li><b>Distributaries:</b> the main channel runs on from the old mouth to the lobe's front; two side branches
 *       leave at the head and usually fork again partway, so 3 to 6 channels reach the sea.</li>
 *   <li><b>Mouth bars:</b> a small sand bar just off the end of each channel.</li>
 * </ul>
 *
 * <p>Everything comes from one hydrology region (the one that owns the head pixel) and a hash of the head's position,
 * so every tile that asks gets the same plan. Coordinates are native pixels (x east, z south), pixel centres at
 * {@code +0.5}. Plans are cached per region.
 */
public final class DeltaPlan {

    /** One channel: a polyline of native-pixel points with the water surface (metres) at each, and its size class. */
    public static final class Channel {
        public final float[] x, z, surface;
        public final byte cls;

        Channel(List<float[]> pts, byte cls) {
            int n = pts.size();
            x = new float[n]; z = new float[n]; surface = new float[n];
            for (int i = 0; i < n; i++) { x[i] = pts.get(i)[0]; z[i] = pts.get(i)[1]; surface[i] = pts.get(i)[2]; }
            this.cls = cls;
        }
    }

    /** Head pixel centre (the apex of the fan), and the fan's axis (unit vector toward the sea). */
    public final float apexX, apexZ, dirX, dirZ;
    /** Distance (pixels) from the apex to the lobe front along the axis. */
    public final float reach;
    /** Half the fan's opening angle (radians). */
    public final float halfAngle;
    /** Where the river reached the old coastline (native pixel centre). */
    public final float mouthX, mouthZ;
    public final byte riverClass;
    public final List<Channel> channels;
    /** Mouth bars: centre, unit direction (along the channel) for each. */
    public final float[] barX, barZ, barDX, barDZ;

    private final double[] frontPhase = new double[3];
    private final double[] frontAmp = new double[3];

    // Distance-to-channel grid (pixels), filled lazily; covers the plan's bounding box.
    private volatile float[] chanDist;
    private int gx0, gz0, gw, gh;

    /** Pixels the river walks from the head to the old coast (see {@link RiverCarver#DELTA_START}). */
    private static final int MAX_WALK = 220;

    private DeltaPlan(RiverService.Region reg, int k, int m) {
        int kr = k / reg.width, kc = k % reg.width;
        apexX = reg.j0 + kc + 0.5f;
        apexZ = reg.i0 + kr + 0.5f;
        mouthX = reg.j0 + m % reg.width + 0.5f;
        mouthZ = reg.i0 + m / reg.width + 0.5f;
        float fx = mouthX - apexX, fz = mouthZ - apexZ, fl = (float) Math.hypot(fx, fz);
        dirX = fl > 0 ? fx / fl : 1f;
        dirZ = fl > 0 ? fz / fl : 0f;
        riverClass = reg.cls[k];
        long h = (long) (reg.i0 + kr) * 73856093L ^ (long) (reg.j0 + kc) * 19349663L ^ 0xDE17AL;
        boolean main = riverClass == RiverMap.MAIN;
        float beyond = main ? 45f + 30f * u(h, 1) : 25f + 15f * u(h, 2);
        reach = fl + beyond;
        halfAngle = 0.75f + 0.3f * u(h, 3);
        for (int i = 0; i < 3; i++) {
            frontPhase[i] = u(h, 10 + i) * 2 * Math.PI;
            frontAmp[i] = 0.09 / (i + 1);
        }

        List<Channel> chans = new ArrayList<>();
        List<float[]> bars = new ArrayList<>();
        float surf0 = Math.max(0f, reg.surface[k]);
        byte side = main ? RiverMap.RIVER : RiverMap.STREAM;
        // Main channel: carried on from the old mouth to the lobe front, forking near the end now and then.
        double mainHeading = Math.atan2(dirZ, dirX);
        walk(reg, mouthX, mouthZ, mainHeading, riverClass, 0f, h ^ 0x11, 0, u(h, 4) < 0.5f ? 0.7f : 2f, chans, bars);
        // Side branches from the head, each usually forking once.
        for (int s = -1; s <= 1; s += 2) {
            double ang = s * (0.45 + 0.3 * u(h, 5 + s));
            walk(reg, apexX, apexZ, mainHeading + ang, side, surf0, h ^ (s * 0x5BD1E995L), 0,
                    u(h, 7 + s) < 0.7f ? 0.45f + 0.2f * u(h, 9 + s) : 2f, chans, bars);
        }
        channels = Collections.unmodifiableList(chans);
        int nb = bars.size();
        barX = new float[nb]; barZ = new float[nb]; barDX = new float[nb]; barDZ = new float[nb];
        for (int i = 0; i < nb; i++) {
            float[] b = bars.get(i);
            barX[i] = b[0]; barZ[i] = b[1]; barDX[i] = b[2]; barDZ[i] = b[3];
        }
    }

    /**
     * Walks one channel from (x, z) on the given heading until it reaches the lobe front (or the sea beyond the old
     * coast stops being shallow, or higher ground blocks it). It forks into two smaller channels once it has
     * covered {@code forkAt} of the distance to the front (no fork when that is above 1).
     */
    private void walk(RiverService.Region reg, float x, float z, double heading, byte cls, float surf, long h,
                      int depth, float forkAt, List<Channel> out, List<float[]> bars) {
        List<float[]> pts = new ArrayList<>();
        pts.add(new float[]{x, z, surf});
        float startU = lobeU(x, z);
        double axis = Math.atan2(dirZ, dirX);
        for (int st = 0; st < MAX_WALK; st++) {
            // Wander gently, and drift back toward the fan's axis so channels spread but stay inside it.
            double rel = wrap(heading - axis);
            heading += 0.22 * (u(h, 100 + st) - 0.5) - 0.004 * rel;
            float nx = x + (float) Math.cos(heading), nz = z + (float) Math.sin(heading);
            int q = reg.index((int) Math.floor(nz), (int) Math.floor(nx));
            if (q < 0) break;
            float e = reg.elev[q];
            if (e > RiverCarver.DELTA_MAX_M) break;                       // blocked by higher ground
            if (e < -40f) break;                                          // the lobe never reaches deep water
            float sq = Math.max(0f, Math.min(surf, reg.surface[q]));
            x = nx; z = nz; surf = sq;
            pts.add(new float[]{x, z, surf});
            float uNow = lobeU(x, z);
            if (uNow >= 1f) {                                             // reached the front: a mouth bar off its end
                bars.add(new float[]{x + 3f * (float) Math.cos(heading), z + 3f * (float) Math.sin(heading),
                        (float) Math.cos(heading), (float) Math.sin(heading)});
                break;
            }
            if (depth == 0 && forkAt <= 1f && (uNow - startU) / Math.max(0.05f, 1f - startU) >= forkAt) {
                out.add(new Channel(pts, cls));
                byte sub = RiverMap.STREAM;
                for (int s = -1; s <= 1; s += 2) {
                    walk(reg, x, z, heading + s * (0.35 + 0.2 * u(h, 300 + s)), sub, surf, h ^ (0x9E37L * (s + 2)),
                            depth + 1, 2f, out, bars);
                }
                return;
            }
        }
        if (pts.size() >= 2) out.add(new Channel(pts, cls));
    }

    /**
     * Where (x, z) sits in the lobe: its distance from the apex over the lobe radius in its direction. Below 1 is on
     * the delta, above 1 past its front; {@link Float#POSITIVE_INFINITY} outside the fan's angle or behind the apex.
     */
    public float lobeU(float x, float z) {
        float dx = x - apexX, dz = z - apexZ;
        float r = (float) Math.hypot(dx, dz);
        if (r < 1e-3f) return 0f;
        double rel = wrap(Math.atan2(dz, dx) - Math.atan2(dirZ, dirX));
        float rad = radiusAt(rel);
        if (rad <= 0f) return Float.POSITIVE_INFINITY;
        return r / rad;
    }

    /** Lobe radius (pixels) at an angle from the axis: full along the axis, closing to 0 at the fan's edge. */
    public float radiusAt(double rel) {
        double t = Math.abs(rel) / halfAngle;
        if (t >= 1) return 0f;
        double wob = 0;
        for (int i = 0; i < 3; i++) wob += frontAmp[i] * Math.cos((i + 2) * rel * 3 + frontPhase[i]);
        return (float) (reach * Math.pow(1 - t * t, 0.6) * (1 + wob));
    }

    /** Distance (pixels, up to 6) from (x, z) to the nearest channel centre line. */
    public float channelDistance(float x, float z) {
        float[] g = chanDist;
        if (g == null) g = buildChanDist();
        int c = (int) Math.floor(x) - gx0, r = (int) Math.floor(z) - gz0;
        if (c < 0 || r < 0 || c >= gw || r >= gh) return 6f;
        return g[r * gw + c];
    }

    private synchronized float[] buildChanDist() {
        if (chanDist != null) return chanDist;
        int pad = 8;
        gx0 = (int) Math.floor(apexX - reach) - pad;
        gz0 = (int) Math.floor(apexZ - reach) - pad;
        gw = (int) Math.ceil(2 * reach) + 2 * pad;
        gh = gw;
        float[] g = new float[gw * gh];
        java.util.Arrays.fill(g, 6f);
        for (Channel ch : channels) {
            for (int i = 0; i + 1 < ch.x.length; i++) {
                float ax = ch.x[i], az = ch.z[i], bx = ch.x[i + 1], bz = ch.z[i + 1];
                int c0 = (int) Math.floor(Math.min(ax, bx) - 6) - gx0, c1 = (int) Math.ceil(Math.max(ax, bx) + 6) - gx0;
                int r0 = (int) Math.floor(Math.min(az, bz) - 6) - gz0, r1 = (int) Math.ceil(Math.max(az, bz) + 6) - gz0;
                float dx = bx - ax, dz = bz - az, len2 = dx * dx + dz * dz;
                for (int r = Math.max(0, r0); r <= Math.min(gh - 1, r1); r++) {
                    for (int c = Math.max(0, c0); c <= Math.min(gw - 1, c1); c++) {
                        float px = gx0 + c + 0.5f, pz = gz0 + r + 0.5f;
                        float t = len2 > 0 ? ((px - ax) * dx + (pz - az) * dz) / len2 : 0;
                        t = Math.max(0f, Math.min(1f, t));
                        float ex = ax + t * dx - px, ez = az + t * dz - pz;
                        float d = (float) Math.sqrt(ex * ex + ez * ez);
                        if (d < g[r * gw + c]) g[r * gw + c] = d;
                    }
                }
            }
        }
        chanDist = g;
        return g;
    }

    /** Bounding box test: could (x, z) be affected by this delta (lobe, front slope or bars)? */
    public boolean near(float x, float z, float margin) {
        return Math.abs(x - apexX) <= reach * 1.4f + margin && Math.abs(z - apexZ) <= reach * 1.4f + margin;
    }

    // ---- Cache and lookup ----

    private static final Map<RiverService.Region, List<DeltaPlan>> CACHE = new WeakHashMap<>();

    /**
     * The deltas whose heads lie in region (ri, rj)'s own square, computed from that region. Cached per region object,
     * so it is recomputed only when the region is.
     */
    public static List<DeltaPlan> of(RiverService.Region reg, int ri, int rj, int regionSize) {
        synchronized (CACHE) {
            List<DeltaPlan> c = CACHE.get(reg);
            if (c != null) return c;
        }
        List<DeltaPlan> list = new ArrayList<>();
        if (reg.elev != null) {
            for (int i = ri * regionSize; i < (ri + 1) * regionSize; i++) {
                for (int j = rj * regionSize; j < (rj + 1) * regionSize; j++) {
                    int k = reg.index(i, j);
                    if (k < 0 || reg.cls[k] < RiverMap.RIVER || reg.cls[k] > RiverMap.MAIN) continue;
                    if (!RiverCarver.isDeltaHead(reg, k)) continue;
                    int m = RiverCarver.deltaMouth(reg, k);
                    if (m < 0) continue;
                    list.add(new DeltaPlan(reg, k, m));
                }
            }
        }
        List<DeltaPlan> result = Collections.unmodifiableList(list);
        synchronized (CACHE) {
            CACHE.put(reg, result);
        }
        return result;
    }

    /** Farthest a delta reaches from its head (pixels): the longest walk plus the front slope and bars. */
    public static final int MAX_REACH_PX = 140;

    // ---- Math ----

    private static double wrap(double a) {
        return Math.atan2(Math.sin(a), Math.cos(a));
    }

    /** Hash to [0, 1). */
    private static float u(long h, long salt) {
        long z = h + salt * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 40) / (float) (1L << 24);
    }
}
