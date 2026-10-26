package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The rock of the stone-variant cave biomes (caves plan v1.5 part one): every stone block inside an andesite, diorite
 * or granite cave biome becomes that stone, and every stone and deepslate block inside a tuff cave becomes tuff, so
 * caves cut through them (noise caves and carvers alike) show those walls, floors and ceilings, and so does mining.
 *
 * <p>Runs in the terrain pass on the proto chunk (cheap block writes, no updates) and reads the chunk's own stored
 * biomes (the cave biome layer, {@code pipeline.CaveBiomes}), skipping every section without a stone-variant cave.
 * The 4x4x4 biome cells are blurred by a hashed one-block jitter so their edges look like natural contacts rather
 * than steps. Ores are placed later (features) and replace these stones as they do stone.
 *
 * <p>Painting here rather than in the material rule avoids a biome lookup for every stone block of the world.
 */
public final class CaveStone {
    private static final Logger LOG = LoggerFactory.getLogger(CaveStone.class);

    private static final byte NONE = 0, ANDESITE = 1, DIORITE = 2, GRANITE = 3, TUFF = 4;
    private static final ResourceKey<Biome> ANDESITE_CAVES = key("andesite_caves"), DIORITE_CAVES = key("diorite_caves"),
            GRANITE_CAVES = key("granite_caves"), TUFF_CAVES = key("tuff_caves");
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState[] ROCK = {null, Blocks.ANDESITE.defaultBlockState(), Blocks.DIORITE.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(), Blocks.TUFF.defaultBlockState()};

    private CaveStone() {
    }

    private static ResourceKey<Biome> key(String name) {
        return ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("terra", name));
    }

    public static void paint(ChunkAccess chunk) {
        if (!TerrainDiffusionConfig.caveBiomes()) return;
        try {
            paintUnchecked(chunk);
        } catch (Throwable t) {
            LOG.error("Terra: cave stone failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static byte kind(Holder<Biome> h, Map<Holder<Biome>, Byte> memo) {
        Byte k = memo.get(h);
        if (k == null) {
            k = h.is(ANDESITE_CAVES) ? ANDESITE : h.is(DIORITE_CAVES) ? DIORITE : h.is(GRANITE_CAVES) ? GRANITE
                    : h.is(TUFF_CAVES) ? TUFF : NONE;
            memo.put(h, k);
        }
        return k;
    }

    private static void paintUnchecked(ChunkAccess chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        Map<Holder<Biome>, Byte> memo = new IdentityHashMap<>();
        // Per section: the stone kind of each 4x4x4 biome cell (index (qy * 4 + qz) * 4 + qx), or null if none.
        byte[][] cells = new byte[sections.length][];
        boolean any = false;
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection sec = sections[s];
            if (sec == null || sec.hasOnlyAir()) continue;
            if (!sec.getBiomes().maybeHas(h -> kind(h, memo) != NONE)) continue;
            byte[] c = new byte[64];
            boolean has = false;
            for (int qy = 0; qy < 4; qy++)
                for (int qz = 0; qz < 4; qz++)
                    for (int qx = 0; qx < 4; qx++) {
                        byte k = kind(sec.getNoiseBiome(qx, qy, qz), memo);
                        c[(qy * 4 + qz) * 4 + qx] = k;
                        has |= k != NONE;
                    }
            if (has) {
                cells[s] = c;
                any = true;
            }
        }
        if (!any) return;

        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        for (int s = 0; s < sections.length; s++) {
            if (cells[s] == null) continue;
            LevelChunkSection sec = sections[s];
            int y0 = chunk.getSectionYFromSectionIndex(s) << 4;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState st = sec.getBlockState(lx, ly, lz);
                        boolean deep;
                        if (st == STONE) deep = false;
                        else if (st.is(Blocks.DEEPSLATE)) deep = true;
                        else continue;
                        // Jitter the cell lookup by -1..1 block on each axis (hashed per block).
                        int h = hash(x0 + lx, y0 + ly, z0 + lz);
                        int jx = Math.max(0, Math.min(15, lx + (h & 3) % 3 - 1));
                        int jz = Math.max(0, Math.min(15, lz + ((h >>> 2) & 3) % 3 - 1));
                        int jy = ly + ((h >>> 4) & 3) % 3 - 1;
                        byte[] cs = cells[s];
                        if (jy < 0) {
                            if (s > 0) { cs = cells[s - 1]; jy = 15; } else jy = 0;
                        } else if (jy > 15) {
                            if (s + 1 < sections.length) { cs = cells[s + 1]; jy = 0; } else jy = 15;
                        }
                        // A neighbouring section without cave stone counts as plain rock there.
                        byte k = cs == null ? NONE : cs[((jy >> 2) * 4 + (jz >> 2)) * 4 + (jx >> 2)];
                        if (k == NONE || (deep && k != TUFF)) continue;
                        sec.setBlockState(lx, ly, lz, ROCK[k], false);
                    }
                }
            }
        }
    }

    private static int hash(int x, int y, int z) {
        int h = x * 0x27d4eb2d ^ y * 0x165667b1 ^ z * 0x9e3779b1;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        return h ^ (h >>> 13);
    }
}
