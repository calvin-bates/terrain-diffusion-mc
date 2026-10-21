package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.pipeline.OceanFeatures;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the ocean surface tags from {@link OceanFeatures} into blocks, right after a chunk's terrain is built (before
 * features, so trees and vanilla plants see the result):
 * <ul>
 *   <li><b>Reefs</b> (atoll, barrier and fringing reefs): coral blocks on the floor with coral, coral fans and the odd
 *       sea pickle on top, densest on the reef front, thinner on the reef flat, only in sunlit water.</li>
 *   <li><b>Lagoons</b> behind reefs: seagrass meadows, dense in patches, with tall seagrass where the water allows.</li>
 *   <li><b>Carbonate banks</b>: patches of bright calcite on the sandy floor, and seagrass meadows.</li>
 *   <li><b>Raised coral islands</b> and <b>forearc islands</b>: the rock is limestone, in layers of calcite and
 *       diorite (vanilla has no limestone). On the rim and cliffs it is bare at the surface, with soil only in pockets
 *       on flat ground; inside the old lagoon basin it lies under the soil.</li>
 *   <li><b>Deep floors</b> (round 3): pale ooze (calcite and diorite) above ~4.3 km, red-brown clay (terracotta mix)
 *       below, pillow lava (basalt, smooth basalt, blackstone) by spreading ridges, mud around mud volcanoes.</li>
 *   <li><b>Coastal cones and lava</b> (round 4): tuff rings in tuff with patches of smooth basalt, grass only on their
 *       flatter ground; fresh lava flows and deltas in bare basalt and blackstone, with the odd magma block in a
 *       hollow and pools of live lava along the active channel wherever it lies level and well away from anything
 *       that burns.</li>
 *   <li><b>Strandflats</b> (round 4): bare, ice-scoured rock with gravel and moss in patches and a little grass, and
 *       gravel and stone where the plain is drowned.</li>
 * </ul>
 * Each chunk writes only its own columns and chooses from hashes of block positions, so the result does not depend on
 * generation order.
 */
public final class OceanSurfaces {
    private static final Logger LOG = LoggerFactory.getLogger(OceanSurfaces.class);

    /** Share of reef columns with coral: on the flat (1 block of water), on the front (2-6 blocks), deeper. */
    private static final float CORAL_FLAT = 0.25f, CORAL_FRONT = 0.55f, CORAL_DEEP = 0.3f;
    /** Share of lagoon or bank floor with seagrass inside a meadow patch, and between patches. */
    private static final float MEADOW_DENSE = 0.65f, MEADOW_SPARSE = 0.08f;
    /** Share of a carbonate bank's floor that is bare calcite. */
    private static final float BANK_CALCITE = 0.2f;
    /** How far (blocks) below the surface a raised coral island is limestone. */
    private static final int LIMESTONE_DEPTH = 40;

    private static final FastNoiseLite PATCHES = noise(81731, 1f / 24f);
    private static final FastNoiseLite STRATA = noise(81732, 1f / 40f);
    private static final FastNoiseLite SOIL = noise(81733, 1f / 10f);

    private static final Block[] CORAL_BLOCKS = {Blocks.TUBE_CORAL_BLOCK, Blocks.BRAIN_CORAL_BLOCK,
            Blocks.BUBBLE_CORAL_BLOCK, Blocks.FIRE_CORAL_BLOCK, Blocks.HORN_CORAL_BLOCK};
    private static final Block[] CORAL_PLANTS = {Blocks.TUBE_CORAL, Blocks.BRAIN_CORAL, Blocks.BUBBLE_CORAL,
            Blocks.FIRE_CORAL, Blocks.HORN_CORAL, Blocks.TUBE_CORAL_FAN, Blocks.BRAIN_CORAL_FAN,
            Blocks.BUBBLE_CORAL_FAN, Blocks.FIRE_CORAL_FAN, Blocks.HORN_CORAL_FAN};

    private static volatile boolean loggedFirst;

    private OceanSurfaces() {
    }

