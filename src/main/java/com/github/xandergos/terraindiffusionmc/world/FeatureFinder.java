package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.explorer.BiomePalette;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveBiomes;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveShapes;
import com.github.xandergos.terraindiffusionmc.pipeline.CoastPlan;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.OceanFeatures;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * {@code /terra-find <feature>}: the nearest ocean feature of a kind (volcanic island and its later stages, island-arc
 * island, landslide scar, aseismic ridge, guyot, seamount, trench, archipelago sea, vent field), river delta, or
 * reef or carbonate bank, with a click-to-teleport. {@code /terra-find biome <name>} finds the nearest land biome of
 * Terra's layout (vanilla's {@code /locate biome} doesn't know it). Ocean features use the same placement rules as the terrain
 * ({@link OceanFeatures#landmarks}), so it can point at places that are not generated yet. Deltas, reefs and banks
 * depend on the model's own terrain, so they are searched for by computing it. The search runs off the server thread
 * because far-away searches compute the coarse map.
 */
public final class FeatureFinder {
    private static final Logger LOG = LoggerFactory.getLogger(FeatureFinder.class);

    /** Command word → landmark type and display name. */
    private static final Map<String, String[]> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("volcanic_island", new String[]{"volcanic_island", "volcanic island"});
        KINDS.put("flooded_caldera", new String[]{"flooded_caldera", "flooded caldera"});
        KINDS.put("barrier_reef_island", new String[]{"barrier_reef_island", "island with a barrier reef"});
        KINDS.put("arc_island", new String[]{"arc_island", "island-arc island"});
        KINDS.put("atoll", new String[]{"atoll", "atoll"});
        KINDS.put("raised_coral_island", new String[]{"raised_coral_island", "raised coral island"});
        KINDS.put("landslide_scar", new String[]{"landslide_scar", "island landslide scar"});
        KINDS.put("aseismic_ridge", new String[]{"aseismic_ridge", "aseismic ridge"});
        KINDS.put("tuff_ring", new String[]{"tuff_ring", "tuff ring or maar"});
        KINDS.put("oceanic_plateau", new String[]{"oceanic_plateau", "oceanic plateau"});
        KINDS.put("forearc_island", new String[]{"forearc_island", "forearc island"});
        KINDS.put("back_arc_basin", new String[]{"back_arc_basin", "back-arc basin spreading axis"});
        KINDS.put("mud_volcano", new String[]{"mud_volcano", "mud volcano"});
        KINDS.put("guyot", new String[]{"guyot", "guyot"});
        KINDS.put("seamount", new String[]{"seamount", "seamount"});
        KINDS.put("trench", new String[]{"trench", "trench"});
        KINDS.put("archipelago", new String[]{"archipelago", "archipelago sea"});
        KINDS.put("rift_sea", new String[]{"rift_sea", "young rift sea"});
        KINDS.put("mushroom_island", new String[]{"mushroom_island", "mushroom island"});
        KINDS.put("active_margin", new String[]{"active_margin", "active continental margin (trench off a coastal range)"});
        KINDS.put("vent_field", new String[]{"vent_field", "hydrothermal vent field"});
        KINDS.put("delta", new String[]{"delta", "river delta"});
        KINDS.put("spit", new String[]{"spit", "sand spit"});
        KINDS.put("tombolo", new String[]{"tombolo", "tombolo"});
        KINDS.put("cuspate_foreland", new String[]{"cuspate_foreland", "cuspate foreland"});
        KINDS.put("barrier_island", new String[]{"barrier_island", "barrier island"});
        KINDS.put("iceberg_field", new String[]{"iceberg_field", "iceberg field"});
        KINDS.put("ice_island", new String[]{"ice_island", "ice island"});
        KINDS.put("fringing_reef", new String[]{"fringing_reef", "fringing reef"});
        KINDS.put("barrier_reef", new String[]{"barrier_reef", "offshore barrier reef"});
        KINDS.put("carbonate_bank", new String[]{"carbonate_bank", "carbonate bank"});
        KINDS.put("submarine_canyon", new String[]{"submarine_canyon", "submarine canyon"});
        KINDS.put("deep_sea_fan", new String[]{"deep_sea_fan", "deep-sea fan channel"});
        KINDS.put("sand_waves", new String[]{"sand_waves", "sand wave field"});
        KINDS.put("pockmarks", new String[]{"pockmarks", "pockmark field"});
        KINDS.put("salt_domes", new String[]{"salt_domes", "salt dome province"});
        KINDS.put("glacial_trough", new String[]{"glacial_trough", "glacial trough"});
        KINDS.put("strandflat", new String[]{"strandflat", "strandflat with skerries"});
        KINDS.put("volcano", new String[]{"volcano", "volcano"});
        KINDS.put("stratovolcano", new String[]{"stratovolcano", "stratovolcano"});
        KINDS.put("shield_volcano", new String[]{"shield_volcano", "shield volcano"});
        KINDS.put("caldera", new String[]{"caldera", "volcano with a caldera"});
        KINDS.put("lava_field", new String[]{"lava_field", "lava field"});
        KINDS.put("active_island", new String[]{"active_island", "active island volcano"});
        KINDS.put("geothermal_basin", new String[]{"geothermal_basin", "geothermal basin (hot springs)"});
        KINDS.put("mirage_isles", new String[]{"mirage_isles", "mirage isles"});
    }

    /** Farthest search, in native pixels (1200 km). */
    private static final int MAX_RADIUS_PX = 40_000;

    private FeatureFinder() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("terra-find")
                .then(literal("cave")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(CAVES.keySet(), builder))
                                .executes(FeatureFinder::executeCave)))
                .then(literal("biome")
                        .then(argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(BIOMES.keySet(), builder))
                                .executes(FeatureFinder::executeBiome)))
                .then(argument("feature", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(KINDS.keySet(), builder))
                        .executes(FeatureFinder::execute)));
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String word = StringArgumentType.getString(ctx, "feature");
        String[] kind = KINDS.get(word);
        if (kind == null) {
            source.sendFailure(Component.literal("Unknown feature \"" + word + "\". Try one of: " + String.join(", ", KINDS.keySet())));
            return 0;
        }
        Vec3 pos = source.getPosition();
        int scale = Math.max(1, WorldScaleManager.getCurrentScale());
        double pi = pos.z / scale, pj = pos.x / scale;
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Searching for the nearest " + kind[1] + "…").withStyle(ChatFormatting.GRAY), false);

        if (word.equals("delta")) {
            findDelta(source, server, pi, pj, scale);
            return 1;
        }
        if (word.equals("iceberg_field") || word.equals("ice_island")) {
            findIce(source, server, word.equals("ice_island"), kind[1], pos.x, pos.z, scale);
            return 1;
        }
        if (word.equals("volcano") || word.equals("stratovolcano") || word.equals("shield_volcano") || word.equals("caldera")
                || word.equals("lava_field") || word.equals("geothermal_basin") || word.equals("mirage_isles")) {
            findVolcano(source, server, word, kind[1], pi, pj, scale);
            return 1;
        }
        if (word.equals("spit") || word.equals("tombolo") || word.equals("cuspate_foreland") || word.equals("barrier_island")) {
            int k = switch (word) {
                case "spit" -> CoastPlan.SPIT;
                case "tombolo" -> CoastPlan.TOMBOLO;
                case "barrier_island" -> CoastPlan.BARRIER;
                default -> CoastPlan.FORELAND;
            };
            findCoast(source, server, k, kind[1], pi, pj, scale);
            return 1;
        }
        if (OceanFeatures.shallowTag(word) >= 0) {
            findShallow(source, server, word, kind[1], pi, pj, scale);
            return 1;
        }

        Thread worker = new Thread(() -> {
            try {
                OceanFeatures.Landmark found = LocalTerrainProvider.findNearestLandmark(kind[0], pi, pj, MAX_RADIUS_PX);
                server.execute(() -> report(source, kind[1], found, pi, pj, scale));
            } catch (Exception e) {
                LOG.error("terra-find failed", e);
                server.execute(() -> source.sendFailure(Component.literal("terra-find failed: " + e.getMessage())));
            }
        }, "terra-find");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    /**
     * Land biomes {@code /terra-find biome} knows: every biome on the explorer's map except oceans, rivers and the
     * water overlay (key → id, from {@link BiomePalette}).
     */
    private static final Map<String, Integer> BIOMES = new LinkedHashMap<>();
    static {
        for (Map.Entry<String, Integer> e : BiomePalette.keys().entrySet()) {
            int id = e.getValue();
            if ((id >= 41 && id <= 49) || id == 120 || id == 121) continue;
            BIOMES.put(e.getKey(), id);
        }
    }

    /** Rings of 7.7 km windows searched for a biome around the player (about 35 km). */
    private static final int BIOME_RINGS = 4;

    private static int executeBiome(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String word = StringArgumentType.getString(ctx, "name");
        Integer id = BIOMES.get(word);
        if (id == null) {
            source.sendFailure(Component.literal("Unknown biome \"" + word + "\". Try one of: " + String.join(", ", BIOMES.keySet())));
            return 0;
        }
        String name = BiomePalette.name(id);
        Vec3 pos = source.getPosition();
        int scale = Math.max(1, WorldScaleManager.getCurrentScale());
        double pi = pos.z / scale, pj = pos.x / scale;
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Searching for the nearest " + name + "…").withStyle(ChatFormatting.GRAY), false);
        Thread worker = new Thread(() -> {
            try {
                LocalTerrainProvider.BiomeSearch res = LocalTerrainProvider.findNearestBiome(id.shortValue(), pi, pj,
                        BIOME_RINGS, reachedPx -> {
                            int km = Math.round(reachedPx * 30 / 1000f);
                            if (km > 0) server.execute(() -> source.sendSuccess(() -> Component.literal("…searched about "
                                    + km + " km around you").withStyle(ChatFormatting.GRAY), false));
                        });
                server.execute(() -> reportBiome(source, name, res, pi, pj, scale));
            } catch (Exception e) {
                LOG.error("terra-find biome " + word + " failed", e);
                server.execute(() -> source.sendFailure(Component.literal("terra-find failed: " + e.getMessage())));
            }
        }, "terra-find");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static void reportBiome(CommandSourceStack source, String name, LocalTerrainProvider.BiomeSearch res,
                                    double pi, double pj, int scale) {
        int searchedKm = Math.round(res.searchedRadiusPx() * 30 / 1000f);
        if (!res.found()) {
            source.sendFailure(Component.literal("No " + name + " within about " + searchedKm + " km (" + res.windows()
                    + " areas classified). Patch biomes only form inside their parent biome; check the explorer's Biomes map."));
            return;
        }
        long x = Math.round(res.j() * scale), z = Math.round(res.i() * scale);
        // Land a few blocks above the planned surface (the game adds a little noise and carves rivers).
        int y = HeightConverter.convertToMinecraftHeight((short) Math.max(0, Math.round(res.elevM())), scale) + 4;
        double distPx = Math.hypot(res.i() - pi, res.j() - pj);
        long blocks = Math.round(distPx * scale);
        String km = String.format("%.1f", distPx * 30 / 1000);
        String tp = "/tp @s " + x + " " + y + " " + z;
        MutableComponent teleport = Component.literal("[Teleport]").withStyle(s -> s
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.SuggestCommand(tp))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(tp))));
        MutableComponent copy = Component.literal("[Copy]").withStyle(s -> s
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.CopyToClipboard(x + " " + y + " " + z))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy the coordinates"))));
        String edge = res.inside() ? "" : "; only small or thin patches nearby, this is on one's edge";
        source.sendSuccess(() -> Component.literal("Nearest " + name + ": " + x + ", " + z + " (" + blocks + " blocks, "
                        + km + " km" + edge + ") ")
                .append(teleport).append(" ").append(copy), false);
    }

    // ---- Caves (caves plan v1.5) ----

    /** Cave biomes {@code /terra-find cave} knows: command word → id. */
    private static final Map<String, Short> CAVES = new LinkedHashMap<>();
    static {
        for (Map.Entry<Short, String> e : CaveBiomes.NAMES.entrySet()) {
            CAVES.put(e.getValue().replaceAll(".*:", ""), e.getKey());
        }
        // Not biomes: where caves meet the surface (part two).
        CAVES.put("cenote", (short) -2);
        CAVES.put("river_sink", (short) -3);
        CAVES.put("sea_caves", (short) -4);
        // Formations (part four): rare zones, found zone by zone.
        CAVES.put("boxwork", (short) -5);
        CAVES.put("crystal_room", (short) -6);
        CAVES.put("cave_mouth", (short) -7);
        CAVES.put("ancient_city", (short) -8);
    }

    /** How far around the player cave biomes are searched, in blocks, and the spacing of the searched columns. */
    private static final int CAVE_RADIUS = 1536, CAVE_STEP = 16;
    /** A found cave spot has its cave biome this many blocks around it in every direction (well inside the biome). */
    private static final int INSIDE = 10;

    private static int executeCave(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String word = StringArgumentType.getString(ctx, "name");
        Short id = CAVES.get(word);
        if (id == null) {
            source.sendFailure(Component.literal("Unknown cave \"" + word + "\". Try one of: " + String.join(", ", CAVES.keySet())));
            return 0;
        }
        if (!TerrainDiffusionConfig.caveBiomes() || !TerrainDiffusionConfig.caves()) {
            source.sendFailure(Component.literal("Cave biomes or noise caves are turned off (caves.biomes / caves.enabled)."));
            return 0;
        }
        String name = word.replace('_', ' ');
        Vec3 pos = source.getPosition();
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Searching for the nearest " + name + " with an open cave…")
                .withStyle(ChatFormatting.GRAY), false);
        Thread worker = new Thread(() -> {
            try {
                int px = (int) Math.floor(pos.x), pz = (int) Math.floor(pos.z);
                String cutKind = id == -2 ? "cenote" : id == -3 ? "river_sink" : id == -7 ? "cave_mouth"
                        : CaveBiomes.isSurfaceCut(id) ? word : null;
                int[] found = cutKind != null ? findCut(cutKind, px, pz) : id == -4 ? findSeaCave(px, pz)
                        : id == -5 ? findZone(com.github.xandergos.terraindiffusionmc.pipeline.CaveFormationLayout.ZONE_BOXWORK, px, pz)
                        : id == -6 ? findZone(com.github.xandergos.terraindiffusionmc.pipeline.CaveFormationLayout.ZONE_CRYSTAL, px, pz)
                        : id == -8 ? findCity(px, pz)
                        : id >= 0 && CaveBiomes.shapeFamily(id) == CaveBiomes.SHAPE_HALL ? findHall(id, px, pz)
                        : findCave(id, px, pz);
                server.execute(() -> reportCave(source, name, found, pos));
            } catch (Exception e) {
                LOG.error("terra-find cave " + word + " failed", e);
                server.execute(() -> source.sendFailure(Component.literal("terra-find failed: " + e.getMessage())));
            }
        }, "terra-find");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    /**
     * The nearest standing spot (two air blocks over a floor) in Terra's noise caves inside the given cave biome, ring
     * by ring around (x0, z0), or null. Uses the same functions as the terrain (cave biome layer, cave field, roof), so it
     * also points at places not generated yet. Vanilla's carver tunnels aren't known here.
     */
    private static int[] findCave(short id, int x0, int z0) {
        long seed = LocalTerrainProvider.getSeed();
        CaveBiomes biomes = CaveBiomes.forSeed(seed);
        CaveShapes shapes = CaveShapes.forSeed(seed);
        CaveSurface surface = CaveSurface.get();
        TerrainDiffusionDensityFunction.TileCache cache = TerrainDiffusionDensityFunction.TileCache.get();
        for (int r = 0; r <= CAVE_RADIUS; r += CAVE_STEP) {
            int[] best = null;
            double bestD = Double.MAX_VALUE;
            int n = r == 0 ? 1 : Math.max(8, (int) Math.round(2 * Math.PI * r / CAVE_STEP));
            for (int k = 0; k < n; k++) {
                double a = 2 * Math.PI * k / n;
                int x = x0 + (int) Math.round(r * Math.cos(a)), z = z0 + (int) Math.round(r * Math.sin(a));
                int ref = cache.caveRefTop(x, z);
                if (ref == Integer.MIN_VALUE) continue;
                float roof = switch (cache.caveKind) {
                    case TerrainDiffusionDensityFunction.TileCache.CAVE_SEA -> CaveShapes.ROOF_SEA;
                    case TerrainDiffusionDensityFunction.TileCache.CAVE_WATER -> CaveShapes.ROOF_WATER;
                    default -> shapes.landRoof(x, z);
                };
                // Start a little under the top of the cave layer, where the game's biome blending can still show the
                // surface biome, and only take spots with the cave biome all around (well inside it).
                for (int y = ref - CaveBiomes.START_DEPTH - INSIDE; y > -60; y--) {
                    if ((y & 3) == 0 && biomes.pick(x, y, z, surface) != id) {
                        y -= 3;   // skip the rest of this 4-block step quickly
                        continue;
                    }
                    if (biomes.pick(x, y, z, surface) != id) continue;
                    if (biomes.pick(x + INSIDE, y, z, surface) != id || biomes.pick(x - INSIDE, y, z, surface) != id
                            || biomes.pick(x, y, z + INSIDE, surface) != id || biomes.pick(x, y, z - INSIDE, surface) != id
                            || biomes.pick(x, y - INSIDE, z, surface) != id) continue;
                    // Two air blocks over a rock floor.
                    if (caveAir(shapes, x, y, z, ref, roof) && caveAir(shapes, x, y + 1, z, ref, roof)
                            && !caveAir(shapes, x, y - 1, z, ref, roof)) {
                        double d = Math.hypot(x - x0, z - z0);
                        if (d < bestD) {
                            bestD = d;
                            best = new int[]{x, y, z};
                        }
                        break;
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /**
     * The nearest giant hall of a kind (geode hall, deep dark forest, selenite chamber), cell by cell in
     * rings out to about 6 km: a standing spot on the hall's floor near its centre, or the centre itself.
     */
    private static int[] findHall(short id, int x0, int z0) {
        long seed = LocalTerrainProvider.getSeed();
        CaveBiomes layer = CaveBiomes.forSeed(seed);
        CaveShapes shapes = CaveShapes.forSeed(seed);
        CaveSurface surface = CaveSurface.get();
        int r = CaveBiomes.REGION_XZ;
        int cx = Math.floorDiv(x0, r), cz = Math.floorDiv(z0, r);
        int cyLo = Math.floorDiv(-64, CaveBiomes.REGION_Y), cyHi = Math.floorDiv(40, CaveBiomes.REGION_Y);
        for (int ring = 0; ring <= 24; ring++) {
            int[] best = null;
            double bestD = Double.MAX_VALUE;
            for (int dz = -ring; dz <= ring; dz++)
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    for (int cy = cyLo; cy <= cyHi; cy++) {
                        long cell = CaveBiomes.unwarpedCell((cx + dx) * r, cy * CaveBiomes.REGION_Y, (cz + dz) * r);
                        var hall = com.github.xandergos.terraindiffusionmc.pipeline.CaveHalls.of(layer, surface, cell);
                        if (hall == null || hall.id() != id) continue;
                        // Hall halfway under the ground at least (the cave layer starts well below it).
                        if (hall.cy() + hall.ry() > surface.groundTop(hall.cx(), hall.cz()) - CaveBiomes.START_DEPTH) continue;
                        double d = Math.hypot(hall.cx() - x0, hall.cz() - z0);
                        if (d >= bestD) continue;
                        int floorY = (int) Math.ceil(hall.cy() - Math.min(hall.floor(), hall.ry() * 0.8f)) + 1;
                        int[] spot = new int[]{hall.cx(), hall.cy(), hall.cz()};
                        for (int off = 0; off <= 12; off += 3) {
                            int x = hall.cx() + off;
                            if (shapes.at(x, floorY, hall.cz(), surface) < 0 && shapes.at(x, floorY + 1, hall.cz(), surface) < 0) {
                                spot = new int[]{x, floorY, hall.cz()};
                                break;
                            }
                        }
                        bestD = d;
                        best = spot;
                    }
                }
            if (best != null) return best;
        }
        return null;
    }

    /**
     * The nearest formation zone of a type (boxwork, crystal room) that holds a cave ceiling, zone cell by zone cell in
     * rings out to about 3 km: a standing spot under that ceiling. Only Terra's noise caves are known here.
     */
    private static int[] findZone(int type, int x0, int z0) {
        long seed = LocalTerrainProvider.getSeed();
        CaveBiomes layer = CaveBiomes.forSeed(seed);
        CaveShapes shapes = CaveShapes.forSeed(seed);
        CaveSurface surface = CaveSurface.get();
        TerrainDiffusionDensityFunction.TileCache cache = TerrainDiffusionDensityFunction.TileCache.get();
        int sxz = com.github.xandergos.terraindiffusionmc.pipeline.CaveFormationLayout.zoneXZ(type);
        int sy = com.github.xandergos.terraindiffusionmc.pipeline.CaveFormationLayout.zoneY(type);
        float[] chance = com.github.xandergos.terraindiffusionmc.pipeline.CaveFormationLayout.zoneChance(type);
        int cx0 = Math.floorDiv(x0, sxz), cz0 = Math.floorDiv(z0, sxz);
        int cyLo = Math.floorDiv(-64, sy), cyHi = Math.floorDiv(320, sy);
        for (int ring = 0; ring <= 3000 / sxz; ring++) {
            int[] best = null;
            double bestD = Double.MAX_VALUE;
            for (int dz = -ring; dz <= ring; dz++)
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    for (int cy = cyLo; cy <= cyHi; cy++) {
                        var zone = com.github.xandergos.terraindiffusionmc.pipeline.CaveFormationLayout.zone(type, seed, layer,
                                surface, cx0 + dx, cy, cz0 + dz, chance);
                        if (zone == null) continue;
                        double d = Math.hypot(zone.x() - x0, zone.z() - z0);
                        if (d >= bestD) continue;
                        int[] spot = zoneSpot(zone, shapes, cache);
                        if (spot != null) {
                            bestD = d;
                            best = spot;
                        }
                    }
                }
            if (best != null) return best;
        }
        return null;
    }

    /** A standing spot under a cave ceiling inside the zone (columns 3 blocks apart from the middle out), or null. */
    private static int[] zoneSpot(com.github.xandergos.terraindiffusionmc.pipeline.CaveFormationLayout.Zone zone,
                                  CaveShapes shapes, TerrainDiffusionDensityFunction.TileCache cache) {
        int r = (int) zone.r(), ry = (int) (zone.r() * zone.stretch());
        for (int rr = 0; rr <= r; rr += 3)
            for (int dz = -rr; dz <= rr; dz += 3)
                for (int dx = -rr; dx <= rr; dx += 3) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != rr) continue;
                    int x = zone.x() + dx, z = zone.z() + dz;
                    int ref = cache.caveRefTop(x, z);
                    if (ref == Integer.MIN_VALUE) continue;
                    float roof = switch (cache.caveKind) {
                        case TerrainDiffusionDensityFunction.TileCache.CAVE_SEA -> CaveShapes.ROOF_SEA;
                        case TerrainDiffusionDensityFunction.TileCache.CAVE_WATER -> CaveShapes.ROOF_WATER;
                        default -> shapes.landRoof(x, z);
                    };
                    // Coarse steps of 3 for cave air, then up to its ceiling and down to its floor.
                    for (int y = Math.min(zone.y() + ry, ref - 2); y > zone.y() - ry && y > -60; y -= 3) {
                        if (!caveAir(shapes, x, y, z, ref, roof)) continue;
                        int c = y;
                        while (c < ref && caveAir(shapes, x, c + 1, z, ref, roof)) c++;
                        if (zone.dist(x, c + 1, z) >= 1f) continue;
                        int f = y;
                        while (f > -60 && caveAir(shapes, x, f - 1, z, ref, roof)) f--;
                        if (c - f >= 2) return new int[]{x, f, z};
                        y = f;   // skip below this run
                    }
                }
        return null;
    }

    /** The nearest ancient city cavern (CaveHalls.city), out to about 6 km: a spot on its floor beside the centre. */
    private static int[] findCity(int x0, int z0) {
        long seed = LocalTerrainProvider.getSeed();
        CaveBiomes layer = CaveBiomes.forSeed(seed);
        CaveSurface surface = CaveSurface.get();
        int span = com.github.xandergos.terraindiffusionmc.pipeline.CaveHalls.CITY_SPACING * 16;
        int gx0 = Math.floorDiv(x0, span), gz0 = Math.floorDiv(z0, span);
        for (int ring = 0; ring <= 16; ring++) {
            int[] best = null;
            double bestD = Double.MAX_VALUE;
            for (int dz = -ring; dz <= ring; dz++)
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    var h = com.github.xandergos.terraindiffusionmc.pipeline.CaveHalls.city(layer, surface, gx0 + dx, gz0 + dz);
                    if (h == null) continue;
                    double d = Math.hypot(h.cx() - x0, h.cz() - z0);
                    if (d < bestD) {
                        bestD = d;
                        // Beside the city's centre (its tallest building), a little above the floor.
                        best = new int[]{h.cx() + 40, h.cy() + 6, h.cz()};
                    }
                }
            if (best != null) return best;
        }
        return null;
    }

    /** The nearest cut of a kind (CaveSites: cenote, river sink, ravine, deep shaft, sinkhole jungle, blue hole). */
    private static int[] findCut(String kind, int x0, int z0) {
        long seed = LocalTerrainProvider.getSeed();
        CaveBiomes layer = CaveBiomes.forSeed(seed);
        CaveSurface surface = CaveSurface.get();
        int r = CaveBiomes.REGION_XZ, half = r / 2;
        int cx = Math.floorDiv(x0 - half, r), cz = Math.floorDiv(z0 - half, r);
        for (int ring = 0; ring <= 28; ring++) {
            int[] best = null;
            double bestD = Double.MAX_VALUE;
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    var s = com.github.xandergos.terraindiffusionmc.pipeline.CaveSites.site((cx + dx) * r + half,
                            (cz + dz) * r + half, layer, surface);
                    if (s == null || !s.kind().equals(kind)) continue;
                    double d = Math.hypot(s.x() - x0, s.z() - z0);
                    if (d < bestD) {
                        bestD = d;
                        // Arrive just above the surface beside the opening (top() is far above for open cuts).
                        int y = Math.min(s.top(), surface.groundTop(s.x(), s.z()) + 3) + 2;
                        best = new int[]{s.x(), y, s.z()};
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    /** The nearest sea cave grotto: a column at the cliff face with air at the waterline. */
    private static int[] findSeaCave(int x0, int z0) {
        var sea = com.github.xandergos.terraindiffusionmc.pipeline.SeaCaves.forSeed(LocalTerrainProvider.getSeed());
        CaveSurface surface = CaveSurface.get();
        for (int r = 0; r <= 1536; r += 8) {
            int n = r == 0 ? 1 : Math.max(8, (int) Math.round(2 * Math.PI * r / 8));
            for (int k = 0; k < n; k++) {
                double a = 2 * Math.PI * k / n;
                int x = x0 + (int) Math.round(r * Math.cos(a)), z = z0 + (int) Math.round(r * Math.sin(a));
                int d = sea.seaDistance(x, z, surface);
                if (d != 8) continue;
                int g = surface.groundTop(x, z);
                for (int y = 64; y <= 68; y++) {
                    if (sea.value(x, y, z, g, d) <= 0 && sea.value(x, y + 1, z, g, d) <= 0) return new int[]{x, y, z};
                }
            }
        }
        return null;
    }

    private static boolean caveAir(CaveShapes shapes, int x, int y, int z, int ref, float roof) {
        return y <= ref && shapes.at(x, y, z, CaveSurface.get()) + CaveShapes.roof(ref - y, roof) <= 0;
    }

    private static void reportCave(CommandSourceStack source, String name, int[] found, Vec3 from) {
        if (found == null) {
            source.sendFailure(Component.literal("No " + name + " found nearby. Cave biomes are regions about 250 blocks"
                    + " across; cenotes need karst caves, river sinks underground lakes under a river, sea caves a rocky"
                    + " coast. Try again from somewhere else."));
            return;
        }
        int x = found[0], y = found[1], z = found[2];
        long blocks = Math.round(Math.hypot(x - from.x, z - from.z));
        String tp = "/tp @s " + x + " " + y + " " + z;
        MutableComponent teleport = Component.literal("[Teleport]").withStyle(s -> s
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.SuggestCommand(tp))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(tp))));
        MutableComponent copy = Component.literal("[Copy]").withStyle(s -> s
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.CopyToClipboard(x + " " + y + " " + z))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy the coordinates"))));
        source.sendSuccess(() -> Component.literal("Nearest " + name + ": " + x + ", " + y + ", " + z + " (" + blocks
                + " blocks away, in a cave) ").append(teleport).append(" ").append(copy), false);
    }

    /** Rings of iceberg regions (7.7 km each) searched around the player. */
    private static final int ICE_RINGS = 3;

    /** Iceberg fields and ice islands come from the iceberg plans, searched region by region near the player. */
    private static void findIce(CommandSourceStack source, MinecraftServer server, boolean island, String name,
                                double bx, double bz, int scale) {
        Thread worker = new Thread(() -> {
            try {
                int regionBlocks = com.github.xandergos.terraindiffusionmc.pipeline.IcebergPlan.REGION_PX * scale;
                int cri = Math.floorDiv((int) Math.floor(bz), regionBlocks), crj = Math.floorDiv((int) Math.floor(bx), regionBlocks);
                double[] best = {Double.MAX_VALUE, 0, 0};
                // Nearby first: the plans around the player, ring by ring.
                for (int k = 0; k <= ICE_RINGS; k++) {
                    if ((k - 1) * (double) regionBlocks > best[0]) break;
                    for (int di = -k; di <= k; di++) {
                        for (int dj = -k; dj <= k; dj++) {
                            if (Math.max(Math.abs(di), Math.abs(dj)) != k) continue;
                            nearestField(cri + di, crj + dj, island, bx, bz, best);
                        }
                    }
                }
                int searchedKm = Math.round((ICE_RINGS + 0.5f) * regionBlocks / (float) scale * 30 / 1000f);
                if (best[0] == Double.MAX_VALUE) {
                    // Farther: cold seas are often hundreds or thousands of km away. Pick the cold sea from the coarse map
                    // (one coarse pixel per iceberg region, cheap) in squares of growing size, then build the plans of the
                    // nearest candidate regions until one has the feature.
                    int checked = 0;
                    java.util.Set<Long> done = new java.util.HashSet<>();
                    for (int half = 16; half <= ICE_COARSE_MAX_HALF && best[0] == Double.MAX_VALUE && checked < ICE_MAX_CHECKS; half *= 2) {
                        final int h = half;
                        server.execute(() -> source.sendSuccess(() -> Component.literal("…looking for a cold enough sea within about "
                                + Math.round(h * 256 * 30 / 1000f) + " km").withStyle(ChatFormatting.GRAY), false));
                        int n = 2 * half + 1;
                        java.util.List<int[]> cands = new java.util.ArrayList<>();
                        // In blocks of 128 coarse pixels, each on the inference thread by itself, so chunk generation keeps
                        // going between them.
                        for (int r0 = 0; r0 < n; r0 += 128) {
                            for (int c0 = 0; c0 < n; c0 += 128) {
                                int bh = Math.min(128, n - r0), bw = Math.min(128, n - c0), np = bh * bw;
                                int gi = cri - half + r0, gj = crj - half + c0;
                                com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor co =
                                        LocalTerrainProvider.getPipelineCoarse(gi, gj, gi + bh, gj + bw);
                                for (int r = 0; r < bh; r++) {
                                    for (int c = 0; c < bw; c++) {
                                        int px = r * bw + c;
                                        float w = co.data[6 * np + px];
                                        if (w <= 1e-6f) continue;
                                        float e = co.data[px] / w, t = co.data[2 * np + px] / w;   // e: signed sqrt of metres
                                        boolean ok = island ? e < -10f && t < -3f : e < 0f && t < 0f;
                                        if (ok) cands.add(new int[]{gi + r, gj + c});
                                    }
                                }
                            }
                        }
                        cands.sort(java.util.Comparator.comparingDouble(q -> Math.hypot(q[0] - cri, q[1] - crj)));
                        for (int[] q : cands) {
                            if (checked >= ICE_MAX_CHECKS) break;
                            if (!done.add(((long) q[0] << 32) ^ (q[1] & 0xffffffffL))) continue;
                            // Once something is found, only regions that could still be closer.
                            if (best[0] != Double.MAX_VALUE && (Math.hypot(q[0] - cri, q[1] - crj) - 1.5) * regionBlocks > best[0]) break;
                            checked++;
                            nearestField(q[0], q[1], island, bx, bz, best);
                        }
                        searchedKm = Math.round(half * 256 * 30 / 1000f);
                    }
                }
                final double bd = best[0], x = best[1], z = best[2];
                final int km = searchedKm;
                server.execute(() -> {
                    if (bd == Double.MAX_VALUE) {
                        source.sendFailure(Component.literal("No " + name + " within about " + km + " km. Icebergs need a frozen "
                                + "or cold sea (they crowd coasts where cold land meets it); ice islands lie far out in frozen seas."));
                        return;
                    }
                    report(source, name, new OceanFeatures.Landmark(island ? "ice_island" : "iceberg_field",
                            z / scale, x / scale, 0f), bz / scale, bx / scale, scale);
                });
            } catch (Exception e) {
                LOG.error("terra-find ice failed", e);
                server.execute(() -> source.sendFailure(Component.literal("terra-find failed: " + e.getMessage())));
            }
        }, "terra-find");
        worker.setDaemon(true);
        worker.start();
    }

    /** Keeps in {@code best} ({distance, x, z}, blocks) the nearest field of the wanted kind in iceberg region (ri, rj). */
    private static void nearestField(int ri, int rj, boolean island, double bx, double bz, double[] best) {
        for (var f : Icebergs.plan(ri, rj).fields) {
            if (f.iceIsland() != island) continue;
            double d = Math.hypot(f.x() - bx, f.z() - bz);
            if (d < best[0]) { best[0] = d; best[1] = f.x(); best[2] = f.z(); }
        }
    }

    /** Farthest coarse search for cold seas (coarse pixels either way: 512 is about 3900 km) and most regions planned. */
    private static final int ICE_COARSE_MAX_HALF = 512, ICE_MAX_CHECKS = 60;

    /** Landform cells (~123 km) searched around the player for volcanoes: about 550 km. */
    private static final int VOLCANO_RINGS = 4;

    private static void findVolcano(CommandSourceStack source, MinecraftServer server, String word, String name,
                                    double pi, double pj, int scale) {
        Thread worker = new Thread(() -> {
            try {
                OceanFeatures.Landmark l = LocalTerrainProvider.findNearestVolcano(word, pi, pj, VOLCANO_RINGS, reached -> {
                    int km = Math.round(reached * 30 / 1000f);
                    server.execute(() -> source.sendSuccess(() -> Component.literal("…searched about " + km + " km around you")
                            .withStyle(ChatFormatting.GRAY), false));
                });
                server.execute(() -> {
                    if (l == null) {
                        source.sendFailure(Component.literal("No " + name + " within about "
                                + Math.round((VOLCANO_RINGS + 0.5f) * com.github.xandergos.terraindiffusionmc.pipeline.Landforms.cellPx() * 30 / 1000f)
                                + (word.equals("geothermal_basin") ? " km. Geothermal basins form in wide basins among cool, forested mountains (1200-3200 m)."
                                : word.equals("mirage_isles") ? " km. Mirage isles stand in warm, deep open ocean far from any land."
                                : " km. Stratovolcanoes need prominent mountain peaks, shields broad, gentle high ground.")));
                        return;
                    }
                    report(source, name, l, pi, pj, scale);
                });
            } catch (Exception e) {
                LOG.error("terra-find volcano failed", e);
                server.execute(() -> source.sendFailure(Component.literal("terra-find failed: " + e.getMessage())));
            }
        }, "terra-find");
        worker.setDaemon(true);
        worker.start();
    }

    /** Farthest reef or bank search (600 km) and most spots whose terrain is computed. */
    private static final int SHALLOW_RADIUS_PX = 20_000, SHALLOW_MAX_CHECKS = 25;

    /**
     * Reefs and banks depend on the model's own sea depths, so likely spots come from the coarse map and the terrain is
     * computed around the nearest ones (a few seconds each the first time) until one has the feature.
     */
    private static void findShallow(CommandSourceStack source, MinecraftServer server, String word, String name,
                                    double pi, double pj, int scale) {
        Thread worker = new Thread(() -> {
            try {
                LocalTerrainProvider.ShallowSearch res = LocalTerrainProvider.findNearestShallow(word, pi, pj,
                        SHALLOW_RADIUS_PX, SHALLOW_MAX_CHECKS, reachedPx -> {
                            int km = Math.round(reachedPx * 30 / 1000f);
                            server.execute(() -> source.sendSuccess(() -> Component.literal("…searched about " + km
                                    + " km around you").withStyle(ChatFormatting.GRAY), false));
                        });
                server.execute(() -> {
                    if (!res.found()) {
                        int km = Math.round(res.searchedRadiusPx() * 30 / 1000f);
                        String hint = switch (word) {
                            case "fringing_reef", "barrier_reef" -> "Reefs need warm water (about 24 °C); try a tropical coast.";
                            case "carbonate_bank" -> "Banks need a warm archipelago sea.";
                            case "submarine_canyon" -> "Canyons cut continental slopes on about half the margins.";
                            case "deep_sea_fan" -> "Fans lie on the deep rise below continental slopes.";
                            case "glacial_trough" -> "Troughs cross cold shelves (sea about 3 °C or colder); try a polar coast.";
                            case "strandflat" -> "Strandflats line patches of cold coast (about 4 °C or colder); try another cold coast.";
                            default -> "These lie on continental shelves and slopes; try a different coast.";
                        };
                        source.sendFailure(Component.literal("No " + name + " found (checked " + res.checked()
                                + " likely spots within about " + km + " km). " + hint));
                        return;
                    }
                    report(source, name, new OceanFeatures.Landmark(word, res.i(), res.j(), 0f), pi, pj, scale);
                });
            } catch (Exception e) {
                LOG.error("terra-find " + word + " failed", e);
                server.execute(() -> source.sendFailure(Component.literal("terra-find failed: " + e.getMessage())));
            }
        }, "terra-find");
        worker.setDaemon(true);
        worker.start();
    }

    /** Rings of river regions (7.7 km each) searched for deltas around the player. */
    private static final int DELTA_RINGS = 3;

    /**
     * Deltas come from the river hydrology, not the ocean feature map, so they are searched for region by region near
     * the player (about 27 km at most with 7.7 km regions), with progress in chat since each region takes a few seconds the first time.
     */
    private static void findDelta(CommandSourceStack source, MinecraftServer server, double pi, double pj, int scale) {
        Thread worker = new Thread(() -> {
            try {
                LocalTerrainProvider.DeltaSearch res = LocalTerrainProvider.findNearestDelta(pi, pj, DELTA_RINGS, reachedPx -> {
                    if (reachedPx > 0) {
                        int km = Math.round(reachedPx * 30 / 1000f);
                        server.execute(() -> source.sendSuccess(() -> Component.literal("…searched about " + km + " km around you")
                                .withStyle(ChatFormatting.GRAY), false));
                    }
                });
                server.execute(() -> reportDelta(source, res, pi, pj, scale));
            } catch (Exception e) {
                LOG.error("terra-find delta failed", e);
                server.execute(() -> source.sendFailure(Component.literal("terra-find failed: " + e.getMessage())));
            }
        }, "terra-find");
        worker.setDaemon(true);
        worker.start();
    }

    /** Rings of coastal regions (7.7 km each) searched for sand landforms. */
    private static final int COAST_RINGS = 3;

    private static void findCoast(CommandSourceStack source, MinecraftServer server, int k, String name, double pi, double pj, int scale) {
        Thread worker = new Thread(() -> {
            try {
                LocalTerrainProvider.CoastSearch res = LocalTerrainProvider.findNearestCoastFeature(k, pi, pj, COAST_RINGS, reachedPx -> {
                    int km = Math.round(reachedPx * 30 / 1000f);
                    server.execute(() -> source.sendSuccess(() -> Component.literal("…searched about " + km + " km around you")
                            .withStyle(ChatFormatting.GRAY), false));
                });
                server.execute(() -> {
                    int searchedKm = Math.round(res.searchedRadiusPx() * 30 / 1000f);
                    if (res.nearest() == null) {
                        source.sendFailure(Component.literal("No " + name + " within about " + searchedKm + " km. These form on low, "
                                + "sandy coasts with shallow water, in zones a few hundred km across; try another lowland coast."));
                        // Why: regions in zones, then why their shore did not grow spits (also in the log, to send back).
                        StringBuilder why = new StringBuilder("Searched " + res.regions() + " coastal regions: " + res.zoneRegions()
                                + " in sand zones, " + res.shoreRegions() + " of those with shore.");
                        if (k == CoastPlan.SPIT) {
                            int[] st = res.spitStats();
                            for (int q = 0; q < st.length; q++) why.append(q == 0 ? " " : "; ").append(CoastPlan.SPIT_REASONS[q]).append(": ").append(st[q]);
                        }
                        LOG.info("[Terra] terra-find {}: {}", name, why);
                        source.sendSystemMessage(Component.literal(why.toString()).withStyle(ChatFormatting.GRAY));
                        return;
                    }
                    report(source, name, new OceanFeatures.Landmark(name, res.nearest().i(), res.nearest().j(), 0f), pi, pj, scale);
                });
            } catch (Exception e) {
                LOG.error("terra-find " + name + " failed", e);
                server.execute(() -> source.sendFailure(Component.literal("terra-find failed: " + e.getMessage())));
            }
        }, "terra-find");
        worker.setDaemon(true);
        worker.start();
    }

    private static void reportDelta(CommandSourceStack source, LocalTerrainProvider.DeltaSearch res, double pi, double pj, int scale) {
        int searchedKm = Math.round(res.searchedRadiusPx() * 30 / 1000f);
        LocalTerrainProvider.Delta d = res.nearest();
        if (d == null) {
            source.sendFailure(Component.literal("No river delta within about " + searchedKm + " km. Deltas form where a river "
                    + "crosses a low, flat coastal plain (under 15 m for its last 900 m) to a shallow sea; try a lowland coast."));
            return;
        }
        long x = Math.round((d.mouthJ() + 0.5) * scale), z = Math.round((d.mouthI() + 0.5) * scale);
        double distPx = Math.hypot(d.mouthI() - pi, d.mouthJ() - pj);
        String km = String.format("%.1f", distPx * 30 / 1000);
        String tp = "/tp @s " + x + " ~ " + z;
        MutableComponent teleport = Component.literal("[Teleport]").withStyle(s -> s
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.SuggestCommand(tp))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(tp))));
        MutableComponent copy = Component.literal("[Copy]").withStyle(s -> s
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.CopyToClipboard(x + " ~ " + z))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy the coordinates"))));
        String kind = d.riverClass() >= 3 ? "main river" : "river";
        long hx = Math.round((d.headJ() + 0.5) * scale), hz = Math.round((d.headI() + 0.5) * scale);
        int found = res.count();
        source.sendSuccess(() -> Component.literal("Nearest river delta (" + kind + "): mouth at " + x + ", " + z + " (" + km
                        + " km); it splits at " + hx + ", " + hz + ". " + found + " delta" + (found == 1 ? "" : "s")
                        + " within about " + searchedKm + " km. ")
                .append(teleport).append(" ").append(copy), false);
    }

    private static void report(CommandSourceStack source, String name, OceanFeatures.Landmark l, double pi, double pj, int scale) {
        if (l == null) {
            source.sendFailure(Component.literal("No " + name + " within " + (MAX_RADIUS_PX * 30 / 1000) + " km."));
            return;
        }
        long x = Math.round(l.j() * scale), z = Math.round(l.i() * scale);
        double distPx = Math.hypot(l.i() - pi, l.j() - pj);
        long blocks = Math.round(distPx * scale);
        String km = String.format("%.1f", distPx * 30 / 1000);
        String tp = "/tp @s " + x + " ~ " + z;
        MutableComponent teleport = Component.literal("[Teleport]").withStyle(s -> s
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent.SuggestCommand(tp))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(tp))));
        MutableComponent copy = Component.literal("[Copy]").withStyle(s -> s
                .withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.CopyToClipboard(x + " ~ " + z))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy the coordinates"))));
        String detail = describe(l);
        source.sendSuccess(() -> Component.literal("Nearest " + name + ": " + x + ", " + z + " (" + blocks + " blocks, "
                        + km + " km" + (detail.isEmpty() ? "" : "; " + detail) + ") ")
                .append(teleport).append(" ").append(copy), false);
    }

    private static String describe(OceanFeatures.Landmark l) {
        int v = Math.round(l.value());
        return switch (l.type()) {
            case "volcanic_island", "arc_island", "flooded_caldera", "barrier_reef_island" -> "peak " + v + " m";
            case "landslide_scar" -> "on an island " + v + " m high";
            case "stratovolcano", "shield_volcano", "caldera" -> "summit about " + v + " m";
            case "active_island" -> "lava lake in the summit crater, lava flows to the coast; peak about " + v + " m";
            case "geothermal_basin" -> "hot springs on the basin floor, about " + v + " m up";
            case "mirage_isles" -> v + " cliff-walled isles";
            case "atoll" -> "reef at sea level";
            case "raised_coral_island" -> "old reef rim about " + v + " m up";
            case "aseismic_ridge" -> "crest about " + (-v) + " m down";
            case "tuff_ring" -> v >= 1000 ? "a maar with a lake, rim about " + (v - 1000) + " m high"
                    : (v < 0 ? "breached and flooded by the sea, rim about " + (-v) + " m high"
                    : "breached toward the sea, rim about " + v + " m high");
            case "oceanic_plateau" -> "top about " + (-v) + " m down";
            case "forearc_island" -> "low limestone island, peak " + v + " m";
            case "back_arc_basin" -> "axis about " + (-v) + " m down";
            case "mud_volcano" -> "cone about " + v + " m high, on the slope; it only forms 400-3000 m down";
            case "guyot", "seamount" -> "summit " + v + " m";
            case "trench" -> "floor about " + v + " m";
            case "vent_field" -> "on the ridge axis about " + (-v) + " m down";
            case "rift_sea" -> "the middle of a narrow rift sea, axial trough planned about " + (-v) + " m deep";
            case "active_margin" -> "over the trench, about " + (-v) + " m down; the coastal range is inland";
            default -> "";
        };
    }
}
