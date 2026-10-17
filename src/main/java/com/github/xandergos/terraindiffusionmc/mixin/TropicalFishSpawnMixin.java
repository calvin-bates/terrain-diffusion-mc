package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.InlandFish;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.fish.TropicalFish;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tropical fish outside oceans and rivers follow {@link InlandFish#canSpawn} (lakes in warm biomes, at any height). */
@Mixin(TropicalFish.class)
public abstract class TropicalFishSpawnMixin {

    @Inject(method = "checkTropicalFishSpawnRules", at = @At("HEAD"), cancellable = true)
    private static void terra$lakeFish(EntityType<TropicalFish> type, LevelAccessor level, EntitySpawnReason spawnReason,
                                       BlockPos pos, RandomSource random, CallbackInfoReturnable<Boolean> cir) {
        if (spawnReason != EntitySpawnReason.NATURAL && spawnReason != EntitySpawnReason.CHUNK_GENERATION) return;
        if (InlandFish.vanillaBiome(level.getBiome(pos))) return;
        try {
            cir.setReturnValue(InlandFish.canSpawn(type, level, pos));
        } catch (RuntimeException e) {
            cir.setReturnValue(false);
        }
    }
}
