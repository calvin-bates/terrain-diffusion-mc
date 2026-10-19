package com.github.xandergos.terraindiffusionmc.hydrology;

import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The layout of one river delta: the land the river has built out into the sea, and the channels that cross it.
 *
 * <p>A delta forms where a river or main river crosses a low, flat coastal plain to the sea (see
 * {@link RiverCarver#isDeltaHead}). From the head, where the river first splits, the plan lays out:
 * <ul>
 *   <li><b>The lobe:</b> the old coastline, pushed out to sea in a smooth bulge that is widest off the river mouth
 *       and tapers to nothing along the coast on either side, so the new land meets the old shore at a shallow angle.
 *       The bulge's own shoreline is ragged. The terrain only builds it over shallow water.</li>
 *   <li><b>Distributaries:</b> the main channel runs on from the old mouth to the new shore; two side branches leave
 *       at the head and usually fork again, so 3 to 6 channels reach the sea. A branch that cannot reach the sea
 *       (blocked by higher ground or deep water) is left out, so no channel ends in the marsh.</li>
 *   <li><b>Tidal creeks:</b> short, winding creeks that open on the new shore and reach a little way into the marsh.</li>
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
    /** Farthest the lobe reaches from the apex in any direction (pixels). */
    public final float maxRadius;
    /** Half the fan's opening angle (radians). */
    public final float halfAngle;
    /** Where the river reached the old coastline (native pixel centre). */
    public final float mouthX, mouthZ;
    public final byte riverClass;
    public final List<Channel> channels;
    public final List<Channel> creeks;
    /** Mouth bars: centre, unit direction (along the channel) for each. */
    public final float[] barX, barZ, barDX, barDZ;

    /** How far (pixels) the bulge pushes the coast out, at its middle. */
    private final float bulge;
    /** Width of the bulge's bell, in radians. */
    private final float sigma;
    private final double axisAngle;
    /** Distance from the apex to the old coast, sampled across the fan. */
    private final float[] coast;
    private final double[] frontPhase = new double[3];
    private final double[] frontAmp = new double[3];

    /** Small-scale wiggle of the new shoreline (world coordinates, so every plan and tile agree). */
    private static final FastNoiseLite OUTLINE = new FastNoiseLite(7311);
    static {
        OUTLINE.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        OUTLINE.SetFrequency(1f / 7f);
        OUTLINE.SetFractalType(FastNoiseLite.FractalType.FBm);
        OUTLINE.SetFractalOctaves(3);
        OUTLINE.SetFractalGain(0.55f);
    }
    /** Amplitude (pixels) of the shoreline wiggle where the bulge is full. */
    private static final float WIGGLE_PX = 5f;
    /** Coast samples across the fan. */
    private static final int COAST_SAMPLES = 49;
    /** Farthest the coast is searched for from the apex (pixels). */
    private static final float COAST_SEARCH = 120f;
    /** A channel ends this far (as a fraction of the local lobe radius) past the new shore, so it opens to the sea. */
    private static final float CHANNEL_END_U = 1.04f;
    private static final int MAX_WALK = 260;

    // Distance grids (pixels), filled lazily; cover the plan's bounding box.
    private volatile float[] chanDist, creekDist;
    private int gx0, gz0, gw, gh;

    private DeltaPlan(RiverService.Region reg, int k, int m) {
        int kr = k / reg.width, kc = k % reg.width;
        apexX = reg.j0 + kc + 0.5f;
        apexZ = reg.i0 + kr + 0.5f;
        mouthX = reg.j0 + m % reg.width + 0.5f;
        mouthZ = reg.i0 + m / reg.width + 0.5f;
        float fx = mouthX - apexX, fz = mouthZ - apexZ, fl = (float) Math.hypot(fx, fz);
        dirX = fl > 0 ? fx / fl : 1f;
        dirZ = fl > 0 ? fz / fl : 0f;
        axisAngle = Math.atan2(dirZ, dirX);
        riverClass = reg.cls[k];
        long h = (long) (reg.i0 + kr) * 73856093L ^ (long) (reg.j0 + kc) * 19349663L ^ 0xDE17AL;
        boolean main = riverClass == RiverMap.MAIN;
        bulge = main ? 45f + 30f * u(h, 1) : 25f + 15f * u(h, 2);
        halfAngle = 1.15f + 0.25f * u(h, 3);
        sigma = halfAngle * (0.42f + 0.1f * u(h, 6));
        for (int i = 0; i < 3; i++) {
            frontPhase[i] = u(h, 10 + i) * 2 * Math.PI;
            frontAmp[i] = 0.09 / (i + 1);
        }

        // The old coastline as seen from the apex: march out along each direction to the first sea pixel.
        float[] c = new float[COAST_SAMPLES];
        for (int s = 0; s < COAST_SAMPLES; s++) {
            double a = axisAngle + (-1 + 2.0 * s / (COAST_SAMPLES - 1)) * halfAngle;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a), d = COAST_SEARCH;
            for (float t = 0.5f; t < COAST_SEARCH; t += 0.5f) {
                int q = reg.index((int) Math.floor(apexZ + sa * t), (int) Math.floor(apexX + ca * t));
                if (q < 0) { d = t; break; }
                if (reg.elev[q] <= 0f) { d = t; break; }
            }
            c[s] = d;
        }
        // Smooth it so the new shore follows the coast's shape, not its pixel steps.
        for (int pass = 0; pass < 3; pass++) {
            float[] n = c.clone();
            for (int s = 1; s + 1 < COAST_SAMPLES; s++) n[s] = 0.25f * c[s - 1] + 0.5f * c[s] + 0.25f * c[s + 1];
            c = n;
        }
        coast = c;
        float mr = 0;
        for (int s = 0; s < COAST_SAMPLES; s++) {
            double rel = (-1 + 2.0 * s / (COAST_SAMPLES - 1)) * halfAngle;
            mr = Math.max(mr, radiusAt(rel) + WIGGLE_PX);
        }
        maxRadius = mr;

        List<Channel> chans = new ArrayList<>();
        List<float[]> bars = new ArrayList<>();
        float surf0 = Math.max(0f, reg.surface[k]);
        byte side = main ? RiverMap.RIVER : RiverMap.STREAM;
        // Main channel: carried on from the old mouth to the new shore, forking near the end now and then.
        walk(reg, mouthX, mouthZ, axisAngle, riverClass, 0f, h ^ 0x11, 0, u(h, 4) < 0.5f ? 0.7f : 2f, chans, bars);
        // Side branches from the head, each usually forking once.
        for (int s = -1; s <= 1; s += 2) {
            double ang = s * (0.45 + 0.3 * u(h, 5 + s));
            walk(reg, apexX, apexZ, axisAngle + ang, side, surf0, h ^ (s * 0x5BD1E995L), 0,
                    u(h, 7 + s) < 0.7f ? 0.45f + 0.2f * u(h, 9 + s) : 2f, chans, bars);
        }
        // Other streams and rivers that used to reach the sea where the new land now lies are carried across it too,
        // so none of them ends at the old shore behind the delta.
        int r0 = (int) Math.floor(apexZ - maxRadius), r1 = (int) Math.ceil(apexZ + maxRadius);
        int c0 = (int) Math.floor(apexX - maxRadius), c1 = (int) Math.ceil(apexX + maxRadius);
        for (int pi = r0; pi <= r1; pi++) {
            for (int pj = c0; pj <= c1; pj++) {
                int q = reg.index(pi, pj);
                if (q < 0 || reg.cls[q] < RiverMap.STREAM || reg.cls[q] > RiverMap.MAIN || reg.elev[q] <= 0f) continue;
                int d = reg.downstream[q];
                if (d < 0 || d == m || reg.elev[d] > 0f) continue;            // only the last land pixel of a river
                float sx = reg.j0 + d % reg.width + 0.5f, sz = reg.i0 + d / reg.width + 0.5f;
                if (bellAt(sx, sz) < 0.02f || lobeU(sx, sz) >= CHANNEL_END_U) continue;
                double hd = Math.atan2(sz - (pi + 0.5f), sx - (pj + 0.5f));
                walk(reg, sx, sz, hd, reg.cls[q], 0f, h ^ ((long) q * 0x2545F491L), 1, 2f, false, chans, bars);
            }
        }
        channels = Collections.unmodifiableList(chans);
        int nb = bars.size();
        barX = new float[nb]; barZ = new float[nb]; barDX = new float[nb]; barDZ = new float[nb];
        for (int i = 0; i < nb; i++) {
            float[] b = bars.get(i);
            barX[i] = b[0]; barZ[i] = b[1]; barDX[i] = b[2]; barDZ[i] = b[3];
        }
        creeks = Collections.unmodifiableList(buildCreeks(h));
    }

    /**
     * Walks one channel from (x, z) on the given heading until it is just past the new shore. It forks into two
     * smaller channels once it has covered {@code forkAt} of the way (no fork when that is above 1). Only channels
     * that reach the sea are kept; returns whether this one (or a fork of it) did.
     */
    private boolean walk(RiverService.Region reg, float x, float z, double heading, byte cls, float surf, long h,
                         int depth, float forkAt, List<Channel> out, List<float[]> bars) {
        return walk(reg, x, z, heading, cls, surf, h, depth, forkAt, true, out, bars);
    }

    private boolean walk(RiverService.Region reg, float x, float z, double heading, byte cls, float surf, long h,
                         int depth, float forkAt, boolean inBulge, List<Channel> out, List<float[]> bars) {
        List<float[]> pts = new ArrayList<>();
        pts.add(new float[]{x, z, surf});
        float startU = lobeU(x, z);
        for (int st = 0; st < MAX_WALK; st++) {
            // Wander gently, and drift back toward the fan's axis so channels spread but stay inside it.
            double rel = wrap(heading - axisAngle);
            heading += 0.22 * (u(h, 100 + st) - 0.5) - 0.004 * rel;
            float nx = x + (float) Math.cos(heading), nz = z + (float) Math.sin(heading);
            int q = reg.index((int) Math.floor(nz), (int) Math.floor(nx));
            if (q < 0) return false;
            float e = reg.elev[q];
            if (e > RiverCarver.DELTA_MAX_M) return false;                // blocked by higher ground
            if (e < -40f) return false;                                   // the lobe never reaches deep water
            float sq = Math.max(0f, Math.min(surf, reg.surface[q]));
            x = nx; z = nz; surf = sq;
            pts.add(new float[]{x, z, surf});
            float uNow = lobeU(x, z);
            // A channel that wanders off sideways, out of the bulge, would run along the old coast: drop it.
            if (inBulge && bellAt(x, z) < 0.06f) return false;
            if (uNow >= CHANNEL_END_U) {                                  // out at sea: a mouth bar off its end
                out.add(new Channel(pts, cls));
                bars.add(new float[]{x + 3f * (float) Math.cos(heading), z + 3f * (float) Math.sin(heading),
                        (float) Math.cos(heading), (float) Math.sin(heading)});
                return true;
            }
            if (depth == 0 && forkAt <= 1f && (uNow - startU) / Math.max(0.05f, 1f - startU) >= forkAt) {
                boolean any = false;
                for (int s = -1; s <= 1; s += 2) {
                    any |= walk(reg, x, z, heading + s * (0.35 + 0.2 * u(h, 300 + s)), RiverMap.STREAM, surf,
                            h ^ (0x9E37L * (s + 2)), depth + 1, 2f, out, bars);
                }
                if (any) out.add(new Channel(pts, cls));
                return any;
            }
        }
        return false;
    }

    /** Short creeks winding in from the new shore, a few with a side creek. */
    private List<Channel> buildCreeks(long h) {
        List<Channel> out = new ArrayList<>();
        int n = 5 + (int) (bulge / 7f);
        for (int i = 0; i < n; i++) {
            long hc = h ^ (0xC4EE7L * (i + 1));
            // Spread over the part of the shore that has really moved out.
            double rel = (u(hc, 1) * 2 - 1) * sigma * 1.3;
            if (bell(rel) < 0.3f) continue;
            double a = axisAngle + rel;
            float r = radiusAt(rel);
            float x = apexX + (float) Math.cos(a) * r, z = apexZ + (float) Math.sin(a) * r;
            // Step out to where this point's ragged shore actually is, plus a little, so the creek opens to the sea.
            for (int t = 0; t < 20 && lobeU(x, z) < CHANNEL_END_U; t++) {
                x += (float) Math.cos(a) * 0.7f;
                z += (float) Math.sin(a) * 0.7f;
            }
            creek(x, z, a + Math.PI + (u(hc, 2) - 0.5) * 0.8, 6 + 10 * u(hc, 3), hc, true, out);
        }
        return out;
    }

    private void creek(float x, float z, double heading, float length, long h, boolean main, List<Channel> out) {
        List<float[]> pts = new ArrayList<>();
        pts.add(new float[]{x, z, 0f});
        float step = 0.7f;
        int steps = (int) (length / step);
        for (int st = 0; st < steps; st++) {
            heading += 0.5 * (u(h, 50 + st) - 0.5);
            x += (float) Math.cos(heading) * step;
            z += (float) Math.sin(heading) * step;
            if (lobeU(x, z) < 0.25f) break;                                // stays in the outer marsh
            pts.add(new float[]{x, z, 0f});
            if (main && st == steps / 2 && u(h, 7) < 0.45f) {
                creek(x, z, heading + (u(h, 8) < 0.5 ? -1 : 1) * 0.8, 2 + 4 * u(h, 9), h ^ 0x51DEL, false, out);
            }
        }
        if (pts.size() >= 2) out.add(new Channel(pts, (byte) 0));
    }

    /**
     * Where (x, z) sits in the lobe: its distance from the apex over the local lobe radius (with the ragged shore).
     * Below 1 is on the delta, above 1 past its shore; {@link Float#POSITIVE_INFINITY} outside the fan's angle.
     */
    public float lobeU(float x, float z) {
        float dx = x - apexX, dz = z - apexZ;
        float r = (float) Math.hypot(dx, dz);
        if (r < 1e-3f) return 0f;
        double rel = wrap(Math.atan2(dz, dx) - axisAngle);
        float rad = radiusAt(rel);
        if (rad <= 0f) return Float.POSITIVE_INFINITY;
        // Ragged shoreline (points and coves ~50-200 m across), only where the coast has moved out.
        rad += WIGGLE_PX * Math.min(1f, 3f * bell(rel)) * OUTLINE.GetNoise(x, z);
        return r / Math.max(1f, rad);
    }

    /**
     * How much of the bulge there is in direction {@code rel} from the axis (1 off the mouth, 0 at the fan's edges):
     * a bell that reaches 0 with zero slope, so the new shore runs smoothly into the old one.
     */
    public float bell(double rel) {
        double t = Math.abs(rel);
        if (t >= halfAngle) return 0f;
        double e = Math.exp(-sq(t / sigma)), eh = Math.exp(-sq(halfAngle / sigma));
        double b = (e - eh) / (1 - eh);
        // Ease the last stretch to zero slope at the fan's edge too.
        double edge = 1 - Math.pow(t / halfAngle, 6);
        return (float) Math.max(0, b * edge);
    }

    /** {@link #bell} at point (x, z). */
    public float bellAt(float x, float z) {
        return bell(wrap(Math.atan2(z - apexZ, x - apexX) - axisAngle));
    }

    /** Lobe radius (pixels) in direction {@code rel} from the axis: the old coast plus the bulge. */
    public float radiusAt(double rel) {
        double t = Math.abs(rel) / halfAngle;
        if (t >= 1) return 0f;
        double fs = (rel / halfAngle + 1) * 0.5 * (COAST_SAMPLES - 1);
        int s0 = Math.max(0, Math.min(COAST_SAMPLES - 2, (int) Math.floor(fs)));
        float tt = (float) Math.max(0, Math.min(1, fs - s0));
        float c = coast[s0] * (1 - tt) + coast[s0 + 1] * tt;
        double wob = 0;
        for (int i = 0; i < 3; i++) wob += frontAmp[i] * Math.cos((i + 2) * rel * 3 + frontPhase[i]);
        return (float) (c + bulge * bell(rel) * (1 + wob));
    }

    /** Distance (pixels) from (x, z) to the lobe's ragged shore, positive on the delta side. */
    public float shoreDistance(float x, float z) {
        float u = lobeU(x, z);
        if (u == Float.POSITIVE_INFINITY) return -999f;
        float r = (float) Math.hypot(x - apexX, z - apexZ);
        return u > 0 ? r / u - r : r;
    }

    /** Distance (pixels, up to 6) from (x, z) to the nearest channel centre line. */
    public float channelDistance(float x, float z) {
        float[] g = chanDist;
        if (g == null) { buildGrids(); g = chanDist; }
        return lookup(g, x, z);
    }

    /** Distance (pixels, up to 6) from (x, z) to the nearest tidal creek. */
    public float creekDistance(float x, float z) {
        float[] g = creekDist;
        if (g == null) { buildGrids(); g = creekDist; }
        return lookup(g, x, z);
    }

    private float lookup(float[] g, float x, float z) {
        int c = (int) Math.floor(x) - gx0, r = (int) Math.floor(z) - gz0;
        if (c < 0 || r < 0 || c >= gw || r >= gh) return 6f;
        return g[r * gw + c];
    }

    private synchronized void buildGrids() {
        if (chanDist != null) return;
        int pad = 8;
        gx0 = (int) Math.floor(apexX - maxRadius) - pad;
        gz0 = (int) Math.floor(apexZ - maxRadius) - pad;
        gw = (int) Math.ceil(2 * maxRadius) + 2 * pad;
        gh = gw;
        float[] cg = new float[gw * gh], kg = new float[gw * gh];
        Arrays.fill(cg, 6f);
        Arrays.fill(kg, 6f);
        for (Channel ch : channels) rasterise(ch, cg);
        for (Channel ch : creeks) rasterise(ch, kg);
        creekDist = kg;
        chanDist = cg;
    }

    private void rasterise(Channel ch, float[] g) {
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

    /** Bounding box test: could (x, z) be affected by this delta (lobe, front slope or bars)? */
    public boolean near(float x, float z, float margin) {
        float m = maxRadius + 60f + margin;
        return Math.abs(x - apexX) <= m && Math.abs(z - apexZ) <= m;
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

    /** Farthest a delta reaches from its head (pixels): coast search, bulge, wiggle, front slope and bars. */
    public static final int MAX_REACH_PX = 260;

    // ---- Math ----

    private static double sq(double v) {
        return v * v;
    }

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
