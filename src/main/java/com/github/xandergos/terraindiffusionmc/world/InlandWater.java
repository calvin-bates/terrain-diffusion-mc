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

    /** Y of the sea surface (sea level 63 is the first air block). */
    private static final int SEA_SURFACE_Y = 62;

    /**
     * Fraction (0..1) of sample columns, every {@code step} blocks across the given x/z boxes, that are water: the
     * mod's lakes and streams, or ground below the sea surface (sea, or a hollow filled to sea level). Each box is
     * {minX, minZ, maxX, maxZ}, inclusive.
     */
    public static double wetFraction(java.util.List<int[]> boxes, int step) {
        int tileSize = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        int curX = Integer.MIN_VALUE, curZ = Integer.MIN_VALUE;
        HeightmapData data = null;
        int wet = 0, total = 0;
        for (int[] b : boxes) {
            for (int x = b[0]; x <= b[2]; x += step) {
                for (int z = b[1]; z <= b[3]; z += step) {
                    int startX = (x >> shift) << shift, startZ = (z >> shift) << shift;
                    if (startX != curX || startZ != curZ) {
                        data = LocalTerrainProvider.getInstance().fetchHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
                        curX = startX;
                        curZ = startZ;
                    }
                    if (data == null || data.heightmap == null) continue;
                    total++;
                    int lx = x - startX, lz = z - startZ;
                    if (data.waterY != null && data.waterY[lz][lx] != RiverCarver.NONE) { wet++; continue; }
                    int top = (data.surfaceY != null && data.surfaceY[lz][lx] != RiverCarver.NONE)
                            ? data.surfaceY[lz][lx]
                            : HeightConverter.convertToMinecraftHeight(data.heightmap[lz][lx]) - 1;
                    if (top < SEA_SURFACE_Y) wet++;
                }
            }
        }
        return total == 0 ? 0 : (double) wet / total;
    }

    /** True if the column or any of four points {@code r} blocks around it holds inland water. */
    public static boolean isWaterNear(int x, int z, int r) {
        return isWater(x, z) || isWater(x + r, z) || isWater(x - r, z) || isWater(x, z + r) || isWater(x, z - r);
    }
}
