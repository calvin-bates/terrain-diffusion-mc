package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Per-block details inside the giant halls of part three round C (caves plan v1.5): the bare stone pillars of the
 * deep dark incursion and the crystal beams of the giant selenite chamber. Both are solid shapes kept standing in the
 * hall's air ({@link CaveShapes} takes the maximum with them where the hall is fully in), and world/CaveStone paints
 * them (polished basalt pillars, calcite beams).
 *
 * <p>Everything is a pure function of world position, the seed and the hall's centre and size, so it is the same
 * whichever chunk or tile asks. Values are densities in blocks: positive inside the shape.
 */
public final class CaveHalls {
    private CaveHalls() {
    }

    // ---- The halls ----

    /**
     * A giant hall: an ellipsoid centred on its region cell's anchor ({@code CaveBiomes.hallY}), radii rx and ry, its
     * floor cut flat {@code floor} blocks below the centre, with per-block details ({@code CaveBiomes.hallDetail}).
     */
    /** A hall's field ends this far outside its shell (+inf beyond), so only volumes near it need to list it. */
    public static final float SHELL_REACH = 16f;

    public record Hall(short id, long cell, int cx, int cy, int cz, float rx, float ry, float floor, int detail) {
        /**
         * Whether a point is the hall's to shape: inside its unwarped region cell (a city cavern: inside its box).
         * Every volume that holds the point lists the hall (CaveHalls.near), so confining the hall's field to this
         * keeps every chunk and query in agreement.
         */
        public boolean owns(int x, int y, int z) {
            if (cell == Long.MIN_VALUE)
                return Math.abs(x - cx) <= rx && Math.abs(z - cz) <= rx && y >= cy - 2 && y <= cy + ry + 2;
            return CaveBiomes.unwarpedCell(x, y, z) == cell;
        }

        /** The hall's density at a point: negative inside (blocks, roughly), +inf where it isn't the hall's. */
        public float value(int x, int y, int z) {
            if (!owns(x, y, z)) return Float.POSITIVE_INFINITY;
            float dx = (x - cx) / rx, dy = (y - cy) / ry, dz = (z - cz) / rx;
            float v = ((float) Math.sqrt(dx * dx + dy * dy + dz * dz) - 1f) * ry;
            v = Math.max(v, (cy - floor) - y);
            return v > SHELL_REACH ? Float.POSITIVE_INFINITY : v;
        }

        /** Distance from the centre in hall radii (1 on the ellipsoid, before the floor cut). */
        public float dist(int x, int y, int z) {
            float dx = (x - cx) / rx, dy = (y - cy) / ry, dz = (z - cz) / rx;
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        /**
         * Whether the box overlaps where the hall's field is finite: the ellipsoid scaled by 1 + SHELL_REACH / ry
         * (the field is in vertical-radius units, so it reaches rx * SHELL_REACH / ry blocks sideways), plus 2.
         */
        public boolean overlaps(int x0, int y0, int z0, int x1, int y1, int z1) {
            float px = rx * (1f + SHELL_REACH / ry) + 2f, py = ry + SHELL_REACH + 2f;
            return x1 >= cx - px && x0 <= cx + px && z1 >= cz - px && z0 <= cz + px && y1 >= cy - py && y0 <= cy + py;
        }

        public boolean near(int x, int y, int z, int pad) {
            return Math.abs(x - cx) <= rx + pad && Math.abs(z - cz) <= rx + pad && Math.abs(y - cy) <= ry + pad;
        }

        /** The details at a point: positive where pillars or beams keep rock standing, -inf where none. */
        public float detail(long seed, int x, int y, int z) {
            if (!owns(x, y, z)) return Float.NEGATIVE_INFINITY;
            if (detail == CaveBiomes.DETAIL_PILLARS) return pillar(seed, x, y, z, cx, cy, cz, rx, ry);
            if (detail == CaveBiomes.DETAIL_BEAMS) return beam(beams(seed, cx, cy, cz, rx, ry), x, y, z);
            return Float.NEGATIVE_INFINITY;
        }
    }

