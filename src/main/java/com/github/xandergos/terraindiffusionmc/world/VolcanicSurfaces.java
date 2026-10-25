package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.Landforms;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.pipeline.RegionalBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Surfaces of the landform stage's volcanoes (biomes plan v1.4 round A), right after the terrain pass, from the tile's
 * surface tags ({@link Landforms#TAG_LAVA} and the others). The volcano and lava field biomes already give the slopes
 * their ash and rock through their surface rules; this lays the exact shapes on top:
 * <ul>
 *   <li><b>Lava flows</b>: fresh rock (blackstone, basalt, a little smooth basalt) three blocks deep, with the odd
 *   magma block in a hollow.</li>
 *   <li><b>Live lava</b> along the middle of an active volcano's freshest flows and in its crater: only where the
 *   ground is level or a hollow (so nothing can run out, checked on the tile across chunk edges) and every column
 *   within three blocks is bare lava rock (nothing to set alight). Magma under it.</li>
 *   <li><b>Craters</b>: blackstone and tuff, with obsidian near the vent.</li>
 * </ul>
 * Snowy ground keeps its snow (a snow-capped volcano), except where live lava lies.
 */
public final class VolcanicSurfaces {
    private static final Logger LOG = LoggerFactory.getLogger(VolcanicSurfaces.class);

    /** Blocks of fresh rock under a flow's surface. */
    private static final int ROCK_DEPTH = 3;
    /** Share of level spots on a cooled flow with a magma block. */
    private static final float MAGMA_CHANCE = 0.05f;
    /** Live lava needs this many blocks of bare lava rock around it in every direction. */
    private static final int CLEARANCE = 3;

    private static final FastNoiseLite PATCHES = noise(77301, 1f / 9f);
    private static final FastNoiseLite STRATA = noise(77302, 1f / 6f);

    private static volatile boolean loggedFirst;

    private VolcanicSurfaces() {
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
            if (!TerrainDiffusionConfig.volcanoes()) return;
            placeUnchecked(chunk);
        } catch (Throwable t) {
            LOG.error("Terra: volcanic surfaces failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static void placeUnchecked(ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        int tileSize = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(tileSize);
        int startX = (x0 >> shift) << shift, startZ = (z0 >> shift) << shift;
        HeightmapData data = LocalTerrainProvider.peekHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
        if (data == null || data.oceanTags == null) return;
        int scale = WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
        long seed = LocalTerrainProvider.getSeed();
        int minY = chunk.getMinY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = x0 + dx, z = z0 + dz, lx = x - startX, lz = z - startZ;
                byte tag = data.oceanTags[lz][lx];
                if (tag != Landforms.TAG_LAVA && tag != Landforms.TAG_LAVA_CHANNEL && tag != Landforms.TAG_LAVA_LAKE
                        && tag != Landforms.TAG_CRATER) continue;
                if (!loggedFirst) {
                    loggedFirst = true;
                    LOG.info("Terra: first chunk with volcanic surfaces at {}", cp);
                }
                long h = mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ 0x7A1AL);
                column(chunk, data, pos, x, z, lx, lz, h, tag, minY, scale);
            }
        }
    }

    private static void column(ChunkAccess chunk, HeightmapData data, BlockPos.MutableBlockPos pos, int x, int z,
                               int lx, int lz, long h, byte tag, int minY, int scale) {
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
        while (y > minY) {
            BlockState st = chunk.getBlockState(pos.set(x, y, z));
            if (!st.isAir() && st.getFluidState().isEmpty() && !st.is(Blocks.SNOW)) break;
            y--;
        }
        if (y <= minY) return;
        if (!chunk.getBlockState(pos.set(x, y + 1, z)).getFluidState().isEmpty()) return;   // under water: leave it
        boolean snowy = com.github.xandergos.terraindiffusionmc.pipeline.CoastShaper.isSnowy(data.biomeIds[lz][lx]);
        boolean liveTag = tag == Landforms.TAG_LAVA_CHANNEL || tag == Landforms.TAG_LAVA_LAKE;
        if (snowy && !liveTag) return;
        for (int yy = y; yy >= Math.max(minY + 1, y - ROCK_DEPTH); yy--) {
            BlockState st = chunk.getBlockState(pos.set(x, yy, z));
            if (!isGround(st)) continue;
            chunk.setBlockState(pos, tag == Landforms.TAG_CRATER ? craterRockAt(x, yy, z, h) : lavaRockAt(x, yy, z), 0);
        }
        BlockState above = chunk.getBlockState(pos.set(x, y + 1, z));
        if (above.is(Blocks.SNOW)) chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), 0);
        else if (!above.isAir()) return;
        // Live lava and magma only where the ground is level or a hollow, so nothing can run out.
        int own = tileTop(data, lx, lz, scale);
        if (own != y || !levelOrHollow(data, lx, lz, scale, own)) return;
        if (liveTag && clearOfFuel(data, lx, lz)) {
            chunk.setBlockState(pos.set(x, y, z), Blocks.LAVA.defaultBlockState(), 0);
            chunk.setBlockState(pos.set(x, y - 1, z), Blocks.MAGMA_BLOCK.defaultBlockState(), 0);
        } else if (tag == Landforms.TAG_LAVA && unit(mix(h + 9)) < MAGMA_CHANCE) {
            chunk.setBlockState(pos.set(x, y, z), Blocks.MAGMA_BLOCK.defaultBlockState(), 0);
        }
    }

    /** Fresh lava rock: blackstone and basalt in patches, a little smooth basalt. */
    private static BlockState lavaRockAt(int x, int y, int z) {
        float p = PATCHES.GetNoise(x * 1.3f + 1500, z * 1.3f);
        float q = STRATA.GetNoise(x * 0.8f - 900, y * 2f, z * 0.8f);
        if (q > 0.55f) return Blocks.SMOOTH_BASALT.defaultBlockState();
        return p > 0.1f ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.BASALT.defaultBlockState();
    }

    /** Crater floors and walls: blackstone and tuff, the odd obsidian. */
    private static BlockState craterRockAt(int x, int y, int z, long h) {
        if (unit(mix(h + y)) < 0.04) return Blocks.OBSIDIAN.defaultBlockState();
        float p = PATCHES.GetNoise(x * 1.7f - 400, z * 1.7f + 90);
        return p > 0f ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.TUFF.defaultBlockState();
    }

    private static boolean isGround(BlockState st) {
        return st.is(Blocks.STONE) || st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT)
                || st.is(Blocks.PODZOL) || st.is(Blocks.GRAVEL) || st.is(Blocks.SAND) || st.is(Blocks.RED_SAND)
                || st.is(Blocks.ANDESITE) || st.is(Blocks.TUFF) || st.is(Blocks.BASALT) || st.is(Blocks.BLACKSTONE)
                || st.is(Blocks.SMOOTH_BASALT) || st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.POWDER_SNOW)
                || st.is(Blocks.MUD) || st.is(Blocks.CLAY) || st.is(Blocks.DIORITE) || st.is(Blocks.GRANITE)
                || st.is(Blocks.CALCITE) || st.is(Blocks.MOSS_BLOCK) || st.is(Blocks.ROOTED_DIRT)
                || st.is(Blocks.SANDSTONE) || st.is(Blocks.TERRACOTTA) || st.is(Blocks.PACKED_ICE);
    }

    /** Top solid block Y of tile column (lx, lz) from the tile (rivers included), or MIN_VALUE outside the tile. */
    private static int tileTop(HeightmapData data, int lx, int lz, int scale) {
        if (lz < 0 || lx < 0 || lz >= data.heightmap.length || lx >= data.heightmap[0].length) return Integer.MIN_VALUE;
        if (data.surfaceY != null && data.surfaceY[lz][lx] != com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver.NONE) {
            return data.surfaceY[lz][lx];
        }
        return HeightConverter.convertToMinecraftHeight(data.heightmap[lz][lx], scale) - 1;
    }

    /** Whether all eight neighbours of tile column (lx, lz) stand at least as high as {@code own}. */
    private static boolean levelOrHollow(HeightmapData data, int lx, int lz, int scale, int own) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && tileTop(data, lx + dx, lz + dz, scale) < own) return false;
            }
        }
        return true;
    }

    /** Whether every column within {@link #CLEARANCE} blocks is bare lava rock or crater (nothing to set alight). */
    private static boolean clearOfFuel(HeightmapData data, int lx, int lz) {
        byte[][] t = data.oceanTags;
        for (int dz = -CLEARANCE; dz <= CLEARANCE; dz++) {
            for (int dx = -CLEARANCE; dx <= CLEARANCE; dx++) {
                int r = lz + dz, c = lx + dx;
                if (r < 0 || c < 0 || r >= t.length || c >= t[0].length) return false;
                byte v = t[r][c];
                if (v != Landforms.TAG_LAVA && v != Landforms.TAG_LAVA_CHANNEL && v != Landforms.TAG_LAVA_LAKE
                        && v != Landforms.TAG_CRATER) return false;
                short b = data.biomeIds[r][c];
                if (b != RegionalBiomes.LAVA_FIELDS && b != RegionalBiomes.VOLCANO && !com.github.xandergos.terraindiffusionmc.pipeline.CoastShaper.isSnowy(b)) return false;
            }
        }
        return true;
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
