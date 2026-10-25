package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.infinitetensor.FloatTensor;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import com.github.xandergos.terraindiffusionmc.pipeline.IcebergPlan;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider.HeightmapData;
import com.github.xandergos.terraindiffusionmc.pipeline.RegionalBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds Terra's icebergs ({@link IcebergPlan}) and the ice shelves of arctic islands into a chunk, right after its
 * terrain (biomes plan v1.3 round C). Every chunk builds only its own columns of every berg within reach, from the same
 * region plans, so bergs are whole across chunk borders whatever order chunks generate in.
 *
 * <p>A berg column: from the keel's bottom (resting on the sea floor where the water is shallower than the keel: a
 * grounded berg) up to its freeboard. Packed ice, with a blue ice core and blue ice bands; a notch worn into the
 * sides at the waterline, ledges of plain ice around it, and a snow cap with snow layers on the gentler tops. Frosted
 * ice is never used. Only water and air are replaced, never terrain.
 *
 * <p>Ice shelves: sea within 30-100 m of arctic island land is covered by a floating slab of packed ice from two blocks
 * under the water to one above it, snow on top, with a ragged edge and the odd crevasse.
 */
public final class Icebergs {
    private static final Logger LOG = LoggerFactory.getLogger(Icebergs.class);

    private static final Map<Long, IcebergPlan> PLANS = new ConcurrentHashMap<>();
    private static volatile long plansSeed;
    private static volatile int plansScale;

    private static final FastNoiseLite BLUE = noise(91201, 1f / 7f);
    private static final FastNoiseLite BANDS = noise(91202, 1f / 30f);
    private static final FastNoiseLite SHELF = noise(91203, 1f / 40f);
    private static final FastNoiseLite CRACKS = noise(91204, 1f / 9f);

    /** Ice shelves reach this far from arctic land (metres): 30 m plus up to 70 m more by noise. */
    static final float SHELF_MIN_M = 30f, SHELF_EXTRA_M = 70f;

    private static volatile boolean loggedFirst;

    private Icebergs() {
    }

    private static FastNoiseLite noise(int seed, float freq) {
        FastNoiseLite n = new FastNoiseLite(seed);
        n.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        n.SetFrequency(freq);
        n.SetFractalType(FastNoiseLite.FractalType.FBm);
        n.SetFractalOctaves(2);
        return n;
    }

    public static void place(ChunkAccess chunk, int seaLevel) {
        try {
            if (!TerrainDiffusionConfig.icebergs()) return;
            placeUnchecked(chunk, seaLevel);
        } catch (Throwable t) {
            // Never let this break chunk generation.
            LOG.error("Terra: icebergs failed for chunk {}", chunk.getPos(), t);
        }
    }

    /** The plan of region (ri, rj) (native-pixel regions of {@link IcebergPlan#REGION_PX}), cached. */
    public static IcebergPlan plan(int ri, int rj) {
        long seed = LocalTerrainProvider.getSeed();
        int scale = WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
        if (seed != plansSeed || scale != plansScale) {
            PLANS.clear();
            plansSeed = seed;
            plansScale = scale;
        }
        long key = ((long) ri << 32) ^ (rj & 0xffffffffL);
        IcebergPlan p = PLANS.get(key);
        if (p != null) return p;
        p = buildPlan(seed, ri, rj, scale);
        if (PLANS.size() > 256) PLANS.clear();
        PLANS.put(key, p);
        return p;
    }

