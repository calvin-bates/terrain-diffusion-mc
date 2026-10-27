package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveBiomes;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveShapes;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The explorer's cave views (caves plan v1.5): an overview of the cave biome layer at one Y over the detail map's
 * window (one pixel per native pixel), and block-resolution horizontal slices and vertical sections of the caves
 * themselves. Slices and sections use exactly the game's functions (tiles, cave reference ground, roof, cave field,
 * cave biome layer, the dry-cave rule), so what they show is what generates, apart from vanilla's carver tunnels,
 * ores and features.
 */
public final class CaveView {
    private CaveView() {
    }

    // ---- Codes shared with the explorer page ----

    /** Material codes. */
    public static final byte SKY = 0, INLAND_WATER = 1, SEA = 2, ROCK = 3, AIR = 4, FLOODED = 5, LAVA = 6;
    /** Biome codes: 0 plain stone caves, 1 the surface layer (no cave biome this close to the ground), 2+ cave biomes. */
    public static final byte PLAIN = 0, SURFACE_LAYER = 1;
    /** Overview-only material: above the ground at this Y. */
    public static final byte ABOVE = 7;
    /** Not a cave biome: the surface layer. */
    private static final short SURFACE_CAVE = Short.MIN_VALUE;

    private static final List<Short> IDS = new ArrayList<>(CaveBiomes.NAMES.keySet());

    private static byte code(short caveId) {
        if (caveId < 0) return PLAIN;
        int k = IDS.indexOf(caveId);
        return k < 0 ? PLAIN : (byte) (2 + k);
    }

    /** Colours of the cave biomes (rock); the page darkens them for cave air. */
    private static final Map<Short, String> COLORS = new LinkedHashMap<>();
    static {
        COLORS.put(CaveBiomes.ANDESITE_CAVES, "#9a9c9e");
        COLORS.put(CaveBiomes.DIORITE_CAVES, "#e2e0da");
        COLORS.put(CaveBiomes.GRANITE_CAVES, "#b7735c");
        COLORS.put(CaveBiomes.TUFF_CAVES, "#6f7562");
        COLORS.put(CaveBiomes.DRIPSTONE_CAVES, "#9a6b45");
        COLORS.put(CaveBiomes.LUSH_CAVES, "#6fae3c");
        COLORS.put(CaveBiomes.FUNGAL_CAVES, "#b04ad0");
        COLORS.put(CaveBiomes.UNDERGROUND_JUNGLE, "#2f8f3a");
        COLORS.put(CaveBiomes.ICY_CAVES, "#9fd8f0");
        COLORS.put(CaveBiomes.FROSTFIRE_CAVES, "#5a8fe0");
        COLORS.put(CaveBiomes.THERMAL_CAVES, "#e3c34a");
        COLORS.put(CaveBiomes.INFESTED_CAVES, "#7d7a6a");
        COLORS.put(CaveBiomes.SPIDER_NEST, "#c8c8c8");
        COLORS.put(CaveBiomes.COPPER_CAVES, "#d9844d");
        COLORS.put(CaveBiomes.SALT_CAVES, "#f2c9d4");
        COLORS.put(CaveBiomes.MUD_CAVES, "#5b4a3e");
        COLORS.put(CaveBiomes.BREAKDOWN_CHAMBERS, "#8a8f99");
        COLORS.put(CaveBiomes.UNDERGROUND_LAKES, "#4f86c6");
        COLORS.put(CaveBiomes.GLOWWORM_GROTTO, "#59d0e8");
        COLORS.put(CaveBiomes.KARST_CAVES, "#d8d3c0");
        COLORS.put(CaveBiomes.MOLTEN_CAVERN, "#c2410c");
        COLORS.put(CaveBiomes.MANTLE_CAVE, "#3b3330");
        COLORS.put(CaveBiomes.CRYSTALLINE_CHASM, "#b48ae8");
        COLORS.put(CaveBiomes.DEEP_DARK, "#0f4f5a");
        COLORS.put(CaveBiomes.DEEP_DARK_CITY, "#0a3d47");
        COLORS.put(CaveBiomes.LAVA_TUBES, "#5a3a32");
        COLORS.put(CaveBiomes.SLOT_CANYON_CAVES, "#d9a35e");
        COLORS.put(CaveBiomes.GLACIER_CAVES, "#6ec6f0");
        COLORS.put(CaveBiomes.GIANT_GEODE_HALL, "#8f5bd6");
        COLORS.put(CaveBiomes.CANYON_RAVINE, "#8a6e55");
        COLORS.put(CaveBiomes.SINKHOLE_JUNGLE, "#3f9a3a");
        COLORS.put(CaveBiomes.BLUE_HOLE, "#1f5fbf");
        COLORS.put(CaveBiomes.DEEP_SHAFT, "#4a4a58");
        COLORS.put(CaveBiomes.DEEP_DARK_FOREST, "#167d6a");
        COLORS.put(CaveBiomes.DEEP_DARK_INCURSION, "#06323a");
        COLORS.put(CaveBiomes.GIANT_SELENITE_BEAMS, "#f0ead2");
    }

