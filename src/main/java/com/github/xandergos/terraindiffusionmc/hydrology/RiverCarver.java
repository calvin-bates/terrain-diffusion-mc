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

    private static final short[] FROZEN_BIOMES = {3, 16, 32, 33, 48, 116};

    /** Distance (blocks) from the channel edge over which banks are shaped. */
    private static final float BANK = 4f;   // how far a stream reshapes the ground beside it (was 8: diagonal steps reached too far)
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
        if (!LOGGED_BUILD) { LOGGED_BUILD = true; LOG.info("[Terra] lake code build: flood-to-shore v7 (a59f687+)"); }
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

        // ---- finalise river channels (pass 1), then blend the banks into them (pass 2)
        final int NOWATER = Integer.MIN_VALUE;
        int[] chanWater = new int[n];
        Arrays.fill(chanWater, NOWATER);
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                float sc = score[idx];
                if (sc == Float.POSITIVE_INFINITY || sc > 0f) continue;
                if (contains(NO_WATER_BIOMES, biomeIds[r][c])) continue;
                int top = ytop[idx];
                if (top < 60) continue;   // never put river water over the sea floor
                // Too steep for a stream to hold water: it would only show as scattered ribbons.
                if (steepAt(ytop, H, W, r, c)) continue;
                int ys = metersToY.applyAsInt((int) Math.floor(Math.max(0f, sA[idx])));
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
                // The hydrology surface is a coarse estimate; where it sits far below the real ground the water used to
                // sag with it and carve a wide ditch. Let it sag at most 2 blocks below the lowest nearby ground.
                waterTop = Math.max(waterTop, low - 1);
                int bed = waterTop - DEPTH[cA[idx]];
                surfaceY[r][c] = (short) Math.min(top, bed);
                waterY[r][c] = (short) waterTop;
                chanWater[idx] = waterTop;
                any = true;
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
                // Wobble the distance by up to +-1.5 blocks with smooth noise, so the edge of the reshaped bank is an irregular
                // line instead of a long straight diagonal parallel to the stream.
                sc = Math.max(0.01f, sc + (valueNoise(j1 + c, i1 + r) - 0.5f) * 3f);
                float t = Math.min(1f, sc / BANK);
                float w = 1f - t * t * (3f - 2f * t);
                // A 1-block flat shelf at the water level on each side, then the slope. Terrain is sampled every 4 blocks and
                // interpolated, so a narrower shelf is smeared upwards and the water placer has to cut vertical walls.
                float target = bwF[idx] + 1 + Math.max(0f, sc - 1f) * SLOPE;
                if (top > target) {   // low banks are left alone: no embankments along streams
                    int newTop = Math.round(top - w * (top - target));
                    if (newTop > top) newTop = top;
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
                if (waterY[r][c] != NONE && !ownLake) continue;
                int lakeTop = metersToY.applyAsInt((int) Math.floor(Math.max(0f, level))) - 1;
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
                if (top <= lakeTop && (ownLake || lakeTop - top <= 2)) {
                    waterY[r][c] = (short) lakeTop;
                    if (surfaceY[r][c] == NONE) surfaceY[r][c] = (short) ytop[idx];
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
                    if (wn < 2) continue;   // ridges and peninsulas: flooded from two sides
                    int top = surfaceY[r][c] != NONE ? surfaceY[r][c] : ytop[idx];
                    if (ytop[idx] < 60 || top - lakeLvl[idx] > 6) continue;
                    surfaceY[r][c] = (short) (lakeLvl[idx] - 1);
                    waterY[r][c] = (short) lakeLvl[idx];
                    lakeCol[idx] = true;
                    any = true;
                }
            }
        }

        // Flood to the mountain edge: the water spreads from the lake over every connected column whose ground is
        // at or below the water level, however far below, and stops only where the ground rises to the water
        // level (the real shore). It is capped in distance so a lake cannot run away down a long valley.
        {
            final int FLOOD_REACH = 48;
            int[] dist = new int[n];
            int[] srcLvl = new int[n];
            Arrays.fill(dist, -1);
            int[] fq = new int[n];
            int fh = 0, ft = 0, fSeeds = 0;
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
                    if (waterY[jr][jc] != NONE) continue;                    // river or other water: leave it
                    if (ytop[j] < 60 || ytop[j] > srcLvl[idx]) continue;     // sea floor, or the shore has been reached
                    dist[j] = d + 1; srcLvl[j] = srcLvl[idx];
                    waterY[jr][jc] = (short) srcLvl[idx];
                    if (surfaceY[jr][jc] == NONE) surfaceY[jr][jc] = (short) ytop[j];
                    lakeCol[j] = true;
                    any = true;
                    fq[ft++] = j;
                }
            }
            if (fSeeds > 0) LOG.info("[Terra] lake flood tile x={} z={}: {} lake columns, flooded {} more down to the shore", j1, i1, fSeeds, ft - fSeeds);
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
                    if (!isW && wet * 2 > tot && lvl != NONE && ground - lvl <= 2) {
                        flipToWater[idx] = true; flipLvl[idx] = lvl;
                    } else if (isW && wet * 2 < tot && waterY[r][c] - ground <= 2) {
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
            int[] depth = new int[n];
            for (int q = 0; q < bt2; q++) {
                int idx = bq2[q];
                int r = idx / W, c = idx % W;
                int level = waterY[r][c];
                int natural = Math.max(1, level - ytop[idx]);
                int minShore = Integer.MAX_VALUE;
                int[] nb = { r > 0 ? idx - W : -1, r < H - 1 ? idx + W : -1, c > 0 ? idx - 1 : -1, c < W - 1 ? idx + 1 : -1 };
                for (int j : nb) {
                    if (j >= 0 && lakeCol[j] && bd[j] == bd[idx] - 1 && depth[j] > 0) minShore = Math.min(minShore, depth[j]);
                }
                int d = natural;
                if (minShore != Integer.MAX_VALUE && minShore > natural) d = Math.min(minShore, natural + 6);
                depth[idx] = d;
                int bedY = level - d;
                int cur = surfaceY[r][c] != NONE ? surfaceY[r][c] : ytop[idx];
                if (bedY < cur) { surfaceY[r][c] = (short) bedY; any = true; }
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
