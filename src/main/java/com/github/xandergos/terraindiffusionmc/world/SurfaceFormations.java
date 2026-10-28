package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveBiomes;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveFormationLayout;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveSites;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rubble where caves meet the surface (caves plan v1.5 part four round B), in the terrain pass after the cave
 * formations: talus (gravel, cobblestone, andesite and tuff, with a few loose blocks on top) heaped at the foot of rock
 * shelters and in front of cave mouths, and the fallen roof of collapse domes: a rubble mound under the skylight,
 * mossy where the light falls. The shapes come from {@link CaveSites} (pure), and every block is decided from its own
 * position, so heaps across chunk borders come out whole. Each heap is laid on the top block found near the height the
 * shape expects, so it lies on whatever ground is really there.
 */
public final class SurfaceFormations {
    private static final Logger LOG = LoggerFactory.getLogger(SurfaceFormations.class);

    private SurfaceFormations() {
    }

    private static final BlockState[] RUBBLE = {b(Blocks.GRAVEL), b(Blocks.GRAVEL), b(Blocks.COBBLESTONE),
            b(Blocks.COBBLESTONE), b(Blocks.ANDESITE), b(Blocks.MOSSY_COBBLESTONE), b(Blocks.TUFF), b(Blocks.GRAVEL),
            b(Blocks.COBBLESTONE), b(Blocks.ANDESITE)};
    private static final BlockState[] LOOSE = {b(Blocks.COBBLESTONE), b(Blocks.MOSSY_COBBLESTONE), b(Blocks.ANDESITE),
            b(Blocks.COBBLESTONE)};
    private static final BlockState[] SHELTER_FLOOR = {b(Blocks.COARSE_DIRT), b(Blocks.GRAVEL), b(Blocks.COARSE_DIRT),
            b(Blocks.GRAVEL), b(Blocks.COBBLESTONE)};

    private static BlockState b(net.minecraft.world.level.block.Block block) {
        return block.defaultBlockState();
    }

    public static void place(ChunkAccess chunk) {
        if (!TerrainDiffusionConfig.caves() || !TerrainDiffusionConfig.caveBiomes() || !TerrainDiffusionConfig.waterCaves()
                || !TerrainDiffusionConfig.caveFormations()) return;
        try {
            placeUnchecked(chunk);
        } catch (Throwable t) {
            LOG.error("Orogeny: surface formations failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static void placeUnchecked(ChunkAccess chunk) {
        long seed = LocalTerrainProvider.getSeed();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        CaveSites.formations = true;
        java.util.List<CaveSites.Cut> cuts = CaveSites.near(x0, z0, x0 + 15, z0 + 15, CaveBiomes.forSeed(seed),
                CaveSurface.get());
        if (cuts.isEmpty()) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (CaveSites.Cut cut : cuts) {
            if (cut instanceof CaveSites.Shelter s) shelter(chunk, s, x0, z0, seed, pos);
            else if (cut instanceof CaveSites.Mouth m) mouth(chunk, m, x0, z0, seed, pos);
            else if (cut instanceof CaveSites.Dome d && d.collapse()) dome(chunk, d, x0, z0, seed, pos);
        }
    }

    /** Talus in front of the shelter's mouth and down the slope below it; coarse dirt and gravel on its floor. */
    private static void shelter(ChunkAccess chunk, CaveSites.Shelter s, int x0, int z0, long seed,
                                BlockPos.MutableBlockPos pos) {
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz;
                float ox = x - s.cx(), oz = z - s.cz();
                float along = ox * s.dirX() + oz * s.dirZ(), across = -ox * s.dirZ() + oz * s.dirX();
                float side = Math.abs(across) / (s.width() * 1.1f);
                if (side >= 1f || along < -7f || along > s.depth() * 0.85f) continue;
                long h = CaveFormationLayout.hash(seed ^ 0x7A1C5L, x, 0, z);
                float u = CaveFormationLayout.unit(h, 0);
                int y = topNear(chunk, pos, x, z, s.foot() + 3, s.foot() - 10);
                if (y == Integer.MIN_VALUE) continue;
                if (along > 1.5f) {
                    // The floor under the overhang.
                    if (u < 0.75f * (1f - side)) set(chunk, pos, x, y, z, SHELTER_FLOOR[(int) (CaveFormationLayout.unit(h, 8) * SHELTER_FLOOR.length)]);
                    continue;
                }
                // Talus: thickest at the foot of the face, thinning outwards and to the sides.
                float f = (1f - side) * (1f - Math.max(0f, -along) / 7f);
                if (u >= 0.9f * f) continue;
                set(chunk, pos, x, y, z, RUBBLE[(int) (CaveFormationLayout.unit(h, 8) * RUBBLE.length)]);
                if (CaveFormationLayout.unit(h, 16) < 0.22f * f) loose(chunk, pos, x, y, z, h);
            }
    }

    /** Talus spilling out of a cave mouth and around its entrance. */
    private static void mouth(ChunkAccess chunk, CaveSites.Mouth m, int x0, int z0, long seed, BlockPos.MutableBlockPos pos) {
        float r = m.r0() * 1.5f;
        if (m.sx() + r < x0 || m.sx() - r > x0 + 15 || m.sz() + r < z0 || m.sz() - r > z0 + 15) return;
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz;
                float d = (float) Math.hypot(x - m.sx(), z - m.sz()) / r;
                if (d >= 1f) continue;
                long h = CaveFormationLayout.hash(seed ^ 0x7A1C6L, x, 0, z);
                if (CaveFormationLayout.unit(h, 0) >= 0.7f * (1f - d * d)) continue;
                int y = topNear(chunk, pos, x, z, m.ground() + 4, m.ground() - 14);
                if (y == Integer.MIN_VALUE) continue;
                set(chunk, pos, x, y, z, RUBBLE[(int) (CaveFormationLayout.unit(h, 8) * RUBBLE.length)]);
                if (CaveFormationLayout.unit(h, 16) < 0.12f * (1f - d)) loose(chunk, pos, x, y, z, h);
            }
    }

