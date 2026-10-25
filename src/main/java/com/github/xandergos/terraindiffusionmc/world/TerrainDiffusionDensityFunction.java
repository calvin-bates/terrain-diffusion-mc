package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.serialization.MapCodec;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.Beardifier;
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
        /** The last column's ground pad (StructurePads), since a column is sampled at every y. */
        Beardifier padBeardifier;
        int padX, padZ, padNatural, padTarget;

        private static final ThreadLocal<TileCache> LOCAL = ThreadLocal.withInitial(TileCache::new);

        static TileCache get() {
            return LOCAL.get();
        }

        /** The provider's cache generation the tile was loaded in (a new seed or world reloads it). */
        int generation = Integer.MIN_VALUE;

        /** The tile holding block column (x, z), loading it if this thread's last tile was another. */
        HeightmapData tile(int x, int z) {
            if (x < blockStartX || x >= blockEndX || z < blockStartZ || z >= blockEndZ
                    || generation != LocalTerrainProvider.cacheGeneration()) {
                load(x, z);
            }
            return this.data;
        }

        /** Returns the terrain surface height in Minecraft Y for the given block column. */
        int surfaceHeight(int x, int z, int fallbackY) {
            HeightmapData tile = tile(x, z);
            if (tile == null || tile.heightmap == null) {
                return fallbackY;
            }
            int localX = Math.max(0, Math.min(tile.width - 1, x - blockStartX));
            int localZ = Math.max(0, Math.min(tile.height - 1, z - blockStartZ));
            // Rivers carve the terrain: use the carved surface where the tile has one.
            short[][] carved = tile.surfaceY;
            if (carved != null) {
                short v = carved[localZ][localX];
                if (v != Short.MIN_VALUE) return v + 1;   // surfaceY is the top solid block; density needs the first air block
            }
            return HeightConverter.convertToMinecraftHeight(tile.heightmap[localZ][localX]);
        }

        private void load(int x, int z) {
            this.generation = LocalTerrainProvider.cacheGeneration();
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
            return (float) (columnTarget(context, TileCache.get(), padBeardifier(context), x, z) - y);
        }

        /**
         * The whole chunk volume (16 x 2096 x 16 at once in the terrain pass, one 2096-block column for height queries):
         * the value only depends on the column, so the column's surface is looked up once and the column filled with
         * {@code target - y}, rather than looking the surface up again at each of its 2096 blocks.
         */
        @Override
        public void sampleVolume(SamplerContext context, DensityBuffer buffer, DensityVolume volume) {
            long t0 = volume.sizeX() * volume.sizeZ() >= 256 ? com.github.xandergos.terraindiffusionmc.pipeline.TerraPerf.start() : 0L;
            TileCache cache = TileCache.get();
            Beardifier beardifier = padBeardifier(context);
            int sizeY = volume.sizeY(), minY = volume.minBlockY(), stepY = volume.stepBlockY();
            int index = 0;
            for (int z = 0; z < volume.sizeZ(); z++) {
                int blockZ = volume.blockZ(z);
                for (int x = 0; x < volume.sizeX(); x++) {
                    int target = columnTarget(context, cache, beardifier, volume.blockX(x), blockZ);
                    for (int y = 0; y < sizeY; y++) buffer.set(index++, (float) (target - (minY + y * stepY)));
                }
            }
            com.github.xandergos.terraindiffusionmc.pipeline.TerraPerf.end("chunk.terrainDensity (incl. any tile.wait)", t0);
        }

        /** The chunk's beardifier when structure pads are on and it has pieces, else null. */
        private static Beardifier padBeardifier(SamplerContext context) {
            if (!TerrainDiffusionConfig.structurePads()) return null;
            Beardifier b = context.getField(Beardifier.CONTEXT_KEY);
            return b == Beardifier.EMPTY ? null : b;
        }

        /** First air block Y of column (x, z): the terrain surface, pulled to any structure pad there. */
        private static int columnTarget(SamplerContext context, TileCache cache, Beardifier beardifier, int x, int z) {
            // When no tile is available, behave like empty air above y=0: density = -y.
            int target = cache.surfaceHeight(x, z, 0);
            // Ground pads under village houses and outposts (see StructurePads), from the chunk's beardifier.
            if (beardifier != null) {
                if (cache.padBeardifier != beardifier || cache.padX != x || cache.padZ != z || cache.padNatural != target) {
                    cache.padBeardifier = beardifier;
                    cache.padX = x;
                    cache.padZ = z;
                    cache.padNatural = target;
                    cache.padTarget = StructurePads.adjust(beardifier, x, z, target);
                }
                target = cache.padTarget;
            }
            return target;
        }
    }
}
