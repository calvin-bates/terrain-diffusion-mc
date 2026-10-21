package com.github.xandergos.terraindiffusionmc.world;

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
 * reef or carbonate bank, with a click-to-teleport. Ocean features use the same placement rules as the terrain
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
        KINDS.put("lava_delta", new String[]{"lava_delta", "lava delta"});
        KINDS.put("oceanic_plateau", new String[]{"oceanic_plateau", "oceanic plateau"});
        KINDS.put("forearc_island", new String[]{"forearc_island", "forearc island"});
        KINDS.put("back_arc_basin", new String[]{"back_arc_basin", "back-arc basin spreading axis"});
        KINDS.put("mud_volcano", new String[]{"mud_volcano", "mud volcano"});
        KINDS.put("guyot", new String[]{"guyot", "guyot"});
        KINDS.put("seamount", new String[]{"seamount", "seamount"});
        KINDS.put("trench", new String[]{"trench", "trench"});
        KINDS.put("archipelago", new String[]{"archipelago", "archipelago sea"});
        KINDS.put("vent_field", new String[]{"vent_field", "hydrothermal vent field"});
        KINDS.put("delta", new String[]{"delta", "river delta"});
        KINDS.put("ria", new String[]{"ria", "ria (drowned river valley)"});
        KINDS.put("spit", new String[]{"spit", "sand spit"});
        KINDS.put("tombolo", new String[]{"tombolo", "tombolo"});
        KINDS.put("cuspate_foreland", new String[]{"cuspate_foreland", "cuspate foreland"});
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
    }

    /** Farthest search, in native pixels (1200 km). */
    private static final int MAX_RADIUS_PX = 40_000;

    private FeatureFinder() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(literal("terra-find")
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
        if (word.equals("ria")) {
            findRia(source, server, pi, pj, scale);
            return 1;
        }
        if (word.equals("spit") || word.equals("tombolo") || word.equals("cuspate_foreland")) {
            int k = word.equals("spit") ? CoastPlan.SPIT : (word.equals("tombolo") ? CoastPlan.TOMBOLO : CoastPlan.FORELAND);
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

    /** Rias come from the river hydrology, like deltas, so they are searched for region by region near the player. */
    private static void findRia(CommandSourceStack source, MinecraftServer server, double pi, double pj, int scale) {
        Thread worker = new Thread(() -> {
            try {
                LocalTerrainProvider.RiaSearch res = LocalTerrainProvider.findNearestRia(pi, pj, DELTA_RINGS, reachedPx -> {
                    int km = Math.round(reachedPx * 30 / 1000f);
                    server.execute(() -> source.sendSuccess(() -> Component.literal("…searched about " + km + " km around you")
                            .withStyle(ChatFormatting.GRAY), false));
                });
                server.execute(() -> {
                    int searchedKm = Math.round(res.searchedRadiusPx() * 30 / 1000f);
                    LocalTerrainProvider.Ria r = res.nearest();
                    if (r == null) {
                        source.sendFailure(Component.literal("No ria within about " + searchedKm + " km. Rias form where a river "
                                + "reaches the sea on a warm (above about 8 °C), hilly coast; about a third of those mouths are drowned."));
                        return;
                    }
                    long hx = Math.round(r.headJ() * scale), hz = Math.round(r.headI() * scale);
                    int found = res.count();
                    report(source, "ria (mouth; the inlet runs up the valley toward " + hx + ", " + hz + "; " + found + " within about "
                            + searchedKm + " km)", new OceanFeatures.Landmark("ria", r.mouthI(), r.mouthJ(), 0f), pi, pj, scale);
                });
            } catch (Exception e) {
                LOG.error("terra-find ria failed", e);
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
            case "atoll" -> "reef at sea level";
            case "raised_coral_island" -> "old reef rim about " + v + " m up";
            case "aseismic_ridge" -> "crest about " + (-v) + " m down";
            case "tuff_ring" -> v >= 1000 ? "a maar with a lake, rim about " + (v - 1000) + " m high"
                    : (v < 0 ? "breached and flooded by the sea, rim about " + (-v) + " m high"
                    : "breached toward the sea, rim about " + v + " m high");
            case "lava_delta" -> "new land about " + v + " m above the sea, with live lava on its channel";
            case "oceanic_plateau" -> "top about " + (-v) + " m down";
            case "forearc_island" -> "low limestone island, peak " + v + " m";
            case "back_arc_basin" -> "axis about " + (-v) + " m down";
            case "mud_volcano" -> "cone about " + v + " m high, on the slope; it only forms 400-3000 m down";
            case "guyot", "seamount" -> "summit " + v + " m";
            case "trench" -> "floor about " + v + " m";
            case "vent_field" -> "on the ridge axis about " + (-v) + " m down";
            default -> "";
        };
    }
}
