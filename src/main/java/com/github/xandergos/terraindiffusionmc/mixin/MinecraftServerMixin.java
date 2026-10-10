package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.pipeline.SpawnSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    /**
     * In 26.3 the private static spawn setup method is {@code setInitialSpawn}, takes a
     * {@link LevelLoadListener} instead of the old chunk-load progress type, and the spawn point is
     * stored as {@link LevelData.RespawnData} on the level.
     */
    @Inject(method = "setInitialSpawn", at = @At("HEAD"), cancellable = true)
    private static void overrideWorldSpawn(ServerLevel world, ServerLevelData worldProperties,
                                           boolean bonusChest, boolean debugWorld,
                                           LevelLoadListener loadListener, CallbackInfo ci) {
        if (!world.dimension().equals(Level.OVERWORLD)) {
            return;
        }

        BlockPos spawnPos = SpawnSelector.findSpawnBlockPos();
        world.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD, spawnPos, 0f, 0f));
        ci.cancel();
    }
}
