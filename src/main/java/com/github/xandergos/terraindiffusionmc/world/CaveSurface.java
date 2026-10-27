package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveBiomes;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;

/**
 * The surface the cave layer looks at ({@link CaveBiomes.Surface}): the surface biome id of a column, read from the
 * tile that holds it (loading it if needed, as the terrain does). Per thread, with a small memo, since the cave layer
 * asks for the same few region anchors over and over.
 */
public final class CaveSurface implements CaveBiomes.Surface {
    private static final ThreadLocal<CaveSurface> LOCAL = ThreadLocal.withInitial(CaveSurface::new);

    public static CaveSurface get() {
        return LOCAL.get();
    }

    private static final int MEMO = 64;
    private final int[] memoX = new int[MEMO], memoZ = new int[MEMO], memoGen = new int[MEMO];
    private final short[] memoVal = new short[MEMO];
    private HeightmapData tile;
    private int tileX = Integer.MIN_VALUE, tileZ = Integer.MIN_VALUE, tileGen = Integer.MIN_VALUE;

    private CaveSurface() {
        java.util.Arrays.fill(memoGen, Integer.MIN_VALUE);
    }

    @Override
    public short biomeAt(int x, int z) {
        int gen = LocalTerrainProvider.cacheGeneration();
        int slot = (Math.floorMod(x * 31 + z * 17, MEMO));
        if (memoGen[slot] == gen && memoX[slot] == x && memoZ[slot] == z) return memoVal[slot];
        short v = lookup(x, z, gen);
        memoX[slot] = x;
        memoZ[slot] = z;
        memoGen[slot] = gen;
        memoVal[slot] = v;
        return v;
    }

    private short lookup(int x, int z, int gen) {
        int size = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(size);
        int tx = (x >> shift) << shift, tz = (z >> shift) << shift;
        if (tile == null || tx != tileX || tz != tileZ || gen != tileGen) {
            LocalTerrainProvider p = LocalTerrainProvider.getInstance();
            tile = p == null ? null : p.fetchHeightmap(tz, tx, tz + size, tx + size);
            tileX = tx;
            tileZ = tz;
            tileGen = gen;
        }
        if (tile == null || tile.biomeIds == null) return -1;
        return tile.biomeIds[Math.min(tile.height - 1, z - tz)][Math.min(tile.width - 1, x - tx)];
    }
}
