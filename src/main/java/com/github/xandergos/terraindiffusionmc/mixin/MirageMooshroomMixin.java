package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.animal.cow.MushroomCow;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mirage isles (biomes plan v1.4 round D) keep both kinds of mooshroom: one spawning there (naturally or with the
 * chunk) is brown half the time. Vanilla only makes brown ones by lightning or rare breeding.
 */
@Mixin(AgeableMob.class)
public abstract class MirageMooshroomMixin {
    private static final ResourceKey<Biome> TERRA$MIRAGE =
            ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("terra", "mirage_isles"));

    @Inject(method = "finalizeSpawn", at = @At("RETURN"), require = 0)
    private void terra$brownMooshrooms(ServerLevelAccessor level, DifficultyInstance difficulty, EntitySpawnReason reason,
                                       SpawnGroupData groupData, CallbackInfoReturnable<SpawnGroupData> cir) {
        if (!((Object) this instanceof MushroomCow cow)) return;
        if (reason != EntitySpawnReason.NATURAL && reason != EntitySpawnReason.CHUNK_GENERATION) return;
        if (!level.getBiome(cow.blockPosition()).is(TERRA$MIRAGE)) return;
        if (cow.getRandom().nextBoolean()) ((MushroomCowAccessor) cow).terra$setVariant(MushroomCow.Variant.BROWN);
    }
}
