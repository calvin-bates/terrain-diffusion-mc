package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.RiverWaterPlacer;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Fills river and lake water right after the terrain pass, while the chunk is still a plain
 * generation chunk (no block-update side effects, no waiting on neighbouring chunks).
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {

    @Inject(method = "buildTerrain", at = @At("RETURN"), cancellable = true)
    private void terra$placeRiverWater(ChunkAccess chunk, Blender blender, RandomState randomState,
                                       StructureManager structureManager, BiomeManager biomeManager,
                                       WorldGenRegion region, Set<Holder<Biome>> biomes,
                                       CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!(((ChunkGenerator) (Object) this).getBiomeSource() instanceof TerrainDiffusionBiomeSource)) {
            return;
        }
        cir.setReturnValue(cir.getReturnValue().thenApply(built -> {
            RiverWaterPlacer.place(built);
            return built;
        }));
    }
}
