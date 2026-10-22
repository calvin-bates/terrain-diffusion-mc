package com.github.xandergos.terraindiffusionmc.hydrology;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.function.IntUnaryOperator;

/**
 * Applies river and lake data to one block-resolution heightmap tile.
 *
 * <p>For every block column it decides whether the column is a river channel, a river bank, a lake
 * or untouched, and produces:
 * <ul>
 *   <li>{@code surfaceY}: replacement terrain surface height (top solid block) where it was carved
 *       or built up, {@link #NONE} elsewhere;</li>
 *   <li>{@code waterY}: Y of the highest water block for columns that hold inland water,
 *       {@link #NONE} elsewhere.</li>
 * </ul>
 * It also rewrites biome ids so water columns (and the 4x4 biome cell around them) become river.
 *
 * <p>The result for a column depends only on that column's own terrain height and the region
 * data, never on the tile it is computed in, so adjacent tiles agree exactly.
 *
 * <p>Pure Java (no Minecraft types).
 */
public final class RiverCarver {
    private static final Logger LOG = LoggerFactory.getLogger(RiverCarver.class);
    private static boolean LOGGED_BUILD;
    public static final short NONE = Short.MIN_VALUE;
    public static final short BIOME_RIVER = 120, BIOME_FROZEN_RIVER = 121;

    /** Biome ids (from the biome classifier) that never get water: snowy / stony peaks and oceans. */
    // Biomes where no water is placed. Empty: streams and rivers are now allowed in stony and snowy peaks too
    // (previously {32, 33, 35, 41, 44, 46, 48}); the altitude and temperature limits in RiverMap still apply.
    private static final short[] NO_WATER_BIOMES = {};
    /** Biome ids that are frozen, so their rivers become frozen rivers. */
    /** True where the ground drops 2+ blocks per block next to (x, z): a cliff, not a stream bed. */
    private static boolean steepAt(int[] ytop, int H, int W, int r, int c) {
        int t = ytop[r * W + c];
        int[] dr = {-2, 2, 0, 0}, dc = {0, 0, -2, 2};
        for (int k = 0; k < 4; k++) {
            int rr = r + dr[k], cc = c + dc[k];
            if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
            if (Math.abs(ytop[rr * W + cc] - t) >= 5) return true;
        }
        return false;
    }

    /** True for cold biomes where river and lake surfaces freeze. */
    public static boolean isFrozen(short biome) { return contains(FROZEN_BIOMES, biome); }

    private static final short[] FROZEN_BIOMES = {3, 16, 32, 33, 34, 48, 49, 50, 51, 116, 131};   // 50 tundra, 51 polar desert

    /** Distance (blocks) from the channel edge over which banks are shaped. */
    private static final float BANK = 4f;   // how far a stream reshapes the ground beside it (was 8: diagonal steps reached too far)
    /** Bank rise per block of distance from the channel edge. */
    private static final float SLOPE = 1f;
    /** Water depth in blocks for stream / river / main river. */
    private static final int[] DEPTH = {0, 1, 3, 5};   // rivers deeper (were 2 and 3) so boats can use them

    /** Water depth of a channel column: rivers deepen from 1 block at the edge to their full depth in the middle. */
    private static int channelDepth(byte cls, float sc, float hw) {
        int d = DEPTH[cls];
        if (d <= 1) return d;
        float frac = Math.max(0f, Math.min(1f, -sc / (0.6f * hw)));
        return 1 + Math.round((d - 1) * frac);
    }

    /** Provides the region that owns a given region index. */
    public interface Regions {
        RiverService.Region get(int ri, int rj);
    }

    public static final class Result {
        public final short[][] surfaceY;
        public final short[][] waterY;
        /** True where the water is a lake (rivers are false). */
        public final boolean[][] isLake;

        Result(short[][] surfaceY, short[][] waterY, boolean[][] isLake) {
            this.surfaceY = surfaceY;
            this.waterY = waterY;
            this.isLake = isLake;
        }
    }

    private RiverCarver() { }

    /** Half channel width in blocks. Grows with world scale but never gets thinner than a minimum. */
    static float halfWidth(byte cls, int scale) {
        switch (cls) {
            case RiverMap.STREAM: return Math.max(1.0f, 0.45f * scale);
            case RiverMap.RIVER:  return Math.max(3.5f, 1.60f * scale);   // wider, navigable (was 2.0 / 0.90)
            default:              return Math.max(6.0f, 2.60f * scale);   // main rivers (was 3.5 / 1.50)
        }
    }

    /**
     * Region index (one axis) whose data to use for a pixel: the preferred region if the pixel is
     * within its trusted area, otherwise the region that owns the pixel.
     */
    private static int ownerIndex(int pixel, int preferred, int regionSize, int trust) {
        int lo = preferred * regionSize - trust;
        int hi = (preferred + 1) * regionSize + trust;
        return (pixel >= lo && pixel < hi) ? preferred : Math.floorDiv(pixel, regionSize);
    }

    /** How many lake pixels a stream's channel is carried into (inlets) or out of (outlets) a lake. */
    private static final int LAKE_LINK = 8;

    /** Rasterises one flow segment (pixel k to its downstream pixel q) into the per-column channel score. */
    private static void drawSegment(RiverService.Region reg, int k, int q, byte cls, float widen, int scale, int i1, int j1,
                                    int H, int W, float[] score, float[] sA, byte[] cA, float[] wA, float[] cpx, float[] cpz,
                                    float[] pt) {
        // Centre line smoothed with a 1-2-1 filter along the flow direction so the D8
        // staircase turns into gentle curves (all branches of a confluence still meet).
        smoothPoint(reg, k, scale, pt);
        float ax = pt[0], az = pt[1];
        smoothPoint(reg, q, scale, pt);
        float bx = pt[0], bz = pt[1];
        drawCapsule(ax, az, bx, bz, reg.surface[k], reg.surface[q], cls, widen, scale, i1, j1, H, W,
                score, sA, cA, wA, cpx, cpz);
    }

    /** Rasterises a channel centre line from (ax, az) to (bx, bz), in block coordinates, into the channel score. */
    private static void drawCapsule(float ax, float az, float bx, float bz, float sp, float sq, byte cls, float widen,
                                    int scale, int i1, int j1, int H, int W, float[] score, float[] sA, byte[] cA,
                                    float[] wA, float[] cpx, float[] cpz) {
        float hw = halfWidth(cls, scale) * widen;
        float reach = hw + BANK;
        int cMin = Math.max(0, (int) Math.floor(Math.min(ax, bx) - reach - j1));
        int cMax = Math.min(W - 1, (int) Math.ceil(Math.max(ax, bx) + reach - j1));
        int rMin = Math.max(0, (int) Math.floor(Math.min(az, bz) - reach - i1));
        int rMax = Math.min(H - 1, (int) Math.ceil(Math.max(az, bz) + reach - i1));
        if (cMin > cMax || rMin > rMax) return;

        float dx = bx - ax, dz = bz - az;
        float len2 = dx * dx + dz * dz;
        for (int r = rMin; r <= rMax; r++) {
            float pz = i1 + r + 0.5f;
            for (int c = cMin; c <= cMax; c++) {
                float px = j1 + c + 0.5f;
                float t = len2 > 0f ? ((px - ax) * dx + (pz - az) * dz) / len2 : 0f;
                t = t < 0f ? 0f : (t > 1f ? 1f : t);
                float ex = ax + t * dx - px, ez = az + t * dz - pz;
                float sc = (float) Math.sqrt(ex * ex + ez * ez) - hw;
                if (sc >= BANK) continue;
                int idx = r * W + c;
                if (sc < score[idx] - 1e-3f || (Math.abs(sc - score[idx]) <= 1e-3f && cls > cA[idx])) {
                    score[idx] = sc;
                    sA[idx] = sp + (sq - sp) * t;
                    cA[idx] = cls;
                    wA[idx] = widen;
                    cpx[idx] = ax + t * dx;   // nearest point on the centre line: identifies the channel's cross-section
                    cpz[idx] = az + t * dz;
                }
            }
        }
    }

    // ---- River mouths (v27, deltas reworked in v29) ----------------------------------------------------------------
    // Estuaries: over the last ESTUARY pixels before the sea a river widens into a funnel and deepens. Deltas: a river
    // or main river that crosses low, flat ground to the sea builds a lobe of new land out into the shallows and splits
    // into 3 to 6 distributaries across it (DeltaPlan; the land itself is raised by pipeline.DeltaShaper). Both come
    // from the region data only, so tiles agree.

    /** Y of the sea's top water block (sea level 63). */
    private static final int SEA_SURFACE_Y = 62;
    /** Pixels upstream of the sea over which a river widens into an estuary (900 m). */
    static final int ESTUARY = 30;
    /** Widest estuary, as a multiple of the river's normal width, by class (stream, river, main). */
    private static final float[] ESTUARY_WIDEN = {1f, 1.5f, 2.5f, 3.5f};
    /** Extra depth (blocks) at the mouth of the widest estuary. */
    private static final int ESTUARY_DEEPEN = 2;
    /** A delta splits this many pixels (900 m) upstream of the sea. */
    static final int DELTA_START = 30;
    /** Ground on a delta (metres) stays below this; anything higher is a valley, which makes an estuary instead. */
    static final float DELTA_MAX_M = 15f;

    /** Steps along the flow from pixel k to the first sea pixel (elevation at or below 0), or -1 within {@code limit}. */
    static int stepsToSea(RiverService.Region reg, int k, int limit) {
        if (reg.elev == null) return -1;
        int cur = k;
        for (int s = 0; s <= limit; s++) {
            if (reg.elev[cur] <= 0f) return s;
            int d = reg.downstream[cur];
            if (d < 0) return -1;
            cur = d;
        }
        return -1;
    }

    /** True if the flow path from k to the sea stays on low ground (a delta plain rather than a valley). */
    private static boolean lowPathToSea(RiverService.Region reg, int k) {
        int cur = k;
        for (int s = 0; s <= DELTA_START + 1; s++) {
            if (reg.elev[cur] <= 0f) return true;
            if (reg.elev[cur] > DELTA_MAX_M) return false;
            int d = reg.downstream[cur];
            if (d < 0) return false;
            cur = d;
        }
        return false;
    }

    /** Width multiplier for a river segment starting at pixel k (1 away from the sea). */
    private static float estuaryWiden(RiverService.Region reg, int k, byte cls) {
        int s = stepsToSea(reg, k, ESTUARY);
        if (s < 0) return 1f;
        float e = 1f - (float) s / ESTUARY;
        float max = ESTUARY_WIDEN[cls];
        // A delta's main channel stays narrow: the water spreads over the distributaries instead.
        if (cls >= RiverMap.RIVER && s <= DELTA_START && lowPathToSea(reg, k)) max = 1.3f;
        return 1f + (max - 1f) * e * e;
    }

