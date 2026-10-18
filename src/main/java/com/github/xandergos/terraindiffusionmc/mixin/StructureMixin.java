package com.github.xandergos.terraindiffusionmc.mixin;

import com.github.xandergos.terraindiffusionmc.config.TerrainDiffusionConfig;
import com.github.xandergos.terraindiffusionmc.world.InlandWater;
import com.github.xandergos.terraindiffusionmc.world.TerrainDiffusionBiomeSource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Structures pick their spot from the terrain before the mod's lakes and streams are filled in, so an igloo or a
 * village could start on what becomes open water. Any structure whose start lands on (or right beside) inland water
 * is skipped, and land structures whose pieces spread over lakes, streams or the sea are skipped too. Shipwrecks,
 * ocean ruins and ocean monuments are not checked for water, but are thinned out (see {@code oceans.structure_frequency}),
 * since Terra's oceans are much larger than vanilla's.
 */
@Mixin(Structure.class)
public abstract class StructureMixin {

    /**
     * Land structures that would spread over water are dropped. The start check below only looks at the centre, so a
     * village whose centre was on land could still build its paths and houses out over a lake. This looks at every
     * piece's footprint, every 4 blocks, against the mod's lakes and streams and the sea; more than 3% water (a
     * stream crossing a village is fine, a lake or the sea is not) and the structure is skipped.
     */
    @Inject(method = "generate", at = @At("RETURN"), cancellable = true, require = 0)
    private void terra$noLandStructuresOverWater(Holder<Structure> selected, ResourceKey<Level> dimension,
                                                 RegistryAccess registryAccess, ChunkGenerator chunkGenerator,
                                                 BiomeSource biomeSource, Climate.Sampler climateSampler,
                                                 RandomState randomState, StructureTemplateManager structureTemplateManager,
                                                 long seed, ChunkPos sourceChunkPos, int references,
                                                 LevelHeightAccessor heightAccessor, Predicate<Holder<Biome>> validBiome,
                                                 CallbackInfoReturnable<StructureStart> cir) {
        StructureStart start = cir.getReturnValue();
        if (start == null || !start.isValid() || !(biomeSource instanceof TerrainDiffusionBiomeSource)) return;
        Structure self = (Structure) (Object) this;
        if (!terra$isLandStructure(self)) return;
        try {
            List<int[]> boxes = new ArrayList<>();
            for (StructurePiece piece : start.getPieces()) {
                BoundingBox bb = piece.getBoundingBox();
                boxes.add(new int[]{bb.minX(), bb.minZ(), bb.maxX(), bb.maxZ()});
            }
            if (InlandWater.wetFraction(boxes, 4) > MAX_WET_FRACTION) cir.setReturnValue(StructureStart.INVALID_START);
        } catch (RuntimeException e) {
            // Never let this check break structure generation.
        }
    }

    private static final double MAX_WET_FRACTION = 0.03;

    /** Structures built on dry land at the surface; ocean, underground and water-loving ones are not checked. */
    private static boolean terra$isLandStructure(Structure s) {
        StructureType<?> t = s.type();
        if (t == StructureType.DESERT_PYRAMID || t == StructureType.JUNGLE_TEMPLE || t == StructureType.IGLOO
                || t == StructureType.WOODLAND_MANSION) return true;
        // Jigsaw structures: villages and pillager outposts build at the surface; ancient cities, trial chambers and
        // trail ruins are underground or buried.
        return t == StructureType.JIGSAW && s.step() == GenerationStep.Decoration.SURFACE_STRUCTURES;
    }

    /** Shipwrecks, ocean ruins and ocean monuments. */
    private static boolean terra$isOceanStructure(Structure s) {
        StructureType<?> t = s.type();
        return t == StructureType.SHIPWRECK || t == StructureType.OCEAN_RUIN || t == StructureType.OCEAN_MONUMENT;
    }

    /**
     * Terra's oceans cover far more of the world than vanilla's, and vanilla spaces these for small seas, so they
     * turn up everywhere. Each candidate is kept with probability {@code oceans.structure_frequency}, decided by a
     * hash of the world seed, chunk and structure type, so it is the same every time it is asked.
     */
    private static boolean terra$keepOceanStructure(Structure s, Structure.GenerationContext context) {
        float keep = TerrainDiffusionConfig.oceanStructureFrequency();
        if (keep >= 1f) return true;
        if (keep <= 0f) return false;
        ChunkPos c = context.chunkPos();
        long h = context.seed() ^ 0x5DEECE66DL;
        h ^= (long) c.x() * 0x9E3779B97F4A7C15L;
        h ^= (long) c.z() * 0xC2B2AE3D27D4EB4FL;
        StructureType<?> t = s.type();
        long salt = t == StructureType.SHIPWRECK ? 1 : t == StructureType.OCEAN_RUIN ? 2 : 3;
        h ^= salt * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 40) / (double) (1L << 24) < keep;
    }

    @Inject(method = "findValidGenerationPoint", at = @At("RETURN"), cancellable = true, require = 0)
    private void terra$noStructuresOnInlandWater(Structure.GenerationContext context,
                                                 CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        Optional<Structure.GenerationStub> stub = cir.getReturnValue();
        if (stub == null || stub.isEmpty()) return;
        if (!(context.biomeSource() instanceof TerrainDiffusionBiomeSource)) return;
        Structure self = (Structure) (Object) this;
        if (terra$isOceanStructure(self)) {
            // Thinned here rather than after generation, so /locate, maps and the actual world all agree.
            if (!terra$keepOceanStructure(self, context)) cir.setReturnValue(Optional.empty());
            return;
        }
        try {
            BlockPos pos = stub.get().position();
            if (InlandWater.isWaterNear(pos.getX(), pos.getZ(), 4)) cir.setReturnValue(Optional.empty());
        } catch (RuntimeException e) {
            // Never let this check break structure generation.
        }
    }
}
