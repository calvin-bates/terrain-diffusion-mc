package com.github.xandergos.terraindiffusionmc;

import com.github.xandergos.terraindiffusionmc.explorer.ExplorerServer;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.ModelAssetManager;
import com.github.xandergos.terraindiffusionmc.pipeline.PipelineModels;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionDensityFunction;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionSurfaceLevelFunction;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import java.net.URI;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static net.minecraft.commands.Commands.literal;

public class TerrainDiffusionMc implements ModInitializer {
    public static final String MOD_ID = "terra";
    private static final Logger LOG = LoggerFactory.getLogger(TerrainDiffusionMc.class);

    @Override
    public void onInitialize() {
        LOG.info("Initializing terra");
        Registry.register(BuiltInRegistries.BIOME_SOURCE, Identifier.fromNamespaceAndPath(MOD_ID, "terrain_diffusion"), TerrainDiffusionBiomeSource.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "terrain_diffusion"), TerrainDiffusionDensityFunction.CODEC);
        Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "terrain_diffusion_surface"), TerrainDiffusionSurfaceLevelFunction.CODEC);

        ModelAssetManager.ensureAssetsReady();
        PipelineModels.load();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> LocalTerrainProvider.clearCache());

        ServerLevelEvents.LOAD.register((server, world) -> {
            if (world.dimension() == Level.OVERWORLD) {
                WorldScaleManager.initializeForWorld(world);
                LocalTerrainProvider.init(world.getSeed());
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ExplorerServer.stop());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                {
                    dispatcher.register(literal("terra-explore").executes(TerrainDiffusionMc::executeExplore));
                    dispatcher.register(literal("terra-column").executes(TerrainDiffusionMc::executeColumn));
                }
        );
    }

    /**
     * Debug: shows what the river code did to the ground around the player. In chat, a 15x15 map (north up):
     * W = water, _ = ground left as generated, digit = blocks the ground was lowered, + = raised. The log gets a
     * 41x41 map with the same symbols plus the generated ground height of each column.
     */
    private static int executeColumn(CommandContext<CommandSourceStack> ctx) {
        try {
            net.minecraft.core.BlockPos p = net.minecraft.core.BlockPos.containing(ctx.getSource().getPosition());
            int tile = com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig.tileSize();
            int scale = Math.max(1, WorldScaleManager.getCurrentScale());
            java.util.Map<Long, LocalTerrainProvider.HeightmapData> tiles = new java.util.HashMap<>();
            final int R = 20;
            int[][] orig = new int[2 * R + 1][2 * R + 1];
            int[][] fin = new int[2 * R + 1][2 * R + 1];
            int[][] wat = new int[2 * R + 1][2 * R + 1];
            for (int dz = -R; dz <= R; dz++) {
                for (int dx = -R; dx <= R; dx++) {
                    int x = p.getX() + dx, z = p.getZ() + dz;
                    int tx = Math.floorDiv(x, tile) * tile, tz = Math.floorDiv(z, tile) * tile;
                    long key = ((long) tz << 32) ^ (tx & 0xFFFFFFFFL);
                    LocalTerrainProvider.HeightmapData d = tiles.get(key);
                    if (d == null) {
                        d = LocalTerrainProvider.getInstance().fetchHeightmap(tz, tx, tz + tile, tx + tile);
                        tiles.put(key, d);
                    }
                    int lx = x - tx, lz = z - tz;
                    int o = com.github.xandergos.terraindiffusionmc.world.HeightConverter.convertToMinecraftHeight(d.heightmap[lz][lx], scale);
                    short sy = d.surfaceY == null ? Short.MIN_VALUE : d.surfaceY[lz][lx];
                    short wy = d.waterY == null ? Short.MIN_VALUE : d.waterY[lz][lx];
                    orig[dz + R][dx + R] = o;
                    fin[dz + R][dx + R] = sy == Short.MIN_VALUE ? o : sy;
                    wat[dz + R][dx + R] = wy;
                }
            }
            StringBuilder log = new StringBuilder("[Terra] terra-column at " + p.getX() + " " + p.getZ()
                    + " (centre generated Y " + orig[R][R] + ", final " + fin[R][R]
                    + (wat[R][R] != Short.MIN_VALUE ? ", water " + wat[R][R] : "") + ")\nlowered-by map (W water, _ unchanged, @ = you), then generated Y minus centre:\n");
            for (int r = 0; r <= 2 * R; r++) {
                for (int c = 0; c <= 2 * R; c++) log.append(symbol(r == R && c == R, wat[r][c], orig[r][c], fin[r][c]));
                log.append("   ");
                for (int c = 0; c <= 2 * R; c++) log.append(String.format("%3d", orig[r][c] - orig[R][R]));
                log.append('\n');
            }
            LOG.info(log.toString());
            ctx.getSource().sendSuccess(() -> Component.literal("Column " + p.getX() + " " + p.getZ() + ": generated Y "
                    + orig[R][R] + ", final " + fin[R][R] + (wat[R][R] != Short.MIN_VALUE ? ", water " + wat[R][R] : "")
                    + "  (W water, _ untouched, digit lowered, @ you; north up; full map in log)"), false);
            for (int r = R - 7; r <= R + 7; r++) {
                StringBuilder row = new StringBuilder();
                for (int c = R - 7; c <= R + 7; c++) row.append(symbol(r == R && c == R, wat[r][c], orig[r][c], fin[r][c]));
                String line = row.toString();
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            }
        } catch (Exception e) {
            LOG.error("terra-column failed", e);
            ctx.getSource().sendFailure(Component.literal("terra-column failed: " + e));
        }
        return 1;
    }

    private static char symbol(boolean me, int water, int orig, int fin) {
        if (me) return '@';
        if (water != Short.MIN_VALUE) return 'W';
        int d = orig - fin;
        if (d == 0) return '_';
        if (d < 0) return '+';
        return (char) ('0' + Math.min(9, d));
    }

    private static int executeExplore(CommandContext<CommandSourceStack> ctx) {
        try {
            int port = ExplorerServer.startIfNotRunning();
            String url = "http://localhost:" + port;
            MutableComponent link = Component.literal(url)
                    .withStyle(s -> s.withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                                  .withUnderlined(true));
            ctx.getSource().sendSuccess(
                    () -> Component.literal("Terrain Explorer: ").append(link),
                    false);
        } catch (Exception e) {
            LOG.error("Failed to start terrain explorer", e);
            ctx.getSource().sendFailure(Component.literal("Failed to start terrain explorer: " + e.getMessage()));
        }
        return 1;
    }
}
