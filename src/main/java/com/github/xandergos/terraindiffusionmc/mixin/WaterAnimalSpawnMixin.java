package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.InlandFish;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.fish.WaterAnimal;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cod and salmon outside oceans and rivers follow {@link InlandFish#canSpawn} (Terra's lakes, at any height). */
@Mixin(WaterAnimal.class)
public abstract class WaterAnimalSpawnMixin {

    @Inject(method = "checkSurfaceWaterAnimalSpawnRules", at = @At("HEAD"), cancellable = true)
    private static void terra$lakeFish(EntityType<? extends WaterAnimal> type, LevelAccessor level, EntitySpawnReason spawnReason,
                                       BlockPos pos, RandomSource random, CallbackInfoReturnable<Boolean> cir) {
        if (spawnReason != EntitySpawnReason.NATURAL && spawnReason != EntitySpawnReason.CHUNK_GENERATION) return;
        if (InlandFish.vanillaBiome(level.getBiome(pos))) return;
        try {
            cir.setReturnValue(InlandFish.canSpawn(type, level, pos));
        } catch (RuntimeException e) {
            cir.setReturnValue(false);   // never let this check break mob spawning
        }
    }
}
