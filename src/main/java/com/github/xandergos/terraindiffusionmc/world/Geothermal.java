package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.pipeline.Showpieces;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hot spring pools on geothermal fields (biomes plan v1.4 round D, Yellowstone), right after the terrain pass. One
 * candidate pool per {@link #CELL}-block cell of the world, at a random point in it; it forms where the tile tags that
 * point {@link Showpieces#TAG_GEOTHERMAL}. A pool is 5-16 blocks across with a ragged edge: its water lies at the level
 * of the lowest ground around it (so on a slope it cuts into the hillside and pools step down the slope like terraces),
 * a bowl 1-3 blocks deep with a pale blue floor, ringed by yellow, then orange, then brown terracotta (the bacterial mats
 * of Grand Prismatic Spring). A third of them boil: soul sand in the middle under a bubble column. Every test reads the
 * tiles (neighbouring ones too), so pools across chunk and tile edges come out whole.
 *
 * <p>Geysers too: one candidate per {@link #GEYSER_CELL}-block cell, a sinter cone with 26.3's own geyser in its crater
 * (see {@link #geyser}); pools keep clear of them.
 *
 * <p>And travertine terraces (caves plan v1.5 part four round B, Mammoth Hot Springs): on the slopes of geothermal
 * fields, steps of white calcite each holding a shallow pool behind a raised lip (see {@link #terraces}).
 */
public final class Geothermal {
    private static final Logger LOG = LoggerFactory.getLogger(Geothermal.class);

    /** Cell (blocks) holding at most one pool, chance of one, and the largest pool radius plus rings. */
    private static final int CELL = 18;
    private static final double CHANCE = 0.65;
    private static final int REACH = 13;   // also covers a geyser apron's test window

    private static final FastNoiseLite EDGE = noise(77501, 1f / 4f);
    private static final FastNoiseLite RING = noise(77502, 1f / 3f);

    private static volatile boolean loggedFirst;

    private Geothermal() {
    }

    private static FastNoiseLite noise(int seed, float freq) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(2);
        return n;
    }

    public static void place(ChunkAccess chunk) {
        try {
            if (!TerrainDiffusionConfig.showpieces()) return;
            placeUnchecked(chunk);
        } catch (Throwable t) {
            LOG.error("Terra: hot springs failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static void placeUnchecked(ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        int tileSize = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(tileSize);
        int startX = (x0 >> shift) << shift, startZ = (z0 >> shift) << shift;
        HeightmapData data = LocalTerrainProvider.peekHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
        if (data == null || data.oceanTags == null) return;
        // Any geothermal ground near this chunk at all (the tile's tags, this tile only: a cheap first test)?
        boolean any = false;
        for (int z = Math.max(0, z0 - startZ - REACH); z < Math.min(tileSize, z0 - startZ + 16 + REACH) && !any; z += 3)
            for (int x = Math.max(0, x0 - startX - REACH); x < Math.min(tileSize, x0 - startX + 16 + REACH) && !any; x += 3)
                any = data.oceanTags[z][x] == Showpieces.TAG_GEOTHERMAL;
        if (!any) return;
        int scale = WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
        long seed = LocalTerrainProvider.getSeed();
        for (int gz = Math.floorDiv(z0 - REACH, CELL); gz <= Math.floorDiv(z0 + 15 + REACH, CELL); gz++) {
            for (int gx = Math.floorDiv(x0 - REACH, CELL); gx <= Math.floorDiv(x0 + 15 + REACH, CELL); gx++) {
                double[] p = params(seed, gx, gz);
                if (p == null) continue;
                int cx = (int) p[0], cz = (int) p[1];
                if (cx + REACH < x0 || cx - REACH > x0 + 15 || cz + REACH < z0 || cz - REACH > z0 + 15) continue;
                // Pools that would run into a neighbour's give way to the one with the lower hash, so they never cut into
                // each other's water.
                boolean blocked = false;
                for (int nz = gz - 1; nz <= gz + 1 && !blocked; nz++) {
                    for (int nx = gx - 1; nx <= gx + 1 && !blocked; nx++) {
                        if (nx == gx && nz == gz) continue;
                        double[] q = params(seed, nx, nz);
                        if (q != null && q[3] < p[3] && Math.hypot(q[0] - p[0], q[1] - p[1]) < (q[2] + p[2]) * 1.25 + 5) blocked = true;
                    }
                }
                if (blocked || nearGeyser(seed, p[0], p[1], p[2] + 4)) continue;
                pool(chunk, data, startX, startZ, scale, cx, cz, Double.doubleToRawLongBits(p[4]));
            }
        }
        // Travertine terraces on the slopes.
        if (TerrainDiffusionConfig.caveFormations()) {
            for (int gz = Math.floorDiv(z0 - TERRACE_REACH, TERRACE_CELL); gz <= Math.floorDiv(z0 + 15 + TERRACE_REACH, TERRACE_CELL); gz++) {
                for (int gx = Math.floorDiv(x0 - TERRACE_REACH, TERRACE_CELL); gx <= Math.floorDiv(x0 + 15 + TERRACE_REACH, TERRACE_CELL); gx++) {
                    double[] t = terraceParams(seed, gx, gz);
                    if (t == null) continue;
                    int cx = (int) t[0], cz = (int) t[1];
                    if (cx + TERRACE_REACH < x0 || cx - TERRACE_REACH > x0 + 15 || cz + TERRACE_REACH < z0 || cz - TERRACE_REACH > z0 + 15) continue;
                    if (nearGeyser(seed, cx, cz, t[2] + 4) || nearPool(seed, cx, cz, t[2] + 3)) continue;
                    terraces(chunk, data, startX, startZ, scale, cx, cz, t[2], Double.doubleToRawLongBits(t[3]));
                }
            }
        }
        // Geysers: sinter cones with a vanilla geyser (potent sulfur over magma, water above) in a crater on top.
        for (int gz = Math.floorDiv(z0 - GEYSER_REACH, GEYSER_CELL); gz <= Math.floorDiv(z0 + 15 + GEYSER_REACH, GEYSER_CELL); gz++) {
            for (int gx = Math.floorDiv(x0 - GEYSER_REACH, GEYSER_CELL); gx <= Math.floorDiv(x0 + 15 + GEYSER_REACH, GEYSER_CELL); gx++) {
                double[] g = geyserParams(seed, gx, gz);
                if (g == null) continue;
                int cx = (int) g[0], cz = (int) g[1];
                if (cx + GEYSER_REACH < x0 || cx - GEYSER_REACH > x0 + 15 || cz + GEYSER_REACH < z0 || cz - GEYSER_REACH > z0 + 15) continue;
                geyser(chunk, data, startX, startZ, scale, cx, cz, g[2], (int) g[3], g[4] > 0, Double.doubleToRawLongBits(g[5]));
            }
        }
    }

    // ---- Geysers ----

    /** Cell (blocks) holding at most one geyser, the chance of one, and the farthest a geyser's cone and apron reach. */
    private static final int GEYSER_CELL = 56;
    private static final double GEYSER_CHANCE = 0.55, CONTINUOUS_CHANCE = 0.12;
    private static final int GEYSER_REACH = 12;

    /** Cell (gx, gz)'s candidate geyser {x, z, cone radius, rise, continuous (1/0), hash}, or null. */
    private static double[] geyserParams(long seed, int gx, int gz) {
        long h = mix(seed ^ ((long) gx * 0x632BE59BD9B4E019L) ^ ((long) gz * 0x85EBCA77C2B2AE63L) ^ 0x6E7L);
        if (unit(h) >= GEYSER_CHANCE) return null;
        int cx = gx * GEYSER_CELL + 12 + (int) ((GEYSER_CELL - 24) * unit(mix(h + 1)));
        int cz = gz * GEYSER_CELL + 12 + (int) ((GEYSER_CELL - 24) * unit(mix(h + 2)));
        double rc = 4 + 2.5 * unit(mix(h + 3));
        int rise = 2 + (int) (3 * unit(mix(h + 4)));
        return new double[]{cx, cz, rc, rise, unit(mix(h + 5)) < CONTINUOUS_CHANCE ? 1 : 0, Double.longBitsToDouble(h)};
    }

    /** Whether a geyser candidate stands within {@code reach} blocks (plus its cone) of (x, z). */
    private static boolean nearGeyser(long seed, double x, double z, double reach) {
        int gx0 = Math.floorDiv((int) x, GEYSER_CELL), gz0 = Math.floorDiv((int) z, GEYSER_CELL);
        for (int gz = gz0 - 1; gz <= gz0 + 1; gz++) {
            for (int gx = gx0 - 1; gx <= gx0 + 1; gx++) {
                double[] g = geyserParams(seed, gx, gz);
                if (g != null && Math.hypot(g[0] - x, g[1] - z) < reach + g[2] + 3) return true;
            }
        }
        return false;
    }

    /**
     * A geyser: a cone of sinter (calcite, white terracotta, yellow at the top) rising {@code rise} blocks over the
     * highest ground under it, a crater on top holding one block of water, and in its middle a vanilla geyser: potent
     * sulfur over magma (erupting every so often; over lava for the continuous ones). Around its foot, an apron of sulfur
     * with a few sulfur spikes. Reads the tiles, so cones across chunk and tile edges come out whole.
     */
    private static void geyser(ChunkAccess chunk, HeightmapData data, int startX, int startZ, int scale, int cx, int cz,
                               double rc, int rise, boolean continuous, long h) {
        HeightmapData centre = VolcanicSurfaces.tileAt(data, startX, startZ, cx, cz);
        if (centre == null || centre.oceanTags == null) return;
        int size = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(size);
        int lcx = cx - ((cx >> shift) << shift), lcz = cz - ((cz >> shift) << shift);
        if (centre.oceanTags[lcz][lcx] != Showpieces.TAG_GEOTHERMAL) return;
        int R = (int) Math.ceil(rc) + 4;
        int base = Integer.MIN_VALUE;
        for (int dz = -R; dz <= R; dz++) {
            for (int dx = -R; dx <= R; dx++) {
                if (Math.hypot(dx, dz) > rc) continue;
                HeightmapData t = VolcanicSurfaces.tileAt(data, startX, startZ, cx + dx, cz + dz);
                if (t == null) return;
                int lx = cx + dx - (((cx + dx) >> shift) << shift), lz = cz + dz - (((cz + dz) >> shift) << shift);
                if (t.waterY != null && t.waterY[lz][lx] != com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver.NONE) return;
                base = Math.max(base, VolcanicSurfaces.tileTop(data, startX, startZ, cx + dx, cz + dz, scale));
            }
        }
        if (base == Integer.MIN_VALUE) return;
        int top = base + rise;
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        BlockState sulfur = Blocks.SULFUR.defaultBlockState(), calcite = Blocks.CALCITE.defaultBlockState();
        BlockState white = Blocks.DYED_TERRACOTTA.white().defaultBlockState(), yellow = Blocks.DYED_TERRACOTTA.yellow().defaultBlockState();
        for (int z = Math.max(z0, cz - R); z <= Math.min(z0 + 15, cz + R); z++) {
            for (int x = Math.max(x0, cx - R); x <= Math.min(x0 + 15, cx + R); x++) {
                double d = Math.hypot(x - cx, z - cz);
                int ground = VolcanicSurfaces.tileTop(data, startX, startZ, x, z, scale);
                if (d < rc) {
                    // The cone: from the ground up to its surface, sinter inside, yellow and sulfur near the top.
                    int surf = d < 2.6 ? top : (int) Math.round(top - (top - base) * Math.pow((d - 2.6) / Math.max(0.5, rc - 2.6), 1.2));
                    for (int y = ground + 1; y <= Math.max(surf, ground) + 2; y++) chunk.setBlockState(pos.set(x, y, z), air, 0);
                    for (int y = ground + 1; y <= surf; y++) {
                        BlockState b = top - y <= 1 && d < 3.6 ? (RING.GetNoise(x * 2f, y * 3f, z * 2f) > 0f ? sulfur : yellow)
                                : RING.GetNoise(x * 1.3f + 77, y * 2f, z * 1.3f) > 0.15f ? calcite : white;
                        chunk.setBlockState(pos.set(x, y, z), b, 0);
                    }
                    if (d < 1.6) {
                        // The crater: one block of water over a yellow floor; the vent in the middle.
                        chunk.setBlockState(pos.set(x, top, z), air, 0);
                        chunk.setBlockState(pos.set(x, top - 1, z), water, 0);
                        boolean vent = x == cx && z == cz;
                        if (vent) {
                            BlockState potent = Blocks.POTENT_SULFUR.defaultBlockState().setValue(
                                    net.minecraft.world.level.block.PotentSulfurBlock.STATE,
                                    continuous ? net.minecraft.world.level.block.state.properties.PotentSulfurState.CONTINUOUS
                                            : net.minecraft.world.level.block.state.properties.PotentSulfurState.DORMANT);
                            chunk.setBlockState(pos.set(x, top - 2, z), potent, 0);
                            chunk.setBlockEntity(new net.minecraft.world.level.block.entity.PotentSulfurBlockEntity(pos.immutable(), potent));
                            chunk.setBlockState(pos.set(x, top - 3, z), continuous ? Blocks.LAVA.defaultBlockState() : Blocks.MAGMA_BLOCK.defaultBlockState(), 0);
                            chunk.setBlockState(pos.set(x, top - 4, z), Blocks.STONE.defaultBlockState(), 0);
                        } else {
                            chunk.setBlockState(pos.set(x, top - 2, z), yellow, 0);
                        }
                    }
                    continue;
                }
                // The apron: sulfur crust on the ground around the cone's foot, a few spikes on it.
                if (d > rc + 3.5 || RING.GetNoise(x * 1.1f + 900, z * 1.1f) < -0.2f) continue;
                BlockState st = chunk.getBlockState(pos.set(x, ground, z));
                if (st.isAir() || !st.getFluidState().isEmpty()) continue;
                BlockState above = chunk.getBlockState(pos.set(x, ground + 1, z));
                if (!above.isAir() && !above.is(Blocks.SNOW) && !above.is(Blocks.SHORT_GRASS)) continue;
                chunk.setBlockState(pos.set(x, ground, z), sulfur, 0);
                if (above.is(Blocks.SNOW) || above.is(Blocks.SHORT_GRASS)) chunk.setBlockState(pos.set(x, ground + 1, z), air, 0);
                if (unit(mix(h + 31L * x + 17L * z)) < 0.12) chunk.setBlockState(pos.set(x, ground + 1, z), Blocks.SULFUR_SPIKE.defaultBlockState(), 0);
            }
        }
    }

    // ---- Travertine terraces (part four round B) ----

    /** Cell (blocks) holding at most one terrace patch, the chance of one, and the farthest a patch reaches. */
    private static final int TERRACE_CELL = 48;
    private static final double TERRACE_CHANCE = 0.5;
    private static final int TERRACE_REACH = 17;

    /** Cell (gx, gz)'s candidate terraces {x, z, radius, hash}, or null. */
    private static double[] terraceParams(long seed, int gx, int gz) {
        long h = mix(seed ^ ((long) gx * 0x5851F42D4C957F2DL) ^ ((long) gz * 0x14057B7EF767814FL) ^ 0x7EAL);
        if (unit(h) >= TERRACE_CHANCE) return null;
        int cx = gx * TERRACE_CELL + 10 + (int) ((TERRACE_CELL - 20) * unit(mix(h + 1)));
        int cz = gz * TERRACE_CELL + 10 + (int) ((TERRACE_CELL - 20) * unit(mix(h + 2)));
        return new double[]{cx, cz, 9 + 6 * unit(mix(h + 3)), Double.longBitsToDouble(h)};
    }

    /** Whether a hot spring pool candidate lies within {@code reach} blocks (plus its rings) of (x, z). */
    private static boolean nearPool(long seed, double x, double z, double reach) {
        int gx0 = Math.floorDiv((int) x, CELL), gz0 = Math.floorDiv((int) z, CELL);
        int n = (int) Math.ceil((reach + REACH) / CELL) + 1;
        for (int gz = gz0 - n; gz <= gz0 + n; gz++) {
            for (int gx = gx0 - n; gx <= gx0 + n; gx++) {
                double[] p = params(seed, gx, gz);
                if (p != null && Math.hypot(p[0] - x, p[1] - z) < reach + p[2] + 4) return true;
            }
        }
        return false;
    }

    /**
     * Travertine terraces: on a geothermal slope (at least 1 in 5), the ground inside a ragged patch is cut into flat
     * steps (1 block high, 2 where the slope is steep). A step column whose four neighbours all stand at least as high
     * holds a pool of water over a calcite bed; the others are calcite, with a raised calcite lip beside the pools and
     * orange stains here and there. Every test reads the tiles, so a patch across chunk edges comes out whole.
     */
    private static void terraces(ChunkAccess chunk, HeightmapData data, int startX, int startZ, int scale, int cx, int cz,
                                 double r, long h) {
        HeightmapData centre = VolcanicSurfaces.tileAt(data, startX, startZ, cx, cz);
        if (centre == null || centre.oceanTags == null) return;
        int size = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(size);
        int lcx = cx - ((cx >> shift) << shift), lcz = cz - ((cz >> shift) << shift);
        if (centre.oceanTags[lcz][lcx] != Showpieces.TAG_GEOTHERMAL) return;
        int g0 = VolcanicSurfaces.tileTop(data, startX, startZ, cx, cz, scale);
        int ge = VolcanicSurfaces.tileTop(data, startX, startZ, cx + 8, cz, scale), gw = VolcanicSurfaces.tileTop(data, startX, startZ, cx - 8, cz, scale);
        int gs = VolcanicSurfaces.tileTop(data, startX, startZ, cx, cz + 8, scale), gn = VolcanicSurfaces.tileTop(data, startX, startZ, cx, cz - 8, scale);
        double slope = Math.max(Math.abs(ge - gw), Math.abs(gs - gn)) / 16.0;
        if (slope < 0.2) return;
        int step = slope > 0.6 ? 2 : 1;
        int R = (int) Math.ceil(r * 1.25) + 2, W = 2 * R + 1;
        int[] T = new int[W * W], G = new int[W * W];
        boolean[] in = new boolean[W * W];
        for (int dz = -R; dz <= R; dz++) {
            for (int dx = -R; dx <= R; dx++) {
                int x = cx + dx, z = cz + dz, i = (dz + R) * W + dx + R;
                if (edgeDist(x, z, cx, cz) >= r) continue;
                HeightmapData t = VolcanicSurfaces.tileAt(data, startX, startZ, x, z);
                if (t == null) return;
                int lx = x - ((x >> shift) << shift), lz = z - ((z >> shift) << shift);
                if (t.waterY != null && t.waterY[lz][lx] != com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver.NONE) return;
                int g = VolcanicSurfaces.tileTop(data, startX, startZ, x, z, scale);
                G[i] = g;
                T[i] = g0 + Math.floorDiv(g - g0, step) * step;
                in[i] = true;
            }
        }
        boolean[] pool = new boolean[W * W];
        for (int dz = -R + 1; dz < R; dz++) {
            for (int dx = -R + 1; dx < R; dx++) {
                int i = (dz + R) * W + dx + R;
                if (!in[i] || edgeDist(cx + dx, cz + dz, cx, cz) >= r - 1.2) continue;
                boolean ok = true;
                for (int k = 0; k < 4 && ok; k++) {
                    int j = i + (k == 0 ? 1 : k == 1 ? -1 : k == 2 ? W : -W);
                    ok = in[j] && T[j] >= T[i];
                }
                pool[i] = ok;
            }
        }
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        BlockState calcite = Blocks.CALCITE.defaultBlockState(), stain = Blocks.DYED_TERRACOTTA.orange().defaultBlockState();
        for (int z = Math.max(z0, cz - R); z <= Math.min(z0 + 15, cz + R); z++) {
            for (int x = Math.max(x0, cx - R); x <= Math.min(x0 + 15, cx + R); x++) {
                int i = (z - cz + R) * W + x - cx + R;
                if (!in[i]) continue;
                int t = T[i];
                for (int y = t + 1; y <= G[i] + 2; y++) chunk.setBlockState(pos.set(x, y, z), air, 0);
                chunk.setBlockState(pos.set(x, t - 1, z), calcite, 0);
                if (pool[i]) {
                    chunk.setBlockState(pos.set(x, t, z), water, 0);
                    chunk.setBlockState(pos.set(x, t - 2, z), calcite, 0);
                    continue;
                }
                boolean byPool = pool[i + 1] || pool[i - 1] || pool[i + W] || pool[i - W];
                boolean orange = byPool && RING.GetNoise(x * 1.9f + 51, z * 1.9f) > 0.45f;
                chunk.setBlockState(pos.set(x, t, z), orange ? stain : calcite, 0);
                if (byPool) chunk.setBlockState(pos.set(x, t + 1, z), calcite, 0);
            }
        }
    }

    /** Cell (gx, gz)'s candidate pool {centre x, centre z, radius, order, hash}, or null if the cell has none. */
    private static double[] params(long seed, int gx, int gz) {
        long h = mix(seed ^ ((long) gx * 0x9E3779B97F4A7C15L) ^ ((long) gz * 0xC2B2AE3D27D4EB4FL) ^ 0x4075L);
        if (unit(h) >= CHANCE) return null;
        int cx = gx * CELL + 3 + (int) ((CELL - 6) * unit(mix(h + 1)));
        int cz = gz * CELL + 3 + (int) ((CELL - 6) * unit(mix(h + 2)));
        return new double[]{cx, cz, radius(h), unit(mix(h + 9)), Double.longBitsToDouble(h)};
    }

    private static double radius(long h) {
        return 2.5 + 5.0 * unit(mix(h + 3));
    }

    private static void pool(ChunkAccess chunk, HeightmapData data, int startX, int startZ, int scale, int cx, int cz, long h) {
        HeightmapData centre = VolcanicSurfaces.tileAt(data, startX, startZ, cx, cz);
        if (centre == null || centre.oceanTags == null) return;
        int size = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(size);
        int lcx = cx - ((cx >> shift) << shift), lcz = cz - ((cz >> shift) << shift);
        if (centre.oceanTags[lcz][lcx] != Showpieces.TAG_GEOTHERMAL) return;
        double r = radius(h);
        int depth = r > 5 ? 3 : 2;
        boolean boiling = unit(mix(h + 4)) < 0.33;
        int R = (int) Math.ceil(r * 1.25) + 4;
        // The water level: the lowest ground in and around the pool; no pool where a river or lake already runs.
        int level = Integer.MAX_VALUE;
        for (int dz = -R; dz <= R; dz++) {
            for (int dx = -R; dx <= R; dx++) {
                double d = edgeDist(cx + dx, cz + dz, cx, cz);
                if (d > r + 1.5) continue;
                HeightmapData t = VolcanicSurfaces.tileAt(data, startX, startZ, cx + dx, cz + dz);
                if (t == null) return;
                int lx = cx + dx - (((cx + dx) >> shift) << shift), lz = cz + dz - (((cz + dz) >> shift) << shift);
                if (t.waterY != null && t.waterY[lz][lx] != com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver.NONE) return;
                level = Math.min(level, VolcanicSurfaces.tileTop(data, startX, startZ, cx + dx, cz + dz, scale));
            }
        }
        if (level == Integer.MAX_VALUE || level <= chunk.getMinY() + depth + 2) return;
        if (!loggedFirst) {
            loggedFirst = true;
            LOG.info("Terra: first hot spring at {} {} {}", cx, level, cz);
        }
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        BlockState bubbles = Blocks.BUBBLE_COLUMN.defaultBlockState().setValue(BubbleColumnBlock.DRAG_DOWN, false);
        for (int z = Math.max(z0, cz - R); z <= Math.min(z0 + 15, cz + R); z++) {
            for (int x = Math.max(x0, cx - R); x <= Math.min(x0 + 15, cx + R); x++) {
                double d = edgeDist(x, z, cx, cz);
                int top = VolcanicSurfaces.tileTop(data, startX, startZ, x, z, scale);
                if (d < r) {
                    // The bowl: open water down to the floor, everything above the water cleared.
                    double f = d / r;
                    int dep = Math.max(1, (int) Math.round(depth * (1 - f * f)));
                    for (int y = level + 1; y <= Math.max(top, level) + 2; y++) chunk.setBlockState(pos.set(x, y, z), air, 0);
                    boolean vent = boiling && d < 0.8;
                    for (int y = level - dep + 1; y <= level; y++) chunk.setBlockState(pos.set(x, y, z), vent ? bubbles : water, 0);
                    BlockState floor = vent ? Blocks.SOUL_SAND.defaultBlockState()
                            : f < 0.45 ? Blocks.DYED_TERRACOTTA.lightBlue().defaultBlockState() : Blocks.DYED_TERRACOTTA.cyan().defaultBlockState();
                    chunk.setBlockState(pos.set(x, level - dep, z), floor, 0);
                    continue;
                }
                // The rings: yellow, then orange, then patchy brown, on the top block of the ground (and the one under it).
                double q = d - r + 0.9 * RING.GetNoise(x, z);
                BlockState ring;
                if (q < 1.3) ring = Blocks.DYED_TERRACOTTA.yellow().defaultBlockState();
                else if (q < 2.8) ring = Blocks.DYED_TERRACOTTA.orange().defaultBlockState();
                else if (q < 3.8 && RING.GetNoise(x * 1.7f + 300, z * 1.7f) > -0.1f) ring = Blocks.TERRACOTTA.defaultBlockState();
                else continue;
                int y = top;
                BlockState above = chunk.getBlockState(pos.set(x, y + 1, z));
                if (above.is(Blocks.SNOW) || above.is(Blocks.SHORT_GRASS) || above.is(Blocks.FERN)) chunk.setBlockState(pos, air, 0);
                BlockState st = chunk.getBlockState(pos.set(x, y, z));
                if (st.isAir() || !st.getFluidState().isEmpty()) continue;
                chunk.setBlockState(pos, ring, 0);
                if (!chunk.getBlockState(pos.set(x, y - 1, z)).isAir()) chunk.setBlockState(pos, ring, 0);
            }
        }
    }

    /** Distance (blocks) from the pool's centre, roughened so the pool's edge wanders. */
    private static double edgeDist(int x, int z, int cx, int cz) {
        double dx = x + 0.5 - (cx + 0.5), dz = z + 0.5 - (cz + 0.5);
        return Math.sqrt(dx * dx + dz * dz) * (1 + 0.22 * EDGE.GetNoise(x, z));
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
