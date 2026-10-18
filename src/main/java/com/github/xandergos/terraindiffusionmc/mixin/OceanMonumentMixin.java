package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla builds every ocean monument at a fixed height (base Y 39, top Y 61) and fills its legs down to the sea
 * floor, which suits vanilla's deep oceans (floor around Y 30-40). Terra's deep sea is much deeper, so monuments
 * stood on tall stilts. In Terra worlds the monument is lowered so its base sits on the sea floor at the chunk
 * centre; it is never raised above the vanilla height.
 */
@Mixin(OceanMonumentStructure.class)
public abstract class OceanMonumentMixin {

    /** The vanilla monument's lowest Y. */
    private static final int TERRA$VANILLA_BASE_Y = 39;

    @Inject(method = "generatePieces", at = @At("TAIL"), require = 0)
    private static void terra$sitOnSeaFloor(StructurePiecesBuilder builder, Structure.GenerationContext context, CallbackInfo ci) {
        if (!(context.biomeSource() instanceof TerrainDiffusionBiomeSource)) return;
        try {
            ChunkPos cp = context.chunkPos();
            int floor = context.chunkGenerator().getFirstOccupiedHeight(cp.getMiddleBlockX(), cp.getMiddleBlockZ(),
                    Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
            int minAllowed = context.heightAccessor().getMinY() + 8;
            int base = Math.max(minAllowed, floor);
            int dy = base - TERRA$VANILLA_BASE_Y;
            if (dy < 0) builder.offsetPiecesVertically(dy);
        } catch (RuntimeException e) {
            // Never let this break structure generation: the monument keeps its vanilla height.
        }
    }

    /**
     * Saved monuments are rebuilt from their bounding box after a reload, at the vanilla height; move the rebuilt
     * piece back to where the saved one was, or chunks generated after a reload would get a second, higher half.
     */
    @Inject(method = "regeneratePiecesAfterLoad", at = @At("RETURN"), require = 0)
    private static void terra$keepSavedHeight(ChunkPos chunkPos, long seed, PiecesContainer savedPieces,
                                              CallbackInfoReturnable<PiecesContainer> cir) {
        try {
            PiecesContainer rebuilt = cir.getReturnValue();
            if (rebuilt == null || rebuilt == savedPieces || rebuilt.isEmpty() || savedPieces.isEmpty()) return;
            int dy = savedPieces.pieces().get(0).getBoundingBox().minY() - rebuilt.pieces().get(0).getBoundingBox().minY();
            if (dy == 0) return;
            for (StructurePiece piece : rebuilt.pieces()) piece.move(0, dy, 0);
        } catch (RuntimeException e) {
            // Leave the rebuilt monument as vanilla made it.
        }
    }
}
