package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drains cave lake water (and molten lava) that nothing holds, across chunk edges (Oct 27).
 *
 * <p>{@link CaveWater} fills water caves up to their region's level in the terrain pass and drains what its own chunk
 * can see leaking, but it can't see the next chunk: water at a chunk's edge beside open air in the neighbour stayed,
 * and poured down the cave's terraces and hung over drops once the chunk loaded (Calvin's screenshots at X -593 Y 46
 * Z -1507). This runs after the features step, when the eight neighbours exist: over the 3 x 3 chunks around the one
 * just decorated, every fluid source in a water or lava cave's biome cell with air beside or under it is removed, and
 * again for what that exposes, until all that is left is held by rock or more fluid. Positions past the 3 x 3 count
 * as holding; each chunk is passed over again as its neighbours decorate, so a leak at any edge is seen.
 */
public final class CaveWaterDrain {
    private static final Logger LOG = LoggerFactory.getLogger(CaveWaterDrain.class);
    private static final ResourceKey<Biome> LAKES = key("underground_lakes"), GROTTO = key("glowworm_grotto"),
            KARST = key("karst_caves"), MOLTEN = key("molten_cavern"), TUBES = key("lava_tubes");
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private CaveWaterDrain() {
    }

    private static ResourceKey<Biome> key(String name) {
        return ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("terra", name));
    }

    private static boolean fluidCave(Holder<Biome> h) {
        return h.is(LAKES) || h.is(GROTTO) || h.is(KARST) || h.is(MOLTEN) || h.is(TUBES);
    }

    public static void finish(WorldGenLevel level, ChunkAccess chunk) {
        if (!TerrainDiffusionConfig.caves() || !TerrainDiffusionConfig.caveBiomes() || !TerrainDiffusionConfig.waterCaves()) return;
        try {
            drain(level, chunk);
        } catch (Throwable t) {
            LOG.error("Terra: cave water drain failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static boolean isSource(BlockState s) {
        return (s.is(Blocks.WATER) || s.is(Blocks.LAVA)) && s.getFluidState().isSource();
    }

    private static void drain(WorldGenLevel level, ChunkAccess center) {
        int cx = center.getPos().x(), cz = center.getPos().z();
        ChunkAccess[] cs = new ChunkAccess[9];
        int minSection = center.getMinSectionY(), nSec = center.getSections().length, minY = center.getMinY();
        // Which sections (by index) hold any fluid in a fluid cave cell, in any of the nine chunks.
        boolean[] secUsed = new boolean[nSec];
        boolean any = false;
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) {
                ChunkAccess c = dx == 0 && dz == 0 ? center : level.getChunk(cx + dx, cz + dz);
                cs[(dz + 1) * 3 + dx + 1] = c;
                if (c == null) continue;
                LevelChunkSection[] secs = c.getSections();
                for (int s = 0; s < nSec && s < secs.length; s++) {
                    LevelChunkSection sec = secs[s];
                    if (sec == null || sec.hasOnlyAir()) continue;
                    if (!sec.getBiomes().maybeHas(CaveWaterDrain::fluidCave)) continue;
                    if (!sec.getStates().maybeHas(CaveWaterDrain::isSource)) continue;
                    secUsed[s] = true;
                    any = true;
                }
            }
        if (!any) return;
        int lo = 0, hi = nSec - 1;
        while (!secUsed[lo]) lo++;
        while (!secUsed[hi]) hi--;
        int y0 = minY + lo * 16, H = (hi - lo + 1) * 16;
        final int W = 48;
        // Candidates: fluid sources in fluid cave cells.
        boolean[] cand = new boolean[W * W * H];
        int[] queue = new int[4096];
        int head = 0, tail = 0;
        for (int ci = 0; ci < 9; ci++) {
            ChunkAccess c = cs[ci];
            if (c == null) continue;
            int ox = (ci % 3) * 16, oz = (ci / 3) * 16;
            LevelChunkSection[] secs = c.getSections();
            for (int s = lo; s <= hi; s++) {
                if (s >= secs.length) break;
                LevelChunkSection sec = secs[s];
                if (sec == null || sec.hasOnlyAir() || !sec.getBiomes().maybeHas(CaveWaterDrain::fluidCave)
                        || !sec.getStates().maybeHas(CaveWaterDrain::isSource)) continue;
                boolean[] cell = new boolean[64];
                for (int q = 0; q < 64; q++) cell[q] = fluidCave(sec.getNoiseBiome(q & 3, q >> 4, (q >> 2) & 3));
                for (int ly = 0; ly < 16; ly++)
                    for (int lz = 0; lz < 16; lz++)
                        for (int lx = 0; lx < 16; lx++) {
                            if (!cell[((ly >> 2) * 4 + (lz >> 2)) * 4 + (lx >> 2)]) continue;
                            if (!isSource(sec.getBlockState(lx, ly, lz))) continue;
                            int i = (((s - lo) * 16 + ly) * W + oz + lz) * W + ox + lx;
                            cand[i] = true;
                            if (tail == queue.length) queue = java.util.Arrays.copyOf(queue, queue.length * 2);
                            queue[tail++] = i;
                        }
            }
        }
        if (tail == 0) return;
        boolean[] queued = new boolean[cand.length];
        for (int k = 0; k < tail; k++) queued[queue[k]] = true;
        int[] nx = {1, -1, 0, 0, 0}, nz = {0, 0, 1, -1, 0}, ny = {0, 0, 0, 0, -1};
        int[] ux = {1, -1, 0, 0, 0}, uz = {0, 0, 1, -1, 0}, uy = {0, 0, 0, 0, 1};
        int removed = 0;
        while (head < tail) {
            int i = queue[head++];
            queued[i] = false;
            if (!cand[i]) continue;
            int x = i % W, z = (i / W) % W, y = i / (W * W);
            boolean leaks = false;
            for (int k = 0; k < 5 && !leaks; k++) {
                int ax = x + nx[k], az = z + nz[k], ay = y + ny[k];
                if (ax < 0 || ax >= W || az < 0 || az >= W) continue;   // unseen: holds
                if (ay < 0) {
                    if (y0 + ay < minY) continue;
                } else if (cand[(ay * W + az) * W + ax]) continue;
                BlockState st = get(cs, ax, y0 + ay, az, minSection);
                if (st != null && st.isAir()) leaks = true;
            }
            if (!leaks) continue;
            cand[i] = false;
            set(cs, x, y0 + y, z, minSection, AIR);
            removed++;
            for (int k = 0; k < 5; k++) {
                int ax = x + ux[k], az = z + uz[k], ay = y + uy[k];
                if (ax < 0 || ax >= W || az < 0 || az >= W || ay >= H) continue;
                int ni = (ay * W + az) * W + ax;
                if (!cand[ni] || queued[ni]) continue;
                if (tail == queue.length) {
                    System.arraycopy(queue, head, queue, 0, tail - head);
                    tail -= head;
                    head = 0;
                    if (tail == queue.length) queue = java.util.Arrays.copyOf(queue, queue.length * 2);
                }
                queue[tail++] = ni;
                queued[ni] = true;
            }
        }
        if (removed > 0 && LOG.isDebugEnabled()) LOG.debug("Terra: drained {} cave fluid blocks around {}", removed, center.getPos());
    }

    /** The block at area position (x, y, z) (x, z in 0..47 over the 3 x 3 chunks), or null if unknown. */
    private static BlockState get(ChunkAccess[] cs, int x, int y, int z, int minSection) {
        ChunkAccess c = cs[(z >> 4) * 3 + (x >> 4)];
        if (c == null) return null;
        int s = (y >> 4) - minSection;
        LevelChunkSection[] secs = c.getSections();
        if (s < 0 || s >= secs.length) return null;
        LevelChunkSection sec = secs[s];
        return sec == null ? AIR : sec.getBlockState(x & 15, y & 15, z & 15);
    }

    private static void set(ChunkAccess[] cs, int x, int y, int z, int minSection, BlockState st) {
        ChunkAccess c = cs[(z >> 4) * 3 + (x >> 4)];
        if (c == null) return;
        int s = (y >> 4) - minSection;
        LevelChunkSection[] secs = c.getSections();
        if (s < 0 || s >= secs.length || secs[s] == null) return;
        secs[s].setBlockState(x & 15, y & 15, z & 15, st, false);
    }
}
