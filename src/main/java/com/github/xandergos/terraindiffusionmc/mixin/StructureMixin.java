package com.github.xandergos.terraindiffusionmc.mixin;

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
 * is skipped, and land structures whose pieces spread over lakes, streams or the sea are skipped too. Sea and ocean
 * structures are unaffected.
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

    @Inject(method = "findValidGenerationPoint", at = @At("RETURN"), cancellable = true, require = 0)
    private void terra$noStructuresOnInlandWater(Structure.GenerationContext context,
                                                 CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        Optional<Structure.GenerationStub> stub = cir.getReturnValue();
        if (stub == null || stub.isEmpty()) return;
        if (!(context.biomeSource() instanceof TerrainDiffusionBiomeSource)) return;
        try {
            BlockPos pos = stub.get().position();
            if (InlandWater.isWaterNear(pos.getX(), pos.getZ(), 4)) cir.setReturnValue(Optional.empty());
        } catch (RuntimeException e) {
            // Never let this check break structure generation.
        }
    }
}
