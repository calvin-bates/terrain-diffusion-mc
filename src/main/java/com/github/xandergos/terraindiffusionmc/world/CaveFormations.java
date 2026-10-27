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
 * Natural cave formations inside caves (caves plan v1.5 part four round A), in the terrain pass after the cave rock and
 * water: columns from floor to ceiling in big chambers (dripstone, calcite in karst and salt caves, packed and blue ice
 * in icy caves), stalactites and stalagmites of pointed dripstone in drip zones, boxwork (a honeycomb of thin calcite
 * fins hanging from the ceiling) and crystal rooms (amethyst and calcite floors and ceilings with clusters).
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

    /** Per block column: air runs as [a, b, flags] triples. Flag bits: floor is rock, ceiling is rock, used. */
    private static final int FLOOR = 1, CEIL = 2, USED = 4;

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

        // 3. Crystal rooms, then boxwork, then stalactites, on the runs columns left alone.
        List<Zone> zones = new ArrayList<>();
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
                    if ((flags & FLOOR) != 0 && zone.dist(x, a - 1, z) < 1f) {
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
                    boolean floorRock = (flags & FLOOR) != 0;
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
