package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveBiomes;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveFormationLayout;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveFormationLayout.Column;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveFormationLayout.Zone;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SpeleothemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Natural cave formations inside caves (caves plan v1.5 part four), in the terrain pass after the cave rock and water.
 * Round A: columns from floor to ceiling in big chambers (dripstone, calcite in karst and salt caves, packed and blue
 * ice in icy caves), stalactites and stalagmites of pointed dripstone in drip zones, boxwork (a honeycomb of thin
 * calcite fins hanging from the ceiling) and crystal rooms (amethyst and calcite floors and ceilings with clusters).
 * Round B: flowstone (banded calcite and dripstone curtains hanging in wavy folds, flowstone sheets on the floor),
 * rimstone pools (gours: shallow pools held by calcite rims, stepping down the floor), waterfalls pouring from the
 * ceiling into a plunge pool, and travertine terraces in thermal caves.
 *
 * <p>Where they go comes from {@link CaveFormationLayout} (pure: anchors and zones on hashed grids). Each chunk fits
 * them to its own columns of blocks: a formation only fills the vertical air run of each block column it covers, so
 * no chunk needs its neighbours' blocks and a column crossing a chunk border is whole. Only dry air runs with rock
 * above (caves, not the open sky) are touched; water and lava caves are left alone except for stalactites over water.
 */
public final class CaveFormations {
    private static final Logger LOG = LoggerFactory.getLogger(CaveFormations.class);

    private CaveFormations() {
    }

    private static final BlockState DRIPSTONE = Blocks.DRIPSTONE_BLOCK.defaultBlockState();
    private static final BlockState CALCITE = Blocks.CALCITE.defaultBlockState();
    private static final BlockState PACKED_ICE = Blocks.PACKED_ICE.defaultBlockState();
    private static final BlockState BLUE_ICE = Blocks.BLUE_ICE.defaultBlockState();
    private static final BlockState AMETHYST = Blocks.AMETHYST_BLOCK.defaultBlockState();
    private static final BlockState BUDDING = Blocks.BUDDING_AMETHYST.defaultBlockState();

    /** Drip zones: where this noise is high, chambers get stalactites and stalagmites (patchy, not everywhere). */
    private static volatile FastNoiseLite dripNoise;
    private static volatile long dripSeed = Long.MIN_VALUE;

    private static FastNoiseLite dripNoise(long seed) {
        if (dripNoise == null || dripSeed != seed) {
            FastNoiseLite n = new FastNoiseLite((int) (seed ^ (seed >>> 32)) + 0xD41F);
            n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
            n.SetFrequency(1f / 48f);
            dripNoise = n;
            dripSeed = seed;
        }
        return dripNoise;
    }

    public static void place(ChunkAccess chunk) {
        if (!TerrainDiffusionConfig.caves() || !TerrainDiffusionConfig.caveFormations()) return;
        try {
            placeUnchecked(chunk);
        } catch (Throwable t) {
            LOG.error("Terra: cave formations failed for chunk {}", chunk.getPos(), t);
        }
    }

    /**
     * Per block column: air runs as [a, b, flags] triples. Flag bits: floor is rock, ceiling is rock, used (a formation
     * took the run), floor used (a floor formation took its floor; ceilings stay free).
     */
    private static final int FLOOR = 1, CEIL = 2, USED = 4, FLOOR_USED = 8;

