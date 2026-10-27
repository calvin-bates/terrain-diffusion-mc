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
    public boolean memoizable() {
        return true;
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
        HeightmapData t = tileAt(x, z, gen);
        if (t == null || t.biomeIds == null) return -1;
        return t.biomeIds[Math.min(t.height - 1, z - tileZ)][Math.min(t.width - 1, x - tileX)];
    }

    /** The tile used before the current one: the cave layer's region anchors often sit in a neighbouring tile. */
    private HeightmapData prevTile;
    private int prevX = Integer.MIN_VALUE, prevZ = Integer.MIN_VALUE, prevGen = Integer.MIN_VALUE;

    private HeightmapData tileAt(int x, int z, int gen) {
        int size = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(size);
        int tx = (x >> shift) << shift, tz = (z >> shift) << shift;
        if (tile == null || tx != tileX || tz != tileZ || gen != tileGen) {
            HeightmapData t;
            if (prevTile != null && tx == prevX && tz == prevZ && gen == prevGen) {
                t = prevTile;   // swap back without a trip to the shared tile cache
            } else {
                LocalTerrainProvider p = LocalTerrainProvider.getInstance();
                t = p == null ? null : p.fetchHeightmap(tz, tx, tz + size, tx + size);
            }
            prevTile = tile;
            prevX = tileX;
            prevZ = tileZ;
            prevGen = tileGen;
            tile = t;
            tileX = tx;
            tileZ = tz;
            tileGen = gen;
        }
        return tile;
    }

    @Override
    public int groundTop(int x, int z) {
        HeightmapData t = tileAt(x, z, LocalTerrainProvider.cacheGeneration());
        if (t == null || t.heightmap == null) return Integer.MIN_VALUE;
        int lz = Math.min(t.height - 1, z - tileZ), lx = Math.min(t.width - 1, x - tileX);
        if (t.surfaceY != null && t.surfaceY[lz][lx] != Short.MIN_VALUE) return t.surfaceY[lz][lx];
        return HeightConverter.convertToMinecraftHeight(t.heightmap[lz][lx]) - 1;
    }

    @Override
    public int inlandWater(int x, int z) {
        HeightmapData t = tileAt(x, z, LocalTerrainProvider.cacheGeneration());
        if (t == null || t.waterY == null) return Integer.MIN_VALUE;
        short w = t.waterY[Math.min(t.height - 1, z - tileZ)][Math.min(t.width - 1, x - tileX)];
        return w == Short.MIN_VALUE ? Integer.MIN_VALUE : w;
    }

    /** {@link #nearInlandWater} per region anchor, memoized (water caves ask once per region). */
    private final int[] nearX = new int[MEMO], nearZ = new int[MEMO], nearGen = new int[MEMO];
    private final boolean[] nearVal = new boolean[MEMO];
    {
        java.util.Arrays.fill(nearGen, Integer.MIN_VALUE);
    }

    @Override
    public boolean nearInlandWater(int x, int z) {
        int gen = LocalTerrainProvider.cacheGeneration();
        int slot = Math.floorMod(x * 13 + z * 7, MEMO);
        if (nearGen[slot] == gen && nearX[slot] == x && nearZ[slot] == z) return nearVal[slot];
        boolean v = CaveBiomes.Surface.super.nearInlandWater(x, z);
        nearX[slot] = x;
        nearZ[slot] = z;
        nearGen[slot] = gen;
        nearVal[slot] = v;
        return v;
    }
}
