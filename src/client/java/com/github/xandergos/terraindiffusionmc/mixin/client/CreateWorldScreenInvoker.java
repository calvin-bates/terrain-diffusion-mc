package com.github.xandergos.terraindiffusionmc.mixin.client;

import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets the scale screen run the create-world screen's own "create" step once a scale is picked. */
@Mixin(CreateWorldScreen.class)
public interface CreateWorldScreenInvoker {
    @Invoker("onCreate")
    void terra$invokeOnCreate();
}
