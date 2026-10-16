package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.InlandWater;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Structures pick their spot from the terrain before the mod's lakes and streams are filled in, so an igloo or a
 * village could start on what becomes open water. Any structure whose start lands on (or right beside) inland water
 * is skipped. Sea and ocean structures are unaffected: only the mod's own lakes and streams count.
 */
@Mixin(Structure.class)
public abstract class StructureMixin {

    @Inject(method = "findValidGenerationPoint", at = @At("RETURN"), cancellable = true, require = 0)
    private void terra$noStructuresOnInlandWater(Structure.GenerationContext context,
                                                 CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        Optional<Structure.GenerationStub> stub = cir.getReturnValue();
        if (stub == null || stub.isEmpty()) return;
        if (!(context.biomeSource() instanceof TerrainDiffusionBiomeSource)) return;
        try {
            BlockPos pos = stub.get().position();
            if (InlandWater.isWaterNear(pos.getX(), pos.getZ(), 4)) cir.setReturnValue(Optional.empty());
        } catch (RuntimeException e) {
            // Never let this check break structure generation.
        }
    }
}
