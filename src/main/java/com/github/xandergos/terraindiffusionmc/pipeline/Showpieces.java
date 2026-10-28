package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Showpiece landforms (biomes plan v1.4 round D), carved in {@link WorldPipeline#get} right after the volcanoes, so the
 * climate, rivers, lakes and biomes follow them. Sites come from the coarse map, one candidate of each kind per landform
 * cell ({@link Landforms#CELL} coarse pixels, ~123 km):
 * <ul>
 *   <li><b>Geothermal basins</b> (Yellowstone): a wide, shallow basin among cool, forested mountains has its floor
 *   levelled (the old caldera), and patches of it become geothermal fields ({@link #TAG_GEOTHERMAL}): white sinter
 *   flats where {@code world.Geothermal} lays the hot spring pools with their yellow and orange rims.</li>
 *   <li><b>Mirage isles</b>: in warm deep ocean far from any land, a cluster of 2-5 flat-topped islands standing out of
 *   the sea on sheer cliffs. Their shape is analytic ({@link Mirage#height}), so the tile can draw the cliffs exactly at
 *   block resolution; here they are drawn at native resolution for the hydrology and climate.</li>
 * </ul>
 * Every edit is pointwise in position and height and fades to nothing at its edge, so tiles agree.
 */
public final class Showpieces {

    /** Surface tags (shared numbering with {@link OceanFeatures} and {@link Landforms}). */
    public static final byte TAG_GEOTHERMAL = 27, TAG_MIRAGE = 28;

    /** Chance a landform cell with a suitable basin gets a geothermal basin, and one with warm open ocean mirage isles. */
    static final double GEO_CHANCE = 0.35, MIRAGE_CHANCE = 0.3;

    /** Coarse map and model access (from the pipeline). */
    public interface Source {
        /** Coarse elevation (m) and temperature (deg C) over coarse pixels [ci0, ci1) x [cj0, cj1): {elev, temp}. */
        float[][] coarse(int ci0, int cj0, int ci1, int cj1);

        /** The model's elevation (metres, no ocean features) over native pixels [i1, i2) x [j1, j2), row-major. */
        float[] modelElevation(int i1, int j1, int i2, int j2);
    }

    /** A geothermal basin: centre and radius (native pixels), floor level (m). */
    public static final class Basin {
        public final double cz, cx, radius, floor;
        final long hash;

        Basin(double cz, double cx, double radius, double floor, long hash) {
            this.cz = cz; this.cx = cx; this.radius = radius; this.floor = floor; this.hash = hash;
        }
    }

    /** One mirage isle: centre and radius (native pixels), the height of its top (m). */
    public static final class Mirage {
        public final double cz, cx, radius, top;
        final long hash;

        Mirage(double cz, double cx, double radius, double top, long hash) {
            this.cz = cz; this.cx = cx; this.radius = radius; this.top = top; this.hash = hash;
        }

        /**
         * The isle's ground (m) at native point (z, x), or NaN outside it. The outline wanders (a warped, lobed
         * circle); the top is a gently rolling table a little lower toward the edge, which drops sheer into the sea.
         */
        public double height(double z, double x) {
            double o = outline(z, x);
            if (o >= 1.0) return Double.NaN;
            double hills = 9.0 * HILLS.GetNoise((float) x, (float) z);
            return top + hills - 10.0 * smooth(0.55, 1.0, o);
        }

        /** Where (z, x) lies against the outline: under 1 inside, 1 on the cliff, more outside (about distance / radius). */
        public double outline(double z, double x) {
            double dz = z - cz, dx = x - cx;
            if (Math.abs(dz) > radius * REACH || Math.abs(dx) > radius * REACH) return 9.0;
            double d = Math.sqrt(dz * dz + dx * dx) / radius;
            // The outline wanders only near the edge (noise inside punched sheer-walled holes into the table).
            double n = OUTLINE.GetNoise((float) (x * 30.0 / radius + (hash & 1023)), (float) (z * 30.0 / radius - (hash >>> 20 & 1023)));
            return d + 0.28 * n * smooth(0.55, 0.95, d);
        }

        /** Sea floor (m) the isle's foot keeps around it at outline value o (1..REACH), or NaN past its reach. */
        public static double foot(double o) {
            if (o >= REACH) return Double.NaN;
            return -60.0 - 6000.0 * smooth(1.0, REACH, o);
        }

        /** How far (in radii, outline value) an isle's foot reaches. */
        public static final double REACH = 1.7;
    }

    private static final FastNoiseLite OUTLINE = fbm(9201, 1f / 22f, 2);
    private static final FastNoiseLite HILLS = fbm(9202, 1f / 6f, 2);
    private static final FastNoiseLite BASIN_EDGE = fbm(9203, 1f / 25f, 2);
    private static final FastNoiseLite BASIN_HILLS = fbm(9204, 1f / 8f, 2);
    private static final FastNoiseLite FIELDS = fbm(9205, 1f / 9f, 2);

    private final long seed;
    private final Source source;
    private final Map<Long, Cell> cells = new ConcurrentHashMap<>();

    private record Cell(List<Basin> basins, List<Mirage> isles) {}

    private static final Cell EMPTY = new Cell(List.of(), List.of());

    public Showpieces(long seed, Source source) {
        this.seed = seed;
        this.source = source;
    }

    private static FastNoiseLite fbm(int s, float freq, int oct) {
        FastNoiseLite n = new FastNoiseLite(s);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(oct);
        return n;
    }

    // =========================================================================
    // Applying
    // =========================================================================

    /** Carves the showpieces into {@code elev} (native window at (i1, j1), H x W) in place and writes their tags. */
    public void apply(float[] elev, int i1, int j1, int H, int W, byte[] tags) {
        List<Basin> basins = new ArrayList<>();
        List<Mirage> isles = new ArrayList<>();
        near(i1, j1, i1 + H, j1 + W, basins, isles);
        if (basins.isEmpty() && isles.isEmpty()) return;
        for (int r = 0; r < H; r++) {
            double z = i1 + r + 0.5;
            for (int c = 0; c < W; c++) {
                double x = j1 + c + 0.5;
                int k = r * W + c;
                float e = elev[k];
                byte tag = 0;
                for (Basin b : basins) {
                    double dz = z - b.cz, dx = x - b.cx;
                    if (Math.abs(dz) > b.radius * 1.3 || Math.abs(dx) > b.radius * 1.3) continue;
                    double u = Math.sqrt(dz * dz + dx * dx) / b.radius + 0.12 * BASIN_EDGE.GetNoise((float) x, (float) z);
                    double w = 1 - smooth(0.6, 1.0, u);
                    if (w <= 0) continue;
                    // A levelled floor with low swells: the old caldera.
                    double target = b.floor + 12 * BASIN_HILLS.GetNoise((float) x, (float) z);
                    e = (float) (e + (target - e) * 0.9 * w);
                    if (u < 0.72 && FIELDS.GetNoise((float) (x + (b.hash & 4095)), (float) z) > 0.05) tag = TAG_GEOTHERMAL;
                }
                for (Mirage m : isles) {
                    double o = m.outline(z, x);
                    if (o < 1.0) {
                        double h = m.height(z, x);
                        if (h > e) e = (float) h;
                        tag = TAG_MIRAGE;
                    } else if (o < Mirage.REACH) {
                        // A submarine foot around the cliffs (keeps the upsampling from ringing far out to sea).
                        e = (float) Math.max(e, Mirage.foot(o));
                    }
                }
                elev[k] = e;
                if (tags != null && tag != 0) tags[k] = tag;
            }
        }
    }

    /** The mirage isles that may reach the native window [i1, i2) x [j1, j2) (for the tile's exact cliffs). */
    public List<Mirage> isles(int i1, int j1, int i2, int j2) {
        List<Mirage> out = new ArrayList<>();
        near(i1, j1, i2, j2, new ArrayList<>(), out);
        return out;
    }

    private void near(int i1, int j1, int i2, int j2, List<Basin> basins, List<Mirage> isles) {
        int span = Landforms.cellPx(), pad = 2 * coarsePx();
        for (int gi = Math.floorDiv(i1 - pad, span); gi <= Math.floorDiv(i2 + pad, span); gi++) {
            for (int gj = Math.floorDiv(j1 - pad, span); gj <= Math.floorDiv(j2 + pad, span); gj++) {
                Cell c = cell(gi, gj);
                for (Basin b : c.basins) {
                    double r = b.radius * 1.3;
                    if (b.cz + r >= i1 && b.cz - r <= i2 && b.cx + r >= j1 && b.cx - r <= j2) basins.add(b);
                }
                for (Mirage m : c.isles) {
                    double r = m.radius * (Mirage.REACH + 0.4);
                    if (m.cz + r >= i1 && m.cz - r <= i2 && m.cx + r >= j1 && m.cx - r <= j2) isles.add(m);
                }
            }
        }
    }

    // =========================================================================
    // Sites
    // =========================================================================

    private static int coarsePx() {
        return 32 * WorldPipelineModelConfig.latentCompression();
    }

    private Cell cell(int gi, int gj) {
        long key = Landforms.mix(((long) gi << 32) ^ (gj & 0xFFFFFFFFL));
        Cell c = cells.get(key);
        if (c != null) return c;
        c = buildCell(gi, gj);
        if (cells.size() > 4096) cells.clear();
        cells.put(key, c);
        return c;
    }

    private Cell buildCell(int gi, int gj) {
        int CELL = Landforms.CELL, S = coarsePx();
        int m = 3, n = CELL + 2 * m;
        int ci0 = gi * CELL - m, cj0 = gj * CELL - m;
        long ch = Landforms.mix(seed ^ ((long) gi * 0x632BE59BD9B4E019L) ^ ((long) gj * 0x85EBCA77C2B2AE63L) ^ 0x5B0CL);
        boolean wantGeo = Landforms.unit(Landforms.mix(ch + 1)) < GEO_CHANCE;
        boolean wantMirage = Landforms.unit(Landforms.mix(ch + 2)) < MIRAGE_CHANCE;
        if (!wantGeo && !wantMirage) return EMPTY;
        float[][] co = source.coarse(ci0, cj0, ci0 + n, cj0 + n);
        float[] ce = co[0], ct = co[1];
        List<Basin> basins = new ArrayList<>();
        List<Mirage> isles = new ArrayList<>();
        if (wantGeo) {
            Basin b = basin(ce, ct, n, m, ci0, cj0, Landforms.mix(ch + 3));
            if (b != null) basins.add(b);
        }
        if (wantMirage) mirages(ce, ct, n, m, ci0, cj0, Landforms.mix(ch + 4), isles);
        if (basins.isEmpty() && isles.isEmpty()) return EMPTY;
        return new Cell(Collections.unmodifiableList(basins), Collections.unmodifiableList(isles));
    }

    /**
     * A geothermal basin on the cell's best basin site: coarse land 1200-3200 m up, cool (-5..10 deg C), lower than the
     * ground around it (a basin among mountains, not a summit or a plain), checked on the model's ground (a floor over
     * 600 m, above the sea).
     */
    private Basin basin(float[] ce, float[] ct, int n, int m, int ci0, int cj0, long h) {
        int S = coarsePx();
        double best = 40;   // the basin must lie at least 40 m below the mean of the ground around it
        int bi = -1, bj = -1;
        for (int r = m; r < n - m; r++) {
            for (int c = m; c < n - m; c++) {
                float e = ce[r * n + c], t = ct[r * n + c];
                if (e < 1200f || e > 3200f || t < -5f || t > 10f) continue;
                double ring = 0, hi = e;
                for (int dr = -2; dr <= 2; dr++) {
                    for (int dc = -2; dc <= 2; dc++) {
                        if (Math.max(Math.abs(dr), Math.abs(dc)) != 2) continue;
                        float q = ce[(r + dr) * n + c + dc];
                        ring += q;
                        hi = Math.max(hi, q);
                    }
                }
                ring /= 16;
                // Lower than its surroundings, with some of them high (mountains around), ties broken by the hash.
                double score = ring - e + (hi - e > 300 ? 50 : 0) + 5 * Landforms.unit(Landforms.mix(h + r * 131L + c));
                if (score > best) { best = score; bi = r; bj = c; }
            }
        }
        if (bi < 0) return null;
        double cz = (ci0 + bi + 0.5) * S + (Landforms.unit(Landforms.mix(h + 7)) - 0.5) * S * 0.5;
        double cx = (cj0 + bj + 0.5) * S + (Landforms.unit(Landforms.mix(h + 8)) - 0.5) * S * 0.5;
        double R = 45 + 35 * Landforms.unit(Landforms.mix(h + 9));
        int half = (int) Math.ceil(R * 0.5);
        int i1 = (int) Math.floor(cz) - half, j1 = (int) Math.floor(cx) - half;
        float[] me = source.modelElevation(i1, j1, i1 + 2 * half, j1 + 2 * half);
        double sum = 0;
        int cnt = 0;
        for (int k = 0; k < me.length; k++) {
            int r = k / (2 * half), c = k % (2 * half);
            if (Math.hypot(r - half, c - half) > half) continue;
            sum += me[k];
            cnt++;
        }
        if (cnt == 0) return null;
        double floor = sum / cnt;
        if (floor < 600) return null;
        return new Basin(cz, cx, R, floor, h);
    }

    /**
     * Mirage isles around the cell's best open-ocean site: coarse sea deeper than 1500 m everywhere within 2 coarse
     * pixels (~15 km of open water), warmer than 10 deg C. 2-5 isles within ~4 km of it, 0.5-1.4 km across... see
     * {@link Mirage}; 40-100 m tall.
     */
    private void mirages(float[] ce, float[] ct, int n, int m, int ci0, int cj0, long h, List<Mirage> out) {
        int S = coarsePx();
        List<int[]> cands = new ArrayList<>();
        for (int r = m; r < n - m; r++) {
            for (int c = m; c < n - m; c++) {
                if (ct[r * n + c] < 10f) continue;
                boolean open = true;
                for (int dr = -2; dr <= 2 && open; dr++)
                    for (int dc = -2; dc <= 2 && open; dc++) if (ce[(r + dr) * n + c + dc] > -1500f) open = false;
                if (open) cands.add(new int[]{r, c});
            }
        }
        if (cands.isEmpty()) return;
        int[] pick = cands.get((int) (Landforms.unit(Landforms.mix(h + 1)) * cands.size()));
        double cz = (ci0 + pick[0] + 0.5) * S, cx = (cj0 + pick[1] + 0.5) * S;
        int count = 2 + (int) (4 * Landforms.unit(Landforms.mix(h + 2)));
        for (int q = 0; q < count; q++) {
            long hq = Landforms.mix(h + 100 + q);
            double R = 18 + 28 * Landforms.unit(Landforms.mix(hq + 1));
            double a = 2 * Math.PI * Landforms.unit(Landforms.mix(hq + 2)), d = q == 0 ? 0 : 50 + 90 * Landforms.unit(Landforms.mix(hq + 3));
            double z = cz + Math.sin(a) * d, x = cx + Math.cos(a) * d;
            boolean clash = false;
            for (Mirage o : out) if (Math.hypot(o.cz - z, o.cx - x) < (o.radius + R) * 1.1) { clash = true; break; }
            if (clash) continue;
            double top = 40 + 60 * Landforms.unit(Landforms.mix(hq + 4));
            out.add(new Mirage(z, x, R, top, hq));
        }
    }

    // =========================================================================
    // Markers
    // =========================================================================

    /** Geothermal basin and mirage isle markers in the native window (cells built only for small windows). */
    public List<OceanFeatures.Landmark> landmarks(int i1, int j1, int i2, int j2) {
        int span = Landforms.cellPx();
        boolean build = (long) (i2 - i1) * (j2 - j1) <= 4L * span * span;
        List<OceanFeatures.Landmark> out = new ArrayList<>();
        for (int gi = Math.floorDiv(i1, span) - 1; gi <= Math.floorDiv(i2, span) + 1; gi++) {
            for (int gj = Math.floorDiv(j1, span) - 1; gj <= Math.floorDiv(j2, span) + 1; gj++) {
                Cell c = build ? cell(gi, gj) : cells.get(Landforms.mix(((long) gi << 32) ^ (gj & 0xFFFFFFFFL)));
                if (c != null) markers(c, i1, j1, i2, j2, out);
            }
        }
        return out;
    }

    /** Markers of cell (gi, gj)'s showpieces (building it if needed), anywhere. For /orogeny-find. */
    public List<OceanFeatures.Landmark> cellMarkers(int gi, int gj) {
        List<OceanFeatures.Landmark> out = new ArrayList<>();
        markers(cell(gi, gj), Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2, out);
        return out;
    }

    private static void markers(Cell c, int i1, int j1, int i2, int j2, List<OceanFeatures.Landmark> out) {
        for (Basin b : c.basins) {
            if (b.cz >= i1 && b.cz < i2 && b.cx >= j1 && b.cx < j2) {
                out.add(new OceanFeatures.Landmark("geothermal_basin", b.cz, b.cx, (float) b.floor));
            }
        }
        if (!c.isles.isEmpty()) {
            Mirage m = c.isles.get(0);
            if (m.cz >= i1 && m.cz < i2 && m.cx >= j1 && m.cx < j2) {
                out.add(new OceanFeatures.Landmark("mirage_isles", m.cz, m.cx, c.isles.size()));
            }
        }
    }

    static double smooth(double e0, double e1, double v) {
        double t = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }
}
