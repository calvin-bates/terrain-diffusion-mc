package com.github.xandergos.terraindiffusionmc.hydrology;

import java.util.Arrays;
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
    public static final short NONE = Short.MIN_VALUE;
    public static final short BIOME_RIVER = 120, BIOME_FROZEN_RIVER = 121;

    /** Biome ids (from the biome classifier) that never get water: snowy / stony peaks and oceans. */
    private static final short[] NO_WATER_BIOMES = {32, 33, 35, 41, 44, 46, 48};
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

    private static final short[] FROZEN_BIOMES = {3, 16, 32, 33, 48, 116};

    /** Distance (blocks) from the channel edge over which banks are shaped. */
    private static final float BANK = 8f;
    /** Bank rise per block of distance from the channel edge. */
    private static final float SLOPE = 1f;
    /** Water depth in blocks for stream / river / main river. */
    private static final int[] DEPTH = {0, 1, 2, 3};

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
            case RiverMap.RIVER:  return Math.max(2.0f, 0.90f * scale);
            default:              return Math.max(3.5f, 1.50f * scale);
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
        byte[] cA = new byte[n];

        // ---- rivers: rasterise flow segments (pixel centre -> downstream pixel centre)
        float maxReach = halfWidth(RiverMap.MAIN, scale) + BANK;
        int pad = (int) Math.ceil(maxReach / scale) + 1;
        int pi0 = Math.floorDiv(i1, scale) - pad, pi1 = Math.floorDiv(i1 + H - 1, scale) + pad;
        int pj0 = Math.floorDiv(j1, scale) - pad, pj1 = Math.floorDiv(j1 + W - 1, scale) + pad;

        final int prefRi = Math.floorDiv(Math.floorDiv(i1 + H / 2, scale), regionSize);
        final int prefRj = Math.floorDiv(Math.floorDiv(j1 + W / 2, scale), regionSize);
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
                byte cls = reg.cls[k];
                if (cls < RiverMap.STREAM || cls > RiverMap.MAIN) continue;
                int q = reg.downstream[k];
                if (q < 0) continue;

                float sp = reg.surface[k], sq = reg.surface[q];
                float hw = halfWidth(cls, scale);
                float reach = hw + BANK;
                // Centre line smoothed with a 1-2-1 filter along the flow direction so the D8
                // staircase turns into gentle curves (all branches of a confluence still meet).
                smoothPoint(reg, k, scale, pt);
                float ax = pt[0], az = pt[1];
                smoothPoint(reg, q, scale, pt);
                float bx = pt[0], bz = pt[1];

                int cMin = Math.max(0, (int) Math.floor(Math.min(ax, bx) - reach - j1));
                int cMax = Math.min(W - 1, (int) Math.ceil(Math.max(ax, bx) + reach - j1));
                int rMin = Math.max(0, (int) Math.floor(Math.min(az, bz) - reach - i1));
                int rMax = Math.min(H - 1, (int) Math.ceil(Math.max(az, bz) + reach - i1));
                if (cMin > cMax || rMin > rMax) continue;

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
                        }
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

        // ---- finalise river channels and banks
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float sc = score[idx];
                if (sc == Float.POSITIVE_INFINITY) continue;
                short biome = biomeIds[r][c];
                if (contains(NO_WATER_BIOMES, biome)) continue;

                int top = ytop[idx];
                if (top < 60) continue;   // never put river water over the sea floor
                int ys = metersToY.applyAsInt((int) Math.floor(Math.max(0f, sA[idx])));
                if (sc <= 0f) {
                    // Too steep for a stream to hold water: it would only show as scattered ribbons.
                    if (steepAt(ytop, H, W, r, c)) continue;
                    // Water never sits higher than the lowest ground beside the channel, otherwise it
                    // would need an embankment (a ridge along the stream) to stay in place.
                    int rad = Math.min(5, (int) Math.ceil(halfWidth(cA[idx], scale)) + 1);
                    int low = top;
                    for (int rr = Math.max(0, r - rad); rr <= Math.min(H - 1, r + rad); rr++) {
                        for (int cc = Math.max(0, c - rad); cc <= Math.min(W - 1, c + rad); cc++) {
                            if (ytop[rr * W + cc] < low) low = ytop[rr * W + cc];
                        }
                    }
                    int waterTop = Math.min(ys - 1, low);
                    int bed = waterTop - DEPTH[cA[idx]];
                    surfaceY[r][c] = (short) Math.min(top, bed);
                    waterY[r][c] = (short) waterTop;
                    any = true;
                } else {
                    float t = sc / BANK;
                    float w = 1f - t * t * (3f - 2f * t);
                    float target = ys + sc * SLOPE;
                    int newTop = top;
                    if (top > target) {
                        newTop = Math.round(top - w * (top - target));
                    }   // low banks are left alone: no embankments along streams
                    if (newTop != top) {
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
        reg = null;
        curRi = Integer.MIN_VALUE; curRj = Integer.MIN_VALUE;
        for (int r = 0; r < H; r++) {
            int pi = Math.floorDiv(i1 + r, scale);
            for (int c = 0; c < W; c++) {
                if (contains(NO_WATER_BIOMES, biomeIds[r][c])) continue;
                int pj = Math.floorDiv(j1 + c, scale);
                int ri = ownerIndex(pi, prefRi, regionSize, trust);
                int rj = ownerIndex(pj, prefRj, regionSize, trust);
                if (reg == null || ri != curRi || rj != curRj) {
                    reg = regions.get(ri, rj);
                    curRi = ri; curRj = rj;
                }
                float level = Float.NaN;
                boolean ownLake = false;
                int lakeNeighbours = 0;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int k = reg.index(pi + dr, pj + dc);
                        if (k >= 0 && reg.cls[k] == RiverMap.LAKE) {
                            lakeNeighbours++;
                            if (dr == 0 && dc == 0) ownLake = true;
                            float s = reg.surface[k];
                            if (Float.isNaN(level) || s > level) level = s;
                        }
                    }
                }
                if (Float.isNaN(level)) continue;
                // A lake wins over a river channel that runs into it, so no channel is left cut into the lake.
                if (waterY[r][c] != NONE && !ownLake) continue;
                int lakeTop = metersToY.applyAsInt((int) Math.floor(Math.max(0f, level))) - 1;
                int idx = r * W + c;
                int top = surfaceY[r][c] != NONE ? surfaceY[r][c] : ytop[idx];
                // Inside a lake pixel any low ground is lake bed; in the one-pixel rim only shallow
                // ground is flooded, otherwise the lake would hang as a water curtain down a slope.
                if (ytop[idx] < 60) continue;                 // never over the sea floor
                // Inside a lake pixel the ground is genuinely below the lake level (it is a filled basin), even
                // in a deep gorge. Only the surrounding rim is limited to shallow ground.
                if (lakeTop - top > (ownLake ? 120 : 2)) continue;
                if (ownLake) { ownLakeCol[idx] = true; lakeLvl[idx] = lakeTop; }
                // Deep inside a lake (all 8 surrounding pixels are lake too) the ground must be lake bed:
                // detail noise can leave ridges above the water, so cut those down. At the edge pixels the
                // shore is left to follow the real ground contour.
                if (ownLake && lakeNeighbours == 9 && top >= lakeTop && top - lakeTop <= 12) {
                    surfaceY[r][c] = (short) (lakeTop - 1);
                    top = lakeTop - 1;
                    any = true;
                }
                if (top <= lakeTop && (ownLake || lakeTop - top <= 2)) {
                    waterY[r][c] = (short) lakeTop;
                    if (surfaceY[r][c] == NONE) surfaceY[r][c] = (short) ytop[idx];
                    lakeCol[idx] = true;
                    any = true;
                }
            }
        }

        // Fill small dry specks inside lakes: ground that pokes just above the water but is boxed in by it.
        for (int iter = 0; iter < 3; iter++) {
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int idx = r * W + c;
                    if (!ownLakeCol[idx] || waterY[r][c] != NONE) continue;
                    int wn = 0;
                    if (r > 0     && waterY[r - 1][c] != NONE) wn++;
                    if (r < H - 1 && waterY[r + 1][c] != NONE) wn++;
                    if (c > 0     && waterY[r][c - 1] != NONE) wn++;
                    if (c < W - 1 && waterY[r][c + 1] != NONE) wn++;
                    if (wn < 3) continue;
                    int top = surfaceY[r][c] != NONE ? surfaceY[r][c] : ytop[idx];
                    if (ytop[idx] < 60 || top - lakeLvl[idx] > 3) continue;
                    surfaceY[r][c] = (short) (lakeLvl[idx] - 1);
                    waterY[r][c] = (short) lakeLvl[idx];
                    lakeCol[idx] = true;
                    any = true;
                }
            }
        }

        // Lake beds deepen gradually away from the shore (a bowl) instead of a flat one-block shelf with
        // random deeper spots. Ground that is already deeper than the bowl is left alone.
        final int FAR = 1 << 20;
        int[] dist = new int[n];
        for (int i = 0; i < n; i++) dist[i] = lakeCol[i] ? FAR : 0;
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int i = r * W + c;
                if (dist[i] == 0) continue;
                int d = dist[i];
                if (r > 0 && dist[i - W] + 1 < d) d = dist[i - W] + 1;
                if (c > 0 && dist[i - 1] + 1 < d) d = dist[i - 1] + 1;
                dist[i] = d;
            }
        }
        for (int r = H - 1; r >= 0; r--) {
            for (int c = W - 1; c >= 0; c--) {
                int i = r * W + c;
                if (dist[i] == 0) continue;
                int d = dist[i];
                if (r < H - 1 && dist[i + W] + 1 < d) d = dist[i + W] + 1;
                if (c < W - 1 && dist[i + 1] + 1 < d) d = dist[i + 1] + 1;
                dist[i] = d;
            }
        }
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int i = r * W + c;
                if (!lakeCol[i] || waterY[r][c] == NONE || dist[i] >= FAR) continue;
                int depth = Math.min(5, 1 + (dist[i] - 1) / 2);
                int bowl = waterY[r][c] - depth;
                if (surfaceY[r][c] == NONE || surfaceY[r][c] > bowl) surfaceY[r][c] = (short) bowl;
            }
        }

        // ---- lake embankments: ground next to lake water is raised to the water level and
        // slopes down 1:1, so water is always held in by terrain.
        final int NO_NEED = -1000000;   // sentinel; far from overflow when 1 is subtracted
        int[] need = new int[n];
        Arrays.fill(need, NO_NEED);
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                if (!lakeCol[idx]) continue;
                int want = waterY[r][c] + 1;   // shore ground just above the water surface
                if (r > 0)     { int j = idx - W; if (waterY[r - 1][c] == NONE && need[j] < want) need[j] = want; }
                if (r < H - 1) { int j = idx + W; if (waterY[r + 1][c] == NONE && need[j] < want) need[j] = want; }
                if (c > 0)     { int j = idx - 1; if (waterY[r][c - 1] == NONE && need[j] < want) need[j] = want; }
                if (c < W - 1) { int j = idx + 1; if (waterY[r][c + 1] == NONE && need[j] < want) need[j] = want; }
            }
        }
        for (int pass = 0; pass < 5; pass++) {
            // The first pass keeps the level (a 2-wide flat shore), later passes slope down 1:1.
            final int dec = pass == 0 ? 0 : 1;
            int[] next = need.clone();
            for (int r = 0; r < H; r++) {
                for (int c = 0; c < W; c++) {
                    int idx = r * W + c;
                    if (waterY[r][c] != NONE) continue;
                    int best = next[idx];
                    if (r > 0     && need[idx - W] > NO_NEED && need[idx - W] - dec > best) best = need[idx - W] - dec;
                    if (r < H - 1 && need[idx + W] > NO_NEED && need[idx + W] - dec > best) best = need[idx + W] - dec;
                    if (c > 0     && need[idx - 1] > NO_NEED && need[idx - 1] - dec > best) best = need[idx - 1] - dec;
                    if (c < W - 1 && need[idx + 1] > NO_NEED && need[idx + 1] - dec > best) best = need[idx + 1] - dec;
                    next[idx] = best;
                }
            }
            need = next;
        }
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                if (need[idx] == NO_NEED || waterY[r][c] != NONE) continue;
                int top = surfaceY[r][c] != NONE ? surfaceY[r][c] : ytop[idx];
                if (top < need[idx]) { surfaceY[r][c] = (short) need[idx]; any = true; }
            }
        }

        if (!any) return null;

        // Biomes are left untouched so banks and beds keep the surface of the biome they are in.
        boolean[][] isLake = new boolean[H][W];
        for (int r = 0; r < H; r++) for (int c = 0; c < W; c++) isLake[r][c] = lakeCol[r * W + c];
        return new Result(surfaceY, waterY, isLake);
    }
}
