package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.world.level.levelgen.Beardifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** The rigid structure pieces (village houses, outposts) near the chunk being filled, for Terra's terrain pads. */
@Mixin(Beardifier.class)
public interface BeardifierAccessor {
    @Accessor("pieces")
    List<Beardifier.Rigid> terra$getPieces();
}
