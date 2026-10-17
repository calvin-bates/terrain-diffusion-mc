package com.github.xandergos.terraindiffusionmc.world;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.material.FluidState;

/**
 * Axolotls in alpine lakes, rarely.
 *
 * <p>In a Terra world an axolotl can spawn naturally in lake water that is
 * <ul>
 *   <li>high up: at least {@link #ALPINE_METRES} real-world metres, so the Y level it needs depends on the world scale
 *       (about Y 463 at scale 6, Y 196 at scale 2, Y 129 at scale 1);</li>
 *   <li>in an "axolotl chunk": like slime chunks, a fixed random set of chunks from the world seed, but rarer
 *       (one chunk in {@link #CHUNK_RARITY}; slime chunks are one in 10);</li>
 *   <li>part of a lake, not a stream: still water across most of an 11x11 area at the water's surface.</li>
 * </ul>
 * Lush caves keep vanilla's rule. Everywhere else (and in non-Terra worlds) the spawn entry added here never succeeds.
 */
public final class AxolotlLakes {
    /** Lake water must be at least this high, in real-world metres. */
    public static final short ALPINE_METRES = 2000;
    /** One chunk in this many is an axolotl chunk. */
    public static final int CHUNK_RARITY = 20;
    /** Mixed into the chunk's random seed so axolotl chunks are not the slime chunks. */
    private static final long CHUNK_SALT = 418813574L;
    /** Half the side of the square checked for open lake water, and how much of it must be still water. */
    private static final int LAKE_RADIUS = 5;
    private static final int LAKE_MIN_STILL = 100;   // of 121

    private AxolotlLakes() {
    }

    /**
     * Adds axolotls (groups of 1-3) to the axolotl spawn list of every overworld biome except lush caves (which keep
     * vanilla's list); {@link #canSpawn} decides where they may actually appear.
     */
    public static void registerSpawns() {
        BiomeModifications.addSpawn(BiomeSelectors.foundInOverworld().and(BiomeSelectors.excludeByKey(Biomes.LUSH_CAVES)),
                MobCategory.AXOLOTLS, EntityTypes.AXOLOTL, 1, 1, 3);
    }

    /** Lowest Y an axolotl may spawn at in the current world (depends on its scale). */
    public static int minY() {
        return HeightConverter.convertToMinecraftHeight(ALPINE_METRES, WorldScaleManager.getCurrentScale());
    }

    /** True for the rare chunks where axolotls can live. */
    public static boolean isAxolotlChunk(int chunkX, int chunkZ, long seed) {
        return WorldgenRandom.seedSlimeChunk(chunkX, chunkZ, seed, CHUNK_SALT).nextInt(CHUNK_RARITY) == 0;
    }

    /** Natural-spawn rule for axolotls outside lush caves. */
    public static boolean canSpawn(ServerLevelAccessor level, BlockPos pos) {
        ServerLevel serverLevel = level.getLevel();
        if (!(serverLevel.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return false;
        if (pos.getY() < minY()) return false;
        if (!isAxolotlChunk(pos.getX() >> 4, pos.getZ() >> 4, serverLevel.getSeed())) return false;
        int x = pos.getX(), z = pos.getZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        if (!level.getFluidState(m.set(x, pos.getY(), z)).is(FluidTags.WATER)) return false;
        // The water's surface above this spot (under any ice).
        int top = pos.getY();
        int maxY = level.getMaxY();
        while (top < maxY && level.getFluidState(m.set(x, top + 1, z)).is(FluidTags.WATER)) top++;
        // A lake, not a stream: still water across most of the square around it at the surface.
        // (only where every chunk the square touches is loaded: never load or generate a chunk from a spawn check)
        for (int cx = (x - LAKE_RADIUS) >> 4; cx <= (x + LAKE_RADIUS) >> 4; cx++)
            for (int cz = (z - LAKE_RADIUS) >> 4; cz <= (z + LAKE_RADIUS) >> 4; cz++)
                if (!level.hasChunk(cx, cz)) return false;
        int still = 0;
        for (int dx = -LAKE_RADIUS; dx <= LAKE_RADIUS; dx++) {
            for (int dz = -LAKE_RADIUS; dz <= LAKE_RADIUS; dz++) {
                FluidState fluid = level.getFluidState(m.set(x + dx, top, z + dz));
                if (fluid.is(FluidTags.WATER) && fluid.isSource()) still++;
            }
        }
        return still >= LAKE_MIN_STILL;
    }
}
