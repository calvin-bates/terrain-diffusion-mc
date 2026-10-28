package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Frostfire caves, Terralith's way (Oct 27): after the features step, every dripstone block inside a frostfire cave
 * turns to blue ice (so vanilla's large dripstone pillars become huge blue ice pillars) and every lava block to
 * deepslate (no lava in frostfire caves). Done for the chunk being decorated and its eight neighbours, because the
 * pillars of one chunk reach into the next. Direct section writes; all four blocks are solid or fluid-for-solid, so
 * heightmaps don't change.
 */
public final class FrostfireCaves {
    private static final Logger LOG = LoggerFactory.getLogger(FrostfireCaves.class);
    private static final ResourceKey<Biome> FROSTFIRE = ResourceKey.create(Registries.BIOME,
            Identifier.fromNamespaceAndPath("orogeny", "frostfire_caves"));
    private static final BlockState BLUE_ICE = Blocks.BLUE_ICE.defaultBlockState();
    private static final BlockState DEEPSLATE = Blocks.DEEPSLATE.defaultBlockState();

    private FrostfireCaves() {
    }

    public static void finish(WorldGenLevel level, ChunkAccess chunk) {
        if (!TerrainDiffusionConfig.caveBiomes()) return;
        try {
            ChunkPos cp = chunk.getPos();
            for (int dz = -1; dz <= 1; dz++)
                for (int dx = -1; dx <= 1; dx++) {
                    ChunkAccess c = dx == 0 && dz == 0 ? chunk : level.getChunk(cp.x() + dx, cp.z() + dz);
                    if (c != null) convert(c);
                }
        } catch (Throwable t) {
            LOG.error("Orogeny: frostfire pass failed for chunk {}", chunk.getPos(), t);
        }
    }

    private static void convert(ChunkAccess chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        for (LevelChunkSection sec : sections) {
            if (sec == null || sec.hasOnlyAir()) continue;
            if (!sec.getBiomes().maybeHas(h -> h.is(FROSTFIRE))) continue;
            if (!sec.getStates().maybeHas(s -> s.is(Blocks.DRIPSTONE_BLOCK) || s.is(Blocks.LAVA))) continue;
            boolean[] cell = new boolean[64];
            for (int qy = 0; qy < 4; qy++)
                for (int qz = 0; qz < 4; qz++)
                    for (int qx = 0; qx < 4; qx++) {
                        Holder<Biome> h = sec.getNoiseBiome(qx, qy, qz);
                        cell[(qy * 4 + qz) * 4 + qx] = h.is(FROSTFIRE);
                    }
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        if (!cell[((y >> 2) * 4 + (z >> 2)) * 4 + (x >> 2)]) continue;
                        BlockState s = sec.getBlockState(x, y, z);
                        if (s.is(Blocks.DRIPSTONE_BLOCK)) sec.setBlockState(x, y, z, BLUE_ICE, false);
                        else if (s.is(Blocks.LAVA)) sec.setBlockState(x, y, z, DEEPSLATE, false);
                    }
        }
    }
}
