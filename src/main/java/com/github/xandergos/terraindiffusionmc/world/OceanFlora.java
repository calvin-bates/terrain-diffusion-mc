package com.github.xandergos.terraindiffusionmc.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Keeps kelp and seagrass in the sunlit shallows.
 *
 * <p>Vanilla grows them on any sea floor, however deep. Real kelp forests and seagrass meadows need light and stop at
 * a few tens of metres. Terra's oceans now go down to the abyss, so after decoration this clears them from sea water
 * deeper than {@link #LIGHT_LIMIT_M} (at least {@link #MIN_LIMIT_BLOCKS} blocks). Coral needs no pass: warm-ocean
 * (coral) biomes are only given to shallow water by the biome classifier.
 */
public final class OceanFlora {

    /** Depth (metres) below which no kelp or seagrass grows. */
    private static final float LIGHT_LIMIT_M = 60f;
    private static final int MIN_LIMIT_BLOCKS = 6;

    private OceanFlora() {
    }

    /** Water depth in blocks that matches {@link #LIGHT_LIMIT_M} at the current scale. */
    static int limitBlocks() {
        float blockSize = 30f / WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
        return Math.max(MIN_LIMIT_BLOCKS, Math.round(HeightConverter.oceanDepthBlocks(LIGHT_LIMIT_M, blockSize)));
    }

    public static void trimDeepPlants(ChunkAccess chunk, int seaLevel) {
        int limit = limitBlocks();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState water = Blocks.WATER.defaultBlockState();
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = x0 + dx, z = z0 + dz;
                // Only the open sea: the column must hold sea water right below sea level.
                if (!isWaterOrPlant(chunk.getBlockState(pos.set(x, seaLevel - 1, z)))) continue;
                int floor = seaLevel - 1;
                boolean anyPlant = false;
                while (floor > minY) {
                    BlockState st = chunk.getBlockState(pos.set(x, floor, z));
                    if (!isWaterOrPlant(st)) break;
                    if (isPlant(st)) anyPlant = true;
                    floor--;
                }
                int depth = seaLevel - 1 - floor;   // water blocks above the floor
                if (!anyPlant || depth <= limit) continue;
                for (int y = floor + 1; y < seaLevel; y++) {
                    if (isPlant(chunk.getBlockState(pos.set(x, y, z)))) chunk.setBlockState(pos, water, 0);
                }
            }
        }
    }

    private static boolean isPlant(BlockState st) {
        return st.is(Blocks.KELP) || st.is(Blocks.KELP_PLANT) || st.is(Blocks.SEAGRASS) || st.is(Blocks.TALL_SEAGRASS);
    }

    private static boolean isWaterOrPlant(BlockState st) {
        return st.is(Blocks.WATER) || isPlant(st);
    }
}
