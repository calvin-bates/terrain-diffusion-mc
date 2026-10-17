package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.AxolotlLakes;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biomes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Natural axolotl spawns outside lush caves follow {@link AxolotlLakes#canSpawn}: rare chunks of high (alpine) lakes in
 * Terra worlds. Lush caves, spawners and every other spawn reason keep vanilla's rule.
 */
@Mixin(Axolotl.class)
public abstract class AxolotlMixin {

    @Inject(method = "checkAxolotlSpawnRules", at = @At("HEAD"), cancellable = true)
    private static void terra$alpineLakes(EntityType<? extends LivingEntity> type, ServerLevelAccessor level,
                                          EntitySpawnReason spawnReason, BlockPos pos, RandomSource random,
                                          CallbackInfoReturnable<Boolean> cir) {
        if (spawnReason != EntitySpawnReason.NATURAL && spawnReason != EntitySpawnReason.CHUNK_GENERATION) return;
        if (level.getBiome(pos).is(Biomes.LUSH_CAVES)) return;
        try {
            cir.setReturnValue(AxolotlLakes.canSpawn(level, pos));
        } catch (RuntimeException e) {
            cir.setReturnValue(false);   // never let this check break mob spawning
        }
    }
}
