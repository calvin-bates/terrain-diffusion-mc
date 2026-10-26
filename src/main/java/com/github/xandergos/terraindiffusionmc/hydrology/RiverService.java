package com.github.xandergos.terraindiffusionmc.hydrology;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes and caches river / lake data for fixed, aligned regions of native pixels.
 *
 * <p>Region (ri, rj) owns the pixels {@code [ri*size, (ri+1)*size) x [rj*size, (rj+1)*size)}. The
 * hydrology for it is computed on that area plus a margin on every side. Because
 * {@link RiverMap.Params#flowSteps} never exceeds the margin, a pixel gets the same answer whichever
 * region computes it, so rivers continue across region borders.
 *
 * <p>Pure Java (no Minecraft types). Not thread-safe by itself; all access is synchronized.
 */
public final class RiverService {
    /** Supplies {@code {elevation, climate}} for a native pixel window (rows i1..i2, columns j1..j2). */
    public interface Source {
        float[][] get(int i1, int j1, int i2, int j2) throws Exception;
    }

    /** River data for one region; arrays cover the region plus its margin. */
    public static final class Region {
        public final int i0, j0, height, width;
        public final byte[] cls;
        public final int[] downstream;
        public final float[] surface;
        /** Terrain elevation in metres before filling, or null. */
        public final float[] elev;
        /** Mean annual temperature (deg C) per pixel, or null if the source gave no climate. */
        public final float[] temp;
        /** Annual precipitation (mm) per pixel, or null if the source gave no climate. */
        public final float[] precip;
        /** Continuous channel size per pixel ({@link RiverMap.Result#size}), or null. */
        public final float[] size;
        /** The region's index and side (its own square is [ri*size, (ri+1)*size) on each axis). */
        public final int ri, rj, regionSize;
        /**
         * Lakes, as this region sees them: per pixel the lake (4-connected LAKE pixels) it belongs to, or -1; per lake,
         * whether it lies wholly inside the window (not touching its edge, so its level is right) and its anchor (its
         * deepest pixel, global native coordinates). A lake belongs to the region whose own square holds its anchor;
         * see {@code RiverCarver.lakeRegion}.
         */
        public final int[] lakeComp;
        public final boolean[] lakeComplete;
        public final int[] lakeAnchorI, lakeAnchorJ;

        Region(int i0, int j0, int height, int width, RiverMap.Result res, float[] climate, int ri, int rj, int regionSize) {
            this.i0 = i0; this.j0 = j0; this.height = height; this.width = width;
            this.ri = ri; this.rj = rj; this.regionSize = regionSize;
            this.cls = res.cls; this.downstream = res.downstream; this.surface = res.surface; this.elev = res.elev;
            this.size = res.size;
            this.temp = climate == null || climate.length < height * width ? null
                    : java.util.Arrays.copyOf(climate, height * width);   // channel 0 of the climate is temperature
            this.precip = climate == null || climate.length < 3 * height * width ? null
                    : java.util.Arrays.copyOfRange(climate, 2 * height * width, 3 * height * width);
            // Lake components.
            int n = height * width;
            lakeComp = new int[n];
            java.util.Arrays.fill(lakeComp, -1);
            java.util.List<int[]> info = new java.util.ArrayList<>();   // {complete, anchor index}
            int[] stack = new int[n];
            for (int s = 0; s < n; s++) {
                if (lakeComp[s] >= 0 || cls[s] != RiverMap.LAKE) continue;
                int id = info.size(), sp = 0, anchor = s;
                boolean complete = true;
                lakeComp[s] = id;
                stack[sp++] = s;
                while (sp > 0) {
                    int k = stack[--sp], r = k / width, c = k % width;
                    if (r <= 1 || c <= 1 || r >= height - 2 || c >= width - 2) complete = false;
                    float ek = elev != null ? elev[k] : surface[k], ea = elev != null ? elev[anchor] : surface[anchor];
                    if (ek < ea || (ek == ea && k < anchor)) anchor = k;
                    if (r > 0 && lakeComp[k - width] < 0 && cls[k - width] == RiverMap.LAKE) { lakeComp[k - width] = id; stack[sp++] = k - width; }
                    if (r < height - 1 && lakeComp[k + width] < 0 && cls[k + width] == RiverMap.LAKE) { lakeComp[k + width] = id; stack[sp++] = k + width; }
                    if (c > 0 && lakeComp[k - 1] < 0 && cls[k - 1] == RiverMap.LAKE) { lakeComp[k - 1] = id; stack[sp++] = k - 1; }
                    if (c < width - 1 && lakeComp[k + 1] < 0 && cls[k + 1] == RiverMap.LAKE) { lakeComp[k + 1] = id; stack[sp++] = k + 1; }
                }
                info.add(new int[]{complete ? 1 : 0, anchor});
            }
            lakeComplete = new boolean[info.size()];
            lakeAnchorI = new int[info.size()];
            lakeAnchorJ = new int[info.size()];
            for (int q = 0; q < info.size(); q++) {
                lakeComplete[q] = info.get(q)[0] == 1;
                lakeAnchorI[q] = i0 + info.get(q)[1] / width;
                lakeAnchorJ[q] = j0 + info.get(q)[1] % width;
            }
        }

        /** Whether this region's own square holds native pixel (pi, pj). */
        public boolean owns(int pi, int pj) {
            return Math.floorDiv(pi, regionSize) == ri && Math.floorDiv(pj, regionSize) == rj;
        }

        /** Whether this region has the right level for its lake at index k: whole in its window, anchored in its square. */
        public boolean ownsLakeAt(int k) {
            int q = lakeComp[k];
            return q >= 0 && lakeComplete[q] && owns(lakeAnchorI[q], lakeAnchorJ[q]);
        }

        private volatile float[] seaDist;

        /**
         * Per pixel, the distance (native pixels) to the nearest sea pixel (ground at or below 0 m), capped at
         * {@link #SEA_DIST_CAP}; computed on first use. Within the cap it depends only on ground the window holds (the
         * margin is wider), so regions agree where they overlap.
         */
        public float[] seaDistance() {
            float[] d = seaDist;
            if (d != null) return d;
            synchronized (this) {
                if (seaDist != null) return seaDist;
                int n = height * width;
                d = new float[n];
                final float INF = SEA_DIST_CAP * 4f;
                for (int k = 0; k < n; k++) d[k] = elev == null || elev[k] <= 0f ? 0f : INF;
                // Two-pass chamfer (1, sqrt 2): within a few percent of the true distance.
                final float D1 = 1f, D2 = 1.41421356f;
                for (int r = 0; r < height; r++) {
                    for (int c = 0; c < width; c++) {
                        int k = r * width + c;
                        float v = d[k];
                        if (v == 0f) continue;
                        if (c > 0) v = Math.min(v, d[k - 1] + D1);
                        if (r > 0) {
                            v = Math.min(v, d[k - width] + D1);
                            if (c > 0) v = Math.min(v, d[k - width - 1] + D2);
                            if (c < width - 1) v = Math.min(v, d[k - width + 1] + D2);
                        }
                        d[k] = v;
                    }
                }
                for (int r = height - 1; r >= 0; r--) {
                    for (int c = width - 1; c >= 0; c--) {
                        int k = r * width + c;
                        float v = d[k];
                        if (v == 0f) continue;
                        if (c < width - 1) v = Math.min(v, d[k + 1] + D1);
                        if (r < height - 1) {
                            v = Math.min(v, d[k + width] + D1);
                            if (c < width - 1) v = Math.min(v, d[k + width + 1] + D2);
                            if (c > 0) v = Math.min(v, d[k + width - 1] + D2);
                        }
                        d[k] = v;
                    }
                }
                for (int k = 0; k < n; k++) d[k] = Math.min(d[k], SEA_DIST_CAP);
                seaDist = d;
                return d;
            }
        }

        /** Index of native pixel (pi, pj), or -1 if it is outside this region's window. */
        public int index(int pi, int pj) {
            int r = pi - i0, c = pj - j0;
            if (r < 0 || c < 0 || r >= height || c >= width) return -1;
            return r * width + c;
        }
    }

    private static final int MAX_REGIONS = 12;
    /** Farthest (native pixels) {@link Region#seaDistance} measures. */
    public static final float SEA_DIST_CAP = 48f;

    private final int size;
    private final int margin;
    private final RiverMap.Params params;
    private final Map<Long, Region> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Region> eldest) {
            return size() > MAX_REGIONS;
        }
    };

    public RiverService(int regionSize, int margin, RiverMap.Params params) {
        if (margin < params.flowSteps + 8) {
            throw new IllegalArgumentException("margin " + margin + " must exceed flowSteps " + params.flowSteps);
        }
        this.size = regionSize;
        this.margin = margin;
        this.params = params;
    }

    /**
     * How many pixels beyond a region's own area its data is still exact (margin minus the flow
     * steps that must lie inside the window).
     */
    public int trust() {
        return margin - params.flowSteps;
    }

    public int regionSize() {
        return size;
    }

    public synchronized void clear() {
        cache.clear();
    }

    /** Returns the region containing native pixel (pi, pj), computing it if needed. */
    public Region regionOfPixel(int pi, int pj, Source source) {
        return region(Math.floorDiv(pi, size), Math.floorDiv(pj, size), source);
    }

    public synchronized boolean isCached(int ri, int rj) {
        return cache.containsKey(((long) ri << 32) ^ (rj & 0xFFFFFFFFL));
    }

    public synchronized Region region(int ri, int rj, Source source) {
        long key = ((long) ri << 32) ^ (rj & 0xFFFFFFFFL);
        Region cached = cache.get(key);
        if (cached != null) return cached;

        int i0 = ri * size - margin;
        int j0 = rj * size - margin;
        int span = size + 2 * margin;
        try {
            float[][] data = source.get(i0, j0, i0 + span, j0 + span);
            RiverMap.Result res = RiverMap.computeFull(data[0], data[1], span, span, i0, j0, params);
            Region region = new Region(i0, j0, span, span, res, data[1], ri, rj, size);
            cache.put(key, region);
            return region;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("River region " + ri + "," + rj + " failed", e);
        }
    }
}