    private static IcebergPlan buildPlan(long seed, int ri, int rj, int scale) {
        try {
            // Skip warm seas without computing the native terrain: the coarse map (one coarse pixel per region) around
            // the region has no sea colder than 6 °C.
            FloatTensor co = LocalTerrainProvider.getPipelineCoarse(ri - 1, rj - 1, ri + 2, rj + 2);
            int n = 9;
            boolean cold = false;
            for (int px = 0; px < n; px++) {
                float w = co.data[6 * n + px];
                if (w <= 1e-6f) continue;
                float e = co.data[px] / w, t = co.data[2 * n + px] / w;
                if (e < 0f && t < 6f) { cold = true; break; }
            }
            if (!cold) return IcebergPlan.EMPTY;
            return IcebergPlan.build(seed, ri, rj, scale, (i1, j1, i2, j2) -> {
                try {
                    return LocalTerrainProvider.getPipelineData(i1, j1, i2, j2, true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (Exception e) {
            LOG.warn("Terra: iceberg plan for region ({}, {}) unavailable: {}", ri, rj, e.toString());
            return IcebergPlan.EMPTY;
        }
    }

    /** The bergs whose keel may reach block box [x0, x1] x [z0, z1]. */
    public static List<IcebergPlan.Berg> bergsNear(int x0, int z0, int x1, int z1) {
        int scale = WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
        int reach = IcebergPlan.reachBlocks(scale), regionBlocks = IcebergPlan.REGION_PX * scale;
        List<IcebergPlan.Berg> out = new ArrayList<>();
        for (int ri = Math.floorDiv(z0 - reach, regionBlocks); ri <= Math.floorDiv(z1 + reach, regionBlocks); ri++) {
            for (int rj = Math.floorDiv(x0 - reach, regionBlocks); rj <= Math.floorDiv(x1 + reach, regionBlocks); rj++) {
                for (IcebergPlan.Berg b : plan(ri, rj).bergs) {
                    float rr = b.reach();
                    if (b.cx + rr < x0 || b.cx - rr > x1 || b.cz + rr < z0 || b.cz - rr > z1) continue;
                    out.add(b);
                }
            }
        }
        return out;
    }

    private static void placeUnchecked(ChunkAccess chunk, int seaLevel) {
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ(), minY = chunk.getMinY();
        int top = seaLevel - 1;                         // the sea's top water block
        // Sea floor per column (top solid block), or Integer.MIN_VALUE on land.
        int[] floor = new int[256];
        boolean anySea = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int y = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, dx, dz);
                for (int k = 0; k < 3 && y > minY; k++) {
                    BlockState st = chunk.getBlockState(pos.set(x0 + dx, y, z0 + dz));
                    if (!st.isAir() && st.getFluidState().isEmpty()) break;
                    y--;
                }
                boolean sea = y < top && chunk.getBlockState(pos.set(x0 + dx, y + 1, z0 + dz)).is(Blocks.WATER);
                floor[dz * 16 + dx] = sea ? y : Integer.MIN_VALUE;
                anySea |= sea;
            }
        }
        if (!anySea) return;
        int scale = WorldScaleManager.clampScale(WorldScaleManager.getCurrentScale());
        long seed = LocalTerrainProvider.getSeed();
        shelves(chunk, floor, x0, z0, top, scale, seed, pos);
        List<IcebergPlan.Berg> bergs = bergsNear(x0, z0, x0 + 15, z0 + 15);
        if (bergs.isEmpty()) return;
        if (!loggedFirst) {
            loggedFirst = true;
            LOG.info("Terra: first chunk with icebergs at {}", cp);
        }
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int f = floor[dz * 16 + dx];
                if (f == Integer.MIN_VALUE) continue;
                int x = x0 + dx, z = z0 + dz;
                column(chunk, bergs, x, z, f, top, seed, pos);
            }
        }
    }

