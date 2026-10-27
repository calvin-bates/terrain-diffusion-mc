package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.List;

/**
 * Where the natural cave formations go (caves plan v1.5 part four): round A's columns, stalactite and stalagmite
 * zones, boxwork and crystal rooms; round B's flowstone zones and floor patches (rimstone pools, waterfalls with plunge
 * pools, travertine terraces in thermal caves). Pure functions of world position and seed (anchors on jittered grids, picked by the cave
 * biome at the anchor), so every chunk agrees about a formation that crosses its border; world/CaveFormations fits
 * them to the cave air each chunk actually has.
 *
 * <p>Formations go in "suitable caves": each cave biome has a formation kind ({@link #kind}) that sets which formations
 * it gets and how often, and what they are made of. Plain stone caves (no cave biome) are the plain kind.
 */
public final class CaveFormationLayout {
    private CaveFormationLayout() {
    }

    /** Formation kinds: none, plain stone caves and the stone caves, dripstone caves, karst-like, salt, ice, thermal. */
    public static final byte K_NONE = 0, K_PLAIN = 1, K_DRIP = 2, K_KARST = 3, K_SALT = 4, K_ICE = 5, K_THERMAL = 6;

    public static byte kind(short caveId) {
        return switch (caveId) {
            case CaveBiomes.NONE, CaveBiomes.ANDESITE_CAVES, CaveBiomes.DIORITE_CAVES, CaveBiomes.GRANITE_CAVES,
                 CaveBiomes.TUFF_CAVES, CaveBiomes.INFESTED_CAVES, CaveBiomes.SPIDER_NEST, CaveBiomes.COPPER_CAVES,
                 CaveBiomes.BREAKDOWN_CHAMBERS -> K_PLAIN;
            case CaveBiomes.DRIPSTONE_CAVES -> K_DRIP;
            case CaveBiomes.UNDERGROUND_LAKES, CaveBiomes.GLOWWORM_GROTTO, CaveBiomes.KARST_CAVES -> K_KARST;
            case CaveBiomes.SALT_CAVES -> K_SALT;
            case CaveBiomes.ICY_CAVES, CaveBiomes.GLACIER_CAVES -> K_ICE;   // frostfire caves have Terralith's own pillars
            case CaveBiomes.THERMAL_CAVES -> K_THERMAL;   // travertine terraces only (round B)
            // Their own look already (fungal, jungle, mud, lush, thermal, the rare caves and halls, the cuts).
            default -> K_NONE;
        };
    }

    // ---- Tunables: the chance a grid cell has the formation, per kind (index = kind) ----

    /** Columns: chance per column cell (14 x 24 x 14 blocks), by kind. */
    public static float[] COLUMN_CHANCE = {0f, 0.07f, 0.30f, 0.30f, 0.25f, 0.30f, 0f};
    /** Stalactites: chance per ceiling block inside a drip zone, by kind (dripstone caves have vanilla's own). */
    public static float[] DRIP_CHANCE = {0f, 0.09f, 0f, 0.16f, 0.12f, 0f, 0f};
    /** Boxwork: chance per boxwork cell (96 x 48 x 96), by kind. Starts rarest. */
    public static float[] BOXWORK_CHANCE = {0f, 0.012f, 0f, 0.025f, 0.02f, 0f, 0f};
    /** Crystal rooms: chance per crystal cell (72 x 48 x 72), by kind. Starts rarest. */
    public static float[] CRYSTAL_CHANCE = {0f, 0.01f, 0.012f, 0.012f, 0.012f, 0f, 0f};
    /** Flowstone (round B): chance per flowstone cell (64 x 40 x 64), by kind. */
    public static float[] FLOWSTONE_CHANCE = {0f, 0.05f, 0.18f, 0.16f, 0.06f, 0f, 0f};
    /** Rimstone pools (round B): chance per chunk and 32-block slab, by kind. */
    public static float[] RIMSTONE_CHANCE = {0f, 0.025f, 0.10f, 0.10f, 0.04f, 0f, 0f};
    /** Waterfalls into plunge pools (round B): chance per chunk and 32-block slab, by kind. */
    public static float[] WATERFALL_CHANCE = {0f, 0.03f, 0.04f, 0.09f, 0f, 0f, 0f};
    /** Travertine terraces (round B): chance per chunk and 32-block slab, by kind (thermal caves only). */
    public static float[] TRAVERTINE_CHANCE = {0f, 0f, 0f, 0f, 0f, 0f, 0.3f};

    // ---- Columns ----

