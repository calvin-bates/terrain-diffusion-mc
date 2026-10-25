package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.GlacierShaper;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lays the land glaciers (biomes plan v1.4 round B) right after the terrain pass, from the tile's ice surface
 * ({@link GlacierShaper}): ice from the ground up to the ice surface. Packed ice throughout, blue ice deep inside, snow on
 * top where the glacier is fed (the colder ground), bare ice with crevasses further down. Moraines around the ice get a
 * top of gravel and cobble.
 */
public final class Glaciers {
    private static final Logger LOG = LoggerFactory.getLogger(Glaciers.class);

    /** Blue ice this many blocks under the ice surface (and a block above the ground). */
    private static final int BLUE_DEPTH = 4;
    /** Crevasses: only in ice at least this thick (blocks), at most this deep. */
    private static final int CREVASSE_MIN_THICK = 6, CREVASSE_MAX_DEPTH = 7;

    private static final FastNoiseLite PATCHES = noise(77401, 1f / 11f, 2);
    private static final FastNoiseLite CREVASSES = crevasseNoise();

    private static volatile boolean loggedFirst;

    private Glaciers() {
    }

    private static FastNoiseLite noise(int seed, float freq, int octaves) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(octaves);
        return n;
    }

    /** Long, thin cracks: where this noise is near zero (stretched along one axis so they run in sets). */
    private static FastNoiseLite crevasseNoise() {
        FastNoiseLite n = new FastNoiseLite(77402);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(1f / 14f);
        return n;
    }

    public static void place(ChunkAccess chunk) {
        try {
            if (!TerrainDiffusionConfig.glaciers()) return;
            placeUnchecked(chunk);
        } catch (Throwable t) {
            LOG.error("Terra: glaciers failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static void placeUnchecked(ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        int tileSize = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(tileSize);
        int startX = (x0 >> shift) << shift, startZ = (z0 >> shift) << shift;
        HeightmapData data = LocalTerrainProvider.peekHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
        if (data == null || data.glacier == null) return;
        int scale = WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
        int minY = chunk.getMinY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = x0 + dx, z = z0 + dz, lx = x - startX, lz = z - startZ;
                byte kind = data.glacier[lz][lx];
                if (kind == GlacierShaper.NONE) continue;
                if (!loggedFirst) {
                    loggedFirst = true;
                    LOG.info("Terra: first chunk with a glacier at {}", cp);
                }
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, dx, dz);
                while (y > minY) {
                    BlockState st = chunk.getBlockState(pos.set(x, y, z));
                    if (!st.isAir() && st.getFluidState().isEmpty() && !st.is(Blocks.SNOW)) break;
                    y--;
                }
                if (y <= minY) continue;
                if (!chunk.getBlockState(pos.set(x, y + 1, z)).getFluidState().isEmpty()) continue;   // water: leave it
                if (kind == GlacierShaper.MORAINE) {
                    moraine(chunk, pos, x, y, z);
                    continue;
                }
                int top = HeightConverter.convertToMinecraftHeight(data.iceTop[lz][lx], scale) - 1;
                if (top <= y) continue;
                ice(chunk, pos, x, y, z, top, kind == GlacierShaper.ICE_SNOW);
            }
        }
    }

    private static void ice(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int ground, int z, int top, boolean snow) {
        int thick = top - ground;
        int crevasse = 0;
        if (!snow && thick >= CREVASSE_MIN_THICK) {
            // Two sets of cracks across each other, each a band where its noise is close to zero.
            float a = Math.abs(CREVASSES.GetNoise(x * 0.35f, z * 1.6f));
            float b = Math.abs(CREVASSES.GetNoise(z * 0.35f + 700, x * 1.6f - 300));
            float m = Math.min(a, b);
            if (m < 0.05f) crevasse = Math.min(Math.min(CREVASSE_MAX_DEPTH, thick - 2), 2 + (int) ((0.05f - m) * 100));
        }
        float p = PATCHES.GetNoise(x, z);
        for (int y = ground + 1; y <= top; y++) {
            int d = top - y;                     // blocks under the ice surface
            BlockState st;
            if (d < crevasse) st = Blocks.AIR.defaultBlockState();
            else if (snow && d == 0) st = Blocks.SNOW_BLOCK.defaultBlockState();
            else if (snow && d == 1 && thick >= 4) st = Blocks.SNOW_BLOCK.defaultBlockState();
            else if (d >= BLUE_DEPTH && y > ground + 1 && PATCHES.GetNoise(x * 0.7f, y * 2f, z * 0.7f) > 0f) st = Blocks.BLUE_ICE.defaultBlockState();
            else if (!snow && d == 0 && p > 0.35f) st = Blocks.BLUE_ICE.defaultBlockState();
            else st = Blocks.PACKED_ICE.defaultBlockState();
            chunk.setBlockState(pos.set(x, y, z), st, 0);
        }
    }

    /** Moraine: the top two blocks of ground become gravel and cobble (stone where neither). */
    private static void moraine(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        BlockState above = chunk.getBlockState(pos.set(x, y + 1, z));
        if (above.is(Blocks.SNOW)) chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), 0);
        for (int yy = y; yy >= y - 1; yy--) {
            BlockState st = chunk.getBlockState(pos.set(x, yy, z));
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            float n = PATCHES.GetNoise(x * 1.9f + 300, yy * 3f, z * 1.9f);
            BlockState put = n > 0.2f ? Blocks.COBBLESTONE.defaultBlockState()
                    : n > -0.35f ? Blocks.GRAVEL.defaultBlockState() : Blocks.ANDESITE.defaultBlockState();
            chunk.setBlockState(pos, put, 0);
        }
    }
}
