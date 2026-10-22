package com.github.xandergos.terraindiffusionmc.world;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectionContext;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;

import java.util.Set;
import java.util.function.Predicate;

/**
 * Fish in Terra's lakes. Vanilla only spawns fish in ocean and river biomes, and only within 13 blocks below sea level;
 * Terra's lakes lie in land biomes and often far above the sea, so they had none.
 *
 * <ul>
 *   <li>Salmon: lakes in cold and cool biomes (taiga, snowy and mountain biomes, meadow, windswept hills).</li>
 *   <li>Cod: larger lakes in any biome that is not warm.</li>
 *   <li>Tropical fish: lakes in warm biomes (jungle, savanna, swamp, desert).</li>
 * </ul>
 * A fish needs water at least three blocks deep (water below and above it, as in vanilla) in open still water:
 * most of the 11x11 area at the surface for salmon and tropical fish, most of 15x15 for cod. Streams are too
 * shallow and narrow. Oceans and rivers keep vanilla's rules, and non-Terra worlds are unchanged.
 */
public final class InlandFish {
    private static final Set<String> WARM = Set.of("jungle", "sparse_jungle", "bamboo_jungle", "savanna", "savanna_plateau",
            "windswept_savanna", "swamp", "mangrove_swamp", "desert", "cloud_forest", "tropical_dry_forest", "rocky_desert");
    private static final Set<String> COLD = Set.of("taiga", "snowy_taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga",
            "taiga_sparse", "snowy_taiga_sparse", "grove", "snowy_slopes", "snowy_plains", "ice_spikes", "frozen_peaks",
            "jagged_peaks", "stony_peaks", "meadow", "windswept_hills", "windswept_gravelly_hills", "windswept_forest",
            "tundra", "polar_desert", "treeline", "boreal_forest", "siberian_taiga", "birch_taiga", "taiga_mountain",
            "taiga_clearing", "bog", "moorland", "temperate_rainforest", "redwoods", "aspen_parkland", "forested_highland");

    private InlandFish() {
    }

    private static String path(BiomeSelectionContext context) {
        return context.getBiomeKey().identifier().getPath();
    }

    /** Adds the fish to the water-ambient spawn lists of overworld land biomes (oceans and rivers already have theirs). */
    public static void registerSpawns() {
        Predicate<BiomeSelectionContext> land = BiomeSelectors.foundInOverworld()
                .and(BiomeSelectors.tag(BiomeTags.IS_OCEAN).negate())
                .and(BiomeSelectors.tag(BiomeTags.IS_RIVER).negate());
        BiomeModifications.addSpawn(land.and(c -> COLD.contains(path(c))), MobCategory.WATER_AMBIENT, EntityTypes.SALMON, 5, 1, 4);
        BiomeModifications.addSpawn(land.and(c -> !WARM.contains(path(c))), MobCategory.WATER_AMBIENT, EntityTypes.COD, 3, 1, 3);
        BiomeModifications.addSpawn(land.and(c -> WARM.contains(path(c))), MobCategory.WATER_AMBIENT, EntityTypes.TROPICAL_FISH, 5, 2, 5);
    }

    /** True if vanilla's own fish rule applies here: oceans and rivers. */
    public static boolean vanillaBiome(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER);
    }

    /** Natural-spawn rule for salmon, cod and tropical fish outside oceans and rivers. */
    public static boolean canSpawn(EntityType<?> type, LevelAccessor level, BlockPos pos) {
        if (!(level instanceof ServerLevelAccessor accessor)) return false;
        ServerLevel serverLevel = accessor.getLevel();
        if (!(serverLevel.getChunkSource().getGenerator().getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return false;
        int radius;
        if (type == EntityTypes.COD) radius = 7;
        else if (type == EntityTypes.SALMON || type == EntityTypes.TROPICAL_FISH) radius = 5;
        else return false;
        // At least three blocks of water, as in vanilla.
        if (!level.getFluidState(pos).is(FluidTags.WATER) || !level.getFluidState(pos.below()).is(FluidTags.WATER)
                || !level.getBlockState(pos.above()).is(Blocks.WATER)) return false;
        return openStillWater(level, pos, radius, 0.8f);
    }

    /**
     * True if, at the water's surface above pos, at least {@code share} of the square of the given radius is still water
     * (a lake rather than a stream). Only checks where every chunk it touches is loaded.
     */
    public static boolean openStillWater(LevelAccessor level, BlockPos pos, int radius, float share) {
        int x = pos.getX(), z = pos.getZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int top = pos.getY();
        int maxY = level.getMaxY();
        while (top < maxY && level.getFluidState(m.set(x, top + 1, z)).is(FluidTags.WATER)) top++;
        for (int cx = (x - radius) >> 4; cx <= (x + radius) >> 4; cx++)
            for (int cz = (z - radius) >> 4; cz <= (z + radius) >> 4; cz++)
                if (!level.hasChunk(cx, cz)) return false;
        int still = 0, side = 2 * radius + 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                FluidState fluid = level.getFluidState(m.set(x + dx, top, z + dz));
                if (fluid.is(FluidTags.WATER) && fluid.isSource()) still++;
            }
        }
        return still >= share * side * side;
    }
}
