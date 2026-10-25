package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Sea ice detail (biomes plan v1.3 round C), after vanilla has frozen the sea's surface during decoration:
 * <ul>
 *   <li><b>Polynyas</b>: patches of open water in the pack ice (a few hundred metres across, in roughly a tenth of it),
 *       where wind and upwelling keep the sea from freezing.</li>
 *   <li><b>Pressure ridges</b>: long, narrow lines of ice piled 1-3 blocks high where floes pushed together, jagged
 *       along their crest.</li>
 * </ul>
 * Only sea ice is touched: an ice block at the sea's surface with water under it. Noise in world metres, so the same
 * at every scale; each chunk writes only its own columns.
 */
public final class SeaIce {

    private static final FastNoiseLite POLYNYA = noise(91301, 1f / 700f, 2);
    private static final FastNoiseLite RIDGE = noise(91302, 1f / 450f, 1);
    private static final FastNoiseLite RIDGE_WARP = noise(91303, 1f / 900f, 2);

    /** Polynya noise level (higher = fewer) and how close to a ridge line (0..1, higher = thinner) a ridge forms. */
    static final float POLYNYA_LEVEL = 0.42f, RIDGE_LEVEL = 0.97f;

    private SeaIce() {
    }

    private static FastNoiseLite noise(int seed, float freq, int oct) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(oct);
        return n;
    }

    public static void place(ChunkAccess chunk, int seaLevel) {
        if (!TerrainDiffusionConfig.icebergs()) return;
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ(), top = seaLevel - 1;
        int scale = WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
        float mpb = 30f / scale;
        long seed = LocalTerrainProvider.getSeed();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = x0 + dx, z = z0 + dz;
                if (!chunk.getBlockState(pos.set(x, top, z)).is(Blocks.ICE)) continue;
                if (!chunk.getBlockState(pos.set(x, top - 1, z)).is(Blocks.WATER)) continue;
                float wx = x * mpb, wz = z * mpb;
                if (POLYNYA.GetNoise(wx, wz) > POLYNYA_LEVEL) {
                    chunk.setBlockState(pos.set(x, top, z), Blocks.WATER.defaultBlockState(), 0);
                    continue;
                }
                float warp = 120f * RIDGE_WARP.GetNoise(wx, wz);
                float ridge = 1f - Math.abs(RIDGE.GetNoise(wx + warp, wz - warp));
                if (ridge < RIDGE_LEVEL) continue;
                long h = mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ 0x5EA1L);
                float strength = (ridge - RIDGE_LEVEL) / (1f - RIDGE_LEVEL);
                int height = 1 + (int) (strength * 2.5f + ((h >>> 12) % 100) / 100f);
                for (int k = 1; k <= height; k++) {
                    pos.set(x, top + k, z);
                    if (!chunk.getBlockState(pos).isAir()) break;
                    BlockState st = ((h >>> (20 + k)) & 1) == 0 ? Blocks.PACKED_ICE.defaultBlockState() : Blocks.ICE.defaultBlockState();
                    chunk.setBlockState(pos, st, 0);
                }
            }
        }
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
