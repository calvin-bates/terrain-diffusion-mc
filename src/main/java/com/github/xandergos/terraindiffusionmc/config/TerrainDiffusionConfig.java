package com.github.xandergos.terraindiffusionmc.config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class TerrainDiffusionConfig {
    private static final String FILE_NAME = "terra.properties";
    private static final String RESOURCE_PATH = "/" + FILE_NAME;
    private static final Properties PROPERTIES = new Properties();
    private static final String BUILD_VARIANT = readBuildVariant();
    private static final boolean DEFAULT_OFFLOAD_MODELS = true;
    private static final boolean DEFAULT_VALIDATE_MODEL = true;
    private static final int DEFAULT_EXPLORER_PORT = 19801;
    private static final int DEFAULT_TILE_SIZE = 256;

    static {
        loadDefaults();
        Path configPath = resolveConfigPath();
        if (configPath != null) {
            loadOverrides(configPath);
        }
    }

    private TerrainDiffusionConfig() {
    }

    /** Overrides a setting in this JVM only (tools outside the game, such as TerraPreview). */
    public static void setForTools(String key, String value) {
        PROPERTIES.setProperty(key, value);
    }

    /** Inference device: "cpu", "gpu", or "auto" (try GPU then fall back to CPU). */
    public static String inferenceDevice() {
        String device = readString("inference.device", "gpu");
        // On the CPU build "gpu" is meaningless (no dedicated GPU provider), so treat it as "auto":
        // tries CoreML on macOS, falls back to CPU elsewhere.
        if ("cpu".equals(BUILD_VARIANT)) {
            return "auto";
        }
        return device;
    }

    /** Whether to offload inactive models from VRAM between pipeline stages. */
    public static boolean offloadModels() {
        return readBoolean("inference.offload_models", DEFAULT_OFFLOAD_MODELS);
    }

    /** TCP port for the local terrain explorer HTTP server. */
    public static int explorerPort() {
        return readInt("explorer.port", DEFAULT_EXPLORER_PORT);
    }

    /** Whether to validate SHA-256 for pre-existing local model files before use. */
    public static boolean validateModel() {
        return readBoolean("validate_model", DEFAULT_VALIDATE_MODEL);
    }

    /** Initial coarse-pixel radius for spawn land search (NxN region centered at origin). */
    public static int spawnSearchInitialSize() {
        return readInt("spawn_search.initial_size", 16);
    }

    /** Maximum coarse-pixel region size for spawn land search before giving up. */
    public static int spawnSearchMaxSize() {
        return readInt("spawn_search.max_size", 128);
    }

    /** Region side length in blocks. Must be a positive power of 2 (128, 256, 512, ...). */
    public static int tileSize() {
        int configuredTileSize = readInt("tile_size", DEFAULT_TILE_SIZE);
        if (configuredTileSize <= 0 || !isPowerOfTwo(configuredTileSize)) {
            System.err.println("Invalid tile_size: " + configuredTileSize + ", using default " + DEFAULT_TILE_SIZE);
            return DEFAULT_TILE_SIZE;
        }
        return configuredTileSize;
    }

    /** Whether rivers and lakes are generated. Only affects newly generated chunks. */
    public static boolean riversEnabled() {
        return readBoolean("rivers.enabled", true);
    }

    /** Whether river and lake water is placed (terrain carving still happens when false). For debugging. */
    public static boolean riversPlaceWater() {
        return readBoolean("rivers.place_water", true);
    }

    /** Side length of a hydrology region in native pixels (30 m each). */
    public static int riverRegionSize() {
        return Math.max(128, readInt("rivers.region_size", 256));
    }

    /** Extra native pixels computed around each region so rivers match across region borders. */
    public static int riverMargin() {
        return readInt("rivers.margin", 96);
    }

    public static float riverStreamThreshold() { return readFloat("rivers.stream_threshold", 2400f); }
    public static float riverRiverThreshold() { return readFloat("rivers.river_threshold", 9000f); }
    public static float riverMainThreshold() { return readFloat("rivers.main_threshold", 25000f); }
    /** Water freezes only above this height (metres of terrain elevation) and inside snowy biomes; 0 = biome only. */
    public static float riverFreezeHeightMetres() { return readFloat("rivers.freeze_height_m", 3500f); }
    /** Rivers and lakes freezing at all (above rivers.freeze_height_m). Off by default. */
    public static boolean riverFreezeEnabled() { return readBoolean("rivers.freeze", false); }
    /** No rivers or lakes above this terrain height in metres. */
    public static float riverMaxHeightMetres() { return readFloat("rivers.max_water_m", 4000f); }
    public static int lakeMinArea() { return readInt("rivers.lake_min_area", 150); }
    public static float lakeMinDepth() { return readFloat("rivers.lake_min_depth", 2f); }

    /** Tectonic sea-floor features and oceanic islands (ridges, trenches, island arcs, hotspot chains, atolls). */
    public static boolean oceanFeatures() { return readBoolean("oceans.features", true); }
    /**
     * Whether the diffusion model draws the big oceanic islands (hotspot, island-arc and forearc islands) itself,
     * guided to where they belong, instead of procedural cones being stamped onto its terrain afterwards.
     */
    public static boolean modelIslands() { return readBoolean("oceans.model_islands", true); }
    /**
     * Whether the diffusion model draws the islands of archipelago seas itself (the drowning is applied to its own
     * low-frequency terrain inside the latent stages, and it redraws the detail), instead of the finished terrain being
     * flattened and drowned afterwards.
     */
    public static boolean modelArchipelagos() { return readBoolean("oceans.model_archipelagos", true); }
    /**
     * How model-drawn archipelagos are made (being tuned): "step" (the drowned terrain goes into the refinement step,
     * the model's detail kept), "keep" (an extra pass from sigma 1 with the detail kept), "clear" (that pass with the
     * detail cleared where the ground moved).
     */
    public static String archipelagoMode() { return readString("oceans.archipelago_mode", "keep"); }
    /** Share of the way the refinement's output is pulled back to the drowned archipelago terrain (being tuned). */
    public static float archipelagoFinalPull() {
        return Math.max(0f, Math.min(1f, readFloat("oceans.archipelago_final_pull", 1f)));
    }
    /**
     * Scale of the fine coastline roughness added to model-drawn archipelagos (1 = as much as the old pasted-in pass).
     * The model draws its own coast detail; at 1 the roughness broke it into slivers and single-pixel islets (preview,
     * Oct 21: lone pixels 18/51 at 1, 7/15 at 0.4, 3/3 at 0, against 4/8 from the model alone); 0.4 keeps some coves.
     */
    public static float archipelagoDetail() {
        return Math.max(0f, Math.min(2f, readFloat("oceans.archipelago_detail", 0.4f)));
    }
    /**
     * Model-drawn archipelagos: how far (latent units; the channel's spread is ~1.2) latent channel 0, which the decoder
     * reads as local height (Oct 21 probe: residual ~ -0.5..-1.4 sqrt-m per unit, correlation -0.6..-0.8), is pushed
     * with the fine island field near sea level, before the refinement ({@code _in}) and after it ({@code _out}), so
     * the islands below ~3 km follow the drowned hills (the low-frequency channel only carries ~5 km and up). Off: at 3/2
     * it about doubled the islands but they came out smooth and textureless, and on the island pass it washed out;
     * Calvin chose the model's own islands without a nudge (Oct 21).
     */
    public static float archipelagoSteerIn() { return readFloat("oceans.archipelago_steer_in", 0f); }
    public static float archipelagoSteerOut() { return readFloat("oceans.archipelago_steer_out", 0f); }
    /** The same push on the island pass's input (from sigma 1, so the model can redraw the texture around it). */
    public static float archipelagoSteerIsland() { return readFloat("oceans.archipelago_steer_island", 0f); }
    /** Young rift seas (Red Sea style) laid into the elevation conditioning (oceans round 5). */
    public static boolean riftSeas() { return readBoolean("oceans.rift_seas", true); }
    /**
     * Coastal currents (biomes plan v1.2): west-facing coasts (facing -X) get cold upwelling where the sea-level
     * temperature is subtropical (cooler, much drier, coastal deserts) and a warm current where it is cold (milder,
     * wetter, open sea instead of sea ice). See {@code pipeline.CoastalCurrents}.
     */
    /**
     * Ground pads under village houses and outposts: the terrain is pulled to each building's floor level so nothing
     * floats or sinks into hills (see {@code world.StructurePads}).
     */
    public static boolean structurePads() { return readBoolean("structures.terrain_pads", true); }
    /** Size multiplier for the patch biomes (meadow, dark forest, tundra, boreal forest ...); 1 = the original sizes. */
    public static float biomeSize() { return Math.max(0.25f, Math.min(10f, readFloat("biomes.size", 2.5f))); }
    public static boolean coastalCurrents() { return readBoolean("climate.coastal_currents", true); }
    /** Active continental margins (coastal range, narrow shelf, trench) laid into the elevation conditioning. */
    public static boolean activeMargins() { return readBoolean("oceans.active_margins", true); }
    /**
     * How strongly the continent conditioning is pushed toward archipelagos: shallow seas scattered with islands.
     * 0 turns it off (the model's own coastlines only); 1 is the default; up to 2 for island-heavy worlds.
     */
    public static float archipelagoStrength() {
        return Math.max(0f, Math.min(2f, readFloat("oceans.archipelago_strength", 1f)));
    }

    /**
     * Fraction of shipwrecks, ocean ruins and ocean monuments kept in Terra worlds (0..1). Terra's oceans are far
     * bigger than vanilla's, so vanilla spacing fills them with wrecks; the default keeps about a third.
     */
    public static float oceanStructureFrequency() {
        return Math.max(0f, Math.min(1f, readFloat("oceans.structure_frequency", 0.35f)));
    }

    private static void loadDefaults() {
        boolean loadedFromResource = false;
        try (InputStream in = TerrainDiffusionConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in != null) {
                PROPERTIES.load(in);
                loadedFromResource = true;
            }
        } catch (IOException e) {
            System.err.println("Failed to load default config from resource: " + e.getMessage());
        }

        if (!loadedFromResource) {
            PROPERTIES.setProperty("inference.device", "gpu");
            PROPERTIES.setProperty("validate_model", String.valueOf(DEFAULT_VALIDATE_MODEL));
            PROPERTIES.setProperty("tile_size", String.valueOf(DEFAULT_TILE_SIZE));
        }
    }

    private static String readString(String key, String defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value != null ? value.trim().toLowerCase() : defaultValue;
    }

    private static Path resolveConfigPath() {
        try {
            return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        } catch (RuntimeException e) {
            System.err.println("Fabric Loader config directory unavailable: " + e.getMessage());
            return null;
        }
    }

    private static void loadOverrides(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            if (Files.exists(configPath)) {
                try (InputStream in = Files.newInputStream(configPath)) {
                    Properties overrides = new Properties();
                    overrides.load(in);
                    PROPERTIES.putAll(overrides);
                }
            } else {
                writeConfig(configPath);
            }
        } catch (IOException e) {
            System.err.println("Failed to read config file: " + e.getMessage());
        }
    }

    private static void writeConfig(Path configPath) {
        try (InputStream defaultConfigInputStream = TerrainDiffusionConfig.class.getResourceAsStream(RESOURCE_PATH)) {
            if (defaultConfigInputStream != null) {
                Files.copy(defaultConfigInputStream, configPath);
                return;
            }
            System.err.println("Default config resource not found: " + RESOURCE_PATH);
        } catch (IOException e) {
            System.err.println("Failed to copy default config resource: " + e.getMessage());
        }
    }

    private static boolean readBoolean(String key, boolean defaultValue) {
        String value = PROPERTIES.getProperty(key);
        return value != null ? Boolean.parseBoolean(value.trim()) : defaultValue;
    }

    private static int readInt(String key, int defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            System.err.println("Invalid int for " + key + ": " + value + ", using default " + defaultValue);
            return defaultValue;
        }
    }

    private static float readFloat(String key, float defaultValue) {
        String value = PROPERTIES.getProperty(key);
        if (value == null) return defaultValue;
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException e) {
            System.err.println("Invalid float for " + key + ": " + value + ", using default " + defaultValue);
            return defaultValue;
        }
    }

    private static boolean isPowerOfTwo(int value) {
        return (value & (value - 1)) == 0;
    }

    private static String readBuildVariant() {
        try (InputStream in = TerrainDiffusionConfig.class.getResourceAsStream("/build-variant.properties")) {
            if (in == null) return "unknown";
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("build.variant", "unknown");
        } catch (IOException e) {
            return "unknown";
        }
    }

}
