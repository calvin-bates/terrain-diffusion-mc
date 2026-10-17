package com.github.xandergos.terraindiffusionmc.client;

import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/** The Terra world type ("world preset") as the create-world screen sees it. */
public final class TerraWorldPreset {
    public static final ResourceKey<WorldPreset> KEY =
            ResourceKey.create(Registries.WORLD_PRESET, Identifier.fromNamespaceAndPath("terra", "terrain_diffusion"));

    private TerraWorldPreset() {
    }

    /** True if the create-world screen currently has Terra selected as the world type. */
    public static boolean isSelected(WorldCreationUiState uiState) {
        if (uiState == null) return false;
        WorldCreationUiState.WorldTypeEntry entry = uiState.getWorldType();
        if (entry == null || entry.preset() == null) return false;
        return entry.preset().unwrapKey().map(KEY::equals).orElse(false);
    }
}
