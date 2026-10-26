package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.material.Fluids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Waterfalls (biomes plan v1.4 round D): where a river or stream's water reaches the edge of a drop of three blocks or
 * more (a hanging valley's mouth on a Yosemite wall, a terrace cliff, a gorge's lip), its water is woken up after the
 * features step, so it pours over the edge as a falling column instead of standing still at the lip. Lakes are left
 * alone. At most {@link #MAX_PER_CHUNK} per chunk.
 */
public final class Waterfalls {
    private static final Logger LOG = LoggerFactory.getLogger(Waterfalls.class);
    private static final int MAX_PER_CHUNK = 48;
    private static final int MIN_DROP = 3;

    private Waterfalls() {
    }

    public static void place(WorldGenLevel level, ChunkAccess chunk) {
        try {
            if (!TerrainDiffusionConfig.showpieces() || !TerrainDiffusionConfig.riversEnabled()) return;
            ChunkPos cp = chunk.getPos();
            int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
            int tileSize = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(tileSize);
            int startX = (x0 >> shift) << shift, startZ = (z0 >> shift) << shift;
            HeightmapData data = LocalTerrainProvider.peekHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
            if (data == null || data.waterY == null) return;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int placed = 0;
            for (int dz = 0; dz < 16 && placed < MAX_PER_CHUNK; dz++) {
                for (int dx = 0; dx < 16 && placed < MAX_PER_CHUNK; dx++) {
                    int x = x0 + dx, z = z0 + dz, lx = x - startX, lz = z - startZ;
                    short wy = data.waterY[lz][lx];
                    if (wy == RiverCarver.NONE) continue;
                    if (data.waterIsLake != null && data.waterIsLake[lz][lx]) continue;
                    if (!level.getFluidState(pos.set(x, wy, z)).isSource()) continue;
                    boolean edge = false;
                    for (int a = 0; a < 4 && !edge; a++) {
                        int nx = x + (a == 0 ? 1 : a == 1 ? -1 : 0), nz = z + (a == 2 ? 1 : a == 3 ? -1 : 0);
                        boolean open = true;
                        for (int k = 0; k < MIN_DROP && open; k++) {
                            if (!level.getBlockState(pos.set(nx, wy - k, nz)).isAir()) open = false;
                        }
                        edge = open;
                    }
                    if (!edge) continue;
                    level.scheduleTick(pos.set(x, wy, z), Fluids.WATER, 1);
                    placed++;
                }
            }
        } catch (Throwable t) {
            LOG.error("Terra: waterfalls failed for chunk {}", chunk.getPos(), t);
        }
    }
}