    public static void color(short id, String hex) {
        COLORS.put(id, hex);
    }

    /** The palette for the page: [{code, name, color}] for plain stone, the surface layer and every cave biome. */
    public static List<Map<String, Object>> palette() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(Map.of("code", (int) PLAIN, "name", "plain stone caves", "color", "#5d5f66"));
        out.add(Map.of("code", (int) SURFACE_LAYER, "name", "surface layer (surface biome)", "color", "#857c6c"));
        for (short id : IDS) {
            String name = CaveBiomes.NAMES.get(id).replaceAll(".*:", "").replace('_', ' ');
            out.add(Map.of("code", 2 + IDS.indexOf(id), "name", name, "color", COLORS.getOrDefault(id, "#ff00ff")));
        }
        return out;
    }

    /** A rendered view: per pixel a material code and a biome code; rows top to bottom. */
    public record Result(int w, int h, byte[] material, byte[] biome, int x0, int z0, int yTop, int stepY, String view) {
    }

    // ---- Overview over the detail map (native pixels) ----

    /**
     * Cave biome codes at block height {@code y} for a window of native pixels (one pixel = {@code scale} blocks),
     * from the window's elevation (metres) and surface biomes. Approximate: the surface is the pixel's elevation (no
     * river cutting), and region anchors outside the window use the nearest edge pixel.
     */
    public static Result overview(short[] surfaceBiomes, float[] elevM, boolean[] inlandWater, int i1, int j1, int H, int W,
                                  int y, int scale) {
        CaveBiomes layer = CaveBiomes.forSeed(LocalTerrainProvider.getSeed());
        int[] top = new int[H * W];
        for (int i = 0; i < H * W; i++) {
            top[i] = HeightConverter.convertToMinecraftHeight((short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(elevM[i]))), scale) - 1;
        }
        CaveBiomes.Surface surface = new CaveBiomes.Surface() {
            private int idx(int bx, int bz) {
                int r = Math.max(0, Math.min(H - 1, Math.floorDiv(bz, scale) - i1));
                int c = Math.max(0, Math.min(W - 1, Math.floorDiv(bx, scale) - j1));
                return r * W + c;
            }

            @Override
            public short biomeAt(int bx, int bz) {
                return surfaceBiomes[idx(bx, bz)];
            }

            @Override
            public int groundTop(int bx, int bz) {
                return top[idx(bx, bz)];
            }

            @Override
            public int inlandWater(int bx, int bz) {
                int i = idx(bx, bz);
                return inlandWater != null && inlandWater[i] ? top[i] + 1 : Integer.MIN_VALUE;
            }
        };
        byte[] mat = new byte[H * W], bio = new byte[H * W];
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int i = r * W + c;
                int ref = top[i];
                if (scale <= 4) {   // the cave reference ground looks 4 blocks around: about one pixel at small scales
                    if (c > 0) ref = Math.min(ref, top[i - 1]);
                    if (c < W - 1) ref = Math.min(ref, top[i + 1]);
                    if (r > 0) ref = Math.min(ref, top[i - W]);
                    if (r < H - 1) ref = Math.min(ref, top[i + W]);
                }
                if (y > top[i]) {
                    mat[i] = elevM[i] < 0 && y < 63 ? SEA : ABOVE;
                    bio[i] = SURFACE_LAYER;
                    continue;
                }
                mat[i] = ROCK;
                int bx = (j1 + c) * scale + scale / 2, bz = (i1 + r) * scale + scale / 2;
                bio[i] = ref - y < CaveBiomes.START_DEPTH ? SURFACE_LAYER : code(layer.pick(bx, y, bz, surface));
            }
        }
        return new Result(W, H, mat, bio, j1 * scale, i1 * scale, y, 1, "overview");
    }

    // ---- Block-resolution slices and sections ----

    /** A horizontal slice of {@code size} x {@code size} blocks at height {@code y}, x0/z0 its north-west corner. */
    public static Result slice(int x0, int z0, int size, int y) {
        Columns cols = new Columns(x0, z0, size, size);
        CaveShapes shapes = CaveShapes.forSeed(LocalTerrainProvider.getSeed());
        CaveShapes.Scratch scratch = new CaveShapes.Scratch();
        float[] cave = new float[16 * 16];
        byte[] mat = new byte[size * size], bio = new byte[size * size];
        for (int gz = 0; gz < size; gz += 16) {
            for (int gx = 0; gx < size; gx += 16) {
                int sx = Math.min(16, size - gx), sz = Math.min(16, size - gz);
                shapes.fill(x0 + gx, z0 + gz, sx, sz, y, 1, cave, scratch, cols.biomesOn ? cols.surface : null);
                for (int dz = 0; dz < sz; dz++) {
                    for (int dx = 0; dx < sx; dx++) {
                        int c = (gz + dz) * size + gx + dx;
                        classify(cols, c, y, cave[dx + dz * sx], mat, bio, c);
                    }
                }
            }
        }
        return new Result(size, size, mat, bio, x0, z0, y, 1, "slice");
    }

    /**
     * A vertical section {@code len} blocks long through (x0, z0), running east (along x) or south (along z), from the
     * highest ground in it (plus a little sky) down to the bottom of the world, one row per {@code stepY} blocks.
     */
    public static Result section(int x0, int z0, int len, boolean alongX, int maxRows) {
        Columns cols = alongX ? new Columns(x0, z0, len, 1) : new Columns(x0, z0, 1, len);
        int minY = -64, maxTop = minY;
        for (int k = 0; k < len; k++) maxTop = Math.max(maxTop, cols.target[k]);
        int yTop = Math.min(2031, maxTop + 12);
        int span = yTop - minY + 1;
        int stepY = Math.max(1, (span + maxRows - 1) / maxRows);
        int rows = (span + stepY - 1) / stepY;
        CaveShapes shapes = CaveShapes.forSeed(LocalTerrainProvider.getSeed());
        float[] cave = new float[len * span];
        shapes.fill(x0, z0, alongX ? len : 1, alongX ? 1 : len, minY, span, cave, new CaveShapes.Scratch(),
                cols.biomesOn ? cols.surface : null);
        byte[] mat = new byte[len * rows], bio = new byte[len * rows];
        for (int r = 0; r < rows; r++) {
            int y = yTop - r * stepY;
            for (int k = 0; k < len; k++) {
                classify(cols, k, y, cave[k * span + (y - minY)], mat, bio, r * len + k);
            }
        }
        return new Result(len, rows, mat, bio, x0, z0, yTop, stepY, alongX ? "ew" : "ns");
    }

    /** What block (column c, height y) is, mirroring the terrain density, the dry-cave rule and the biome source. */
    private static void classify(Columns cols, int c, int y, float caveValue, byte[] mat, byte[] bio, int out) {
        int target = cols.target[c], ref = cols.ref[c];
        if (y >= target) {
            mat[out] = cols.water[c] != Short.MIN_VALUE && y <= cols.water[c] ? INLAND_WATER : y < 63 ? SEA : SKY;
            bio[out] = SURFACE_LAYER;
            return;
        }
        float v = target - y;
        if (TerrainDiffusionConfig.caves() && ref != Integer.MIN_VALUE && y <= ref) {
            v = Math.min(v, caveValue + CaveShapes.roof(ref - y, cols.roof[c]));
        }
        if (cols.extras != null && cols.extras.any) v = Math.min(v, cols.extras.value(c, cols.x0 + c % cols.w, y, cols.z0 + c / cols.w));
        short cave = cols.cave(c, y);
        if (v > 0) mat[out] = ROCK;
        else if (y < -54) mat[out] = LAVA;
        else if (y < 63 && !cols.dry[c]) mat[out] = FLOODED;
        else if (cave >= 0 && CaveBiomes.hasFluid(cave) && TerrainDiffusionConfig.waterCaves()
                && y <= cols.fluidLevel(c, y, cave)) mat[out] = CaveBiomes.isLavaCave(cave) ? LAVA : FLOODED;
        else mat[out] = AIR;
        bio[out] = cave == SURFACE_CAVE ? SURFACE_LAYER : code(cave);
    }

    /** Per-column terrain data for a block rectangle, from the game's tile cache on this thread. */
    private static final class Columns {
        final int x0, z0, w, h;
        final int[] target, ref;
        final float[] roof;
        final short[] water;
        final boolean[] dry;
        final TerrainDiffusionDensityFunction.TileCache cache = TerrainDiffusionDensityFunction.TileCache.get();
        final CaveBiomes layer = CaveBiomes.forSeed(LocalTerrainProvider.getSeed());
        final CaveSurface surface = CaveSurface.get();
        final boolean biomesOn = TerrainDiffusionConfig.caveBiomes();
        final CaveExtras extras;

        Columns(int x0, int z0, int w, int h) {
            this.x0 = x0;
            this.z0 = z0;
            this.w = w;
            this.h = h;
            int n = w * h;
            target = new int[n];
            ref = new int[n];
            roof = new float[n];
            water = new short[n];
            dry = new boolean[n];
            CaveShapes shapes = CaveShapes.forSeed(LocalTerrainProvider.getSeed());
            for (int dz = 0; dz < h; dz++) {
                for (int dx = 0; dx < w; dx++) {
                    int x = x0 + dx, z = z0 + dz, c = dz * w + dx;
                    target[c] = cache.surfaceHeight(x, z, 0);
                    ref[c] = cache.caveRefTop(x, z);
                    roof[c] = switch (cache.caveKind) {
                        case TerrainDiffusionDensityFunction.TileCache.CAVE_SEA -> CaveShapes.ROOF_SEA;
                        case TerrainDiffusionDensityFunction.TileCache.CAVE_WATER -> CaveShapes.ROOF_WATER;
                        default -> shapes.landRoof(x, z);
                    };
                    water[c] = waterAt(x, z);
                }
            }
            extras = TerrainDiffusionConfig.caves() ? CaveExtras.of(x0, z0, w, h) : null;
            // The dry-cave rule (DryCaves): a column's caves are dry when every column within 4 blocks is land.
            final int R = 4, mw = w + 2 * R, mh = h + 2 * R;
            com.github.xandergos.terraindiffusionmc.pipeline.SeaCaves seaCaves = TerrainDiffusionConfig.caves()
                    && TerrainDiffusionConfig.seaCaves()
                    ? com.github.xandergos.terraindiffusionmc.pipeline.SeaCaves.forSeed(LocalTerrainProvider.getSeed()) : null;
            boolean[] land = new boolean[mw * mh];
            for (int dz = 0; dz < mh; dz++) {
                for (int dx = 0; dx < mw; dx++) {
                    int x = x0 - R + dx, z = z0 - R + dz;
                    boolean l = waterAt(x, z) != Short.MIN_VALUE || cache.surfaceHeight(x, z, 0) - 1 >= 62;
                    if (l && seaCaves != null && waterAt(x, z) == Short.MIN_VALUE && seaCaves.active(x, z, surface)) l = false;
                    land[dz * mw + dx] = l;
                }
            }
            for (int dz = 0; dz < h; dz++) {
                for (int dx = 0; dx < w; dx++) {
                    boolean ok = true;
                    for (int oz = -R; oz <= R && ok; oz++)
                        for (int ox = -R; ox <= R && ok; ox++)
                            if (ox * ox + oz * oz <= R * R && !land[(dz + R + oz) * mw + dx + R + ox]) ok = false;
                    dry[dz * w + dx] = ok;
                }
            }
        }

        private short waterAt(int x, int z) {
            HeightmapData t = cache.tile(x, z);
            if (t == null || t.waterY == null) return Short.MIN_VALUE;
            return t.waterY[Math.min(t.height - 1, z - cache.blockStartZ)][Math.min(t.width - 1, x - cache.blockStartX)];
        }

        /** The cave biome at column c, height y, as the biome source answers for its 4x4x4 cell, or SURFACE_CAVE. */
        short cave(int c, int y) {
            if (!biomesOn) return SURFACE_CAVE;
            int x = x0 + c % w, z = z0 + c / w;
            int bx = (x & ~3) + 2, by = (y & ~3) + 2, bz = (z & ~3) + 2;
            int r = cache.caveRefTopMemo(bx, bz);
            if (r == Integer.MIN_VALUE || r - by < CaveBiomes.START_DEPTH) return SURFACE_CAVE;
            return layer.pick(bx, by, bz, surface);
        }

        /** The fluid level of the region holding column c's biome cell at height y (as world/CaveWater fills it). */
        int fluidLevel(int c, int y, short id) {
            int x = x0 + c % w, z = z0 + c / w;
            return layer.fluidLevel(layer.regionCell((x & ~3) + 2, (y & ~3) + 2, (z & ~3) + 2), id);
        }
    }
}