    /** The hall of one unwarped region cell, or null. */
    public static Hall of(CaveBiomes layer, CaveBiomes.Surface surface, long cell) {
        short id = layer.hallId(cell, surface);
        if (id == CaveBiomes.NONE) return null;
        return new Hall(id, cell, CaveBiomes.anchorX(cell), CaveBiomes.hallY(cell, id), CaveBiomes.anchorZ(cell),
                CaveBiomes.hallRX(id), CaveBiomes.hallRY(id), CaveBiomes.hallFloor(id), CaveBiomes.hallDetail(id));
    }

    /** The halls of every unwarped region cell overlapping the box, into {@code out} (cleared first). */
    public static void near(CaveBiomes layer, CaveBiomes.Surface surface, int x0, int y0, int z0, int x1, int y1, int z1,
                            java.util.List<Hall> out) {
        out.clear();
        int ax = Math.floorDiv(x0, CaveBiomes.REGION_XZ), bx = Math.floorDiv(x1, CaveBiomes.REGION_XZ);
        int ay = Math.floorDiv(y0, CaveBiomes.REGION_Y), by = Math.floorDiv(y1, CaveBiomes.REGION_Y);
        int az = Math.floorDiv(z0, CaveBiomes.REGION_XZ), bz = Math.floorDiv(z1, CaveBiomes.REGION_XZ);
        for (int cz = az; cz <= bz; cz++)
            for (int cx = ax; cx <= bx; cx++)
                for (int cy = ay; cy <= by; cy++) {
                    Hall h = of(layer, surface, CaveBiomes.unwarpedCell(cx * CaveBiomes.REGION_XZ, cy * CaveBiomes.REGION_Y,
                            cz * CaveBiomes.REGION_XZ));
                    // Listed whenever the box can see any of its field (the shell plus SHELL_REACH).
                    if (h != null && h.overlaps(x0, y0, z0, x1, y1, z1)) out.add(h);
                }
        cities(layer, surface, x0, y0, z0, x1, y1, z1, out);
    }

    // ---- Ancient city caverns ----

    /**
     * Ancient cities (vanilla's structure set: random spread, spacing 24 chunks, separation 8, salt 20083232) start
     * at a chunk Terra can compute exactly. Where that chunk is under a mountain, a giant cavern opens around it: a
     * dome over a flat floor at the city's floor level, its biome the city's deep dark (the only biome cities may
     * start in), so the city stands in a cave instead of carving boxes out of the rock.
     */
    public static final int CITY_SPACING = 24, CITY_SEPARATION = 8, CITY_SALT = 20083232;
    /** The cavern: radius sideways, height of the dome over the floor, the floor's Y (cities stand on Y -51). */
    public static final float CITY_RX = 104f, CITY_RY = 46f;
    public static final int CITY_FLOOR_Y = -52;

    /** The chunk an ancient city may start in for its placement grid cell (gx, gz), as vanilla computes it. */
    public static long cityChunk(long seed, int gx, int gz) {
        java.util.Random r = new java.util.Random((long) gx * 341873128712L + (long) gz * 132897987541L + seed + CITY_SALT);
        int limit = CITY_SPACING - CITY_SEPARATION;
        int ox = r.nextInt(limit), oz = r.nextInt(limit);
        return ((long) (gx * CITY_SPACING + ox) << 32) | ((gz * CITY_SPACING + oz) & 0xFFFFFFFFL);
    }

