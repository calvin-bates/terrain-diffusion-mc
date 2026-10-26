package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * No lakes (lava or water) dug into ice: vanilla's surface lava lakes landed on Terra's ice islands and bergs and on
 * glaciers, where they looked wrong (Calvin, Oct 26). A lake is skipped when any of a few spots of its 16x16 footprint,
 * from 1 to 4 blocks under where it would sit, is berg or glacier ice or snow block.
 */
@Mixin(LakeFeature.class)
public abstract class LakeFeatureMixin {

    @Inject(method = "place", at = @At("HEAD"), cancellable = true, require = 0)
    private void terra$noLakesOnIce(WorldGenLevel level, ChunkGenerator chunkGenerator, RandomSource random, BlockPos origin,
                                    CallbackInfoReturnable<Boolean> cir) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -6; dx <= 6; dx += 6) {
            for (int dz = -6; dz <= 6; dz += 6) {
                for (int dy = 1; dy <= 4; dy++) {
                    BlockState st = level.getBlockState(pos.set(origin.getX() + dx, origin.getY() - dy, origin.getZ() + dz));
                    if (st.is(Blocks.PACKED_ICE) || st.is(Blocks.BLUE_ICE) || st.is(Blocks.ICE) || st.is(Blocks.SNOW_BLOCK)) {
                        cir.setReturnValue(false);
                        return;
                    }
                }
            }
        }
    }
}
