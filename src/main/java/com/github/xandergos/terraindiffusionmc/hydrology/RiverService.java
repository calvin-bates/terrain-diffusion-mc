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

        Region(int i0, int j0, int height, int width, RiverMap.Result res) {
            this.i0 = i0; this.j0 = j0; this.height = height; this.width = width;
            this.cls = res.cls; this.downstream = res.downstream; this.surface = res.surface; this.elev = res.elev;
        }

        /** Index of native pixel (pi, pj), or -1 if it is outside this region's window. */
        public int index(int pi, int pj) {
            int r = pi - i0, c = pj - j0;
            if (r < 0 || c < 0 || r >= height || c >= width) return -1;
            return r * width + c;
        }
    }

    private static final int MAX_REGIONS = 12;

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
            Region region = new Region(i0, j0, span, span, res);
            cache.put(key, region);
            return region;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("River region " + ri + "," + rj + " failed", e);
        }
    }
}
