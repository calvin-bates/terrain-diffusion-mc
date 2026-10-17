package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Keeps caves and ravines away from the mod's lakes and rivers. Vanilla carvers run after the water is placed, so a ravine
 * could slice through a lake's shore or floor, leaving a deep slot beside the water. In every column within
 * {@link #RADIUS} blocks of inland water nothing is carved from {@link #ROOF} blocks below the nearby water's bed upward;
 * caves can still pass underneath.
 */
public final class InlandWaterCarving {
    private static final int RADIUS = 4;
    private static final int ROOF = 5;

    private InlandWaterCarving() {
    }

    /** The carving filter for this chunk: the original one, plus no carving near inland water. */
    public static CarvingMask.Filter wrap(ChunkAccess chunk, CarvingMask.Filter original) {
        int[] carveBelow = protection(chunk);
        if (carveBelow == null) return original;
        return (x, y, z) -> (original == null || original.test(x, y, z)) && y < carveBelow[(x << 4) | z];
    }

    /** For each column (index x*16+z), the Y from which carving is blocked, or null if no inland water is near. */
    private static int[] protection(ChunkAccess chunk) {
        if (!TerrainDiffusionConfig.riversEnabled()) return null;
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        int size = 16 + 2 * RADIUS;
        // The lowest water bed in each column of the chunk plus its margin, or MAX_VALUE where there is no inland water.
        int[] bed = new int[size * size];
        boolean any = false;
        int tileSize = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        HeightmapData tile = null;
        int tileX = Integer.MIN_VALUE, tileZ = Integer.MIN_VALUE;
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                int x = x0 - RADIUS + dx, z = z0 - RADIUS + dz;
                int tx = (x >> shift) << shift, tz = (z >> shift) << shift;
                if (tx != tileX || tz != tileZ) {
                    tile = LocalTerrainProvider.peekHeightmap(tz, tx, tz + tileSize, tx + tileSize);   // never generates
                    tileX = tx; tileZ = tz;
                }
                int b = Integer.MAX_VALUE;
                if (tile != null && tile.waterY != null) {
                    short w = tile.waterY[z - tz][x - tx];
                    if (w != RiverCarver.NONE) {
                        short s = tile.surfaceY != null ? tile.surfaceY[z - tz][x - tx] : RiverCarver.NONE;
                        b = s != RiverCarver.NONE ? s : w - 3;
                        any = true;
                    }
                }
                bed[dz * size + dx] = b;
            }
        }
        if (!any) return null;
        int[] out = new int[256];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int low = Integer.MAX_VALUE;
                for (int dz = 0; dz <= 2 * RADIUS; dz++)
                    for (int dx = 0; dx <= 2 * RADIUS; dx++)
                        low = Math.min(low, bed[(z + dz) * size + (x + dx)]);
                out[(x << 4) | z] = low == Integer.MAX_VALUE ? Integer.MAX_VALUE : low - ROOF;
            }
        }
        return out;
    }
}
