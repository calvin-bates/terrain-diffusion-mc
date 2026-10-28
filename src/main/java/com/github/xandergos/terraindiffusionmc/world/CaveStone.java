package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveBiomes;
import com.github.xandergos.terraindiffusionmc.pipeline.CaveHalls;
import com.github.xandergos.terraindiffusionmc.pipeline.LocalTerrainProvider;
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

    private static final byte NONE = 0, ANDESITE = 1, DIORITE = 2, GRANITE = 3, TUFF = 4, SALT = 5, KARST = 6;
    // Part three round A.
    private static final byte MOLTEN = 7, TUBES = 8, CRYSTAL = 9, SLOT = 10, GLACIER = 11, GEODE = 12;
    // Round C.
    // 13 was the incursion's pillars (polished basalt) until the incursion became WWOO's plain sculk caves (Oct 27).
    private static final byte SELENITE = 14;
    private static final ResourceKey<Biome> GIANT_SELENITE_BEAMS = key("giant_selenite_beams");
    /** The selenite chamber's hot walls: granite and tuff with smooth basalt and magma seams. */
    private static final BlockState[] HOT_BEDS = {b(Blocks.GRANITE), b(Blocks.TUFF), b(Blocks.GRANITE),
            b(Blocks.SMOOTH_BASALT), b(Blocks.TUFF), b(Blocks.MAGMA_BLOCK), b(Blocks.GRANITE)};
    private static final ResourceKey<Biome> MOLTEN_CAVERN = key("molten_cavern"), LAVA_TUBES = key("lava_tubes"),
            CRYSTALLINE_CHASM = key("crystalline_chasm"), SLOT_CANYON_CAVES = key("slot_canyon_caves"),
            GLACIER_CAVES = key("glacier_caves"), GIANT_GEODE_HALL = key("giant_geode_hall");
    private static BlockState b(net.minecraft.world.level.block.Block block) {
        return block.defaultBlockState();
    }
    /** Molten caverns: blackstone and basalt with magma seams. */
    private static final BlockState[] MOLTEN_BEDS = {b(Blocks.BLACKSTONE), b(Blocks.BASALT), b(Blocks.BLACKSTONE),
            b(Blocks.SMOOTH_BASALT), b(Blocks.MAGMA_BLOCK), b(Blocks.BLACKSTONE), b(Blocks.BASALT)};
    /** Lava tubes: cooled lava, basalt and blackstone. */
    private static final BlockState[] TUBE_BEDS = {b(Blocks.BASALT), b(Blocks.SMOOTH_BASALT), b(Blocks.BLACKSTONE),
            b(Blocks.BASALT), b(Blocks.TUFF)};
    /** Slot canyons: sandstone layers, red under badlands, canyons and painted mountains. */
    private static final BlockState[] SAND_BEDS = {b(Blocks.SANDSTONE), b(Blocks.SMOOTH_SANDSTONE), b(Blocks.SANDSTONE),
            b(Blocks.DYED_TERRACOTTA.yellow()), b(Blocks.SANDSTONE), b(Blocks.SMOOTH_SANDSTONE)};
    private static final BlockState[] RED_BEDS = {b(Blocks.RED_SANDSTONE), b(Blocks.SMOOTH_RED_SANDSTONE), b(Blocks.RED_SANDSTONE),
            b(Blocks.DYED_TERRACOTTA.orange()), b(Blocks.RED_SANDSTONE), b(Blocks.TERRACOTTA)};
    /** Glacier caves: blue ice, packed ice, ice and old snow. */
    private static final BlockState[] ICE_BEDS = {b(Blocks.PACKED_ICE), b(Blocks.BLUE_ICE), b(Blocks.PACKED_ICE), b(Blocks.ICE),
            b(Blocks.BLUE_ICE), b(Blocks.SNOW_BLOCK), b(Blocks.PACKED_ICE)};
    private static final ResourceKey<Biome> ANDESITE_CAVES = key("andesite_caves"), DIORITE_CAVES = key("diorite_caves"),
            GRANITE_CAVES = key("granite_caves"), TUFF_CAVES = key("tuff_caves"), SALT_CAVES = key("salt_caves"),
            KARST_CAVES = key("karst_caves");
    /** Karst caves (part two): limestone, as Terra's other limestone, a mix of calcite and diorite, in thick beds. */
    private static final BlockState[] KARST_BEDS = {Blocks.CALCITE.defaultBlockState(), Blocks.DIORITE.defaultBlockState(),
            Blocks.CALCITE.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.DIORITE.defaultBlockState(),
            Blocks.CALCITE.defaultBlockState()};
    /** Salt caves (part two): rock salt layers of calcite with white and pink terracotta bands. */
    private static final BlockState[] SALT_LAYERS = {Blocks.CALCITE.defaultBlockState(), Blocks.CALCITE.defaultBlockState(),
            Blocks.DYED_TERRACOTTA.white().defaultBlockState(), Blocks.CALCITE.defaultBlockState(),
            Blocks.DYED_TERRACOTTA.pink().defaultBlockState(), Blocks.DYED_TERRACOTTA.white().defaultBlockState(),
            Blocks.CALCITE.defaultBlockState(), Blocks.DYED_TERRACOTTA.pink().defaultBlockState()};
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState[] ROCK = {null, Blocks.ANDESITE.defaultBlockState(), Blocks.DIORITE.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(), Blocks.TUFF.defaultBlockState(), Blocks.CALCITE.defaultBlockState(),
            Blocks.CALCITE.defaultBlockState()};

    private CaveStone() {
    }

    private static ResourceKey<Biome> key(String name) {
        return ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("orogeny", name));
    }

    public static void paint(ChunkAccess chunk) {
        if (!TerrainDiffusionConfig.caveBiomes()) return;
        try {
            paintUnchecked(chunk);
        } catch (Throwable t) {
            LOG.error("Orogeny: cave stone failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static byte kind(Holder<Biome> h, Map<Holder<Biome>, Byte> memo) {
        Byte k = memo.get(h);
        if (k == null) {
            k = h.is(ANDESITE_CAVES) ? ANDESITE : h.is(DIORITE_CAVES) ? DIORITE : h.is(GRANITE_CAVES) ? GRANITE
                    : h.is(TUFF_CAVES) ? TUFF : h.is(SALT_CAVES) ? SALT : h.is(KARST_CAVES) ? KARST
                    : h.is(MOLTEN_CAVERN) ? MOLTEN : h.is(LAVA_TUBES) ? TUBES : h.is(CRYSTALLINE_CHASM) ? CRYSTAL
                    : h.is(SLOT_CANYON_CAVES) ? SLOT : h.is(GLACIER_CAVES) ? GLACIER : h.is(GIANT_GEODE_HALL) ? GEODE
                    : h.is(GIANT_SELENITE_BEAMS) ? SELENITE : NONE;
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
        byte[] red = null;   // per column, for slot canyons: 0 unknown, 1 yellow, 2 red
        CaveBiomes layer = null;
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
                        if (k == NONE || (deep && (k <= GRANITE || k == KARST))) continue;
                        BlockState rock = k < ROCK.length ? ROCK[k] : null;
                        int bx = x0 + lx, bz = z0 + lz, by = y0 + ly;
                        if (k == SLOT) {
                            if (red == null) red = new byte[256];
                            int c = lz * 16 + lx;
                            if (red[c] == 0) {
                                short sb = CaveSurface.get().biomeAt(bx, bz);
                                red[c] = (byte) (sb == 26 || sb == 89 || sb == 90 || sb == 79 ? 2 : 1);
                            }
                            rock = bands(red[c] == 2 ? RED_BEDS : SAND_BEDS, bx, by, bz, 3, 4);
                        } else if (k == MOLTEN) {
                            rock = bands(MOLTEN_BEDS, bx, by, bz, 2, 5);
                        } else if (k == TUBES) {
                            rock = bands(TUBE_BEDS, bx, by, bz, 2, 4);
                        } else if (k == GLACIER) {
                            rock = bands(ICE_BEDS, bx, by, bz, 2, 5);
                        } else if (k == CRYSTAL) {
                            // Calcite veins through the host rock.
                            if (Math.floorMod(Math.floorDiv(by * 2 + (bx >> 2) - (bz >> 2), 5) * 0x9E3779B1 >>> 9, 3) != 0) continue;
                            rock = b(Blocks.CALCITE);
                        } else if (k == GEODE) {
                            if (layer == null) layer = CaveBiomes.forSeed(LocalTerrainProvider.getSeed());
                            rock = geodeShell(layer, bx, by, bz, h);
                            if (rock == null) continue;
                        } else if (k == SELENITE) {
                            if (layer == null) layer = CaveBiomes.forSeed(LocalTerrainProvider.getSeed());
                            rock = seleniteRock(layer, bx, by, bz);
                            if (rock == null) continue;
                        }
                        else if (k == SALT) {
                            // Gently tilted layers 1-3 blocks thick: the band index follows y plus a slow tilt.
                            int band = Math.floorDiv(by * 3 + (bx >> 3) + (bz >> 4), 5);
                            rock = SALT_LAYERS[Math.floorMod(band * 0x9E3779B1 >>> 7, SALT_LAYERS.length)];
                        } else if (k == KARST) {
                            int band = Math.floorDiv(by * 2 + (bx >> 2) + (bz >> 3), 7);
                            rock = KARST_BEDS[Math.floorMod(band * 0x9E3779B1 >>> 7, KARST_BEDS.length)];
                        }
                        sec.setBlockState(lx, ly, lz, rock, false);
                    }
                }
            }
        }
    }

    /** Gently tilted beds: the band index follows y (times {@code thick}) plus a slow tilt, hashed to a block. */
    private static BlockState bands(BlockState[] beds, int x, int y, int z, int thick, int per) {
        int band = Math.floorDiv(y * thick + (x >> 3) + (z >> 4), per);
        return beds[Math.floorMod(band * 0x9E3779B1 >>> 7, beds.length)];
    }

    /**
     * The giant geode hall's lining, from the hall surface outwards: amethyst (with some budding amethyst), calcite,
     * smooth basalt, like a vanilla geode at hall scale. Null outside the lining.
     */
    private static BlockState geodeShell(CaveBiomes layer, int x, int y, int z, int h) {
        CaveHalls.Hall hall = hallAt(layer, x, y, z, CaveBiomes.GIANT_GEODE_HALL);
        if (hall == null) return null;
        float out = (CaveBiomes.hallDistance(hall.id(), hall.cell(), x, y, z) - 1f) * hall.ry();
        if (out < -2f || out > 4.5f) return null;
        if (out < 1.6f) return (h & 15) == 0 ? b(Blocks.BUDDING_AMETHYST) : b(Blocks.AMETHYST_BLOCK);
        if (out < 3.2f) return b(Blocks.CALCITE);
        return b(Blocks.SMOOTH_BASALT);
    }

    /** The hall of the unwarped region cell holding a block, if it is one of {@code id} (memoised per cell). */
    private static CaveHalls.Hall hallAt(CaveBiomes layer, int x, int y, int z, short id) {
        long cell = CaveBiomes.unwarpedCell(x, y, z);
        HallMemo m = HALL_MEMO.get();
        if (m.layer != layer || m.cell != cell) {
            m.layer = layer;
            m.cell = cell;
            m.hall = CaveHalls.of(layer, CaveSurface.get(), cell);
        }
        return m.hall != null && m.hall.id() == id ? m.hall : null;
    }

    private static final class HallMemo {
        CaveBiomes layer;
        long cell;
        CaveHalls.Hall hall;
    }

    private static final ThreadLocal<HallMemo> HALL_MEMO = ThreadLocal.withInitial(HallMemo::new);

    /** Selenite chamber rock: calcite beams with amethyst tips (CaveHalls), hot banded rock around them. */
    private static BlockState seleniteRock(CaveBiomes layer, int x, int y, int z) {
        CaveHalls.Hall hall = hallAt(layer, x, y, z, CaveBiomes.GIANT_SELENITE_BEAMS);
        if (hall != null) {
            CaveHalls.Beam beam = CaveHalls.beamAt(CaveHalls.beams(LocalTerrainProvider.getSeed(), hall.cx(), hall.cy(),
                    hall.cz(), hall.rx(), hall.ry()), x, y, z);
            if (beam != null) return CaveHalls.isTip(beam, x, y, z) ? b(Blocks.AMETHYST_BLOCK) : b(Blocks.CALCITE);
        }
        return bands(HOT_BEDS, x, y, z, 2, 5);
    }

    private static int hash(int x, int y, int z) {
        int h = x * 0x27d4eb2d ^ y * 0x165667b1 ^ z * 0x9e3779b1;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        return h ^ (h >>> 13);
    }
}