    /** The city cavern of placement cell (gx, gz), or null when its site isn't under a mountain. */
    public static Hall city(CaveBiomes layer, CaveBiomes.Surface surface, int gx, int gz) {
        long key = ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
        CityMemo m = CITY_MEMO.get();
        int slot = (int) ((key ^ (key >>> 29)) & 31);
        if (m.layer[slot] == layer && m.key[slot] == key && m.done[slot]) return m.hall[slot];
        long ch = cityChunk(layer.seed(), gx, gz);
        int cx = (int) (ch >> 32) * 16 + 8, cz = (int) ch * 16 + 8;
        Hall h = CaveBiomes.isMountain(surface, cx, cz)
                ? new Hall(CaveBiomes.DEEP_DARK_CITY, Long.MIN_VALUE, cx, CITY_FLOOR_Y, cz, CITY_RX, CITY_RY, 0f,
                CaveBiomes.DETAIL_NONE) : null;
        m.layer[slot] = layer;
        m.key[slot] = key;
        m.hall[slot] = h;
        m.done[slot] = true;
        return h;
    }

    private static final class CityMemo {
        final CaveBiomes[] layer = new CaveBiomes[32];
        final long[] key = new long[32];
        final Hall[] hall = new Hall[32];
        final boolean[] done = new boolean[32];
    }

    private static final ThreadLocal<CityMemo> CITY_MEMO = ThreadLocal.withInitial(CityMemo::new);

    /** The city cavern whose footprint holds column (x, z), or null. */
    public static Hall cityNear(CaveBiomes layer, CaveBiomes.Surface surface, int x, int z) {
        int span = CITY_SPACING * 16;
        int gx0 = Math.floorDiv((int) (x - CITY_RX - 16), span), gx1 = Math.floorDiv((int) (x + CITY_RX), span);
        int gz0 = Math.floorDiv((int) (z - CITY_RX - 16), span), gz1 = Math.floorDiv((int) (z + CITY_RX), span);
        for (int gz = gz0; gz <= gz1; gz++)
            for (int gx = gx0; gx <= gx1; gx++) {
                long ch = cityChunk(layer.seed(), gx, gz);
                int cx = (int) (ch >> 32) * 16 + 8, cz = (int) ch * 16 + 8;
                if (Math.abs(x - cx) > CITY_RX + 1 || Math.abs(z - cz) > CITY_RX + 1) continue;
                Hall h = city(layer, surface, gx, gz);
                if (h != null) return h;
            }
        return null;
    }

    /** City caverns overlapping a box, added to out. */
    static void cities(CaveBiomes layer, CaveBiomes.Surface surface, int x0, int y0, int z0, int x1, int y1, int z1,
                       java.util.List<Hall> out) {
        if (y0 > CITY_FLOOR_Y + CITY_RY + 2 || y1 < CITY_FLOOR_Y - 2) return;
        int span = CITY_SPACING * 16;
        int gx0 = Math.floorDiv((int) (x0 - CITY_RX - 16), span), gx1 = Math.floorDiv((int) (x1 + CITY_RX), span);
        int gz0 = Math.floorDiv((int) (z0 - CITY_RX - 16), span), gz1 = Math.floorDiv((int) (z1 + CITY_RX), span);
        for (int gz = gz0; gz <= gz1; gz++)
            for (int gx = gx0; gx <= gx1; gx++) {
                long ch = cityChunk(layer.seed(), gx, gz);
                int cx = (int) (ch >> 32) * 16 + 8, cz = (int) ch * 16 + 8;
                if (cx + CITY_RX < x0 || cx - CITY_RX > x1 || cz + CITY_RX < z0 || cz - CITY_RX > z1) continue;
                Hall h = city(layer, surface, gx, gz);
                if (h != null) out.add(h);   // owns() is inside this box test, so every overlapping volume lists it
            }
    }

    // ---- Incursion pillars ----

    /** Pillars sit on a jittered grid of this spacing (blocks). */
    public static final int PILLAR_CELL = 18;

