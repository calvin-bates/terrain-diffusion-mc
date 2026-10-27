package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.pipeline.RegionalBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Yellowstone's pools, Terralith's way (Oct 27), right after the terrain pass. Terralith scatters small pools over its
 * Yellowstone and stains everything around the water with "acid" disks: yellow terracotta at the water's edge, then red
 * sand, blackstone, smooth basalt and gravel further out, magma on the beds and lit campfires under some of that magma
 * whose smoke rises through the water like steam. Terralith's pools sit at sea level (its Yellowstone does); Terra's
 * lie in hollows of the ground wherever Yellowstone is, at the level of the lowest ground around each one.
 *
 * <p>One candidate pool per {@link #CELL}-block cell; it forms where the tile's biome is Yellowstone and no river or
 * lake runs. Every test reads the tiles (neighbouring ones too), so pools across chunk and tile edges come out whole.
 */
public final class Yellowstone {
    private static final Logger LOG = LoggerFactory.getLogger(Yellowstone.class);

    private static final int CELL = 11;
    private static final double CHANCE = 0.6;
    /** Largest pool radius plus its stained rim. */
    private static final int REACH = 13;

    private static final FastNoiseLite EDGE = noise(77601, 1f / 3.5f);
    private static final FastNoiseLite RING = noise(77602, 1f / 3f);

    private Yellowstone() {
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
            LOG.error("Terra: Yellowstone pools failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static boolean isYellowstone(HeightmapData data, int startX, int startZ, int x, int z) {
        HeightmapData t = VolcanicSurfaces.tileAt(data, startX, startZ, x, z);
        if (t == null || t.biomeIds == null) return false;
        int shift = Integer.numberOfTrailingZeros(TerrainDiffusionConfig.tileSize());
        int lx = x - ((x >> shift) << shift), lz = z - ((z >> shift) << shift);
        return t.biomeIds[lz][lx] == RegionalBiomes.YELLOWSTONE;
    }

    private static void placeUnchecked(ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        int tileSize = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(tileSize);
        int startX = (x0 >> shift) << shift, startZ = (z0 >> shift) << shift;
        HeightmapData data = LocalTerrainProvider.peekHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
        if (data == null || data.biomeIds == null) return;
        boolean any = false;
        for (int z = Math.max(0, z0 - startZ - REACH); z < Math.min(tileSize, z0 - startZ + 16 + REACH) && !any; z += 3)
            for (int x = Math.max(0, x0 - startX - REACH); x < Math.min(tileSize, x0 - startX + 16 + REACH) && !any; x += 3)
                any = data.biomeIds[z][x] == RegionalBiomes.YELLOWSTONE;
        if (!any) return;
        int scale = WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
        long seed = LocalTerrainProvider.getSeed();
        for (int gz = Math.floorDiv(z0 - REACH, CELL); gz <= Math.floorDiv(z0 + 15 + REACH, CELL); gz++) {
            for (int gx = Math.floorDiv(x0 - REACH, CELL); gx <= Math.floorDiv(x0 + 15 + REACH, CELL); gx++) {
                double[] p = params(seed, gx, gz);
                if (p == null) continue;
                int cx = (int) p[0], cz = (int) p[1];
                if (cx + REACH < x0 || cx - REACH > x0 + 15 || cz + REACH < z0 || cz - REACH > z0 + 15) continue;
                // A pool that would run into a neighbour's gives way to the one with the lower hash.
                boolean blocked = false;
                for (int nz = gz - 1; nz <= gz + 1 && !blocked; nz++)
                    for (int nx = gx - 1; nx <= gx + 1 && !blocked; nx++) {
                        if (nx == gx && nz == gz) continue;
                        double[] q = params(seed, nx, nz);
                        if (q != null && q[3] < p[3] && Math.hypot(q[0] - p[0], q[1] - p[1]) < (q[2] + p[2]) * 1.3 + 3) blocked = true;
                    }
                if (blocked) continue;
                pool(chunk, data, startX, startZ, scale, cx, cz, p[2], Double.doubleToRawLongBits(p[4]));
            }
        }
    }

    /** Cell (gx, gz)'s candidate pool {x, z, radius, order, hash}, or null. */
    private static double[] params(long seed, int gx, int gz) {
        long h = mix(seed ^ ((long) gx * 0x632BE59BD9B4E019L) ^ ((long) gz * 0x85EBCA77C2B2AE63L) ^ 0x7E11L);
        if (unit(h) >= CHANCE) return null;
        int cx = gx * CELL + 2 + (int) ((CELL - 4) * unit(mix(h + 1)));
        int cz = gz * CELL + 2 + (int) ((CELL - 4) * unit(mix(h + 2)));
        double r = 1.5 + 3.0 * unit(mix(h + 3));
        return new double[]{cx, cz, r, unit(mix(h + 4)), Double.longBitsToDouble(h)};
    }

    private static void pool(ChunkAccess chunk, HeightmapData data, int startX, int startZ, int scale, int cx, int cz,
                             double r, long h) {
        if (!isYellowstone(data, startX, startZ, cx, cz)) return;
        int size = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(size);
        int depth = r > 3 ? 2 : 1;
        int R = (int) Math.ceil(r * 1.25) + 9;
        // The water level: the lowest ground in and just around the pool; no pool where a river or lake already runs.
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
        if (level == Integer.MAX_VALUE || level <= chunk.getMinY() + depth + 3) return;
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        for (int z = Math.max(z0, cz - R); z <= Math.min(z0 + 15, cz + R); z++) {
            for (int x = Math.max(x0, cx - R); x <= Math.min(x0 + 15, cx + R); x++) {
                double d = edgeDist(x, z, cx, cz);
                int top = VolcanicSurfaces.tileTop(data, startX, startZ, x, z, scale);
                if (d < r) {
                    double f = d / r;
                    int dep = Math.max(1, (int) Math.round(depth * (1 - f * f)));
                    for (int y = level + 1; y <= Math.max(top, level) + 2; y++) chunk.setBlockState(pos.set(x, y, z), air, 0);
                    for (int y = level - dep + 1; y <= level; y++) chunk.setBlockState(pos.set(x, y, z), water, 0);
                    // The bed: magma (Terralith's underwater magma) in patches, red sand and blackstone stains elsewhere;
                    // under some of the magma a lit campfire, whose smoke rises out of the pool.
                    long bh = mix(h ^ ((long) x * 0x9E3779B97F4A7C15L) ^ ((long) z * 0xC2B2AE3D27D4EB4FL));
                    int bed = level - dep;
                    if (unit(bh) < 0.3) {
                        chunk.setBlockState(pos.set(x, bed, z), Blocks.MAGMA_BLOCK.defaultBlockState(), 0);
                        if (unit(mix(bh + 1)) < 0.3) {
                            BlockState fire = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true)
                                    .setValue(CampfireBlock.SIGNAL_FIRE, unit(mix(bh + 2)) < 0.25)
                                    .setValue(CampfireBlock.FACING, Direction.NORTH);
                            chunk.setBlockState(pos.set(x, bed - 1, z), fire, 0);
                            chunk.setBlockEntity(new CampfireBlockEntity(pos.immutable(), fire));
                        }
                    } else {
                        chunk.setBlockState(pos.set(x, bed, z), RING.GetNoise(x * 2f, z * 2f) > 0.1f
                                ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.RED_SAND.defaultBlockState(), 0);
                    }
                    continue;
                }
                // The stained rim (Terralith's acid disks around the water): yellow, red, black, grey, then gravel.
                double q = d - r + 0.9 * RING.GetNoise(x, z);
                BlockState ring;
                if (q < 1.2) ring = Blocks.DYED_TERRACOTTA.yellow().defaultBlockState();
                else if (q < 2.8) ring = Blocks.RED_SAND.defaultBlockState();
                else if (q < 3.8) ring = Blocks.BLACKSTONE.defaultBlockState();
                else if (q < 5.6) ring = Blocks.SMOOTH_BASALT.defaultBlockState();
                else if (q < 7.8 && RING.GetNoise(x * 1.7f + 300, z * 1.7f) > -0.25f) ring = Blocks.GRAVEL.defaultBlockState();
                else continue;
                int y = top;
                BlockState above = chunk.getBlockState(pos.set(x, y + 1, z));
                if (above.is(Blocks.SNOW) || above.is(Blocks.SHORT_GRASS) || above.is(Blocks.FERN)) chunk.setBlockState(pos, air, 0);
                BlockState st = chunk.getBlockState(pos.set(x, y, z));
                if (st.isAir() || !st.getFluidState().isEmpty()) continue;
                chunk.setBlockState(pos, ring, 0);
                // Red sand needs a floor under it (it falls): keep the block below solid.
                BlockState below = chunk.getBlockState(pos.set(x, y - 1, z));
                if (below.isAir()) chunk.setBlockState(pos, Blocks.CALCITE.defaultBlockState(), 0);
            }
        }
    }

    /** Distance (blocks) from the pool's centre, roughened so the pool's edge wanders. */
    private static double edgeDist(int x, int z, int cx, int cz) {
        double dx = x + 0.5 - (cx + 0.5), dz = z + 0.5 - (cz + 0.5);
        return Math.sqrt(dx * dx + dz * dz) * (1 + 0.25 * EDGE.GetNoise(x, z));
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
