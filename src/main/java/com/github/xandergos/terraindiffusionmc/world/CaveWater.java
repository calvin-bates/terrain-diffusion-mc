package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveBiomes;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveSites;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Water caves (caves plan v1.5 part two): underground lakes, glowworm grottos and karst caves hold water. In the terrain
 * pass, every block of cave air inside one of their 4x4x4 biome cells at or below its region's water level
 * ({@link CaveBiomes#waterLevel}) becomes a water source, and the floor under the water becomes a lake bed of gravel,
 * clay and sand. Where that water touches open cave air in the same chunk (another region, a lower level, a tunnel),
 * it is marked to flow once the chunk loads, so it spills naturally instead of standing as a wall.
 *
 * <p>Also the helpers for river sinks ({@link CaveSites}): the river water placer keeps their holes open.
 */
public final class CaveWater {
    private static final Logger LOG = LoggerFactory.getLogger(CaveWater.class);
    private static final ResourceKey<Biome> LAKES = key("underground_lakes"), GROTTO = key("glowworm_grotto"),
            KARST = key("karst_caves"), MOLTEN = key("molten_cavern"), TUBES = key("lava_tubes");
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
    private static final BlockState[] LAVA_BED = {Blocks.MAGMA_BLOCK.defaultBlockState(), Blocks.BASALT.defaultBlockState(),
            Blocks.BLACKSTONE.defaultBlockState()};
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState[] BED = {Blocks.GRAVEL.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(),
            Blocks.CLAY.defaultBlockState(), Blocks.CLAY.defaultBlockState(), Blocks.SAND.defaultBlockState()};

    private CaveWater() {
    }

    private static ResourceKey<Biome> key(String name) {
        return ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("orogeny", name));
    }

    private static boolean enabled() {
        return TerrainDiffusionConfig.caves() && TerrainDiffusionConfig.caveBiomes() && TerrainDiffusionConfig.waterCaves();
    }

    // ---- River sinks ----

    /** River sink shafts reaching the 16 x 16 chunk at (x0, z0), or an empty list. */
    static List<CaveSites.Shaft> sinksNear(int x0, int z0) {
        if (!enabled()) return List.of();
        try {
            List<CaveSites.Shaft> out = new java.util.ArrayList<>();
            for (CaveSites.Cut c : CaveSites.near(x0, z0, x0 + 15, z0 + 15,
                    CaveBiomes.forSeed(LocalTerrainProvider.getSeed()), CaveSurface.get())) {
                if (c instanceof CaveSites.Shaft s && s.sink()) out.add(s);
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Whether column (x, z) lies inside a sink shaft's throat. */
    static boolean inSink(List<CaveSites.Shaft> sinks, int x, int z) {
        for (CaveSites.Shaft s : sinks) {
            float dx = x - s.x(), dz = z - s.z();
            if (dx * dx + dz * dz <= s.radius() * s.radius()) return true;
        }
        return false;
    }

    // ---- Filling water caves ----

    public static void place(ChunkAccess chunk) {
        if (!enabled()) return;
        try {
            placeUnchecked(chunk);
        } catch (Throwable t) {
            LOG.error("Orogeny: cave water failed for chunk {}", chunk.getPos(), t);
        }
    }

    /** The cave biome id of a fluid cave (water or lava), or -1. */
    private static short fluidCave(Holder<Biome> h, Map<Holder<Biome>, Short> memo) {
        Short b = memo.get(h);
        if (b == null) {
            b = h.is(LAKES) ? CaveBiomes.UNDERGROUND_LAKES : h.is(GROTTO) ? CaveBiomes.GLOWWORM_GROTTO
                    : h.is(KARST) ? CaveBiomes.KARST_CAVES : h.is(MOLTEN) ? CaveBiomes.MOLTEN_CAVERN
                    : h.is(TUBES) ? CaveBiomes.LAVA_TUBES : (short) -1;
            memo.put(h, b);
        }
        return b;
    }

    private static void placeUnchecked(ChunkAccess chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        Map<Holder<Biome>, Short> memo = new IdentityHashMap<>();
        CaveBiomes layer = null;
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        int minY = chunk.getMinY(), height = chunk.getHeight();
        // Water levels per biome cell column: the highest level of any water cell, per block column of 4x4 cells.
        boolean any = false;
        // levelAt[y - minY] would be big; store per section cell instead.
        int[][] cellLevel = new int[sections.length][];
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection sec = sections[s];
            if (sec == null || sec.hasOnlyAir()) continue;
            if (!sec.getBiomes().maybeHas(h -> fluidCave(h, memo) >= 0)) continue;
            int y0 = chunk.getSectionYFromSectionIndex(s) << 4;
            int[] lv = null;
            for (int qy = 0; qy < 4; qy++)
                for (int qz = 0; qz < 4; qz++)
                    for (int qx = 0; qx < 4; qx++) {
                        short id = fluidCave(sec.getNoiseBiome(qx, qy, qz), memo);
                        if (id < 0) continue;
                        if (layer == null) layer = CaveBiomes.forSeed(LocalTerrainProvider.getSeed());
                        if (lv == null) {
                            lv = new int[64];
                            java.util.Arrays.fill(lv, Integer.MIN_VALUE);
                        }
                        int bx = x0 + qx * 4 + 2, by = y0 + qy * 4 + 2, bz = z0 + qz * 4 + 2;
                        int level = layer.fluidLevel(layer.regionCell(bx, by, bz), id);
                        // Lava levels are stored negative-offset so the fill knows the fluid: level * 2 + (lava ? 1 : 0).
                        lv[(qy * 4 + qz) * 4 + qx] = level * 2 + (CaveBiomes.isLavaCave(id) ? 1 : 0);
                        any = true;
                    }
            cellLevel[s] = lv;
        }
        if (!any) return;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int lowest = chunk.getMinY();
        boolean[] placed = new boolean[16 * 16 * height];
        for (int s = 0; s < sections.length; s++) {
            int[] lv = cellLevel[s];
            if (lv == null) continue;
            LevelChunkSection sec = sections[s];
            int y0 = chunk.getSectionYFromSectionIndex(s) << 4;
            for (int ly = 0; ly < 16; ly++) {
                int y = y0 + ly;
                if (y < lowest) continue;
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int code = lv[((ly >> 2) * 4 + (lz >> 2)) * 4 + (lx >> 2)];
                        if (code == Integer.MIN_VALUE) continue;
                        boolean lava = (code & 1) != 0;
                        int level = code >> 1;
                        if (y > level || (!lava && y < CaveBiomes.WATER_MIN_Y - 10)) continue;
                        if (!sec.getBlockState(lx, ly, lz).isAir()) continue;
                        sec.setBlockState(lx, ly, lz, lava ? LAVA : WATER, false);
                        placed[((y - minY) * 16 + lz) * 16 + lx] = true;
                    }
                }
            }
        }

        drain(chunk, placed, minY, height);

        // Lake beds, and flow where the water meets open air in this chunk.
        for (int y = Math.max(minY, lowest); y < minY + height; y++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    if (!placed[((y - minY) * 16 + lz) * 16 + lx]) continue;
                    int x = x0 + lx, z = z0 + lz;
                    BlockState below = chunk.getBlockState(pos.set(x, y - 1, z));
                    boolean lava = chunk.getBlockState(pos.set(x, y, z)).is(Blocks.LAVA);
                    pos.set(x, y - 1, z);
                    if (below.is(BlockTags.BASE_STONE_OVERWORLD) || below.is(Blocks.CALCITE) || below.is(Blocks.BASALT)
                            || below.is(Blocks.BLACKSTONE)) {
                        int h = hash(x, y, z);
                        chunk.setBlockState(pos, lava ? LAVA_BED[h % LAVA_BED.length] : BED[h % BED.length], 0);
                    }
                    boolean flow = below.isAir();
                    if (!flow && lx > 0) flow = chunk.getBlockState(pos.set(x - 1, y, z)).isAir();
                    if (!flow && lx < 15) flow = chunk.getBlockState(pos.set(x + 1, y, z)).isAir();
                    if (!flow && lz > 0) flow = chunk.getBlockState(pos.set(x, y, z - 1)).isAir();
                    if (!flow && lz < 15) flow = chunk.getBlockState(pos.set(x, y, z + 1)).isAir();
                    if (flow) chunk.markPosForPostProcessing(pos.set(x, y, z));
                }
            }
        }
    }

    /**
     * Keeps only water a basin holds. The fill puts water in every air block of a lake cave's cells up to the lake level,
     * but the cave's own floor often climbs out of the basin below that level (or the cells reach past the cave's wall
     * into another cave): that water had nothing to hold it, and it stood as walls and poured down the cave's terraces
     * once the chunk loaded (Calvin, Oct 27, X -2773 Y 137 Z -2396). A placed block next to open air beside it or under
     * it is removed, and so are the blocks that leaves exposed, until all the water left is held by rock (or by more
     * water). Blocks past the chunk's edge can't be seen and count as holding.
     */
    private static void drain(ChunkAccess chunk, boolean[] placed, int minY, int height) {
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        int minSection = chunk.getMinSectionY();
        int size = 256 * height;
        int[] queue = new int[1024];
        int head = 0, tail = 0;
        boolean[] queued = new boolean[size];
        for (int i = 0; i < size; i++) {
            if (!placed[i]) continue;
            if (tail == queue.length) queue = java.util.Arrays.copyOf(queue, queue.length * 2);
            queue[tail++] = i;
            queued[i] = true;
        }
        int[] dx = {1, -1, 0, 0, 0}, dz = {0, 0, 1, -1, 0}, dy = {0, 0, 0, 0, -1};
        while (head < tail) {
            int i = queue[head++];
            queued[i] = false;
            if (!placed[i]) continue;
            int lx = i & 15, lz = (i >> 4) & 15, y = i / 256 + minY;
            boolean leaks = false;
            for (int k = 0; k < 5 && !leaks; k++) {
                int nx = lx + dx[k], nz = lz + dz[k], ny = y + dy[k];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < minY) continue;   // unseen: holds
                int ni = ((ny - minY) * 16 + nz) * 16 + nx;
                if (placed[ni]) continue;
                LevelChunkSection sec = sections[(ny >> 4) - minSection];
                if (sec == null || sec.getBlockState(nx, ny & 15, nz).isAir()) leaks = true;
            }
            if (!leaks) continue;
            placed[i] = false;
            LevelChunkSection own = sections[(y >> 4) - minSection];
            if (own != null) own.setBlockState(lx, y & 15, lz, AIR, false);
            // Its neighbours beside and above may now leak.
            int[] ox = {1, -1, 0, 0, 0}, oz = {0, 0, 1, -1, 0}, oy = {0, 0, 0, 0, 1};
            for (int k = 0; k < 5; k++) {
                int nx = lx + ox[k], nz = lz + oz[k], ny = y + oy[k];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny >= minY + height) continue;
                int ni = ((ny - minY) * 16 + nz) * 16 + nx;
                if (!placed[ni] || queued[ni]) continue;
                if (tail == queue.length) {
                    // Compact the queue before growing it.
                    System.arraycopy(queue, head, queue, 0, tail - head);
                    tail -= head;
                    head = 0;
                    if (tail == queue.length) queue = java.util.Arrays.copyOf(queue, queue.length * 2);
                }
                queue[tail++] = ni;
                queued[ni] = true;
            }
        }
    }

    private static int hash(int x, int y, int z) {
        int h = x * 0x27d4eb2d ^ y * 0x165667b1 ^ z * 0x9e3779b1;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        return (h ^ (h >>> 13)) >>> 1;
    }
}
