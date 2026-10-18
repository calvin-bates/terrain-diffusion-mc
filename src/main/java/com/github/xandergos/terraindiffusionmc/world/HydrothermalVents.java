package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.OceanFeatures;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PotentSulfurBlock;
import net.minecraft.world.level.block.SpeleothemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PotentSulfurState;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

/**
 * Hydrothermal vents, built from 26.3's sulfur blocks.
 *
 * <ul>
 *   <li><b>Black smokers</b> on mid-ocean ridge axes, deep under the sea: clusters of chimneys of blackstone,
 *       basalt, sulfur and cinnabar on a sulfur-stained mound, ringed by sulfur spikes. Each chimney is capped with
 *       potent sulfur over a magma block, so it streams sulfur bubbles. (Vanilla geysers only erupt under at most
 *       4 blocks of water, so deep ones bubble but never blow.)</li>
 *   <li><b>Shallow geysers</b> around active volcanoes (a hotspot chain's young island, island-arc volcanoes): potent
 *       sulfur on the floor of water 1 to 4 blocks deep, over magma (periodic eruptions) or, rarely, sealed-in lava
 *       (continuous), with sulfur, tuff and cinnabar staining around it. That includes the shallows round the coast
 *       and a summit crater lake.</li>
 * </ul>
 *
 * <p>Where vents go comes from {@link OceanFeatures#vents}, which needs only the seed, so this can run in chunk
 * decoration without waiting for the terrain model. Each chunk only writes inside itself, chooses from a random
 * seeded by its position, and reads its own blocks, so results do not depend on generation order. The terrain is
 * not changed: the vents sit on the sea floor the terrain already has.
 */
public final class HydrothermalVents {

    /** Ridge vent-field strength (0..1) at a chunk's centre needed for black smokers there. */
    private static final double RIDGE_MIN = 0.15;
    /** Most chimneys per chunk, at the heart of a vent field. */
    private static final int MAX_CHIMNEYS = 4;
    /** Water (metres) needed over a black smoker: ridge axes are always deep. */
    private static final float DEEP_MIN_M = 1000f;
    /** Chance per chunk of a shallow geyser at full volcanic strength. */
    private static final float GEYSER_CHANCE = 0.1f;
    /** Deepest water (blocks) over a geyser that still erupts (vanilla's limit). */
    private static final int GEYSER_MAX_WATER = PotentSulfurBlock.ALLOWED_WATER_BLOCKS_ABOVE;

    private HydrothermalVents() {
    }

    public static void place(WorldGenLevel level, ChunkAccess chunk, int seaLevel) {
        OceanFeatures features = LocalTerrainProvider.oceanFeatures();
        if (features == null) return;
        int scale = WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        double[] v = features.vents((x0 + 8.0) / scale, (z0 + 8.0) / scale);
        if (v[0] < RIDGE_MIN && v[1] <= 0.02) return;
        RandomSource rnd = RandomSource.create(level.getSeed() ^ (cp.x() * 0x9E3779B97F4A7C15L)
                ^ (cp.z() * 0xC2B2AE3D27D4EB4FL) ^ 0x7E475EEDL);
        if (v[0] >= RIDGE_MIN) blackSmokers(level, chunk, v[0], rnd, seaLevel, scale);
        if (v[1] > 0.02) geyser(level, chunk, v[1], rnd);
    }

    // ---- Black smokers ----