    public static final int COL_XZ = 14, COL_Y = 24;
    /** Columns only fill air runs this tall (chambers, not tunnels) and no taller (halls and chasms). */
    public static final int COL_MIN_H = 9, COL_MAX_H = 60;
    /** How far a column reaches from its anchor at most (radius, flare and wobble). */
    public static final int COL_REACH = 9;

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
                    float r = 1.8f + 2.2f * unit(h, 32);
                    out.add(new Column(px, py, pz, r, unit(h, 40) < 0.2f, unit(h, 48) * 6.28f,
                            unit(h, 56) * 2f - 1f, k));
                }
    }

    // ---- Zones: boxwork and crystal rooms ----

    public static final int ZONE_BOXWORK = 0, ZONE_CRYSTAL = 1, ZONE_FLOWSTONE = 2;
    private static final int[] ZONE_XZ = {96, 72, 64}, ZONE_Y = {48, 48, 40};
    private static final long[] ZONE_SALT = {0xB0C5L, 0xC4157AL, 0xF105L};

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
        float[] chance = zoneChance(type);
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
        long h = hash(seed ^ ZONE_SALT[type], cx, cy, cz);
        float u = unit(h, 0);
        if (u >= max(chance)) return null;   // skip the biome pick early
        int px = cx * sxz + 16 + (int) (unit(h, 8) * (sxz - 32));
        int pz = cz * sxz + 16 + (int) (unit(h, 16) * (sxz - 32));
        int py = cy * sy + 12 + (int) (unit(h, 24) * (sy - 24));
        byte k = kind(layer.pick(px, py, pz, surface));
        if (u >= chance[k]) return null;
        float r = type == ZONE_BOXWORK ? 22f + 12f * unit(h, 32) : type == ZONE_FLOWSTONE ? 14f + 10f * unit(h, 32)
                : 7f + 6f * unit(h, 32);
        return new Zone(type, px, py, pz, r, type == ZONE_BOXWORK ? 2f : type == ZONE_FLOWSTONE ? 1.5f : 1f, k);
    }

    /** The grid cell size of a zone type: xz, y. */
    public static int zoneXZ(int type) {
        return ZONE_XZ[type];
    }

    public static int zoneY(int type) {
        return ZONE_Y[type];
    }

    public static float[] zoneChance(int type) {
        return type == ZONE_BOXWORK ? BOXWORK_CHANCE : type == ZONE_FLOWSTONE ? FLOWSTONE_CHANCE : CRYSTAL_CHANCE;
    }

    // ---- Floor patches (round B): rimstone pools, waterfalls with plunge pools, travertine terraces ----

    public static final int P_RIMSTONE = 0, P_WATERFALL = 1, P_TRAVERTINE = 2;
    /** Patches are tried once per chunk and slab of this many blocks of height. */
    public static final int PATCH_SLAB = 32;

    /**
     * A floor patch, kept inside one chunk so no chunk needs another's blocks: centred at (x, y, z) (y a height in its
     * slab; the patch takes the cave floor of its centre column nearest it), radius r.
     */
    public record Patch(int type, int x, int y, int z, float r, long hash, byte kind) {
    }

    /** The patch of chunk (chunkX, chunkZ) in slab {@code slab} (blocks slab * 32 to slab * 32 + 31), or null. */
    public static Patch patch(long seed, CaveBiomes layer, CaveBiomes.Surface surface, int chunkX, int chunkZ, int slab) {
        long h = hash(seed ^ 0x9A7C4L, chunkX, slab, chunkZ);
        float u = unit(h, 0);
        if (u >= maxPatch()) return null;   // skip the biome pick early
        int x = chunkX * 16 + 5 + (int) (unit(h, 8) * 6), z = chunkZ * 16 + 5 + (int) (unit(h, 16) * 6);
        int y = slab * PATCH_SLAB + 2 + (int) (unit(h, 24) * (PATCH_SLAB - 4));
        byte k = kind(layer.pick(x, y, z, surface));
        float rim = RIMSTONE_CHANCE[k], fall = WATERFALL_CHANCE[k], trav = TRAVERTINE_CHANCE[k];
        int type;
        if (u < rim) type = P_RIMSTONE;
        else if (u < rim + fall) type = P_WATERFALL;
        else if (u < rim + fall + trav) type = P_TRAVERTINE;
        else return null;
        float r = switch (type) {
            case P_WATERFALL -> 2f + 1.3f * unit(h, 32);
            case P_TRAVERTINE -> 4f + 1.5f * unit(h, 32);
            default -> 3.5f + 2f * unit(h, 32);
        };
        return new Patch(type, x, y, z, r, h, k);
    }

    /** The highest total patch chance of any kind. */
    private static float maxPatch() {
        float m = 0f;
        for (int k = 0; k < RIMSTONE_CHANCE.length; k++)
            m = Math.max(m, RIMSTONE_CHANCE[k] + WATERFALL_CHANCE[k] + TRAVERTINE_CHANCE[k]);
        return m;
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
