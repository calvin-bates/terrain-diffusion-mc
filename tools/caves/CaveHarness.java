import com.github.xandergos.terraindiffusionmc.pipeline.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

/**
 * Cave harness (caves plan v1.5): renders Terra's noise caves and cave biomes over synthetic terrain (slices at Y 60,
 * 20 and -30, a vertical section, a top-down X-ray of Y 0..31) and prints the air share by depth, the thin-sheet
 * share, cave biome shares, a seam check and timings. Pure Java: compile with FastNoiseLite, CaveShapes, CaveBiomes.
 *
 *   R=src/main/java/com/github/xandergos/terraindiffusionmc/pipeline
 *   javac -d /tmp/ch $R/FastNoiseLite.java $R/CaveShapes.java $R/CaveBiomes.java $R/CaveHalls.java $R/CaveSites.java $R/SeaCaves.java tools/caves/CaveHarness.java
 *   (cd /tmp/ch && java -cp . CaveHarness [cheese spaghettiW spaghettiVar noodleW noodleGate])
 */

public class CaveHarness {
    static final long SEED = 1234567L;
    static CaveShapes S = CaveShapes.forSeed(SEED);
    static CaveBiomes B = CaveBiomes.forSeed(SEED);
    /** Synthetic surface biomes in 256-block bands: forest, snowy plains, desert, jungle, swamp, plains. */
    static final short[] BANDS = {8, 3, 5, 23, 6, 1};
    /** The synthetic surface, with a river along z = 300 and the sea beyond z = 470 under a cliff. */
    static CaveBiomes.Surface SURF = new CaveBiomes.Surface() {
        public short biomeAt(int x, int z) {
            if (z > 470) return 44;
            return BANDS[Math.floorMod(Math.floorDiv(x + z / 3, 256), BANDS.length)];
        }
        public int groundTop(int x, int z) { return surfaceTop(x, z); }
        public int inlandWater(int x, int z) { return Math.abs(z - 300) < 4 && z <= 470 ? surfaceTop(x, z) + 3 : Integer.MIN_VALUE; }
    };
    static SeaCaves SEA = SeaCaves.forSeed(SEED);
    static java.util.List<CaveSites.Cut> SHAFTS;

    // Synthetic surface: rolling hills around Y 100 with a cliff and a mountain.
    static int surfaceTop(int x, int z) {
        if (z > 470) return 40;                            // the sea floor
        if (z > 430) return 100;                           // a sea cliff
        if (Math.abs(z - 300) < 4) return 92;              // a river channel
        double h = 100 + 18 * Math.sin(x / 90.0) * Math.cos(z / 110.0);
        if (x > 300) h += 60;                      // a 60-block cliff at x = 300
        double d = Math.hypot(x - 420, z - 120);
        if (d < 80) h += (80 - d) * 6;             // a steep mountain
        return (int) h;
    }

    static int refTop(int x, int z) {
        int[] ring = {0, 0, 4, 0, -4, 0, 0, 4, 0, -4, 3, 3, -3, 3, 3, -3, -3, -3};
        int r = Integer.MAX_VALUE;
        for (int k = 0; k < ring.length; k += 2) r = Math.min(r, surfaceTop(x + ring[k], z + ring[k + 1]));
        return r;
    }

    static int rgb(int r, int g, int b) { return (r << 16) | (g << 8) | b; }

    static int biomeColor(short id) {
        return switch (id) {
            case CaveBiomes.ANDESITE_CAVES -> rgb(150, 150, 150);
            case CaveBiomes.DIORITE_CAVES -> rgb(225, 225, 220);
            case CaveBiomes.GRANITE_CAVES -> rgb(170, 110, 90);
            case CaveBiomes.TUFF_CAVES -> rgb(110, 115, 100);
            case CaveBiomes.DRIPSTONE_CAVES -> rgb(150, 110, 70);
            case CaveBiomes.LUSH_CAVES -> rgb(111, 174, 60);
            case CaveBiomes.FUNGAL_CAVES -> rgb(176, 74, 208);
            case CaveBiomes.UNDERGROUND_JUNGLE -> rgb(47, 143, 58);
            case CaveBiomes.ICY_CAVES -> rgb(159, 216, 240);
            case CaveBiomes.FROSTFIRE_CAVES -> rgb(90, 143, 224);
            case CaveBiomes.THERMAL_CAVES -> rgb(227, 195, 74);
            case CaveBiomes.INFESTED_CAVES -> rgb(125, 122, 106);
            case CaveBiomes.SPIDER_NEST -> rgb(200, 200, 200);
            case CaveBiomes.COPPER_CAVES -> rgb(217, 132, 77);
            case CaveBiomes.SALT_CAVES -> rgb(242, 201, 212);
            case CaveBiomes.MUD_CAVES -> rgb(91, 74, 62);
            case CaveBiomes.BREAKDOWN_CHAMBERS -> rgb(138, 143, 153);
            default -> rgb(90, 90, 95);
        };
    }

