package com.github.xandergos.terraindiffusionmc.explorer;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.pipeline.BiomeClassifier;
import com.github.xandergos.terraindiffusionmc.pipeline.BiomeMap;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.OceanFeatures;
import com.github.xandergos.terraindiffusionmc.pipeline.WorldPipelineModelConfig;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embedded terrain explorer HTTP server. Java port of
 * terrain_diffusion/inference/explorer/server.py.
 *
 * <p>Bound to 127.0.0.1 only. All pipeline calls are routed through
 * LocalTerrainProvider's inference thread for thread safety.
 */
public final class ExplorerServer {

    private static final Logger LOG = LoggerFactory.getLogger(ExplorerServer.class);
    private static final Gson GSON = new Gson();

    private static final String[] CHANNEL_NAMES = {"Elev", "p5", "Temp", "T std", "Precip", "Precip CV"};
    private static final float NATIVE_RESOLUTION = WorldPipelineModelConfig.nativeResolution();

    private static volatile HttpServer SERVER;
    private static volatile int SERVER_PORT = -1;

    private ExplorerServer() {}

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Start the server if not already running. Returns the port.
     */
    public static synchronized int startIfNotRunning() throws IOException {
        if (SERVER != null) return SERVER_PORT;
        int port = TerrainDiffusionConfig.explorerPort();
        InetSocketAddress addr = new InetSocketAddress("127.0.0.1", port);
        HttpServer server = HttpServer.create(addr, 0);
        server.createContext("/", ExplorerServer::handleRoot);
        server.createContext("/api/status", ExplorerServer::handleStatus);
        server.createContext("/api/seed", ExplorerServer::handleSeed);
        server.createContext("/api/new_seed", ExplorerServer::handleNewSeed);
        server.createContext("/api/coarse.png", ExplorerServer::handleCoarsePng);
        server.createContext("/api/coarse_data.json", ExplorerServer::handleCoarseData);
        server.createContext("/api/coarse_stats", ExplorerServer::handleCoarseStats);
        server.createContext("/api/detail.png", ExplorerServer::handleDetailPng);
        server.createContext("/api/detail_raw", ExplorerServer::handleDetailRaw);
        server.createContext("/api/features", ExplorerServer::handleFeatures);
        server.createContext("/api/detail_biomes", ExplorerServer::handleDetailBiomes);
        server.createContext("/api/biome_palette", ExplorerServer::handleBiomePalette);
        // Single-thread executor matches Python's threaded=False
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "terrain-explorer-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        SERVER = server;
        SERVER_PORT = port;
        LOG.info("Terrain explorer started at http://127.0.0.1:{}", port);
        return port;
    }

    public static synchronized void stop() {
        if (SERVER != null) {
            SERVER.stop(0);
            SERVER = null;
            SERVER_PORT = -1;
            LOG.info("Terrain explorer stopped.");
        }
    }

    public static boolean isRunning() {
        return SERVER != null;
    }

    public static int getPort() {
        return SERVER_PORT;
    }

    // =========================================================================
    // Handlers — direct port of server.py routes
    // =========================================================================

