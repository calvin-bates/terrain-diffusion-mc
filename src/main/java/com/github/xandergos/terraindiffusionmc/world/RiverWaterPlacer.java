package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fills river and lake columns with water while a chunk's terrain is built.
 *
 * <p>Vanilla only floods terrain up to sea level, so inland water is placed here, right after the
 * terrain pass (see {@code NoiseBasedChunkGeneratorMixin}), while the chunk is still a plain
 * generation chunk. The terrain itself was already carved by {@link RiverCarver} (through the
 * heightmap tile), and the tile also records the Y of the highest water block for each column.
 */
public final class RiverWaterPlacer {
    private static final Logger LOG = LoggerFactory.getLogger(RiverWaterPlacer.class);
    private static final int MIN_Y = -64;
    private static volatile boolean loggedFirst;
    private static volatile boolean loggedFreeze;

    private RiverWaterPlacer() {
    }

    public static void place(ChunkAccess chunk) {
        try {
            placeUnchecked(chunk);
        } catch (Throwable t) {
            // Never let water placement break chunk generation.
            LOG.error("Terra rivers: water placement failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static void placeUnchecked(ChunkAccess chunk) {
        if (!TerrainDiffusionConfig.riversEnabled() || !TerrainDiffusionConfig.riversPlaceWater()) return;

        ChunkPos chunkPos = chunk.getPos();
        int x0 = chunkPos.getMinBlockX();
        int z0 = chunkPos.getMinBlockZ();

        int tileSize = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        int startX = (x0 >> shift) << shift;
        int startZ = (z0 >> shift) << shift;

        // The terrain pass just used this tile, so it is cached. Never generate it from here.
        HeightmapData data = LocalTerrainProvider.peekHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
        if (!loggedFirst) {
            loggedFirst = true;
            LOG.info("Terra rivers: first chunk reached the water placer (tile cached: {})", data != null);
        }
        if (data == null || data.waterY == null) return;

        boolean freezeOn = TerrainDiffusionConfig.riverFreezeEnabled();
        float freezeM = TerrainDiffusionConfig.riverFreezeHeightMetres();
        int freezeY = freezeM <= 0f ? Integer.MIN_VALUE
                : HeightConverter.convertToMinecraftHeight((short) Math.min(freezeM, 30000f), Math.max(1, WorldScaleManager.getCurrentScale()));
        if (!loggedFreeze) {
            loggedFreeze = true;
            LOG.info("Terra rivers: water freezes at Y >= {} (freeze_height_m={}, scale={})",
                    freezeY, freezeM, WorldScaleManager.getCurrentScale());
        }
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState ice = Blocks.ICE.defaultBlockState();
        BlockState gravel = Blocks.GRAVEL.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = x0 + dx, z = z0 + dz;
                int lx = x - startX, lz = z - startZ;
                short top = data.waterY[lz][lx];
                if (top == RiverCarver.NONE) continue;

                boolean frozen = freezeOn && (freezeM > 0f ? top >= freezeY : frozenAround(data.biomeIds, lz, lx));
                short bed = data.surfaceY != null ? data.surfaceY[lz][lx] : RiverCarver.NONE;
                boolean isLake = data.waterIsLake != null && data.waterIsLake[lz][lx];
                // Find the ground under the water line. Whether the bed block itself is solid depends on
                // how the terrain pass rounds, so just look for the first solid block from the top down.
                int capY = bed != RiverCarver.NONE ? bed - 2 : top - 12;
                int ground = Integer.MIN_VALUE;
                for (int y = top; y >= capY && y > MIN_Y; y--) {
                    pos.set(x, y, z);
                    if (!chunk.getBlockState(pos).isAir()) { ground = y; break; }
                }
                if (ground == Integer.MIN_VALUE) {
                    // Nothing solid down to the bed: a cave runs right under the channel.
                    if (!isLake) {
                        // A stream that meets a cave goes underground: leave a solid ford.
                        for (int y = top; y >= capY && y > MIN_Y; y--) {
                            pos.set(x, y, z);
                            BlockState fill = matchingFill(chunk, x, y, z, x0, z0, stone);
                            if (y == top && fill.is(Blocks.DIRT)) fill = Blocks.GRASS_BLOCK.defaultBlockState();
                            chunk.setBlockState(pos, fill, 0);
                        }
                        continue;
                    }
                    // A lake just gets a plugged floor.
                    pos.set(x, capY, z);
                    chunk.setBlockState(pos, gravel, 0);
                    ground = capY;
                }
                for (int y = top; y > ground; y--) {
                    pos.set(x, y, z);
                    chunk.setBlockState(pos, (frozen && y == top) ? ice : water, 0);
                }
            }
        }

        // Seal gaps beside the water: a cave mouth or hollow touching a river or lake would otherwise
        // leave a bare wall of water. Fill those blocks up to the water line with stone.
        int tileH = data.waterY.length, tileW = data.waterY[0].length;
        int[] ddz = {-1, 1, 0, 0}, ddx = {0, 0, -1, 1};
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = x0 + dx, z = z0 + dz;
                int lx = x - startX, lz = z - startZ;
                if (data.waterY[lz][lx] != RiverCarver.NONE) continue;
                for (int k = 0; k < 4; k++) {
                    int nz = lz + ddz[k], nx = lx + ddx[k];
                    if (nz < 0 || nx < 0 || nz >= tileH || nx >= tileW) continue;
                    short nTop = data.waterY[nz][nx];
                    if (nTop == RiverCarver.NONE) continue;
                    short nBed = data.surfaceY != null ? data.surfaceY[nz][nx] : RiverCarver.NONE;
                    int low = nBed != RiverCarver.NONE ? nBed : nTop - 3;
                    for (int y = nTop; y >= low && y > MIN_Y; y--) {
                        pos.set(x, y, z);
                        if (chunk.getBlockState(pos).isAir()) {
                            chunk.setBlockState(pos, matchingFill(chunk, x, y, z, x0, z0, stone), 0);
                        }
                    }
                }
            }
        }
    }

    /**
     * Picks a block for a gap beside the water so it blends in: the solid block next to it at the same
     * height, else the first solid block below it. Grass-like tops become dirt when buried.
     */
    private static BlockState matchingFill(ChunkAccess chunk, int x, int y, int z, int x0, int z0, BlockState fallback) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int[] ox = {-1, 1, 0, 0}, oz = {0, 0, -1, 1};
        BlockState found = null;
        for (int k = 0; k < 4 && found == null; k++) {
            int nx = x + ox[k], nz = z + oz[k];
            if (nx < x0 || nx > x0 + 15 || nz < z0 || nz > z0 + 15) continue;
            BlockState st = chunk.getBlockState(p.set(nx, y, nz));
            if (isFillable(st)) found = st;
        }
        for (int d = 1; d <= 16 && found == null && y - d > MIN_Y; d++) {
            BlockState st = chunk.getBlockState(p.set(x, y - d, z));
            if (isFillable(st)) found = st;
        }
        if (found == null) return fallback;
        boolean airAbove = chunk.getBlockState(p.set(x, y + 1, z)).isAir();
        if (!airAbove && (found.is(Blocks.GRASS_BLOCK) || found.is(Blocks.PODZOL) || found.is(Blocks.MYCELIUM))) {
            return Blocks.DIRT.defaultBlockState();
        }
        return found;
    }

    private static boolean isFillable(BlockState st) {
        return !st.isAir() && st.getFluidState().isEmpty() && !st.is(Blocks.ICE) && st.canOcclude();
    }

    /** Water only freezes well inside snowy biomes, so streams never ice up at the edge of the snow line. */
    private static boolean frozenAround(short[][] biomeIds, int lz, int lx) {
        int h = biomeIds.length, w = biomeIds[0].length;
        for (int dz = -8; dz <= 8; dz += 4) {
            for (int dx = -8; dx <= 8; dx += 4) {
                int z = Math.max(0, Math.min(h - 1, lz + dz));
                int x = Math.max(0, Math.min(w - 1, lx + dx));
                if (!RiverCarver.isFrozen(biomeIds[z][x])) return false;
            }
        }
        return true;
    }
}