    /** Full density as the world sampler combines it (land roof only). */
    static float density(float cave, int x, int y, int z) {
        int top = surfaceTop(x, z), ref = refTop(x, z);
        float v = top + 1 - y;
        if (y <= ref) {
            float roof = top < 62 ? S.seaRoof(x, z) : Math.abs(z - 300) < 8 ? CaveShapes.ROOF_WATER : S.landRoof(x, z);
            float cv = cave + CaveShapes.roof(ref - y, roof);
            v = Math.min(v, cv);
        }
        for (CaveSites.Cut sh : SHAFTS) v = Math.min(v, sh.value(x, y, z));
        int sd = SEA.seaDistance(x, z, SURF);
        if (sd > 0) v = Math.min(v, SEA.value(x, y, z, top, sd));
        return v;
    }

    public static void main(String[] a) throws Exception {
        if (a.length >= 5) {
            CaveShapes.CHEESE_THRESHOLD = Float.parseFloat(a[0]);
            CaveShapes.SPAGHETTI_WIDTH = Float.parseFloat(a[1]);
            CaveShapes.SPAGHETTI_WIDTH_VAR = Float.parseFloat(a[2]);
            CaveShapes.NOODLE_WIDTH = Float.parseFloat(a[3]);
            CaveShapes.NOODLE_GATE = Float.parseFloat(a[4]);
        }
        int W = 512, minY = -64, H = 700 - minY;
        SHAFTS = CaveSites.near(0, 0, W - 1, W - 1, B, SURF);
        System.out.println("shafts: " + SHAFTS);
        int seaCols = 0;
        for (int x = 0; x < W; x++) for (int z = 0; z < W; z++) if (SEA.active(x, z, SURF)) seaCols++;
        System.out.println("sea cave columns: " + seaCols);
        // Volume over the whole area, chunk by chunk (16x16 columns), like the sampler.
        float[] col = new float[16 * 16 * H];
        CaveShapes.Scratch lat = new CaveShapes.Scratch();
        boolean[][][] air = new boolean[W][W][];
        long t0 = System.nanoTime();
        int chunks = 0;
        for (int cz = 0; cz < W; cz += 16) for (int cx = 0; cx < W; cx += 16) {
            S.fill(cx, cz, 16, 16, minY, H, col, lat, SURF);
            chunks++;
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                boolean[] c = new boolean[H];
                for (int y = 0; y < H; y++) c[y] = density(col[(x + z * 16) * H + y], cx + x, minY + y, cz + z) <= 0;
                air[cx + x][cz + z] = c;
            }
        }
        System.out.printf("fill: %.2f ms per chunk for %d rows%n", (System.nanoTime() - t0) / 1e6 / chunks, H);

        // Underground air fraction by depth below the surface, and caves breaking the surface.
        int[] airBy = new int[20], allBy = new int[20];
        int openings = 0, cols = 0;
        for (int x = 0; x < W; x++) for (int z = 0; z < W; z++) {
            int top = surfaceTop(x, z);
            cols++;
            if (air[x][z][top - minY]) openings++;
            for (int y = minY; y < top; y++) {
                int band = Math.min(19, (top - y) / 16);
                allBy[band]++;
                if (air[x][z][y - minY]) airBy[band]++;
            }
        }
        int tot = 0, totAir = 0;
        for (int b = 0; b < 20; b++) {
            if (allBy[b] == 0) continue;
            tot += allBy[b]; totAir += airBy[b];
            if (b < 12) System.out.printf("depth %3d-%3d: %5.1f%% air%n", b * 16, b * 16 + 15, 100.0 * airBy[b] / allBy[b]);
        }
        System.out.printf("all underground: %.1f%% air; surface openings: %.3f%% of columns%n", 100.0 * totAir / tot, 100.0 * openings / cols);
        long runs = 0, thin = 0, thinBlocks = 0, airBlocks = 0;
        for (int x = 0; x < W; x++) for (int z = 0; z < W; z++) {
            int top = surfaceTop(x, z) - minY, run = 0;
            for (int y = 0; y <= top; y++) {
                if (air[x][z][y]) { run++; airBlocks++; }
                else if (run > 0) { runs++; if (run <= 2) { thin++; thinBlocks += run; } run = 0; }
            }
        }
        System.out.printf("thin air (vertical runs of 1-2 blocks): %.1f%% of runs, %.2f%% of cave air%n", 100.0 * thin / runs, 100.0 * thinBlocks / airBlocks);

