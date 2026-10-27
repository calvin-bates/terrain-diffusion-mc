package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.List;

/**
 * Where the natural cave formations go (caves plan v1.5 part four round A): columns, stalactite and stalagmite zones,
 * boxwork and crystal rooms. Pure functions of world position and seed (anchors on jittered grids, picked by the cave
 * biome at the anchor), so every chunk agrees about a formation that crosses its border; world/CaveFormations fits
 * them to the cave air each chunk actually has.
 *
 * <p>Formations go in "suitable caves": each cave biome has a formation kind ({@link #kind}) that sets which formations
 * it gets and how often, and what they are made of. Plain stone caves (no cave biome) are the plain kind.
 */
public final class CaveFormationLayout {
    private CaveFormationLayout() {
    }

    /** Formation kinds: none, plain stone caves and the stone caves, dripstone caves, karst-like, salt, ice. */
    public static final byte K_NONE = 0, K_PLAIN = 1, K_DRIP = 2, K_KARST = 3, K_SALT = 4, K_ICE = 5;

    public static byte kind(short caveId) {
        return switch (caveId) {
            case CaveBiomes.NONE, CaveBiomes.ANDESITE_CAVES, CaveBiomes.DIORITE_CAVES, CaveBiomes.GRANITE_CAVES,
                 CaveBiomes.TUFF_CAVES, CaveBiomes.INFESTED_CAVES, CaveBiomes.SPIDER_NEST, CaveBiomes.COPPER_CAVES,
                 CaveBiomes.BREAKDOWN_CHAMBERS -> K_PLAIN;
            case CaveBiomes.DRIPSTONE_CAVES -> K_DRIP;
            case CaveBiomes.UNDERGROUND_LAKES, CaveBiomes.GLOWWORM_GROTTO, CaveBiomes.KARST_CAVES -> K_KARST;
            case CaveBiomes.SALT_CAVES -> K_SALT;
            case CaveBiomes.ICY_CAVES, CaveBiomes.FROSTFIRE_CAVES, CaveBiomes.GLACIER_CAVES -> K_ICE;
            // Their own look already (fungal, jungle, mud, lush, thermal, the rare caves and halls, the cuts).
            default -> K_NONE;
        };
    }

    // ---- Tunables: the chance a grid cell has the formation, per kind (index = kind) ----

    /** Columns: chance per column cell (14 x 24 x 14 blocks), by kind. */
    public static float[] COLUMN_CHANCE = {0f, 0.07f, 0.30f, 0.30f, 0.25f, 0.30f};
    /** Stalactites: chance per ceiling block inside a drip zone, by kind (dripstone caves have vanilla's own). */
    public static float[] DRIP_CHANCE = {0f, 0.09f, 0f, 0.16f, 0.12f, 0f};
    /** Boxwork: chance per boxwork cell (96 x 48 x 96), by kind. Starts rarest. */
    public static float[] BOXWORK_CHANCE = {0f, 0.012f, 0f, 0.025f, 0.02f, 0f};
    /** Crystal rooms: chance per crystal cell (72 x 48 x 72), by kind. Starts rarest. */
    public static float[] CRYSTAL_CHANCE = {0f, 0.01f, 0.012f, 0.012f, 0.012f, 0f};

    // ---- Columns ----

    public static final int COL_XZ = 14, COL_Y = 24;
    /** Columns only fill air runs this tall (chambers, not tunnels) and no taller (halls and chasms). */
    public static final int COL_MIN_H = 9, COL_MAX_H = 40;
    /** How far a column reaches from its anchor at most (radius, flare and wobble). */
    public static final int COL_REACH = 7;