    private static FastNoiseLite noise(int seed, float freq) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(2);
        return n;
    }

    public static void place(ChunkAccess chunk, int seaLevel) {
        try {
            placeUnchecked(chunk, seaLevel);
        } catch (Throwable t) {
            // Never let this break chunk generation.
            LOG.error("Terra: ocean surfaces failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static void placeUnchecked(ChunkAccess chunk, int seaLevel) {
        if (!TerrainDiffusionConfig.oceanFeatures()) return;
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        int tileSize = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        int startX = (x0 >> shift) << shift, startZ = (z0 >> shift) << shift;
        // The terrain pass just used this tile, so it is cached. Never generate it from here.
        HeightmapData data = LocalTerrainProvider.peekHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
        if (data == null || data.oceanTags == null) return;
        if (!loggedFirst) {
            loggedFirst = true;
            LOG.info("Terra: first chunk with ocean surfaces (reef, lagoon, bank, limestone or deep floor) at {}", cp);
        }
        int scale = WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
        float blockSize = 30f / scale;
        int lightLimit = OceanFlora.limitBlocks();
        long seed = LocalTerrainProvider.getSeed();
        int minY = chunk.getMinY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = x0 + dx, z = z0 + dz, lx = x - startX, lz = z - startZ;
                byte tag = data.oceanTags[lz][lx];
                if (tag == OceanFeatures.TAG_NONE) continue;
                long h = mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL));
                switch (tag) {
                    case OceanFeatures.TAG_REEF, OceanFeatures.TAG_FRINGING, OceanFeatures.TAG_BARRIER ->
                            reef(chunk, pos, x, z, h, seaLevel, lightLimit, minY);
                    case OceanFeatures.TAG_LAGOON -> lagoon(chunk, pos, x, z, h, seaLevel, lightLimit, minY, false);
                    case OceanFeatures.TAG_BANK -> lagoon(chunk, pos, x, z, h, seaLevel, lightLimit, minY, true);
                    case OceanFeatures.TAG_LIMESTONE, OceanFeatures.TAG_LIMESTONE_SOIL ->
                            limestone(chunk, data, pos, x, z, lx, lz, h, tag == OceanFeatures.TAG_LIMESTONE, minY, blockSize);
                    case OceanFeatures.TAG_OOZE, OceanFeatures.TAG_RED_CLAY, OceanFeatures.TAG_PILLOW, OceanFeatures.TAG_MUD ->
                            deepFloor(chunk, pos, x, z, tag, seaLevel, minY);
                    case OceanFeatures.TAG_TUFF, OceanFeatures.TAG_LAVA, OceanFeatures.TAG_LAVA_CHANNEL ->
                            volcanicRock(chunk, data, pos, x, z, lx, lz, h, tag, minY, blockSize, scale);
                    case OceanFeatures.TAG_STRANDFLAT -> strandflat(chunk, pos, x, z, minY);
                    default -> {
                    }
                }
            }
        }
    }

    // ---- Sea floor ----

    /** Y of the sea floor (top solid block) under open sea water at (x, z), or Integer.MIN_VALUE if not open sea. */
    private static int seaFloor(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z, int seaLevel, int minY) {
        if (!chunk.getBlockState(pos.set(x, seaLevel - 1, z)).is(Blocks.WATER)) return Integer.MIN_VALUE;
        int y = seaLevel - 1;
        while (y > minY) {
            BlockState st = chunk.getBlockState(pos.set(x, y, z));
            if (!st.is(Blocks.WATER) && !st.is(Blocks.SEAGRASS) && !st.is(Blocks.TALL_SEAGRASS)) break;
            y--;
        }
        return y;
    }

    private static void reef(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z, long h, int seaLevel,
                             int lightLimit, int minY) {
        int floor = seaFloor(chunk, pos, x, z, seaLevel, minY);
        if (floor == Integer.MIN_VALUE) return;
        int water = seaLevel - 1 - floor;
        if (water < 1 || water > lightLimit) return;
        float chance = water == 1 ? CORAL_FLAT : (water <= 6 ? CORAL_FRONT : CORAL_DEEP);
        if (unit(h) >= chance) return;
        BlockState ground = chunk.getBlockState(pos.set(x, floor, z));
        if (ground.isAir() || !ground.getFluidState().isEmpty()) return;
        chunk.setBlockState(pos, CORAL_BLOCKS[(int) (unit(mix(h + 1)) * CORAL_BLOCKS.length)].defaultBlockState(), 0);
        double u = unit(mix(h + 2));
        pos.set(x, floor + 1, z);
        if (u < 0.6) {
            chunk.setBlockState(pos, CORAL_PLANTS[(int) (unit(mix(h + 3)) * CORAL_PLANTS.length)].defaultBlockState(), 0);
        } else if (u < 0.68) {
            int pickles = 1 + (int) (unit(mix(h + 4)) * SeaPickleBlock.MAX_PICKLES);
            chunk.setBlockState(pos, Blocks.SEA_PICKLE.defaultBlockState().setValue(SeaPickleBlock.PICKLES, pickles), 0);
        }
    }

    /** Seagrass meadows in a lagoon or on a carbonate bank (which also gets calcite patches). */
    private static void lagoon(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z, long h, int seaLevel,
                               int lightLimit, int minY, boolean bank) {
        int floor = seaFloor(chunk, pos, x, z, seaLevel, minY);
        if (floor == Integer.MIN_VALUE) return;
        int water = seaLevel - 1 - floor;
        if (water < 1 || water > lightLimit) return;
        BlockState ground = chunk.getBlockState(pos.set(x, floor, z));
        if (ground.isAir() || !ground.getFluidState().isEmpty()) return;
        if (bank && PATCHES.GetNoise(x * 1.7f + 300, z * 1.7f) > 1 - 2 * BANK_CALCITE) {
            chunk.setBlockState(pos, Blocks.CALCITE.defaultBlockState(), 0);
            return;
        }
        // Seagrass only takes on soft floors.
        if (!(ground.is(Blocks.SAND) || ground.is(Blocks.GRAVEL) || ground.is(Blocks.DIRT) || ground.is(Blocks.CLAY)
                || ground.is(Blocks.MUD))) return;
        float meadow = PATCHES.GetNoise(x, z);
        float chance = meadow > -0.1f ? MEADOW_DENSE * (bank ? 0.8f : 1f) : MEADOW_SPARSE;
        if (unit(h) >= chance) return;
        BlockState above = chunk.getBlockState(pos.set(x, floor + 1, z));
        if (!above.is(Blocks.WATER)) return;
        if (water >= 2 && unit(mix(h + 5)) < 0.25) {
            chunk.setBlockState(pos, Blocks.TALL_SEAGRASS.defaultBlockState().setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.LOWER), 0);
            chunk.setBlockState(pos.set(x, floor + 2, z),
                    Blocks.TALL_SEAGRASS.defaultBlockState().setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.UPPER), 0);
        } else {
            chunk.setBlockState(pos, Blocks.SEAGRASS.defaultBlockState(), 0);
        }
    }

    // ---- Raised coral islands ----

    private static void limestone(ChunkAccess chunk, HeightmapData data, BlockPos.MutableBlockPos pos, int x, int z,
                                  int lx, int lz, long h, boolean bareRock, int minY, float blockSize) {
        int top = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
        // Down to the first solid block (the heightmap can be stale above a carved hole, and water is not ground).
        int y = top;
        while (y > minY) {
            BlockState st = chunk.getBlockState(pos.set(x, y, z));
            if (!st.isAir() && st.getFluidState().isEmpty() && !st.is(Blocks.SNOW)) break;
            y--;
        }
        if (y <= minY) return;
        boolean underWater = !chunk.getBlockState(pos.set(x, y + 1, z)).getFluidState().isEmpty();
        // Soil stays in pockets on flat ground on the rim, and everywhere in the old lagoon basin.
        boolean keepSoil;
        if (bareRock) {
            keepSoil = !underWater && SOIL.GetNoise(x, z) > 0.25f && !steep(data, lx, lz, blockSize);
        } else {
            keepSoil = !underWater;
        }
        if (underWater && SOIL.GetNoise(x + 500, z) > 0.2f) keepSoil = true;   // sand pockets on the submerged rock
        int bottom = Math.max(minY + 1, y - LIMESTONE_DEPTH);
        int soil = keepSoil ? 4 : 0;
        for (int yy = y; yy >= bottom; yy--) {
            BlockState st = chunk.getBlockState(pos.set(x, yy, z));
            if (!isRock(st)) continue;                                  // air, water and caves pass through
            if (soil > 0 && isSoft(st)) { soil--; continue; }
            soil = 0;
            chunk.setBlockState(pos, limestoneAt(x, yy, z), 0);
        }
    }

    /** Whether the ground at tile column (lx, lz) is steeper than about 1 block per block (a cliff). */
    private static boolean steep(HeightmapData data, int lx, int lz, float blockSize) {
        short[][] hm = data.heightmap;
        int H = hm.length, W = hm[0].length;
        int c = hm[lz][lx];
        int max = 0;
        if (lx > 0) max = Math.max(max, Math.abs(hm[lz][lx - 1] - c));
        if (lx < W - 1) max = Math.max(max, Math.abs(hm[lz][lx + 1] - c));
        if (lz > 0) max = Math.max(max, Math.abs(hm[lz - 1][lx] - c));
        if (lz < H - 1) max = Math.max(max, Math.abs(hm[lz + 1][lx] - c));
        return max > 1.2f * blockSize;
    }

    /** Limestone in layers a few blocks thick, gently warped, mostly calcite with bands of diorite. */
    private static BlockState limestoneAt(int x, int y, int z) {
        double v = y + 2.5 * STRATA.GetNoise(x, z);
        long band = (long) Math.floor(v / 3.0);
        boolean calcite = unit(mix(band * 0x632BE59BD9B4E019L + 17)) < 0.62;
        if (unit(mix(x * 0x9E3779B97F4A7C15L ^ y * 0x85EBCA77C2B2AE63L ^ z * 0xC2B2AE3D27D4EB4FL)) < 0.08) calcite = !calcite;
        return calcite ? Blocks.CALCITE.defaultBlockState() : Blocks.DIORITE.defaultBlockState();
    }

    // ---- Coastal cones and lava (round 4) ----

    /** Blocks of tuff below a tuff ring's surface, and of fresh rock below a lava flow's. */
    private static final int TUFF_DEPTH = 5, LAVA_DEPTH = 3;
    /** Share of level spots on a cooled flow with a magma block. */
    private static final float MAGMA_CHANCE = 0.06f;
    /** Live lava needs this many blocks of fresh, bare rock around it in every direction (nothing to set alight). */
    private static final int LAVA_CLEARANCE = 3;

    private static void volcanicRock(ChunkAccess chunk, HeightmapData data, BlockPos.MutableBlockPos pos, int x, int z,
                                     int lx, int lz, long h, byte tag, int minY, float blockSize, int scale) {
        int top = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
        int y = top;
        while (y > minY) {
            BlockState st = chunk.getBlockState(pos.set(x, y, z));
            if (!st.isAir() && st.getFluidState().isEmpty() && !st.is(Blocks.SNOW)) break;
            y--;
        }
        if (y <= minY) return;
        boolean underWater = !chunk.getBlockState(pos.set(x, y + 1, z)).getFluidState().isEmpty();
        if (tag == OceanFeatures.TAG_TUFF) {
            // Grass holds on the flatter ground of the rim; the walls and anything under water are bare tuff.
            boolean keepSoil = !underWater && SOIL.GetNoise(x - 300, z + 41) > 0.05f && !steep(data, lx, lz, blockSize);
            int soil = keepSoil ? 2 : 0;
            for (int yy = y; yy >= Math.max(minY + 1, y - TUFF_DEPTH); yy--) {
                BlockState st = chunk.getBlockState(pos.set(x, yy, z));
                if (!isRock(st)) continue;
                if (soil > 0 && isSoft(st)) { soil--; continue; }
                soil = 0;
                chunk.setBlockState(pos, tuffAt(x, yy, z), 0);
            }
            return;
        }
        for (int yy = y; yy >= Math.max(minY + 1, y - LAVA_DEPTH); yy--) {
            BlockState st = chunk.getBlockState(pos.set(x, yy, z));
            if (!isRock(st)) continue;
            chunk.setBlockState(pos, lavaRockAt(x, yy, z), 0);
        }
        if (underWater) return;
        BlockState above = chunk.getBlockState(pos.set(x, y + 1, z));
        if (above.is(Blocks.SNOW)) chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), 0);
        else if (!above.isAir()) return;
        if (data.waterY != null && data.waterY[lz][lx] != com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver.NONE) return;
        // Only where the ground is level or a hollow, so nothing can run out (checked on the tile, across chunk edges).
        int own = tileTop(data, lx, lz, scale);
        if (own != y || !levelOrHollow(data, lx, lz, scale, own)) return;
        if (tag == OceanFeatures.TAG_LAVA_CHANNEL && clearOfFuel(data, lx, lz)) {
            chunk.setBlockState(pos.set(x, y, z), Blocks.LAVA.defaultBlockState(), 0);
            chunk.setBlockState(pos.set(x, y - 1, z), Blocks.MAGMA_BLOCK.defaultBlockState(), 0);
        } else if (unit(mix(h + 9)) < MAGMA_CHANCE) {
            chunk.setBlockState(pos.set(x, y, z), Blocks.MAGMA_BLOCK.defaultBlockState(), 0);
        }
    }

    /** Soft blocks under a strandflat's surface that turn to stone. */
    private static final int STRAND_DEPTH = 3;

    private static void strandflat(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z, int minY) {
        int top = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x & 15, z & 15);
        int y = top;
        while (y > minY) {
            BlockState st = chunk.getBlockState(pos.set(x, y, z));
            if (!st.isAir() && st.getFluidState().isEmpty() && !st.is(Blocks.SNOW)) break;
            y--;
        }
        if (y <= minY) return;
        boolean underWater = !chunk.getBlockState(pos.set(x, y + 1, z)).getFluidState().isEmpty();
        float p = PATCHES.GetNoise(x * 1.5f - 2100, z * 1.5f);
        if (!underWater && SOIL.GetNoise(x + 900, z - 40) > 0.45f) return;    // pockets of grass or heath
        for (int k = 0; k < STRAND_DEPTH && y - k > minY; k++) {
            BlockState st = chunk.getBlockState(pos.set(x, y - k, z));
            if (!isRock(st)) break;
            BlockState b;
            if (k == 0 && !underWater && p > 0.35f) b = Blocks.MOSS_BLOCK.defaultBlockState();
            else if (k < 2 && (p < -0.4f || (underWater && p < 0.2f))) b = Blocks.GRAVEL.defaultBlockState();
            else b = Blocks.STONE.defaultBlockState();
            chunk.setBlockState(pos, b, 0);
        }
    }

    /** Tuff, with patches of smooth basalt. */
    private static BlockState tuffAt(int x, int y, int z) {
        float p = PATCHES.GetNoise(x * 0.9f - 700, z * 0.9f + 50);
        float q = STRATA.GetNoise(x * 0.4f + 90, y * 3f, z * 0.4f);
        return p > 0.45f || q > 0.7f ? Blocks.SMOOTH_BASALT.defaultBlockState() : Blocks.TUFF.defaultBlockState();
    }

    /** Fresh lava rock: basalt and blackstone in patches, a little smooth basalt. */
    private static BlockState lavaRockAt(int x, int y, int z) {
        float p = PATCHES.GetNoise(x * 1.3f + 1500, z * 1.3f);
        float q = STRATA.GetNoise(x * 0.8f - 900, y * 2f, z * 0.8f);
        if (q > 0.55f) return Blocks.SMOOTH_BASALT.defaultBlockState();
        return p > 0.1f ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.BASALT.defaultBlockState();
    }

    /** Top solid block Y of tile column (lx, lz) from the tile (rivers included), or MIN_VALUE outside the tile. */
    private static int tileTop(HeightmapData data, int lx, int lz, int scale) {
        if (lz < 0 || lx < 0 || lz >= data.heightmap.length || lx >= data.heightmap[0].length) return Integer.MIN_VALUE;
        if (data.surfaceY != null && data.surfaceY[lz][lx] != com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver.NONE) {
            return data.surfaceY[lz][lx];
        }
        return HeightConverter.convertToMinecraftHeight(data.heightmap[lz][lx], scale) - 1;
    }

    /** Whether all eight neighbours of tile column (lx, lz) stand at least as high as {@code own}. */
    private static boolean levelOrHollow(HeightmapData data, int lx, int lz, int scale, int own) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && tileTop(data, lx + dx, lz + dz, scale) < own) return false;
            }
        }
        return true;
    }

    /** Whether every column within {@link #LAVA_CLEARANCE} blocks is fresh lava rock (so no tree or grass is near). */
    private static boolean clearOfFuel(HeightmapData data, int lx, int lz) {
        byte[][] t = data.oceanTags;
        for (int dz = -LAVA_CLEARANCE; dz <= LAVA_CLEARANCE; dz++) {
            for (int dx = -LAVA_CLEARANCE; dx <= LAVA_CLEARANCE; dx++) {
                int r = lz + dz, c = lx + dx;
                if (r < 0 || c < 0 || r >= t.length || c >= t[0].length) return false;
                if (t[r][c] != OceanFeatures.TAG_LAVA && t[r][c] != OceanFeatures.TAG_LAVA_CHANNEL) return false;
            }
        }
        return true;
    }

    // ---- Deep floors: ooze, red clay, pillow lava, mud ----

    /** How many blocks of the deep floor's top are replaced. */
    private static final int DEEP_LAYER = 3;

    /**
     * Covers an open-sea floor with its sediment or rock: pale carbonate ooze (calcite with patches of diorite, like
     * the raised coral islands' limestone), red-brown clay (terracotta, brown and red terracotta in patches), pillow
     * lava (basalt, smooth basalt and blackstone) or mud. Only the soft floor and stone the terrain pass laid there
     * are replaced, and only under open sea water.
     */
    private static void deepFloor(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z, byte tag, int seaLevel, int minY) {
        int floor = chunk.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, x & 15, z & 15);
        // The heightmap gives the first free block above the floor in some versions, the floor in others: find the
        // floor itself, and require sea water right above it.
        int y = floor;
        for (int k = 0; k < 3 && y > minY; k++) {
            BlockState st = chunk.getBlockState(pos.set(x, y, z));
            if (!st.isAir() && st.getFluidState().isEmpty()) break;
            y--;
        }
        if (y <= minY || y >= seaLevel - 1) return;
        if (!chunk.getBlockState(pos.set(x, y + 1, z)).is(Blocks.WATER)) return;
        for (int k = 0; k < DEEP_LAYER && y - k > minY; k++) {
            pos.set(x, y - k, z);
            BlockState st = chunk.getBlockState(pos);
            if (!isRock(st)) break;
            chunk.setBlockState(pos, deepBlock(tag, x, y - k, z, k), 0);
        }
    }

    private static BlockState deepBlock(byte tag, int x, int y, int z, int below) {
        float p = PATCHES.GetNoise(x * 0.7f + 1000, z * 0.7f);
        float q = STRATA.GetNoise(x * 0.5f - 300, y * 2f, z * 0.5f);
        return switch (tag) {
            case OceanFeatures.TAG_OOZE -> p > 0.35f || q > 0.55f ? Blocks.DIORITE.defaultBlockState() : Blocks.CALCITE.defaultBlockState();
            case OceanFeatures.TAG_RED_CLAY -> p > 0.25f ? ((Block) Blocks.DYED_TERRACOTTA.red()).defaultBlockState()
                    : (p < -0.25f ? ((Block) Blocks.DYED_TERRACOTTA.brown()).defaultBlockState() : Blocks.TERRACOTTA.defaultBlockState());
            case OceanFeatures.TAG_PILLOW -> q > 0.3f ? Blocks.BLACKSTONE.defaultBlockState()
                    : (p > 0.2f ? Blocks.SMOOTH_BASALT.defaultBlockState() : Blocks.BASALT.defaultBlockState());
            case OceanFeatures.TAG_MUD -> below < 2 ? Blocks.MUD.defaultBlockState() : Blocks.PACKED_MUD.defaultBlockState();
            default -> Blocks.STONE.defaultBlockState();
        };
    }

    private static boolean isSoft(BlockState st) {
        return st.is(Blocks.GRASS_BLOCK) || st.is(Blocks.DIRT) || st.is(Blocks.COARSE_DIRT) || st.is(Blocks.PODZOL)
                || st.is(Blocks.ROOTED_DIRT) || st.is(Blocks.MUD) || st.is(Blocks.SAND) || st.is(Blocks.GRAVEL);
    }

    private static boolean isRock(BlockState st) {
        return isSoft(st) || st.is(Blocks.STONE) || st.is(Blocks.DEEPSLATE) || st.is(Blocks.GRANITE)
                || st.is(Blocks.ANDESITE) || st.is(Blocks.DIORITE) || st.is(Blocks.TUFF) || st.is(Blocks.SANDSTONE)
                || st.is(Blocks.CLAY) || st.is(Blocks.CALCITE) || st.is(Blocks.SAND) || st.is(Blocks.RED_SAND);
    }

    // ---- Hashing ----

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
