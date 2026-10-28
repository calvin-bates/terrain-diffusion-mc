package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.CaveBiomes;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;

import java.util.function.Consumer;

/**
 * {@code orogeny:cave_layer} (caves plan v1.5): a height range for features of Terra's cave biomes. Vanilla cave
 * features pick a Y between the bottom of the world and Y 256; Terra's caves go as high as its mountains, so this
 * picks a Y in the column's cave layer instead, from the bottom of the world up to where cave biomes can start (the
 * cave reference ground minus {@link CaveBiomes#START_DEPTH}). A column with a taller cave layer gets proportionally
 * more tries ({@code per_256} per 256 blocks, rounded at random), so features keep the same density per block as
 * vanilla's.
 *
 * <pre>{"type": "orogeny:cave_layer", "per_256": 1.0}</pre>
 */
public record CaveLayerPlacement(float per256) implements PlacementModifier {
    public static final MapCodec<CaveLayerPlacement> CODEC = Codec.floatRange(0f, 64f).optionalFieldOf("per_256", 1f)
            .xmap(CaveLayerPlacement::new, CaveLayerPlacement::per256);

    @Override
    public void modify(PlacementContext context, RandomSource random, BlockPos origin, Consumer<BlockPos> output) {
        int minY = context.getMinY();
        int ref = TerrainDiffusionDensityFunction.TileCache.get().caveRefTop(origin.getX(), origin.getZ());
        if (ref == Integer.MIN_VALUE) return;
        int top = ref - CaveBiomes.START_DEPTH + 4;   // a little margin: biome cells are 4 blocks tall
        if (top <= minY) return;
        float tries = per256 * (top - minY + 1) / 256f;
        int n = (int) tries;
        if (random.nextFloat() < tries - n) n++;
        for (int k = 0; k < n; k++) output.accept(origin.atY(minY + random.nextInt(top - minY + 1)));
    }

    /** Registers {@code orogeny:cave_layer}; called once from the mod initializer, before data packs load. */
    public static void register() {
        net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.PLACEMENT_MODIFIER_TYPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("orogeny", "cave_layer"), CODEC);
    }

    @Override
    public MapCodec<CaveLayerPlacement> codec() {
        return CODEC;
    }
}
