package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveBiomes;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveSites;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.SeaArches;
import com.github.xandergos.terraindiffusionmc.pipeline.SeaCaves;

import java.util.List;

/**
 * The cave shapes that may cut through the surface (caves plan v1.5 parts two and three), for one block rectangle:
 * river sinks, cenotes, canyon ravines (with natural bridges), deep shafts, sinkhole jungles, blue holes, cave mouths,
 * collapse domes and rock shelters ({@link CaveSites}), sea arches and coastal overhangs ({@link SeaArches}) and sea caves
 * ({@link SeaCaves}). The terrain density takes the minimum with them
 * after the cave roof, and so do the explorer's slices.
 */
final class CaveExtras {
    final int x0, z0, sx, sz;
    final List<CaveSites.Cut> shafts;
    final SeaCaves sea;
    /** Per column: the ground top if sea caves may run there, else {@link Integer#MIN_VALUE}. */
    final int[] seaTop;
    /** Per column: how far the sea is (SeaCaves.seaDistance). */
    final int[] seaDist;
    /** Per column: its coastal overhang (SeaArches.notch, packed), or -1; null if none in the area. */
    final int[] notch;
    final boolean any;

    private CaveExtras(int x0, int z0, int sx, int sz, List<CaveSites.Cut> shafts, SeaCaves sea, int[] seaTop,
                       int[] seaDist, boolean anySea, int[] notch) {
        this.seaDist = seaDist;
        this.notch = notch;
        this.x0 = x0;
        this.z0 = z0;
        this.sx = sx;
        this.sz = sz;
        this.shafts = shafts;
        this.sea = sea;
        this.seaTop = seaTop;
        this.any = anySea || !shafts.isEmpty() || notch != null;
    }

    static CaveExtras of(int x0, int z0, int sx, int sz) {
        long seed = LocalTerrainProvider.getSeed();
        CaveSurface surface = CaveSurface.get();
        CaveSites.formations = TerrainDiffusionConfig.caveFormations();
        List<CaveSites.Cut> shafts = TerrainDiffusionConfig.caveBiomes() && TerrainDiffusionConfig.waterCaves()
                ? CaveSites.near(x0, z0, x0 + sx - 1, z0 + sz - 1, CaveBiomes.forSeed(seed), surface) : List.of();
        // Sea arches and coastal overhangs: with the sea caves and the formations.
        int[] notch = null;
        if (TerrainDiffusionConfig.seaCaves() && TerrainDiffusionConfig.caveFormations()) {
            SeaArches.generation = LocalTerrainProvider.cacheGeneration();
            List<CaveSites.Cut> arches = new java.util.ArrayList<>();
            SeaArches.arches(x0, z0, x0 + sx - 1, z0 + sz - 1, surface, arches);
            if (!arches.isEmpty()) {
                arches.addAll(shafts);
                shafts = arches;
            }
            int[] n = new int[sx * sz];
            boolean anyNotch = false;
            for (int dz = 0; dz < sz; dz++)
                for (int dx = 0; dx < sx; dx++) {
                    int v = SeaArches.notch(x0 + dx, z0 + dz, surface);
                    n[dz * sx + dx] = v;
                    anyNotch |= v >= 0;
                }
            if (anyNotch) notch = n;
        }
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
        return new CaveExtras(x0, z0, sx, sz, shafts, sea, seaTop, seaDist, anySea, notch);
    }

    /** The highest Y any extra shape reaches in column c (relative index dz * sx + dx), or MIN_VALUE. */
    int maxY(int c) {
        int x = x0 + c % sx, z = z0 + c / sx, m = Integer.MIN_VALUE;
        for (CaveSites.Cut s : shafts) if (s.covers(x, z)) m = Math.max(m, s.top());
        if (seaTop != null && seaTop[c] != Integer.MIN_VALUE) m = Math.max(m, seaTop[c]);
        if (notch != null && notch[c] >= 0) m = Math.max(m, SeaArches.notchTop(notch[c]));
        return m;
    }

    /** The lowest Y any extra shape reaches in column c, given it reaches somewhere. */
    int minY(int c) {
        int x = x0 + c % sx, z = z0 + c / sx, m = Integer.MAX_VALUE;
        for (CaveSites.Cut s : shafts) if (s.covers(x, z)) m = Math.min(m, s.minY());
        if (seaTop != null && seaTop[c] != Integer.MIN_VALUE) m = Math.min(m, SeaCaves.MIN_Y);
        if (notch != null && notch[c] >= 0) m = Math.min(m, SeaArches.NOTCH_MIN_Y);
        return m;
    }

    /** Thinnest roof (blocks) a cut may leave under the ground; anything thinner is opened to the sky. */
    static final int THIN_ROOF = 2;

    /**
     * The lowest Y to open to the sky in column c, or MAX_VALUE: where a cut (cave mouth, ravine, shaft, dome...)
     * passes 1-2 blocks under the ground's top block {@code s}, that thin crust is opened too, so no one-block strips of
     * ground are left spanning a cut's opening (Calvin, Oct 27). Rock shelters, sea arches and notches keep their
     * roofs (they are overhangs by design, with 3+ blocks over them).
     */
    int openFrom(int c, int x, int s, int z) {
        boolean any = false;
        for (CaveSites.Cut cut : shafts) if (open(cut) && cut.covers(x, z)) { any = true; break; }
        if (!any) return Integer.MAX_VALUE;
        if (openValue(x, s, z) < 0f) return Integer.MAX_VALUE;   // already open at the top
        for (int d = 1; d <= THIN_ROOF; d++)
            if (openValue(x, s - d, z) < 0f) return s - d + 1;
        return Integer.MAX_VALUE;
    }

    private static boolean open(CaveSites.Cut cut) {
        return !(cut instanceof CaveSites.Shelter) && !(cut instanceof SeaArches.Arch);
    }

    private float openValue(int x, int y, int z) {
        float v = Float.POSITIVE_INFINITY;
        for (CaveSites.Cut cut : shafts) if (open(cut)) v = Math.min(v, cut.value(x, y, z));
        return v;
    }

    /** Density of the extra shapes at a block of column c (negative is air), +inf where none reaches. */
    float value(int c, int x, int y, int z) {
        float v = Float.POSITIVE_INFINITY;
        for (CaveSites.Cut s : shafts) v = Math.min(v, s.value(x, y, z));
        if (seaTop != null && seaTop[c] != Integer.MIN_VALUE) v = Math.min(v, sea.value(x, y, z, seaTop[c], seaDist[c]));
        if (notch != null && notch[c] >= 0) v = Math.min(v, SeaArches.notchValue(notch[c], y));
        return v;
    }
}
