package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.mixin.BeardifierAccessor;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

import java.util.List;

/**
 * Ground pads under village houses and other "beard_thin" structures (villages, pillager outposts) in Terra worlds.
 *
 * <p>Vanilla fits such buildings to the terrain by adding a small "beard" term to the density (fill below the floor,
 * clear above it). It is sized for vanilla's density, where a unit moves the ground many blocks; Terra's density is
 * the surface height minus y, one unit per block, so the beard would move the ground half a block, and Terra's
 * settings didn't include it at all: houses on slopes floated or sank into the hill (Calvin, Oct 21).
 *
 * <p>Instead the terrain height itself is pulled to each building's floor level: fully under the building and one
 * block around it, fading back to the natural ground over {@link #FADE_BLOCKS}, so it fills under the house and cuts
 * the hill out of it with a short slope. The pieces come from the chunk's own beardifier (every piece within 12
 * blocks of the chunk), and the fade stays inside that, so neighbouring chunks agree. Buildings more than
 * {@link #MAX_SHIFT} blocks off the natural ground are left alone (a pad that big would look worse than the problem).
 */
public final class StructurePads {

    /** Fully at the floor level up to this far (blocks) outside the building's footprint. */
    static final float INNER_BLOCKS = 1f;
    /** Back to the natural ground by this far (blocks); must stay under the beardifier's 12-block reach. */
    static final float FADE_BLOCKS = 9f;
    /** Largest change (blocks) a pad may make to the natural ground. */
    static final int MAX_SHIFT = 24;

    private StructurePads() {
    }

    /**
     * The terrain's first air block Y at column (x, z) with the pads applied.
     *
     * @param natural the natural first air block Y there
     */
    public static int adjust(Beardifier beardifier, int x, int z, int natural) {
        List<Beardifier.Rigid> pieces = ((BeardifierAccessor) beardifier).terra$getPieces();
        if (pieces.isEmpty()) return natural;
        float wMax = 0f, sumW = 0f, sumG = 0f;
        for (Beardifier.Rigid r : pieces) {
            if (r.terrainAdjustment() != TerrainAdjustment.BEARD_THIN) continue;
            BoundingBox box = r.box();
            int dx = Math.max(0, Math.max(box.minX() - x, x - box.maxX()));
            int dz = Math.max(0, Math.max(box.minZ() - z, z - box.maxZ()));
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d >= FADE_BLOCKS) continue;
            int floor = box.minY() + r.groundLevelDelta();   // the first air block above the ground the piece expects
            if (Math.abs(floor - natural) > MAX_SHIFT) continue;
            float t = Math.max(0f, Math.min(1f, (d - INNER_BLOCKS) / (FADE_BLOCKS - INNER_BLOCKS)));
            float w = 1f - t * t * (3f - 2f * t);
            if (w <= 0f) continue;
            // Sharpened weights for the average, so a column under one house follows that house's floor.
            float w6 = w * w * w;
            w6 *= w6;
            sumW += w6;
            sumG += w6 * floor;
            wMax = Math.max(wMax, w);
        }
        if (wMax <= 0f) return natural;
        float floor = sumG / sumW;
        return Math.round(natural + wMax * (floor - natural));
    }
}
