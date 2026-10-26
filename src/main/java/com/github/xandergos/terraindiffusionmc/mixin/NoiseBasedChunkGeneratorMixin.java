package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.pipeline.TerraPerf;
import com.github.xandergos.terraindiffusionmc.world.DryCaves;
import com.github.xandergos.terraindiffusionmc.world.InlandWaterCarving;
import com.github.xandergos.terraindiffusionmc.world.OceanSurfaces;
import com.github.xandergos.terraindiffusionmc.world.VolcanicSurfaces;
import com.github.xandergos.terraindiffusionmc.world.Icebergs;
import com.github.xandergos.terraindiffusionmc.world.RiverWaterPlacer;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Fills river and lake water right after the terrain pass, while the chunk is still a plain
 * generation chunk (no block-update side effects, no waiting on neighbouring chunks), then lays the
 * reef, lagoon, bank and limestone surfaces ({@link OceanSurfaces}).
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
        int seaLevel = ((NoiseBasedChunkGenerator) (Object) this).getSeaLevel();
        cir.setReturnValue(cir.getReturnValue().thenApply(built -> {
            long t0 = TerraPerf.start();
            com.github.xandergos.terraindiffusionmc.world.CaveStone.paint(built);
            TerraPerf.end("chunk.caveStone", t0);
            t0 = TerraPerf.start();
            RiverWaterPlacer.place(built);
            TerraPerf.end("chunk.riverWater", t0);
            t0 = TerraPerf.start();
            OceanSurfaces.place(built, seaLevel);
            VolcanicSurfaces.place(built);
            com.github.xandergos.terraindiffusionmc.world.Geothermal.place(built);
            com.github.xandergos.terraindiffusionmc.world.Glaciers.place(built);
            TerraPerf.end("chunk.oceanSurfaces", t0);
            Icebergs.place(built, seaLevel);
            return built;
        }));
    }

    /** Start times of this thread's current terrain stages ({@link TerraPerf}): fill, surface, carvers. */
    private static final ThreadLocal<long[]> TERRA$STAGE_START = ThreadLocal.withInitial(() -> new long[3]);

    /** The chunk {@code doFill} is working on, for {@link #terra$dryNoiseCaves}. */
    private static final ThreadLocal<ChunkAccess> TERRA$FILL_CHUNK = new ThreadLocal<>();

    @Inject(method = "doFill", at = @At("HEAD"), require = 0)
    private void terra$fillStart(NoiseChunk noiseChunk, ChunkAccess chunk, CallbackInfo ci) {
        TERRA$STAGE_START.get()[0] = TerraPerf.start();
        TERRA$FILL_CHUNK.set(chunk);
    }

    /**
     * Terra's noise caves (caves plan v1.5) are made by the density, so the fill step decides between air and water for
     * them with the same disabled aquifer that floods carved caves below sea level. Keep caves under land dry here too,
     * as for the carvers ({@link DryCaves}); caves under and right beside the sea stay flooded.
     */
    @Redirect(method = "doFill",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;aquifer()Lnet/minecraft/world/level/levelgen/Aquifer;"),
            require = 0)
    private Aquifer terra$dryNoiseCaves(NoiseChunk noiseChunk) {
        Aquifer aquifer = noiseChunk.aquifer();
        ChunkAccess chunk = TERRA$FILL_CHUNK.get();
        TERRA$FILL_CHUNK.remove();
        if (chunk == null || !(((ChunkGenerator) (Object) this).getBiomeSource() instanceof TerrainDiffusionBiomeSource)) {
            return aquifer;
        }
        try {
            return DryCaves.wrap(chunk, aquifer, ((NoiseBasedChunkGenerator) (Object) this).getSeaLevel());
        } catch (RuntimeException e) {
            return aquifer;   // never let this break chunk generation
        }
    }

    @Inject(method = "doFill", at = @At("RETURN"), require = 0)
    private void terra$fillEnd(NoiseChunk noiseChunk, ChunkAccess chunk, CallbackInfo ci) {
        TerraPerf.end("chunk.fill (blocks from density)", TERRA$STAGE_START.get()[0]);
    }

    @Inject(method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Ljava/util/Set;Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;)V",
            at = @At("HEAD"), require = 0)
    private void terra$surfaceStart(ChunkAccess chunk, NoiseChunk noiseChunk, RandomState randomState, BiomeManager biomeManager,
                                    Set<Holder<Biome>> possibleBiomes, MaterialRule materialRule, CallbackInfo ci) {
        TERRA$STAGE_START.get()[1] = TerraPerf.start();
    }

    @Inject(method = "buildSurface(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/levelgen/NoiseChunk;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/biome/BiomeManager;Ljava/util/Set;Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;)V",
            at = @At("RETURN"), require = 0)
    private void terra$surfaceEnd(ChunkAccess chunk, NoiseChunk noiseChunk, RandomState randomState, BiomeManager biomeManager,
                                  Set<Holder<Biome>> possibleBiomes, MaterialRule materialRule, CallbackInfo ci) {
        TerraPerf.end("chunk.surface (surface rules)", TERRA$STAGE_START.get()[1]);
    }

    @Inject(method = "generateCarvers", at = @At("HEAD"), require = 0)
    private void terra$carversStart(ChunkAccess chunk, Blender blender, NoiseChunk noiseChunk, RandomState randomState,
                                    BiomeManager biomeManager, WorldGenRegion carverBiomeRegion, MaterialRule materialRule,
                                    CallbackInfo ci) {
        TERRA$STAGE_START.get()[2] = TerraPerf.start();
    }

    @Inject(method = "generateCarvers", at = @At("RETURN"), require = 0)
    private void terra$carversEnd(ChunkAccess chunk, Blender blender, NoiseChunk noiseChunk, RandomState randomState,
                                  BiomeManager biomeManager, WorldGenRegion carverBiomeRegion, MaterialRule materialRule,
                                  CallbackInfo ci) {
        TerraPerf.end("chunk.carvers", TERRA$STAGE_START.get()[2]);
    }

    /**
     * Caves and ravines are carved after the water is placed; keep them out of the ground under and right around inland
     * water (a ravine sliced a lake's shore into a deep slot). See {@link InlandWaterCarving}.
     */
    @ModifyArg(method = "generateCarvers",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseBasedChunkGenerator;applyCarvingMask(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/chunk/CarvingMask;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/levelgen/material/rule/MaterialRule;Lnet/minecraft/world/level/levelgen/WorldGenerationContext;Lnet/minecraft/world/level/levelgen/NoiseChunk;Ljava/util/function/Function;Lnet/minecraft/world/level/chunk/CarvingMask$Filter;)V"),
            index = 7, require = 0)
    private CarvingMask.Filter terra$noCarvingAtInlandWater(ChunkAccess chunk, CarvingMask mask, RandomState randomState,
                                                           MaterialRule materialRule, WorldGenerationContext context,
                                                           NoiseChunk noiseChunk, Function<BlockPos, Holder<Biome>> biomeGetter,
                                                           CarvingMask.Filter filter) {
        if (!(((ChunkGenerator) (Object) this).getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return filter;
        try {
            return InlandWaterCarving.wrap(chunk, filter);
        } catch (RuntimeException e) {
            return filter;   // never let this break chunk generation
        }
    }

    /** The chunk {@code applyCarvingMask} is working on, for {@link #terra$dryCaves}. */
    private static final ThreadLocal<ChunkAccess> TERRA$CARVING_CHUNK = new ThreadLocal<>();

    @Inject(method = "applyCarvingMask", at = @At("HEAD"), require = 0)
    private void terra$rememberCarvingChunk(ChunkAccess chunk, CarvingMask mask, RandomState randomState,
                                            MaterialRule materialRule, WorldGenerationContext context, NoiseChunk noiseChunk,
                                            Function<BlockPos, Holder<Biome>> biomeGetter, CarvingMask.Filter filter,
                                            CallbackInfo ci) {
        TERRA$CARVING_CHUNK.set(chunk);
    }

    /**
     * Terra has no aquifers, so vanilla fills everything carved below sea level with water, flooding every ravine and cave
     * entrance on low land. Carve those dry instead. See {@link DryCaves}.
     */
    @Redirect(method = "applyCarvingMask",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseChunk;aquifer()Lnet/minecraft/world/level/levelgen/Aquifer;"),
            require = 0)
    private Aquifer terra$dryCaves(NoiseChunk noiseChunk) {
        Aquifer aquifer = noiseChunk.aquifer();
        ChunkAccess chunk = TERRA$CARVING_CHUNK.get();
        TERRA$CARVING_CHUNK.remove();
        if (chunk == null || !(((ChunkGenerator) (Object) this).getBiomeSource() instanceof TerrainDiffusionBiomeSource)) {
            return aquifer;
        }
        try {
            return DryCaves.wrap(chunk, aquifer, ((NoiseBasedChunkGenerator) (Object) this).getSeaLevel());
        } catch (RuntimeException e) {
            return aquifer;   // never let this break chunk generation
        }
    }
}
