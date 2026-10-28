package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fills river and lake columns with water while a chunk's terrain is built.
 *
 * <p>Vanilla only floods terrain up to sea level, so inland water is placed here, right after the
 * terrain pass (see {@code NoiseBasedChunkGeneratorMixin}), while the chunk is still a plain
 * generation chunk. The terrain itself was already carved by {@link RiverCarver} (through the
 * heightmap tile), and the tile also records the Y of the highest water block for each column.
 */
public final class RiverWaterPlacer {
    private static final Logger LOG = LoggerFactory.getLogger(RiverWaterPlacer.class);
    private static final int MIN_Y = -64;
    private static volatile boolean loggedFirst;
    private static volatile boolean loggedFreeze;

    private RiverWaterPlacer() {
    }

    public static void place(ChunkAccess chunk) {
        try {
            placeUnchecked(chunk);
        } catch (Throwable t) {
            // Never let water placement break chunk generation.
            LOG.error("Orogeny rivers: water placement failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static void placeUnchecked(ChunkAccess chunk) {
        if (!TerrainDiffusionConfig.riversEnabled() || !TerrainDiffusionConfig.riversPlaceWater()) return;

        ChunkPos chunkPos = chunk.getPos();
        int x0 = chunkPos.getMinBlockX();
        int z0 = chunkPos.getMinBlockZ();

        int tileSize = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        int startX = (x0 >> shift) << shift;
        int startZ = (z0 >> shift) << shift;

        // The terrain pass just used this tile, so it is cached. Never generate it from here.
        HeightmapData data = LocalTerrainProvider.peekHeightmap(startZ, startX, startZ + tileSize, startX + tileSize);
        if (!loggedFirst) {
            loggedFirst = true;
            LOG.info("Orogeny rivers: first chunk reached the water placer (tile cached: {})", data != null);
        }
        if (data == null || data.waterY == null) return;

        boolean freezeOn = TerrainDiffusionConfig.riverFreezeEnabled();
        float freezeM = TerrainDiffusionConfig.riverFreezeHeightMetres();
        int freezeY = freezeM <= 0f ? Integer.MIN_VALUE
                : HeightConverter.convertToMinecraftHeight((short) Math.min(freezeM, 30000f), Math.max(1, WorldScaleManager.getCurrentScale()));
        if (!loggedFreeze) {
            loggedFreeze = true;
            LOG.info("Orogeny rivers: water freezes at Y >= {} (freeze_height_m={}, scale={})",
                    freezeY, freezeM, WorldScaleManager.getCurrentScale());
        }
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState ice = Blocks.ICE.defaultBlockState();
        BlockState gravel = Blocks.GRAVEL.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // River sinks (caves plan v1.5 part two): shafts from the bed into a water cave. Their columns keep the hole open.
        java.util.List<com.github.xandergos.terraindiffusionmc.pipeline.CaveSites.Shaft> sinks = CaveWater.sinksNear(x0, z0);

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = x0 + dx, z = z0 + dz;
                int lx = x - startX, lz = z - startZ;
                short top = data.waterY[lz][lx];
                if (top == RiverCarver.NONE) continue;

                boolean frozen = freezeOn && (freezeM > 0f ? top >= freezeY : frozenAround(data.biomeIds, lz, lx));
                short bed = data.surfaceY != null ? data.surfaceY[lz][lx] : RiverCarver.NONE;
                if (!sinks.isEmpty() && CaveWater.inSink(sinks, x, z)) {
                    // Water down to the bed, then let it pour into the shaft once the chunk loads.
                    int lowY = bed != RiverCarver.NONE ? bed : top - 2;
                    for (int y = top; y >= lowY && y > MIN_Y; y--) {
                        pos.set(x, y, z);
                        chunk.setBlockState(pos, (frozen && y == top) ? ice : water, 0);
                    }
                    pos.set(x, lowY, z);
                    chunk.markPosForPostProcessing(pos);
                    continue;
                }
                boolean isLake = data.waterIsLake != null && data.waterIsLake[lz][lx];
                // Find the ground under the water line. Whether the bed block itself is solid depends on
                // how the terrain pass rounds, so just look for the first solid block from the top down.
                int capY = bed != RiverCarver.NONE ? bed - 8 : top - 12;   // open this far below the bed = a cave
                int ground = Integer.MIN_VALUE;
                for (int y = top; y >= capY && y > MIN_Y; y--) {
                    pos.set(x, y, z);
                    BlockState st = chunk.getBlockState(pos);
                    if (!st.isAir() && !st.is(Blocks.SNOW)) { ground = y; break; }   // a snow layer is not ground
                }
                if (ground == Integer.MIN_VALUE) {
                    // Nothing solid down to the bed: a cave runs right under the channel.
                    if (!isLake) {
                        // A stream that meets a cave goes underground: leave a solid ford.
                        for (int y = top; y >= capY && y > MIN_Y; y--) {
                            pos.set(x, y, z);
                            BlockState fill = matchingFill(chunk, x, y, z, x0, z0, stone);
                            if (y == top && fill.is(Blocks.DIRT)) fill = Blocks.GRASS_BLOCK.defaultBlockState();
                            chunk.setBlockState(pos, fill, 0);
                        }
                        continue;
                    }
                    // A lake just gets a plugged floor.
                    pos.set(x, capY, z);
                    chunk.setBlockState(pos, gravel, 0);
                    ground = capY;
                }
                if (isLake && ground >= top) {
                    // Ground reaches the water line inside a lake. If the column is boxed in by water (3 or more of its
                    // 4 neighbours) it is a hole or a stub, not shore: dig it out and flood it one block deep.
                    int wetNeighbours = 0;
                    int th = data.waterY.length, tw = data.waterY[0].length;
                    if (lz > 0      && data.waterY[lz - 1][lx] != RiverCarver.NONE) wetNeighbours++;
                    if (lz < th - 1 && data.waterY[lz + 1][lx] != RiverCarver.NONE) wetNeighbours++;
                    if (lx > 0      && data.waterY[lz][lx - 1] != RiverCarver.NONE) wetNeighbours++;
                    if (lx < tw - 1 && data.waterY[lz][lx + 1] != RiverCarver.NONE) wetNeighbours++;
                    if (wetNeighbours >= 3) {
                        int surf = top;
                        for (int y = top + 1; y <= top + 2; y++) {
                            pos.set(x, y, z);
                            if (!chunk.getBlockState(pos).isAir()) surf = y;
                        }
                        for (int y = top + 1; y <= surf; y++) {
                            pos.set(x, y, z);
                            chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), 0);
                        }
                        pos.set(x, top, z);
                        chunk.setBlockState(pos, frozen ? ice : water, 0);
                        continue;
                    }
                }
                if (!isLake && ground >= top) {
                    // The terrain pass left ground at or above the water line here (the narrow channel was
                    // smoothed away), so dig the channel out again instead of leaving a dry carved line.
                    int surf = top;
                    for (int y = top + 1; y <= top + 3; y++) {
                        pos.set(x, y, z);
                        if (!chunk.getBlockState(pos).isAir()) surf = y;
                    }
                    for (int y = top + 1; y <= surf; y++) {
                        pos.set(x, y, z);
                        chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), 0);
                    }
                    int lowY = bed != RiverCarver.NONE ? bed + 1 : top - 2;
                    for (int y = top; y >= lowY && y > MIN_Y; y--) {
                        pos.set(x, y, z);
                        chunk.setBlockState(pos, (frozen && y == top) ? ice : water, 0);
                    }
                    continue;
                }
                for (int y = top; y > ground; y--) {
                    pos.set(x, y, z);
                    chunk.setBlockState(pos, (frozen && y == top) ? ice : water, 0);
                }
                if (ground < top) wetFloor(chunk, pos, x, z, ground, gravel);
                // Lakes from two blocks of water, rivers from three (streams are shallower and stay bare).
                plantFloor(chunk, pos, x, z, ground, top, frozen, isLake ? 2 : 3);
            }
        }

        // Seal gaps beside the water: a cave mouth or hollow touching a river or lake would otherwise
        // leave a bare wall of water. Fill those blocks up to the water line with stone.
        // Neighbours across the tile's edge are read from the next tile (the river levels of two tiles can differ by a
        // block or two along their seam; a step there was left standing as a wall of water).
        Tiles tiles = new Tiles(data, startX, startZ, tileSize);
        int[] ddz = {-1, 1, 0, 0}, ddx = {0, 0, -1, 1};
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = x0 + dx, z = z0 + dz;
                int lx = x - startX, lz = z - startZ;
                if (data.waterY[lz][lx] != RiverCarver.NONE) continue;
                for (int k = 0; k < 4; k++) {
                    int nxw = x + ddx[k], nzw = z + ddz[k];
                    short nTop = tiles.water(nxw, nzw);
                    if (nTop == RiverCarver.NONE) continue;
                    short nBed = tiles.bed(nxw, nzw);
                    int low = nBed != RiverCarver.NONE ? nBed : nTop - 3;
                    for (int y = nTop; y >= low && y > MIN_Y; y--) {
                        pos.set(x, y, z);
                        if (chunk.getBlockState(pos).isAir()) {
                            chunk.setBlockState(pos, matchingFill(chunk, x, y, z, x0, z0, stone), 0);
                        }
                    }
                }
            }
        }

        // Where a stream steps down to a lower water level, the higher water would otherwise stay frozen as a wall above
        // the lower water: water placed during generation is not simulated. Mark those top blocks for the post-generation
        // update (vanilla does the same for aquifer water), so they flow down once the chunk is loaded.
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = x0 + dx, z = z0 + dz;
                int lx = x - startX, lz = z - startZ;
                short top = data.waterY[lz][lx];
                if (top == RiverCarver.NONE) continue;
                // (Lakes too: where a lake drains into a lower stream, its edge water spills down instead of standing as a wall.)
                boolean stepsDown = false;
                for (int k = 0; k < 4 && !stepsDown; k++) {
                    short nTop = tiles.water(x + ddx[k], z + ddz[k]);
                    if (nTop != RiverCarver.NONE && nTop < top) stepsDown = true;
                }
                if (!stepsDown) continue;
                pos.set(x, top, z);
                if (chunk.getBlockState(pos).is(Blocks.WATER)) markForUpdate(chunk, pos.immutable());
            }
        }
    }

    /** Water and bed heights by world column: this chunk's tile, or a neighbouring one if it is cached (never built). */
    private static final class Tiles {
        final HeightmapData home;
        final int startX, startZ, size;
        HeightmapData other;
        int otherX = Integer.MIN_VALUE, otherZ = Integer.MIN_VALUE;

        Tiles(HeightmapData home, int startX, int startZ, int size) {
            this.home = home;
            this.startX = startX;
            this.startZ = startZ;
            this.size = size;
        }

        private HeightmapData tile(int x, int z) {
            int tx = Math.floorDiv(x, size) * size, tz = Math.floorDiv(z, size) * size;
            if (tx == startX && tz == startZ) return home;
            if (tx != otherX || tz != otherZ) {
                other = LocalTerrainProvider.peekHeightmap(tz, tx, tz + size, tx + size);
                otherX = tx;
                otherZ = tz;
            }
            return other;
        }

        short water(int x, int z) {
            HeightmapData t = tile(x, z);
            if (t == null || t.waterY == null) return RiverCarver.NONE;
            return t.waterY[Math.floorMod(z, size)][Math.floorMod(x, size)];
        }

        short bed(int x, int z) {
            HeightmapData t = tile(x, z);
            if (t == null || t.surfaceY == null) return RiverCarver.NONE;
            return t.surfaceY[Math.floorMod(z, size)][Math.floorMod(x, size)];
        }
    }

    /**
     * The surface rules ran before the water was placed, so the floor got the dry-land top block of its biome: snow on
     * snowy slopes and peaks, grass elsewhere. Under water that becomes gravel and dirt.
     */
    private static void wetFloor(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z, int ground, BlockState gravel) {
        pos.set(x, ground, z);
        BlockState floor = chunk.getBlockState(pos);
        if (isSnow(floor)) {
            chunk.setBlockState(pos, gravel, 0);
        } else if (floor.is(Blocks.GRASS_BLOCK) || floor.is(Blocks.PODZOL) || floor.is(Blocks.MYCELIUM)) {
            chunk.setBlockState(pos, Blocks.DIRT.defaultBlockState(), 0);
        }
    }

    private static boolean isSnow(BlockState st) {
        return st.is(Blocks.SNOW_BLOCK) || st.is(Blocks.POWDER_SNOW) || st.is(Blocks.SNOW);
    }

    /**
     * Seagrass on lake and river floors, in meadows: dense patches (about ten blocks across) with sparse plants between
     * them. Only where at least {@code minDepth} blocks of water cover the floor (shallow edges and streams stay bare),
     * tall seagrass where there is a block more. Kelp is left to the sea.
     */
    private static void plantFloor(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z, int ground, int top,
                                   boolean frozen, int minDepth) {
        int waterTop = frozen ? top - 1 : top;   // frozen water has ice as its top block
        int depth = waterTop - ground;           // water blocks above the floor
        if (depth < minDepth) return;
        pos.set(x, ground, z);
        BlockState floor = chunk.getBlockState(pos);
        if (!floor.isFaceSturdy(chunk, pos, Direction.UP) || floor.is(BlockTags.CANNOT_SUPPORT_SEAGRASS)) return;
        long seed = LocalTerrainProvider.getSeed();
        float meadow = patchNoise(x, z, seed);
        float chance = meadow > 0.58f ? 0.65f : meadow > 0.45f ? 0.22f : 0.03f;
        int h = hash(x, z, seed);
        if ((h & 0xFFFF) / 65536f >= chance) return;
        pos.set(x, ground + 1, z);
        if (depth >= Math.max(3, minDepth + 1) && ((h >>> 16) & 0xFF) < 77) {   // about 30% tall where there is room
            chunk.setBlockState(pos, Blocks.TALL_SEAGRASS.defaultBlockState().setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.LOWER), 0);
            pos.set(x, ground + 2, z);
            chunk.setBlockState(pos, Blocks.TALL_SEAGRASS.defaultBlockState().setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.UPPER), 0);
        } else {
            chunk.setBlockState(pos, Blocks.SEAGRASS.defaultBlockState(), 0);
        }
    }

    private static int hash(int x, int z, long seed) {
        long h = x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL + seed;
        h ^= h >>> 31; h *= 0xBF58476D1CE4E5B9L; h ^= h >>> 29;
        return (int) h;
    }

    /** Smooth value noise in [0, 1] on a 9-block lattice: where the seagrass meadows are. */
    private static float patchNoise(int x, int z, long seed) {
        final int S = 9;
        int x0 = Math.floorDiv(x, S), z0 = Math.floorDiv(z, S);
        float fx = (x - x0 * S) / (float) S, fz = (z - z0 * S) / (float) S;
        fx = fx * fx * (3f - 2f * fx); fz = fz * fz * (3f - 2f * fz);
        float a = lattice(x0, z0, seed), b = lattice(x0 + 1, z0, seed), c = lattice(x0, z0 + 1, seed), d = lattice(x0 + 1, z0 + 1, seed);
        float top = a + (b - a) * fx, bottom = c + (d - c) * fx;
        return top + (bottom - top) * fz;
    }

    private static float lattice(int x, int z, long seed) {
        return (hash(x, z, seed ^ 0x5EA6A55L) & 0xFFFF) / 65535f;
    }

    private static volatile java.lang.reflect.Method postProcessMethod;
    private static volatile boolean postProcessLookedUp;

    /**
     * Marks a block for the post-generation update (vanilla's "post-processing", used for aquifer water). The method's
     * name has changed between versions, so it is found by reflection once; if none is found, the candidate method
     * names are logged and water steps simply stay as they are.
     */
    private static void markForUpdate(ChunkAccess chunk, BlockPos pos) {
        if (!postProcessLookedUp) {
            synchronized (RiverWaterPlacer.class) {
                if (!postProcessLookedUp) {
                    java.lang.reflect.Method found = null;
                    StringBuilder names = new StringBuilder();
                    for (java.lang.reflect.Method m : chunk.getClass().getMethods()) {
                        String n = m.getName().toLowerCase(java.util.Locale.ROOT);
                        if (n.contains("post") || n.contains("tick")) names.append(m.getName()).append(java.util.Arrays.toString(m.getParameterTypes())).append(' ');
                        if (n.contains("postprocess") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isAssignableFrom(BlockPos.class)) found = m;
                    }
                    postProcessMethod = found;
                    postProcessLookedUp = true;
                    if (found != null) LOG.info("Orogeny rivers: water steps use {}.{}", found.getDeclaringClass().getSimpleName(), found.getName());
                    else LOG.warn("Orogeny rivers: no post-processing method found, water steps will not flow. Candidates: {}", names);
                }
            }
        }
        java.lang.reflect.Method m = postProcessMethod;
        if (m == null) return;
        try {
            m.invoke(chunk, pos);
        } catch (ReflectiveOperationException e) {
            postProcessMethod = null;
            LOG.warn("Orogeny rivers: post-processing call failed, water steps will not flow", e);
        }
    }

    /**
     * Picks a block for a gap beside the water so it blends in: the solid block next to it at the same
     * height, else the first solid block below it. Grass-like tops become dirt when buried.
     */
    private static BlockState matchingFill(ChunkAccess chunk, int x, int y, int z, int x0, int z0, BlockState fallback) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int[] ox = {-1, 1, 0, 0}, oz = {0, 0, -1, 1};
        BlockState found = null;
        for (int k = 0; k < 4 && found == null; k++) {
            int nx = x + ox[k], nz = z + oz[k];
            if (nx < x0 || nx > x0 + 15 || nz < z0 || nz > z0 + 15) continue;
            BlockState st = chunk.getBlockState(p.set(nx, y, nz));
            if (isFillable(st)) found = st;
        }
        for (int d = 1; d <= 16 && found == null && y - d > MIN_Y; d++) {
            BlockState st = chunk.getBlockState(p.set(x, y - d, z));
            if (isFillable(st)) found = st;
        }
        if (found == null) return fallback;
        if (isSnow(found)) return fallback;   // the gap faces water: stone, not a wall of snow
        boolean airAbove = chunk.getBlockState(p.set(x, y + 1, z)).isAir();
        if (!airAbove && (found.is(Blocks.GRASS_BLOCK) || found.is(Blocks.PODZOL) || found.is(Blocks.MYCELIUM))) {
            return Blocks.DIRT.defaultBlockState();
        }
        return found;
    }

    private static boolean isFillable(BlockState st) {
        return !st.isAir() && st.getFluidState().isEmpty() && !st.is(Blocks.ICE) && st.canOcclude();
    }

    /** Water only freezes well inside snowy biomes, so streams never ice up at the edge of the snow line. */
    private static boolean frozenAround(short[][] biomeIds, int lz, int lx) {
        int h = biomeIds.length, w = biomeIds[0].length;
        for (int dz = -8; dz <= 8; dz += 4) {
            for (int dx = -8; dx <= 8; dx += 4) {
                int z = Math.max(0, Math.min(h - 1, lz + dz));
                int x = Math.max(0, Math.min(w - 1, lx + dx));
                if (!RiverCarver.isFrozen(biomeIds[z][x])) return false;
            }
        }
        return true;
    }
}
