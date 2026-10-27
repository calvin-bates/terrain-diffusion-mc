package com.github.xandergos.terraindiffusionmc;

import com.github.xandergos.terraindiffusionmc.explorer.ExplorerServer;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.ModelAssetManager;
import com.github.xandergos.terraindiffusionmc.pipeline.PipelineModels;
import com.github.xandergos.terraindiffusionmc.world.AxolotlLakes;
import com.github.xandergos.terraindiffusionmc.world.FeatureFinder;
import com.github.xandergos.terraindiffusionmc.world.InlandFish;
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
        com.github.xandergos.terraindiffusionmc.world.CaveLayerPlacement.register();

        AxolotlLakes.registerSpawns();
        InlandFish.registerSpawns();

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
                    FeatureFinder.register(dispatcher);
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
            int[][] act = new int[2 * R + 1][2 * R + 1];
            boolean[][] lakeAt = new boolean[2 * R + 1][2 * R + 1];
            net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
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
                    int o = com.github.xandergos.terraindiffusionmc.world.HeightConverter.convertToMinecraftHeight(d.heightmap[lz][lx], scale) - 1;   // top solid block (see applyRivers)
                    short sy = d.surfaceY == null ? Short.MIN_VALUE : d.surfaceY[lz][lx];
                    short wy = d.waterY == null ? Short.MIN_VALUE : d.waterY[lz][lx];
                    orig[dz + R][dx + R] = o;
                    fin[dz + R][dx + R] = sy == Short.MIN_VALUE ? o : sy;
                    wat[dz + R][dx + R] = wy;
                    lakeAt[dz + R][dx + R] = d.waterIsLake != null && d.waterIsLake[lz][lx];
                    // What is really in the world: the top block that blocks movement (water and plants excluded).
                    act[dz + R][dx + R] = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
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
            log.append("world vs data (top solid block in the world vs the expected one; _ = matches; W = data says water), then water Y minus centre ground:\n");
            int mism = 0;
            for (int r = 0; r <= 2 * R; r++) {
                for (int c = 0; c <= 2 * R; c++) {
                    int d = act[r][c] - fin[r][c];
                    char ch;
                    if (r == R && c == R) ch = '@';
                    else if (wat[r][c] != Short.MIN_VALUE) ch = d == 0 || act[r][c] <= wat[r][c] ? 'W' : '!';
                    else if (d == 0) ch = '_';
                    else { ch = d > 0 ? (char) ('0' + Math.min(9, d)) : (char) ('a' + Math.min(25, -d - 1)); mism++; }
                    log.append(ch);
                }
                log.append("   ");
                for (int c = 0; c <= 2 * R; c++) log.append(wat[r][c] == Short.MIN_VALUE ? "  ." : String.format("%3d", wat[r][c] - orig[R][R]));
                log.append('\n');
            }
            log.append("(digit = world higher by that many, letter a/b/c.. = world lower by 1/2/3.., ! = solid ground above the water line)\n");
            log.append("water blocks in the world (L still lake water, S still stream water, f flowing water, i ice, a air, x something else at the water line; _ no water in the data), then water depth in the data (water Y minus bed):\n");
            for (int r = 0; r <= 2 * R; r++) {
                for (int c = 0; c <= 2 * R; c++) {
                    char ch = '_';
                    if (wat[r][c] != Short.MIN_VALUE) {
                        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(
                                new net.minecraft.core.BlockPos(p.getX() + c - R, wat[r][c], p.getZ() + r - R));
                        if (st.getFluidState().isSource()) ch = lakeAt[r][c] ? 'L' : 'S';
                        else if (!st.getFluidState().isEmpty()) ch = 'f';
                        else if (st.is(net.minecraft.world.level.block.Blocks.ICE)) ch = 'i';
                        else if (st.isAir()) ch = 'a';
                        else ch = 'x';
                    }
                    if (r == R && c == R) ch = '@';
                    log.append(ch);
                }
                log.append("   ");
                for (int c = 0; c <= 2 * R; c++) log.append(wat[r][c] == Short.MIN_VALUE ? "  ." : String.format("%3d", wat[r][c] - fin[r][c]));
                log.append('\n');
            }
            log.append("hydrology map under each column (L lake, s stream, r river, m main river, . none, ? unavailable):\n");
            for (int r = 0; r <= 2 * R; r++) {
                for (int c = 0; c <= 2 * R; c++) {
                    if (r == R && c == R) { log.append('@'); continue; }
                    byte k = LocalTerrainProvider.hydrologyClassAt(p.getX() + c - R, p.getZ() + r - R);
                    log.append(k == 4 ? 'L' : k == 1 ? 's' : k == 2 ? 'r' : k == 3 ? 'm' : k == 0 ? '.' : '?');
                }
                log.append('\n');
            }
            LOG.info(log.toString());
            final int mismatches = mism;
            ctx.getSource().sendSuccess(() -> Component.literal("Column " + p.getX() + " " + p.getZ() + ": generated Y "
                    + orig[R][R] + ", final " + fin[R][R] + (wat[R][R] != Short.MIN_VALUE ? ", water " + wat[R][R] : "")
                    + ", world top here " + act[R][R] + ", columns where the world differs from the data: " + mismatches
                    + "  (W water, _ untouched, digit lowered, @ you; north up; full map in log)"), false);
            for (int r = R - 7; r <= R + 7; r++) {
                StringBuilder row = new StringBuilder();
                for (int c = R - 7; c <= R + 7; c++) row.append(symbol(r == R && c == R, wat[r][c], orig[r][c], fin[r][c]));
                String line = row.toString();
                ctx.getSource().sendSuccess(() -> Component.literal(line), false);
            }
            final boolean axolotlChunk = AxolotlLakes.isAxolotlChunk(p.getX() >> 4, p.getZ() >> 4, level.getSeed());
            final int axolotlMinY = AxolotlLakes.minY();
            ctx.getSource().sendSuccess(() -> Component.literal("Axolotls: this chunk is " + (axolotlChunk ? "" : "not ")
                    + "an axolotl chunk; they spawn in lakes in those chunks from Y " + axolotlMinY + " up"), false);
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
