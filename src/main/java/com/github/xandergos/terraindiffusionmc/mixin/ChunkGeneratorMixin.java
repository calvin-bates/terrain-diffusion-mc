package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.OceanFlora;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After features are placed in a Terra chunk, clear kelp and seagrass from deep sea water. See {@link OceanFlora}. */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @Inject(method = "applyBiomeDecoration", at = @At("RETURN"))
    private void terra$trimDeepPlants(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager,
                                     CallbackInfo ci) {
        ChunkGenerator self = (ChunkGenerator) (Object) this;
        if (!(self.getBiomeSource() instanceof TerrainDiffusionBiomeSource)) return;
        try {
            OceanFlora.trimDeepPlants(chunk, self.getSeaLevel());
        } catch (RuntimeException e) {
            // never let this break chunk generation
        }
    }
}
