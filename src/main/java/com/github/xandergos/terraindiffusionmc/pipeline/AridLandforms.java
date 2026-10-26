package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.List;

/**
 * Dry-land landforms (biomes plan v1.4 round C), applied to a tile's final ground at block resolution, after the coast:
 * <ul>
 *   <li><b>Badlands mesas</b> ({@link #mesas}): in hot, arid zones the hills are terraced into tall banded cap-rock layers: a
 *   monotone remap of height, so every slope becomes a stair of flat benches and short cliffs (the Colorado Plateau's
 *   mesas and buttes). Badlands mesa biome.</li>
 *   <li><b>Canyons</b> ({@link #canyons}): rivers and streams through arid zones cut stepped gorges: the ground near a
 *   channel is lowered to a profile that rises from the river in three cliffs separated by broad benches (an inner
 *   gorge, a Tonto-like platform, the upper walls), reaching up to ~2 km over the river. Only ever lowers, so on low
 *   ground near a river nothing happens and in high plateaus the canyon is as deep as the plateau. Canyon biome.</li>
 *   <li><b>Desert dunes</b> ({@link #dunes}): in sand seas (erg zones of flat desert), transverse ridges across a wind
 *   from the west, a gentle windward slope and a steep slip face, 6-30 m high and 250-450 m apart, wandering and
 *   breaking into crescents.</li>
 *   <li><b>Hoodoos</b> ({@link #hoodoos}): clusters of thin spires 15-70 m tall on arid slopes in badlands, mesas and
 *   canyons (Bryce Canyon).</li>
 * </ul>
 * Everything depends only on world position, the model's ground and climate and the hydrology regions' rivers, so
 * neighbouring tiles agree.
 */
public final class AridLandforms {

    private static final FastNoiseLite MESA_ZONE = noise(7401, 1f / 6000f, 2);
    private static final FastNoiseLite MESA_STEP = noise(7402, 1f / 1800f, 1);
    private static final FastNoiseLite MESA_PHASE = noise(7403, 1f / 2500f, 1);
    private static final FastNoiseLite CANYON_ZONE = noise(7404, 1f / 8000f, 2);
    private static final FastNoiseLite CANYON_WALL = noise(7405, 1f / 90f, 2);
    private static final FastNoiseLite ERG_ZONE = noise(7406, 1f / 7000f, 2);
    private static final FastNoiseLite DUNE_WARP = noise(7407, 1f / 900f, 2);
    private static final FastNoiseLite DUNE_AMP = noise(7408, 1f / 1200f, 1);
    private static final FastNoiseLite DUNE_BREAK = noise(7409, 1f / 260f, 1);
    private static final FastNoiseLite HOODOO_ZONE = noise(7410, 1f / 700f, 2);

    /** Zone noise levels (2-octave simplex; 0.55 leaves ~10% of the world in the zone, 0.6 ~7%): higher = rarer. */
    static final float MESA_ZONE_LEVEL = 0.6f, CANYON_ZONE_LEVEL = 0.55f, ERG_ZONE_LEVEL = 0.0f, HOODOO_ZONE_LEVEL = 0.2f;
    /** Canyon half-width (native pixels) for a stream, a river and a main river (times 0.8-1.2 by channel). */
    static final float CANYON_HW_STREAM = 6f, CANYON_HW_RIVER = 10f, CANYON_HW_MAIN = 14f;
    /** How far past its half-width (fraction) a canyon segment is looked at; its profile is far above any rim there. */
    static final float CANYON_LOOK = 1.25f;
    /** Widest canyon half-width (native pixels, ~1.1 km): canyons widen to span the valley they are cut into. */
    public static final float CANYON_MAX_HW_PX = 36f;
    /** Widest a canyon reaches from its river (native pixels), wobble included. */
    public static final int CANYON_REACH_PX = (int) Math.ceil(CANYON_MAX_HW_PX * CANYON_LOOK) + 2;

    private AridLandforms() {
    }