    /** Depth of a channel column, including the estuary's extra depth. */
    private static int columnDepth(byte cls, float sc, float widen, int scale) {
        int d = channelDepth(cls, sc, halfWidth(cls, scale) * widen);
        float max = ESTUARY_WIDEN[cls];
        if (widen > 1.01f && max > 1f) d += Math.round(ESTUARY_DEEPEN * Math.min(1f, (widen - 1f) / (max - 1f)));
        return d;
    }

    /**
     * True if region pixel k is a stream or river within {@link #ESTUARY} pixels of the sea along its flow: the stretch
     * where it meets the coast. The coast shaper keeps cliffs away from these so rivers reach the sea at sea level.
     */
    public static boolean isNearMouth(RiverService.Region reg, int k) {
        byte c = reg.cls[k];
        return c >= RiverMap.STREAM && c <= RiverMap.MAIN && stepsToSea(reg, k, ESTUARY) >= 0;
    }

    /**
     * Region pixel where the delta that begins at head pixel k reaches the sea (the main channel's mouth), or -1.
     */
    public static int deltaMouth(RiverService.Region reg, int k) {
        int cur = k;
        for (int s = 0; s <= DELTA_START + 1; s++) {
            if (reg.elev[cur] <= 0f) return cur;
            int d = reg.downstream[cur];
            if (d < 0) return -1;
            cur = d;
        }
        return -1;
    }

    /** True if pixel k is where a river's delta begins. */
    public static boolean isDeltaHead(RiverService.Region reg, int k) {
        byte c = reg.cls[k];
        return (c == RiverMap.MAIN || c == RiverMap.RIVER) && reg.elev != null && reg.elev[k] > 0f
                && stepsToSea(reg, k, DELTA_START) == DELTA_START && lowPathToSea(reg, k);
    }

    /** Draws a delta's distributaries (see {@link DeltaPlan}): each channel widens a little toward the sea. */
    private static void drawDelta(DeltaPlan plan, int scale, int i1, int j1, int H, int W,
                                  float[] score, float[] sA, byte[] cA, float[] wA, float[] cpx, float[] cpz) {
        for (DeltaPlan.Channel ch : plan.channels) {
            for (int i = 0; i + 1 < ch.x.length; i++) {
                float t = Math.min(1f, (float) i / DELTA_START);
                float widen = 1f + 0.6f * t * t;
                drawCapsule(ch.x[i] * scale, ch.z[i] * scale, ch.x[i + 1] * scale, ch.z[i + 1] * scale,
                        ch.surface[i], ch.surface[i + 1], ch.cls, widen, scale, i1, j1, H, W, score, sA, cA, wA, cpx, cpz);
            }
        }
    }

    /** Block-space position of pixel {@code k}'s centre line point: (P_k + 2 P_d + P_dd) / 4. */
    private static void smoothPoint(RiverService.Region reg, int k, int scale, float[] out) {
        int d = reg.downstream[k];
        if (d < 0) {
            out[0] = (reg.j0 + k % reg.width + 0.5f) * scale;
            out[1] = (reg.i0 + k / reg.width + 0.5f) * scale;
            return;
        }
        int dd = reg.downstream[d];
        if (dd < 0) dd = d;
        float x = (reg.j0 + k % reg.width + 0.5f) + 2f * (reg.j0 + d % reg.width + 0.5f) + (reg.j0 + dd % reg.width + 0.5f);
        float z = (reg.i0 + k / reg.width + 0.5f) + 2f * (reg.i0 + d / reg.width + 0.5f) + (reg.i0 + dd / reg.width + 0.5f);
        out[0] = x * 0.25f * scale;
        out[1] = z * 0.25f * scale;
    }


    /** Catmull-Rom interpolation of a row-major grid at fractional (row, column), clamped at the grid's edges. */
    private static float catmullRom(float[] g, int gh, int gw, float fr, float fc) {
        int r0 = (int) Math.floor(fr), c0 = (int) Math.floor(fc);
        float tr = fr - r0, tc = fc - c0;
        float[] col = new float[4];
        for (int i = 0; i < 4; i++) {
            int rr = Math.max(0, Math.min(gh - 1, r0 - 1 + i));
            float[] v = new float[4];
            for (int j = 0; j < 4; j++) v[j] = g[rr * gw + Math.max(0, Math.min(gw - 1, c0 - 1 + j))];
            col[i] = cr(v[0], v[1], v[2], v[3], tc);
        }
        return cr(col[0], col[1], col[2], col[3], tr);
    }

    private static float cr(float p0, float p1, float p2, float p3, float t) {
        return 0.5f * (2f * p1 + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t * t + (-p0 + 3f * p1 - 3f * p2 + p3) * t * t * t);
    }

    /** Smooth deterministic value noise in [0,1], lattice spacing 7 blocks, from world block coordinates. */
    private static float valueNoise(int x, int z) { return valueNoise(x, z, 7); }

    private static float valueNoise(int x, int z, int S) {
        int x0 = Math.floorDiv(x, S), z0 = Math.floorDiv(z, S);
        float fx = (x - x0 * S) / (float) S, fz = (z - z0 * S) / (float) S;
        fx = fx * fx * (3f - 2f * fx); fz = fz * fz * (3f - 2f * fz);
        float a = latticeHash(x0, z0), b = latticeHash(x0 + 1, z0), c = latticeHash(x0, z0 + 1), d = latticeHash(x0 + 1, z0 + 1);
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
    }