    private static void placeUnchecked(ChunkAccess chunk) {
        long seed = LocalTerrainProvider.getSeed();
        CaveBiomes layer = CaveBiomes.forSeed(seed);
        CaveSurface surface = CaveSurface.get();
        Blocks3 w = new Blocks3(chunk);
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();

        // 1. The cave air runs of every block column, up to the surface.
        int[][] runs = new int[256][];
        int[] nRuns = new int[256];
        int maxY = w.minY;
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int top = Math.min(chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, lx, lz), w.maxY - 1);
                int c = lz * 16 + lx;
                int[] r = new int[24];
                int n = 0;
                int y = w.minY + 1;
                while (y < top) {
                    int s = w.sectionIndex(y);
                    if (!w.hasAir[s]) {   // no air anywhere in this section: skip it
                        y = w.sectionBottom(s) + 16;
                        continue;
                    }
                    if (!w.get(lx, y, lz).isAir()) {
                        y++;
                        continue;
                    }
                    int a = y;
                    while (y < top && w.get(lx, y, lz).isAir()) y++;
                    int b = y - 1;   // ends under a block: top is the column's highest one, so no run here is open sky
                    int flags = 0;
                    if (isRock(w.get(lx, a - 1, lz))) flags |= FLOOR;
                    if (isRock(w.get(lx, b + 1, lz))) flags |= CEIL;
                    if ((flags & CEIL) == 0) continue;
                    if (n + 3 > r.length) r = java.util.Arrays.copyOf(r, r.length * 2);
                    r[n++] = a;
                    r[n++] = b;
                    r[n++] = flags;
                    maxY = Math.max(maxY, b);
                }
                runs[c] = r;
                nRuns[c] = n;
            }
        if (maxY <= w.minY) return;

        // 2. Columns (dry chambers, floor to ceiling).
        List<Column> cols = new ArrayList<>();
        CaveFormationLayout.columns(seed, layer, surface, x0, z0, x0 + 15, z0 + 15, w.minY, maxY, cols);
        for (Column col : cols) {
            BlockState body = col.kind() == CaveFormationLayout.K_ICE ? PACKED_ICE
                    : col.kind() == CaveFormationLayout.K_KARST || col.kind() == CaveFormationLayout.K_SALT ? CALCITE
                    : DRIPSTONE;
            int reach = CaveFormationLayout.COL_REACH;
            for (int lz = Math.max(0, (int) Math.floor(col.z() - reach) - z0); lz <= Math.min(15, (int) Math.ceil(col.z() + reach) - z0); lz++)
                for (int lx = Math.max(0, (int) Math.floor(col.x() - reach) - x0); lx <= Math.min(15, (int) Math.ceil(col.x() + reach) - x0); lx++) {
                    float dx = x0 + lx + 0.5f - col.x(), dz = z0 + lz + 0.5f - col.z();
                    float d = (float) Math.sqrt(dx * dx + dz * dz);
                    if (d > reach) continue;
                    int c = lz * 16 + lx;
                    int[] r = runs[c];
                    for (int i = 0; i < nRuns[c]; i += 3) {
                        int a = r[i], b = r[i + 1];
                        if (col.y() < a || col.y() > b || (r[i + 2] & FLOOR) == 0) continue;
                        int h = b - a + 1;
                        if (h < CaveFormationLayout.COL_MIN_H || h > CaveFormationLayout.COL_MAX_H) continue;
                        boolean any = false;
                        for (int y = a; y <= b; y++) {
                            float rad = col.radius(y, a, b);
                            if (rad < 0 || d > rad) continue;
                            BlockState st = body;
                            if (body == PACKED_ICE && d < rad * 0.45f) st = BLUE_ICE;
                            w.set(lx, y, lz, st);
                            any = true;
                        }
                        if (any) r[i + 2] |= USED;
                    }
                }
        }

        // 3. Round B: floor patches (rimstone pools, waterfalls, travertine terraces), one try per slab of the chunk.
        int cxChunk = chunk.getPos().x(), czChunk = chunk.getPos().z();
        for (int slab = Math.floorDiv(w.minY, CaveFormationLayout.PATCH_SLAB);
             slab <= Math.floorDiv(maxY, CaveFormationLayout.PATCH_SLAB); slab++) {
            CaveFormationLayout.Patch p = CaveFormationLayout.patch(seed, layer, surface, cxChunk, czChunk, slab);
            if (p == null) continue;
            if (p.type() == CaveFormationLayout.P_WATERFALL) waterfall(w, p, runs, nRuns, x0, z0, slab);
            else floorPatch(w, p, runs, nRuns, x0, z0, slab, seed);
        }

        // 4. Flowstone, crystal rooms, boxwork, then stalactites, on the runs columns left alone.
        List<Zone> zones = new ArrayList<>();
        CaveFormationLayout.zones(CaveFormationLayout.ZONE_FLOWSTONE, seed, layer, surface, x0, w.minY, z0, x0 + 15,
                maxY + 1, z0 + 15, zones);
        for (Zone zone : zones) flowstone(w, zone, runs, nRuns, x0, z0, seed);
        CaveFormationLayout.zones(CaveFormationLayout.ZONE_CRYSTAL, seed, layer, surface, x0, w.minY, z0, x0 + 15, maxY + 1,
                z0 + 15, zones);
        for (Zone zone : zones) crystalRoom(w, zone, runs, nRuns, x0, z0, seed);
        CaveFormationLayout.zones(CaveFormationLayout.ZONE_BOXWORK, seed, layer, surface, x0, w.minY, z0, x0 + 15, maxY + 1,
                z0 + 15, zones);
        for (Zone zone : zones) boxwork(w, zone, runs, nRuns, x0, z0, seed);
        stalactites(w, runs, nRuns, x0, z0, seed, layer, surface);
    }

    /** Amethyst and calcite on the floors and ceilings inside the zone, with clusters growing off them. */
    private static void crystalRoom(Blocks3 w, Zone zone, int[][] runs, int[] nRuns, int x0, int z0, long seed) {
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz, c = lz * 16 + lx;
                int[] r = runs[c];
                for (int i = 0; i < nRuns[c]; i += 3) {
                    if ((r[i + 2] & USED) != 0) continue;
                    int a = r[i], b = r[i + 1], flags = r[i + 2];
                    boolean did = false;
                    if (zone.dist(x, b + 1, z) < 1f) {
                        long h = CaveFormationLayout.hash(seed ^ 0xA3E7L, x, b + 1, z);
                        line(w, lx, b + 1, lz, b + 2, h);
                        if (b >= a && CaveFormationLayout.unit(h, 16) < 0.38f) w.set(lx, b, lz, cluster(Direction.DOWN, h));
                        did = true;
                    }
                    if ((flags & FLOOR) != 0 && (flags & FLOOR_USED) == 0 && zone.dist(x, a - 1, z) < 1f) {
                        long h = CaveFormationLayout.hash(seed ^ 0xA3E8L, x, a - 1, z);
                        line(w, lx, a - 1, lz, a - 2, h);
                        // Leave room for the ceiling's cluster in a one-block gap.
                        if (b - a >= 1 && CaveFormationLayout.unit(h, 16) < 0.32f) w.set(lx, a, lz, cluster(Direction.UP, h));
                        did = true;
                    }
                    if (did) r[i + 2] |= USED;
                }
            }
    }

    /** Lines one cave surface block with amethyst (1 in 12 budding) and the rock behind it with calcite. */
    private static void line(Blocks3 w, int lx, int y, int lz, int behind, long h) {
        if (!isRock(w.get(lx, y, lz))) return;
        w.set(lx, y, lz, CaveFormationLayout.unit(h, 0) < 1f / 12f ? BUDDING : AMETHYST);
        if (w.inside(behind) && isRock(w.get(lx, behind, lz))) w.set(lx, behind, lz, CALCITE);
    }

    private static BlockState cluster(Direction facing, long h) {
        float u = CaveFormationLayout.unit(h, 24);
        net.minecraft.world.level.block.Block b = u < 0.4f ? Blocks.AMETHYST_CLUSTER : u < 0.65f ? Blocks.LARGE_AMETHYST_BUD
                : u < 0.85f ? Blocks.MEDIUM_AMETHYST_BUD : Blocks.SMALL_AMETHYST_BUD;
        return b.defaultBlockState().setValue(AmethystClusterBlock.FACING, facing).setValue(AmethystClusterBlock.WATERLOGGED, false);
    }

    /** Calcite fins in a honeycomb hanging 1 to 4 blocks from the ceilings inside the zone (longest at its middle). */
    private static void boxwork(Blocks3 w, Zone zone, int[][] runs, int[] nRuns, int x0, int z0, long seed) {
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz, c = lz * 16 + lx;
                float edge = CaveFormationLayout.boxworkEdge(seed, x + 0.5f, z + 0.5f);
                if (edge > 0.55f) continue;
                int[] r = runs[c];
                for (int i = 0; i < nRuns[c]; i += 3) {
                    if ((r[i + 2] & USED) != 0) continue;
                    int a = r[i], b = r[i + 1];
                    float d = zone.dist(x, b + 1, z);
                    if (d >= 1f) continue;
                    int depth = 1 + Math.round(2.5f * (1f - d));
                    depth = Math.min(depth, b - a + 1 - 2);   // keep 2 blocks of headroom
                    if (depth <= 0) continue;
                    for (int k = 0; k < depth; k++) w.set(lx, b - k, lz, CALCITE);
                    r[i + 2] |= USED;
                }
            }
    }

    /** Pointed dripstone stalactites in drip zones, stalagmites under some of them and here and there on the floor. */
    private static void stalactites(Blocks3 w, int[][] runs, int[] nRuns, int x0, int z0, long seed, CaveBiomes layer,
                                    CaveSurface surface) {
        FastNoiseLite noise = dripNoise(seed);
        byte[] kindMemo = new byte[64 * 1024];   // per 4x4x4 cell of the run's top: kind + 1, 0 unknown
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz, c = lz * 16 + lx;
                int[] r = runs[c];
                for (int i = 0; i < nRuns[c]; i += 3) {
                    if ((r[i + 2] & USED) != 0) continue;
                    int a = r[i], b = r[i + 1], flags = r[i + 2];
                    int h = b - a + 1;
                    if (h < 4) continue;
                    float zoneF = (noise.GetNoise(x, b, z) - 0.05f) / 0.35f;
                    if (zoneF <= 0f) continue;
                    zoneF = Math.min(1f, zoneF);
                    byte k = kindAt(layer, surface, x, b, z, w, kindMemo);
                    float p = CaveFormationLayout.DRIP_CHANCE[k] * zoneF;
                    if (p <= 0f) continue;
                    long hh = CaveFormationLayout.hash(seed ^ 0xD121L, x, b, z);
                    float u = CaveFormationLayout.unit(hh, 0);
                    int down = 0, up = 0;
                    if (u < p) down = length(CaveFormationLayout.unit(hh, 8));
                    boolean floorRock = (flags & FLOOR) != 0 && (flags & FLOOR_USED) == 0;
                    if (floorRock) {
                        float v = CaveFormationLayout.unit(hh, 16);
                        if ((down > 0 && v < 0.45f) || (down == 0 && v < p * 0.4f)) up = length(CaveFormationLayout.unit(hh, 24));
                    }
                    // Keep at least one block between the tips.
                    while (down + up > h - 1) { if (up >= down && up > 0) up--; else down--; }
                    if (down > 0) {
                        if (isRock(w.get(lx, b + 1, lz))) w.set(lx, b + 1, lz, DRIPSTONE);
                        speleothem(w, lx, b, lz, Direction.DOWN, down);
                    }
                    if (up > 0) {
                        if (isRock(w.get(lx, a - 1, lz))) w.set(lx, a - 1, lz, DRIPSTONE);
                        speleothem(w, lx, a, lz, Direction.UP, up);
                    }
                    if (down > 0 || up > 0) r[i + 2] |= USED;
                }
            }
    }

    // ---- Round B ----

    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /** Flowstone curtains: where this 2D noise crosses zero, a fold of the curtain hangs (wavy lines, 1 block thick). */
    private static volatile FastNoiseLite curtainNoise;
    private static volatile long curtainSeed = Long.MIN_VALUE;

    private static FastNoiseLite curtainNoise(long seed) {
        if (curtainNoise == null || curtainSeed != seed) {
            FastNoiseLite n = new FastNoiseLite((int) (seed ^ (seed >>> 32)) + 0xF1057);
            n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
            n.SetFrequency(1f / 11f);
            n.SetFractalType(FastNoiseLite.FractalType.FBm);
            n.SetFractalOctaves(2);
            curtainNoise = n;
            curtainSeed = seed;
        }
        return curtainNoise;
    }

    /**
     * Flowstone in a zone: curtains of banded calcite and dripstone hanging 2 to 7 blocks from the ceilings in wavy
     * folds (longest at the zone's middle, always 2 blocks of headroom), and flowstone sheets on the floors (calcite
     * with dripstone bands).
     */
    private static void flowstone(Blocks3 w, Zone zone, int[][] runs, int[] nRuns, int x0, int z0, long seed) {
        FastNoiseLite n = curtainNoise(seed);
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz, c = lz * 16 + lx;
                float fold = n.GetNoise(x, z);
                float sheet = n.GetNoise(x * 0.5f + 311f, z * 0.5f - 97f);
                int[] r = runs[c];
                for (int i = 0; i < nRuns[c]; i += 3) {
                    int a = r[i], b = r[i + 1], flags = r[i + 2];
                    int h = b - a + 1;
                    // Curtains on ceilings at least 5 blocks up.
                    float dc = zone.dist(x, b + 1, z);
                    if ((flags & USED) == 0 && h >= 5 && dc < 1f && Math.abs(fold) < 0.075f) {
                        long hh = CaveFormationLayout.hash(seed ^ 0xF10C7L, x, b, z);
                        int len = 2 + Math.round((1f - dc) * 4f + CaveFormationLayout.unit(hh, 0) * 1.5f);
                        len = Math.min(len, h - 2);
                        int band = (int) (CaveFormationLayout.unit(hh, 8) * 3);
                        if (isRock(w.get(lx, b + 1, lz))) w.set(lx, b + 1, lz, CALCITE);
                        for (int k = 0; k < len; k++) {
                            int y = b - k;
                            w.set(lx, y, lz, Math.floorMod(y + band, 4) == 0 ? DRIPSTONE : CALCITE);
                        }
                        r[i + 2] |= USED;
                    }
                    // Sheets on floors.
                    float df = zone.dist(x, a - 1, z);
                    if ((flags & FLOOR) != 0 && (flags & FLOOR_USED) == 0 && df < 0.85f && sheet > 0.05f
                            && isRock(w.get(lx, a - 1, lz))) {
                        int y = a - 1;
                        w.set(lx, y, lz, Math.floorMod(y * 3 + (int) (sheet * 9f), 5) == 0 ? DRIPSTONE : CALCITE);
                        r[i + 2] |= FLOOR_USED;
                    }
                }
            }
    }

    /** The run of column c whose floor is in this slab, has rock under it and is nearest height y, or -1. */
    private static int floorRun(int[][] runs, int[] nRuns, int c, int slab, int y, int minHeight) {
        int[] r = runs[c];
        int best = -1, bestD = Integer.MAX_VALUE;
        int lo = slab * CaveFormationLayout.PATCH_SLAB, hi = lo + CaveFormationLayout.PATCH_SLAB - 1;
        for (int i = 0; i < nRuns[c]; i += 3) {
            int a = r[i], b = r[i + 1], flags = r[i + 2];
            if ((flags & FLOOR) == 0 || (flags & FLOOR_USED) != 0 || a < lo || a > hi || b - a + 1 < minHeight) continue;
            int d = Math.abs(a - y);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** The run of column c whose floor block (a - 1) is within {@code tol} of {@code floor}, or -1. */
    private static int runNear(int[][] runs, int[] nRuns, int c, int floor, int tol, int minHeight) {
        int[] r = runs[c];
        int best = -1, bestD = Integer.MAX_VALUE;
        for (int i = 0; i < nRuns[c]; i += 3) {
            int a = r[i], b = r[i + 1], flags = r[i + 2];
            if ((flags & FLOOR) == 0 || (flags & FLOOR_USED) != 0 || b - a + 1 < minHeight) continue;
            int d = Math.abs(a - 1 - floor);
            if (d <= tol && d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Rimstone pools and travertine terraces: the floor around the patch's centre, kept as it is (rimstone) or cut into
     * 2-block steps (travertine), holds shallow pools wherever water can't run off: a column is a pool when the floor
     * of each of its four neighbours stands at least as high (inside the chunk). The rest are calcite rims, raised a
     * block beside the pools. Rimstone also splits wide pools into gours with thin rims (a 4-block honeycomb).
     */
    private static void floorPatch(Blocks3 w, CaveFormationLayout.Patch p, int[][] runs, int[] nRuns, int x0, int z0,
                                   int slab, long seed) {
        boolean trav = p.type() == CaveFormationLayout.P_TRAVERTINE;
        int lcx = p.x() - x0, lcz = p.z() - z0;
        int ci = floorRun(runs, nRuns, lcz * 16 + lcx, slab, p.y(), 4);
        if (ci < 0) return;
        int f0 = runs[lcz * 16 + lcx][ci] - 1;
        int R = (int) Math.ceil(p.r()) + 1;
        int[] idx = new int[256], T = new int[256];
        java.util.Arrays.fill(idx, -1);
        int n = 0;
        for (int lz = Math.max(0, lcz - R); lz <= Math.min(15, lcz + R); lz++)
            for (int lx = Math.max(0, lcx - R); lx <= Math.min(15, lcx + R); lx++) {
                int x = x0 + lx, z = z0 + lz;
                float d = (float) Math.hypot(lx - lcx, lz - lcz)
                        * (1f + 0.18f * (float) Math.sin(x * 0.9f + z * 0.4f + p.hash() % 7));
                if (d > p.r()) continue;
                int c = lz * 16 + lx;
                int i = runNear(runs, nRuns, c, f0, trav ? 4 : 2, 3);
                if (i < 0) continue;
                int f = runs[c][i] - 1;
                int t = trav ? f0 + Math.floorDiv(f - f0, 2) * 2 : f;
                // Cut no deeper than the floor's own rock allows (travertine steps).
                if (!isRock(w.get(lx, t, lz)) || !isRock(w.get(lx, t - 1, lz))) continue;
                idx[c] = i;
                T[c] = t;
                n++;
            }
        if (n < 6) return;
        boolean[] pool = new boolean[256];
        for (int c = 0; c < 256; c++) {
            if (idx[c] < 0) continue;
            int lx = c & 15, lz = c >> 4;
            if (lx == 0 || lx == 15 || lz == 0 || lz == 15) continue;
            if (!trav && CaveFormationLayout.boxworkEdge(seed ^ 0x61AL, x0 + lx + 0.5f, z0 + lz + 0.5f) < 0.45f) continue;
            boolean ok = true;
            for (int k = 0; k < 4 && ok; k++) {
                int nx = lx + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = lz + (k == 2 ? 1 : k == 3 ? -1 : 0);
                int nc = nz * 16 + nx;
                // The neighbour's floor after the patch: its step if it's in the patch, else the rock as it is.
                ok = idx[nc] >= 0 ? T[nc] >= T[c] : isRock(w.get(nx, T[c], nz));
            }
            pool[c] = ok;
        }
        long h = p.hash();
        for (int c = 0; c < 256; c++) {
            if (idx[c] < 0) continue;
            int lx = c & 15, lz = c >> 4;
            int i = idx[c], a = runs[c][i], b = runs[c][i + 1], t = T[c];
            for (int y = t + 1; y < a; y++) w.set(lx, y, lz, AIR);   // travertine: cut down to the step
            long hc = CaveFormationLayout.hash(seed ^ 0x7A3L, x0 + lx, t, z0 + lz);
            if (pool[c]) {
                w.set(lx, t, lz, WATER);
                w.set(lx, t - 1, lz, trav || CaveFormationLayout.unit(hc, 0) < 0.7f ? CALCITE : DRIPSTONE);
            } else {
                boolean stain = trav && CaveFormationLayout.unit(hc, 8) < 0.14f;
                w.set(lx, t, lz, stain ? Blocks.DYED_TERRACOTTA.yellow().defaultBlockState() : CALCITE);
                // A raised lip beside a pool (with 2 blocks of headroom left above it).
                boolean byPool = (lx < 15 && pool[c + 1]) || (lx > 0 && pool[c - 1]) || (lz < 15 && pool[c + 16])
                        || (lz > 0 && pool[c - 16]);
                if (byPool && b - (t + 1) >= 2) w.set(lx, t + 1, lz, CALCITE);
            }
            runs[c][i + 2] |= FLOOR_USED;
        }
    }

    /**
     * A waterfall: a spring in the ceiling of a tall chamber (12 blocks or more) pouring into a round plunge pool 2
     * deep in the floor beneath, its bed gravel, clay and sand. The pool must hold: its floor level and its sides are
     * checked inside the chunk, else nothing is placed.
     */
    private static void waterfall(Blocks3 w, CaveFormationLayout.Patch p, int[][] runs, int[] nRuns, int x0, int z0,
                                  int slab) {
        int lcx = p.x() - x0, lcz = p.z() - z0;
        int cc = lcz * 16 + lcx;
        int ci = floorRun(runs, nRuns, cc, slab, p.y(), 12);
        if (ci < 0 || (runs[cc][ci + 2] & (CEIL | USED)) != CEIL) return;
        int f0 = runs[cc][ci] - 1, top = runs[cc][ci + 1];
        int R = (int) Math.ceil(p.r());
        int[] idx = new int[256];
        java.util.Arrays.fill(idx, -1);
        int level = f0;
        for (int lz = lcz - R; lz <= lcz + R; lz++)
            for (int lx = lcx - R; lx <= lcx + R; lx++) {
                if (Math.hypot(lx - lcx, lz - lcz) > p.r()) continue;
                int c = lz * 16 + lx;
                int i = runNear(runs, nRuns, c, f0, 1, 5);
                if (i < 0) return;
                idx[c] = i;
                level = Math.min(level, runs[c][i] - 1);
            }
        // The sides hold: every neighbour outside the pool is rock at the water's two levels.
        for (int c = 0; c < 256; c++) {
            if (idx[c] < 0) continue;
            int lx = c & 15, lz = c >> 4;
            if (!isRock(w.get(lx, level - 2, lz))) return;
            for (int k = 0; k < 4; k++) {
                int nx = lx + (k == 0 ? 1 : k == 1 ? -1 : 0), nz = lz + (k == 2 ? 1 : k == 3 ? -1 : 0);
                if (idx[nz * 16 + nx] >= 0) continue;
                if (!isRock(w.get(nx, level, nz)) || !isRock(w.get(nx, level - 1, nz))) return;
            }
        }
        long h = p.hash();
        for (int c = 0; c < 256; c++) {
            if (idx[c] < 0) continue;
            int lx = c & 15, lz = c >> 4;
            int a = runs[c][idx[c]];
            for (int y = level + 1; y < a; y++) w.set(lx, y, lz, AIR);
            w.set(lx, level, lz, WATER);
            w.set(lx, level - 1, lz, WATER);
            long hc = CaveFormationLayout.hash(h, lx, level, lz);
            float u = CaveFormationLayout.unit(hc, 0);
            w.set(lx, level - 2, lz, u < 0.5f ? Blocks.GRAVEL.defaultBlockState() : u < 0.8f
                    ? Blocks.CLAY.defaultBlockState() : Blocks.SAND.defaultBlockState());
            runs[c][idx[c] + 2] |= FLOOR_USED;
        }
        // The spring: a water source under the ceiling, flowing once the chunk loads.
        w.set(lcx, top, lcz, WATER);
        w.chunk.markPosForPostProcessing(new net.minecraft.core.BlockPos(x0 + lcx, top, z0 + lcz));
        runs[cc][ci + 2] |= USED;
    }

    /** Stalactite or stalagmite length: mostly 1 or 2, sometimes up to 5. */
    private static int length(float u) {
        return u < 0.35f ? 1 : u < 0.65f ? 2 : u < 0.85f ? 3 : u < 0.95f ? 4 : 5;
    }

    /** A pointed dripstone of {@code len} blocks from (lx, y, lz) going {@code dir}: base, middles, frustum, tip. */
    private static void speleothem(Blocks3 w, int lx, int y, int lz, Direction dir, int len) {
        int step = dir == Direction.DOWN ? -1 : 1;
        for (int k = 0; k < len; k++) {
            SpeleothemThickness t;
            int fromTip = len - 1 - k;
            if (fromTip == 0) t = SpeleothemThickness.TIP;
            else if (fromTip == 1) t = SpeleothemThickness.FRUSTUM;
            else if (k == 0) t = SpeleothemThickness.BASE;
            else t = SpeleothemThickness.MIDDLE;
            w.set(lx, y + step * k, lz, Blocks.POINTED_DRIPSTONE.defaultBlockState()
                    .setValue(SpeleothemBlock.TIP_DIRECTION, dir).setValue(SpeleothemBlock.THICKNESS, t)
                    .setValue(SpeleothemBlock.WATERLOGGED, false));
        }
    }

    /** The formation kind at a block, from the cave biome layer (memoised per 4x4x4 cell). */
    private static byte kindAt(CaveBiomes layer, CaveSurface surface, int x, int y, int z, Blocks3 w, byte[] memo) {
        int qx = (x & 15) >> 2, qz = (z & 15) >> 2, qy = (y - w.minY) >> 2;
        int i = (qy * 4 + qz) * 4 + qx;
        if (i >= 0 && i < memo.length && memo[i] != 0) return (byte) (memo[i] - 1);
        byte k = CaveFormationLayout.kind(layer.pick((x & ~3) + 2, (y & ~3) + 2, (z & ~3) + 2, surface));
        if (i >= 0 && i < memo.length) memo[i] = (byte) (k + 1);
        return k;
    }

    /** Rock a formation may sit on or replace: the overworld's base stones, Terra's cave rocks, ice, dripstone. */
    private static boolean isRock(BlockState s) {
        return s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(Blocks.CALCITE) || s.is(Blocks.DRIPSTONE_BLOCK)
                || s.is(Blocks.PACKED_ICE) || s.is(Blocks.BLUE_ICE) || s.is(Blocks.SMOOTH_BASALT)
                || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE) || s.is(BlockTags.TERRACOTTA)
                || s.is(Blocks.COBBLESTONE) || s.is(Blocks.COBBLED_DEEPSLATE);
    }

    /** Direct section access for the proto chunk (no block updates). */
    private static final class Blocks3 {
        final LevelChunkSection[] sections;
        final boolean[] hasAir;
        final int minY, maxY, minSection;
        final ChunkAccess chunk;

        Blocks3(ChunkAccess chunk) {
            this.chunk = chunk;
            sections = chunk.getSections();
            minY = chunk.getMinY();
            maxY = minY + chunk.getHeight();
            minSection = chunk.getMinSectionY();
            hasAir = new boolean[sections.length];
            for (int s = 0; s < sections.length; s++) {
                LevelChunkSection sec = sections[s];
                hasAir[s] = sec == null || sec.hasOnlyAir() || sec.getStates().maybeHas(BlockState::isAir);
            }
        }

        boolean inside(int y) {
            return y >= minY && y < maxY;
        }

        int sectionIndex(int y) {
            return (y >> 4) - minSection;
        }

        int sectionBottom(int s) {
            return (s + minSection) << 4;
        }

        BlockState get(int lx, int y, int lz) {
            if (!inside(y)) return Blocks.BEDROCK.defaultBlockState();
            LevelChunkSection sec = sections[sectionIndex(y)];
            return sec == null ? Blocks.AIR.defaultBlockState() : sec.getBlockState(lx, y & 15, lz);
        }

        void set(int lx, int y, int lz, BlockState st) {
            if (!inside(y)) return;
            LevelChunkSection sec = sections[sectionIndex(y)];
            if (sec != null) sec.setBlockState(lx, y & 15, lz, st, false);
        }
    }
}