    private static FastNoiseLite noise(int seed, float freqPerMetre, int octaves) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freqPerMetre);
        if (octaves > 1) {
            n.SetFractalType(FastNoiseLite.FractalType.FBm);
            n.SetFractalOctaves(octaves);
        }
        return n;
    }

    /** How arid and warm (0..1) a place with annual mean temperature t (deg C) and precipitation p (mm) is. */
    static float arid(float t, float p) {
        return smoothstep(450f, 250f, p) * smoothstep(2f, 8f, t);
    }

    /** As {@link #arid}, but hot too (mesas and hoodoos). */
    static float hotArid(float t, float p) {
        return smoothstep(420f, 260f, p) * smoothstep(9f, 15f, t);
    }

    // ---- Mesas ----

    /**
     * Terraces hot, arid hills into mesas.
     *
     * @param climate the tile's climate (temperature at 0, precipitation at 2 * H * W)
     */
    public static void mesas(float[] elev, short[] biomes, float[] climate, float[] riverDist, int i1, int j1, int H,
                             int W, float metresPerBlock) {
        if (climate == null) return;
        int HW = H * W;
        for (int k = 0; k < HW; k++) {
            float e = elev[k];
            if (e < 250f || BiomeClassifier.isOceanBiome(biomes[k])) continue;
            float a = hotArid(climate[k], climate[2 * HW + k]);
            if (a <= 0f) continue;
            int r = k / W, c = k % W;
            float wx = (j1 + c) * metresPerBlock, wz = (i1 + r) * metresPerBlock;
            float zone = smoothstep(MESA_ZONE_LEVEL, MESA_ZONE_LEVEL + 0.12f, MESA_ZONE.GetNoise(wx, wz));
            if (zone <= 0f) continue;
            float raw = zone * a * smoothstep(250f, 500f, e);
            // Full strength over most of a mesa zone: half-strength terraces were low ledges like ripples (Calvin, Oct 26).
            float w = smoothstep(0.15f, 0.5f, raw);
            // Rivers keep their own valleys through the mesas (a terrace edge along a channel left the river in a slot).
            if (riverDist != null) w *= awayFromChannel(riverDist[k], 0.8f, 3f);
            if (w <= 0f) continue;
            float step = 120f + 50f * MESA_STEP.GetNoise(wx, wz);           // ~85-155 m layers: a few tall cliffs
            float off = 200f * MESA_PHASE.GetNoise(wx, wz);
            float q = (e - off) / step;
            float fl = (float) Math.floor(q), f = q - fl;
            // The lower 95% of each layer is a nearly flat bench, the top 5% a cliff (the cap rock's edge). The cliff
            // takes only that share of the old slope's width, so even on gentle hills it is near vertical; with 14% it
            // was a 30-40 degree bank, and the hills read as rippled ledges rather than mesas.
            float g = f < 0.95f ? 0.05f * f / 0.95f : 0.05f + 0.95f * smoothstep(0.95f, 1f, f);
            float terraced = off + step * (fl + g);
            elev[k] = e + (terraced - e) * w;
            if (raw > 0.3f) biomes[k] = RegionalBiomes.BADLANDS_MESA;
        }
    }

    // ---- Canyons ----

    /**
     * One river segment from the hydrology, from a channel pixel to the one downstream of it (native pixel centres),
     * with its water level (m), canyon half-width (native pixels) and canyon weight (0..1) at each end.
     */
    public record Segment(float ax, float az, float bx, float bz, float levelA, float levelB, float hwA, float hwB,
                          float wA, float wB, float rimA, float rimB, long hash) {}

    /** Canyon half-width (native pixels) for a channel of class {@code cls} and size (1 stream .. 3 main), at pixel (i, j). */
    public static float halfWidth(float size, int i, int j) {
        float s = Math.max(1f, Math.min(3f, size));
        float hw = s < 2f ? CANYON_HW_STREAM + (CANYON_HW_RIVER - CANYON_HW_STREAM) * (s - 1f)
                : CANYON_HW_RIVER + (CANYON_HW_MAIN - CANYON_HW_RIVER) * (s - 2f);
        return hw * (0.8f + 0.4f * unit(hash(i, j, 0xCA1L), 1));
    }

    /** Canyon weight (0..1) at native pixel (i, j) with climate t, p. */
    public static float canyonWeight(int i, int j, float t, float p) {
        float a = arid(t, p);
        if (a <= 0f) return 0f;
        float m = WorldPipelineModelConfig.nativeResolution();
        return a * smoothstep(CANYON_ZONE_LEVEL, CANYON_ZONE_LEVEL + 0.12f, CANYON_ZONE.GetNoise(j * m, i * m));
    }

    /**
     * Cuts the canyons of {@code segments} into the tile's ground (blocks; world block (i1 + r, j1 + c) at r * W + c).
     * The canyon's surface is worked out at native pixels (the lowest of every segment's profile there) and
     * interpolated to blocks at a position jittered by ~half a pixel of noise, which roughens the cliff lines. Columns
     * inside a canyon (cut or not, so the risers too) get the canyon biome.
     */
    public static void canyons(float[] elev, short[] biomes, List<Segment> segments, int i1, int j1, int H, int W,
                               int scale) {
        canyons(elev, biomes, segments, i1, j1, H, W, scale, false);
    }

    /**
     * As above; {@code yosemite} cuts Yosemite valleys instead (biomes plan v1.4 round D): a U-shaped glacial valley
     * with a broad flat floor (the inner 35% of its half-width), sheer granite walls rising most of the way to the rim
     * within the next 12%, and rounded domes above. Only valleys at least 250-500 m deep.
     */
    public static void canyons(float[] elev, short[] biomes, List<Segment> segments, int i1, int j1, int H, int W,
                               int scale, boolean yosemite) {
        if (segments.isEmpty()) return;
        float metresPerBlock = WorldPipelineModelConfig.nativeResolution() / scale;
        final float NONE = 1e9f;
        int ni0 = Math.floorDiv(i1, scale) - 2, nj0 = Math.floorDiv(j1, scale) - 2;
        int nh = Math.floorDiv(i1 + H - 1, scale) + 3 - ni0 + 1, nw = Math.floorDiv(j1 + W - 1, scale) + 3 - nj0 + 1;
        float[] tn = new float[nh * nw], in = new float[nh * nw];
        java.util.Arrays.fill(tn, NONE);
        boolean any = false;
        for (Segment s : segments) {
            float hwMax = Math.max(s.hwA(), s.hwB()) * CANYON_LOOK;
            int r0 = Math.max(0, (int) Math.floor(Math.min(s.az(), s.bz()) - hwMax) - ni0);
            int r1 = Math.min(nh - 1, (int) Math.ceil(Math.max(s.az(), s.bz()) + hwMax) - ni0);
            int c0 = Math.max(0, (int) Math.floor(Math.min(s.ax(), s.bx()) - hwMax) - nj0);
            int c1 = Math.min(nw - 1, (int) Math.ceil(Math.max(s.ax(), s.bx()) + hwMax) - nj0);
            if (r0 > r1 || c0 > c1) continue;
            float ex = s.bx() - s.ax(), ez = s.bz() - s.az(), l2 = ex * ex + ez * ez;
            // Shares of the canyon's depth taken by the inner gorge and the middle cliffs (the upper cliffs take the rest).
            float f1 = 0.2f + 0.1f * unit(s.hash(), 2), f2 = 0.25f + 0.1f * unit(s.hash(), 3);
            for (int r = r0; r <= r1; r++) {
                float z = ni0 + r + 0.5f;
                for (int c = c0; c <= c1; c++) {
                    float x = nj0 + c + 0.5f;
                    float t = l2 > 0f ? Math.max(0f, Math.min(1f, ((x - s.ax()) * ex + (z - s.az()) * ez) / l2)) : 0f;
                    float qx = s.ax() + t * ex - x, qz = s.az() + t * ez - z;
                    float d = (float) Math.sqrt(qx * qx + qz * qz);
                    float hw = s.hwA() + (s.hwB() - s.hwA()) * t;
                    float u = d / hw;
                    if (u >= CANYON_LOOK) continue;
                    float w = s.wA() + (s.wB() - s.wA()) * t;
                    if (w <= 0f) continue;
                    float level = s.levelA() + (s.levelB() - s.levelA()) * t;
                    float depth = Math.max(0f, s.rimA() + (s.rimB() - s.rimA()) * t - level);
                    // Only deep valleys become canyons: shallow ones (grassland streams) were flagged as canyon too.
                    w *= yosemite ? smoothstep(250f, 500f, depth) : smoothstep(80f, 200f, depth);
                    if (w <= 0f) continue;
                    float tgt = level + (1f - w) * 600f + (yosemite ? uProfile(u, depth) : profile(u, depth, f1, f2));
                    int k = r * nw + c;
                    if (tgt < tn[k]) tn[k] = tgt;
                    float inside = w * (1f - smoothstep(0.9f, 1.05f, u));
                    if (inside > in[k]) in[k] = inside;
                    any = true;
                }
            }
        }
        if (!any) return;
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                float fz = (i1 + r + 0.5f) / scale - 0.5f - ni0, fx = (j1 + c + 0.5f) / scale - 0.5f - nj0;
                int z0 = (int) Math.floor(fz), x0 = (int) Math.floor(fx);
                // Skip blocks no canyon comes near (all four corners empty).
                if (tn[z0 * nw + x0] >= NONE && tn[z0 * nw + x0 + 1] >= NONE && tn[(z0 + 1) * nw + x0] >= NONE
                        && tn[(z0 + 1) * nw + x0 + 1] >= NONE) continue;
                float wx = (j1 + c) * metresPerBlock, wz = (i1 + r) * metresPerBlock;
                fz += 0.55f * CANYON_WALL.GetNoise(wx, wz);
                fx += 0.55f * CANYON_WALL.GetNoise(wz + 5000f, wx - 3000f);
                fz = Math.max(0f, Math.min(nh - 1.001f, fz));
                fx = Math.max(0f, Math.min(nw - 1.001f, fx));
                z0 = (int) fz; x0 = (int) fx;
                float tz = fz - z0, tx = fx - x0;
                float tgt = bilerp(tn, nw, z0, x0, tz, tx);
                int k = r * W + c;
                if (BiomeClassifier.isOceanBiome(biomes[k])) continue;
                if (tgt < elev[k]) elev[k] = tgt;
                if (bilerp(in, nw, z0, x0, tz, tx) > 0.4f) biomes[k] = yosemite ? RegionalBiomes.YOSEMITE : RegionalBiomes.CANYON;
            }
        }
    }

    private static float bilerp(float[] g, int w, int r0, int c0, float tr, float tc) {
        float a = g[r0 * w + c0], b = g[r0 * w + c0 + 1], c = g[(r0 + 1) * w + c0], d = g[(r0 + 1) * w + c0 + 1];
        return (a * (1 - tc) + b * tc) * (1 - tr) + (c * (1 - tc) + d * tc) * tr;
    }

    /**
     * Height (m) of a canyon's walls over its river at u half-widths out, for a canyon {@code depth} deep (its rim over
     * the river): a floor, the inner gorge (share f1 of the depth), a broad bench, the middle cliffs (f2), another bench,
     * the upper cliffs up to the rim, then steeply on (far over any rim).
     */
    static float profile(float u, float depth, float f1, float f2) {
        float bench = Math.min(8f, 0.03f * depth);
        float f3 = Math.max(0f, 1f - f1 - f2) * depth - 2f * bench;
        float g = f1 * depth * smoothstep(0.10f, 0.17f, u)
                + bench * smoothstep(0.17f, 0.45f, u)
                + f2 * depth * smoothstep(0.45f, 0.53f, u)
                + bench * smoothstep(0.53f, 0.75f, u)
                + f3 * smoothstep(0.75f, 0.83f, u)
                + 10f * smoothstep(0.83f, 1f, u);
        if (u > 1f) g += (u - 1f) * 6000f;
        return g;
    }

    /** A Yosemite valley's walls (m over its river) at u half-widths out: floor, sheer walls, domes, then steeply on. */
    static float uProfile(float u, float depth) {
        float g = 4f * smoothstep(0f, 0.35f, u)
                + 0.85f * depth * smoothstep(0.35f, 0.47f, u)
                + 0.15f * depth * smoothstep(0.47f, 0.95f, u);
        if (u > 1f) g += (u - 1f) * 6000f;
        return g;
    }

    private static final FastNoiseLite YOSEMITE_ZONE = noise(7411, 1f / 9000f, 2);
    /** Zone level for Yosemite valleys (~10% of the world in the zone). */
    static final float YOSEMITE_ZONE_LEVEL = 0.55f;

    /** Yosemite weight (0..1) at native pixel (i, j) with climate t, p: temperate, wet mountains in Yosemite zones. */
    public static float yosemiteWeight(int i, int j, float t, float p) {
        float climate = smoothstep(1f, 5f, t) * (1f - smoothstep(13f, 17f, t)) * smoothstep(400f, 700f, p);
        if (climate <= 0f) return 0f;
        float m = WorldPipelineModelConfig.nativeResolution();
        return climate * smoothstep(YOSEMITE_ZONE_LEVEL, YOSEMITE_ZONE_LEVEL + 0.12f, YOSEMITE_ZONE.GetNoise(j * m, i * m));
    }

    /** Most a canyon cuts (m): its rim over the river is capped at this. */
    static final float CANYON_MAX_DEPTH_M = 1500f;

    // ---- Dunes ----

    /**
     * Dune ridges on flat desert in erg zones (only raises).
     *
     * @param up    upsampled ground (m) with the tile's margin (for the slope test)
     * @param cropI row in {@code up} of the tile's first row
     * @param cropJ column in {@code up} of the tile's first column
     */
    public static void dunes(float[] elev, short[] biomes, float[] riverDist, float[][] up, int cropI, int cropJ, int i1,
                             int j1, int H, int W, float metresPerBlock) {
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int k = r * W + c;
                if (biomes[k] != BiomeClassifier.DESERT) continue;
                float e = elev[k];
                if (e < 4f) continue;
                float wx = (j1 + c) * metresPerBlock, wz = (i1 + r) * metresPerBlock;
                float zone = smoothstep(ERG_ZONE_LEVEL, ERG_ZONE_LEVEL + 0.15f, ERG_ZONE.GetNoise(wx, wz));
                if (zone <= 0f) continue;
                // Flat ground only (the smooth ground over ~2 native pixels).
                int si = cropI + r, sj = cropJ + c, g = Math.max(2, (int) (60f / metresPerBlock));
                float sx = (up[si][sj + g] - up[si][sj - g]) / (2f * g * metresPerBlock);
                float sz = (up[si + g][sj] - up[si - g][sj]) / (2f * g * metresPerBlock);
                float flat = 1f - smoothstep(0.04f, 0.12f, (float) Math.sqrt(sx * sx + sz * sz));
                if (riverDist != null) flat *= awayFromChannel(riverDist[k], 1f, 4f);
                if (flat <= 0f) continue;
                // Ridges run north-south across a west wind, wandering; spacing 250-450 m.
                float spacing = 350f + 100f * DUNE_AMP.GetNoise(wz * 0.7f + 3000, wx * 0.7f);
                float phase = (wx + 220f * DUNE_WARP.GetNoise(wx, wz)) / spacing;
                float p = phase - (float) Math.floor(phase);
                float h = p < 0.78f ? smoothstep(0f, 0.78f, p) : 1f - smoothstep(0.78f, 1f, p);
                // Crests break into crescents and fade in and out along their length.
                float brk = 0.55f + 0.45f * DUNE_BREAK.GetNoise(wx * 0.4f, wz * 1.3f);
                float amp = 6f + 24f * (0.5f + 0.5f * DUNE_AMP.GetNoise(wx, wz));
                elev[k] = e + amp * h * brk * zone * flat;
            }
        }
    }

    // ---- Hoodoos ----

    /** Where the hoodoo pass may look up the model's smooth ground and climate outside the tile. */
    public interface Field {
        /** Smooth ground (m) at world block (bi, bj). */
        float ground(float bi, float bj);

        /** Temperature (deg C) at world block (bi, bj). */
        float temp(float bi, float bj);

        /** Precipitation (mm) at world block (bi, bj). */
        float precip(float bi, float bj);
    }

    /**
     * Hoodoos: one candidate spire per 4-block cell (scale 6; 20 m), in hoodoo zones on arid slopes; each column within
     * a spire's radius is raised by the spire's height times a flat-topped profile (columns judged on their own, so
     * the spire stands on whatever ground the tile has). Only on canyon, mesa, badlands and painted mountain columns.
     */
    public static void hoodoos(float[] elev, short[] biomes, float[] riverDist, Field field, int i1, int j1, int H, int W,
                               int scale) {
        float metresPerBlock = WorldPipelineModelConfig.nativeResolution() / scale;
        float cellM = 20f;
        int cell = Math.max(1, Math.round(cellM / metresPerBlock));
        float maxR = 9f / metresPerBlock + 1f;
        int m = (int) Math.ceil(maxR);
        for (int gi = Math.floorDiv(i1 - m, cell); gi <= Math.floorDiv(i1 + H + m, cell); gi++) {
            for (int gj = Math.floorDiv(j1 - m, cell); gj <= Math.floorDiv(j1 + W + m, cell); gj++) {
                long h = hash(gi, gj, 0x400D00L);
                if (unit(h, 1) > 0.35f) continue;
                float cz = (gi + 0.2f + 0.6f * unit(h, 2)) * cell, cx = (gj + 0.2f + 0.6f * unit(h, 3)) * cell;
                float wx = cx * metresPerBlock, wz = cz * metresPerBlock;
                if (HOODOO_ZONE.GetNoise(wx, wz) < HOODOO_ZONE_LEVEL) continue;
                if (hotArid(field.temp(cz, cx), field.precip(cz, cx)) < 0.5f) continue;
                float g = Math.max(1f, 30f / metresPerBlock);
                float sx = (field.ground(cz, cx + g) - field.ground(cz, cx - g)) / (2f * g * metresPerBlock);
                float sz = (field.ground(cz + g, cx) - field.ground(cz - g, cx)) / (2f * g * metresPerBlock);
                float slope = (float) Math.sqrt(sx * sx + sz * sz);
                if (slope < 0.12f || slope > 1.6f) continue;
                float radius = (4f + 5f * unit(h, 4)) / metresPerBlock + 0.35f;
                float height = 15f + 55f * unit(h, 5) * unit(h, 6);
                for (int r = Math.max(0, (int) Math.floor(cz - radius) - i1); r <= Math.min(H - 1, (int) Math.ceil(cz + radius) - i1); r++) {
                    for (int c = Math.max(0, (int) Math.floor(cx - radius) - j1); c <= Math.min(W - 1, (int) Math.ceil(cx + radius) - j1); c++) {
                        float dx = j1 + c + 0.5f - cx, dz = i1 + r + 0.5f - cz;
                        float d = (float) Math.sqrt(dx * dx + dz * dz);
                        if (d >= radius) continue;
                        int k = r * W + c;
                        if (riverDist != null && riverDist[k] < 1.5f) continue;
                        short b = biomes[k];
                        if (b != RegionalBiomes.CANYON && b != RegionalBiomes.BADLANDS_MESA && b != BiomeClassifier.BADLANDS
                                && b != RegionalBiomes.PAINTED_MOUNTAINS) continue;
                        // Flat top, then the spire narrows toward its top: the outer columns stand lower.
                        float f = d / radius;
                        float prof = f < 0.45f ? 1f : 1f - 0.75f * smoothstep(0.45f, 1f, f);
                        elev[k] += height * prof;
                    }
                }
            }
        }
    }

    // ---- Helpers ----

    /** 0 on a channel (closer than {@code near} native pixels), rising to 1 at {@code far}. */
    public static float awayFromChannel(float distPx, float near, float far) {
        return smoothstep(near, far, distPx);
    }

    static long hash(int a, int b, long salt) {
        return (long) a * 0x9E3779B97F4A7C15L ^ (long) b * 0xC2B2AE3D27D4EB4FL ^ salt;
    }

    static float unit(long h, long salt) {
        long z = h + salt * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 40) / (float) (1L << 24);
    }

    static float smoothstep(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }
}