    /** GET / → serve index.html */
    private static void handleRoot(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try (InputStream in = ExplorerServer.class.getResourceAsStream(
                "/assets/terra/explorer/index.html")) {
            if (in == null) {
                sendError(ex, 404, "index.html not found");
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
        } finally {
            ex.close();
        }
    }

    /** GET /api/status → {seed, channels, native_resolution, scale} */
    private static void handleStatus(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("seed", Long.toUnsignedString(LocalTerrainProvider.getSeed()));
            resp.put("channels", Arrays.asList(CHANNEL_NAMES));
            resp.put("native_resolution", NATIVE_RESOLUTION);
            resp.put("scale", WorldScaleManager.getCurrentScale());
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 500, e.getMessage());
        }
    }

    /**
     * GET /api/features?i0=&j0=&i1=&j1= (native pixels) → {features:[{type, i, j, x, z, value}]}: the ocean features
     * centred in the window (volcanic and arc islands, atolls, guyots, seamounts, trenches, archipelago seas), with
     * x/z in blocks at the world's scale.
     */
    private static void handleFeatures(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int i0 = getInt(q, "i0", -12800), j0 = getInt(q, "j0", -12800);
            int i1 = getInt(q, "i1", 12800), j1 = getInt(q, "j1", 12800);
            if (i1 <= i0 || j1 <= j0 || (long) (i1 - i0) * (j1 - j0) > 400_000L * 400_000L) {
                sendError(ex, 400, "bad window");
                return;
            }
            int scale = Math.max(1, WorldScaleManager.getCurrentScale());
            List<Map<String, Object>> list = new ArrayList<>();
            List<OceanFeatures.Landmark> marks = new ArrayList<>(LocalTerrainProvider.getOceanLandmarks(i0, j0, i1, j1));
            marks.addAll(LocalTerrainProvider.getInletLandmarks(i0, j0, i1, j1));
            for (OceanFeatures.Landmark l : marks) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", l.type());
                m.put("i", l.i());
                m.put("j", l.j());
                m.put("x", Math.round(l.j() * scale));
                m.put("z", Math.round(l.i() * scale));
                m.put("value", Math.round(l.value() * 100f) / 100f);
                list.add(m);
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("features", list);
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            LOG.error("features error", e);
            sendError(ex, 500, String.valueOf(e.getMessage()));
        }
    }

    /** POST /api/seed body={seed:int} → {seed} */
    private static void handleSeed(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send405(ex); return; }
        try {
            String body = readBody(ex, 1024);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = GSON.fromJson(body, Map.class);
            if (!data.containsKey("seed")) { sendError(ex, 400, "seed required"); return; }
            long newSeed = ((Number) data.get("seed")).longValue();
            LocalTerrainProvider.changeSeedFromExplorer(newSeed);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("seed", Long.toUnsignedString(LocalTerrainProvider.getSeed()));
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /** POST /api/new_seed → {seed} */
    private static void handleNewSeed(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) { send405(ex); return; }
        try {
            long newSeed = LocalTerrainProvider.generateRandomSeedFromExplorer();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("seed", Long.toUnsignedString(newSeed));
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /**
     * GET /api/coarse.png — port of coarse_png() + _coarse_channel().
     * Query params: channel, ci0, ci1, cj0, cj1, ch{0,2,3,4,5}_min/max
     * Response headers: X-Vmin, X-Vmax
     */
    private static void handleCoarsePng(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int channel = getInt(q, "channel", 0);
            int ci0 = getInt(q, "ci0", -50), ci1 = getInt(q, "ci1", 50);
            int cj0 = getInt(q, "cj0", -50), cj1 = getInt(q, "cj1", 50);

            float[] data = coarseChannel(ci0, ci1, cj0, cj1, channel);
            int H = ci1 - ci0, W = cj1 - cj0;

            // Precipitation: log1p(max(v,0)) before normalizing (matches Python)
            float[] display = data.clone();
            if (channel == 4) {
                for (int i = 0; i < display.length; i++)
                    display[i] = (float) Math.log1p(Math.max(0f, display[i]));
            }
            float vmin = nanMin(display), vmax = nanMax(display);
            if (vmax == vmin) vmax = vmin + 1f;

            // Viridis colormap
            float[][] rgba = new float[4][H * W];
            for (int i = 0; i < H * W; i++) {
                float t = (display[i] - vmin) / (vmax - vmin);
                float[] rgb = Colormaps.viridis(clamp01(t));
                rgba[0][i] = rgb[0]; rgba[1][i] = rgb[1]; rgba[2][i] = rgb[2]; rgba[3][i] = 1f;
            }

            // Optional filter: dim non-matching pixels to 30% (matches Python rgba[~mask, :3] *= 0.3)
            int[] filterChs = {0, 2, 3, 4, 5};
            boolean filterActive = false;
            for (int ch : filterChs) {
                if (q.containsKey("ch" + ch + "_min") || q.containsKey("ch" + ch + "_max")) {
                    filterActive = true; break;
                }
            }
            if (filterActive) {
                boolean[] mask = new boolean[H * W];
                Arrays.fill(mask, true);
                for (int ch : filterChs) {
                    Float lo = getFloat(q, "ch" + ch + "_min");
                    Float hi = getFloat(q, "ch" + ch + "_max");
                    if (lo == null && hi == null) continue;
                    float[] chData = coarseChannel(ci0, ci1, cj0, cj1, ch);
                    for (int i = 0; i < H * W; i++) {
                        if (lo != null && chData[i] < lo) mask[i] = false;
                        if (hi != null && chData[i] > hi) mask[i] = false;
                    }
                }
                for (int i = 0; i < H * W; i++) {
                    if (!mask[i]) {
                        rgba[0][i] *= 0.3f; rgba[1][i] *= 0.3f; rgba[2][i] *= 0.3f;
                    }
                }
            }

            byte[] png = toPng(rgba, H, W);
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("X-Vmin", String.format("%.3f", vmin));
            ex.getResponseHeaders().set("X-Vmax", String.format("%.3f", vmax));
            ex.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Vmin, X-Vmax");
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
        } catch (Exception e) {
            LOG.error("coarse.png error", e);
            sendError(ex, 400, e.getMessage());
        } finally {
            ex.close();
        }
    }

    /**
     * GET /api/coarse_data.json — port of coarse_data().
     * Returns all 6 channel values as 2D arrays for client-side hover.
     */
    private static void handleCoarseData(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci0 = getInt(q, "ci0", -50), ci1 = getInt(q, "ci1", 50);
            int cj0 = getInt(q, "cj0", -50), cj1 = getInt(q, "cj1", 50);
            int H = ci1 - ci0, W = cj1 - cj0;

            Map<String, Object> channels = new LinkedHashMap<>();
            for (int ch = 0; ch < CHANNEL_NAMES.length; ch++) {
                float[] flat = coarseChannel(ci0, ci1, cj0, cj1, ch);
                channels.put(CHANNEL_NAMES[ch], roundedGrid(flat, H, W, 2));
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ci0", ci0); resp.put("ci1", ci1);
            resp.put("cj0", cj0); resp.put("cj1", cj1);
            resp.put("channels", channels);
            sendJson(ex, 200, resp);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /** GET /api/coarse_stats — port of coarse_stats(). */
    private static void handleCoarseStats(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci0 = getInt(q, "ci0", -50), ci1 = getInt(q, "ci1", 50);
            int cj0 = getInt(q, "cj0", -50), cj1 = getInt(q, "cj1", 50);

            Map<String, Object> stats = new LinkedHashMap<>();
            for (int ch = 0; ch < CHANNEL_NAMES.length; ch++) {
                float[] data = coarseChannel(ci0, ci1, cj0, cj1, ch);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", CHANNEL_NAMES[ch]);
                entry.put("min", round3(nanMin(data)));
                entry.put("max", round3(nanMax(data)));
                stats.put(String.valueOf(ch), entry);
            }
            sendJson(ex, 200, stats);
        } catch (Exception e) {
            sendError(ex, 400, e.getMessage());
        }
    }

    /** Relief map with rivers overlaid. Hydrology runs on a window with a margin around (i1, j1, H, W). */
    private static float getFloat(Map<String, String> q, String key, float def) {
        try { return Float.parseFloat(q.getOrDefault(key, "")); } catch (Exception e) { return def; }
    }

    /** River and lake classes (RiverMap.NONE..LAKE) and elevation over a window, computed with a 192 px margin. */
    private record RiverWindow(byte[] cls, float[] elev) {
    }

    private static RiverWindow riverClasses(int i1, int j1, int H, int W,
            com.github.xandergos.terraindiffusionmc.hydrology.RiverMap.Params params) throws Exception {
        final int margin = 192;
        int HH = H + 2 * margin, WW = W + 2 * margin;
        float[][] out = LocalTerrainProvider.getPipelineData(
                i1 - margin, j1 - margin, i1 + H + margin, j1 + W + margin, true);
        float[] elevBig = out[0];
        float[] climateBig = out[1];
        byte[] clsBig = com.github.xandergos.terraindiffusionmc.hydrology.RiverMap.compute(
                elevBig, climateBig, HH, WW, i1 - margin, j1 - margin, params);
        float[] elev = new float[H * W];
        byte[] cls = new byte[H * W];
        for (int r = 0; r < H; r++) {
            System.arraycopy(elevBig, (r + margin) * WW + margin, elev, r * W, W);
            System.arraycopy(clsBig, (r + margin) * WW + margin, cls, r * W, W);
        }
        return new RiverWindow(cls, elev);
    }

    private static byte[] riversPng(int i1, int j1, int H, int W,
            com.github.xandergos.terraindiffusionmc.hydrology.RiverMap.Params params) throws Exception {
        RiverWindow rw = riverClasses(i1, j1, H, W, params);
        float[][] relief = ReliefMap.getReliefMap(rw.elev(), H, W, 90.0);
        float[][] rgba = new float[4][H * W];
        for (int i = 0; i < H * W; i++) {
            float cr = relief[0][i], cg = relief[1][i], cb = relief[2][i];
            byte k = rw.cls()[i];
            if (k == 1) { cr = 0.50f; cg = 0.69f; cb = 1.00f; }
            else if (k == 2) { cr = 0.18f; cg = 0.48f; cb = 1.00f; }
            else if (k == 3) { cr = 0.00f; cg = 0.90f; cb = 1.00f; }
            else if (k == 4) { cr = 0.08f; cg = 0.28f; cb = 0.80f; }
            rgba[0][i] = cr; rgba[1][i] = cg; rgba[2][i] = cb; rgba[3][i] = 1f;
        }
        return toPng(rgba, H, W);
    }

    // =========================================================================
    // Biomes map (biomes plan v1.1)
    // =========================================================================

    /** One computed biome window: ids, inland water (rivers and lakes) and elevation. */
    private record BiomeView(long seed, int i1, int j1, int H, int W, short[] biomes, boolean[] water, float[] elev) {
    }

    /** The last biome window, so the image and the raw ids for hover and legend are computed once. */
    private static volatile BiomeView lastBiomeView;

    private static BiomeView biomeView(int i1, int j1, int H, int W) throws Exception {
        long seed = LocalTerrainProvider.getSeed();
        BiomeView v = lastBiomeView;
        if (v != null && v.seed() == seed && v.i1() == i1 && v.j1() == j1 && v.H() == H && v.W() == W) return v;
        BiomeMap.Result res = LocalTerrainProvider.getBiomeMap(i1, j1, i1 + H, j1 + W);
        RiverWindow rw = riverClasses(i1, j1, H, W, new com.github.xandergos.terraindiffusionmc.hydrology.RiverMap.Params());
        boolean[] water = new boolean[H * W];
        for (int i = 0; i < H * W; i++) {
            water[i] = rw.cls()[i] != com.github.xandergos.terraindiffusionmc.hydrology.RiverMap.NONE
                    && !BiomeClassifier.isOceanBiome(res.biomes()[i]);
        }
        v = new BiomeView(seed, i1, j1, H, W, res.biomes(), water, res.elev());
        lastBiomeView = v;
        return v;
    }

    /** Biome colours over a hillshade of the elevation, rivers and lakes on top. */
    private static byte[] biomesPng(BiomeView v) throws IOException {
        int H = v.H(), W = v.W();
        float[] e = v.elev();
        float[][] rgba = new float[4][H * W];
        int waterRgb = BiomePalette.rgb(BiomePalette.WATER);
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int i = r * W + c;
                // Hillshade from the north-west, 30 m pixels; sea flat.
                float zl = e[r * W + Math.max(0, c - 1)], zr = e[r * W + Math.min(W - 1, c + 1)];
                float zu = e[Math.max(0, r - 1) * W + c], zd = e[Math.min(H - 1, r + 1) * W + c];
                float dx = (Math.max(0f, zr) - Math.max(0f, zl)) / 60f, dz = (Math.max(0f, zd) - Math.max(0f, zu)) / 60f;
                float shade = (float) (0.88 - 0.9 * (dx + dz) * 0.7071 / Math.sqrt(1 + dx * dx + dz * dz));
                shade = Math.max(0.45f, Math.min(1.12f, shade));
                int rgb = v.water()[i] ? waterRgb : BiomePalette.rgb(v.biomes()[i]);
                if (v.water()[i]) shade = 0.5f * shade + 0.5f;
                rgba[0][i] = Math.min(1f, ((rgb >> 16) & 255) / 255f * shade);
                rgba[1][i] = Math.min(1f, ((rgb >> 8) & 255) / 255f * shade);
                rgba[2][i] = Math.min(1f, (rgb & 255) / 255f * shade);
                rgba[3][i] = 1f;
            }
        }
        return toPng(rgba, H, W);
    }

    /**
     * GET /api/detail_biomes — same window parameters as detail.png. Binary: H*W biome ids (uint8), then H*W water
     * flags (1 where a river or lake covers the pixel). Headers X-Height, X-Width.
     */
    private static void handleDetailBiomes(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int detailSize = getInt(q, "detail_size", 1024);
            int centerI = getInt(q, "ci", 0) * 256 + getInt(q, "pan_i", 0);
            int centerJ = getInt(q, "cj", 0) * 256 + getInt(q, "pan_j", 0);
            int half = detailSize / 2;
            BiomeView v = biomeView(centerI - half, centerJ - half, detailSize, detailSize);
            int n = v.H() * v.W();
            byte[] payload = new byte[2 * n];
            for (int i = 0; i < n; i++) {
                payload[i] = (byte) v.biomes()[i];
                payload[n + i] = (byte) (v.water()[i] ? 1 : 0);
            }
            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().set("X-Height", String.valueOf(v.H()));
            ex.getResponseHeaders().set("X-Width", String.valueOf(v.W()));
            ex.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Height, X-Width");
            ex.sendResponseHeaders(200, payload.length);
            ex.getResponseBody().write(payload);
        } catch (Exception e) {
            LOG.error("detail_biomes error", e);
            sendError(ex, 400, String.valueOf(e.getMessage()));
        } finally {
            ex.close();
        }
    }

    /** GET /api/biome_palette → {id: {name, color}} for every biome id the map can show. */
    private static void handleBiomePalette(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        sendJson(ex, 200, BiomePalette.asJson());
    }

    /**
     * GET /api/detail.png — port of detail_png().
     * Query params: ci, cj, detail_size, pan_i, pan_j, mode
     */
    private static void handleDetailPng(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci         = getInt(q, "ci", 0);
            int cj         = getInt(q, "cj", 0);
            int detailSize = getInt(q, "detail_size", 1024);
            int panI       = getInt(q, "pan_i", 0);
            int panJ       = getInt(q, "pan_j", 0);
            String mode    = q.getOrDefault("mode", "relief");

            int centerI = ci * 256 + panI;
            int centerJ = cj * 256 + panJ;
            int half    = detailSize / 2;

            int H = detailSize, W = detailSize;

            if (mode.equals("biomes")) {
                byte[] png = biomesPng(biomeView(centerI - half, centerJ - half, H, W));
                ex.getResponseHeaders().set("Content-Type", "image/png");
                ex.sendResponseHeaders(200, png.length);
                ex.getResponseBody().write(png);
                return;
            }

            if (mode.equals("rivers")) {
                var params = new com.github.xandergos.terraindiffusionmc.hydrology.RiverMap.Params();
                params.streamQ = getFloat(q, "sq", params.streamQ);
                params.riverQ  = getFloat(q, "rq", params.riverQ);
                params.mainQ   = getFloat(q, "mq", params.mainQ);
                params.lakeMinArea  = (int) getFloat(q, "la", params.lakeMinArea);
                params.lakeMinDepth = getFloat(q, "ld", params.lakeMinDepth);
                byte[] png = riversPng(centerI - half, centerJ - half, H, W, params);
                ex.getResponseHeaders().set("Content-Type", "image/png");
                ex.sendResponseHeaders(200, png.length);
                ex.getResponseBody().write(png);
                return;
            }

            float[][] out = LocalTerrainProvider.getPipelineData(
                    centerI - half, centerJ - half, centerI + half, centerJ + half,
                    mode.equals("temperature"));
            float[] elevFlat  = out[0];
            float[] climate   = out[1];

            float[][] rgba;
            if (mode.equals("elevation")) {
                float vmin = nanMin(elevFlat), vmax = nanMax(elevFlat);
                if (vmax == vmin) vmax = vmin + 1f;
                rgba = applyColormap1D(elevFlat, H, W, vmin, vmax, "terrain");
            } else if (mode.equals("temperature") && climate != null) {
                // climate[0] = temperature channel (H*W floats)
                float[] temp = Arrays.copyOfRange(climate, 0, H * W);
                float vmin = nanMin(temp), vmax = nanMax(temp);
                if (vmax == vmin) vmax = vmin + 1f;
                rgba = applyColormap1D(temp, H, W, vmin, vmax, "rdbu_r");
            } else {
                // relief mode (default)
                float[][] reliefRgb = ReliefMap.getReliefMap(elevFlat, H, W, 90.0);
                rgba = new float[4][H * W];
                for (int i = 0; i < H * W; i++) {
                    rgba[0][i] = reliefRgb[0][i];
                    rgba[1][i] = reliefRgb[1][i];
                    rgba[2][i] = reliefRgb[2][i];
                    rgba[3][i] = 1f;
                }
            }

            byte[] png = toPng(rgba, H, W);
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.sendResponseHeaders(200, png.length);
            ex.getResponseBody().write(png);
        } catch (Exception e) {
            LOG.error("detail.png error", e);
            sendError(ex, 400, e.getMessage());
        } finally {
            ex.close();
        }
    }

    /**
     * GET /api/detail_raw — port of detail_raw().
     * Binary: int16-LE elevation (H*W*2 bytes) + float32-LE temperature (H*W*4 bytes).
     * Headers: X-Height, X-Width, X-Has-Temp.
     */
    private static void handleDetailRaw(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) { send405(ex); return; }
        try {
            Map<String, String> q = parseQuery(ex.getRequestURI());
            int ci         = getInt(q, "ci", 0);
            int cj         = getInt(q, "cj", 0);
            int detailSize = getInt(q, "detail_size", 1024);
            int panI       = getInt(q, "pan_i", 0);
            int panJ       = getInt(q, "pan_j", 0);

            int centerI = ci * 256 + panI;
            int centerJ = cj * 256 + panJ;
            int half    = detailSize / 2;
            int H = detailSize, W = detailSize;

            float[][] out = LocalTerrainProvider.getPipelineData(
                    centerI - half, centerJ - half, centerI + half, centerJ + half, true);
            float[] elevFlat = out[0];
            float[] climate  = out[1];

            // Elevation → int16 LE (matching Python: clip(floor(elev), -32768, 32767).astype('<i2'))
            ByteBuffer elevBuf = ByteBuffer.allocate(H * W * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (float e : elevFlat) {
                short s = (short) Math.max(-32768, Math.min(32767, (int) Math.floor(e)));
                elevBuf.putShort(s);
            }

            boolean hasTemp = climate != null;
            byte[] payload;
            if (hasTemp) {
                // Temperature = climate[0..H*W] as float32 LE
                ByteBuffer tempBuf = ByteBuffer.allocate(H * W * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < H * W; i++) tempBuf.putFloat(climate[i]);
                payload = new byte[elevBuf.capacity() + tempBuf.capacity()];
                System.arraycopy(elevBuf.array(), 0, payload, 0, elevBuf.capacity());
                System.arraycopy(tempBuf.array(), 0, payload, elevBuf.capacity(), tempBuf.capacity());
            } else {
                payload = elevBuf.array();
            }

            ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
            ex.getResponseHeaders().set("X-Height", String.valueOf(H));
            ex.getResponseHeaders().set("X-Width", String.valueOf(W));
            ex.getResponseHeaders().set("X-Has-Temp", hasTemp ? "1" : "0");
            ex.getResponseHeaders().set("Access-Control-Expose-Headers", "X-Height, X-Width, X-Has-Temp");
            ex.sendResponseHeaders(200, payload.length);
            ex.getResponseBody().write(payload);
        } catch (Exception e) {
            LOG.error("detail_raw error", e);
            sendError(ex, 400, e.getMessage());
        } finally {
            ex.close();
        }
    }

    // =========================================================================
    // Coarse channel helper — port of _coarse_channel() in server.py
    // =========================================================================

    /**
     * Return the given channel of the coarse map in real units.
     * Channels 0 and 1: undo signed-sqrt (sign(v) * v^2).
     */
    private static float[] coarseChannel(int ci0, int ci1, int cj0, int cj1, int channel) throws Exception {
        FloatTensor slice = LocalTerrainProvider.getPipelineCoarse(ci0, cj0, ci1, cj1);
        int H = ci1 - ci0, W = cj1 - cj0;
        float[] result = new float[H * W];
        for (int i = 0; i < H * W; i++) {
            float w   = slice.data[6 * H * W + i];
            float raw = (w > 1e-8f) ? slice.data[channel * H * W + i] / w : 0f;
            // Channels 0 (elev) and 1 (p5): signed-sqrt → real units via sign(v)*v^2
            result[i] = (channel <= 1) ? (float) (Math.signum(raw) * raw * raw) : raw;
        }
        return result;
    }

    // =========================================================================
    // PNG rendering
    // =========================================================================

    /** Encode RGBA channels (float[4][H*W]) to a PNG byte array. */
    private static byte[] toPng(float[][] rgba, int H, int W) throws IOException {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int r = 0; r < H; r++) {
            for (int c = 0; c < W; c++) {
                int idx = r * W + c;
                int ri = (int) (clamp01(rgba[0][idx]) * 255f + 0.5f);
                int gi = (int) (clamp01(rgba[1][idx]) * 255f + 0.5f);
                int bi = (int) (clamp01(rgba[2][idx]) * 255f + 0.5f);
                int ai = (int) (clamp01(rgba[3][idx]) * 255f + 0.5f);
                img.setRGB(c, r, (ai << 24) | (ri << 16) | (gi << 8) | bi);
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static float[][] applyColormap1D(float[] data, int H, int W, float vmin, float vmax, String cmap) {
        float[][] rgba = new float[4][H * W];
        for (int i = 0; i < H * W; i++) {
            float t = (data[i] - vmin) / (vmax - vmin);
            float[] rgb;
            switch (cmap) {
                case "terrain": rgb = Colormaps.terrain(clamp01(t)); break;
                case "rdbu_r":  rgb = Colormaps.rdBuR(clamp01(t));   break;
                default:        rgb = Colormaps.viridis(clamp01(t)); break;
            }
            rgba[0][i] = rgb[0]; rgba[1][i] = rgb[1]; rgba[2][i] = rgb[2]; rgba[3][i] = 1f;
        }
        return rgba;
    }

    // =========================================================================
    // HTTP utilities
    // =========================================================================

    private static void sendJson(HttpExchange ex, int status, Object obj) throws IOException {
        byte[] body = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        ex.close();
    }

    private static void sendError(HttpExchange ex, int status, String msg) throws IOException {
        Map<String, String> err = new HashMap<>();
        err.put("error", msg != null ? msg : "unknown error");
        sendJson(ex, status, err);
    }

    private static void send405(HttpExchange ex) throws IOException {
        sendError(ex, 405, "Method Not Allowed");
    }

    private static String readBody(HttpExchange ex, int maxBytes) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = in.readNBytes(maxBytes);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> map = new HashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty()) return map;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                map.put(pair.substring(0, eq), pair.substring(eq + 1));
            } else {
                map.put(pair, "");
            }
        }
        return map;
    }

    // =========================================================================
    // Math utilities
    // =========================================================================

    private static float nanMin(float[] arr) {
        float min = Float.MAX_VALUE;
        for (float v : arr) if (!Float.isNaN(v) && v < min) min = v;
        return min == Float.MAX_VALUE ? 0f : min;
    }

    private static float nanMax(float[] arr) {
        float max = -Float.MAX_VALUE;
        for (float v : arr) if (!Float.isNaN(v) && v > max) max = v;
        return max == -Float.MAX_VALUE ? 0f : max;
    }

    private static float clamp01(float v) {
        return Math.min(1f, Math.max(0f, v));
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** Rounded 2-D list for coarse_data JSON (np.round equivalent). */
    private static List<List<Double>> roundedGrid(float[] flat, int H, int W, int decimals) {
        double factor = Math.pow(10, decimals);
        List<List<Double>> grid = new ArrayList<>(H);
        for (int r = 0; r < H; r++) {
            List<Double> row = new ArrayList<>(W);
            for (int c = 0; c < W; c++) {
                row.add(Math.round(flat[r * W + c] * factor) / factor);
            }
            grid.add(row);
        }
        return grid;
    }

    private static int getInt(Map<String, String> q, String key, int def) {
        String v = q.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    private static Float getFloat(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null) return null;
        try { return Float.parseFloat(v); } catch (NumberFormatException e) { return null; }
    }
}
