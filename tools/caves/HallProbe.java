import com.github.xandergos.terraindiffusionmc.pipeline.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/**
 * Hall probe (caves plan v1.5 part three round C): finds a deep dark forest, a deep dark incursion and a giant selenite
 * chamber over flat synthetic ground, renders a plan slice and a vertical section through each (air black, rock grey,
 * pillars and beams coloured), and checks seams (fill at two chunk alignments and the single-block query agree).
 *
 *   R=src/main/java/com/github/xandergos/terraindiffusionmc/pipeline
 *   javac -d /tmp/hp $R/FastNoiseLite.java $R/CaveShapes.java $R/CaveBiomes.java $R/CaveHalls.java tools/caves/HallProbe.java
 *   (cd /tmp/hp && java -cp . HallProbe [outdir])
 */
public class HallProbe {
    static final long SEED = 1234567L;
    static CaveShapes S = CaveShapes.forSeed(SEED);
    static CaveBiomes B = CaveBiomes.forSeed(SEED);
    static CaveBiomes.Surface SURF = new CaveBiomes.Surface() {
        public short biomeAt(int x, int z) { return 1; }
        public int groundTop(int x, int z) { return 100; }
    };
    static final int MIN_Y = -64, H = 120;   // Y -64..55

    public static void main(String[] a) throws Exception {
        String out = a.length > 0 ? a[0] : ".";
        short[] want = {CaveBiomes.DEEP_DARK_FOREST, CaveBiomes.DEEP_DARK_INCURSION, CaveBiomes.GIANT_SELENITE_BEAMS};
        int[] counts = new int[3];
        long[] found = new long[3];
        boolean[] have = new boolean[3];
        int cells = 0;
        for (int gz = -150; gz < 150; gz++)
            for (int gx = -150; gx < 150; gx++) {
                int ax = gx * CaveBiomes.REGION_XZ + CaveBiomes.REGION_XZ / 2, az = gz * CaveBiomes.REGION_XZ + CaveBiomes.REGION_XZ / 2;
                int ay = -CaveBiomes.REGION_Y / 2;
                cells++;
                long ucell = CaveBiomes.unwarpedCell(ax, ay, az);
                short id = B.hallId(ucell, SURF);
                for (int k = 0; k < 3; k++)
                    if (id == want[k]) {
                        counts[k]++;
                        if (!have[k]) { found[k] = ucell; have[k] = true; }
                    }
            }
        System.out.printf("deep cells sampled %d: forest %d (%.2f%%), incursion %d (%.2f%%), selenite %d (%.2f%%)%n", cells,
                counts[0], 100.0 * counts[0] / cells, counts[1], 100.0 * counts[1] / cells, counts[2], 100.0 * counts[2] / cells);
        String[] names = {"forest", "incursion", "selenite"};
        for (int k = 0; k < 3; k++) {
            if (!have[k]) { System.out.println("no " + names[k] + " found"); continue; }
            long cell = found[k];
            short id = want[k];
            int cx = CaveBiomes.anchorX(cell), cz = CaveBiomes.anchorZ(cell), cy = CaveBiomes.hallY(cell, id);
            System.out.printf("%s at %d %d %d%n", names[k], cx, cy, cz);
            render(out + "/hall_" + names[k] + ".png", id, cx, cy, cz);
            // The biome inside the hall must be the hall's everywhere (CaveBiomes.pick follows the hall).
            int bad = 0, n = 0;
            for (int dx = -40; dx <= 40; dx += 4) for (int dz = -40; dz <= 40; dz += 4) for (int dy = -26; dy <= 26; dy += 4) {
                int x = cx + dx, y = cy + dy, z = cz + dz;
                if (CaveBiomes.hallDistance(id, cell, x, y, z) >= 1f) continue;
                n++;
                if (B.pick(x, y, z, SURF) != id) bad++;
            }
            System.out.printf("  biome inside the hall: %d of %d samples not the hall's%n", bad, n);
            seams(names[k], cx, cy, cz);
        }
    }

