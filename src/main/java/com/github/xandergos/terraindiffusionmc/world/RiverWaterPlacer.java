package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fills river and lake columns with water when a chunk is generated.
 *
 * <p>Vanilla only floods terrain up to sea level, so inland water is placed here. The terrain
 * itself was already carved by {@link RiverCarver} (through the heightmap tile), and the tile also
 * records the Y of the highest water block for each column.
 */
public final class RiverWaterPlacer {
    private static final Logger LOG = LoggerFactory.getLogger(RiverWaterPlacer.class);
    private static final int MIN_Y = -64;
    /** Block update flag: do not run onPlace (it would schedule a fluid tick for every water block). */
    private static final int SKIP_ON_PLACE = 1024;
    private static volatile boolean loggedFirst;

    private RiverWaterPlacer() {
    }

    public static void register() {
        ServerChunkEvents.CHUNK_GENERATE.register(RiverWaterPlacer::onChunkGenerate);
    }

    private static void onChunkGenerate(ServerLevel level, LevelChunk chunk) {
        try {
            place(level, chunk);
        } catch (Throwable t) {
            // Never let water placement break chunk generation.
            LOG.error("Terra rivers: water placement failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static void place(ServerLevel level, LevelChunk chunk) {
        if (!TerrainDiffusionConfig.riversEnabled() || !TerrainDiffusionConfig.riversPlaceWater()) return;
        if (!(level.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return;

        ChunkPos chunkPos = chunk.getPos();
        int x0 = chunkPos.getMinBlockX();
        int z0 = chunkPos.getMinBlockZ();

        int tileSize = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        int startX = (x0 >> shift) << shift;
        int startZ = (z0 >> shift) << shift;

        HeightmapData data = LocalTerrainProvider.peekHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
        if (data == null) {
            // Not cached any more (evicted). Skip rather than block the server thread on terrain inference.
            return;
        }
        if (!loggedFirst) {
            loggedFirst = true;
            LOG.info("Terra rivers: first generated chunk reached the water placer (tile cached: {})", data != null);
        }
        if (data == null || data.waterY == null) return;

        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState ice = Blocks.ICE.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = x0 + dx, z = z0 + dz;
                int lx = x - startX, lz = z - startZ;
                short top = data.waterY[lz][lx];
                if (top == RiverCarver.NONE) continue;

                boolean frozen = data.biomeIds[lz & ~3][lx & ~3] == RiverCarver.BIOME_FROZEN_RIVER;
                // Fill downwards from the water line until we hit the ground.
                for (int y = top; y > MIN_Y; y--) {
                    pos.set(x, y, z);
                    if (!chunk.getBlockState(pos).isAir()) break;
                    chunk.setBlockState(pos, (frozen && y == top) ? ice : water, SKIP_ON_PLACE);
                }
            }
        }
    }
}
