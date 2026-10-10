package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.pipeline.SpawnSelector;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.Level;
import net.minecraft.world.chunk.ChunkLoadProgress;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    @Inject(method = "setupSpawn", at = @At("HEAD"), cancellable = true)
    private static void overrideWorldSpawn(ServerLevel world, ServerLevelData worldProperties,
                                           boolean bonusChest, boolean debugWorld,
                                           ChunkLoadProgress loadProgress, CallbackInfo ci) {
        if (!world.getRegistryKey().equals(Level.OVERWORLD)) {
            return;
        }

        BlockPos spawnPos = SpawnSelector.findSpawnBlockPos();
        worldProperties.setSpawnPoint(
                LevelData.SpawnPoint.create(Level.OVERWORLD, spawnPos, 0f, 0f));
        ci.cancel();
    }
}
