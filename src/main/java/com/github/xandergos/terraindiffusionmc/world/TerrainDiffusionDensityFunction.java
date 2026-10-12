package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Density function backed by the Terrain Diffusion heightmap.
 *
 * <p>Minecraft 26.3 replaced the old per-position {@code sample}/{@code fill} API with compiled
 * samplers. This function is stateless; all work happens in {@link Sampler}.
 *
 * <p>Value semantics are unchanged from the 1.21.x version: {@code targetHeight(x, z) - y},
 * so the value is positive below the terrain surface and negative above it.
 */
public class TerrainDiffusionDensityFunction implements DensityFunction {
    public static final MapCodec<TerrainDiffusionDensityFunction> CODEC =
            MapCodec.unit(TerrainDiffusionDensityFunction::new);

    /** Conservative bounds: y spans the -64..2031 column and targetHeight is within a few thousand blocks. */
    private static final Interval RANGE = Interval.of(-4096.0f, 8192.0f);

    @Override
    public DensitySampler compileSampler(DensityFunction.CompileContext context) {
        return new Sampler();
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        // No child functions to rewrite.
        return this;
    }

    @Override
    public Interval range() {
        return RANGE;
    }

    @Override
    public int domainAxes() {
        return DensityFunction.ALL_AXES;
    }

    @Override
    public MapCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    /**
     * Per-thread cache of the most recently used heightmap tile, so that scanning a volume
     * does not hit the tile provider once per sampled point.
     */
    static final class TileCache {
        int blockStartX = Integer.MIN_VALUE;
        int blockStartZ = Integer.MIN_VALUE;
        int blockEndX = Integer.MIN_VALUE;
        int blockEndZ = Integer.MIN_VALUE;
        HeightmapData data;

        private static final ThreadLocal<TileCache> LOCAL = ThreadLocal.withInitial(TileCache::new);

        static TileCache get() {
            return LOCAL.get();
        }

        /** Returns the terrain surface height in Minecraft Y for the given block column. */
        int surfaceHeight(int x, int z, int fallbackY) {
            if (x < blockStartX || x >= blockEndX || z < blockStartZ || z >= blockEndZ) {
                load(x, z);
            }
            HeightmapData tile = this.data;
            if (tile == null || tile.heightmap == null) {
                return fallbackY;
            }
            int localX = Math.max(0, Math.min(tile.width - 1, x - blockStartX));
            int localZ = Math.max(0, Math.min(tile.height - 1, z - blockStartZ));
            // Rivers carve the terrain: use the carved surface where the tile has one.
            short[][] carved = tile.surfaceY;
            if (carved != null) {
                short v = carved[localZ][localX];
                if (v != Short.MIN_VALUE) return v;
            }
            return HeightConverter.convertToMinecraftHeight(tile.heightmap[localZ][localX]);
        }

        private void load(int x, int z) {
            int tileSize = TerrainDiffusionConfig.tileSize();
            int tileShift = Integer.numberOfTrailingZeros(tileSize);
            int tileX = x >> tileShift;
            int tileZ = z >> tileShift;
            this.blockStartX = tileX << tileShift;
            this.blockStartZ = tileZ << tileShift;
            this.blockEndX = blockStartX + tileSize;
            this.blockEndZ = blockStartZ + tileSize;
            this.data = LocalTerrainProvider.getInstance()
                    .fetchHeightmap(blockStartZ, blockStartX, blockEndZ, blockEndX);
        }
    }

    private static final class Sampler implements DensitySampler {
        @Override
        public float sampleValue(SamplerContext context, int x, int y, int z) {
            // When no tile is available, behave like empty air above y=0: density = -y.
            int target = TileCache.get().surfaceHeight(x, z, 0);
            return (float) (target - y);
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer buffer, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, buffer, volume, this);
        }
    }
}