        // Horizontal slices (air dark, rock coloured by cave biome) and a vertical section.
        for (int y : new int[]{60, 20, -30}) {
            BufferedImage img = new BufferedImage(W, W, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < W; x++) for (int z = 0; z < W; z++) {
                short id = B.at(x, y, z, refTop(x, z), SURF);
                int c = air[x][z][y - minY] ? rgb(20, 20, 30) : biomeColor(id);
                if (y > surfaceTop(x, z)) c = rgb(130, 180, 230);
                img.setRGB(x, z, c);
            }
            ImageIO.write(img, "png", new File("slice_y" + y + ".png"));
        }
        BufferedImage xr = new BufferedImage(W, W, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < W; x++) for (int z = 0; z < W; z++) {
            int n = 0;
            for (int y = 0; y < 32; y++) if (air[x][z][y - minY]) n++;
            int v = Math.min(255, n * 24);
            xr.setRGB(x, z, n == 0 ? rgb(70, 70, 75) : rgb(255 - v / 2, 230 - v / 2, 120));
        }
        ImageIO.write(xr, "png", new File("xray_y0_32.png"));
        int SH = 300;
        BufferedImage sec = new BufferedImage(W, SH, BufferedImage.TYPE_INT_RGB);
        int z0 = 120;
        for (int x = 0; x < W; x++) for (int yy = 0; yy < SH; yy++) {
            int y = minY + (SH - 1 - yy) * 2;   // 2 blocks per pixel vertically, Y -64..534
            int c;
            if (y > surfaceTop(x, z0)) c = rgb(130, 180, 230);
            else c = air[x][z0][y - minY] ? rgb(20, 20, 30) : biomeColor(B.at(x, y, z0, refTop(x, z0), SURF));
            sec.setRGB(x, yy, c);
        }
        ImageIO.write(sec, "png", new File("section_z120.png"));

        // Cave biome shares (by region draw, any depth) and inside the cave layer under the hills.
        int[] count = new int[256]; int n = 0;
        java.util.Random r = new java.util.Random(5);
        for (int i = 0; i < 400000; i++) {
            int x = r.nextInt(200000) - 100000, z = r.nextInt(200000) - 100000, y = r.nextInt(60) - 20;
            short id = B.pick(x, y, z, SURF);
            count[id < 0 ? 0 : id]++; n++;
        }
        System.out.printf("shares at Y -20..40: plain %.1f%%", 100.0 * count[0] / n);
        for (short id : CaveBiomes.NAMES.keySet()) System.out.printf(", %s %.1f%%", CaveBiomes.NAMES.get(id).replaceAll(".*:", ""), 100.0 * count[id] / n);
        System.out.println();

        // Seam checks: the same blocks from differently placed regions, and fill() vs at().
        float[] A = new float[40 * 40 * 100], Bv = new float[40 * 40 * 100];
        S.fill(1000, 2000, 40, 40, -20, 100, A, new CaveShapes.Scratch(), SURF);
        S.fill(1013, 2007, 40, 40, -11, 100, Bv, new CaveShapes.Scratch(), SURF);
        int diff = 0, checked = 0;
        for (int x = 13; x < 40; x++) for (int z = 7; z < 40; z++) for (int y = 9; y < 100; y++) {
            float va = A[(x + z * 40) * 100 + y];
            float vb = Bv[((x - 13) + (z - 7) * 40) * 100 + (y - 9)];
            checked++;
            if (va != vb) diff++;
        }
        int diff2 = 0;
        for (int i = 0; i < 2000; i++) {
            int x = r.nextInt(40), z = r.nextInt(40), y = r.nextInt(100);
            if (A[(x + z * 40) * 100 + y] != S.at(1000 + x, -20 + y, 2000 + z, SURF)) diff2++;
        }
        System.out.println("seam: " + diff + " of " + checked + " blocks differ between offset regions; fill vs at(): " + diff2 + " of 2000");

        // Mountain-height column cost: a chunk with 1600 rows.
        float[] big = new float[16 * 16 * 1600];
        long t1 = System.nanoTime();
        for (int i = 0; i < 20; i++) S.fill(i * 16, 0, 16, 16, -64, 1600, big, lat, SURF);
        System.out.printf("mountain chunk (1600 rows): %.2f ms%n", (System.nanoTime() - t1) / 1e6 / 20);
        long t2 = System.nanoTime();
        int q = 0;
        for (int x = 0; x < 64; x++) for (int z = 0; z < 64; z++) for (int y = -64; y < 1500; y += 4) { B.pick(x * 4, y, z * 4, SURF); q++; }
        System.out.printf("biome pick: %.0f ns each%n", (System.nanoTime() - t2) / (double) q);
    }
}