    private static void blackSmokers(WorldGenLevel level, ChunkAccess chunk, double strength, RandomSource rnd,
                                     int seaLevel, int scale) {
        if (rnd.nextFloat() > strength) return;
        float blockSize = 30f / scale;
        int minWater = Math.max(12, Math.round(HeightConverter.oceanDepthBlocks(DEEP_MIN_M, blockSize)));
        int n = 1 + rnd.nextInt(1 + (int) Math.round(strength * (MAX_CHIMNEYS - 1)));
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int c = 0; c < n; c++) {
            int x = x0 + 3 + rnd.nextInt(10), z = z0 + 3 + rnd.nextInt(10);
            int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            int water = waterAbove(chunk, pos, x, floor, z);
            if (water < minWater || floor + water != seaLevel - 1) continue;   // open sea only
            int height = 3 + rnd.nextInt(3 + (int) Math.round(5 * strength));
            height = Math.min(height, water - 2);
            if (height < 3) continue;

            // Mound of sulfur-stained rock around the chimney's foot.
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx * dx + dz * dz > 5) continue;
                    int fx = x + dx, fz = z + dz;
                    int f = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, fx, fz);
                    if (Math.abs(f - floor) > 2 || !isWater(chunk, pos.set(fx, f + 1, fz))) continue;
                    if (rnd.nextFloat() < 0.8f) set(level, pos.set(fx, f, fz), moundBlock(rnd));
                }
            }
            // The chimney: a knobbly column, magma at the core of its top, potent sulfur venting at the tip.
            for (int y = 1; y <= height - 2; y++) {
                set(level, pos.set(x, floor + y, z), chimneyBlock(rnd));
                if (rnd.nextFloat() < 0.3f) {
                    Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(rnd);
                    int kx = x + d.getStepX(), kz = z + d.getStepZ();
                    if (isWater(chunk, pos.set(kx, floor + y, kz))) set(level, pos, chimneyBlock(rnd));
                }
            }
            set(level, pos.set(x, floor + height - 1, z), Blocks.MAGMA_BLOCK.defaultBlockState());
            set(level, pos.set(x, floor + height, z), potentSulfur(PotentSulfurState.DORMANT));
            // A smaller side spire now and then.
            if (rnd.nextFloat() < 0.4f) {
                Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(rnd);
                int sx = x + d.getStepX(), sz = z + d.getStepZ();
                int sh = 1 + rnd.nextInt(Math.max(1, height / 2));
                for (int y = 1; y < sh; y++) {
                    if (isWater(chunk, pos.set(sx, floor + y, sz))) set(level, pos, chimneyBlock(rnd));
                }
                if (isWater(chunk, pos.set(sx, floor + sh, sz))) set(level, pos, potentSulfur(PotentSulfurState.WET));
            }
            // Sulfur spikes scattered over the mound and around it.
            int spikes = 3 + rnd.nextInt(5);
            for (int s = 0; s < spikes; s++) {
                int fx = x + rnd.nextInt(9) - 4, fz = z + rnd.nextInt(9) - 4;
                if (!inChunk(chunk, fx, fz)) continue;
                int f = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, fx, fz);
                if (!isWater(chunk, pos.set(fx, f + 1, fz)) || !isWater(chunk, pos.set(fx, f + 2, fz))) continue;
                set(level, pos.set(fx, f, fz), Blocks.SULFUR.defaultBlockState());
                boolean tall = rnd.nextFloat() < 0.4f && isWater(chunk, pos.set(fx, f + 3, fz));
                if (tall) {
                    set(level, pos.set(fx, f + 1, fz), spike(SpeleothemThickness.FRUSTUM));
                    set(level, pos.set(fx, f + 2, fz), spike(SpeleothemThickness.TIP));
                } else {
                    set(level, pos.set(fx, f + 1, fz), spike(SpeleothemThickness.TIP));
                }
            }
        }
    }

    private static BlockState moundBlock(RandomSource rnd) {
        float u = rnd.nextFloat();
        Block b = u < 0.4f ? Blocks.SULFUR : u < 0.6f ? Blocks.TUFF : u < 0.75f ? Blocks.CINNABAR
                : u < 0.9f ? Blocks.BASALT : Blocks.BLACKSTONE;
        return b.defaultBlockState();
    }

    private static BlockState chimneyBlock(RandomSource rnd) {
        float u = rnd.nextFloat();
        Block b = u < 0.35f ? Blocks.BLACKSTONE : u < 0.6f ? Blocks.BASALT : u < 0.85f ? Blocks.SULFUR : Blocks.CINNABAR;
        return b.defaultBlockState();
    }

    // ---- Shallow geysers ----

    private static void geyser(WorldGenLevel level, ChunkAccess chunk, double strength, RandomSource rnd) {
        if (rnd.nextFloat() > GEYSER_CHANCE * strength) return;
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int attempt = 0; attempt < 8; attempt++) {
            int x = x0 + 2 + rnd.nextInt(12), z = z0 + 2 + rnd.nextInt(12);
            int floor = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            int water = waterAbove(chunk, pos, x, floor, z);
            if (water < 1 || water > GEYSER_MAX_WATER) continue;
            if (!chunk.getBlockState(pos.set(x, floor + water + 1, z)).isAir()) continue;   // open to the sky
            // Lava (continuous plume) only when it is sealed in by solid rock on every side and below.
            boolean lava = rnd.nextFloat() < 0.2f;
            for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN}) {
                if (!lava) break;
                BlockState n = chunk.getBlockState(pos.set(x + d.getStepX(), floor - 1 + d.getStepY(), z + d.getStepZ()));
                if (!n.isSolid() || !n.getFluidState().isEmpty()) lava = false;
            }
            set(level, pos.set(x, floor - 1, z), lava ? Blocks.LAVA.defaultBlockState() : Blocks.MAGMA_BLOCK.defaultBlockState());
            set(level, pos.set(x, floor, z), potentSulfur(lava ? PotentSulfurState.CONTINUOUS : PotentSulfurState.DORMANT));
            // Sulfur, tuff and cinnabar staining on the floor around it.
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if ((dx == 0 && dz == 0) || dx * dx + dz * dz > 5 || rnd.nextFloat() > 0.6f) continue;
                    int fx = x + dx, fz = z + dz;
                    int f = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, fx, fz);
                    if (Math.abs(f - floor) > 1 || !isWater(chunk, pos.set(fx, f + 1, fz))) continue;
                    float u = rnd.nextFloat();
                    Block b = u < 0.55f ? Blocks.SULFUR : u < 0.85f ? Blocks.TUFF : Blocks.CINNABAR;
                    set(level, pos.set(fx, f, fz), b.defaultBlockState());
                }
            }
            return;
        }
    }

    // ---- Helpers ----

    private static BlockState potentSulfur(PotentSulfurState state) {
        return Blocks.POTENT_SULFUR.defaultBlockState().setValue(PotentSulfurBlock.STATE, state);
    }

    private static BlockState spike(SpeleothemThickness thickness) {
        return Blocks.SULFUR_SPIKE.defaultBlockState()
                .setValue(SpeleothemBlock.TIP_DIRECTION, Direction.UP)
                .setValue(SpeleothemBlock.THICKNESS, thickness)
                .setValue(SpeleothemBlock.WATERLOGGED, true);
    }

    /** Source water blocks stacked directly above the floor at (x, floor, z). */
    private static int waterAbove(ChunkAccess chunk, BlockPos.MutableBlockPos pos, int x, int floor, int z) {
        int top = chunk.getMaxY();
        int n = 0;
        for (int y = floor + 1; y <= top && isWater(chunk, pos.set(x, y, z)); y++) n++;
        return n;
    }

    private static boolean isWater(ChunkAccess chunk, BlockPos pos) {
        BlockState st = chunk.getBlockState(pos);
        return st.is(Blocks.WATER) && st.getFluidState().isSourceOfType(Fluids.WATER);
    }

    private static boolean inChunk(ChunkAccess chunk, int x, int z) {
        ChunkPos cp = chunk.getPos();
        return x >= cp.getMinBlockX() && x <= cp.getMaxBlockX() && z >= cp.getMinBlockZ() && z <= cp.getMaxBlockZ();
    }

    /** Writes through the world-gen level so blocks that carry a block entity (potent sulfur) get one. */
    private static void set(WorldGenLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }
}
