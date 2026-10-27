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
    private static final int SINK_REACH = 44;
    /** Depth under the anchor's ground at which a site asks the cave layer for its region. */
    private static final int PROBE_DEPTH = 44;
    /** Widest a site gets (cenote radius plus its chamber), for finding sites near an area. */
    public static final int MAX_REACH = 110;

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
                Cut s = site(ax * r + half, az * r + half, layer, surface);
                if (s == null) continue;
                float reachS = s.reach();
                if (s.x() + reachS < x0 || s.x() - reachS > x1 || s.z() + reachS < z0 || s.z() - reachS > z1) continue;
                out.add(s);
            }
        }
        return out;
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
            case CaveBiomes.CANYON_RAVINE: return ravine(ax, az, ground, u, v, w);
            case CaveBiomes.DEEP_SHAFT: return pit(ax, az, ground, u, v, w);
            case CaveBiomes.SINKHOLE_JUNGLE: return dome(ax, az, ground, u, v, w);
            case CaveBiomes.BLUE_HOLE: return blueHole(ax, az, ground, u, v, w);
            default: break;
        }
        if (ground < 40) return null;
        if (id != CaveBiomes.KARST_CAVES && id != CaveBiomes.UNDERGROUND_LAKES && id != CaveBiomes.GLOWWORM_GROTTO) return null;
        int level = layer.waterLevel(cell);
        if (level >= ground - 12 || level < CaveBiomes.WATER_MIN_Y) return null;
        if (id == CaveBiomes.KARST_CAVES) {
            if (u >= CENOTE_CHANCE) return null;
            // A round sinkhole open to the sky, 10 to 22 blocks across the throat, over a wider pool.
            int jx = (int) ((v - 0.5f) * 30), jz = (int) ((((h >>> 8) & 0xFF) / 256f - 0.5f) * 30);
            int x = ax + jx, z = az + jz;
            int top = surface.groundTop(x, z);
            if (top == Integer.MIN_VALUE || top - level < 16) return null;
            float radius = 5f + v * 6f;
            return new Shaft(x, z, radius, level - 4, top + 2, false, radius + 5f);
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
                return new Shaft(x, z, 1.5f + v, level - 3, bed, true, 6f + 3f * v);
            }
        }
        return null;
    }

    // ---- Part three round B: rare surface cuts ----

    /** Lowest a cut goes: above the lava of the deepest caves. */
    private static final int CUT_FLOOR_Y = -50;

    /** A canyon ravine: 90-170 blocks long, 8-18 wide at the top, 50-100 deep, wandering, narrowing downwards. */
    static Cut ravine(int ax, int az, int ground, float u, float v, float w) {
        float angle = (float) (u * Math.PI);
        float length = 90f + v * 80f, halfWidth = 4f + w * 5f;
        int bottom = Math.max(CUT_FLOOR_Y, ground - 50 - (int) (u * 50f));
        return new Ravine(ax, az, (float) Math.cos(angle), (float) Math.sin(angle), length, halfWidth, bottom, ground,
                4f + 6f * w, 30f + 30f * v, v * 6.28f);
    }

    public record Ravine(int x, int z, float cos, float sin, float length, float halfWidth, int bottom, int ground,
                         float wiggle, float wiggleLen, float phase) implements Cut {
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
            return Math.max(Math.abs(b) - half, floor - py);
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
            return ground + 120;
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

    /** A deep shaft: 16-28 blocks across at the mouth, widening like a bell, 120-240 deep (to Y -50 at most). */
    static Cut pit(int ax, int az, int ground, float u, float v, float w) {
        float radius = 8f + 6f * v;
        int bottom = Math.max(CUT_FLOOR_Y, ground - 120 - (int) (u * 120f));
        if (ground - bottom < 60) return null;
        return new Pit(ax + (int) ((w - 0.5f) * 20), az + (int) ((v - 0.5f) * 20), radius, bottom, ground, u * 6.28f);
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
            float floor = bottom + (d / Math.max(1f, r)) * (d / Math.max(1f, r)) * 6f;
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

    /** A sinkhole jungle: a dome 56-76 blocks across and 36-48 tall, its top 18 blocks down, with a skylight. */
    static Cut dome(int ax, int az, int ground, float u, float v, float w) {
        float rx = 28f + 10f * v, ry = 18f + 6f * w;
        int cy = ground - 18 - (int) ry;
        if (cy - ry * 0.55f < CUT_FLOOR_Y) return null;
        return new Dome(ax, az, cy, rx, ry, 7f + 5f * u, ground, (int) ((u - 0.5f) * rx * 0.6f), (int) ((w - 0.5f) * rx * 0.6f));
    }

    public record Dome(int x, int z, int cy, float rx, float ry, float skylight, int ground, int lightDx, int lightDz)
            implements Cut {
        @Override
        public float value(int px, int py, int pz) {
            float dx = (px - x) / rx, dy = (py - cy) / ry, dz = (pz - z) / rx;
            float e = ((float) Math.sqrt(dx * dx + dy * dy + dz * dz) - 1f) * ry;
            float floorY = cy - ry * 0.55f;
            e = Math.max(e, floorY - py);   // a flat floor
            // The skylight: a round opening from the dome's upper half to the sky, a little off centre.
            float v = Float.POSITIVE_INFINITY;
            if (py >= cy) {
                float sx = px - (x + lightDx), sz = pz - (z + lightDz);
                v = (float) Math.sqrt(sx * sx + sz * sz) - skylight * (1f + 0.08f * (float) Math.sin(py / 4f));
            }
            return Math.min(e, v);
        }

        @Override
        public boolean covers(int px, int pz) {
            float dx = px - x, dz = pz - z, r = reach() + 1;
            return dx * dx + dz * dz <= r * r;
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
            return "sinkhole_jungle";
        }
    }

    /** A blue hole: 20-44 blocks across in a warm shallow sea floor, 40-90 deep, widening below its lip. */
    static Cut blueHole(int ax, int az, int ground, float u, float v, float w) {
        if (ground >= 61) return null;
        float radius = 10f + 12f * v;
        int bottom = Math.max(CUT_FLOOR_Y + 5, ground - 40 - (int) (u * 50f));
        return new BlueHole(ax + (int) ((w - 0.5f) * 30), az + (int) ((u - 0.5f) * 30), radius, bottom, ground);
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

    private static long hash(long cell, CaveBiomes layer) {
        long z = cell * 0x9E3779B97F4A7C15L + (long) (layer.draw2(cell) * (1 << 24)) * 0xC2B2AE3D27D4EB4FL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
