package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * Biome ids at native resolution (30 m pixels) for tools: the explorer's Biomes map, {@code terraPreview} and
 * {@code /terra-find}. Runs the same classifier (with the patch biomes, {@link BiomePatches}) and shore pass
 * ({@link CoastShaper}) as the game, on the pipeline's elevation and climate.
 *
 * <p>Riverside and floodplain forests use the game's river regions ({@link LocalTerrainProvider#riverDistances}).
 * Leaves out the block-level steps that happen after {@link WorldPipeline#get} in the game (spits and the other
 * coastal plans, deltas, the elevation noise) and the river-mouth mask, so shores can differ by a pixel or two and a
 * river mouth may show a cliff the game doesn't make. Inland biomes are exactly the game's (the classifier is pointwise
 * and the patches are sampled in world metres, so they match at every scale).
 */
public final class BiomeMap {

    private BiomeMap() {
    }

    /** Biome ids, elevation (m) and climate channels for one window. */
    public record Result(short[] biomes, float[] elev, float[] climate, int H, int W) {
    }

    /** Classifies native pixels [i1, i2) x [j1, j2). */
    public static Result compute(WorldPipeline p, int i1, int j1, int i2, int j2) {
        int H = i2 - i1, W = j2 - j1;
        final int M = CoastShaper.PLATFORM_LOOK_PX;
        int HH = H + 2 * M, WW = W + 2 * M;
        byte[] tagsBig = new byte[HH * WW];
        float[][] big = p.get(i1 - M, j1 - M, i2 + M, j2 + M, true, tagsBig);
        float[] elevBig = big[0], climBig = big[1];

        float[][] elev2D = new float[HH][WW];
        for (int r = 0; r < HH; r++) System.arraycopy(elevBig, r * WW, elev2D[r], 0, WW);
        float[] elev = crop(elevBig, WW, M, M, H, W);
        float[] elevPadded = crop(elevBig, WW, M - 1, M - 1, H + 2, W + 2);
        float[] climate = null;
        if (climBig != null) {
            int ch = climBig.length / (HH * WW);
            climate = new float[ch * H * W];
            for (int k = 0; k < ch; k++) {
                float[] plane = new float[HH * WW];
                System.arraycopy(climBig, k * HH * WW, plane, 0, HH * WW);
                System.arraycopy(crop(plane, WW, M, M, H, W), 0, climate, k * H * W, H * W);
            }
        }
        float[][] rd = LocalTerrainProvider.getInstance().riverDistances(elev, i1, j1, H, W, 1);
        short[] biomes = BiomeClassifier.classify(elev, climate, i1, j1, elevPadded, H, W,
                WorldPipelineModelConfig.nativeResolution(), p.getSeed(), rd == null ? null : rd[0], rd == null ? null : rd[1]);
        float[] elevOut = elev.clone();
        CoastShaper.apply(elev2D, M, M, H, W, 1, i1, j1, climate, elevOut, biomes, null);
        // Mushroom islands, from the ocean features' surface tags.
        byte[][] tags2 = new byte[H][W];
        for (int r = 0; r < H; r++) for (int c = 0; c < W; c++) tags2[r][c] = tagsBig[(r + M) * WW + c + M];
        LocalTerrainProvider.mushroomFields(tags2, elev, biomes, W);
        LocalTerrainProvider.volcanicBiomes(tags2, elev, biomes, W);
        // Round C landforms that change biomes (mesas, canyons, fans, karst).
        LocalTerrainProvider.getInstance().landformsRoundC(elevOut, biomes, climate, elev2D, M, M, i1 - M, j1 - M,
                climBig, i1 - M, j1 - M, HH, WW, i1, j1, H, W, 1, false, rd == null ? null : rd[0]);
        // Glaciers, at native resolution (the margin is just wide enough for its filters).
        if (com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig.glaciers()) {
            GlacierShaper.apply(elev2D, M, M, H, W, 1, i1, j1, climate, elevOut, biomes, tags2);
        }
        return new Result(biomes, elev, climate, H, W);
    }

    private static float[] crop(float[] src, int srcW, int r0, int c0, int h, int w) {
        float[] out = new float[h * w];
        for (int r = 0; r < h; r++) System.arraycopy(src, (r + r0) * srcW + c0, out, r * w, w);
        return out;
    }
}
