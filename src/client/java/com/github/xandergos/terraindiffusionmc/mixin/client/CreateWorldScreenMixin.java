package com.github.xandergos.terraindiffusionmc.mixin.client;

import com.github.xandergos.terraindiffusionmc.client.TerraWorldPreset;
import com.github.xandergos.terraindiffusionmc.client.WorldScaleChoiceScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Terra is the default world type when a new world is created, and pressing "Create New World" with Terra selected
 * first asks for the world scale ({@link WorldScaleChoiceScreen}).
 */
@Mixin(CreateWorldScreen.class)
public abstract class CreateWorldScreenMixin {

    /** A fresh create-world screen starts on the Terra world type instead of vanilla's "Default". */
    @ModifyArg(
            method = "openFresh(Lnet/minecraft/client/Minecraft;Ljava/lang/Runnable;Lnet/minecraft/client/gui/screens/worldselection/CreateWorldCallback;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/worldselection/CreateWorldScreen;openCreateWorldScreen(Lnet/minecraft/client/Minecraft;Ljava/lang/Runnable;Ljava/util/function/Function;Lnet/minecraft/client/gui/screens/worldselection/WorldCreationContextMapper;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/gui/screens/worldselection/CreateWorldCallback;)V"),
            index = 4)
    private static ResourceKey<WorldPreset> terra$defaultToTerra(ResourceKey<WorldPreset> preset) {
        return TerraWorldPreset.KEY;
    }

    /** With Terra selected, "Create New World" opens the scale buttons first; picking one runs this again and goes ahead. */
    @Inject(method = "onCreate", at = @At("HEAD"), cancellable = true)
    private void terra$askForScale(CallbackInfo ci) {
        CreateWorldScreen self = (CreateWorldScreen) (Object) this;
        if (!TerraWorldPreset.isSelected(self.getUiState())) return;
        if (WorldScaleChoiceScreen.consumeChosen(self)) return;
        Minecraft.getInstance().gui.setScreen(new WorldScaleChoiceScreen(self));
        ci.cancel();
    }
}
