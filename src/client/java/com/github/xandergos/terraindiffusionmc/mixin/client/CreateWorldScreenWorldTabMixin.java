package com.github.xandergos.terraindiffusionmc.mixin.client;

import com.github.xandergos.terraindiffusionmc.client.WorldScaleSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reuses vanilla's World tab "Customize" button for Terrain Diffusion worlds.
 *
 * <p>26.3: the tab class is {@code CreateWorldScreen$WorldTab}; its outer-instance field is the
 * synthetic {@code this$0}, the button is {@code customizeTypeButton} and the click handler is
 * {@code openPresetEditor()}. The button's enabled state is refreshed from a lambda inside the
 * constructor, so instead of injecting into anonymous lambdas we re-enable the button from
 * {@code openPresetEditor} callers' state each time the tab is ticked via the screen's UI state
 * listener — see {@link #terrainDiffusionMc$keepButtonActive()}.
 */
@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public abstract class CreateWorldScreenWorldTabMixin {
    @Shadow
    @Final
    CreateWorldScreen this$0;

    @Shadow
    @Final
    private Button customizeTypeButton;

    private static final ResourceKey<WorldPreset> TERRAIN_DIFFUSION_PRESET_KEY =
            ResourceKey.create(Registries.WORLD_PRESET,
                    Identifier.fromNamespaceAndPath("terra", "terrain_diffusion"));

    /** Re-enable the customize button after the tab's constructor finished wiring its listeners. */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void terrainDiffusionMc$afterInit(CreateWorldScreen screen, CallbackInfo ci) {
        terrainDiffusionMc$keepButtonActive();
        // Keep it enabled whenever the UI state changes (e.g. world type cycled).
        screen.getUiState().addListener(state -> terrainDiffusionMc$keepButtonActive());
    }

    private void terrainDiffusionMc$keepButtonActive() {
        if (customizeTypeButton != null && isTerrainDiffusionWorldTypeSelected()) {
            customizeTypeButton.visible = true;
            customizeTypeButton.active = true;
        }
    }

    @Inject(method = "openPresetEditor", at = @At("HEAD"), cancellable = true)
    private void terrainDiffusionMc$openTerrainScaleScreen(CallbackInfo callbackInfo) {
        if (!isTerrainDiffusionWorldTypeSelected()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.gui.setScreen(new WorldScaleSettingsScreen(this$0));
            callbackInfo.cancel();
        }
    }

    private boolean isTerrainDiffusionWorldTypeSelected() {
        WorldCreationUiState uiState = this$0.getUiState();
        if (uiState == null) {
            return false;
        }
        WorldCreationUiState.WorldTypeEntry entry = uiState.getWorldType();
        if (entry == null || entry.preset() == null) {
            return false;
        }
        return entry.preset().unwrapKey().map(TERRAIN_DIFFUSION_PRESET_KEY::equals).orElse(false);
    }
}
