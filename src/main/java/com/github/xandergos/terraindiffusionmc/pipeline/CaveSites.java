package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Where caves meet the surface (caves plan v1.5 parts two and three): shapes cut through the cave roof.
 *
 * <ul>
 *   <li><b>River sinks.</b> Under some underground lake and glowworm grotto regions, a narrow shaft drops from the bed of
 *       the nearest river or lake into a pool chamber at the region's water level, so the river visibly pours into the
 *       cave (world/CaveWater lets the water above the shaft flow).</li>
 *   <li><b>Cenotes.</b> Over some karst cave regions, a wide round sinkhole open to the sky drops to a pool chamber at
 *       the region's water level.</li>
 *   <li><b>Canyon ravines</b> (part three): long, deep, V-shaped slots open to the sky in mountains.</li>
 *   <li><b>Deep shafts</b>: huge pits from the surface, widening like a bell as they go down, in mountains and karst.</li>
 *   <li><b>Sinkhole jungles</b>: a collapse dome under karst or tropical ground with a round skylight, where a jungle
 *       grows on the floor.</li>
 *   <li><b>Blue holes</b>: round, deep sinkholes in the floor of warm shallow seas.</li>
 *   <li><b>Cave mouths</b> (Oct 27): big arched tunnels from the surface down into the caves.</li>
 *   <li>Part four round B formations: <b>natural bridges</b> (stone arches across canyon ravines), <b>collapse
 *       domes</b> (a cavern whose roof fell in: a skylight over a rubble mound) and <b>rock shelters</b> (overhangs cut
 *       into the foot of steep cliffs, on their own finer grid, {@link #shelters}). world/SurfaceFormations lays the
 *       rubble and talus on them.</li>
 * </ul>
 *
 * <p>Sites sit on the cave layer's region anchors (every {@link CaveBiomes#REGION_XZ} blocks), decided from the cave
 * biome at the anchor below its ground and a hash of the region, so every chunk agrees. Pure Java.
 */
public final class CaveSites {
    private CaveSites() {
    }

    /** Share of eligible regions that get a river sink or a cenote. */
    public static float SINK_CHANCE = 0.5f, CENOTE_CHANCE = 0.45f;
    /** How far from the anchor a sink looks for a river or lake column. */
    private static final int SINK_REACH = 80;
    /** Depth under the anchor's ground at which a site asks the cave layer for its region. */
    private static final int PROBE_DEPTH = 44;
    /** Widest a site gets (cenote radius plus its chamber), for finding sites near an area. */
    public static final int MAX_REACH = 240;
    /**
     * Whether the part four round B formations are on (natural bridges, collapse domes, rock shelters). Set from the
     * config by the callers in the mod (caves.formations); on by default (harnesses, explorer).
     */
    public static volatile boolean formations = true;

    /** A shape cut through the surface: its density, where it reaches, and its site's centre. */
    public interface Cut {
        /** Density (negative is air, in blocks) at a point, +inf where the shape doesn't reach. */
        float value(int px, int py, int pz);

        /** Whether the shape may reach column (px, pz). */
        boolean covers(int px, int pz);

        int top();

        int minY();

        int x();

        int z();

        /** Command word for the finder (cenote, river_sink, canyon_ravine, deep_shaft, sinkhole_jungle, blue_hole). */
        String kind();

        default boolean sink() {
            return false;
        }

        /** How far from (x, z) the shape reaches sideways. */
        float reach();
    }

    /** A vertical shaft from {@code top} down to {@code bottom}, with a round pool chamber at its foot. */
    public record Shaft(int x, int z, float radius, int bottom, int top, boolean sink, float chamberRadius) implements Cut {
        @Override
        public boolean covers(int px, int pz) {
            float dx = px - x, dz = pz - z, r = reach() + 1;
            return dx * dx + dz * dz <= r * r;
        }

        @Override
        public float reach() {
            return Math.max(radius, chamberRadius);
        }

        @Override
        public String kind() {
            return sink ? "river_sink" : "cenote";
        }

        /** Density (negative is air, in blocks) of this shaft at a point, or +inf if the point is outside its reach. */
        public float value(int px, int py, int pz) {
            float dx = px - x, dz = pz - z;
            float d2 = dx * dx + dz * dz;
            float v = Float.POSITIVE_INFINITY;
            if (py <= top && py >= bottom) v = (float) Math.sqrt(d2) - radius;
            // The chamber: a squashed sphere centred 3 blocks above the shaft's foot.
            float cy = (py - (bottom + 3)) * 1.6f;
            float c = (float) Math.sqrt(d2 + cy * cy) - chamberRadius;
            return Math.min(v, c);
        }

        public int minY() {
            return bottom + 3 - (int) Math.ceil(chamberRadius / 1.6f) - 1;
        }
    }

    /** Cuts whose reach touches the block rectangle [x0, x1] x [z0, z1]. */
    public static List<Cut> near(int x0, int z0, int x1, int z1, CaveBiomes layer, CaveBiomes.Surface surface) {
        List<Cut> out = new ArrayList<>();
        int r = CaveBiomes.REGION_XZ, half = r / 2, reach = Math.max(MAX_REACH, SINK_REACH + 4);
        int ax0 = Math.floorDiv(x0 - reach - half, r), ax1 = Math.floorDiv(x1 + reach - half, r) + 1;
        int az0 = Math.floorDiv(z0 - reach - half, r), az1 = Math.floorDiv(z1 + reach - half, r) + 1;
        for (int az = az0; az <= az1; az++) {
            for (int ax = ax0; ax <= ax1; ax++) {
                Cut s = siteMemo(ax * r + half, az * r + half, layer, surface);
                if (s == null) continue;
                float reachS = s.reach();
                if (s.x() + reachS < x0 || s.x() - reachS > x1 || s.z() + reachS < z0 || s.z() - reachS > z1) continue;
                out.add(s);
            }
        }
        if (formations) shelters(x0, z0, x1, z1, surface, out);
        return out;
    }

    // ---- Memo: a chunk looks at about 16 anchors up to 370 blocks away, and three passes per chunk ask (terrain, cave
    // water, surface formations). Each site reads many ground heights (a river sink searches rings out to 80 blocks), so
    // without this most of a chunk's terrain time went here. Only for surfaces whose answers never change (the world's).

    private static final java.util.concurrent.ConcurrentHashMap<Long, Object> SITES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Long, Object> SHELTERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object NO_CUT = new Object();
    private static final int MEMO_MAX = 16384;
    private static volatile long memoStamp = Long.MIN_VALUE;

    /** Clears the memos when the world, seed or formations setting changed since they were filled. */
    private static void checkStamp(long seed) {
        long stamp = seed * 31L + LocalTerrainProvider.cacheGeneration() * 2L + (formations ? 1 : 0);
        if (stamp != memoStamp) {
            synchronized (SITES) {
                if (stamp != memoStamp) {
                    SITES.clear();
                    SHELTERS.clear();
                    memoStamp = stamp;
                }
            }
        }
    }

    private static long cellKey(int a, int b) {
        return ((long) a << 32) ^ (b & 0xFFFFFFFFL);
    }

    /** {@link #site}, memoized for the world's surface. */
    public static Cut siteMemo(int ax, int az, CaveBiomes layer, CaveBiomes.Surface surface) {
        if (!surface.memoizable()) return site(ax, az, layer, surface);
        checkStamp(layer.seed());
        long key = cellKey(ax, az);
        Object o = SITES.get(key);
        if (o == null) {
            Cut c = site(ax, az, layer, surface);
            if (SITES.size() > MEMO_MAX) SITES.clear();
            SITES.put(key, c == null ? NO_CUT : c);
            return c;
        }
        return o == NO_CUT ? null : (Cut) o;
    }

    /** {@link #shelter}, memoized for the world's surface. */
    private static Shelter shelterMemo(int gx, int gz, CaveBiomes.Surface surface) {
        if (!surface.memoizable()) return shelter(gx, gz, surface);
        checkStamp(LocalTerrainProvider.getSeed());
        long key = cellKey(gx, gz);
        Object o = SHELTERS.get(key);
        if (o == null) {
            Shelter s = shelter(gx, gz, surface);
            if (SHELTERS.size() > MEMO_MAX) SHELTERS.clear();
            SHELTERS.put(key, s == null ? NO_CUT : s);
            return s;
        }
        return o == NO_CUT ? null : (Shelter) o;
    }

    /** The cut at a region anchor, or null. */
    public static Cut site(int ax, int az, CaveBiomes layer, CaveBiomes.Surface surface) {
        int ground = surface.groundTop(ax, az);
        if (ground == Integer.MIN_VALUE || ground < 10) return null;
        int probe = ground - PROBE_DEPTH;
        short id = layer.pick(ax, probe, az, surface);
        long cell = layer.regionCell(ax, probe, az);
        long h = hash(cell, layer);
        float u = (h >>> 40) / (float) (1L << 24);
        float v = ((h >>> 16) & 0xFFFF) / 65536f;
        float w = ((h >>> 8) & 0xFF) / 256f;
        switch (id) {
            case CaveBiomes.CANYON_RAVINE: return ravine(ax, az, ground, u, v, w, h);
            case CaveBiomes.DEEP_SHAFT: return pit(ax, az, ground, u, v, w);
            case CaveBiomes.SINKHOLE_JUNGLE: return dome(ax, az, ground, u, v, w);
            case CaveBiomes.BLUE_HOLE: return blueHole(ax, az, ground, u, v, w);
            default: break;
        }
        if (ground < 40) return null;
        if (id != CaveBiomes.KARST_CAVES && id != CaveBiomes.UNDERGROUND_LAKES && id != CaveBiomes.GLOWWORM_GROTTO) {
            if (u < MOUTH_CHANCE) return mouth(ax, az, surface, h, u, v, w);
            return formations && u < MOUTH_CHANCE + COLLAPSE_CHANCE ? collapseDome(ax, az, surface, h, v, w) : null;
        }
        if (id != CaveBiomes.KARST_CAVES && id != CaveBiomes.UNDERGROUND_LAKES && id != CaveBiomes.GLOWWORM_GROTTO) return null;
        int level = layer.waterLevel(cell);
        if (level >= ground - 12 || level < CaveBiomes.WATER_MIN_Y) return null;
        if (id == CaveBiomes.KARST_CAVES) {
            if (u >= CENOTE_CHANCE) return null;
            // A round sinkhole open to the sky, 18 to 36 blocks across the throat, over a much wider pool.
            int jx = (int) ((v - 0.5f) * 80), jz = (int) ((((h >>> 8) & 0xFF) / 256f - 0.5f) * 80);
            int x = ax + jx, z = az + jz;
            int top = surface.groundTop(x, z);
            if (top == Integer.MIN_VALUE || top - level < 16) return null;
            float radius = 9f + v * 9f;
            return new Shaft(x, z, radius, level - 6, top + 2, false, radius + 10f);
        }
        if (u >= SINK_CHANCE) return null;
        // A river sink: the nearest river or lake column to the anchor (rings outwards, 4 blocks apart).
        for (int d = 0; d <= SINK_REACH; d += 4) {
            int n = d == 0 ? 1 : 8 * d / 4;
            for (int k = 0; k < n; k++) {
                double a = 2 * Math.PI * k / n + v;
                int x = ax + (int) Math.round(d * Math.cos(a)), z = az + (int) Math.round(d * Math.sin(a));
                int water = surface.inlandWater(x, z);
                if (water == Integer.MIN_VALUE) continue;
                int bed = surface.groundTop(x, z);
                if (bed == Integer.MIN_VALUE || bed - level < 14) return null;
                return new Shaft(x, z, 2f + 2f * v, level - 3, bed, true, 10f + 6f * v);
            }
        }
        return null;
    }

    // ---- Part three round B: rare surface cuts ----

    /** Lowest a cut goes: above the lava of the deepest caves. */
    private static final int CUT_FLOOR_Y = -50;

    /** Share of canyon ravines with one natural bridge, and with two (part four round B). */
    public static float BRIDGE_CHANCE = 0.6f, TWO_BRIDGES_CHANCE = 0.25f;

    /**
     * A canyon ravine: 180-340 blocks long, 18-40 wide at the top, 90-180 deep, wandering, narrowing downwards. Most
     * get a natural bridge or two: a stone arch spanning the ravine somewhere in its middle half.
     */
    static Cut ravine(int ax, int az, int ground, float u, float v, float w, long h) {
        float angle = (float) (u * Math.PI);
        float length = 180f + v * 160f, halfWidth = 9f + w * 11f;
        int bottom = Math.max(CUT_FLOOR_Y, ground - 90 - (int) (u * 90f));
        float b1 = Float.NaN, b2 = Float.NaN, y1 = 0f, y2 = 0f;
        float r0 = unit(h, 3), r1 = unit(h, 13), r2 = unit(h, 23), r3 = unit(h, 33), r4 = unit(h, 43);
        if (formations && r0 < BRIDGE_CHANCE) {
            b1 = (r1 - 0.5f) * length * 0.5f;   // in the middle half, where the ravine is at its deepest
            y1 = 0.42f + 0.33f * r2;            // how far up from the floor to the rim
            if (r0 < TWO_BRIDGES_CHANCE) {
                b2 = b1 + (r3 < 0.5f ? 1 : -1) * (40f + 50f * r4);
                if (Math.abs(b2) > length * 0.33f) b2 = b1 - (b2 - b1);
                y2 = 0.42f + 0.33f * r3;
            }
        }
        return new Ravine(ax, az, (float) Math.cos(angle), (float) Math.sin(angle), length, halfWidth, bottom, ground,
                8f + 12f * w, 50f + 50f * v, v * 6.28f, b1, y1, b2, y2, 3f + 3f * r4);
    }

    /**
     * A canyon ravine and its natural bridges: bridge k stands at {@code bk} along the ravine's axis (NaN for none),
     * its deck {@code yk} of the way from the floor to the rim, {@code bridgeT} blocks thick along the axis (half).
     */
    public record Ravine(int x, int z, float cos, float sin, float length, float halfWidth, int bottom, int ground,
                         float wiggle, float wiggleLen, float phase, float b1, float y1, float b2, float y2,
                         float bridgeT) implements Cut {
        @Override
        public float value(int px, int py, int pz) {
            if (py < bottom - 1) return Float.POSITIVE_INFINITY;
            float dx = px - x, dz = pz - z;
            float a = dx * cos + dz * sin, b = -dx * sin + dz * cos;
            b -= wiggle * (float) Math.sin(a / wiggleLen + phase);
            float t = 2f * a / length;
            float end = 1f - t * t;
            if (end <= 0f) return Float.POSITIVE_INFINITY;
            float taper = (float) Math.sqrt(end);
            // Shallower and narrower towards the ends; V-shaped across: narrow at the floor, widest at the top.
            float floor = bottom + (ground - bottom) * (1f - taper) * 0.8f;
            float up = Math.max(0f, Math.min(1f, (py - floor) / Math.max(1f, ground - floor)));
            float half = halfWidth * taper * (0.3f + 0.7f * up);
            float v = Math.max(Math.abs(b) - half, floor - py);
            if (v < 0f) {
                if (!Float.isNaN(b1)) v = Math.max(v, -bridge(a, b, py, b1, y1, floor, halfWidth * taper));
                if (!Float.isNaN(b2)) v = Math.max(v, -bridge(a, b, py, b2, y2, floor, halfWidth * taper));
            }
            return v;
        }

        /**
         * A natural bridge's distance field (negative inside the rock): a flat-topped arch across the ravine at
         * {@code ba} along its axis, 6-12 blocks wide. 3-7 blocks thick in the middle, its underside curving down
         * into the walls.
         */
        private float bridge(float a, float b, int py, float ba, float frac, float floor, float topHalf) {
            float da = Math.abs(a - ba);
            float T = bridgeT * (1f + 0.25f * (float) Math.sin(b / 3.7f + phase) + 0.15f * (float) Math.sin(py / 2.3f));
            if (da > T + 3f) return Float.POSITIVE_INFINITY;
            float deck = floor + (ground - floor) * frac;
            float top = deck + 1.2f * (float) Math.sin(a / 4.1f + b / 5.3f + phase);
            float q = b / Math.max(4f, topHalf);
            float thick = 5f + 2f * (float) Math.sin(ba + phase);
            float under = top - thick - 12f * q * q - 1.5f * (da / Math.max(1f, T)) * (da / Math.max(1f, T));
            return Math.max(da - T, Math.max(py - top, under - py));
        }

        /** Where a bridge is: its middle (deck top), or null if the ravine has none. */
        public int[] bridgeSpot(int k) {
            float ba = k == 0 ? b1 : b2, frac = k == 0 ? y1 : y2;
            if (Float.isNaN(ba)) return null;
            float bm = wiggle * (float) Math.sin(ba / wiggleLen + phase);
            int bx = Math.round(x + ba * cos - bm * sin), bz = Math.round(z + ba * sin + bm * cos);
            float t = 2f * ba / length, taper = (float) Math.sqrt(Math.max(0f, 1f - t * t));
            float floor = bottom + (ground - bottom) * (1f - taper) * 0.8f;
            return new int[]{bx, (int) (floor + (ground - floor) * frac) + 3, bz};
        }

        @Override
        public boolean covers(int px, int pz) {
            float dx = px - x, dz = pz - z, r = reach() + 1;
            return dx * dx + dz * dz <= r * r;
        }

        @Override
        public float reach() {
            return length / 2f + halfWidth + wiggle;
        }

        @Override
        public int top() {
            return ground + 160;
        }

        @Override
        public int minY() {
            return bottom - 1;
        }

        @Override
        public String kind() {
            return "canyon_ravine";
        }
    }

    /** A deep shaft: 28-52 blocks across at the mouth, widening like a bell, 120-240 deep (to Y -50 at most). */
    static Cut pit(int ax, int az, int ground, float u, float v, float w) {
        float radius = 14f + 12f * v;
        int bottom = Math.max(CUT_FLOOR_Y, ground - 120 - (int) (u * 120f));
        if (ground - bottom < 60) return null;
        return new Pit(ax + (int) ((w - 0.5f) * 60), az + (int) ((v - 0.5f) * 60), radius, bottom, ground, u * 6.28f);
    }

    public record Pit(int x, int z, float radius, int bottom, int ground, float phase) implements Cut {
        @Override
        public float value(int px, int py, int pz) {
            if (py < bottom - 2) return Float.POSITIVE_INFINITY;
            float dx = px - x, dz = pz - z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            float t = Math.max(0f, Math.min(1f, (ground - py) / (float) (ground - bottom)));
            double th = Math.atan2(dz, dx);
            float rough = 1f + 0.10f * (float) Math.sin(3 * th + phase) + 0.06f * (float) Math.sin(7 * th + 2 * phase)
                    + 0.05f * (float) Math.sin(py / 5f + phase);
            float r = radius * (1f + 1.1f * t * t) * rough;
            // A bowl-shaped floor.
            float floor = bottom + (d / Math.max(1f, r)) * (d / Math.max(1f, r)) * 10f;
            return Math.max(d - r, floor - py);
        }

        @Override
        public boolean covers(int px, int pz) {
            float dx = px - x, dz = pz - z, r = reach() + 1;
            return dx * dx + dz * dz <= r * r;
        }

        @Override
        public float reach() {
            return radius * 2.1f * 1.21f;
        }

        @Override
        public int top() {
            return ground + 120;
        }

        @Override
        public int minY() {
            return bottom - 2;
        }

        @Override
        public String kind() {
            return "deep_shaft";
        }
    }

    /** A sinkhole jungle: a dome 100-140 blocks across and 50-70 tall, its top 20 blocks down, with a skylight. */
    static Cut dome(int ax, int az, int ground, float u, float v, float w) {
        float rx = 50f + 20f * v, ry = 32f + 13f * w;
        int cy = ground - 20 - (int) ry;
        if (cy - ry * 0.55f < CUT_FLOOR_Y) return null;
        return new Dome(ax, az, cy, rx, ry, 12f + 10f * u, ground, (int) ((u - 0.5f) * rx * 0.6f), (int) ((w - 0.5f) * rx * 0.6f),
                0f, false);
    }

    /** Share of land region anchors (without a cave mouth) that get a collapse dome (part four round B). */
    public static float COLLAPSE_CHANCE = 0.08f;

    /**
     * A collapse dome: a cavern 56-90 blocks across and 36-52 tall whose roof fell in, 10-18 blocks under the ground,
     * open to the sky through a ragged skylight 12-22 across, a mound of fallen rock under it (world/SurfaceFormations
     * covers it with rubble). On land away from rivers and lakes.
     */
    static Cut collapseDome(int ax, int az, CaveBiomes.Surface surface, long h, float v, float w) {
        int x = ax + (int) ((v - 0.5f) * 120), z = az + (int) ((w - 0.5f) * 120);
        int ground = surface.groundTop(x, z);
        if (ground == Integer.MIN_VALUE || ground < 64 || surface.nearInlandWater(x, z)) return null;
        float a = unit(h, 3), b = unit(h, 13), c = unit(h, 23);
        float rx = 28f + 17f * a, ry = 18f + 8f * b;
        int cy = ground - 10 - (int) (8 * c) - (int) ry;
        if (cy - ry * 0.55f < CUT_FLOOR_Y + 4) return null;
        float sky = 6f + 5f * c;
        return new Dome(x, z, cy, rx, ry, sky, ground, (int) ((b - 0.5f) * rx * 0.4f), (int) ((a - 0.5f) * rx * 0.4f),
                ry * (0.45f + 0.25f * a), true);
    }

    /**
     * A dome: the sinkhole jungle's (flat floor), or a collapse dome's ({@code collapse}, with a mound {@code rubble}
     * blocks high under its skylight).
     */
    public record Dome(int x, int z, int cy, float rx, float ry, float skylight, int ground, int lightDx, int lightDz,
                       float rubble, boolean collapse) implements Cut {
        @Override
        public float value(int px, int py, int pz) {
            float dx = (px - x) / rx, dy = (py - cy) / ry, dz = (pz - z) / rx;
            float e = ((float) Math.sqrt(dx * dx + dy * dy + dz * dz) - 1f) * ry;
            e = Math.max(e, floorAt(px, pz) - py);   // a flat floor, or a rubble mound
            // The skylight: a round opening from the dome's upper half to the sky, a little off centre.
            float v = Float.POSITIVE_INFINITY;
            if (py >= cy) {
                float sx = px - (x + lightDx), sz = pz - (z + lightDz);
                float rag = collapse ? 0.18f * (float) Math.sin(Math.atan2(sz, sx) * 4 + py / 6f) : 0f;
                v = (float) Math.sqrt(sx * sx + sz * sz) - skylight * (1f + 0.08f * (float) Math.sin(py / 4f) + rag);
            }
            // The mound stands up into the skylight too.
            return rubble > 0f ? Math.max(Math.min(e, v), floorAt(px, pz) - py) : Math.min(e, v);
        }

        @Override
        public boolean covers(int px, int pz) {
            float dx = px - x, dz = pz - z, r = reach() + 1;
            return dx * dx + dz * dz <= r * r;
        }

        /** The dome's floor at a column: flat, or for a collapse dome a mound under the skylight. */
        public float floorAt(int px, int pz) {
            float floorY = cy - ry * 0.55f;
            if (rubble <= 0f) return floorY;
            float sx = px - (x + lightDx), sz = pz - (z + lightDz);
            float d = (float) Math.sqrt(sx * sx + sz * sz) / (skylight * 2.6f);
            d *= 1f + 0.12f * (float) Math.sin(Math.atan2(sz, sx) * 5 + skylight);
            if (d >= 1f) return floorY;
            float m = 1f - d;
            return floorY + rubble * m * m * (3f - 2f * m);
        }

        @Override
        public float reach() {
            return rx;
        }

        @Override
        public int top() {
            return ground + 120;
        }

        @Override
        public int minY() {
            return (int) (cy - ry * 0.55f) - 1;
        }

        @Override
        public String kind() {
            return collapse ? "collapse_dome" : "sinkhole_jungle";
        }
    }

    /** A blue hole: 36-72 blocks across in a warm shallow sea floor, 60-120 deep, widening below its lip. */
    static Cut blueHole(int ax, int az, int ground, float u, float v, float w) {
        if (ground >= 61) return null;
        float radius = 18f + 18f * v;
        int bottom = Math.max(CUT_FLOOR_Y + 5, ground - 60 - (int) (u * 60f));
        return new BlueHole(ax + (int) ((w - 0.5f) * 80), az + (int) ((u - 0.5f) * 80), radius, bottom, ground);
    }

    public record BlueHole(int x, int z, float radius, int bottom, int ground) implements Cut {
        @Override
        public float value(int px, int py, int pz) {
            if (py < bottom - 2 || py > ground + 2) return Float.POSITIVE_INFINITY;
            float dx = px - x, dz = pz - z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            float t = Math.max(0f, Math.min(1f, (ground - py) / (float) (ground - bottom)));
            // A sharp lip, then a cavern that widens below it (as real blue holes are flooded caves).
            float r = radius * (t < 0.15f ? 1f : 1f + 0.5f * (t - 0.15f) / 0.85f);
            float floor = bottom + (d / r) * (d / r) * 4f;
            return Math.max(d - r, floor - py);
        }

        @Override
        public boolean covers(int px, int pz) {
            float dx = px - x, dz = pz - z, r = reach() + 1;
            return dx * dx + dz * dz <= r * r;
        }

        @Override
        public float reach() {
            return radius * 1.5f;
        }

        @Override
        public int top() {
            return ground + 2;
        }

        @Override
        public int minY() {
            return bottom - 2;
        }

        @Override
        public String kind() {
            return "blue_hole";
        }
    }

    // ---- Cave mouths (Oct 27) ----

    /** Share of land region anchors (without another cut) that get a cave mouth. */
    public static float MOUTH_CHANCE = 0.5f;

    /**
     * A cave mouth: a big arched tunnel from the surface sloping down into the caves, 14-26 blocks across at the
     * entrance and widening inside, with a flat floor you can walk down. On a slope it heads into the hillside (an
     * arch in the hill); on flat ground it is a ramp-like opening. Two straight legs with a bend between them.
     */
    static Cut mouth(int ax, int az, CaveBiomes.Surface surface, long h, float u, float v, float w) {
        if (u >= MOUTH_CHANCE) return null;
        float a = ((h >>> 24) & 0xFF) / 256f, b = ((h >>> 32) & 0xFF) / 256f;
        int x = ax + (int) ((v - 0.5f) * 160), z = az + (int) ((w - 0.5f) * 160);
        int ground = surface.groundTop(x, z);
        if (ground == Integer.MIN_VALUE || ground < 66) return null;
        if (surface.nearInlandWater(x, z)) return null;
        // Uphill, if the ground rises: the direction among eight whose ground 24 blocks away is highest.
        float yaw = a * 6.2831855f;
        int best = ground + 5;
        for (int k = 0; k < 8; k++) {
            double ang = k * Math.PI / 4 + a * 0.5;
            int g = surface.groundTop(x + (int) Math.round(24 * Math.cos(ang)), z + (int) Math.round(24 * Math.sin(ang)));
            if (g != Integer.MIN_VALUE && g > best) {
                best = g;
                yaw = (float) ang;
            }
        }
        float pitch = 0.35f + 0.25f * b;
        float len = 70f + 70f * w;
        float r0 = 7f + 6f * v;
        float sx = x, sy = ground + r0 * 0.35f, sz = z;
        float cp = (float) Math.cos(pitch);
        float ex = sx + len * cp * (float) Math.cos(yaw), ey = sy - len * (float) Math.sin(pitch), ez = sz + len * cp * (float) Math.sin(yaw);
        if (ey - r0 < CUT_FLOOR_Y + 10) return null;
        float yaw2 = yaw + (a - 0.5f) * 1.4f, len2 = len * 0.5f, p2 = pitch * 0.6f;
        float c2 = (float) Math.cos(p2);
        float fx = ex + len2 * c2 * (float) Math.cos(yaw2), fy = ey - len2 * (float) Math.sin(p2), fz = ez + len2 * c2 * (float) Math.sin(yaw2);
        if (fy - r0 * 1.4f < CUT_FLOOR_Y + 6) return null;
        return new Mouth(sx, sy, sz, ex, ey, ez, fx, fy, fz, r0, r0 * 1.4f, ground, b * 6.28f);
    }

    public record Mouth(float sx, float sy, float sz, float ex, float ey, float ez, float fx, float fy, float fz,
                        float r0, float r1, int ground, float phase) implements Cut {
        @Override
        public float value(int px, int py, int pz) {
            float rm = (r0 + r1) * 0.5f;
            return Math.min(leg(px, py, pz, sx, sy, sz, ex, ey, ez, r0, rm), leg(px, py, pz, ex, ey, ez, fx, fy, fz, rm, r1));
        }

        /** One leg: an arched tunnel, 1.4 times wider than tall, its floor flat a third of the way down. */
        private float leg(int px, int py, int pz, float ax, float ay, float az, float bx, float by, float bz, float ra, float rb) {
            float dx = bx - ax, dy = by - ay, dz = bz - az;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= len; dy /= len; dz /= len;
            float ox = px - ax, oy = py - ay, oz = pz - az;
            float t = Math.max(0f, Math.min(len, ox * dx + oy * dy + oz * dz));
            float qx = ox - dx * t, qy = oy - dy * t, qz = oz - dz * t;
            float hl = (float) Math.sqrt(dx * dx + dz * dz) + 1e-6f;
            float ux = -dz / hl, uz = dx / hl;
            float across = qx * ux + qz * uz;
            float up = (float) Math.sqrt(Math.max(0f, qx * qx + qy * qy + qz * qz - across * across)) * Math.signum(qy);
            float r = (ra + (rb - ra) * t / len) * (1f + 0.08f * (float) Math.sin(t / 6f + phase)
                    + 0.05f * (float) Math.sin(Math.atan2(up, across) * 3 + phase));
            float e = ((float) Math.sqrt((across / 1.4f) * (across / 1.4f) + up * up) - r);
            return Math.max(e, -0.35f * r - up);   // a flat floor a third of the way down
        }

        @Override
        public boolean covers(int px, int pz) {
            float dx = px - sx, dz = pz - sz, rr = reach() + 1;
            return dx * dx + dz * dz <= rr * rr;
        }

        @Override
        public float reach() {
            float d1 = (float) Math.hypot(ex - sx, ez - sz), d2 = (float) Math.hypot(fx - sx, fz - sz);
            return Math.max(d1, d2) + r1 * 1.5f;
        }

        @Override
        public int x() {
            return (int) sx;
        }

        @Override
        public int z() {
            return (int) sz;
        }

        @Override
        public int top() {
            return ground + (int) (r0 * 2) + 4;
        }

        @Override
        public int minY() {
            return (int) Math.floor(Math.min(ey, fy) - r1 - 2);
        }

        @Override
        public String kind() {
            return "cave_mouth";
        }
    }

    // ---- Rock shelters (part four round B) ----

    /** Shelter candidates: one per cell (blocks) at this chance; most fail the cliff test. */
    public static final int SHELTER_CELL = 40;
    public static float SHELTER_CHANCE = 0.55f;
    /** The rise (blocks) over 12 blocks that counts as a cliff. */
    public static int SHELTER_RISE = 14;
    private static final int SHELTER_REACH = 24;

    /** The rock shelters reaching the block rectangle, added to out. */
    public static void shelters(int x0, int z0, int x1, int z1, CaveBiomes.Surface surface, List<Cut> out) {
        int gx0 = Math.floorDiv(x0 - SHELTER_REACH, SHELTER_CELL), gx1 = Math.floorDiv(x1 + SHELTER_REACH, SHELTER_CELL);
        int gz0 = Math.floorDiv(z0 - SHELTER_REACH, SHELTER_CELL), gz1 = Math.floorDiv(z1 + SHELTER_REACH, SHELTER_CELL);
        for (int gz = gz0; gz <= gz1; gz++)
            for (int gx = gx0; gx <= gx1; gx++) {
                Shelter s = shelterMemo(gx, gz, surface);
                if (s == null) continue;
                float r = s.reach();
                if (s.cx + r < x0 || s.cx - r > x1 || s.cz + r < z0 || s.cz - r > z1) continue;
                out.add(s);
            }
    }

    /**
     * The rock shelter of a grid cell, or null: at a random point of the cell, on land above the sea and off rivers,
     * where the ground rises at least {@link #SHELTER_RISE} blocks within 12 (a cliff), an overhang cut into the
     * cliff's foot: 12-28 blocks wide, 5-9 deep, 5-9 tall, its floor the ground at the foot.
     */
    public static Shelter shelter(int gx, int gz, CaveBiomes.Surface surface) {
        long h = mixL(gx * 0x9E3779B97F4A7C15L ^ gz * 0xC2B2AE3D27D4EB4FL ^ 0x5E1735L);
        if (unit(h, 0) >= SHELTER_CHANCE) return null;
        int qx = gx * SHELTER_CELL + 4 + (int) (unit(h, 8) * (SHELTER_CELL - 8));
        int qz = gz * SHELTER_CELL + 4 + (int) (unit(h, 16) * (SHELTER_CELL - 8));
        int g0 = surface.groundTop(qx, qz);
        if (g0 == Integer.MIN_VALUE || g0 < 64 || surface.inlandWater(qx, qz) != Integer.MIN_VALUE) return null;
        // The steepest of eight directions.
        int bestRise = 0;
        float dirX = 0, dirZ = 0;
        for (int k = 0; k < 8; k++) {
            double ang = k * Math.PI / 4 + unit(h, 24) * 0.7;
            float cx = (float) Math.cos(ang), cz = (float) Math.sin(ang);
            int g = surface.groundTop(qx + Math.round(12 * cx), qz + Math.round(12 * cz));
            if (g != Integer.MIN_VALUE && g - g0 > bestRise) {
                bestRise = g - g0;
                dirX = cx;
                dirZ = cz;
            }
        }
        if (bestRise < SHELTER_RISE) return null;
        // The cliff's foot: the last point along that direction before the ground starts to rise.
        int fx = qx, fz = qz, foot = g0;
        for (int k = 1; k <= 12; k++) {
            int x = qx + Math.round(k * dirX), z = qz + Math.round(k * dirZ);
            int g = surface.groundTop(x, z);
            if (g == Integer.MIN_VALUE) return null;
            if (g - foot >= 3) break;
            fx = x;
            fz = z;
            foot = Math.min(foot, g);
        }
        float depth = 5f + 4f * unit(h, 32), width = 6f + 8f * unit(h, 40), height = 5f + 4f * unit(h, 48);
        // A roof: the cliff stays well above the shelter's top all the way in.
        int in = surface.groundTop(fx + Math.round((depth + 2) * dirX), fz + Math.round((depth + 2) * dirZ));
        if (in == Integer.MIN_VALUE) return null;
        height = Math.min(height, in - foot - 4f);
        if (height < 4f) return null;
        return new Shelter(fx, fz, foot, dirX, dirZ, depth, width, height, unit(h, 56) * 6.28f);
    }

    /**
     * A rock shelter: an overhang cut into a cliff whose foot is at (cx, foot, cz), the cliff rising towards (dirX,
     * dirZ). Half an ellipsoid reaching {@code depth} into the cliff, {@code width} to each side, {@code height} up,
     * on a floor at the foot's ground.
     */
    public record Shelter(int cx, int cz, int foot, float dirX, float dirZ, float depth, float width, float height,
                          float phase) implements Cut {
        @Override
        public float value(int px, int py, int pz) {
            if (py <= foot || py > foot + height + 2) return Float.POSITIVE_INFINITY;
            float ox = px - cx, oz = pz - cz;
            float along = ox * dirX + oz * dirZ, across = -ox * dirZ + oz * dirX;
            if (along < -4f) return Float.POSITIVE_INFINITY;
            float ht = height * (1f + 0.15f * (float) Math.sin(across / 3.1f + phase));
            float dp = depth * (1f + 0.2f * (float) Math.sin(across / 4.3f + 2 * phase) + 0.1f * (float) Math.sin(py / 2.2f));
            float qa = along / dp, qc = across / width, qy = (py - foot - 0.5f) / ht;
            return ((float) Math.sqrt(qa * qa + qc * qc + qy * qy) - 1f) * Math.min(ht, dp);
        }

        @Override
        public boolean covers(int px, int pz) {
            float dx = px - cx, dz = pz - cz, r = reach() + 1;
            return dx * dx + dz * dz <= r * r;
        }

        @Override
        public float reach() {
            return Math.max(width, depth * 1.3f) + 2f;
        }

        @Override
        public int x() {
            return cx;
        }

        @Override
        public int z() {
            return cz;
        }

        @Override
        public int top() {
            return foot + (int) Math.ceil(height * 1.15f) + 3;
        }

        @Override
        public int minY() {
            return foot;
        }

        @Override
        public String kind() {
            return "rock_shelter";
        }
    }

    /** 8 bits of h from bit {@code shift}, as [0, 1). */
    private static float unit(long h, int shift) {
        return ((h >>> shift) & 0xFF) / 256f;
    }

    private static long mixL(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static long hash(long cell, CaveBiomes layer) {
        long z = cell * 0x9E3779B97F4A7C15L + (long) (layer.draw2(cell) * (1 << 24)) * 0xC2B2AE3D27D4EB4FL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
