package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An ocean monument's rooms (entry, core, wings, penthouse and the rest) are kept inside the main building piece, not
 * as structure pieces of their own, so moving the building moved only its outer shell: when Terra lowered monuments
 * onto its deep sea floor ({@link OceanMonumentMixin}), the shell went down and every room stayed at vanilla height
 * (Calvin, Oct 20). Moving the building now moves its rooms with it. This covers the lowering and the rebuild after a
 * reload (rooms are not saved; they are rebuilt at vanilla height and then moved like the building).
 */
@Mixin(StructurePiece.class)
public abstract class StructurePieceMixin {

    @Inject(method = "move", at = @At("TAIL"), require = 0)
    private void terra$moveMonumentRooms(int dx, int dy, int dz, CallbackInfo ci) {
        if (!((Object) this instanceof OceanMonumentPieces.MonumentBuilding building)) return;
        try {
            for (StructurePiece room : ((MonumentBuildingAccessor) building).terra$getChildPieces()) {
                room.getBoundingBox().move(dx, dy, dz);
            }
        } catch (RuntimeException e) {
            // Never let this break structure generation.
        }
    }
}