    /**
     * The pillars at (x, y, z) in an incursion hall centred at (cx, cy, cz) with radii rx, ry: positive inside a
     * pillar. Pillars are 3 to 7 blocks in radius, wobble a little, flare where they meet the floor and the ceiling, and
     * stay away from the hall's walls; about one in five is broken (a stalagmite and a stalactite that don't meet).
     */
    public static float pillar(long seed, int x, int y, int z, float cx, float cy, float cz, float rx, float ry) {
        int gx = Math.floorDiv(x, PILLAR_CELL), gz = Math.floorDiv(z, PILLAR_CELL);
        float best = Float.NEGATIVE_INFINITY;
        for (int oz = -1; oz <= 1; oz++) {
            for (int ox = -1; ox <= 1; ox++) {
                long h = hash(seed, gx + ox, gz + oz);
                float px = (gx + ox) * PILLAR_CELL + 3 + unit(h, 0) * (PILLAR_CELL - 6);
                float pz = (gz + oz) * PILLAR_CELL + 3 + unit(h, 8) * (PILLAR_CELL - 6);
                float hx = (px - cx) / rx, hz = (pz - cz) / rx;
                float hd = hx * hx + hz * hz;
                if (hd > 0.62f) continue;   // within about 0.79 rx of the centre
                if (unit(h, 16) < 0.12f) continue;   // some cells have none
                float ddx = x - px, ddz = z - pz;
                float d = (float) Math.sqrt(ddx * ddx + ddz * ddz);
                float r = 3f + 4f * unit(h, 24);
                r += 0.6f * (float) Math.sin(y * 0.29f + unit(h, 32) * 6.28f);
                // Distance to the hall's top or bottom at the pillar's column: flare the last 8 blocks.
                float halfH = ry * (float) Math.sqrt(Math.max(0f, 1f - hd));
                float dv = halfH - Math.abs(y - cy);
                if (dv < 8f) r += (8f - Math.max(0f, dv)) * 0.55f;
                if (unit(h, 40) < 0.2f) {
                    // Broken: a gap of 6 to 14 blocks somewhere in the middle third.
                    float gapMid = cy + (unit(h, 48) - 0.5f) * halfH * 0.6f;
                    float gapHalf = 3f + 4f * unit(h, 56);
                    float off = Math.abs(y - gapMid);
                    if (off < gapHalf) continue;
                    r *= Math.min(1f, 0.35f + (off - gapHalf) * 0.12f);   // tapering tips
                }
                best = Math.max(best, r - d);
            }
        }
        return best;
    }

    // ---- Selenite beams ----

    /** A crystal beam: a long prism through (px, py, pz) along unit (dx, dy, dz), u and v across it. */
    public record Beam(float px, float py, float pz, float dx, float dy, float dz, float ux, float uz,
                       float vx, float vy, float vz, float half, float wide, float thin) {
        /** Positive inside the beam; its last {@code wide * 2.5} blocks taper to a point. */
        float value(int x, int y, int z) {
            float rx = x - px, ry = y - py, rz = z - pz;
            float t = rx * dx + ry * dy + rz * dz;
            float at = Math.abs(t);
            if (at > half) return Float.NEGATIVE_INFINITY;
            float a = Math.abs(rx * ux + rz * uz);
            float b = Math.abs(rx * vx + ry * vy + rz * vz);
            float taper = Math.min(1f, (half - at) / (wide * 2.5f));
            return Math.min(wide * taper - a, thin * taper - b);
        }

        /** Whether (x, y, z) is in the beam's tip (the last 1.5 blocks), for the amethyst ends. */
        boolean tip(int x, int y, int z) {
            float t = (x - px) * dx + (y - py) * dy + (z - pz) * dz;
            return Math.abs(t) > half - 1.5f;
        }
    }

    private static final ThreadLocal<long[]> BEAM_KEY = ThreadLocal.withInitial(() -> new long[]{Long.MIN_VALUE});
    private static final ThreadLocal<Beam[][]> BEAM_MEMO = ThreadLocal.withInitial(() -> new Beam[1][]);

