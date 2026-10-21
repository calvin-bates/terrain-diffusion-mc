package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The rooms of an ocean monument, which live inside the main building piece rather than as pieces of their own. */
@Mixin(OceanMonumentPieces.MonumentBuilding.class)
public interface MonumentBuildingAccessor {
    @Accessor("childPieces")
    List<? extends StructurePiece> terra$getChildPieces();
}