    private static float latticeHash(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFF) / 65535f;
    }

    /** True if a 4-neighbour of the column holds stream (non-lake) water. Shore rounding keeps such lake columns: drying
     *  one left a strip of land between a stream and the lake it runs into. */
    private static boolean besideStream(int idx, int H, int W, short[][] waterY, boolean[] lakeCol) {
        int r = idx / W, c = idx % W;
        if (r > 0     && !lakeCol[idx - W] && waterY[r - 1][c] != NONE) return true;
        if (r < H - 1 && !lakeCol[idx + W] && waterY[r + 1][c] != NONE) return true;
        if (c > 0     && !lakeCol[idx - 1] && waterY[r][c - 1] != NONE) return true;
        if (c < W - 1 && !lakeCol[idx + 1] && waterY[r][c + 1] != NONE) return true;
        return false;
    }

    private static boolean contains(short[] set, short v) {
        for (short s : set) if (s == v) return true;
        return false;
    }

    /**
     * @param heightmap  tile heightmap in metres (only its shape is used here)
     * @param biomeIds   tile biome ids; modified in place
     * @param i1         world block Z of the tile's first row
     * @param j1         world block X of the tile's first column
     * @param scale      blocks per native pixel
     * @param metersToY  converts a heightmap value in metres to a Minecraft surface Y
     * @param regionSize native pixels per hydrology region
     * @param trust      pixels beyond a region's own area where its data is still exact; lets one
     *                   region serve a whole tile instead of computing every neighbour
     * @param regions    region provider
     * @return result, or null if the tile has no water at all
     */
    public static Result apply(short[][] heightmap, short[][] biomeIds, int i1, int j1, int scale,
                               IntUnaryOperator metersToY, int regionSize, int trust, Regions regions) {
        if (!LOGGED_BUILD) { LOGGED_BUILD = true; LOG.info("[Terra] lake code build: v30 (river water level from the cross-section, no gorges on steep slopes)"); }
        final int H = heightmap.length;
        final int W = H == 0 ? 0 : heightmap[0].length;
        final int n = H * W;
        if (n == 0) return null;

        int[] ytop = new int[n];
        for (int r = 0; r < H; r++)
            for (int c = 0; c < W; c++)
                ytop[r * W + c] = metersToY.applyAsInt(heightmap[r][c]);

        float[] score = new float[n];
        Arrays.fill(score, Float.POSITIVE_INFINITY);
        float[] sA = new float[n];
        float[] cpx = new float[n], cpz = new float[n];
        byte[] cA = new byte[n];
        float[] wA = new float[n];
        Arrays.fill(wA, 1f);

        // ---- rivers: rasterise flow segments (pixel centre -> downstream pixel centre)
        float maxReach = halfWidth(RiverMap.MAIN, scale) * ESTUARY_WIDEN[RiverMap.MAIN] + BANK;
        int pad = (int) Math.ceil(maxReach / scale) + 1;
        int pi0 = Math.floorDiv(i1, scale) - pad, pi1 = Math.floorDiv(i1 + H - 1, scale) + pad;
        int pj0 = Math.floorDiv(j1, scale) - pad, pj1 = Math.floorDiv(j1 + W - 1, scale) + pad;

        final int prefRi = Math.floorDiv(Math.floorDiv(i1 + H / 2, scale), regionSize);
        final int prefRj = Math.floorDiv(Math.floorDiv(j1 + W / 2, scale), regionSize);

        // Distance (in blocks, Chebyshev) from each column to the nearest lake pixel of the hydrology mask: 0 inside a lake
        // pixel, LAKE_NEAR + 1 for anything further than LAKE_NEAR. Taken from the region data, so it agrees across tiles.
        // Lake water may spread only this far outside the mask (v21): further out, ground below the lake level is not
        // part of the lake (it is the slope below an outlet), and spreading over it ran lake water along hillsides.
        final int LAKE_NEAR = Math.max(3, scale);
        final int[] lakeDist = new int[n];
        {
            final int rpx = LAKE_NEAR / scale + 1;
            int gi0 = Math.floorDiv(i1, scale) - rpx, gi1 = Math.floorDiv(i1 + H - 1, scale) + rpx;
            int gj0 = Math.floorDiv(j1, scale) - rpx, gj1 = Math.floorDiv(j1 + W - 1, scale) + rpx;
            int gh = gi1 - gi0 + 1, gw = gj1 - gj0 + 1;
            boolean[] lk = new boolean[gh * gw];
            boolean anyLake = false;
            RiverService.Region rg = null;
            int cri = Integer.MIN_VALUE, crj = Integer.MIN_VALUE;
            for (int a = 0; a < gh; a++) {
                for (int b = 0; b < gw; b++) {
                    int pi = gi0 + a, pj = gj0 + b;
                    int ri = ownerIndex(pi, prefRi, regionSize, trust), rj = ownerIndex(pj, prefRj, regionSize, trust);
                    if (rg == null || ri != cri || rj != crj) { rg = regions.get(ri, rj); cri = ri; crj = rj; }
                    int k = rg.index(pi, pj);
                    lk[a * gw + b] = k >= 0 && rg.cls[k] == RiverMap.LAKE;
                    anyLake |= lk[a * gw + b];
                }
            }
            Arrays.fill(lakeDist, LAKE_NEAR + 1);
            if (anyLake) {
                for (int r = 0; r < H; r++) {
                    int I = i1 + r, pi = Math.floorDiv(I, scale);
                    for (int c = 0; c < W; c++) {
                        int J = j1 + c, pj = Math.floorDiv(J, scale);
                        int best = LAKE_NEAR + 1;
                        for (int a = pi - rpx; a <= pi + rpx && best > 0; a++) {
                            for (int b = pj - rpx; b <= pj + rpx; b++) {
                                if (!lk[(a - gi0) * gw + (b - gj0)]) continue;
                                int dz = Math.max(0, Math.max(a * scale - I, I - (a * scale + scale - 1)));
                                int dx = Math.max(0, Math.max(b * scale - J, J - (b * scale + scale - 1)));
                                int d = Math.max(dx, dz);
                                if (d < best) best = d;
                            }
                        }
                        lakeDist[r * W + c] = best;
                    }
                }
            }
        }
        float[] pt = new float[2];
        RiverService.Region reg = null;
        int curRi = Integer.MIN_VALUE, curRj = Integer.MIN_VALUE;
        for (int pi = pi0; pi <= pi1; pi++) {
            for (int pj = pj0; pj <= pj1; pj++) {
                int ri = ownerIndex(pi, prefRi, regionSize, trust);
                int rj = ownerIndex(pj, prefRj, regionSize, trust);
                if (reg == null || ri != curRi || rj != curRj) {
                    reg = regions.get(ri, rj);
                    curRi = ri; curRj = rj;
                }
                int k = reg.index(pi, pj);
                if (k < 0) continue;
                byte cls0 = reg.cls[k];
                int q0 = reg.downstream[k];
                if (q0 < 0) continue;
                // A channel also runs through the edge of a lake's pixels: a lake pixel whose flow leaves the lake within
                // LAKE_LINK pixels carries the outlet stream, and a stream entering a lake continues LAKE_LINK pixels in.
                // Otherwise the flat ground inside the lake mask but level with the water (left dry) cut the stream off.
                byte cls;
                boolean inlet = false;
                if (cls0 >= RiverMap.STREAM && cls0 <= RiverMap.MAIN) {
                    cls = cls0;
                    inlet = true;
                } else if (cls0 == RiverMap.LAKE) {
                    // Only the lake pixel that drains straight into a stream (the outlet). Its channel is carried straight
                    // back into the lake, against the stream's direction, so it is one line and not a fan of every lake
                    // pixel that drains toward the outlet.
                    byte cq = reg.cls[q0];
                    if (cq < RiverMap.STREAM || cq > RiverMap.MAIN) continue;
                    int kr = k / reg.width, kc = k % reg.width, qr = q0 / reg.width, qc = q0 % reg.width;
                    int dr = qr - kr, dc = qc - kc;
                    drawSegment(reg, k, q0, cq, 1f, scale, i1, j1, H, W, score, sA, cA, wA, cpx, cpz, pt);
                    int curR = kr, curC = kc;
                    for (int st = 0; st < LAKE_LINK; st++) {
                        int prR = curR - dr, prC = curC - dc;
                        if (prR < 0 || prC < 0 || prR >= reg.height || prC >= reg.width) break;
                        int prev = prR * reg.width + prC;
                        if (reg.cls[prev] != RiverMap.LAKE) break;
                        drawSegment(reg, prev, curR * reg.width + curC, cq, 1f, scale, i1, j1, H, W, score, sA, cA, wA, cpx, cpz, pt);
                        curR = prR; curC = prC;
                    }
                    continue;
                } else {
                    continue;
                }
                int segK = k, q = q0;
                float widen = estuaryWiden(reg, k, cls);
                for (int seg = 0; seg <= LAKE_LINK; seg++) {
                    drawSegment(reg, segK, q, cls, widen, scale, i1, j1, H, W, score, sA, cA, wA, cpx, cpz, pt);
                    if (!inlet || reg.cls[q] != RiverMap.LAKE) break;
                    segK = q;
                    q = reg.downstream[segK];
                    if (q < 0) break;
                }
            }
        }

        // ---- deltas: the distributaries of every delta whose plan can reach this tile (see DeltaPlan). Each plan comes
        // from the region that owns its head, so all tiles draw the same channels.
        boolean nearSeaLevel = false;
        for (int v : ytop) if (v >= 50 && v <= 66) { nearSeaLevel = true; break; }
        if (nearSeaLevel) {   // deltas only touch ground near sea level; don't load neighbouring regions for others
            int dpad = pad + DeltaPlan.MAX_REACH_PX;
            int ri0 = Math.floorDiv(Math.floorDiv(i1, scale) - dpad, regionSize);
            int ri1 = Math.floorDiv(Math.floorDiv(i1 + H - 1, scale) + dpad, regionSize);
            int rj0 = Math.floorDiv(Math.floorDiv(j1, scale) - dpad, regionSize);
            int rj1 = Math.floorDiv(Math.floorDiv(j1 + W - 1, scale) + dpad, regionSize);
            float tcx = (j1 + W / 2f) / scale, tcz = (i1 + H / 2f) / scale;
            float tileHalf = Math.max(H, W) / 2f / scale + pad;
            for (int ri = ri0; ri <= ri1; ri++) {
                for (int rj = rj0; rj <= rj1; rj++) {
                    for (DeltaPlan plan : DeltaPlan.of(regions.get(ri, rj), ri, rj, regionSize)) {
                        if (!plan.near(tcx, tcz, tileHalf)) continue;
                        drawDelta(plan, scale, i1, j1, H, W, score, sA, cA, wA, cpx, cpz);
                    }
                }
            }
        }

        short[][] surfaceY = new short[H][W];
        short[][] waterY = new short[H][W];
        for (short[] row : surfaceY) Arrays.fill(row, NONE);
        for (short[] row : waterY) Arrays.fill(row, NONE);
        boolean any = false;
        boolean[] lakeCol = new boolean[n];

        // ---- finalise river channels (pass 1), then blend the banks into them (pass 2)
        final int NOWATER = Integer.MIN_VALUE;
        int[] chanWater = new int[n];
        Arrays.fill(chanWater, NOWATER);
        boolean[] steepSkip = new boolean[n];   // channel columns skipped for being too steep (gaps to bridge later)
        // Lowest bed allowed per column: near the sea a channel is no deeper than the sea floor beside it (a river mouth
        // shoals onto a bar rather than ending in a pit deeper than the sea). Integer.MIN_VALUE = no limit.
        int[] bedFloor = new int[n];
        Arrays.fill(bedFloor, Integer.MIN_VALUE);
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float sc = score[idx];
                if (sc == Float.POSITIVE_INFINITY || sc > 0f) continue;
                if (contains(NO_WATER_BIOMES, biomeIds[r][c])) continue;
                int top = ytop[idx];
                // Never carve the sea floor: a channel continued out into the sea as a dark trench across the shallows.
                if (top < SEA_SURFACE_Y) continue;
                // Too steep for a stream to hold water: it would only show as scattered ribbons.
                if (steepAt(ytop, H, W, r, c)) { steepSkip[idx] = true; continue; }
                int ys = metersToY.applyAsInt((int) Math.floor(Math.max(0f, sA[idx])));
                // Water never sits higher than the lowest ground beside the channel, otherwise it
                // would need an embankment (a ridge along the stream) to stay in place.
                int rad = Math.min(8, (int) Math.ceil(halfWidth(cA[idx], scale)) + 1);
                // "Beside" means across the channel: ground in the same cross-section (nearest centre-line points within a
                // block and a half, as in the one-level pass below). Counting the ground up and down the channel too put
                // the water of a river running down a steep slope at the level of the ground a few blocks downstream:
                // 5-13 blocks below its own banks, a gorge of stepped pools down the mountainside (v30, Calvin's
                // X -73622 Z 4091). Near the sea every direction still counts, so river mouths stay level with the sea.
                int low = top, lowAcross = top, seaLow = Integer.MAX_VALUE;
                for (int rr = Math.max(0, r - rad); rr <= Math.min(H - 1, r + rad); rr++) {
                    for (int cc = Math.max(0, c - rad); cc <= Math.min(W - 1, c + rad); cc++) {
                        // The ground of a lake's basin is not a bank: counting it cut a stream down to the lake floor where
                        // it meets the lake, several blocks below the lake's water.
                        int j = rr * W + cc;
                        if (lakeDist[j] == 0) continue;
                        int yt = ytop[j];
                        if (yt < low) low = yt;
                        if (yt < SEA_SURFACE_Y && yt < seaLow) seaLow = yt;
                        if (yt < lowAcross && score[j] != Float.POSITIVE_INFINITY) {
                            float ddx = cpx[j] - cpx[idx], ddz = cpz[j] - cpz[idx];
                            if (ddx * ddx + ddz * ddz <= 2.25f) lowAcross = yt;
                        }
                    }
                }
                if (seaLow != Integer.MAX_VALUE) bedFloor[idx] = seaLow - 1;
                else low = lowAcross;
                // The water's top block is level with the lowest ground beside the channel, so the stream sits flush with its
                // banks. (The hydrology surface is a coarse estimate; letting the water sag below the ground with it left a
                // one-block bank along almost every stream.) ys is unused now but kept for reference.
                // Near the coast the lowest ground can be the sea floor; river water never sits below the sea's surface
                // (Y 62), or a river mouth became a deep trench cut down to the sea floor.
                int waterTop = Math.max(low, Math.min(top, SEA_SURFACE_Y));
                int bed = Math.max(bedFloor[idx], waterTop - columnDepth(cA[idx], sc, wA[idx], scale));
                surfaceY[r][c] = (short) Math.min(top, bed);
                waterY[r][c] = (short) waterTop;
                chanWater[idx] = waterTop;
                any = true;
            }
        }

        // Bridge short gaps: a channel column skipped for steepness that sits between two stretches of water would
        // leave a hole in the stream. Join it to its neighbours at their lowest level, as long as the ground there is
        // at most 3 blocks above the water (the water placer can dig that much); a real drop is left as a gap.
        for (int pass = 0; pass < 3; pass++) {
            boolean grew = false;
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int idx = r * W + c;
                    if (!steepSkip[idx] || chanWater[idx] != NOWATER) continue;
                    float sc = score[idx];
                    if (sc == Float.POSITIVE_INFINITY || sc > 0f) continue;
                    int top = ytop[idx];
                    if (top < SEA_SURFACE_Y) continue;
                    int minLvl = Integer.MAX_VALUE, count = 0;
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            if (dr == 0 && dc == 0) continue;
                            int rr = r + dr, cc = c + dc;
                            if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                            int cw = chanWater[rr * W + cc];
                            if (cw == NOWATER) continue;
                            count++;
                            if (cw < minLvl) minLvl = cw;
                        }
                    }
                    if (count < 2 || top - minLvl > 3) continue;
                    int bed = Math.max(bedFloor[idx], minLvl - columnDepth(cA[idx], sc, wA[idx], scale));
                    surfaceY[r][c] = (short) Math.min(top, bed);
                    waterY[r][c] = (short) minLvl;
                    chanWater[idx] = minLvl;
                    grew = true;
                    any = true;
                }
            }
            if (!grew) break;
        }

        // Neighbouring channel columns never differ by more than one block of water level. On rough ground the "lowest nearby
        // ground" rule jumps by several blocks between adjacent columns, which showed as broken, stepped water and gaps along
        // the stream. Higher columns are lowered to meet the lower ones (a 45-degree cone around any dip, never raised).
        // A column is lowered by at most 2 blocks: on a steep slope the cone otherwise cut a deep trench with walls down the
        // mountainside. Bigger steps stay as steps; they are marked to flow, so they show as small waterfalls.
        final int[] chanOrig = chanWater.clone();
        for (int pass = 0; pass < 40; pass++) {
            boolean changed = false;
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int idx = r * W + c;
                    if (chanWater[idx] == NOWATER) continue;
                    int lim = chanWater[idx];
                    if (r > 0     && chanWater[idx - W] != NOWATER) lim = Math.min(lim, chanWater[idx - W] + 1);
                    if (r < H - 1 && chanWater[idx + W] != NOWATER) lim = Math.min(lim, chanWater[idx + W] + 1);
                    if (c > 0     && chanWater[idx - 1] != NOWATER) lim = Math.min(lim, chanWater[idx - 1] + 1);
                    if (c < W - 1 && chanWater[idx + 1] != NOWATER) lim = Math.min(lim, chanWater[idx + 1] + 1);
                    lim = Math.max(lim, chanOrig[idx] - 2);
                    if (lim < chanWater[idx]) {
                        chanWater[idx] = lim;
                        waterY[r][c] = (short) lim;
                        surfaceY[r][c] = (short) Math.min(ytop[idx], Math.max(bedFloor[idx], lim - columnDepth(cA[idx], score[idx], wA[idx], scale)));
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }

        // One water level across the channel (v25). Each column's level comes from the lowest ground near it, so where a
        // wide river's strip runs up a sloping bank (on the inside of a bend, say) the high side got its own, higher level:
        // a sheet of water stepping up the bank one block at a time, every step pouring down as a little waterfall. The
        // cross-section is the set of channel columns whose nearest centre-line points lie within a block and a half of
        // each other. A column more than a block above it is not river: it becomes bank, shaped below as a slope rising
        // from the water.
        // Rivers (wide and gentle) take the cross-section's lowest level: a column one block above it is lowered to it, so
        // the surface is flat from bank to bank (along the river a one-block step just moves up to a block and a half
        // upstream), and a column on ground more than 3 blocks above the water becomes bank too. Streams keep their own levels (lowering them left the water a block below the banks) and only shed
        // columns more than a block above their centre.
        {
            boolean[] dropped = new boolean[n];
            int[] lowerTo = new int[n];
            Arrays.fill(lowerTo, NOWATER);
            int dropCount = 0;
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int idx = r * W + c;
                    if (chanWater[idx] == NOWATER) continue;
                    int rr0 = (int) Math.ceil(halfWidth(cA[idx], scale)) + 1;
                    int ref = chanWater[idx], min = chanWater[idx], minTop = ytop[idx];
                    float best = score[idx];
                    for (int rr = Math.max(0, r - 2 * rr0); rr <= Math.min(H - 1, r + 2 * rr0); rr++) {
                        for (int cc = Math.max(0, c - 2 * rr0); cc <= Math.min(W - 1, c + 2 * rr0); cc++) {
                            int j = rr * W + cc;
                            if (chanWater[j] == NOWATER) continue;
                            float ddx = cpx[j] - cpx[idx], ddz = cpz[j] - cpz[idx];
                            if (ddx * ddx + ddz * ddz > 2.25f) continue;
                            if (chanWater[j] < min) min = chanWater[j];
                            if (ytop[j] < minTop) minTop = ytop[j];
                            if (score[j] < best) { best = score[j]; ref = chanWater[j]; }
                        }
                    }
                    if (cA[idx] >= RiverMap.RIVER) {
                        // Ground more than 3 above the water is hillside, not river bed (it was cut down into a trench with a
                        // dirt wall at the edge), unless the whole cross-section is that high: a ridge the river cuts through.
                        boolean hillside = ytop[idx] > min + 3 && minTop <= min + 3;
                        if (chanWater[idx] > min + 1 || hillside) { dropped[idx] = true; dropCount++; }
                        else if (chanWater[idx] > min) lowerTo[idx] = min;
                    } else if (chanWater[idx] > ref + 1) { dropped[idx] = true; dropCount++; }
                }
            }
            for (int idx = 0; idx < n; idx++) {
                if (lowerTo[idx] == NOWATER || dropped[idx]) continue;
                int r = idx / W, c = idx % W;
                chanWater[idx] = lowerTo[idx];
                waterY[r][c] = (short) lowerTo[idx];
                surfaceY[r][c] = (short) Math.min(ytop[idx], Math.max(bedFloor[idx], lowerTo[idx] - columnDepth(cA[idx], score[idx], wA[idx], scale)));
            }
            if (dropCount > 0) {
                // Distance (in blocks) from the remaining water, used as the dropped columns' bank score.
                int[] dq = new int[n];
                int[] dd = new int[n];
                Arrays.fill(dd, -1);
                int dh = 0, dt = 0;
                for (int idx = 0; idx < n; idx++) {
                    if (dropped[idx]) {
                        int r = idx / W, c = idx % W;
                        chanWater[idx] = NOWATER;
                        chanOrig[idx] = NOWATER;
                        waterY[r][c] = NONE;
                        surfaceY[r][c] = NONE;
                    }
                }
                // Bank columns measure their distance from the water that is left, not from the channel's old edge (the old
                // bank beyond a dropped strip was otherwise cut as if the water were right beside it: a dip behind the bank).
                for (int idx = 0; idx < n; idx++) if (chanWater[idx] != NOWATER) { dd[idx] = 0; dq[dt++] = idx; }
                while (dh < dt) {
                    int idx = dq[dh++];
                    if (dd[idx] > BANK) continue;
                    int r = idx / W, c = idx % W;
                    int[] nb = { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 };
                    for (int j : nb) {
                        if (j < 0 || dd[j] >= 0 || chanWater[j] != NOWATER) continue;
                        dd[j] = dd[idx] + 1;
                        dq[dt++] = j;
                    }
                }
                for (int idx = 0; idx < n; idx++) {
                    float far = dd[idx] > 0 ? dd[idx] : BANK + 1;
                    if (dropped[idx]) score[idx] = far;
                    else if (score[idx] > 0f && score[idx] != Float.POSITIVE_INFINITY) score[idx] = Math.max(score[idx], far);
                }
            }
        }

        // Spread each channel's water level outwards over the bank width, so the banks are shaped
        // against the water that is really there (not the unadjusted surface) and meet it flush.
        int[] bankWater = chanWater.clone();
        int bankReach = (int) Math.ceil(BANK) + 1;
        for (int pass = 0; pass < bankReach; pass++) {
            int[] next = bankWater.clone();
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int idx = r * W + c;
                    if (bankWater[idx] != NOWATER) continue;
                    int v = NOWATER;
                    if (r > 0     && bankWater[idx - W] != NOWATER) v = bankWater[idx - W];
                    else if (r < H - 1 && bankWater[idx + W] != NOWATER) v = bankWater[idx + W];
                    else if (c > 0     && bankWater[idx - 1] != NOWATER) v = bankWater[idx - 1];
                    else if (c < W - 1 && bankWater[idx + 1] != NOWATER) v = bankWater[idx + 1];
                    next[idx] = v;
                }
            }
            bankWater = next;
        }
        // Bank water level as a distance-weighted average of the nearby channel levels. Copying the nearest channel's
        // level (a 4-neighbour dilation) made diamond-shaped zones, so wherever two neighbouring stretches of stream
        // sat one block apart the bank had a straight 45-degree step. The average has no such seams.
        float[] bwF = new float[n];
        {
            final int WR = bankReach;
            final float inv2s2 = 1f / (2f * 2.5f * 2.5f);
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int idx = r * W + c;
                    bwF[idx] = bankWater[idx] == NOWATER ? 0f : bankWater[idx];
                    if (bankWater[idx] == NOWATER || chanWater[idx] != NOWATER) continue;
                    float acc = 0f, wsum = 0f;
                    for (int rr = Math.max(0, r - WR); rr <= Math.min(H - 1, r + WR); rr++) {
                        for (int cc = Math.max(0, c - WR); cc <= Math.min(W - 1, c + WR); cc++) {
                            int cw = chanWater[rr * W + cc];
                            if (cw == NOWATER) continue;
                            float d2 = (rr - r) * (rr - r) + (cc - c) * (cc - c);
                            float wgt = (float) Math.exp(-d2 * inv2s2);
                            acc += wgt * cw; wsum += wgt;
                        }
                    }
                    if (wsum > 1e-4f) bwF[idx] = acc / wsum;
                }
            }
        }
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float sc = score[idx];
                if (sc == Float.POSITIVE_INFINITY || sc <= 0f) continue;
                if (bankWater[idx] == NOWATER) continue;
                int top = ytop[idx];
                if (top < 60) continue;
                // The bank is a 1:1 ramp up from the water's edge, cut only where the ground is higher than the ramp, and never
                // deeper than a cap that shrinks by one block per block of distance. Each column is therefore at most one block
                // below its outer neighbour: a continuous slope. (Blending with a smooth weight and rounding put every rounding
                // step on a straight line parallel to the stream, which showed as a long diagonal strip.)
                sc = Math.max(0f, sc + (valueNoise(j1 + c, i1 + r) - 0.5f) * 1.2f);
                float ramp = bwF[idx] + Math.max(0f, sc - 1f) * SLOPE;   // flush with the water for the first block, then 1:1
                int rampI = Math.round(ramp);
                // Never cut a bank below the water right beside it, or the water would have nothing to hold it.
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int rr = r + dr, cc = c + dc;
                        if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                        int cw = chanWater[rr * W + cc];
                        if (cw != NOWATER && cw > rampI) rampI = cw;
                    }
                }
                int cap = (int) Math.floor(Math.max(0f, BANK - sc));
                if (top > rampI && cap > 0) {   // low banks are left alone: no embankments along streams
                    int newTop = Math.max(rampI, top - cap);
                    if (newTop < top) {
                        surfaceY[r][c] = (short) newTop;
                        any = true;
                    }
                }
            }
        }

        // ---- lakes: flat water over low ground inside (or right next to) lake pixels. The shore follows
        // the real ground contour, not the 30 m pixel squares of the lake mask.
        boolean[] ownLakeCol = new boolean[n];
        int[] lakeLvl = new int[n];
        int[] rimLvl = new int[n];
        Arrays.fill(rimLvl, NONE);
        reg = null;
        curRi = Integer.MIN_VALUE; curRj = Integer.MIN_VALUE;
        for (int r = 0; r < H; r++) {
            int pi = Math.floorDiv(i1 + r, scale);
            for (int c = 0; c < W; c++) {
                int pj = Math.floorDiv(j1 + c, scale);
                int ri = ownerIndex(pi, prefRi, regionSize, trust);
                int rj = ownerIndex(pj, prefRj, regionSize, trust);
                if (reg == null || ri != curRi || rj != curRj) {
                    reg = regions.get(ri, rj);
                    curRi = ri; curRj = rj;
                }
                float level = Float.NaN;
                float ownLevel = Float.NaN, minLevel = Float.NaN;
                boolean ownLake = false;
                int lakeNeighbours = 0;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int k = reg.index(pi + dr, pj + dc);
                        if (k >= 0 && reg.cls[k] == RiverMap.LAKE) {
                            lakeNeighbours++;
                            if (dr == 0 && dc == 0) ownLake = true;
                            float s = reg.surface[k];
                            if (dr == 0 && dc == 0) ownLevel = s;
                            if (Float.isNaN(minLevel) || s < minLevel) minLevel = s;
                            if (Float.isNaN(level) || s > level) level = s;
                        }
                    }
                }
                if (Float.isNaN(level)) continue;
                // Stony/snowy peak biomes get no rivers, but a lake there is fine: skipping them cut square holes in
                // alpine lakes, walled in with stone. Only the one-pixel rim keeps the biome exclusion.
                if (!ownLake && contains(NO_WATER_BIOMES, biomeIds[r][c])) continue;
                // A neighbouring lake at a different height must not lend its level (that perched water on walls):
                // a column in a lake keeps that lake's own level, a rim column takes the lowest adjacent level.
                level = ownLake ? ownLevel : minLevel;
                // A lake wins over a river channel that runs into it, so no channel is left cut into the lake.
                // ... except where the channel crosses ground at or above the lake level (the dry, flush margin of the lake
                // mask): there it stays a stream, so it reaches the water instead of ending at the margin.
                // (v24: ground exactly at the lake level inside the lake's own pixels is lake too, see below, so a channel
                // there gives way to it as well.)
                if (waterY[r][c] != NONE && (!ownLake || ytop[r * W + c] > metersToY.applyAsInt((int) Math.floor(Math.max(0f, level))))) continue;
                // metersToY already gives the real top block, so the water's top block is level with the basin's rim (it used
                // to subtract another block, which left every lake one block below its shore).
                int lakeTop = metersToY.applyAsInt((int) Math.floor(Math.max(0f, level)));
                int idx = r * W + c;
                int top = surfaceY[r][c] != NONE ? surfaceY[r][c] : ytop[idx];
                // Inside a lake pixel any low ground is lake bed; in the one-pixel rim only shallow
                // ground is flooded, otherwise the lake would hang as a water curtain down a slope.
                if (ytop[idx] < 60) continue;                 // never over the sea floor
                if (!ownLake) rimLvl[idx] = lakeTop;           // candidate for the flood-fill below
                // Inside a lake pixel the ground is genuinely below the lake level (it is a filled basin), even
                // in a deep gorge. Only the surrounding rim is limited to shallow ground.
                if (lakeTop - top > (ownLake ? 120 : 2)) continue;
                if (ownLake) { ownLakeCol[idx] = true; lakeLvl[idx] = lakeTop; }
                // Ground at the water level stays dry, level with the water (a flush shore); only lower ground is lake.
                // ... except inside the lake's own pixels (v24): there the hydrology says it is under water, and a lake on
                // flat ground often lies less than a block deep, so its level rounds to the ground's own height. Leaving
                // that ground dry hid such lakes almost entirely (and left the river channels drawn through them as
                // straight cuts). Its bed is dug a block down; the lake-bed pass below shapes it with the rest.
                boolean flushInLake = ownLake && top == lakeTop;
                if ((top < lakeTop && (ownLake || lakeTop - top <= 2)) || flushInLake) {
                    waterY[r][c] = (short) lakeTop;
                    if (flushInLake) surfaceY[r][c] = (short) (lakeTop - 1);
                    else if (surfaceY[r][c] == NONE) surfaceY[r][c] = (short) ytop[idx];
                    lakeCol[idx] = true;
                    any = true;
                }
            }
        }

        // Fill small dry specks inside lakes: ground that pokes just above the water but is boxed in by it.
        for (int iter = 0; iter < 16; iter++) {
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int idx = r * W + c;
                    if (!ownLakeCol[idx] || waterY[r][c] != NONE) continue;
                    int wn = 0;
                    if (r > 0     && waterY[r - 1][c] != NONE) wn++;
                    if (r < H - 1 && waterY[r + 1][c] != NONE) wn++;
                    if (c > 0     && waterY[r][c - 1] != NONE) wn++;
                    if (c < W - 1 && waterY[r][c + 1] != NONE) wn++;
                    // Only real specks, boxed in on three or four sides and barely above the water. (Two sides and up to 6 blocks
                    // let it creep along the whole shore, flooding and digging out the land at the edge of the lake mask.)
                    if (wn < 3) continue;
                    int top = surfaceY[r][c] != NONE ? surfaceY[r][c] : ytop[idx];
                    if (ytop[idx] < 60 || top - lakeLvl[idx] > 1) continue;
                    surfaceY[r][c] = (short) (lakeLvl[idx] - 1);
                    waterY[r][c] = (short) lakeLvl[idx];
                    lakeCol[idx] = true;
                    any = true;
                }
            }
        }

        // Flood to the shore: the water spreads from the lake over connected columns whose ground is below the water level,
        // and stops where the ground rises to the water level (the real shore). Outside the lake's own pixels it spreads only
        // over shallow ground (within 3 blocks of the level) and only near the lake mask (LAKE_NEAR blocks, v21).
        // Spreading "however far below" ran the lake down the next valley where the basin's rim is broken; spreading over
        // shallow ground up to 48 blocks away ran lake water along the hillside beside an outlet stream (a "fork" that just
        // ends, held up by a dam), and could leave a ring of low dry ground that let the spill step drain the whole lake.
        // Stream columns are not changed here: they are joined to the lake after the spill step, at the lake's final level
        // (see "stream mouths").
        {
            final int FLOOD_REACH = 48;
            int[] dist = new int[n];
            int[] srcLvl = new int[n];
            Arrays.fill(dist, -1);
            int[] fq = new int[n];
            int fh = 0, ft = 0, fSeeds = 0, flooded = 0;
            for (int idx = 0; idx < n; idx++) {
                if (lakeCol[idx]) { fSeeds++; dist[idx] = 0; srcLvl[idx] = waterY[idx / W][idx % W]; fq[ft++] = idx; }
            }
            while (fh < ft) {
                int idx = fq[fh++];
                int d = dist[idx];
                if (d >= FLOOD_REACH) continue;
                int r = idx / W, c = idx % W;
                int[] nb = { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 };
                for (int j : nb) {
                    if (j < 0 || dist[j] >= 0) continue;
                    int jr = j / W, jc = j % W;
                    boolean river = waterY[jr][jc] != NONE;
                    // Sea floor, or the shore has been reached: ground at the water level is the shore, left dry and flush with
                    // the water (flooding it and digging a 1-block bed made every shore a 2-block drop from land to floor).
                    if (ytop[j] < 60 || ytop[j] >= srcLvl[idx]) continue;
                    if (!ownLakeCol[j] && srcLvl[idx] - ytop[j] > 3) continue;
                    if (lakeDist[j] > LAKE_NEAR) continue;
                    // Not across a stream either: that flooded the ground on its far side, and once the spill step lowered
                    // the lake those patches were left as puddles beside the stream.
                    if (river) continue;
                    dist[j] = d + 1; srcLvl[j] = srcLvl[idx];
                    fq[ft++] = j;
                    waterY[jr][jc] = (short) srcLvl[idx];
                    if (surfaceY[jr][jc] == NONE) surfaceY[jr][jc] = (short) ytop[j];
                    lakeCol[j] = true;
                    flooded++;
                    any = true;
                }
            }
            if (fSeeds > 0) LOG.info("[Terra] lake flood tile x={} z={}: {} lake columns, flooded {} more down to the shore", j1, i1, fSeeds, flooded);
        }

        // ---- connect streams to lakes: a stream that starts or ends a few blocks from a lake, with dry ground in
        // between (an outlet that never reaches the water), is joined to it by a short channel whose water level
        // runs smoothly from the lake level to the stream's. Streams already touching a lake, or too far away, are left.
        {
            final int CONNECT_REACH = 12;
            int[] ld = new int[n], lsrc = new int[n];
            Arrays.fill(ld, -1);
            int[] cq = new int[n];
            int ch = 0, ct = 0;
            for (int idx = 0; idx < n; idx++) if (lakeCol[idx]) { ld[idx] = 0; lsrc[idx] = idx; cq[ct++] = idx; }
            while (ch < ct) {
                int idx = cq[ch++];
                if (ld[idx] >= CONNECT_REACH) continue;
                int r = idx / W, c = idx % W;
                int[] nb = { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 };
                for (int j : nb) {
                    if (j < 0 || ld[j] >= 0) continue;
                    ld[j] = ld[idx] + 1; lsrc[j] = lsrc[idx]; cq[ct++] = j;
                }
            }
            int[] comp = new int[n];
            Arrays.fill(comp, -1);
            int[] sq = new int[n];
            for (int start = 0; start < n; start++) {
                if (waterY[start / W][start % W] == NONE || lakeCol[start] || comp[start] >= 0) continue;
                int sh = 0, st = 0;
                sq[st++] = start; comp[start] = start;
                int best = -1;
                boolean touching = false;
                while (sh < st) {
                    int idx = sq[sh++];
                    if (ld[idx] == 1) touching = true;
                    if (ld[idx] >= 0 && (best < 0 || ld[idx] < ld[best])) best = idx;
                    int r = idx / W, c = idx % W;
                    int[] nb = { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 };
                    for (int j : nb) {
                        if (j < 0 || comp[j] >= 0 || lakeCol[j] || waterY[j / W][j % W] == NONE) continue;
                        comp[j] = start; sq[st++] = j;
                    }
                }
                if (touching || best < 0 || ld[best] < 2) continue;
                int src = lsrc[best];
                int sr = src / W, sc2 = src % W, dr0 = best / W, dc0 = best % W;
                int lakeLevel = waterY[sr][sc2], streamLevel = waterY[dr0][dc0];
                // Bresenham line from the lake column to the stream column.
                int steps = Math.max(Math.abs(dr0 - sr), Math.abs(dc0 - sc2));
                if (steps < 2) continue;
                int[] pathIdx = new int[steps + 1];
                boolean ok = true;
                for (int k = 0; k <= steps; k++) {
                    float t = k / (float) steps;
                    int pr = Math.round(sr + (dr0 - sr) * t), pc = Math.round(sc2 + (dc0 - sc2) * t);
                    int pidx = pr * W + pc;
                    pathIdx[k] = pidx;
                    int lvl = Math.round(lakeLevel + (streamLevel - lakeLevel) * t);
                    if (waterY[pr][pc] == NONE && ytop[pidx] - lvl > 6) { ok = false; break; }   // do not cut through a hill
                }
                if (!ok) continue;
                for (int k = 1; k < steps; k++) {
                    float t = k / (float) steps;
                    int lvl = Math.round(lakeLevel + (streamLevel - lakeLevel) * t);
                    int pidx = pathIdx[k];
                    int pr = pidx / W, pc = pidx % W;
                    // the line plus its 4-neighbours: a channel about 3 wide
                    int[] cells = { pidx, pr > 0 ? pidx - W : -1, pr < H - 1 ? pidx + W : -1, pc > 0 ? pidx - 1 : -1, pc < W - 1 ? pidx + 1 : -1 };
                    for (int q : cells) {
                        if (q < 0 || waterY[q / W][q % W] != NONE || lakeCol[q] || ytop[q] < 60) continue;
                        int top = surfaceY[q / W][q % W] != NONE ? surfaceY[q / W][q % W] : ytop[q];
                        waterY[q / W][q % W] = (short) lvl;
                        surfaceY[q / W][q % W] = (short) Math.min(top, lvl - 1);
                        any = true;
                    }
                }
            }
        }

        // ---- round the shore: the water mask follows 6-block lake pixels, which leaves right-angle corners. A 5x5
        // majority filter rounds them. It only touches shoreline cells whose ground is within 2 blocks of the water
        // level, so it moves the waterline by a block or two and never builds or digs anything large.
        for (int pass = 0; pass < 2; pass++) {
            final int R = 2;
            boolean[] flipToWater = new boolean[n], flipToDry = new boolean[n];
            int[] flipLvl = new int[n];
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int idx = r * W + c;
                    boolean isW = lakeCol[idx];
                    if (!isW && waterY[r][c] != NONE) continue;      // a river: leave it
                    if (ytop[idx] < 60) continue;
                    // boundary cells only: a 4-neighbour with the other state
                    boolean edge = false;
                    int lvl = NONE;
                    if (r > 0     && lakeCol[idx - W] != isW) { edge = true; if (!isW) lvl = waterY[r - 1][c]; }
                    if (r < H - 1 && lakeCol[idx + W] != isW) { edge = true; if (!isW && lvl == NONE) lvl = waterY[r + 1][c]; }
                    if (c > 0     && lakeCol[idx - 1] != isW) { edge = true; if (!isW && lvl == NONE) lvl = waterY[r][c - 1]; }
                    if (c < W - 1 && lakeCol[idx + 1] != isW) { edge = true; if (!isW && lvl == NONE) lvl = waterY[r][c + 1]; }
                    if (!edge) continue;
                    int wet = 0, tot = 0;
                    for (int dr = -R; dr <= R; dr++) {
                        for (int dc = -R; dc <= R; dc++) {
                            int rr = r + dr, cc = c + dc;
                            if (rr < 0 || cc < 0 || rr >= H || cc >= W) continue;
                            tot++;
                            if (lakeCol[rr * W + cc]) wet++;
                        }
                    }
                    int ground = surfaceY[r][c] != NONE ? surfaceY[r][c] : ytop[idx];
                    if (!isW && wet * 2 > tot && lvl != NONE && ground - lvl <= 0) {   // only land level with the water: no new banks
                        flipToWater[idx] = true; flipLvl[idx] = lvl;
                    } else if (isW && wet * 2 < tot && waterY[r][c] - ground <= 2 && !besideStream(idx, H, W, waterY, lakeCol)) {
                        flipToDry[idx] = true; flipLvl[idx] = waterY[r][c];
                    }
                }
            }
            boolean changed = false;
            for (int idx = 0; idx < n; idx++) {
                int r = idx / W, c = idx % W;
                if (flipToWater[idx]) {
                    waterY[r][c] = (short) flipLvl[idx];
                    surfaceY[r][c] = (short) (flipLvl[idx] - 1);   // a 1-block-deep bed
                    lakeCol[idx] = true; changed = true; any = true;
                } else if (flipToDry[idx]) {
                    waterY[r][c] = NONE;
                    surfaceY[r][c] = (short) flipLvl[idx];         // land flush with the old water surface
                    lakeCol[idx] = false; changed = true; any = true;
                }
            }
            if (!changed) break;
        }

        // ---- dry patches inside a lake: ground at (or below) the water level stays dry at the shore, but a patch of it that
        // the lake surrounds on every side reads as missing water. Such enclosed patches (up to 400 columns) are flooded.
        {
            boolean[] seenP = new boolean[n];
            int[] pq = new int[n];
            for (int s0 = 0; s0 < n; s0++) {
                if (seenP[s0] || lakeCol[s0] || waterY[s0 / W][s0 % W] != NONE || ytop[s0] < 60) continue;
                // only start from a column next to the lake, at or below its level
                int r0 = s0 / W, c0 = s0 % W, lvl0 = NONE;
                if (r0 > 0 && lakeCol[s0 - W]) lvl0 = waterY[r0 - 1][c0];
                else if (r0 < H - 1 && lakeCol[s0 + W]) lvl0 = waterY[r0 + 1][c0];
                else if (c0 > 0 && lakeCol[s0 - 1]) lvl0 = waterY[r0][c0 - 1];
                else if (c0 < W - 1 && lakeCol[s0 + 1]) lvl0 = waterY[r0][c0 + 1];
                int top0 = surfaceY[r0][c0] != NONE ? surfaceY[r0][c0] : ytop[s0];
                if (lvl0 == NONE || top0 > lvl0) continue;
                int dph = 0, dpt = 0;
                pq[dpt++] = s0; seenP[s0] = true;
                boolean enclosed = true;
                while (dph < dpt) {
                    int idx = pq[dph++];
                    int r = idx / W, c = idx % W;
                    if (r == 0 || c == 0 || r == H - 1 || c == W - 1) enclosed = false;
                    int[] nb = { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 };
                    for (int j : nb) {
                        if (j < 0 || lakeCol[j] || seenP[j]) continue;
                        int jr = j / W, jc = j % W;
                        int tj = surfaceY[jr][jc] != NONE ? surfaceY[jr][jc] : ytop[j];
                        if (waterY[jr][jc] != NONE || tj > lvl0 || ytop[j] < 60) { enclosed = false; continue; }
                        seenP[j] = true;
                        if (dpt < 400) pq[dpt++] = j; else enclosed = false;
                    }
                }
                if (!enclosed) continue;
                for (int k = 0; k < dpt; k++) {
                    int idx = pq[k];
                    int r = idx / W, c = idx % W;
                    waterY[r][c] = (short) lvl0;
                    surfaceY[r][c] = (short) Math.min(lvl0 - 1, surfaceY[r][c] != NONE ? surfaceY[r][c] : ytop[idx]);
                    lakeCol[idx] = true;
                    any = true;
                }
            }
        }

        // ---- lake spill level: a lake can only hold water up to the lowest part of its rim. Where the rim
        // drops away (the water hangs over a slope) the level is lowered until the water is held in by
        // ground that rises all the way around, instead of walling it in with stone.
        {
            boolean[] seen = new boolean[n];
            int[] queue = new int[n];
            for (int start = 0; start < n; start++) {
                if (!lakeCol[start] || seen[start]) continue;
                int qh = 0, qt = 0;
                queue[qt++] = start; seen[start] = true;
                while (qh < qt) {
                    int idx = queue[qh++];
                    int r = idx / W, c = idx % W;
                    if (r > 0     && lakeCol[idx - W] && !seen[idx - W]) { seen[idx - W] = true; queue[qt++] = idx - W; }
                    if (r < H - 1 && lakeCol[idx + W] && !seen[idx + W]) { seen[idx + W] = true; queue[qt++] = idx + W; }
                    if (c > 0     && lakeCol[idx - 1] && !seen[idx - 1]) { seen[idx - 1] = true; queue[qt++] = idx - 1; }
                    if (c < W - 1 && lakeCol[idx + 1] && !seen[idx + 1]) { seen[idx + 1] = true; queue[qt++] = idx + 1; }
                }
                int count = qt;
                int level = Integer.MIN_VALUE;
                for (int k = 0; k < count; k++) { int idx = queue[k]; level = Math.max(level, waterY[idx / W][idx % W]); }
                boolean touchesEdge = false;
                for (int k = 0; k < count; k++) {
                    int idx = queue[k]; int r = idx / W, c = idx % W;
                    if (r == 0 || c == 0 || r == H - 1 || c == W - 1) { touchesEdge = true; break; }
                }
                // Chunks only see part of a big lake; leveling a partial view would make neighbours disagree.
                if (touchesEdge) {
                    LOG.info("[Terra] lake at block x={} z={}: {} columns, level Y{}, touches tile edge -> not leveled", j1, i1, count, level);
                    continue;
                }
                final int level0 = level;
                int[] ring = new int[count * 4];
                for (int iter = 0; iter < 5; iter++) {
                    int rn = 0;
                    for (int k = 0; k < count; k++) {
                        int idx = queue[k];
                        if (!lakeCol[idx]) continue;
                        int r = idx / W, c = idx % W;
                        int[] nb = { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 };
                        for (int j : nb) {
                            if (j < 0 || lakeCol[j] || ytop[j] < 60) continue;
                            ring[rn++] = ytop[j];
                        }
                    }
                    if (rn == 0) break;
                    java.util.Arrays.sort(ring, 0, rn);
                    int spill = ring[Math.min(rn - 1, rn / 12)];
                    LOG.info("[Terra] lake tile x={} z={}: {} columns, level Y{}, rim min Y{} / spill Y{} / max Y{} ({} rim cells), iter {}", j1, i1, count, level, ring[0], spill, ring[rn - 1], rn, iter);
                    if (spill >= level) break;
                    level = spill;
                    LOG.info("[Terra] lake in tile x={} z={}: {} columns, spill lowered Y{} -> Y{}", j1, i1, count, level0, level);
                    for (int k = 0; k < count; k++) {
                        int idx = queue[k];
                        if (!lakeCol[idx]) continue;
                        int r = idx / W, c = idx % W;
                        if (ytop[idx] >= level) {   // ground at or above the spill level is dry shore
                            lakeCol[idx] = false;
                            waterY[r][c] = NONE;
                            surfaceY[r][c] = NONE;
                        } else {
                            waterY[r][c] = (short) level;
                        }
                    }
                }
            }
        }

        // ---- puddles (v21): lake water entirely outside the lake's own pixels (shallow ground in the one-pixel rim) that
        // is not connected to its lake (cut off by a stream, or left behind when the spill step lowered the lake) and sits
        // 2+ blocks above a stream beside it would pour into the stream from the side. It is left dry.
        {
            boolean[] seenL = new boolean[n];
            int[] lq = new int[n];
            int removed = 0;
            for (int s0 = 0; s0 < n; s0++) {
                if (!lakeCol[s0] || seenL[s0]) continue;
                int lh = 0, lt = 0;
                lq[lt++] = s0; seenL[s0] = true;
                boolean own = false, spills = false, edge = false;
                while (lh < lt) {
                    int idx = lq[lh++];
                    if (ownLakeCol[idx]) own = true;
                    int r = idx / W, c = idx % W;
                    if (r == 0 || c == 0 || r == H - 1 || c == W - 1) edge = true;   // decided the same way in both tiles
                    int lvl = waterY[r][c];
                    int[] nb = { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 };
                    for (int j : nb) {
                        if (j < 0) continue;
                        if (lakeCol[j]) { if (!seenL[j]) { seenL[j] = true; lq[lt++] = j; } continue; }
                        int wj = waterY[j / W][j % W];
                        if (wj != NONE && wj <= lvl - 2) spills = true;
                    }
                }
                if (own || edge || !spills) continue;
                for (int k = 0; k < lt; k++) {
                    int idx = lq[k];
                    lakeCol[idx] = false;
                    waterY[idx / W][idx % W] = NONE;
                    removed++;
                }
            }
            if (removed > 0) LOG.info("[Terra] tile x={} z={}: {} columns of cut-off lake water beside a lower stream left dry", j1, i1, removed);
        }

        // ---- stream mouths (v21): where a stream meets a lake, done after the spill step so it uses the lake's final level.
        // Stream columns touching the lake (and, through each other, the rest of the stream's mouth) whose water is below
        // the lake level are raised: to the lake's level within LAKE_NEAR of the lake mask, then one block lower per block
        // (a stream leaving the lake steps down to its own level). Only where the water ends up at most a block above the
        // column's ground and no bank or neighbouring stream water is more than a block below it (no new step, no wall).
        // They keep their carved bed. Without this the stream dips below the lake right where it meets it; where the
        // stream is cut much lower than the lake, the step stays at the lake's edge (the lake spills into the stream).
        // Before, the raised mouth ended in a single 3-4 block drop into a deep pool, and it was raised before the spill
        // step, so a lowered lake left its outlet standing above both the lake and the stream below.
        {
            // Target levels for the stream columns near each lake, decided for the whole mouth at once (across a stream
            // several blocks wide, raising columns one at a time made each wait for its still-low neighbour).
            int[] tgt = new int[n];
            int[] cap = new int[n];
            Arrays.fill(cap, Integer.MAX_VALUE);
            boolean[] cand = new boolean[n];
            int raised = 0, stepped = 0;
            for (int round = 0; round < 24; round++) {
                Arrays.fill(cand, false);
                // highest level reachable from a lake through stream columns: the lake's level while within LAKE_NEAR of
                // the lake mask on ground at most a block below it, otherwise one block lower per block
                for (int pass = 0; pass < 64; pass++) {
                    boolean changed = false;
                    for (int r = 0; r < H; r++) {
                        for (int c = 0; c < W; c++) {
                            int idx = r * W + c;
                            if (lakeCol[idx] || waterY[r][c] == NONE || ytop[idx] < 60) continue;
                            int best = Integer.MIN_VALUE;
                            int[] nb = { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 };
                            for (int q : nb) {
                                if (q < 0) continue;
                                int src;
                                if (lakeCol[q]) src = waterY[q / W][q % W];
                                else if (cand[q]) src = tgt[q];
                                else continue;
                                int t = (lakeDist[idx] <= LAKE_NEAR && src - ytop[idx] <= 1) ? src : src - 1;
                                if (t > best) best = t;
                            }
                            best = Math.min(best, cap[idx]);
                            if (best == Integer.MIN_VALUE || best <= waterY[r][c] || best - ytop[idx] > 1) continue;
                            if (!cand[idx] || best > tgt[idx]) { cand[idx] = true; tgt[idx] = best; changed = true; }
                        }
                    }
                    if (!changed) break;
                }
                // each raised column must be held in: every 4-neighbour is lake water, or a raised column, stream water or
                // dry ground at most a block below it. One that is not gets a lower cap and the targets are worked out again.
                boolean blocked = false;
                for (int idx = 0; idx < n; idx++) {
                    if (!cand[idx]) continue;
                    int r = idx / W, c = idx % W;
                    int[] nb = { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 };
                    for (int q : nb) {
                        if (q < 0 || lakeCol[q]) continue;
                        int qr = q / W, qc = q % W;
                        int h = cand[q] ? tgt[q] : waterY[qr][qc] != NONE ? waterY[qr][qc] : surfaceY[qr][qc] != NONE ? surfaceY[qr][qc] : ytop[q];
                        if (h < tgt[idx] - 1) { cap[idx] = tgt[idx] - 1; blocked = true; break; }
                    }
                }
                if (!blocked) break;
            }
            for (int idx = 0; idx < n; idx++) {
                if (!cand[idx]) continue;
                int r = idx / W, c = idx % W;
                boolean atLake = false;
                for (int q : new int[] { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 })
                    if (q >= 0 && lakeCol[q] && waterY[q / W][q % W] == tgt[idx]) atLake = true;
                if (atLake) raised++; else stepped++;
                waterY[r][c] = (short) tgt[idx];
                if (surfaceY[r][c] == NONE || surfaceY[r][c] >= tgt[idx]) surfaceY[r][c] = (short) Math.min(ytop[idx], tgt[idx] - 1);
                any = true;
            }
            if (raised + stepped > 0) LOG.info("[Terra] stream mouths tile x={} z={}: {} stream columns raised (next to the lake {}, further along {})", j1, i1, raised + stepped, raised, stepped);
        }

        // ---- lake bed: from the shore to the middle the bed should only go down, never come back up. A column
        // shallower than the nearest shoreward neighbour (a bench or ridge in the natural ground) is dug down to it, by at
        // most 6 blocks. Nothing else is touched, so the natural bed stays natural everywhere it already descends.
        {
            int[] bd = new int[n];
            Arrays.fill(bd, -1);
            int[] bq2 = new int[n];
            int bh2 = 0, bt2 = 0;
            for (int idx = 0; idx < n; idx++) {
                if (!lakeCol[idx]) continue;
                int r = idx / W, c = idx % W;
                boolean shore = (r > 0 && !lakeCol[idx - W] && waterY[r - 1][c] == NONE)
                        || (r < H - 1 && !lakeCol[idx + W] && waterY[r + 1][c] == NONE)
                        || (c > 0 && !lakeCol[idx - 1] && waterY[r][c - 1] == NONE)
                        || (c < W - 1 && !lakeCol[idx + 1] && waterY[r][c + 1] == NONE);
                if (shore) { bd[idx] = 1; bq2[bt2++] = idx; }
            }
            for (int q = 0; q < bt2; q++) {
                int idx = bq2[q];
                int r = idx / W, c = idx % W;
                int[] nb = { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 };
                for (int j : nb) {
                    if (j < 0 || !lakeCol[j] || bd[j] >= 0) continue;
                    bd[j] = bd[idx] + 1; bq2[bt2++] = j;
                }
            }
            // The generated floor of a shallow, flat basin shows every one-block step of the terrain, and some of those run
            // dead straight (seams in the terrain model's tiles), which reads as straight lines through clear water. The bed
            // follows a blurred copy of the natural ground instead, as sediment would, so its steps follow soft curves.
            float[] smooth = new float[n];
            {
                final int SR = 4;
                final float inv2s2 = 1f / (2f * 2.2f * 2.2f);
                for (int q = 0; q < bt2; q++) {
                    int idx = bq2[q];
                    int r = idx / W, c = idx % W;
                    float acc = 0f, ws = 0f;
                    for (int rr = Math.max(0, r - SR); rr <= Math.min(H - 1, r + SR); rr++) {
                        for (int cc = Math.max(0, c - SR); cc <= Math.min(W - 1, c + SR); cc++) {
                            float d2 = (rr - r) * (rr - r) + (cc - c) * (cc - c);
                            float wgt = (float) Math.exp(-d2 * inv2s2);
                            acc += wgt * ytop[rr * W + cc]; ws += wgt;
                        }
                    }
                    smooth[idx] = acc / ws;
                }
            }
            // Lakes get deeper toward the middle: extra depth grows with the distance to the nearest pixel that is not deep
            // lake (outside the hydrology lake mask, or a lake pixel whose natural basin is under SHALLOW_BLOCKS deep), about
            // 1 block per 4.5 blocks, up to 5, with a slow +-25% wobble. The distance is taken
            // from the region data, not this tile's shoreline, so a lake that crosses tile borders gets the same depth on
            // both sides.
            float[] extra = new float[n];
            {
                final float EXTRA_PER_BLOCK = 0.22f, EXTRA_MAX = 5f, SHALLOW_BLOCKS = 0.6f;
                final float blocksPerMetre = (metersToY.applyAsInt(10000) - metersToY.applyAsInt(0)) / 10000f;
                final int maxD = (int) Math.ceil(EXTRA_MAX / EXTRA_PER_BLOCK);
                final int rpx = maxD / scale + 4;
                int gi0 = Math.floorDiv(i1, scale) - rpx, gi1 = Math.floorDiv(i1 + H - 1, scale) + rpx;
                int gj0 = Math.floorDiv(j1, scale) - rpx, gj1 = Math.floorDiv(j1 + W - 1, scale) + rpx;
                int gh = gi1 - gi0 + 1, gw = gj1 - gj0 + 1;
                boolean[] lakePx = new boolean[gh * gw];
                RiverService.Region rg = null;
                int cri = Integer.MIN_VALUE, crj = Integer.MIN_VALUE;
                for (int a = 0; a < gh; a++) {
                    for (int b = 0; b < gw; b++) {
                        int pi = gi0 + a, pj = gj0 + b;
                        int ri = ownerIndex(pi, prefRi, regionSize, trust), rj = ownerIndex(pj, prefRj, regionSize, trust);
                        if (rg == null || ri != cri || rj != crj) { rg = regions.get(ri, rj); cri = ri; crj = rj; }
                        int k = rg.index(pi, pj);
                        // v23: only the part of the lake whose natural basin is at least SHALLOW_BLOCKS deep counts. On flat
                        // ground the hydrology mask reaches well past the water, and measuring from the mask's edge put
                        // almost the full extra depth right at the real shore: a 4-6 block drop from the beach.
                        boolean deep = k >= 0 && rg.cls[k] == RiverMap.LAKE;
                        if (deep && rg.elev != null) deep = (rg.surface[k] - rg.elev[k]) * blocksPerMetre >= SHALLOW_BLOCKS;
                        lakePx[a * gw + b] = deep;
                    }
                }
                // v26: the distance is worked out once per pixel (from its centre) and blended smoothly between pixel
                // centres (Catmull-Rom). Measured column by column to the nearest pixel square, the depth contours took on
                // the squares' straight edges and corners: lines all over the floors of flat lowland lakes.
                final int R = maxD / scale + 1;
                float[] pd = new float[gh * gw];
                for (int a = 0; a < gh; a++) {
                    for (int b = 0; b < gw; b++) {
                        if (!lakePx[a * gw + b]) continue;   // not deep lake: 0
                        float px = (gj0 + b + 0.5f) * scale, pz = (gi0 + a + 0.5f) * scale;
                        float best = maxD;
                        for (int a2 = Math.max(0, a - R); a2 <= Math.min(gh - 1, a + R); a2++) {
                            for (int b2 = Math.max(0, b - R); b2 <= Math.min(gw - 1, b + R); b2++) {
                                if (lakePx[a2 * gw + b2]) continue;
                                int ai = gi0 + a2, bj = gj0 + b2;
                                float dx = Math.max(0f, Math.max(bj * scale - px, px - (bj + 1) * scale));
                                float dz = Math.max(0f, Math.max(ai * scale - pz, pz - (ai + 1) * scale));
                                float d = (float) Math.sqrt(dx * dx + dz * dz);
                                if (d < best) best = d;
                            }
                        }
                        pd[a * gw + b] = best;
                    }
                }
                for (int q = 0; q < bt2; q++) {
                    int idx = bq2[q];
                    int r = idx / W, c = idx % W;
                    float fz = (i1 + r + 0.5f) / scale - 0.5f - gi0, fx = (j1 + c + 0.5f) / scale - 0.5f - gj0;
                    float best = Math.max(0f, Math.min(maxD, catmullRom(pd, gh, gw, fz, fx)));
                    float wobble = 0.75f + 0.5f * valueNoise(j1 + c, i1 + r, 19);
                    extra[idx] = Math.min(EXTRA_MAX, best * EXTRA_PER_BLOCK) * wobble;
                }
            }
            int[] depth = new int[n];
            for (int q = 0; q < bt2; q++) {
                int idx = bq2[q];
                int r = idx / W, c = idx % W;
                int level = waterY[r][c];
                // v26: a gentle floor wobble (about +-0.9 block, two smooth noises on 13- and 5-block lattices) away from the
                // shore. Low-lying terrain is nearly flat, and its one-block steps run in long straight lines; a floor that
                // just follows it showed those lines through the water.
                float floorFade = Math.min(1f, Math.max(0f, (bd[idx] - 2) / 4f));
                float wob = ((valueNoise(j1 + c, i1 + r, 13) - 0.5f) * 1.3f + (valueNoise(j1 + c, i1 + r, 5) - 0.5f) * 0.5f) * floorFade;
                int natural = Math.max(1, Math.round(level - smooth[idx] + extra[idx] + wob));
                // Variety right at the shore: the first 3 blocks get a slowly changing +-1 block of extra or less depth
                // (smooth noise, 11-block lattice), so the drop from shore to lake floor is not the same everywhere.
                int off = 0;
                if (bd[idx] <= 3) {
                    float fade = (4 - bd[idx]) / 3f;
                    off = Math.round((valueNoise(j1 + c, i1 + r, 11) - 0.5f) * 2.6f * fade);
                }
                natural = Math.max(1, natural + off);
                // No "only deeper toward the middle" rule any more: it worked along grid-step distance layers and left terraces
                // with straight diagonal edges. The extra middle depth already makes lakes deepen toward the middle.
                int d = natural;
                depth[idx] = d;
                int bedY = level - d;
                // The bed may fill small dips and shave small humps (up to 3 blocks) and is carved down by the extra middle
                // depth. Out-of-range values are clamped (skipping them left steps between neighbouring columns).
                bedY = Math.max(bedY, ytop[idx] - 3 - (int) Math.ceil(extra[idx]));
                bedY = Math.min(bedY, Math.min(ytop[idx] + 3, level - 1));
                int cur = surfaceY[r][c] != NONE ? surfaceY[r][c] : ytop[idx];
                if (bedY != cur) { surfaceY[r][c] = (short) bedY; any = true; }
            }
        }

        // ---- natural dams: where a lake's water stands 2+ blocks above the dry ground beside it (a rim broken by the
        // terrain's small-scale noise), the water placer used to wall it in with a vertical dirt face. Raise that ground
        // into a dam instead: level with the water at the shore, then sloping down one block per block. Done column by
        // column, so a lake that crosses a tile border gets the same shore on both sides.
        {
            final int DAM_REACH = 10;
            int[] dd = new int[n];
            int[] dlvl = new int[n];
            Arrays.fill(dd, -1);
            int[] dq = new int[n];
            int dh = 0, dt = 0;
            for (int idx = 0; idx < n; idx++) {
                if (!lakeCol[idx]) continue;
                int r = idx / W, c = idx % W;
                int lvl = waterY[r][c];
                int[] nb = { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 };
                for (int j : nb) {
                    if (j < 0 || waterY[j / W][j % W] != NONE || ytop[j] < 60) continue;
                    int tj = surfaceY[j / W][j % W] != NONE ? surfaceY[j / W][j % W] : ytop[j];
                    if (lvl - tj < 2) continue;
                    if (dd[j] < 0 || lvl > dlvl[j]) {
                        if (dd[j] < 0) dq[dt++] = j;
                        dd[j] = 1; dlvl[j] = lvl;
                    }
                }
            }
            while (dh < dt) {
                int idx = dq[dh++];
                int r = idx / W, c = idx % W;
                int target = dlvl[idx] - (dd[idx] - 1);
                int top = surfaceY[r][c] != NONE ? surfaceY[r][c] : ytop[idx];
                if (target <= top) continue;   // the ground already meets the slope: the dam ends here
                surfaceY[r][c] = (short) target;
                any = true;
                if (dd[idx] >= DAM_REACH) continue;
                int[] nb = { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 };
                for (int j : nb) {
                    if (j < 0 || dd[j] >= 0 || waterY[j / W][j % W] != NONE || ytop[j] < 60) continue;
                    dd[j] = dd[idx] + 1; dlvl[j] = dlvl[idx]; dq[dt++] = j;
                }
            }
        }

        // ---- one-block pits: a dry column near water that is lower than at least 3 of its dry 4-neighbours (all of them
        // higher) is a pit left by the carving and rounding passes. Fill it up to its lowest neighbour (at most 2 blocks).
        {
            boolean[] near = new boolean[n];
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    if (waterY[r][c] == NONE) continue;
                    for (int rr = Math.max(0, r - 3); rr <= Math.min(H - 1, r + 3); rr++)
                        for (int cc = Math.max(0, c - 3); cc <= Math.min(W - 1, c + 3); cc++) near[rr * W + cc] = true;
                }
            }
            int[] g = new int[n];
            for (int r = 0; r < H; r++)
                for (int c = 0; c < W; c++) g[r * W + c] = surfaceY[r][c] != NONE ? surfaceY[r][c] : ytop[r * W + c];
            for (int r = 1; r < H - 1; r++) {
                for (int c = 1; c < W - 1; c++) {
                    int idx = r * W + c;
                    if (!near[idx] || waterY[r][c] != NONE || ytop[idx] < 60) continue;
                    int[] nb = { idx - W, idx + W, idx - 1, idx + 1 };
                    int dry = 0, minN = Integer.MAX_VALUE;
                    boolean allHigher = true;
                    for (int j : nb) {
                        if (waterY[j / W][j % W] != NONE) continue;
                        dry++;
                        if (g[j] <= g[idx]) allHigher = false;
                        if (g[j] < minN) minN = g[j];
                    }
                    if (dry >= 3 && allHigher && minN - g[idx] <= 2) {
                        surfaceY[r][c] = (short) minN;
                        any = true;
                    }
                }
            }
        }

        // Diagnostics: water columns held up by a tall drop on the dry side ("dams"). Logs a few per tile with the
        // exact numbers so a wall can be traced to the data that produced it.
        {
            int logged = 0, dams = 0;
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    if (waterY[r][c] == NONE) continue;
                    int[] dr = {-1, 1, 0, 0}, dc = {0, 0, -1, 1};
                    for (int k = 0; k < 4; k++) {
                        int rr = r + dr[k], cc = c + dc[k];
                        if (rr < 0 || cc < 0 || rr >= H || cc >= W || waterY[rr][cc] != NONE) continue;
                        int nIdx = rr * W + cc;
                        int nTop = surfaceY[rr][cc] != NONE ? surfaceY[rr][cc] : ytop[nIdx];
                        if (waterY[r][c] - nTop > 8) {
                            dams++;
                            if (logged < 6) {
                                logged++;
                                int idx = r * W + c;
                                LOG.info("[Terra] dam at block x={} z={}: water Y{} (lake={}) own ytop Y{} surf {} | dry neighbour x={} z={} ytop Y{} surf {} | cls own/n scores {}/{}",
                                        j1 + c, i1 + r, waterY[r][c], lakeCol[idx], ytop[idx], surfaceY[r][c],
                                        j1 + cc, i1 + rr, ytop[nIdx], surfaceY[rr][cc], score[idx], score[nIdx]);
                            }
                            break;
                        }
                    }
                }
            }
            if (dams > 0) LOG.info("[Terra] tile x={} z={}: {} water columns with a >8 block drop on the dry side", j1, i1, dams);
        }

        if (!any) return null;

        // Biomes are left untouched so banks and beds keep the surface of the biome they are in.
        boolean[][] isLake = new boolean[H][W];
        for (int r = 0; r < H; r++) for (int c = 0; c < W; c++) isLake[r][c] = lakeCol[r * W + c];
        return new Result(surfaceY, waterY, isLake);
    }
}
