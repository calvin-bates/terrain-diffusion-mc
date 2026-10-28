package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;

/**
 * Converts pipeline elevation (metres, ocean negative) to Minecraft heights.
 *
 * <p>Land is linear: one block per {@code 30 / scale} metres above sea level (Y 63), eased under the build limit
 * above Y {@value #SUMMIT_KNEE_Y} (see {@link #summit}).
 *
 * <p>The sea floor follows a shelf / slope / abyss profile instead of the old single square-root bowl:
 * <ul>
 *   <li><b>Continental shelf</b> (0 to {@value #SHELF_BREAK_M} m deep) is linear, so shallow seas keep their
 *       real shape. Its depth in blocks is {@code 200 m / block size}, clamped to
 *       [{@value #MIN_SHELF_BLOCKS}, {@value #MAX_SHELF_BLOCKS}]; at scale 6 the shelf break sits about 26 blocks
 *       down (Y 36), close to the land's own vertical scale near the shore.</li>
 *   <li><b>Slope, rise and abyss</b> (below the shelf break) are log-compressed, since a 1:1 mapping would need
 *       hundreds of blocks: a 1000 m sea is about Y 18, a 4000 m abyssal plain about Y −15, and the deepest
 *       trench (11 km) about Y −50.</li>
 *   <li>The floor never goes below Y {@value #MIN_FLOOR_Y}: open water under Y −54 would fill with lava, because
 *       the noise settings have no aquifers.</li>
 * </ul>
 * The ocean profile depends on scale only through the shelf depth.
 */
public class HeightConverter {
    private static final int SEA_LEVEL = 63;
    private static final short MAX_PIPELINE_METERS = 10_000;

    /** Depth of the shelf break in metres. Shallower water is mapped linearly. */
    public static final float SHELF_BREAK_M = 200f;
    private static final float MIN_SHELF_BLOCKS = 12f;
    private static final float MAX_SHELF_BLOCKS = 26f;
    /** Length scale (metres) of the log compression below the shelf break. */
    private static final float DEEP_SCALE_M = 1500f;
    /** Depth whose floor lands at {@link #TRENCH_BLOCKS} below sea level. */
    private static final float TRENCH_M = 11000f;
    private static final float TRENCH_BLOCKS = 112f;
    /** Lowest allowed sea floor height (the density value; the top solid block is one lower). */
    private static final int MIN_FLOOR_Y = -51;

    private static float getResolutionForScale(int configuredScale) {
        return WorldPipelineModelConfig.nativeResolution() / WorldScaleManager.clampScale(configuredScale);
    }

    public static int convertToMinecraftHeight(short meters) {
        return convertToMinecraftHeight(meters, WorldScaleManager.getCurrentScale());
    }

    public static int convertToMinecraftHeight(short meters, int configuredScale) {
        float resolution = getResolutionForScale(configuredScale);
        if (meters >= 0) {
            return summit(meters / resolution + SEA_LEVEL);
        }
        // Any ocean pixel lies at least one block below sea level, as before.
        int baseY = -(int) oceanDepthBlocks(-meters, resolution) - 1;
        return Math.max(MIN_FLOOR_Y, baseY + SEA_LEVEL);
    }

    /**
     * Summits under the build limit (Oct 27): land above Y {@link #SUMMIT_KNEE_Y} is eased towards
     * {@link #SUMMIT_CAP_Y} along a tanh curve (slope 1 at the knee, so nothing below changes, then flatter), so the
     * highest peaks taper to a point under the cap instead of being sheared flat at the build limit. At scale 6 a
     * 10 km peak lands about Y 1960 instead of a plateau at 2031. The cap leaves 32 blocks of headroom for snow, ice
     * and trees on top.
     */
    public static final int SUMMIT_KNEE_Y = 1600, SUMMIT_CAP_Y = 2000;

    private static int summit(float y) {
        if (y <= SUMMIT_KNEE_Y) return (int) y;
        float room = SUMMIT_CAP_Y - SUMMIT_KNEE_Y;
        return Math.min(SUMMIT_CAP_Y - 1, (int) (SUMMIT_KNEE_Y + room * (float) Math.tanh((y - SUMMIT_KNEE_Y) / room)));
    }

    /** Blocks of water above the sea floor (before the one-block offset) for a depth in metres. */
    static float oceanDepthBlocks(float depthM, float blockSizeM) {
        float shelf = Math.max(MIN_SHELF_BLOCKS, Math.min(MAX_SHELF_BLOCKS, SHELF_BREAK_M / blockSizeM));
        if (depthM <= SHELF_BREAK_M) {
            return shelf * depthM / SHELF_BREAK_M;
        }
        float k = (TRENCH_BLOCKS - shelf) / (float) Math.log1p((TRENCH_M - SHELF_BREAK_M) / DEEP_SCALE_M);
        return shelf + k * (float) Math.log1p((depthM - SHELF_BREAK_M) / DEEP_SCALE_M);
    }

    /**
     * Returns the highest generated block Y expected from pipeline output for a given scale.
     */
    public static int getMaxGeneratedYForScale(int configuredScale) {
        return convertToMinecraftHeight(MAX_PIPELINE_METERS, configuredScale);
    }
}