    /** One column of every berg over it. */
    private static void column(ChunkAccess chunk, List<IcebergPlan.Berg> bergs, int x, int z, int floor, int top, long seed,
                               BlockPos.MutableBlockPos pos) {
        float bestAbove = -1f, keel = -1f, qAbove = 9f, qMin = 9f;
        IcebergPlan.Berg main = null;
        for (IcebergPlan.Berg b : bergs) {
            float ddx = x + 0.5f - b.cx, ddz = z + 0.5f - b.cz;
            if (Math.abs(ddx) > b.reach() || Math.abs(ddz) > b.reach()) continue;
            float q = b.q(ddx, ddz);
            qMin = Math.min(qMin, q);
            float a = b.above(ddx, ddz);
            if (a >= 0f && a > bestAbove) { bestAbove = a; qAbove = q; main = b; }
            float kd = b.keelDepth(ddx, ddz);
            if (kd > keel) keel = kd;
        }
        long h = mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL) ^ 0x1CE5L);
        if (keel < 0f) {
            // Ledges of plain ice just outside a berg's waterline.
            if (qMin > 1f && qMin < 1.1f && unit(h) < 0.6) setIfWater(chunk, pos.set(x, top, z), Blocks.ICE.defaultBlockState());
            return;
        }
        int bottom = Math.max(floor + 1, top - Math.round(keel));
        int roof;
        if (bestAbove >= 0f) {
            roof = top + Math.max(1, Math.round(bestAbove));
        } else {
            // Under the water only: the keel's shoulder spreading out below the waterline.
            float outside = Math.max(0f, Math.min(1f, (qMin - 1f) / (IcebergPlan.KEEL_WIDE - 1f)));
            roof = top - 1 - Math.round(3f * outside);
            if (qMin > 1f && qMin < 1.1f && unit(h) < 0.6) setIfWater(chunk, pos.set(x, top, z), Blocks.ICE.defaultBlockState());
        }
        if (roof < bottom) return;
        float bandShift = 4f * BANDS.GetNoise(x, z);
        boolean notch = bestAbove >= 0f && qAbove > 0.86f && roof > top + 2;
        for (int y = bottom; y <= roof; y++) {
            if (notch && (y == top || y == top + 1)) continue;          // worn in at the waterline
            int depthIn = Math.min(roof - y, y - bottom);
            BlockState st;
            boolean core = depthIn >= 3 && (main == null || (1f - qAbove) * main.r >= 3f) && BLUE.GetNoise(x, y * 1.3f, z) > 0.2f;
            boolean band = Math.floorMod(Math.round(y + bandShift), 9) == 0 && ((h >>> 7) & 3) != 0;
            st = core || band ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
            setIfWater(chunk, pos.set(x, y, z), st);
        }
        // A snow cap on the gentler tops.
        boolean capped = main != null && roof > top && (main.shape == IcebergPlan.TABULAR || main.shape == IcebergPlan.ICE_ISLAND
                || qAbove < 0.6f);
        if (capped) {
            setIfWater(chunk, pos.set(x, roof, z), Blocks.SNOW_BLOCK.defaultBlockState());
            int layers = 1 + (int) ((h >>> 20) % 2);
            setIfWater(chunk, pos.set(x, roof + 1, z), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers));
        }
    }

    /**
     * Ice shelves along arctic islands: sea columns within {@link #SHELF_MIN_M} + up to {@link #SHELF_EXTRA_M} of arctic
     * land (from the tiles' biomes, so the reach is the same from every chunk).
     */
    private static void shelves(ChunkAccess chunk, int[] floor, int x0, int z0, int top, int scale, long seed,
                                BlockPos.MutableBlockPos pos) {
        // Only in cold seas (arctic islands lie in frozen seas): the chunk's own tile has cold or frozen ocean here.
        int tileSize0 = TerrainDiffusionConfig.tileSize(), shift0 = Integer.numberOfTrailingZeros(tileSize0);
        int sx0 = (x0 >> shift0) << shift0, sz0 = (z0 >> shift0) << shift0;
        HeightmapData own = LocalTerrainProvider.peekHeightmap(sz0, sx0, sz0 + tileSize0, sx0 + tileSize0);
        if (own == null || own.biomeIds == null) return;
        boolean coldSea = false;
        for (int dz = 0; dz < 16 && !coldSea; dz++)
            for (int dx = 0; dx < 16 && !coldSea; dx++) {
                short b = own.biomeIds[z0 - sz0 + dz][x0 - sx0 + dx];
                coldSea = b >= 46 && b <= 49;
            }
        if (!coldSea) return;
        float mpb = 30f / scale;
        int D = (int) Math.ceil((SHELF_MIN_M + SHELF_EXTRA_M) / mpb) + 1;
        int n = 16 + 2 * D;
        boolean[] arctic = new boolean[n * n];
        boolean any = false;
        int tileSize = TerrainDiffusionConfig.tileSize(), shift = Integer.numberOfTrailingZeros(tileSize);
        HeightmapData data = null;
        int tsx = Integer.MIN_VALUE, tsz = Integer.MIN_VALUE;
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                int x = x0 - D + c, z = z0 - D + r;
                int sx = (x >> shift) << shift, sz = (z >> shift) << shift;
                if (sx != tsx || sz != tsz) {
                    data = LocalTerrainProvider.getInstance().fetchHeightmap(sz, sx, sz + tileSize, sx + tileSize);
                    tsx = sx;
                    tsz = sz;
                }
                if (data == null || data.biomeIds == null) continue;
                int lx = x - sx, lz = z - sz;
                if (lx < 0 || lz < 0 || lz >= data.height || lx >= data.width) continue;
                if (data.biomeIds[lz][lx] == RegionalBiomes.ARCTIC && data.heightmap[lz][lx] >= 0) {
                    arctic[r * n + c] = true;
                    any = true;
                }
            }
        }
        if (!any) return;
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int f = floor[dz * 16 + dx];
                if (f == Integer.MIN_VALUE) continue;
                int x = x0 + dx, z = z0 + dz;
                float best = Float.MAX_VALUE;
                for (int r = dz; r <= dz + 2 * D; r++)
                    for (int c = dx; c <= dx + 2 * D; c++)
                        if (arctic[r * n + c]) {
                            float ddx = c - D - dx, ddz = r - D - dz;
                            best = Math.min(best, ddx * ddx + ddz * ddz);
                        }
                if (best == Float.MAX_VALUE) continue;
                float dist = (float) Math.sqrt(best) * mpb;
                float reach = SHELF_MIN_M + SHELF_EXTRA_M * (0.5f + 0.5f * SHELF.GetNoise(x, z));
                if (dist > reach || CRACKS.GetNoise(x, z) > 0.55f) continue;
                int bottom = Math.max(f + 1, top - 2);
                for (int y = bottom; y <= top + 1; y++) setIfWater(chunk, pos.set(x, y, z), Blocks.PACKED_ICE.defaultBlockState());
                setIfWater(chunk, pos.set(x, top + 2, z), Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1));
            }
        }
    }

    private static void setIfWater(ChunkAccess chunk, BlockPos pos, BlockState st) {
        BlockState old = chunk.getBlockState(pos);
        if (old.isAir() || old.is(Blocks.WATER)) chunk.setBlockState(pos, st, 0);
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
