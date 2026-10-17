package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.mixin.client.CreateWorldScreenInvoker;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleSelectionState;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

import java.util.HashMap;
import java.util.Map;

/**
 * Shown when "Create New World" is pressed with the Terra world type selected: six buttons, one per world scale.
 * Picking one applies that scale and goes on to create the world; Back returns to the create-world screen.
 */
public final class WorldScaleChoiceScreen extends Screen {
    private static final int SCALE_BUTTON_WIDTH = 40;
    private static final int SCALE_BUTTON_GAP = 6;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BACK_BUTTON_WIDTH = 100;

    /** The create-world screen whose next "create" press already has its scale chosen (set just before re-running it). */
    private static CreateWorldScreen chosenFor;

    private final CreateWorldScreen parent;

    public WorldScaleChoiceScreen(CreateWorldScreen parent) {
        super(Component.translatable("terra.scale_choice.title"));
        this.parent = parent;
    }

    /** True (once) if the scale was just chosen for this create-world screen, so creating the world can go ahead. */
    public static boolean consumeChosen(CreateWorldScreen screen) {
        if (chosenFor != screen) return false;
        chosenFor = null;
        return true;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        addCentered(this.title, centerX, centerY - 62, 0xFFFFFF);
        addCentered(Component.translatable("terra.scale_choice.line1"), centerX, centerY - 44, 0xAAAAAA);
        addCentered(Component.translatable("terra.scale_choice.line2"), centerX, centerY - 32, 0xAAAAAA);

        int count = WorldScaleManager.MAX_SCALE;
        int rowWidth = count * SCALE_BUTTON_WIDTH + (count - 1) * SCALE_BUTTON_GAP;
        int left = centerX - rowWidth / 2;
        int rowY = centerY - 12;
        for (int scale = 1; scale <= count; scale++) {
            final int chosen = scale;
            int x = left + (scale - 1) * (SCALE_BUTTON_WIDTH + SCALE_BUTTON_GAP);
            String metres = metresPerBlock(scale);
            Component tip = scale == WorldScaleManager.DEFAULT_SCALE
                    ? Component.translatable("terra.scale_choice.tooltip_default", scale, metres)
                    : Component.translatable("terra.scale_choice.tooltip", scale, metres);
            this.addRenderableWidget(Button.builder(Component.literal(String.valueOf(scale)), b -> choose(chosen))
                    .bounds(x, rowY, SCALE_BUTTON_WIDTH, BUTTON_HEIGHT)
                    .tooltip(Tooltip.create(tip))
                    .build());
            addCentered(Component.literal(metres + " m"), x + SCALE_BUTTON_WIDTH / 2, rowY + BUTTON_HEIGHT + 4, 0xAAAAAA);
        }
        addCentered(Component.translatable("terra.scale_choice.per_block"), centerX, rowY + BUTTON_HEIGHT + 16, 0x808080);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
                .bounds(centerX - BACK_BUTTON_WIDTH / 2, rowY + BUTTON_HEIGHT + 36, BACK_BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
    }

    /** Real-world metres covered by one block at this scale (a native pixel is 30 m and spans {@code scale} blocks). */
    private static String metresPerBlock(int scale) {
        float m = 30f / scale;
        return m == Math.round(m) ? String.valueOf(Math.round(m)) : String.valueOf(m);
    }

    private void addCentered(Component text, int centerX, int y, int color) {
        int textWidth = this.font.width(text);
        StringWidget widget = new StringWidget(centerX - textWidth / 2, y, textWidth, 9, text.copy().withColor(color), this.font);
        this.addRenderableWidget(widget);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    /** Applies the chosen scale and runs the create-world screen's own "create" step again, which now goes ahead. */
    private void choose(int scale) {
        applyDimensionTypeForScale(scale);
        WorldScaleSelectionState.setPendingScale(scale);
        chosenFor = parent;
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
        ((CreateWorldScreenInvoker) parent).terra$invokeOnCreate();
    }

    /** Puts the pre-registered dimension type for this scale on the overworld. */
    private void applyDimensionTypeForScale(int scale) {
        parent.getUiState().updateDimensions((registryAccess, selectedDimensions) -> {
            WorldDimensions updated = withOverworldDimensionType(
                    registryAccess.lookupOrThrow(Registries.DIMENSION_TYPE), selectedDimensions, scale);
            return updated == null ? selectedDimensions : updated;
        });
    }

    private static WorldDimensions withOverworldDimensionType(Registry<DimensionType> dimensionTypes,
                                                              WorldDimensions selectedDimensions, int scale) {
        LevelStem overworld = selectedDimensions.get(LevelStem.OVERWORLD).orElse(null);
        if (overworld == null) return null;
        Identifier id = Identifier.fromNamespaceAndPath("terra", "terrain_diffusion_scale_" + scale);
        Holder.Reference<DimensionType> type = dimensionTypes.get(id).orElse(null);
        if (type == null) return null;
        Map<ResourceKey<LevelStem>, LevelStem> dimensions = new HashMap<>(selectedDimensions.dimensions());
        dimensions.put(LevelStem.OVERWORLD, new LevelStem(type, overworld.generator()));
        return new WorldDimensions(dimensions);
    }
}
