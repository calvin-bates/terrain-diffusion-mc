package com.github.xandergos.terraindiffusionmc.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.DfRewriteRule;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

/**
 * Supplies the terrain surface height (Minecraft Y) for a block column, independent of Y.
 *
 * <p>Used for the noise router's {@code chunk_surface_level} input, which 26.3 requires and which
 * the old 1.21.x data pack fed with the same density function. Unlike
 * {@link TerrainDiffusionDensityFunction}, this returns the height itself, not {@code height - y}.
 */
public class TerrainDiffusionSurfaceLevelFunction implements DensityFunction {
    public static final MapCodec<TerrainDiffusionSurfaceLevelFunction> CODEC =
            MapCodec.unit(TerrainDiffusionSurfaceLevelFunction::new);

    private static final Interval RANGE = Interval.of(-4096.0f, 4096.0f);

    /**
     * How far below the first air block this reports the surface. The material system's {@code above_preliminary_surface}
     * test is {@code y >= level + surfaceDepth - 8} (surface depth 0..6), and Terra's grass, dirt and other surface
     * layers are guarded by it so they stay off cave floors (caves plan v1.5). With the level 3 blocks down, the test
     * reaches at least 5 blocks under the ground (the deepest dirt layer, stone depth 5) and at most 11, while caves
     * keep a roof of {@code CaveShapes.ROOF_LAND} (10) blocks, so cave floors are below it. Nothing else reads this
     * function (Terra has no aquifers) except vanilla's frozen-ocean iceberg extension, which uses it as a lower bound.
     */
    public static final int SOIL_GUARD_DROP = 3;

    @Override
    public DensitySampler compileSampler(DensityFunction.CompileContext context) {
        return new Sampler();
    }

    @Override
    public DensityFunction rewriteChildren(DfRewriteRule rule) {
        return this;
    }

    @Override
    public Interval range() {
        return RANGE;
    }

    @Override
    public int domainAxes() {
        // Depends on X and Z only.
        return DensityFunction.AXIS_X | DensityFunction.AXIS_Z;
    }

    @Override
    public MapCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    private static final class Sampler implements DensitySampler {
        @Override
        public float sampleValue(SamplerContext context, int x, int y, int z) {
            return (float) (TerrainDiffusionDensityFunction.TileCache.get().surfaceHeight(x, z, 0) - SOIL_GUARD_DROP);
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer buffer, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, buffer, volume, this);
        }
    }
}
