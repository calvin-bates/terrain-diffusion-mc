package com.github.xandergos.terraindiffusionmc.world;

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
 * {@code /terra-find <feature>}: the nearest ocean feature of a kind (volcanic island, island-arc island, atoll,
 * guyot, seamount, trench or archipelago sea), with a click-to-teleport. Uses the same placement rules as the terrain
 * ({@link OceanFeatures#landmarks}), so it can point at places that are not generated yet. The search runs off the
 * server thread because far-away searches compute the coarse map.
 */
public final class FeatureFinder {
    private static final Logger LOG = LoggerFactory.getLogger(FeatureFinder.class);

    /** Command word → landmark type and display name. */
    private static final Map<String, String[]> KINDS = new LinkedHashMap<>();
    static {
        KINDS.put("volcanic_island", new String[]{"volcanic_island", "volcanic island"});
        KINDS.put("arc_island", new String[]{"arc_island", "island-arc island"});
        KINDS.put("atoll", new String[]{"atoll", "atoll"});
        KINDS.put("guyot", new String[]{"guyot", "guyot"});
        KINDS.put("seamount", new String[]{"seamount", "seamount"});
        KINDS.put("trench", new String[]{"trench", "trench"});
        KINDS.put("archipelago", new String[]{"archipelago", "archipelago sea"});
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
            case "volcanic_island", "arc_island" -> "peak " + v + " m";
            case "atoll" -> "reef at sea level";
            case "guyot", "seamount" -> "summit " + v + " m";
            case "trench" -> "floor about " + v + " m";
            default -> "";
        };
    }
}