    /** The collapse dome's fallen roof: rubble over its mound, moss where the skylight's light falls. */
    private static void dome(ChunkAccess chunk, CaveSites.Dome d, int x0, int z0, long seed, BlockPos.MutableBlockPos pos) {
        float lx0 = d.x() + d.lightDx(), lz0 = d.z() + d.lightDz(), r = d.skylight() * 2.9f;
        if (lx0 + r < x0 || lx0 - r > x0 + 15 || lz0 + r < z0 || lz0 - r > z0 + 15) return;
        for (int lz = 0; lz < 16; lz++)
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz;
                float q = (float) Math.hypot(x - lx0, z - lz0);
                if (q >= r) continue;
                int expect = (int) Math.ceil(d.floorAt(x, z));
                int y = topNear(chunk, pos, x, z, expect + 3, expect - 5);
                if (y == Integer.MIN_VALUE) continue;
                long h = CaveFormationLayout.hash(seed ^ 0x7A1C7L, x, 0, z);
                float u = CaveFormationLayout.unit(h, 0);
                float f = 1f - q / r;
                if (u >= 0.25f + 0.75f * f) continue;
                BlockState st = q < d.skylight() * 0.9f && CaveFormationLayout.unit(h, 24) < 0.4f ? b(Blocks.MOSS_BLOCK)
                        : RUBBLE[(int) (CaveFormationLayout.unit(h, 8) * RUBBLE.length)];
                set(chunk, pos, x, y, z, st);
                if (CaveFormationLayout.unit(h, 16) < 0.16f * f) loose(chunk, pos, x, y, z, h);
            }
    }

    /** A loose block (or two) on top of the rubble, where there is room. */
    private static void loose(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z, long h) {
        if (!chunk.getBlockState(pos.set(x, y + 1, z)).isAir() || !chunk.getBlockState(pos.set(x, y + 2, z)).isAir()) return;
        set(chunk, pos, x, y + 1, z, LOOSE[(int) (CaveFormationLayout.unit(h, 24) * LOOSE.length)]);
        if (CaveFormationLayout.unit(h, 32) < 0.25f && chunk.getBlockState(pos.set(x, y + 3, z)).isAir())
            set(chunk, pos, x, y + 2, z, LOOSE[(int) (CaveFormationLayout.unit(h, 40) * LOOSE.length)]);
    }

    private static void set(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int y, int z, BlockState st) {
        chunk.setBlockState(pos.set(x, y, z), st, 0);
    }

    /**
     * The highest solid ground block with air above it from {@code from} down to {@code to}, or MIN_VALUE: rock, dirt,
     * sand or gravel (no water, no leaves).
     */
    private static int topNear(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int z, int from, int to) {
        int minY = chunk.getMinY() + 1, maxY = chunk.getMinY() + chunk.getHeight() - 2;
        from = Math.min(from, maxY);
        to = Math.max(to, minY);
        boolean airAbove = chunk.getBlockState(pos.set(x, from + 1, z)).isAir();
        for (int y = from; y >= to; y--) {
            BlockState st = chunk.getBlockState(pos.set(x, y, z));
            if (st.isAir()) {
                airAbove = true;
                continue;
            }
            if (airAbove && st.getFluidState().isEmpty() && st.isSolid() && !st.is(net.minecraft.tags.BlockTags.LEAVES)) return y;
            airAbove = false;
        }
        return Integer.MIN_VALUE;
    }
}
