package com.github.xandergos.terraindiffusionmc.hydrology;

import java.util.PriorityQueue;

/**
 * Drainage network on a regular grid: depression filling (priority-flood), D8 flow directions
 * and runoff-weighted flow accumulation.
 *
 * <p>Pure Java with no Minecraft dependencies so it can be unit-tested on synthetic terrain.
 *
 * <p>Outlets: every cell at or below {@code seaLevel} (the ocean). Grid edges are NOT treated as
 * outlets by default (water leaving the window is treated as flowing to an unknown outlet, see
 * {@link #edgesAreOutlets}); callers should compute on a window with a margin and only use the
 * central part, so that edge effects do not matter.
 */
public final class DrainageNetwork {
    /** Cell indices of the 8 neighbours. */
    private static final int[] DR = {-1, -1, -1, 0, 0, 1, 1, 1};
    private static final int[] DC = {-1, 0, 1, -1, 1, -1, 0, 1};

    public final int height;
    public final int width;
    /** Downstream cell index for every cell, or -1 for outlets (ocean cells and window-edge exits). */
    public final int[] downstream;
    /** Runoff accumulated upstream of (and including) every cell, in the units of the runoff input. */
    public final float[] discharge;
    /** Elevation after depression filling (lakes become flat plus a tiny epsilon slope). */
    public final float[] filled;
    /** True for cells that were raised by depression filling (lake / flat areas). */
    public final boolean[] inDepression;

    private DrainageNetwork(int h, int w, int[] downstream, float[] discharge, float[] filled, boolean[] dep) {
        this.height = h;
        this.width = w;
        this.downstream = downstream;
        this.discharge = discharge;
        this.filled = filled;
        this.inDepression = dep;
    }

    /**
     * @param elev        elevation in metres, row-major {@code height*width}
     * @param runoff      runoff produced by each cell (for example precipitation times cell area)
     * @param seaLevel    cells with elevation at or below this value are ocean outlets
     * @param edgesAreOutlets if true, water reaching the window border leaves the grid
     */
    public static DrainageNetwork compute(float[] elev, float[] runoff, int height, int width,
                                          float seaLevel, boolean edgesAreOutlets) {
        int n = height * width;
        float[] filled = new float[n];
        int[] downstream = new int[n];
        boolean[] closed = new boolean[n];
        boolean[] dep = new boolean[n];
        java.util.Arrays.fill(downstream, -1);

        PriorityQueue<long[]> open = new PriorityQueue<>((a, b) -> {
            int c = Float.compare(Float.intBitsToFloat((int) a[0]), Float.intBitsToFloat((int) b[0]));
            return c != 0 ? c : Long.compare(a[2], b[2]);
        });
        long order = 0;

        for (int i = 0; i < n; i++) {
            int r = i / width, c = i % width;
            boolean ocean = elev[i] <= seaLevel;
            boolean edge = edgesAreOutlets && (r == 0 || c == 0 || r == height - 1 || c == width - 1);
            if (ocean || edge) {
                closed[i] = true;
                filled[i] = elev[i];
                open.add(new long[]{Float.floatToIntBits(elev[i]), i, order++});
            }
        }
        // If nothing is an outlet, seed from the lowest edge cell so the algorithm still terminates.
        if (open.isEmpty()) {
            int best = 0;
            for (int i = 1; i < n; i++) if (elev[i] < elev[best]) best = i;
            closed[best] = true;
            filled[best] = elev[best];
            open.add(new long[]{Float.floatToIntBits(elev[best]), best, order++});
        }

        final float eps = 1e-3f;
        while (!open.isEmpty()) {
            long[] top = open.poll();
            int i = (int) top[1];
            int r = i / width, c = i % width;
            float fi = filled[i];
            for (int k = 0; k < 8; k++) {
                int rr = r + DR[k], cc = c + DC[k];
                if (rr < 0 || cc < 0 || rr >= height || cc >= width) continue;
                int j = rr * width + cc;
                if (closed[j]) continue;
                closed[j] = true;
                float fj = elev[j];
                if (fj <= fi) {
                    fj = fi + eps;
                    dep[j] = true;
                }
                filled[j] = fj;
                downstream[j] = i;
                open.add(new long[]{Float.floatToIntBits(fj), j, order++});
            }
        }

        // Accumulate from high to low along the (acyclic) downstream tree.
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Float.compare(filled[b], filled[a]));
        float[] discharge = new float[n];
        for (int i = 0; i < n; i++) discharge[i] = Math.max(0f, runoff[i]);
        for (int t = 0; t < n; t++) {
            int i = idx[t];
            int d = downstream[i];
            if (d >= 0) discharge[d] += discharge[i];
        }
        return new DrainageNetwork(height, width, downstream, discharge, filled, dep);
    }
}
