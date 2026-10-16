package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;

/** Looks up where the mod placed inland water (lakes and streams), from the terrain tiles. */
public final class InlandWater {
    private InlandWater() {
    }

    /** True if the block column (x, z) holds lake or stream water. */
    public static boolean isWater(int x, int z) {
        int tileSize = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        int startX = (x >> shift) << shift;
        int startZ = (z >> shift) << shift;
        HeightmapData data = LocalTerrainProvider.getInstance().fetchHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
        if (data == null || data.waterY == null) return false;
        return data.waterY[z - startZ][x - startX] != RiverCarver.NONE;
    }

    /** True if the column or any of four points {@code r} blocks around it holds inland water. */
    public static boolean isWaterNear(int x, int z, int r) {
        return isWater(x, z) || isWater(x + r, z) || isWater(x - r, z) || isWater(x, z + r) || isWater(x, z - r);
    }
}
