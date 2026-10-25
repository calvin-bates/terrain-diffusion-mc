package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.HolderGetter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Map;
import com.github.xandergos.terraindiffusionmc.pipeline.RegionalBiomes;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Map.entry;

public class TerrainDiffusionBiomeSource extends BiomeSource {
    private static final ResourceKey<Biome> FOREST_SPARSE = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("terra", "forest_sparse"));
    private static final ResourceKey<Biome> TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("terra", "taiga_sparse"));
    private static final ResourceKey<Biome> SNOWY_TAIGA_SPARSE = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("terra", "snowy_taiga_sparse"));
    /** Vanilla dark forest without the huge mushrooms (biomes plan v1.1). */
    private static final ResourceKey<Biome> DARK_FOREST = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("terra", "dark_forest"));

    public static final MapCodec<TerrainDiffusionBiomeSource> CODEC = RecordCodecBuilder.mapCodec((instance) ->
            instance.group(
                    RegistryOps.retrieveGetter(Registries.BIOME)
            ).apply(instance, instance.stable(TerrainDiffusionBiomeSource::new)));


    private HolderGetter<Biome> biomeLookup;
    private Map<Short, Holder<Biome>> biomeIdMap = null;

    public TerrainDiffusionBiomeSource(HolderGetter<Biome> biomeLookup) {
        this.biomeLookup = biomeLookup;
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    private void requireBiomeIdMap() {
        if (biomeIdMap == null) {
            Map<Short, Holder<Biome>> m = new java.util.HashMap<>(Map.ofEntries(
                    entry((short) 1, this.biomeLookup.getOrThrow(Biomes.PLAINS)),
                    entry((short) 3, this.biomeLookup.getOrThrow(Biomes.SNOWY_PLAINS)),
                    entry((short) 5, this.biomeLookup.getOrThrow(Biomes.DESERT)),
                    entry((short) 6, this.biomeLookup.getOrThrow(Biomes.SWAMP)),
                    entry((short) 8, this.biomeLookup.getOrThrow(Biomes.FOREST)),
                    entry((short) 15, this.biomeLookup.getOrThrow(Biomes.TAIGA)),
                    entry((short) 16, this.biomeLookup.getOrThrow(Biomes.SNOWY_TAIGA)),
                    entry((short) 17, this.biomeLookup.getOrThrow(Biomes.SAVANNA)),
                    entry((short) 19, this.biomeLookup.getOrThrow(Biomes.WINDSWEPT_HILLS)),
                    entry((short) 23, this.biomeLookup.getOrThrow(Biomes.JUNGLE)),
                    entry((short) 26, this.biomeLookup.getOrThrow(Biomes.BADLANDS)),
                    entry((short) 29, this.biomeLookup.getOrThrow(Biomes.MEADOW)),
                    entry((short) 31, this.biomeLookup.getOrThrow(Biomes.GROVE)),
                    entry((short) 32, this.biomeLookup.getOrThrow(Biomes.SNOWY_SLOPES)),
                    entry((short) 33, this.biomeLookup.getOrThrow(Biomes.FROZEN_PEAKS)),
                    entry((short) 35, this.biomeLookup.getOrThrow(Biomes.STONY_PEAKS)),
                    entry((short) 41, this.biomeLookup.getOrThrow(Biomes.WARM_OCEAN)),
                    entry((short) 44, this.biomeLookup.getOrThrow(Biomes.OCEAN)),
                    entry((short) 46, this.biomeLookup.getOrThrow(Biomes.COLD_OCEAN)),
                    entry((short) 48, this.biomeLookup.getOrThrow(Biomes.FROZEN_OCEAN)),
                    entry((short) 42, this.biomeLookup.getOrThrow(Biomes.LUKEWARM_OCEAN)),
                    entry((short) 43, this.biomeLookup.getOrThrow(Biomes.DEEP_LUKEWARM_OCEAN)),
                    entry((short) 45, this.biomeLookup.getOrThrow(Biomes.DEEP_OCEAN)),
                    entry((short) 47, this.biomeLookup.getOrThrow(Biomes.DEEP_COLD_OCEAN)),
                    entry((short) 49, this.biomeLookup.getOrThrow(Biomes.DEEP_FROZEN_OCEAN)),
                    entry((short) 130, this.biomeLookup.getOrThrow(Biomes.BEACH)),
                    entry((short) 131, this.biomeLookup.getOrThrow(Biomes.SNOWY_BEACH)),
                    entry((short) 132, this.biomeLookup.getOrThrow(Biomes.STONY_SHORE)),
                    entry((short) 134, this.biomeLookup.getOrThrow(Biomes.MANGROVE_SWAMP)),
                    entry((short) 133, this.biomeLookup.getOrThrow(Biomes.MUSHROOM_FIELDS)),
                    entry((short) 120, this.biomeLookup.getOrThrow(Biomes.RIVER)),
                    entry((short) 121, this.biomeLookup.getOrThrow(Biomes.FROZEN_RIVER)),
                    entry((short) 108, this.biomeLookup.getOrThrow(FOREST_SPARSE)),
                    entry((short) 115, this.biomeLookup.getOrThrow(TAIGA_SPARSE)),
                    entry((short) 116, this.biomeLookup.getOrThrow(SNOWY_TAIGA_SPARSE)),
                    // v1.1 patch biomes (BiomePatches)
                    entry((short) 2, this.biomeLookup.getOrThrow(Biomes.SUNFLOWER_PLAINS)),
                    entry((short) 9, this.biomeLookup.getOrThrow(Biomes.FLOWER_FOREST)),
                    entry((short) 10, this.biomeLookup.getOrThrow(Biomes.BIRCH_FOREST)),
                    entry((short) 11, this.biomeLookup.getOrThrow(DARK_FOREST)),
                    entry((short) 12, this.biomeLookup.getOrThrow(Biomes.OLD_GROWTH_BIRCH_FOREST)),
                    entry((short) 13, this.biomeLookup.getOrThrow(Biomes.OLD_GROWTH_PINE_TAIGA)),
                    entry((short) 14, this.biomeLookup.getOrThrow(Biomes.OLD_GROWTH_SPRUCE_TAIGA)),
                    entry((short) 18, this.biomeLookup.getOrThrow(Biomes.SAVANNA_PLATEAU)),
                    entry((short) 20, this.biomeLookup.getOrThrow(Biomes.WINDSWEPT_GRAVELLY_HILLS)),
                    entry((short) 21, this.biomeLookup.getOrThrow(Biomes.WINDSWEPT_FOREST)),
                    entry((short) 22, this.biomeLookup.getOrThrow(Biomes.WINDSWEPT_SAVANNA)),
                    entry((short) 24, this.biomeLookup.getOrThrow(Biomes.SPARSE_JUNGLE)),
                    entry((short) 25, this.biomeLookup.getOrThrow(Biomes.BAMBOO_JUNGLE)),
                    entry((short) 30, this.biomeLookup.getOrThrow(Biomes.CHERRY_GROVE)),
                    entry((short) 34, this.biomeLookup.getOrThrow(Biomes.JAGGED_PEAKS)),
                    entry((short) 36, this.biomeLookup.getOrThrow(Biomes.PALE_GARDEN)),
                    entry((short) 37, this.biomeLookup.getOrThrow(Biomes.DAPPLED_FOREST))
            ));
            // v1.2 real-world biomes (terra namespace; data from tools/biomes/gen_biomes_v12.py).
            for (Map.Entry<Short, String> e : RegionalBiomes.NAMES.entrySet()) {
                m.put(e.getKey(), this.biomeLookup.getOrThrow(ResourceKey.create(Registries.BIOME,
                        Identifier.fromNamespaceAndPath("terra", e.getValue()))));
            }
            biomeIdMap = m;
        }
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        requireBiomeIdMap();
        return biomeIdMap.values().stream();
    }

    /**
     * 26.3 asks biome sources for a resolver bound to a climate sampler. This source ignores the
     * climate sampler entirely and reads biome ids straight from the Terrain Diffusion tiles.
     */
    @Override
    public BiomeResolver createResolver(Climate.Sampler sampler) {
        return this::getNoiseBiome;
    }

    /**
     * Biome holders indexed by Terra's biome id, built once from {@link #biomeIdMap}. The game asks for a biome at every
     * 4x4x4 cell of every chunk (over 8000 per chunk in a 2096-block world), so the lookup is an array read in the
     * calling thread's current tile ({@link TerrainDiffusionDensityFunction.TileCache}), not a map lookup per call.
     */
    private volatile Holder<Biome>[] biomeById;

    @SuppressWarnings("unchecked")
    private Holder<Biome>[] biomeById() {
        Holder<Biome>[] a = biomeById;
        if (a == null) {
            requireBiomeIdMap();
            int max = 0;
            for (short id : biomeIdMap.keySet()) max = Math.max(max, id);
            a = (Holder<Biome>[]) new Holder[max + 1];
            for (Map.Entry<Short, Holder<Biome>> e : biomeIdMap.entrySet()) if (e.getKey() >= 0) a[e.getKey()] = e.getValue();
            biomeById = a;
        }
        return a;
    }

    private Holder<Biome> getNoiseBiome(int x, int y, int z) {
        Holder<Biome>[] byId = biomeById();
        // x, y, z are in quart coordinates (block / 4)
        int blockX = QuartPos.toBlock(x);
        int blockZ = QuartPos.toBlock(z);
        TerrainDiffusionDensityFunction.TileCache cache = TerrainDiffusionDensityFunction.TileCache.get();
        HeightmapData data = cache.tile(blockX, blockZ);
        if (data != null && data.biomeIds != null) {
            int localX = Math.max(0, Math.min(data.width - 1, blockX - cache.blockStartX));
            int localZ = Math.max(0, Math.min(data.height - 1, blockZ - cache.blockStartZ));
            short id = data.biomeIds[localZ][localX];
            if (id >= 0 && id < byId.length && byId[id] != null) return byId[id];
        }
        return byId[1];
    }

    // The Terrain Diffusion biome layout is not searchable through vanilla's climate search, so
    // /locate biome style lookups are disabled, as in the 1.21.x version.

    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius,
            Predicate<Holder<Biome>> predicate, RandomSource random, RandomState randomState) {
        return null;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findClosestBiome3d(BlockPos origin, int radius,
            int horizontalBlockCheckInterval, int verticalBlockCheckInterval,
            Predicate<Holder<Biome>> predicate, RandomState randomState, LevelReader world) {
        return null;
    }

    @Override
    public Pair<BlockPos, Holder<Biome>> findBiomeHorizontal(int x, int y, int z, int radius,
            int blockCheckInterval, Predicate<Holder<Biome>> predicate, RandomSource random,
            boolean bl, RandomState randomState) {
        return null;
    }
}

