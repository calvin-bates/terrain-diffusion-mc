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
    private static final int SINK_REACH = 80;
    /** Depth under the anchor's ground at which a site asks the cave layer for its region. */
    private static final int PROBE_DEPTH = 44;
    /** Widest a site gets (cenote radius plus its chamber), for finding sites near an area. */
    public static final int MAX_REACH = 240;

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
        if (id != CaveBiomes.KARST_CAVES && id != CaveBiomes.UNDERGROUND_LAKES && id != CaveBiomes.GLOWWORM_GROTTO)
            return mouth(ax, az, surface, h, u, v, w);
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

    /** A canyon ravine: 180-340 blocks long, 18-40 wide at the top, 90-180 deep, wandering, narrowing downwards. */
    static Cut ravine(int ax, int az, int ground, float u, float v, float w) {
        float angle = (float) (u * Math.PI);
        float length = 180f + v * 160f, halfWidth = 9f + w * 11f;
        int bottom = Math.max(CUT_FLOOR_Y, ground - 90 - (int) (u * 90f));
        return new Ravine(ax, az, (float) Math.cos(angle), (float) Math.sin(angle), length, halfWidth, bottom, ground,
                8f + 12f * w, 50f + 50f * v, v * 6.28f);
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
        return new Dome(ax, az, cy, rx, ry, 12f + 10f * u, ground, (int) ((u - 0.5f) * rx * 0.6f), (int) ((w - 0.5f) * rx * 0.6f));
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

    private static long hash(long cell, CaveBiomes layer) {
        long z = cell * 0x9E3779B97F4A7C15L + (long) (layer.draw2(cell) * (1 << 24)) * 0xC2B2AE3D27D4EB4FL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
