package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.hydrology.RiverCarver;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Aquifer;

/**
 * Keeps caves and ravines under dry land dry.
 *
 * <p>Terra's noise settings have no aquifers, so vanilla falls back to a "disabled" aquifer that answers sea-level water
 * for every carved block below the sea level. On land lower than about Y 63 that floods every ravine and cave entrance
 * up to Y 62. This wraps the carving aquifer so that, in columns of land that are at least {@link #RADIUS} blocks from
 * the sea, carved blocks become air instead of water. Lava deep down is untouched, and so are caves under and near the
 * ocean. Along that boundary the water is scheduled to flow, so a cave reaching from the shore into land floods
 * naturally instead of ending in a standing wall of water.
 */
public final class DryCaves {
    /** How far (blocks) a column must be from sea water for its caves to stay dry. */
    private static final int RADIUS = 4;
    private static final byte VANILLA = 0, DRY = 1, FLOW = 2;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private DryCaves() {
    }

    /** The aquifer to carve this chunk with: the vanilla one, or one that keeps caves under land dry. */
    public static Aquifer wrap(ChunkAccess chunk, Aquifer inner, int seaLevel) {
        byte[] kind = classify(chunk, seaLevel);
        if (kind == null) return inner;
        return new Aquifer() {
            private boolean schedule;

            @Override
            public BlockState computeSubstance(int blockX, int blockY, int blockZ, double density) {
                BlockState state = inner.computeSubstance(blockX, blockY, blockZ, density);
                schedule = inner.shouldScheduleFluidUpdate();
                if (state == null || !state.is(Blocks.WATER)) return state;
                byte k = kind[((blockX & 15) << 4) | (blockZ & 15)];
                if (k == DRY) {
                    schedule = false;
                    return AIR;
                }
                if (k == FLOW) schedule = true;
                return state;
            }

            @Override
            public boolean shouldScheduleFluidUpdate() {
                return schedule;
            }
        };
    }

    /** Per chunk column (index x*16+z): DRY, FLOW (sea water beside a dry column) or VANILLA; null if nothing is dry. */
    private static byte[] classify(ChunkAccess chunk, int seaLevel) {
        ChunkPos cp = chunk.getPos();
        int margin = RADIUS + 1;
        int size = 16 + 2 * margin;
        int x0 = cp.getMinBlockX() - margin, z0 = cp.getMinBlockZ() - margin;
        // Whether each column of the chunk plus its margin is free of sea water (land, or inland water).
        boolean[] land = new boolean[size * size];
        int tileSize = TerrainDiffusionConfig.tileSize();
        int shift = Integer.numberOfTrailingZeros(tileSize);
        HeightmapData tile = null;
        int tileX = Integer.MIN_VALUE, tileZ = Integer.MIN_VALUE;
        boolean anyLand = false;
        // Sea caves (caves plan v1.5 part two) count as sea, so their passages fill with sea water.
        com.github.xandergos.terraindiffusionmc.pipeline.SeaCaves seaCaves = TerrainDiffusionConfig.caves()
                && TerrainDiffusionConfig.seaCaves()
                ? com.github.xandergos.terraindiffusionmc.pipeline.SeaCaves.forSeed(LocalTerrainProvider.getSeed()) : null;
        CaveSurface surface = seaCaves == null ? null : CaveSurface.get();
        // So do sea arches and coastal overhangs.
        boolean arches = seaCaves != null && TerrainDiffusionConfig.caveFormations();
        java.util.List<com.github.xandergos.terraindiffusionmc.pipeline.CaveSites.Cut> archList = new java.util.ArrayList<>();
        if (arches) {
            com.github.xandergos.terraindiffusionmc.pipeline.SeaArches.generation = LocalTerrainProvider.cacheGeneration();
            com.github.xandergos.terraindiffusionmc.pipeline.SeaArches.arches(x0, z0, x0 + size - 1, z0 + size - 1, surface, archList);
        }
        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                int x = x0 + dx, z = z0 + dz;
                int tx = (x >> shift) << shift, tz = (z >> shift) << shift;
                if (tx != tileX || tz != tileZ) {
                    tile = LocalTerrainProvider.peekHeightmap(tz, tx, tz + tileSize, tx + tileSize);
                    // A neighbouring tile not built yet used to count as sea, flooding the caves along this chunk's
                    // edge below Y 62 (a wall of water that then poured into the dry caves beside it). Build it
                    // instead, as the cave shapes of the same chunk already do (CaveSurface).
                    if (tile == null) {
                        LocalTerrainProvider p = LocalTerrainProvider.getInstance();
                        if (p != null) tile = p.fetchHeightmap(tz, tx, tz + tileSize, tx + tileSize);
                    }
                    tileX = tx; tileZ = tz;
                }
                if (tile == null) continue;   // unknown (no terrain provider): treat as sea, the vanilla behaviour
                int lz = z - tz, lx = x - tx;
                boolean dry;
                if (tile.waterY != null && tile.waterY[lz][lx] != RiverCarver.NONE) {
                    dry = true;   // a lake or river: caves under it are kept away by InlandWaterCarving
                } else {
                    int top = HeightConverter.convertToMinecraftHeight(tile.heightmap[lz][lx]) - 1;   // top solid block
                    if (tile.surfaceY != null && tile.surfaceY[lz][lx] != RiverCarver.NONE) top = tile.surfaceY[lz][lx];
                    dry = top >= seaLevel - 1;   // no sea water above it
                    if (dry && seaCaves != null && seaCaves.active(x, z, surface)) dry = false;
                    if (dry && arches) {
                        for (var a : archList)
                            if (((com.github.xandergos.terraindiffusionmc.pipeline.SeaArches.Arch) a).wet(x, z)) { dry = false; break; }
                        if (dry && com.github.xandergos.terraindiffusionmc.pipeline.SeaArches.notch(x, z, surface) >= 0) dry = false;
                    }
                }
                land[dz * size + dx] = dry;
                anyLand |= dry;
            }
        }
        if (!anyLand) return null;

        // A column's caves stay dry when every column within RADIUS of it is land. Computed for the chunk plus a 1-block
        // ring so sea water right next to a dry column (possibly in the next chunk) can be told to flow.
        int ring = 16 + 2;
        boolean[] dry = new boolean[ring * ring];
        boolean anyDry = false;
        for (int rz = 0; rz < ring; rz++) {
            for (int rx = 0; rx < ring; rx++) {
                int cx = rx + margin - 1, cz = rz + margin - 1;   // position in the land grid
                boolean ok = true;
                for (int dz = -RADIUS; dz <= RADIUS && ok; dz++)
                    for (int dx = -RADIUS; dx <= RADIUS && ok; dx++)
                        if (dx * dx + dz * dz <= RADIUS * RADIUS && !land[(cz + dz) * size + (cx + dx)]) ok = false;
                dry[rz * ring + rx] = ok;
                anyDry |= ok;
            }
        }
        if (!anyDry) return null;

        byte[] kind = new byte[256];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int rx = x + 1, rz = z + 1;
                byte k = VANILLA;
                if (dry[rz * ring + rx]) {
                    k = DRY;
                } else {
                    for (int dz = -1; dz <= 1 && k == VANILLA; dz++)
                        for (int dx = -1; dx <= 1; dx++)
                            if (dry[(rz + dz) * ring + (rx + dx)]) { k = FLOW; break; }
                }
                kind[(x << 4) | z] = k;
            }
        }
        return kind;
    }
}
