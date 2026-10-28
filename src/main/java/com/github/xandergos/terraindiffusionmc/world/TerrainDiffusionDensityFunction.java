package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveShapes;
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

        // ---- Cave reference ground (caves plan v1.5) ----

        /** What the ground near a column is, for the cave roof: dry land, sea floor, or the bed of a river or lake. */
        static final int CAVE_LAND = 0, CAVE_SEA = 1, CAVE_WATER = 2;
        /** Terra's sea level (noise settings); ground whose top block is below sea level - 1 is under the sea. */
        private static final int SEA_LEVEL = 63;
        /** Sample offsets around a column (centre, then a ring 4 blocks out). */
        private static final int[] CAVE_RING = {0, 0, 4, 0, -4, 0, 0, 4, 0, -4, 3, 3, -3, 3, 3, -3, -3, -3};
        /** {@link #caveRefTop}'s kind of ground for the last column asked. */
        int caveKind;

        /**
         * Top block Y of the lowest ground within 4 blocks of column (x, z), sampled inside the tile holding (x, z) (so
         * the answer is the same whoever asks), and the kind of ground there in {@link #caveKind}. Caves and cave biomes
         * are measured from this rather than the column's own top, so they stay inside a slope or cliff instead of
         * breaking out of its face. {@link Integer#MIN_VALUE} if no tile is available.
         */
        int caveRefTop(int x, int z) {
            HeightmapData t = tile(x, z);
            if (t == null || t.heightmap == null) {
                caveKind = CAVE_LAND;
                return Integer.MIN_VALUE;
            }
            int ref = Integer.MAX_VALUE, kind = CAVE_LAND;
            for (int k = 0; k < CAVE_RING.length; k += 2) {
                int lx = Math.max(0, Math.min(t.width - 1, x + CAVE_RING[k] - blockStartX));
                int lz = Math.max(0, Math.min(t.height - 1, z + CAVE_RING[k + 1] - blockStartZ));
                int top = HeightConverter.convertToMinecraftHeight(t.heightmap[lz][lx]) - 1;
                if (t.surfaceY != null && t.surfaceY[lz][lx] != Short.MIN_VALUE) top = t.surfaceY[lz][lx];
                if (t.waterY != null && t.waterY[lz][lx] != Short.MIN_VALUE) kind = CAVE_WATER;
                else if (top < SEA_LEVEL - 1 && kind == CAVE_LAND) kind = CAVE_SEA;
                ref = Math.min(ref, top);
            }
            caveKind = kind;
            return ref;
        }

        /** Small per-thread memo of {@link #caveRefTop} for the biome source, which asks per 4x4x4 cell. */
        private final int[] refMemoX = new int[64], refMemoZ = new int[64], refMemoVal = new int[64], refMemoGen = new int[64];
        {
            java.util.Arrays.fill(refMemoGen, Integer.MIN_VALUE);
        }

        int caveRefTopMemo(int x, int z) {
            int slot = ((x >> 2) & 7) | (((z >> 2) & 7) << 3);
            int gen = LocalTerrainProvider.cacheGeneration();
            if (refMemoGen[slot] == gen && refMemoX[slot] == x && refMemoZ[slot] == z) return refMemoVal[slot];
            int v = caveRefTop(x, z);
            refMemoX[slot] = x;
            refMemoZ[slot] = z;
            refMemoVal[slot] = v;
            refMemoGen[slot] = gen;
            return v;
        }

        /** Scratch for the cave field (see the sampler). */
        float[] caveBuf;
        final CaveShapes.Scratch caveScratch = new CaveShapes.Scratch();
        int[] colTarget, colRef;
        float[] colRoof;

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
            int sizeX = volume.sizeX(), sizeZ = volume.sizeZ();
            int sizeY = volume.sizeY(), minY = volume.minBlockY(), stepY = volume.stepBlockY();
            // Caves only in block-resolution volumes of a chunk's area or more (the terrain pass). Single-column height
            // queries skip them: caves never reach the surface outside entrance patches, so the answer is the same.
            boolean caves = TerrainDiffusionConfig.caves() && sizeX * sizeZ >= 16
                    && volume.stepBlockX() == 1 && stepY == 1 && volume.stepBlockZ() == 1;
            if (caves) {
                fillWithCaves(context, cache, beardifier, buffer, volume);
            } else {
                int index = 0;
                for (int z = 0; z < sizeZ; z++) {
                    int blockZ = volume.blockZ(z);
                    for (int x = 0; x < sizeX; x++) {
                        int target = columnTarget(context, cache, beardifier, volume.blockX(x), blockZ);
                        for (int y = 0; y < sizeY; y++) buffer.set(index++, (float) (target - (minY + y * stepY)));
                    }
                }
            }
            com.github.xandergos.terraindiffusionmc.pipeline.TerraPerf.end("chunk.terrainDensity (incl. any tile.wait)", t0);
        }

        /**
         * Terrain density with Terra's noise caves ({@link CaveShapes}) cut into it: the minimum of the terrain and the
         * cave field plus a roof under the lowest nearby ground ({@link TileCache#caveRefTop}).
         */
        private static void fillWithCaves(SamplerContext context, TileCache cache, Beardifier beardifier,
                                          DensityBuffer buffer, DensityVolume volume) {
            int sizeX = volume.sizeX(), sizeZ = volume.sizeZ(), sizeY = volume.sizeY(), minY = volume.minBlockY();
            int maxY = minY + sizeY - 1, cols = sizeX * sizeZ;
            if (cache.colTarget == null || cache.colTarget.length < cols) {
                cache.colTarget = new int[cols];
                cache.colRef = new int[cols];
                cache.colRoof = new float[cols];
            }
            int[] target = cache.colTarget, ref = cache.colRef;
            float[] roof = cache.colRoof;
            CaveShapes shapes = CaveShapes.forSeed(LocalTerrainProvider.getSeed());
            int caveTop = Integer.MIN_VALUE;
            for (int z = 0; z < sizeZ; z++) {
                int blockZ = volume.blockZ(z);
                for (int x = 0; x < sizeX; x++) {
                    int blockX = volume.blockX(x), c = x + z * sizeX;
                    target[c] = columnTarget(context, cache, beardifier, blockX, blockZ);
                    int r = cache.caveRefTop(blockX, blockZ);
                    ref[c] = r;
                    roof[c] = switch (cache.caveKind) {
                        case TileCache.CAVE_SEA -> TerrainDiffusionConfig.seaCaves() ? shapes.seaRoof(blockX, blockZ) : CaveShapes.ROOF_SEA;
                        case TileCache.CAVE_WATER -> CaveShapes.ROOF_WATER;
                        default -> shapes.landRoof(blockX, blockZ);
                    };
                    if (r != Integer.MIN_VALUE) caveTop = Math.max(caveTop, CaveShapes.caveTop(r));
                }
            }
            caveTop = Math.min(caveTop, maxY);
            int caveRows = caveTop - minY + 1;
            float[] cave = null;
            if (caveRows > 0) {
                long t1 = com.github.xandergos.terraindiffusionmc.pipeline.TerraPerf.start();
                int need = cols * caveRows;
                if (cache.caveBuf == null || cache.caveBuf.length < need) cache.caveBuf = new float[need];
                cave = cache.caveBuf;
                shapes.fill(volume.blockX(0), volume.blockZ(0), sizeX, sizeZ, minY, caveRows, cave, cache.caveScratch,
                        TerrainDiffusionConfig.caveBiomes() ? CaveSurface.get() : null);
                com.github.xandergos.terraindiffusionmc.pipeline.TerraPerf.end("chunk.caveField", t1);
            }
            // Shapes that may cut through the surface: river sinks, cenotes, sea caves (CaveExtras).
            long t2 = com.github.xandergos.terraindiffusionmc.pipeline.TerraPerf.start();
            CaveExtras extras = CaveExtras.of(volume.blockX(0), volume.blockZ(0), sizeX, sizeZ);
            com.github.xandergos.terraindiffusionmc.pipeline.TerraPerf.end("chunk.caveExtras", t2);
            // Buried structures stay in solid rock; ancient cities get their cleared box (StructureGuards).
            java.util.List<Beardifier.Rigid> guards = StructureGuards.near(context.getField(Beardifier.CONTEXT_KEY),
                    volume.blockX(0), volume.blockZ(0), volume.blockX(sizeX - 1), volume.blockZ(sizeZ - 1));
            java.util.List<Beardifier.Rigid> colGuards = guards.isEmpty() ? null : new java.util.ArrayList<>();
            // Volcano craters and lava lakes stay sealed: no cave comes within VOLCANO_SEAL blocks of their ground.
            float[] seal = volcanoSeal(cache, volume.blockX(0), volume.blockZ(0), sizeX, sizeZ);
            int index = 0;
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    int c = x + z * sizeX, t = target[c], r = ref[c];
                    float rf = roof[c];
                    int sealFrom = seal == null || seal[c] <= 0f ? Integer.MAX_VALUE : t - (int) Math.ceil(seal[c]);
                    int top = (cave == null || r == Integer.MIN_VALUE) ? minY - 1 : Math.min(CaveShapes.caveTop(r), caveTop);
                    int base = c * caveRows;
                    int eTop = extras.any ? extras.maxY(c) : Integer.MIN_VALUE;
                    int eBottom = eTop == Integer.MIN_VALUE ? Integer.MAX_VALUE : extras.minY(c);
                    int blockX = volume.blockX(x), blockZ = volume.blockZ(z);
                    if (colGuards != null) StructureGuards.column(guards, blockX, blockZ, colGuards);
                    boolean guarded = colGuards != null && !colGuards.isEmpty();
                    // A cut passing just under the ground opens the thin crust over it (no one-block strips).
                    int open = eTop == Integer.MIN_VALUE ? Integer.MAX_VALUE : extras.openFrom(c, blockX, t - 1, blockZ);
                    // The rock sill at the coastal band's edge (CoastBand): caves stay sealed up to Y 63 there.
                    boolean sill = minY <= com.github.xandergos.terraindiffusionmc.pipeline.CoastBand.SILL_TOP
                            && TerrainDiffusionConfig.caveFormations()
                            && com.github.xandergos.terraindiffusionmc.pipeline.CoastBand.sill(blockX, blockZ, CaveSurface.get());
                    for (int y = 0; y < sizeY; y++) {
                        int blockY = minY + y;
                        float v = t - blockY;
                        if (blockY >= open && blockY < t) v = Math.min(v, -0.5f);
                        if (blockY <= top) {
                            float cv = CaveShapes.roofed(cave[base + y], r - blockY, rf);
                            if (cv < v) v = cv;
                        }
                        if (blockY >= eBottom && blockY <= eTop) {
                            float ev = extras.value(c, blockX, blockY, blockZ);
                            if (ev < v) v = ev;
                        }
                        if (sill && blockY < t && blockY <= com.github.xandergos.terraindiffusionmc.pipeline.CoastBand.SILL_TOP && v < 0.5f) v = 0.5f;
                        if (blockY >= sealFrom && blockY < t && v < 0.5f) v = 0.5f;
                        if (guarded && blockY < t) v = StructureGuards.apply(colGuards, blockX, blockY, blockZ, v);
                        buffer.set(index++, v);
                    }
                }
            }
        }

        /** How deep (blocks) the rock under a crater or lava lake stays solid, and how far round it that holds in full / fades out. */
        private static final float VOLCANO_SEAL = 40f, VOLCANO_SEAL_FULL = 8f, VOLCANO_SEAL_FADE = 16f;
        private static final int VOLCANO_STEP = 2;

        /**
         * Per column of the volume, how many blocks under the ground stay solid because a volcano's crater or lava lake
         * is near (Calvin, Oct 28: a cave ran through the crater of an active volcano, opening a pit beside the lava
         * lake): {@link #VOLCANO_SEAL} within {@link #VOLCANO_SEAL_FULL} blocks of crater or lava-lake ground (the
         * Landforms tags), fading to nothing over the next {@link #VOLCANO_SEAL_FADE}, so a cave nearby rises to a
         * domed end instead of a wall. Null if there is none near. The tags are read on a 2-block grid with the
         * distance from a two-pass chamfer transform; a function of position only, so seamless across chunks.
         */
        private static float[] volcanoSeal(TileCache cache, int x0, int z0, int sizeX, int sizeZ) {
            int reach = (int) (VOLCANO_SEAL_FULL + VOLCANO_SEAL_FADE) + VOLCANO_STEP;
            int gx0 = Math.floorDiv(x0 - reach, VOLCANO_STEP), gz0 = Math.floorDiv(z0 - reach, VOLCANO_STEP);
            int gx1 = Math.floorDiv(x0 + sizeX - 1 + reach, VOLCANO_STEP), gz1 = Math.floorDiv(z0 + sizeZ - 1 + reach, VOLCANO_STEP);
            int gw = gx1 - gx0 + 1, gh = gz1 - gz0 + 1;
            float[] d = new float[gw * gh];
            boolean any = false;
            for (int gz = 0; gz < gh; gz++) {
                for (int gx = 0; gx < gw; gx++) {
                    int bx = (gx0 + gx) * VOLCANO_STEP, bz = (gz0 + gz) * VOLCANO_STEP;
                    HeightmapData tile = cache.tile(bx, bz);
                    boolean hot = false;
                    if (tile != null && tile.oceanTags != null) {
                        int lx = bx - cache.blockStartX, lz = bz - cache.blockStartZ;
                        if (lx >= 0 && lz >= 0 && lz < tile.oceanTags.length && lx < tile.oceanTags[lz].length) {
                            byte tag = tile.oceanTags[lz][lx];
                            hot = tag == com.github.xandergos.terraindiffusionmc.pipeline.Landforms.TAG_CRATER
                                    || tag == com.github.xandergos.terraindiffusionmc.pipeline.Landforms.TAG_HOT_CRATER
                                    || tag == com.github.xandergos.terraindiffusionmc.pipeline.Landforms.TAG_LAVA_LAKE;
                        }
                    }
                    d[gx + gz * gw] = hot ? 0f : 1e9f;
                    any |= hot;
                }
            }
            if (!any) return null;
            final float o = VOLCANO_STEP, g = VOLCANO_STEP * 1.4142f;
            for (int gz = 0; gz < gh; gz++)
                for (int gx = 0; gx < gw; gx++) {
                    int i = gx + gz * gw;
                    float v = d[i];
                    if (gx > 0) v = Math.min(v, d[i - 1] + o);
                    if (gz > 0) {
                        v = Math.min(v, d[i - gw] + o);
                        if (gx > 0) v = Math.min(v, d[i - gw - 1] + g);
                        if (gx < gw - 1) v = Math.min(v, d[i - gw + 1] + g);
                    }
                    d[i] = v;
                }
            for (int gz = gh - 1; gz >= 0; gz--)
                for (int gx = gw - 1; gx >= 0; gx--) {
                    int i = gx + gz * gw;
                    float v = d[i];
                    if (gx < gw - 1) v = Math.min(v, d[i + 1] + o);
                    if (gz < gh - 1) {
                        v = Math.min(v, d[i + gw] + o);
                        if (gx < gw - 1) v = Math.min(v, d[i + gw + 1] + g);
                        if (gx > 0) v = Math.min(v, d[i + gw - 1] + g);
                    }
                    d[i] = v;
                }
            float[] out = new float[sizeX * sizeZ];
            boolean some = false;
            for (int z = 0; z < sizeZ; z++)
                for (int x = 0; x < sizeX; x++) {
                    // Bilinear between grid points, so the seal is smooth at block scale.
                    float fx = (x0 + x) / (float) VOLCANO_STEP - gx0, fz = (z0 + z) / (float) VOLCANO_STEP - gz0;
                    int ix = Math.min(gw - 2, (int) Math.floor(fx)), iz = Math.min(gh - 2, (int) Math.floor(fz));
                    float tx = fx - ix, tz = fz - iz;
                    float dist = (d[ix + iz * gw] * (1 - tx) + d[ix + 1 + iz * gw] * tx) * (1 - tz)
                            + (d[ix + (iz + 1) * gw] * (1 - tx) + d[ix + 1 + (iz + 1) * gw] * tx) * tz;
                    float f = 1f - Math.max(0f, Math.min(1f, (dist - VOLCANO_SEAL_FULL) / VOLCANO_SEAL_FADE));
                    f = f * f * (3f - 2f * f);
                    out[x + z * sizeX] = VOLCANO_SEAL * f;
                    some |= f > 0f;
                }
            return some ? out : null;
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