    /** Fills the 160 x 160 area around (cx, cz) chunk by chunk, offset by (ox, oz) from the 16-block grid. */
    static float[][][] volume(int cx, int cz, int ox, int oz) {
        int W = 160, x0 = cx - 80, z0 = cz - 80;
        float[][][] v = new float[W][W][];
        float[] col = new float[16 * 16 * H];
        CaveShapes.Scratch sc = new CaveShapes.Scratch();
        int bx0 = Math.floorDiv(x0 - ox, 16) * 16 + ox, bz0 = Math.floorDiv(z0 - oz, 16) * 16 + oz;
        for (int bz = bz0; bz < z0 + W; bz += 16)
            for (int bx = bx0; bx < x0 + W; bx += 16) {
                S.fill(bx, bz, 16, 16, MIN_Y, H, col, sc, SURF);
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        int X = bx + x - x0, Z = bz + z - z0;
                        if (X < 0 || Z < 0 || X >= W || Z >= W) continue;
                        float[] c = new float[H];
                        System.arraycopy(col, (x + z * 16) * H, c, 0, H);
                        v[X][Z] = c;
                    }
            }
        return v;
    }

    static void render(String path, short id, int cx, int cy, int cz) throws Exception {
        long t0 = System.nanoTime();
        float[][][] v = volume(cx, cz, 0, 0);
        System.out.printf("  fill %.1f ms for 100 chunks%n", (System.nanoTime() - t0) / 1e6);
        int W = 160;
        float rx = CaveBiomes.hallRX(id), ry = CaveBiomes.hallRY(id);
        CaveHalls.Beam[] beams = id == CaveBiomes.GIANT_SELENITE_BEAMS ? CaveHalls.beams(SEED, cx, cy, cz, rx, ry) : null;
        // Plan at the floor + 3 (forest, selenite) or the centre (incursion), section along x through the centre.
        int planY = id == CaveBiomes.DEEP_DARK_INCURSION ? cy - 8 : (int) (cy - CaveBiomes.hallFloor(id)) + 3;
        // The section runs along x at the z (within 20 of the centre) that crosses the most rock inside the hall.
        int secZ = 80, bestRock = -1;
        for (int Z = 60; Z <= 100; Z++) {
            int rock = 0;
            for (int X = 0; X < W; X++) if (v[X][Z][cy - MIN_Y] > 0 && Math.abs(X - 80) < rx * 0.8f) rock++;
            if (rock > bestRock) { bestRock = rock; secZ = Z; }
        }
        BufferedImage img = new BufferedImage(W * 2 + 4, W, BufferedImage.TYPE_INT_RGB);
        int air = 0, total = 0, feature = 0;
        for (int X = 0; X < W; X++)
            for (int Z = 0; Z < W; Z++) {
                int x = cx - 80 + X, z = cz - 80 + Z;
                float d = v[X][Z][planY - MIN_Y];
                img.setRGB(X, Z, color(d, id, beams, x, planY, z, cx, cy, cz, rx, ry));
            }
        for (int X = 0; X < W; X++)
            for (int yy = 0; yy < W && yy < H; yy++) {
                int y = MIN_Y + H - 1 - yy;
                int x = cx - 80 + X;
                float d = v[X][secZ][y - MIN_Y];
                img.setRGB(W + 4 + X, yy, color(d, id, beams, x, y, cz - 80 + secZ, cx, cy, cz, rx, ry));
                total++;
                if (d <= 0) air++;
            }
        ImageIO.write(img, "png", new File(path));
        System.out.printf("  section air %.1f%%, wrote %s%n", 100.0 * air / total, path);
    }

    static int color(float d, short id, CaveHalls.Beam[] beams, int x, int y, int z, int cx, int cy, int cz, float rx, float ry) {
        if (d <= 0) return 0x101014;
        if (id == CaveBiomes.DEEP_DARK_INCURSION && CaveHalls.pillar(SEED, x, y, z, cx, cy, cz, rx, ry) >= -0.5f) return 0x8a8f99;
        if (beams != null) {
            CaveHalls.Beam b = CaveHalls.beamAt(beams, x, y, z);
            if (b != null) return CaveHalls.isTip(b, x, y, z) ? 0x9a5cd6 : 0xf2efe6;
        }
        return 0x3a3a40;
    }

    static void seams(String name, int cx, int cy, int cz) {
        float[][][] a = volume(cx, cz, 0, 0), b = volume(cx, cz, 7, 11);
        int diff = 0, n = 0, pointDiff = 0;
        java.util.Random r = new java.util.Random(5);
        for (int X = 0; X < 160; X++)
            for (int Z = 0; Z < 160; Z++)
                for (int y = 0; y < H; y++) {
                    n++;
                    if ((a[X][Z][y] <= 0) != (b[X][Z][y] <= 0)) diff++;
                }
        for (int i = 0; i < 4000; i++) {
            int X = r.nextInt(160), Z = r.nextInt(160), y = r.nextInt(H);
            float p = S.at(cx - 80 + X, MIN_Y + y, cz - 80 + Z, SURF);
            if ((p <= 0) != (a[X][Z][y] <= 0)) pointDiff++;
        }
        System.out.printf("  seams %s: %d of %d blocks differ between alignments, %d of 4000 points differ from at()%n",
                name, diff, n, pointDiff);
    }
}
