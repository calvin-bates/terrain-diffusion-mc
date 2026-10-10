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
            return (float) TerrainDiffusionDensityFunction.TileCache.get().surfaceHeight(x, z, 0);
        }

        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer buffer, DensityVolume volume) {
            DensitySampler.sampleVolumeNaive(context, buffer, volume, this);
        }
    }
}
