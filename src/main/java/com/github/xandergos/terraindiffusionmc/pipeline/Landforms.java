package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The landform stage (biomes plan v1.4): rare landforms carved into the model's terrain, inside
 * {@link WorldPipeline#get} after the ocean features and before the climate, so everything downstream (lapse-rate
 * climate, rivers and lakes, biomes, coast shaping, the explorer) sees the new shapes.
 *
 * <p>Round A: volcanoes. Sites come from the coarse map (one candidate per {@link #CELL} coarse pixels for each kind),
 * are checked against the model's own terrain around them (suitability first: a stratovolcano only on a prominent
 * peak, a shield only on broad, gentle high ground), and then reshape what is there: every edit is pointwise in the
 * pixel's position and height, fades to nothing at its edge, and keeps the model's detail, so tiles always agree.
 * <ul>
 *   <li><b>Stratovolcano</b>: the peak becomes a concave cone (steep near the summit, flattening out), lifted a few
 *   hundred metres, with an uneven outline, radial gullies and a summit crater (or, for a quarter of them, a
 *   caldera that fills with a lake).</li>
 *   <li><b>Shield</b>: the high ground is smoothed and lifted into a broad, gentle dome with a summit caldera, pit
 *   craters along two rift zones, and many lava flows.</li>
 *   <li><b>Lava fields</b>: lobes of rough, slightly raised rock running down from the vent (and the rift zones), out
 *   past the foot of the volcano. Active volcanoes have a lava lake in the crater and live lava along their freshest
 *   flows.</li>
 *   <li><b>Volcanic islands</b>: the hotspot volcano at the head of each chain (the active one) gets lava flows down to
 *   its coast.</li>
 * </ul>
 * Surfaces come from tags (written like the ocean surface tags, read by {@code world.VolcanicSurfaces}) and from the
 * volcano and lava field biomes the tile assigns from them.
 */
public final class Landforms {

    /** Cell (coarse pixels, ~123 km) holding at most one stratovolcano and one shield. */
    static final int CELL = 16;
    /** Chance a cell with a suitable peak gets a stratovolcano, and one with suitable high plains a shield. */
    static final double STRATO_CHANCE = 0.45, SHIELD_CHANCE = 0.25;
    /** Share of volcanoes that are active (lava lake, live lava channels). */
    static final double ACTIVE_CHANCE = 0.4;
    /** An active shield's lava lake: a pit this fraction of its caldera's radius across, 30 m deeper. */
    static final double LAVA_PIT = 0.28;
    /** Share of stratovolcanoes with a caldera instead of a summit crater. */
    static final double CALDERA_CHANCE = 0.25;

    /** Surface tags (shared with {@link OceanFeatures}' tag numbering). */
    public static final byte TAG_LAVA = 18, TAG_LAVA_CHANNEL = 19, TAG_VOLCANO = 23, TAG_CRATER = 24, TAG_LAVA_LAKE = 25;
    /** An active crater's walls and floor around its lava lake: bare rock, and never water. */
    public static final byte TAG_HOT_CRATER = 26;

    public static final int STRATO = 0, SHIELD = 1;
    /** Steepest mean slope (rise over run) of the ground a shield may be built on. */
    static final double SHIELD_MAX_SLOPE = 0.12;

    /** Coarse map and model elevation access (from the pipeline). */
    public interface Source {
        /** Coarse elevation (metres) over coarse pixels [ci0, ci1) x [cj0, cj1), row-major. */
        float[] coarseElevation(int ci0, int cj0, int ci1, int cj1);

        /** The model's elevation (metres, no ocean features) over native pixels [i1, i2) x [j1, j2), row-major. */
        float[] modelElevation(int i1, int j1, int i2, int j2);
    }

    /** One lava flow: from (oz, ox) along {@code angle}, starting {@code start} pixels out, {@code len} long. */
    static final class Lobe {
        final double oz, ox, angle, start, len, w0, grow, thick;
        final boolean live;
        final float seed;

        Lobe(double oz, double ox, double angle, double start, double len, double w0, double grow, double thick,
             boolean live, float seed) {
            this.oz = oz; this.ox = ox; this.angle = angle; this.start = start; this.len = len; this.w0 = w0;
            this.grow = grow; this.thick = thick; this.live = live; this.seed = seed;
        }
    }

    /** A volcano (native pixels, metres). */
    public static final class Volcano {
        public final int type;
        public final double cz, cx;
        /** Base radius (pixels), base height and summit height (metres). */
        public final double radius, base, summit;
        public final boolean active, caldera;
        final double exponent, craterR, craterDepth, craterTop;
        final float p1, p2, p3, gullyN, gullyDepth;
        final long h;
        final List<Lobe> lobes;
        /** Pit craters along the rift zones (shields): {z, x, radius, depth} each. */
        final List<double[]> pits;

        Volcano(int type, double cz, double cx, double radius, double base, double summit, boolean active, boolean caldera,
                double exponent, double craterR, double craterDepth, double craterTop, long h, List<Lobe> lobes,
                List<double[]> pits) {
            this.type = type; this.cz = cz; this.cx = cx; this.radius = radius; this.base = base; this.summit = summit;
            this.active = active; this.caldera = caldera; this.exponent = exponent; this.craterR = craterR;
            this.craterDepth = craterDepth; this.craterTop = craterTop; this.h = h; this.lobes = lobes; this.pits = pits;
            p1 = (float) (2 * Math.PI * unit(mix(h + 41)));
            p2 = (float) (2 * Math.PI * unit(mix(h + 42)));
            p3 = (float) (2 * Math.PI * unit(mix(h + 43)));
            gullyN = 18 + (int) (16 * unit(mix(h + 44)));
            gullyDepth = (float) ((12 + 22 * unit(mix(h + 45))) * Math.min(1.5, (summit - base) / 1500.0));
        }

        /** Farthest any part reaches from the centre (pixels). */
        double reach() {
            double r = radius * 1.25;
            for (Lobe l : lobes) {
                double d = Math.hypot(l.oz - cz, l.ox - cx) + l.start + l.len + l.w0 + l.grow * l.len;
                r = Math.max(r, d);
            }
            return r + 4;
        }
    }

    private final long seed;
    private final Source source;
    private final Map<Long, List<Volcano>> cells = new ConcurrentHashMap<>();

    private final FastNoiseLite rough = fbm(9101, 1f / 9f, 3);
    private final FastNoiseLite warp = fbm(9102, 1f / 40f, 2);
    private final FastNoiseLite outline = fbm(9103, 1.3f, 2);
    private final FastNoiseLite flowRough = fbm(9104, 1f / 2.5f, 2);

    public Landforms(long seed, Source source) {
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

    /** Carves the landforms into {@code elev} (native window at (i1, j1), H x W) in place and writes their tags. */
    public void apply(float[] elev, int i1, int j1, int H, int W, byte[] tags, List<double[]> islandVents) {
        List<Volcano> vs = volcanoesNear(i1, j1, i1 + H, j1 + W);
        if (vs.isEmpty() && (islandVents == null || islandVents.isEmpty())) return;
        List<Lobe> islandLobes = islandLobes(islandVents);
        for (int r = 0; r < H; r++) {
            double z = i1 + r + 0.5;
            for (int c = 0; c < W; c++) {
                double x = j1 + c + 0.5;
                int k = r * W + c;
                float e = elev[k];
                byte tag = 0;
                for (Volcano v : vs) {
                    double dz = z - v.cz, dx = x - v.cx;
                    double reach = v.reach();
                    if (Math.abs(dz) > reach || Math.abs(dx) > reach) continue;
                    float[] out = {e, tag};
                    shape(v, dz, dx, x, z, out);
                    e = out[0];
                    tag = (byte) out[1];
                }
                if (islandVents != null) {
                    for (double[] v : islandVents) {
                        // The summit crater: a lava lake on a flat floor in its middle, hot bare rock (never water) out
                        // over the rim.
                        double rr = Math.hypot(z - v[0], x - v[1]) / v[5];
                        if (rr >= 1.1) continue;
                        double level = islandLakeLevel(v);
                        if (Double.isNaN(level) || e <= 0f) continue;
                        if (rr < ISLAND_LAKE) {
                            e = (float) Math.min(e, level);
                            tag = TAG_LAVA_LAKE;
                        } else if (tag != TAG_LAVA_LAKE) {
                            tag = TAG_HOT_CRATER;
                        }
                    }
                }
                for (Lobe l : islandLobes) {
                    if (e <= 0f) break;
                    float[] out = {e, tag};
                    lobe(l, x, z, out, true);
                    e = out[0];
                    tag = (byte) out[1];
                }
                elev[k] = e;
                if (tags != null && tag != 0) tags[k] = tag;
            }
        }
    }

    /** One volcano's edit at offset (dz, dx) from its centre; out = {elevation, tag}, updated in place. */
    private void shape(Volcano v, double dz, double dx, double x, double z, float[] out) {
        double e = out[0];
        int tag = (int) out[1];
        double r = Math.hypot(dz, dx), th = Math.atan2(dz, dx);
        // An uneven outline: the radius varies by direction (sampled on a circle, so there is no seam).
        float cs = (float) Math.cos(th), sn = (float) Math.sin(th);
        double rTheta = v.radius * (1 + 0.12 * Math.sin(th + v.p1) + 0.06 * Math.sin(2 * th + v.p2)
                + 0.08 * outline.GetNoise(cs + (v.h & 0xFF), sn - 31));
        double u = r / rTheta;
        if (u < 1.0) {
            double relief = v.summit - v.base;
            double target;
            double w;
            if (v.type == STRATO) {
                target = v.base + relief * Math.pow(1 - u, v.exponent);
                w = 1 - smooth(0.6, 1.0, u);
                // Radial gullies down the flanks (none at the summit or the foot).
                double g = Math.cos(v.gullyN * th + 2.5 * warp.GetNoise((float) (r / 3), (float) (v.h & 0x3FF)));
                double gully = v.gullyDepth * Math.pow(Math.max(0, g), 6) * smooth(0.08, 0.3, u) * (1 - smooth(0.7, 1.0, u));
                target -= gully;
                // Rough, broken flanks.
                target += 10 * rough.GetNoise((float) x, (float) z) * smooth(0.05, 0.2, u);
                // Raise toward the cone everywhere it reaches, but cut ground down to it only on the upper slopes: a
                // ridge running into the foot of the volcano stays (cutting it dug a moat around the cone).
                double down = 1 - smooth(0.3, 0.6, u);
                e = target > e ? e + w * (target - e) : e + w * down * (target - e);
            } else {
                // A shield: the high ground smoothed toward its base and lifted into a broad dome. The model's own detail
                // stays (damped near the summit).
                double dome = relief * Math.pow(Math.max(0, 1 - u * u), 1.2);
                double keep = 0.35 + 0.65 * smooth(0.3, 1.0, u);
                double fade = 1 - smooth(0.85, 1.0, u);
                double smoothed = v.base + (e - v.base) * keep;
                e = e + fade * (smoothed + dome - e) + 6 * rough.GetNoise((float) x, (float) z) * fade;
            }
            // Summit crater or caldera.
            double uc = r / v.craterR;
            if (uc < 1.4) {
                double floor = v.craterTop - v.craterDepth;
                double crater = floor + v.craterDepth * smooth(0.72, 1.0, uc) + 0.1 * v.craterDepth * Math.exp(-sq((uc - 1.05) / 0.14));
                if (uc < 1.0) e = Math.min(e, crater);
                else e += 0.1 * v.craterDepth * Math.exp(-sq((uc - 1.05) / 0.14)) * (1 - smooth(1.2, 1.4, uc));
                // An active crater holds a lava lake on its flat floor only (on the stepped walls it made rings, Calvin,
                // Oct 25). A shield's caldera is too big to be all lava: the lake fills a deeper pit in its middle
                // (Halemaumau in Kilauea's caldera). The rest of an active crater, out over the rim, is hot bare rock
                // that never holds water (the hydrology would pond it).
                double lake = v.type == SHIELD ? LAVA_PIT : 0.68;
                if (v.active && v.type == SHIELD && uc < 1.6 * LAVA_PIT) {
                    e -= 30 * (1 - smooth(0.75 * LAVA_PIT, 1.6 * LAVA_PIT, uc));
                }
                if (v.active) {
                    if (uc < lake) tag = TAG_LAVA_LAKE;
                    else if (uc < 1.15) tag = TAG_HOT_CRATER;
                } else if (uc < 0.95) {
                    tag = TAG_CRATER;
                }
            }
            // Pit craters along the rift zones.
            for (double[] p : v.pits) {
                double up = Math.hypot(z - p[0], x - p[1]) / p[2];
                if (up >= 1.0) continue;
                e -= p[3] * (1 - smooth(0.7, 1.0, up));
                if (tag != TAG_LAVA_LAKE && tag != TAG_HOT_CRATER) tag = TAG_CRATER;
            }
            // Bare rock on the upper slopes of a stratovolcano and around a shield's summit.
            double bare = v.type == STRATO ? 0.55 : 0.18;
            if (tag == 0 && u < bare * (1 + 0.2 * outline.GetNoise(cs * 3 + 11, sn * 3 + (v.h & 0x7F)))) tag = TAG_VOLCANO;
        }
        out[0] = (float) e;
        out[1] = tag;
        for (Lobe l : v.lobes) lobe(l, x, z, out, true);
    }

    /** A lava flow over pixel (x, z): rough, slightly raised rock, live lava along an active flow's middle. */
    private void lobe(Lobe l, double x, double z, float[] out, boolean keepCrater) {
        double dz = z - l.oz, dx = x - l.ox;
        double r = Math.hypot(dz, dx);
        if (r < l.start || r > l.start + l.len + l.w0 + l.grow * l.len) return;
        // The flow meanders: its heading wanders with distance.
        double a = l.angle + 0.18 * warp.GetNoise((float) (r / 4.0), l.seed);
        double ca = Math.cos(a), sa = Math.sin(a);
        double along = dx * ca + dz * sa, lat = -dx * sa + dz * ca;
        double s = along - l.start;
        if (s < 0) return;
        double hw = l.w0 + l.grow * Math.min(s, l.len);
        // A rounded snout at the far end.
        if (s > l.len) {
            double over = s - l.len;
            if (over > hw) return;
            hw = Math.sqrt(hw * hw - over * over);
        }
        hw *= 1 + 0.25 * flowRough.GetNoise((float) (x * 0.7), (float) (z * 0.7));
        double edge = 1 - Math.abs(lat) / Math.max(1, hw);
        if (edge <= 0) return;
        double e = out[0];
        if (e <= 0) return;   // flows stop at the sea
        int tag = (int) out[1];
        if (keepCrater && (tag == TAG_CRATER || tag == TAG_LAVA_LAKE || tag == TAG_HOT_CRATER)) return;
        e += l.thick * smooth(0, 0.3, edge) + 1.5 * flowRough.GetNoise((float) x, (float) z);
        boolean channel = l.live && edge > 0.8 && s < 0.7 * l.len;
        out[0] = (float) e;
        out[1] = channel ? TAG_LAVA_CHANNEL : (tag == TAG_LAVA_CHANNEL ? TAG_LAVA_CHANNEL : TAG_LAVA);
    }

    // =========================================================================
    // Sites
    // =========================================================================

    /** The volcanoes that may reach the native window [i1, i2) x [j1, j2). */
    public List<Volcano> volcanoesNear(int i1, int j1, int i2, int j2) {
        int S = coarsePx(), span = CELL * S;
        int pad = 4 * S;   // the farthest a volcano reaches past its cell's square (a shield's rift flows: ~1.8 x 520 px)
        List<Volcano> out = new ArrayList<>();
        for (int gi = Math.floorDiv(i1 - pad, span); gi <= Math.floorDiv(i2 + pad, span); gi++) {
            for (int gj = Math.floorDiv(j1 - pad, span); gj <= Math.floorDiv(j2 + pad, span); gj++) {
                for (Volcano v : cell(gi, gj)) {
                    double reach = v.reach();
                    if (v.cz + reach < i1 || v.cz - reach > i2 || v.cx + reach < j1 || v.cx - reach > j2) continue;
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static int coarsePx() {
        return 32 * WorldPipelineModelConfig.latentCompression();
    }

    /** Cell (gi, gj)'s volcanoes (cached). */
    List<Volcano> cell(int gi, int gj) {
        long key = mix(((long) gi << 32) ^ (gj & 0xFFFFFFFFL));
        List<Volcano> v = cells.get(key);
        if (v != null) return v;
        v = buildCell(gi, gj);
        if (cells.size() > 4096) cells.clear();
        cells.put(key, v);
        return v;
    }

    private List<Volcano> buildCell(int gi, int gj) {
        int m = 2, n = CELL + 2 * m;
        int ci0 = gi * CELL - m, cj0 = gj * CELL - m;
        float[] ce = source.coarseElevation(ci0, cj0, ci0 + n, cj0 + n);
        long ch = mix(seed ^ ((long) gi * 0x9E3779B97F4A7C15L) ^ ((long) gj * 0xC2B2AE3D27D4EB4FL) ^ 0x701CL);
        List<Volcano> out = new ArrayList<>();
        if (unit(mix(ch + 1)) < STRATO_CHANCE) {
            Volcano v = strato(ce, n, m, ci0, cj0, mix(ch + 2));
            if (v != null) out.add(v);
        }
        if (unit(mix(ch + 3)) < SHIELD_CHANCE) {
            Volcano v = shield(ce, n, m, ci0, cj0, mix(ch + 4));
            if (v != null && (out.isEmpty() || Math.hypot(v.cz - out.get(0).cz, v.cx - out.get(0).cx) > v.radius + out.get(0).radius)) {
                out.add(v);
            }
        }
        return out.isEmpty() ? List.of() : Collections.unmodifiableList(out);
    }

    /** A stratovolcano on the cell's prominent peak, or null if none suits. */
    private Volcano strato(float[] ce, int n, int m, int ci0, int cj0, long h) {
        // Candidates: coarse local maxima above 1200 m standing at least 300 m over their 5x5 surroundings.
        List<int[]> cands = new ArrayList<>();
        for (int r = m; r < n - m; r++) {
            for (int c = m; c < n - m; c++) {
                float e = ce[r * n + c];
                if (e < 1200f) continue;
                float mn = Float.MAX_VALUE;
                boolean max = true;
                for (int a = -2; a <= 2 && max; a++) {
                    for (int b = -2; b <= 2; b++) {
                        float o = ce[(r + a) * n + c + b];
                        if (o > e) { max = false; break; }
                        mn = Math.min(mn, o);
                    }
                }
                if (max && e - mn >= 300f) cands.add(new int[]{r, c});
            }
        }
        if (cands.isEmpty()) return null;
        int[] pick = cands.get((int) (cands.size() * unit(mix(h + 1))));
        int S = coarsePx();
        // The model's highest point near the coarse peak.
        int pi = (ci0 + pick[0]) * S + S / 2, pj = (cj0 + pick[1]) * S + S / 2;
        double radius = 120 + 110 * unit(mix(h + 2));
        // Only a small window of the model's terrain (the peak search); the base comes from the coarse map.
        int win = 136;
        float[] me = source.modelElevation(pi - win, pj - win, pi + win, pj + win);
        int w = 2 * win, best = -1;
        for (int r = win - 128; r < win + 128; r++) {
            for (int c = win - 128; c < win + 128; c++) {
                if (best < 0 || me[r * w + c] > me[best]) best = r * w + c;
            }
        }
        double cz = pi - win + best / w + 0.5, cx = pj - win + best % w + 0.5;
        double peak = me[best];
        double base = coarseRing(ce, n, ci0, cj0, cz, cx, radius);
        if (peak - base < 350 || base < 0) return null;   // not prominent enough: no volcano
        double summit = peak + 250 + 650 * unit(mix(h + 3));
        boolean caldera = unit(mix(h + 4)) < CALDERA_CHANCE;
        boolean active = !caldera && unit(mix(h + 5)) < ACTIVE_CHANCE;
        double relief = summit - base;
        double craterR = caldera ? (0.12 + 0.08 * unit(mix(h + 6))) * radius : Math.max(4, (0.03 + 0.025 * unit(mix(h + 6))) * radius);
        double craterDepth = caldera ? Math.min(0.4 * relief, 250 + 250 * unit(mix(h + 7))) : 60 + 90 * unit(mix(h + 7));
        double exponent = 1.6 + 0.6 * unit(mix(h + 8));
        List<Lobe> lobes = new ArrayList<>();
        int nl = 2 + (int) (4 * unit(mix(h + 9)));
        for (int l = 0; l < nl; l++) {
            long lh = mix(h + 100 + l);
            double ang = 2 * Math.PI * unit(mix(lh + 1));
            double len = (0.6 + 0.7 * unit(mix(lh + 2))) * radius;
            lobes.add(new Lobe(cz, cx, ang, 0.25 * radius, len, 7 + 6 * unit(mix(lh + 3)), 0.14 + 0.14 * unit(mix(lh + 4)),
                    3 + 5 * unit(mix(lh + 5)), active && l < 2, (float) (unit(mix(lh + 6)) * 1000)));
        }
        double craterTop = base + relief * Math.pow(Math.max(0, 1 - craterR / radius), exponent);
        return new Volcano(STRATO, cz, cx, radius, base, summit, active, caldera, exponent, craterR, craterDepth, craterTop, h,
                Collections.unmodifiableList(lobes), List.of());
    }

    /** A shield volcano on the cell's broad, gentle high ground, or null if none suits. */
    private Volcano shield(float[] ce, int n, int m, int ci0, int cj0, long h) {
        List<int[]> cands = new ArrayList<>();
        for (int r = m; r < n - m; r++) {
            for (int c = m; c < n - m; c++) {
                float e = ce[r * n + c];
                if (e < 200f || e > 2500f) continue;
                float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
                for (int a = -2; a <= 2; a++) {
                    for (int b = -2; b <= 2; b++) {
                        float o = ce[(r + a) * n + c + b];
                        mn = Math.min(mn, o);
                        mx = Math.max(mx, o);
                    }
                }
                if (mn > 50f && mx - mn < 450f) cands.add(new int[]{r, c});
            }
        }
        if (cands.isEmpty()) return null;
        int[] pick = cands.get((int) (cands.size() * unit(mix(h + 1))));
        int S = coarsePx();
        int pi = (ci0 + pick[0]) * S + S / 2, pj = (cj0 + pick[1]) * S + S / 2;
        double radius = 280 + 240 * unit(mix(h + 2));
        // The model's terrain over the inner half of the dome (its slope test); the base comes from the coarse map.
        int win = (int) Math.ceil(0.5 * radius);
        float[] me = source.modelElevation(pi - win, pj - win, pi + win, pj + win);
        int w = 2 * win;
        // Gentle enough? The model's ground over the dome must not be rugged (a mountain range is no shield): its mean
        // slope, measured over 4 pixels (120 m), at most SHIELD_MAX_SLOPE. A broad tilt is fine.
        double sum = 0, slope = 0;
        int cnt = 0;
        for (int r = 0; r + 4 < w; r += 8) {
            for (int c = 0; c + 4 < w; c += 8) {
                if (Math.hypot(r - win, c - win) > win) continue;
                double v = me[r * w + c];
                if (v <= 0) return null;   // no sea under a land shield
                slope += Math.hypot(me[r * w + c + 4] - v, me[(r + 4) * w + c] - v) / (4 * 30.0);
                sum += v;
                cnt++;
            }
        }
        double mean = sum / cnt;
        if (slope / cnt > SHIELD_MAX_SLOPE) return null;
        double cz = pi + 0.5, cx = pj + 0.5;
        double base = coarseRing(ce, n, ci0, cj0, cz, cx, radius);
        double summit = Math.max(base, mean) + 350 + 550 * unit(mix(h + 3));
        boolean active = unit(mix(h + 5)) < 0.5;
        double craterR = (0.05 + 0.03 * unit(mix(h + 6))) * radius, craterDepth = 80 + 120 * unit(mix(h + 7));
        // Two rift zones with pit craters; flows start at the summit and along the rifts.
        List<double[]> pits = new ArrayList<>();
        List<Lobe> lobes = new ArrayList<>();
        double rift0 = 2 * Math.PI * unit(mix(h + 8));
        for (int rz = 0; rz < 2; rz++) {
            double ra = rift0 + Math.PI * (0.7 + 0.6 * unit(mix(h + 9 + rz))) * rz;
            int np = 2 + (int) (4 * unit(mix(h + 20 + rz)));
            for (int p = 0; p < np; p++) {
                double d = (0.15 + 0.6 * (p + unit(mix(h + 30 + 10 * rz + p))) / np) * radius;
                double pr = 2 + 4 * unit(mix(h + 50 + 10 * rz + p));
                pits.add(new double[]{cz + Math.sin(ra) * d, cx + Math.cos(ra) * d, pr, 20 + 40 * unit(mix(h + 70 + 10 * rz + p))});
                if (p % 2 == 0) {
                    long lh = mix(h + 200 + 10 * rz + p);
                    double side = unit(mix(lh)) < 0.5 ? -1 : 1;
                    lobes.add(new Lobe(cz + Math.sin(ra) * d, cx + Math.cos(ra) * d, ra + side * (0.6 + 0.8 * unit(mix(lh + 1))),
                            2, (0.4 + 0.6 * unit(mix(lh + 2))) * radius, 8 + 6 * unit(mix(lh + 3)), 0.12 + 0.12 * unit(mix(lh + 4)),
                            3 + 4 * unit(mix(lh + 5)), active && p == 0, (float) (unit(mix(lh + 6)) * 1000)));
                }
            }
        }
        int nl = 3 + (int) (4 * unit(mix(h + 90)));
        for (int l = 0; l < nl; l++) {
            long lh = mix(h + 300 + l);
            lobes.add(new Lobe(cz, cx, 2 * Math.PI * unit(mix(lh + 1)), 1.2 * craterR, (0.7 + 0.8 * unit(mix(lh + 2))) * radius,
                    8 + 8 * unit(mix(lh + 3)), 0.12 + 0.14 * unit(mix(lh + 4)), 3 + 5 * unit(mix(lh + 5)),
                    active && l == 0, (float) (unit(mix(lh + 6)) * 1000)));
        }
        // The surface at the caldera's rim: the smoothed ground there (the model's, damped to 35% about the base) plus the dome.
        double groundAtRim = me[win * w + win];
        double craterTop = base + 0.35 * (groundAtRim - base) + (summit - base) * Math.pow(Math.max(0, 1 - sq(craterR / radius)), 1.2);
        return new Volcano(SHIELD, cz, cx, radius, base, summit, active, false, 1.2, craterR, craterDepth, craterTop, h,
                Collections.unmodifiableList(lobes), Collections.unmodifiableList(pits));
    }

    /** An active island's lava lake: this fraction of its summit crater's radius (inside the crater's flat floor). */
    static final double ISLAND_LAKE = 0.45;
    private final Map<Long, Double> islandLakes = new ConcurrentHashMap<>();

    /**
     * The lava lake's level (metres) in an active island's crater, or NaN for none: the lowest the model drew the
     * island's summit over the lake's disc, less the crater's depth (as the ocean features cut it), so the lake's floor
     * is flat and inside the crater. None where the model drew no island there (its summit under 30 m).
     */
    private double islandLakeLevel(double[] v) {
        long key = Double.doubleToLongBits(v[4]);
        Double cached = islandLakes.get(key);
        if (cached != null) return cached;
        int rad = (int) Math.ceil(ISLAND_LAKE * v[5]) + 1;
        int ci = (int) Math.floor(v[0]), cj = (int) Math.floor(v[1]), w = 2 * rad + 1;
        float[] me = source.modelElevation(ci - rad, cj - rad, ci + rad + 1, cj + rad + 1);
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (int r = 0; r < w; r++) {
            for (int c = 0; c < w; c++) {
                if (Math.hypot(r - rad, c - rad) > rad) continue;
                min = Math.min(min, me[r * w + c]);
                max = Math.max(max, me[r * w + c]);
            }
        }
        double level = max < 30 ? Double.NaN : min - v[6] - 5;
        if (level < 5) level = Double.NaN;
        if (islandLakes.size() > 4096) islandLakes.clear();
        islandLakes.put(key, level);
        return level;
    }

    /**
     * Lava flows down the active island volcanoes ({z, x, land radius, peak, hash, crater radius, crater depth} each,
     * from the ocean features), starting outside the summit crater.
     */
    private List<Lobe> islandLobes(List<double[]> vents) {
        if (vents == null || vents.isEmpty()) return List.of();
        List<Lobe> out = new ArrayList<>();
        for (double[] v : vents) {
            long h = mix(Double.doubleToLongBits(v[4]) ^ 0x1A7AL);
            int nl = 3 + (int) (4 * unit(mix(h + 1)));
            for (int l = 0; l < nl; l++) {
                long lh = mix(h + 10 + l);
                out.add(new Lobe(v[0], v[1], 2 * Math.PI * unit(mix(lh + 1)), Math.max(0.1 * v[2], 1.15 * v[5]), (0.8 + 0.5 * unit(mix(lh + 2))) * v[2],
                        6 + 6 * unit(mix(lh + 3)), 0.12 + 0.12 * unit(mix(lh + 4)), 2 + 4 * unit(mix(lh + 5)), l < 2,
                        (float) (unit(mix(lh + 6)) * 1000)));
            }
        }
        return out;
    }

    /** Mean coarse elevation on the circle of radius R (native pixels) around native (cz, cx). */
    private static double coarseRing(float[] ce, int n, int ci0, int cj0, double cz, double cx, double R) {
        int S = coarsePx();
        double sum = 0;
        for (int a = 0; a < 48; a++) {
            double t = a * Math.PI / 24;
            double gy = (cz + Math.sin(t) * R) / S - 0.5 - ci0, gx = (cx + Math.cos(t) * R) / S - 0.5 - cj0;
            int y0 = Math.max(0, Math.min(n - 2, (int) Math.floor(gy))), x0 = Math.max(0, Math.min(n - 2, (int) Math.floor(gx)));
            double ty = Math.max(0, Math.min(1, gy - y0)), tx = Math.max(0, Math.min(1, gx - x0));
            sum += (ce[y0 * n + x0] * (1 - tx) + ce[y0 * n + x0 + 1] * tx) * (1 - ty)
                    + (ce[(y0 + 1) * n + x0] * (1 - tx) + ce[(y0 + 1) * n + x0 + 1] * tx) * ty;
        }
        return sum / 48;
    }

    // =========================================================================
    // Markers
    // =========================================================================

    /**
     * Volcano and lava field markers in the native window (for the explorer). Cells not yet laid out are only built
     * when the window spans at most a few cells (each new cell computes the model's terrain around its candidate);
     * otherwise only the cells already known are shown.
     */
    public List<OceanFeatures.Landmark> landmarks(int i1, int j1, int i2, int j2) {
        int span = CELL * coarsePx();
        boolean build = (long) (i2 - i1) * (j2 - j1) <= 4L * span * span;
        List<Volcano> vs = new ArrayList<>();
        for (int gi = Math.floorDiv(i1, span) - 1; gi <= Math.floorDiv(i2, span) + 1; gi++) {
            for (int gj = Math.floorDiv(j1, span) - 1; gj <= Math.floorDiv(j2, span) + 1; gj++) {
                List<Volcano> c = build ? cell(gi, gj) : cells.get(mix(((long) gi << 32) ^ (gj & 0xFFFFFFFFL)));
                if (c != null) vs.addAll(c);
            }
        }
        return markers(vs, i1, j1, i2, j2);
    }

    /** Side of a landform cell in native pixels. */
    public static int cellPx() {
        return CELL * coarsePx();
    }

    /** Markers of cell (gi, gj)'s volcanoes (building it if needed), anywhere. For /orogeny-find. */
    public List<OceanFeatures.Landmark> cellMarkers(int gi, int gj) {
        return markers(cell(gi, gj), Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);
    }

    private static List<OceanFeatures.Landmark> markers(List<Volcano> vs, int i1, int j1, int i2, int j2) {
        List<OceanFeatures.Landmark> out = new ArrayList<>();
        for (Volcano v : vs) {
            if (v.cz >= i1 && v.cz < i2 && v.cx >= j1 && v.cx < j2) {
                String type = v.type == SHIELD ? "shield_volcano" : v.caldera ? "caldera" : "stratovolcano";
                out.add(new OceanFeatures.Landmark(type, v.cz, v.cx, (float) v.summit));
            }
            if (!v.lobes.isEmpty()) {
                Lobe l = v.lobes.get(0);
                double d = l.start + 0.6 * l.len;
                double z = l.oz + Math.sin(l.angle) * d, x = l.ox + Math.cos(l.angle) * d;
                if (z >= i1 && z < i2 && x >= j1 && x < j2) out.add(new OceanFeatures.Landmark("lava_field", z, x, (float) v.summit));
            }
        }
        return out;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    static double smooth(double e0, double e1, double v) {
        double t = Math.max(0, Math.min(1, (v - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    private static double sq(double v) {
        return v * v;
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
