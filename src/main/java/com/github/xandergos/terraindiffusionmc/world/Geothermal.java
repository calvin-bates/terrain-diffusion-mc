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
 */
public final class Geothermal {
    private static final Logger LOG = LoggerFactory.getLogger(Geothermal.class);

    /** Cell (blocks) holding at most one pool, chance of one, and the largest pool radius plus rings. */
    private static final int CELL = 18;
    private static final double CHANCE = 0.65;
    private static final int REACH = 13;

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
                if (blocked) continue;
                pool(chunk, data, startX, startZ, scale, cx, cz, Double.doubleToRawLongBits(p[4]));
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
