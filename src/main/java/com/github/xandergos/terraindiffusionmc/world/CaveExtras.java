package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveBiomes;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveSites;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.SeaCaves;

import java.util.List;

/**
 * The cave shapes that may cut through the surface (caves plan v1.5 part two), for one block rectangle: river sink and
 * cenote shafts ({@link CaveSites}) and sea caves ({@link SeaCaves}). The terrain density takes the minimum with them
 * after the cave roof, and so do the explorer's slices.
 */
final class CaveExtras {
    final int x0, z0, sx, sz;
    final List<CaveSites.Shaft> shafts;
    final SeaCaves sea;
    /** Per column: the ground top if sea caves may run there, else {@link Integer#MIN_VALUE}. */
    final int[] seaTop;
    /** Per column: how far the sea is (SeaCaves.seaDistance). */
    final int[] seaDist;
    final boolean any;

    private CaveExtras(int x0, int z0, int sx, int sz, List<CaveSites.Shaft> shafts, SeaCaves sea, int[] seaTop,
                       int[] seaDist, boolean anySea) {
        this.seaDist = seaDist;
        this.x0 = x0;
        this.z0 = z0;
        this.sx = sx;
        this.sz = sz;
        this.shafts = shafts;
        this.sea = sea;
        this.seaTop = seaTop;
        this.any = anySea || !shafts.isEmpty();
    }

    static CaveExtras of(int x0, int z0, int sx, int sz) {
        long seed = LocalTerrainProvider.getSeed();
        CaveSurface surface = CaveSurface.get();
        List<CaveSites.Shaft> shafts = TerrainDiffusionConfig.caveBiomes() && TerrainDiffusionConfig.waterCaves()
                ? CaveSites.near(x0, z0, x0 + sx - 1, z0 + sz - 1, CaveBiomes.forSeed(seed), surface) : List.of();
        SeaCaves sea = TerrainDiffusionConfig.seaCaves() ? SeaCaves.forSeed(seed) : null;
        int[] seaTop = null, seaDist = null;
        boolean anySea = false;
        if (sea != null) {
            seaTop = new int[sx * sz];
            seaDist = new int[sx * sz];
            for (int dz = 0; dz < sz; dz++) {
                for (int dx = 0; dx < sx; dx++) {
                    int x = x0 + dx, z = z0 + dz;
                    int d = sea.seaDistance(x, z, surface);
                    boolean on = d > 0;
                    seaTop[dz * sx + dx] = on ? surface.groundTop(x, z) : Integer.MIN_VALUE;
                    seaDist[dz * sx + dx] = d;
                    anySea |= on;
                }
            }
        }
        return new CaveExtras(x0, z0, sx, sz, shafts, sea, seaTop, seaDist, anySea);
    }

    /** The highest Y any extra shape reaches in column c (relative index dz * sx + dx), or MIN_VALUE. */
    int maxY(int c) {
        int x = x0 + c % sx, z = z0 + c / sx, m = Integer.MIN_VALUE;
        for (CaveSites.Shaft s : shafts) {
            float dx = x - s.x(), dz = z - s.z(), r = Math.max(s.radius(), s.chamberRadius()) + 1;
            if (dx * dx + dz * dz <= r * r) m = Math.max(m, s.top());
        }
        if (seaTop != null && seaTop[c] != Integer.MIN_VALUE) m = Math.max(m, seaTop[c]);
        return m;
    }

    /** The lowest Y any extra shape reaches in column c, given it reaches somewhere. */
    int minY(int c) {
        int x = x0 + c % sx, z = z0 + c / sx, m = Integer.MAX_VALUE;
        for (CaveSites.Shaft s : shafts) {
            float dx = x - s.x(), dz = z - s.z(), r = Math.max(s.radius(), s.chamberRadius()) + 1;
            if (dx * dx + dz * dz <= r * r) m = Math.min(m, s.minY());
        }
        if (seaTop != null && seaTop[c] != Integer.MIN_VALUE) m = Math.min(m, SeaCaves.MIN_Y);
        return m;
    }

    /** Density of the extra shapes at a block of column c (negative is air), +inf where none reaches. */
    float value(int c, int x, int y, int z) {
        float v = Float.POSITIVE_INFINITY;
        for (CaveSites.Shaft s : shafts) v = Math.min(v, s.value(x, y, z));
        if (seaTop != null && seaTop[c] != Integer.MIN_VALUE) v = Math.min(v, sea.value(x, y, z, seaTop[c], seaDist[c]));
        return v;
    }
}
