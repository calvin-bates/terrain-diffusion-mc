package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Terra's iceberg fields (biomes plan v1.3 round C), replacing vanilla's icebergs: clusters of big ice masses shaped
 * like real ones, a few large bergs with many small pieces (bergy bits and growlers) around them, and rare flat ice
 * islands far out in the frozen sea.
 *
 * <p>Laid out per region, like the river and coast plans: region (ri, rj) owns native pixels
 * {@code [ri*REGION_PX, (ri+1)*REGION_PX)} on both axes and looks at its square plus {@link #MARGIN_PX} (the model's
 * elevation and temperature there). Fields are centred in the region's own square and reach at most
 * {@link #reachBlocks} blocks from it, so every chunk asks the regions within that reach and all chunks agree on every
 * berg. Each chunk then builds its own slice of each berg (see {@code world.Icebergs}).
 *
 * <p>Where: the sea must be cold (full in frozen seas, below about −7 °C, thinning to nothing by +1 °C), and fields
 * crowd the coasts where cold land meets the sea (where real bergs calve), thinning with distance from it as the drift
 * spreads them. Never over shallow water or close to the shore.
 *
 * <p>Sizes are in metres and converted to blocks for the world scale: above the water a block is {@code 30/scale}
 * metres, below it the sea is squashed (a block is {@link #underwaterMetresPerBlock} metres on the shelf), as
 * {@code HeightConverter} lays it out. Most of a berg's mass is under the water: its keel is 4-6 times its freeboard
 * and spreads wider than the part above water.
 */
public final class IcebergPlan {

    /** Region side and analysis margin (native pixels). */
    public static final int REGION_PX = 256, MARGIN_PX = 120;
    /** One candidate field per cell of this many pixels (1.9 km). */
    static final int FIELD_CELL_PX = 64;
    /** Chance a fully cold cell right off a calving coast gets a field. */
    public static final float FIELD_CHANCE = 0.45f;
    /** Chance a region far out in the frozen sea gets one flat ice island. */
    public static final float ICE_ISLAND_CHANCE = 0.35f;
    /** Cold land (calving sources): land colder than this (°C). */
    static final float SOURCE_TEMP_C = -2f;
    /** Fields thin with distance from a calving coast over this many pixels (e-folding, 2.4 km). */
    static final float SOURCE_FALLOFF_PX = 80f;

    public static final int TABULAR = 0, DOME = 1, PINNACLE = 2, WEDGE = 3, DRYDOCK = 4, BIT = 5, ICE_ISLAND = 6;
    public static final String[] SHAPES = {"tabular", "dome", "pinnacle", "wedge", "dry-dock", "bergy bit", "ice island"};

    /** Supplies {elevation, climate} for a native window, as {@link WorldPipeline#get} does. */
    public interface Source {
        float[][] get(int i1, int j1, int i2, int j2);
    }

    /**
     * One berg, in block coordinates: centre, waterline radius, shape, freeboard (blocks above the water) and keel
     * (blocks below it), heading, and a seed for its outline and spires.
     */
    public static final class Berg {
        public final float cx, cz, r, freeboard, keel, angle;
        public final int shape;
        final float p1, p2, p3, cosA, sinA;
        final float[] spireU, spireV, spireH;

        Berg(float cx, float cz, float r, int shape, float freeboard, float keel, float angle, long seed) {
            this.cx = cx; this.cz = cz; this.r = r; this.shape = shape; this.freeboard = freeboard; this.keel = keel;
            this.angle = angle;
            cosA = (float) Math.cos(angle);
            sinA = (float) Math.sin(angle);
            p1 = (float) (2 * Math.PI * unit(mix(seed + 1)));
            p2 = (float) (2 * Math.PI * unit(mix(seed + 2)));
            p3 = (float) (2 * Math.PI * unit(mix(seed + 3)));
            int ns = shape == PINNACLE ? 2 + (int) (3 * unit(mix(seed + 4))) : 0;
            spireU = new float[ns]; spireV = new float[ns]; spireH = new float[ns];
            for (int s = 0; s < ns; s++) {
                // The main spire near the middle, the others around it.
                double a = 2 * Math.PI * unit(mix(seed + 10 + s)), d = s == 0 ? 0.05 + 0.1 * unit(mix(seed + 20))
                        : 0.2 + 0.3 * unit(mix(seed + 20 + s));
                spireU[s] = (float) (Math.cos(a) * d);
                spireV[s] = (float) (Math.sin(a) * d);
                spireH[s] = s == 0 ? 1f : (float) (0.5 + 0.4 * unit(mix(seed + 30 + s)));
            }
        }

        /** Widest the keel reaches (blocks), for culling. */
        public float reach() {
            return r * KEEL_WIDE * 1.3f + 1;
        }

        /** Waterline radius in the direction of (dx, dz) (a wobbly outline). */
        float radiusAt(float dx, float dz) {
            float th = (float) Math.atan2(dz, dx);
            float k = shape == TABULAR || shape == ICE_ISLAND ? 1f : 1.6f;   // the others more ragged
            return r * (1f + k * (0.14f * (float) Math.sin(3 * th + p1) + 0.08f * (float) Math.sin(5 * th + p2)
                    + 0.05f * (float) Math.sin(8 * th + p3)));
        }

        /** Distance from the centre over the waterline radius in that direction (1 at the waterline). */
        public float q(float dx, float dz) {
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            return d < 1e-4f ? 0f : d / radiusAt(dx, dz);
        }

        /** Freeboard (blocks, ≥ 0) at offset (dx, dz) from the centre, or −1 outside the waterline. */
        public float above(float dx, float dz) {
            float h = shapeAbove(dx, dz);
            if (h < 0f || shape == BIT) return h;
            float wx = cx + dx, wz = cz + dz;
            if (shape == TABULAR || shape == ICE_ISLAND) {
                // A flat top with low swells and the odd crevasse cut a third of the way down.
                float c = Math.abs(CREVASSE.GetNoise(wx, wz));
                float cut = c < 0.035f ? 0.35f * (1f - c / 0.035f) : 0f;
                return Math.max(0.5f, h * (1f + 0.06f * ROUGH.GetNoise(wx * 0.5f, wz * 0.5f) - cut));
            }
            // Broken, ridged faces: up to a quarter of the height carved out or built up, more toward the top.
            float rough = ROUGH.GetNoise(wx, wz);
            return Math.max(0f, h * (1f + 0.25f * rough * Math.min(1f, h / Math.max(1f, 0.3f * freeboard))));
        }

        private float shapeAbove(float dx, float dz) {
            float q = q(dx, dz);
            if (q >= 1f) return -1f;
            float u = (dx * cosA + dz * sinA) / r, v = (-dx * sinA + dz * cosA) / r;   // heading frame, in radii
            float F = freeboard;
            switch (shape) {
                case TABULAR:
                case ICE_ISLAND:
                    return F * (1f - 0.15f * (float) Math.pow(q, 8));               // flat top, sheer sides
                case DOME:
                    return F * (float) Math.pow(Math.max(0f, 1f - q * q * q), 0.45);   // steep sides, rounded top
                case BIT:
                    return F * (float) Math.pow(Math.max(0f, 1f - q * q), 0.6);
                case PINNACLE: {
                    float h = 0.45f * F * (float) Math.pow(Math.max(0f, 1f - q * q), 0.7);
                    for (int s = 0; s < spireU.length; s++) {
                        float du = u - spireU[s], dv = v - spireV[s];
                        float t = 1f - (float) Math.sqrt(du * du + dv * dv) / 0.38f;
                        if (t > 0f) h = Math.max(h, F * spireH[s] * (float) Math.pow(t, 0.9));
                    }
                    return h;
                }
                case WEDGE: {
                    float t = Math.max(0f, Math.min(1f, (u + 1f) / 2f));            // a long slope up to a steep face
                    return F * (float) Math.pow(t, 0.8) * (1f - smooth(0.9f, 1f, q)) + 0.1f;
                }
                case DRYDOCK: {
                    float h = 0.15f * F * (1f - q * q);                              // the slot, eroded to near the water
                    for (int s = -1; s <= 1; s += 2) {
                        float du = u - 0.55f * s;
                        float t = 1f - (float) Math.sqrt(du * du + v * v) / 0.45f;
                        if (t > 0f) h = Math.max(h, F * (float) Math.pow(t, 0.7));
                    }
                    return h;
                }
                default:
                    return 0f;
            }
        }

        /** Keel depth (blocks below the water, ≥ 0) at offset (dx, dz), or −1 outside the keel. */
        public float keelDepth(float dx, float dz) {
            float q = q(dx, dz) / KEEL_WIDE;
            if (q >= 1f) return -1f;
            return keel * (float) Math.pow(1f - q * q, 0.6);
        }
    }

    /** Surface relief of the bergs (block coordinates): broken tops, ridges and gullies down the faces. */
    private static final FastNoiseLite ROUGH = noise(91301, 1f / 14f, FastNoiseLite.FractalType.Ridged, 3);
    /** Crevasses across tabular bergs and ice islands. */
    private static final FastNoiseLite CREVASSE = noise(91302, 1f / 45f, FastNoiseLite.FractalType.FBm, 2);

    private static FastNoiseLite noise(int seed, float freq, FastNoiseLite.FractalType type, int octaves) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        n.SetFractalType(type);
        n.SetFractalOctaves(octaves);
        return n;
    }

    /** The keel spreads this much wider than the waterline. */
    public static final float KEEL_WIDE = 1.25f;

    /** A field's centre (block coordinates) and whether it is an ice island, for the finder. */
    public record Field(float x, float z, boolean iceIsland) {}

    public final List<Berg> bergs;
    public final List<Field> fields;

    private IcebergPlan(List<Berg> bergs, List<Field> fields) {
        this.bergs = bergs;
        this.fields = fields;
    }

    public static final IcebergPlan EMPTY = new IcebergPlan(List.of(), List.of());

    /** Metres per block below the sea, on the shelf (as {@code HeightConverter}: the shelf is 12-26 blocks for 200 m). */
    public static float underwaterMetresPerBlock(int scale) {
        float blockSize = WorldPipelineModelConfig.nativeResolution() / (float) scale;
        return 200f / Math.max(12f, Math.min(26f, 200f / blockSize));
    }

    /** Farthest (blocks) a berg reaches from its region's own square, at this scale. */
    public static int reachBlocks(int scale) {
        float m = 30f / scale;
        // The farthest piece from its field's centre (a bergy bit: 600 m + 1.2 × 800 m + 400 m out, ~2 km; a big berg's
        // keel: 600 m + 800 m × 1.25 × 1.3 ≈ 1.9 km) or an ice island's keel (1500 m × 1.25 × 1.3 ≈ 2.45 km).
        return (int) Math.ceil(2500f / m) + 2;
    }

    /** Lays out region (ri, rj)'s fields. */
    public static IcebergPlan build(long seed, int ri, int rj, int scale, Source source) {
        int i0 = ri * REGION_PX - MARGIN_PX, j0 = rj * REGION_PX - MARGIN_PX, span = REGION_PX + 2 * MARGIN_PX;
        float[][] data = source.get(i0, j0, i0 + span, j0 + span);
        float[] elev = data[0], clim = data[1];
        if (clim == null) return EMPTY;
        int n = span * span;
        boolean cold = false;
        for (int k = 0; k < n; k++) if (elev[k] < 0f && clim[k] < 1f) { cold = true; break; }
        if (!cold) return EMPTY;
        // Distance (pixels) to cold land, the calving sources.
        float[][] src = new float[span][span];
        for (int r = 0; r < span; r++)
            for (int c = 0; c < span; c++) {
                int k = r * span + c;
                src[r][c] = elev[k] >= 0f && clim[k] < SOURCE_TEMP_C ? -1f : 1f;
            }
        float[] toSource = CoastShaper.distanceField(src, span, span, true);
        float mpb = 30f / scale, upb = underwaterMetresPerBlock(scale);
        List<Berg> bergs = new ArrayList<>();
        List<Field> fields = new ArrayList<>();
        int own0i = ri * REGION_PX, own0j = rj * REGION_PX;
        int bestIsle = -1;
        long bestIsleH = Long.MAX_VALUE;
        for (int ci = 0; ci < REGION_PX / FIELD_CELL_PX + 1; ci++) {
            for (int cj = 0; cj < REGION_PX / FIELD_CELL_PX + 1; cj++) {
                long h = mix(seed ^ ((long) (own0i / FIELD_CELL_PX + ci) * 0x9E3779B97F4A7C15L)
                        ^ ((long) (own0j / FIELD_CELL_PX + cj) * 0xC2B2AE3D27D4EB4FL) ^ 0x1CEBL);
                int pi = own0i + ci * FIELD_CELL_PX + (int) (FIELD_CELL_PX * unit(mix(h + 1)));
                int pj = own0j + cj * FIELD_CELL_PX + (int) (FIELD_CELL_PX * unit(mix(h + 2)));
                if (pi >= own0i + REGION_PX || pj >= own0j + REGION_PX) continue;
                int k = (pi - i0) * span + (pj - j0);
                float e = elev[k], t = clim[k];
                if (e > -40f) continue;
                if (!openWater(elev, span, pi - i0, pj - j0, 20)) continue;
                float coldness = 1f - smooth(-7f, 1f, t);
                if (coldness <= 0f) continue;
                float d = toSource[k];
                float near = d == Float.MAX_VALUE ? 0f : (float) Math.exp(-d / SOURCE_FALLOFF_PX);
                // Far out in a very cold sea (Calvin, Oct 26: none within 3900 km at below -8 C, 200 m deep, 3 km from land).
                if (t < -4f && e < -100f && (d == Float.MAX_VALUE || d > 50f) && h < bestIsleH) {
                    bestIsleH = h;
                    bestIsle = k;
                }
                double p = FIELD_CHANCE * coldness * (0.15 + 0.85 * near);
                if (unit(mix(h + 3)) >= p) continue;
                float fx = (pj + 0.5f) * scale, fz = (pi + 0.5f) * scale;
                fields.add(new Field(fx, fz, false));
                field(bergs, fx, fz, t, near, mpb, upb, scale, elev, span, i0, j0, mix(h + 4));
            }
        }
        long rh = mix(seed ^ ((long) ri * 0x632BE59BD9B4E019L) ^ ((long) rj * 0x85EBCA77C2B2AE63L) ^ 0x1CE1L);
        if (bestIsle >= 0 && unit(rh) < ICE_ISLAND_CHANCE) {
            float fx = (j0 + bestIsle % span + 0.5f) * scale, fz = (i0 + bestIsle / span + 0.5f) * scale;
            float radM = 600f + 900f * (float) unit(mix(rh + 1)), freeM = 15f + 15f * (float) unit(mix(rh + 2));
            bergs.add(new Berg(fx, fz, radM / mpb, ICE_ISLAND, Math.max(1f, freeM / mpb),
                    (30f + 30f * (float) unit(mix(rh + 3))) / upb, (float) (Math.PI * unit(mix(rh + 4))), mix(rh + 5)));
            fields.add(new Field(fx, fz, true));
        }
        return bergs.isEmpty() ? EMPTY : new IcebergPlan(Collections.unmodifiableList(bergs), Collections.unmodifiableList(fields));
    }

    /** Whether the sea floor stays below sea level within {@code R} pixels in eight directions (not hugging a coast). */
    private static boolean openWater(float[] elev, int span, int r, int c, int R) {
        for (int a = 0; a < 8; a++) {
            int rr = r + (int) Math.round(R * Math.sin(a * Math.PI / 4)), cc = c + (int) Math.round(R * Math.cos(a * Math.PI / 4));
            if (rr < 0 || cc < 0 || rr >= span || cc >= span || elev[rr * span + cc] > -10f) return false;
        }
        return true;
    }

    /**
     * One field around (fx, fz): 1-3 large bergs (tabular ones mostly in the coldest water close to their source) and
     * 6-20 bergy bits and growlers scattered around them.
     */
    private static void field(List<Berg> out, float fx, float fz, float temp, float near, float mpb, float upb, int scale,
                              float[] elev, int span, int i0, int j0, long h) {
        int big = 2 + (int) (4 * unit(mix(h + 1)));
        List<Berg> made = new ArrayList<>();
        for (int b = 0; b < big; b++) {
            long bh = mix(h + 100 + b);
            double pick = unit(mix(bh + 1));
            double tabular = temp < -8f ? 0.4 * (0.5 + 0.5 * near) : 0.15;
            int shape = pick < tabular ? TABULAR
                    : pick < tabular + 0.25 ? DOME
                    : pick < tabular + 0.45 ? PINNACLE
                    : pick < tabular + 0.7 ? WEDGE
                    : pick < tabular + 0.8 ? DRYDOCK : DOME;
            // Tabular bergs are wide and flat; the others are taller for their width (real pinnacle bergs stand 50-80 m
            // over a base of 100-200 m).
            // Exaggerated for drama (Calvin, Oct 25: "much larger and more dramatic"): real bergs rarely stand over
            // 75 m, these reach 160 m, and tabular bergs run up to 1.6 km across.
            float radM = shape == TABULAR ? 250f + 550f * (float) unit(mix(bh + 2)) : 70f + 130f * (float) unit(mix(bh + 2));
            float freeM = switch (shape) {
                case TABULAR -> 35f + 25f * (float) unit(mix(bh + 3));
                case PINNACLE -> 90f + 70f * (float) unit(mix(bh + 3));
                case DOME -> 45f + 45f * (float) unit(mix(bh + 3));
                default -> 60f + 60f * (float) unit(mix(bh + 3));
            };
            double a = 2 * Math.PI * unit(mix(bh + 4)), d = (b == 0 ? 0 : 150 + 450 * unit(mix(bh + 5))) / mpb;
            float x = fx + (float) (Math.cos(a) * d), z = fz + (float) (Math.sin(a) * d);
            if (!overWater(elev, span, i0, j0, x, z, scale)) continue;
            // Big bergs stand apart (overlapping ones merged into shapeless masses).
            boolean clear = true;
            for (Berg o : made) {
                float sep = (float) Math.hypot(o.cx - x, o.cz - z);
                if (sep < 0.9f * (o.r + radM / mpb)) { clear = false; break; }
            }
            if (!clear) continue;
            float keelM = freeM * (3f + 1.5f * (float) unit(mix(bh + 6)));
            Berg berg = new Berg(x, z, radM / mpb, shape, Math.max(1f, freeM / mpb), Math.min(60f, Math.max(2f, keelM / upb)),
                    (float) (2 * Math.PI * unit(mix(bh + 7))), mix(bh + 8));
            made.add(berg);
            out.add(berg);
        }
        if (made.isEmpty()) return;
        int bits = 12 + (int) (24 * unit(mix(h + 2)));
        for (int b = 0; b < bits; b++) {
            long bh = mix(h + 500 + b);
            Berg anchor = made.get((int) (made.size() * unit(mix(bh + 1))));
            double a = 2 * Math.PI * unit(mix(bh + 2));
            float d = anchor.r * 1.2f + (40f + 360f * (float) unit(mix(bh + 3))) / mpb;
            float x = anchor.cx + (float) Math.cos(a) * d, z = anchor.cz + (float) Math.sin(a) * d;
            if (!overWater(elev, span, i0, j0, x, z, scale)) continue;
            float radM = 8f + 32f * (float) unit(mix(bh + 4)), freeM = 3f + 12f * (float) unit(mix(bh + 5));
            out.add(new Berg(x, z, Math.max(1.2f, radM / mpb), BIT, Math.max(0.6f, freeM / mpb),
                    Math.max(1f, freeM * 4.5f / upb), (float) (2 * Math.PI * unit(mix(bh + 6))), mix(bh + 7)));
        }
    }

    /** Whether the sea at block (x, z) is at least 15 m deep (nearest pixel of the window). */
    private static boolean overWater(float[] elev, int span, int i0, int j0, float x, float z, int scale) {
        int r = (int) Math.floor(z / scale) - i0, c = (int) Math.floor(x / scale) - j0;
        if (r < 0 || c < 0 || r >= span || c >= span) return false;
        return elev[r * span + c] < -15f;
    }

    static float smooth(float e0, float e1, float v) {
        float t = Math.max(0f, Math.min(1f, (v - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
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