    /**
     * The beams of a selenite hall centred at (cx, cy, cz) (rounded: the hall's anchor) with radii rx, ry: 14 to 22
     * prisms 40 to 100 blocks long, 2 to 5 blocks across, crossing the chamber mostly sideways, some steeply.
     */
    public static Beam[] beams(long seed, int cx, int cy, int cz, float rx, float ry) {
        long key = ((long) cx * 73856093L) ^ ((long) cy * 19349663L) ^ ((long) cz * 83492791L) ^ seed;
        long[] k = BEAM_KEY.get();
        Beam[][] memo = BEAM_MEMO.get();
        if (k[0] == key && memo[0] != null) return memo[0];
        long h = hash(seed ^ 0x5E1E417EL, cx, cz) ^ cy * 0x9E3779B97F4A7C15L;
        h = mix(h);
        int n = 14 + (int) (unit(h, 0) * 9);
        Beam[] out = new Beam[n];
        for (int i = 0; i < n; i++) {
            long g = mix(h + (i + 1) * 0xC2B2AE3D27D4EB4FL);
            // A point inside the hall (most of the way out), a direction (yaw anywhere, pitch mostly gentle).
            float ax = (unit(g, 0) * 2f - 1f), ay = (unit(g, 8) * 2f - 1f), az = (unit(g, 16) * 2f - 1f);
            float ar = (float) Math.sqrt(ax * ax + ay * ay + az * az) + 1e-3f;
            float reach = 0.75f * (float) Math.cbrt(unit(g, 24));
            float px = cx + ax / ar * reach * rx, py = cy + ay / ar * reach * ry * 0.7f, pz = cz + az / ar * reach * rx;
            float yaw = unit(g, 32) * 6.2831855f;
            float p = unit(g, 40);
            float pitch = (p < 0.8f ? (p / 0.8f - 0.5f) * 0.9f : (p - 0.8f) / 0.2f * 0.7f + 0.55f);
            float cp = (float) Math.cos(pitch);
            float dx = (float) Math.cos(yaw) * cp, dy = (float) Math.sin(pitch), dz = (float) Math.sin(yaw) * cp;
            // u: horizontal across the beam; v = d x u.
            float ul = (float) Math.sqrt(dx * dx + dz * dz) + 1e-6f;
            float ux = -dz / ul, uz = dx / ul;
            float vx = dy * uz, vy = dz * ux - dx * uz, vz = -dy * ux;
            float half = 20f + unit(g, 48) * 30f;
            float wide = 2f + unit(g, 56) * 3f;
            float thin = wide * (0.55f + 0.3f * unit(g, 4));
            out[i] = new Beam(px, py, pz, dx, dy, dz, ux, uz, vx, vy, vz, half, wide, thin);
        }
        k[0] = key;
        memo[0] = out;
        return out;
    }

    /** Positive inside any beam. */
    public static float beam(Beam[] beams, int x, int y, int z) {
        float best = Float.NEGATIVE_INFINITY;
        for (Beam b : beams) best = Math.max(best, b.value(x, y, z));
        return best;
    }

    /** The beam a solid block belongs to (null if none). */
    public static Beam beamAt(Beam[] beams, int x, int y, int z) {
        for (Beam b : beams) if (b.value(x, y, z) >= 0f) return b;
        return null;
    }

    public static boolean isTip(Beam b, int x, int y, int z) {
        return b.tip(x, y, z);
    }

    private static long hash(long seed, int a, int b) {
        long h = seed ^ 0x9E3779B97F4A7C15L;
        h = mix(h + a * 0xC2B2AE3D27D4EB4FL);
        return mix(h + b * 0x165667B19E3779F9L);
    }

    /** 8 bits of h from bit {@code shift}, as [0, 1). */
    private static float unit(long h, int shift) {
        return ((h >>> shift) & 0xFF) / 256f;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
