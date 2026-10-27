package com.github.xandergos.terraindiffusionmc.pipeline;

import java.util.ArrayList;
import java.util.List;

/**
 * Where water caves meet the surface (caves plan v1.5 part two): shafts cut through the cave roof.
 *
 * <ul>
 *   <li><b>River sinks.</b> Under some underground lake and glowworm grotto regions, a narrow shaft drops from the bed of
 *       the nearest river or lake into a pool chamber at the region's water level, so the river visibly pours into the
 *       cave (world/CaveWater lets the water above the shaft flow).</li>
 *   <li><b>Cenotes.</b> Over some karst cave regions, a wide round sinkhole open to the sky drops to a pool chamber at
 *       the region's water level.</li>
 * </ul>
 *
 * <p>Sites sit on the cave layer's region anchors (every {@link CaveBiomes#REGION_XZ} blocks), decided from the cave
 * biome at the anchor below its ground and a hash of the region, so every chunk agrees. Pure Java.
 */
public final class CaveSites {
    private CaveSites() {
    }

    /** Share of eligible regions that get a river sink or a cenote. */
    public static float SINK_CHANCE = 0.5f, CENOTE_CHANCE = 0.45f;
    /** How far from the anchor a sink looks for a river or lake column. */
    private static final int SINK_REACH = 44;
    /** Depth under the anchor's ground at which a site asks the cave layer for its region. */
    private static final int PROBE_DEPTH = 44;
    /** Widest a site gets (cenote radius plus its chamber), for finding sites near an area. */
    public static final int MAX_REACH = 24;

    /** A vertical shaft from {@code top} down to {@code bottom}, with a round pool chamber at its foot. */
    public record Shaft(int x, int z, float radius, int bottom, int top, boolean sink, float chamberRadius) {
        /** Density (negative is air, in blocks) of this shaft at a point, or +inf if the point is outside its reach. */
        public float value(int px, int py, int pz) {
            float dx = px - x, dz = pz - z;
            float d2 = dx * dx + dz * dz;
            float v = Float.POSITIVE_INFINITY;
            if (py <= top && py >= bottom) v = (float) Math.sqrt(d2) - radius;
            // The chamber: a squashed sphere centred 3 blocks above the shaft's foot.
            float cy = (py - (bottom + 3)) * 1.6f;
            float c = (float) Math.sqrt(d2 + cy * cy) - chamberRadius;
            return Math.min(v, c);
        }

        public int minY() {
            return bottom + 3 - (int) Math.ceil(chamberRadius / 1.6f) - 1;
        }
    }

    /** Shafts whose reach touches the block rectangle [x0, x1] x [z0, z1]. */
    public static List<Shaft> near(int x0, int z0, int x1, int z1, CaveBiomes layer, CaveBiomes.Surface surface) {
        List<Shaft> out = new ArrayList<>();
        int r = CaveBiomes.REGION_XZ, half = r / 2, reach = Math.max(MAX_REACH, SINK_REACH + 4);
        int ax0 = Math.floorDiv(x0 - reach - half, r), ax1 = Math.floorDiv(x1 + reach - half, r) + 1;
        int az0 = Math.floorDiv(z0 - reach - half, r), az1 = Math.floorDiv(z1 + reach - half, r) + 1;
        for (int az = az0; az <= az1; az++) {
            for (int ax = ax0; ax <= ax1; ax++) {
                Shaft s = site(ax * r + half, az * r + half, layer, surface);
                if (s == null) continue;
                float reachS = Math.max(s.radius(), s.chamberRadius());
                if (s.x() + reachS < x0 || s.x() - reachS > x1 || s.z() + reachS < z0 || s.z() - reachS > z1) continue;
                out.add(s);
            }
        }
        return out;
    }

    /** The shaft at a region anchor, or null. */
    public static Shaft site(int ax, int az, CaveBiomes layer, CaveBiomes.Surface surface) {
        int ground = surface.groundTop(ax, az);
        if (ground == Integer.MIN_VALUE || ground < 40) return null;
        int probe = ground - PROBE_DEPTH;
        short id = layer.pick(ax, probe, az, surface);
        if (id != CaveBiomes.KARST_CAVES && id != CaveBiomes.UNDERGROUND_LAKES && id != CaveBiomes.GLOWWORM_GROTTO) return null;
        long cell = layer.regionCell(ax, probe, az);
        int level = layer.waterLevel(cell);
        if (level >= ground - 12 || level < CaveBiomes.WATER_MIN_Y) return null;
        long h = hash(cell, layer);
        float u = (h >>> 40) / (float) (1L << 24);
        float v = ((h >>> 16) & 0xFFFF) / 65536f;
        if (id == CaveBiomes.KARST_CAVES) {
            if (u >= CENOTE_CHANCE) return null;
            // A round sinkhole open to the sky, 10 to 22 blocks across the throat, over a wider pool.
            int jx = (int) ((v - 0.5f) * 30), jz = (int) ((((h >>> 8) & 0xFF) / 256f - 0.5f) * 30);
            int x = ax + jx, z = az + jz;
            int top = surface.groundTop(x, z);
            if (top == Integer.MIN_VALUE || top - level < 16) return null;
            float radius = 5f + v * 6f;
            return new Shaft(x, z, radius, level - 4, top + 2, false, radius + 5f);
        }
        if (u >= SINK_CHANCE) return null;
        // A river sink: the nearest river or lake column to the anchor (rings outwards, 4 blocks apart).
        for (int d = 0; d <= SINK_REACH; d += 4) {
            int n = d == 0 ? 1 : 8 * d / 4;
            for (int k = 0; k < n; k++) {
                double a = 2 * Math.PI * k / n + v;
                int x = ax + (int) Math.round(d * Math.cos(a)), z = az + (int) Math.round(d * Math.sin(a));
                int water = surface.inlandWater(x, z);
                if (water == Integer.MIN_VALUE) continue;
                int bed = surface.groundTop(x, z);
                if (bed == Integer.MIN_VALUE || bed - level < 14) return null;
                return new Shaft(x, z, 1.5f + v, level - 3, bed, true, 6f + 3f * v);
            }
        }
        return null;
    }

    private static long hash(long cell, CaveBiomes layer) {
        long z = cell * 0x9E3779B97F4A7C15L + (long) (layer.draw2(cell) * (1 << 24)) * 0xC2B2AE3D27D4EB4FL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