    /**
     * A column: anchored at (x, y, z) inside a chamber, base radius r. It fills the air run holding y in every column
     * of blocks within its radius, flaring where it meets the floor and the ceiling. A broken one is a stalagmite and
     * a stalactite that don't meet.
     */
    public record Column(float x, int y, float z, float r, boolean broken, float phase, float gap, byte kind) {
        /** The radius at height y of a run from a to b (inclusive), or a negative number where it has a gap. */
        public float radius(int y, int a, int b) {
            float r0 = r + 0.45f * (float) Math.sin(y * 0.37f + phase);
            int d = Math.min(y - a, b - y);
            if (d < 3) r0 += (3 - d) * 0.6f;
            if (broken) {
                float mid = (a + b) * 0.5f + gap * (b - a) * 0.15f;
                float half = 1.5f + 0.12f * (b - a + 1);
                float off = Math.abs(y - mid);
                if (off < half) return -1f;
                r0 *= Math.min(1f, 0.3f + (off - half) * 0.25f);
            }
            return r0;
        }
    }

    /** The columns whose reach overlaps blocks x0..x1, z0..z1 with anchors in y0..y1, into out (cleared first). */
    public static void columns(long seed, CaveBiomes layer, CaveBiomes.Surface surface, int x0, int z0, int x1, int z1,
                               int y0, int y1, List<Column> out) {
        out.clear();
        int ax = Math.floorDiv(x0 - COL_REACH, COL_XZ), bx = Math.floorDiv(x1 + COL_REACH, COL_XZ);
        int az = Math.floorDiv(z0 - COL_REACH, COL_XZ), bz = Math.floorDiv(z1 + COL_REACH, COL_XZ);
        int ay = Math.floorDiv(y0, COL_Y), by = Math.floorDiv(y1, COL_Y);
        for (int cz = az; cz <= bz; cz++)
            for (int cx = ax; cx <= bx; cx++)
                for (int cy = ay; cy <= by; cy++) {
                    long h = hash(seed ^ 0xC01C01L, cx, cy, cz);
                    float u = unit(h, 0);
                    if (u >= max(COLUMN_CHANCE)) continue;   // skip the biome pick early
                    float px = cx * COL_XZ + 2 + unit(h, 8) * (COL_XZ - 4);
                    float pz = cz * COL_XZ + 2 + unit(h, 16) * (COL_XZ - 4);
                    int py = cy * COL_Y + 4 + (int) (unit(h, 24) * (COL_Y - 8));
                    byte k = kind(layer.pick((int) px, py, (int) pz, surface));
                    if (u >= COLUMN_CHANCE[k]) continue;
                    float r = 1.4f + 1.4f * unit(h, 32);
                    out.add(new Column(px, py, pz, r, unit(h, 40) < 0.2f, unit(h, 48) * 6.28f,
                            unit(h, 56) * 2f - 1f, k));
                }
    }

    // ---- Zones: boxwork and crystal rooms ----

    public static final int ZONE_BOXWORK = 0, ZONE_CRYSTAL = 1;
    private static final int[] ZONE_XZ = {96, 72}, ZONE_Y = {48, 48};

