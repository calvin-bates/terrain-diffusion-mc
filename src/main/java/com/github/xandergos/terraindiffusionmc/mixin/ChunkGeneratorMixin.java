package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.pipeline.TerraPerf;
import com.github.xandergos.terraindiffusionmc.world.HydrothermalVents;
import com.github.xandergos.terraindiffusionmc.world.OceanFlora;
import com.github.xandergos.terraindiffusionmc.world.SeaIce;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * After features are placed in a Terra chunk, clear kelp and seagrass from deep sea water ({@link OceanFlora}) and
 * build hydrothermal vents ({@link HydrothermalVents}).
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    /** When this thread started decorating its current chunk ({@link TerraPerf}). */
    private static final ThreadLocal<long[]> TERRA$DECORATE_START = ThreadLocal.withInitial(() -> new long[1]);

    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"))
    private void terra$timeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager,
                                      CallbackInfo ci) {
        TERRA$DECORATE_START.get()[0] = TerraPerf.start();
    }

    @Inject(method = "applyBiomeDecoration", at = @At("RETURN"))
    private void terra$trimDeepPlants(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager,
                                     CallbackInfo ci) {
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        if (!(self.getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return;
        TerraPerf.end("chunk.features (vanilla and Terra features, trees)", TERRA$DECORATE_START.get()[0]);
        long t0 = TerraPerf.start();
        try {
            OceanFlora.trimDeepPlants(chunk, self.getSeaLevel());
        } catch (RuntimeException e) {
            // never let this break chunk generation
        }
        try {
            SeaIce.place(chunk, self.getSeaLevel());
        } catch (RuntimeException e) {
            // never let this break chunk generation
        }
        try {
            HydrothermalVents.place(level, chunk, self.getSeaLevel());
        } catch (RuntimeException e) {
            // never let this break chunk generation
        }
        TerraPerf.end("chunk.afterFeatures (deep plants, sea ice, vents)", t0);
    }
}
