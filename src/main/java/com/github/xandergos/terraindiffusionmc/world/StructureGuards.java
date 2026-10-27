package com.github.xandergos.terraindiffusionmc.world;

import com.github.xandergos.terraindiffusionmc.mixin.BeardifierAccessor;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;

import java.util.ArrayList;
import java.util.List;

/**
 * Terra's stand-in for vanilla's structure terrain adaptations underground (caves plan v1.5 part three). Vanilla adds a
 * "beard" to its density so buried structures sit in solid rock (trial chambers "encapsulate", strongholds "bury") and
 * ancient cities get a cleared box ("beard_box"). Terra's density never included it (only the village pads,
 * {@link StructurePads}), and with Terra's noise caves that would let caves cut through trial chambers and strongholds.
 *
 * <p>So, for pieces of those structures near a chunk: around encapsulated and buried pieces (within
 * {@link #MARGIN} blocks of their box) the rock is kept solid, so caves stop short of them; inside an ancient city's
 * boxes everything from the floor up is cleared, and the few blocks under the floor kept solid, as vanilla's beard box
 * does. The pieces come from the chunk's own beardifier, which holds every piece within its reach, so neighbouring
 * chunks agree.
 */
final class StructureGuards {
    static final int MARGIN = 3, FLOOR = 6;

    private StructureGuards() {
    }

    /** The guarded pieces whose reach touches the block rectangle [x0, x1] x [z0, z1], or an empty list. */
    static List<Beardifier.Rigid> near(Beardifier beardifier, int x0, int z0, int x1, int z1) {
        if (beardifier == null || beardifier == Beardifier.EMPTY) return List.of();
        List<Beardifier.Rigid> out = null;
        for (Beardifier.Rigid r : ((BeardifierAccessor) beardifier).terra$getPieces()) {
            TerrainAdjustment t = r.terrainAdjustment();
            if (t != TerrainAdjustment.ENCAPSULATE && t != TerrainAdjustment.BURY && t != TerrainAdjustment.BEARD_BOX) continue;
            BoundingBox b = r.box();
            if (b.maxX() + MARGIN < x0 || b.minX() - MARGIN > x1 || b.maxZ() + MARGIN < z0 || b.minZ() - MARGIN > z1) continue;
            if (out == null) out = new ArrayList<>();
            out.add(r);
        }
        return out == null ? List.of() : out;
    }

    /** The pieces whose reach covers column (x, z), into {@code col} (cleared first). */
    static void column(List<Beardifier.Rigid> pieces, int x, int z, List<Beardifier.Rigid> col) {
        col.clear();
        for (Beardifier.Rigid r : pieces) {
            BoundingBox b = r.box();
            if (x >= b.minX() - MARGIN && x <= b.maxX() + MARGIN && z >= b.minZ() - MARGIN && z <= b.maxZ() + MARGIN) col.add(r);
        }
    }

    /** The density at (x, y, z) after the guards of this column's pieces ({@code v}: the density before). */
    static float apply(List<Beardifier.Rigid> col, int x, int y, int z, float v) {
        for (Beardifier.Rigid r : col) {
            BoundingBox b = r.box();
            if (r.terrainAdjustment() == TerrainAdjustment.BEARD_BOX) {
                if (x < b.minX() || x > b.maxX() || z < b.minZ() || z > b.maxZ()) continue;
                int floor = b.minY() + r.groundLevelDelta();
                if (y >= floor && y <= b.maxY()) v = Math.min(v, -1f);
                else if (y < floor && y >= floor - FLOOR) v = Math.max(v, 1f);
            } else if (y >= b.minY() - MARGIN && y <= b.maxY() + MARGIN) {
                v = Math.max(v, 1f);
            }
        }
        return v;
    }
}
