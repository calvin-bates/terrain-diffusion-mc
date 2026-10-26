package com.github.xandergos.terraindiffusionmc.mixin;

import net.minecraft.world.entity.animal.cow.MushroomCow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Sets a mooshroom's variant (private in 26.3), for brown mooshrooms on the mirage isles. */
@Mixin(MushroomCow.class)
public interface MushroomCowAccessor {
    @Invoker("setVariant")
    void terra$setVariant(MushroomCow.Variant variant);
}