    /**
     * A zone: an ellipsoid of radius r (and {@code r * stretch} up and down) at (x, y, z) where cave surfaces get a
     * formation. Boxwork zones are stretched so they catch whole ceilings, not just the edge of one.
     */
    public record Zone(int type, int x, int y, int z, float r, float stretch, byte kind) {
        public float dist(int px, int py, int pz) {
            float dx = px - x, dy = (py - y) / stretch, dz = pz - z;
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) / r;
        }
    }

    /** The zones of a type overlapping the box, into out (cleared first). */
    public static void zones(int type, long seed, CaveBiomes layer, CaveBiomes.Surface surface, int x0, int y0, int z0,
                             int x1, int y1, int z1, List<Zone> out) {
        out.clear();
        int sxz = ZONE_XZ[type], sy = ZONE_Y[type], reach = 36, reachY = 70;
        float[] chance = type == ZONE_BOXWORK ? BOXWORK_CHANCE : CRYSTAL_CHANCE;
        int ax = Math.floorDiv(x0 - reach, sxz), bx = Math.floorDiv(x1 + reach, sxz);
        int az = Math.floorDiv(z0 - reach, sxz), bz = Math.floorDiv(z1 + reach, sxz);
        int ay = Math.floorDiv(y0 - reachY, sy), by = Math.floorDiv(y1 + reachY, sy);
        for (int cz = az; cz <= bz; cz++)
            for (int cx = ax; cx <= bx; cx++)
                for (int cy = ay; cy <= by; cy++) {
                    Zone z = zone(type, seed, layer, surface, cx, cy, cz, chance);
                    if (z == null) continue;
                    float ry = z.r * z.stretch;
                    if (z.x + z.r < x0 || z.x - z.r > x1 || z.z + z.r < z0 || z.z - z.r > z1
                            || z.y + ry < y0 || z.y - ry > y1) continue;
                    out.add(z);
                }
    }

    /** The zone of one grid cell, or null. */
    public static Zone zone(int type, long seed, CaveBiomes layer, CaveBiomes.Surface surface, int cx, int cy, int cz,
                            float[] chance) {
        int sxz = ZONE_XZ[type], sy = ZONE_Y[type];
        long h = hash(seed ^ (type == ZONE_BOXWORK ? 0xB0C5L : 0xC4157AL), cx, cy, cz);
        float u = unit(h, 0);
        if (u >= max(chance)) return null;   // skip the biome pick early
        int px = cx * sxz + 16 + (int) (unit(h, 8) * (sxz - 32));
        int pz = cz * sxz + 16 + (int) (unit(h, 16) * (sxz - 32));
        int py = cy * sy + 12 + (int) (unit(h, 24) * (sy - 24));
        byte k = kind(layer.pick(px, py, pz, surface));
        if (u >= chance[k]) return null;
        float r = type == ZONE_BOXWORK ? 22f + 12f * unit(h, 32) : 7f + 6f * unit(h, 32);
        return new Zone(type, px, py, pz, r, type == ZONE_BOXWORK ? 2f : 1f, k);
    }

    /** The grid cell size of a zone type: xz, y. */
    public static int zoneXZ(int type) {
        return ZONE_XZ[type];
    }

    public static int zoneY(int type) {
        return ZONE_Y[type];
    }

    public static float[] zoneChance(int type) {
        return type == ZONE_BOXWORK ? BOXWORK_CHANCE : CRYSTAL_CHANCE;
    }

    /**
     * Boxwork: half the distance between the nearest and second-nearest points of a jittered 4-block grid at (x, z)
     * (0 on the honeycomb's walls). The fins are where this is under about half a block.
     */
    public static float boxworkEdge(long seed, float x, float z) {
        final float cell = 4f;
        int gx = (int) Math.floor(x / cell), gz = (int) Math.floor(z / cell);
        float d1 = Float.MAX_VALUE, d2 = Float.MAX_VALUE;
        for (int oz = -1; oz <= 1; oz++)
            for (int ox = -1; ox <= 1; ox++) {
                long h = hash(seed ^ 0xB0E5L, gx + ox, 0, gz + oz);
                float px = (gx + ox + 0.15f + 0.7f * unit(h, 0)) * cell, pz = (gz + oz + 0.15f + 0.7f * unit(h, 8)) * cell;
                float dx = x - px, dz = z - pz, d = (float) Math.sqrt(dx * dx + dz * dz);
                if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) d2 = d;
            }
        return (d2 - d1) * 0.5f;
    }

    private static float max(float[] v) {
        float m = 0f;
        for (float x : v) m = Math.max(m, x);
        return m;
    }

    // ---- Hashing ----

    public static long hash(long seed, int a, int b, int c) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h = mix(h + a * 0xC2B2AE3D27D4EB4FL);
        h = mix(h + b * 0x165667B19E3779F9L);
        return mix(h + c * 0x27D4EB2F165667C5L);
    }

    /** 8 bits of h from bit {@code shift}, as [0, 1). */
    public static float unit(long h, int shift) {
        return ((h >>> shift) & 0xFF) / 256f;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
