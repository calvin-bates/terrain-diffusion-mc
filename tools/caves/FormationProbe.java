import com.github.xandergos.terraindiffusionmc.pipeline.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.util.*;

/**
 * Formation probe (caves plan v1.5 part four round A): carves Terra's noise caves under flat synthetic ground over
 * 512 x 512 blocks, fits the formations to the cave air as world/CaveFormations does (same layout, same per-column
 * rules, simplified blocks), prints how many of each actually land in a cave per km^2, and renders vertical sections
 * through a column, a boxwork zone and a crystal room.
 *
 *   R=src/main/java/com/github/xandergos/terraindiffusionmc/pipeline
 *   javac -d /tmp/fp $R/FastNoiseLite.java $R/CaveShapes.java $R/CaveBiomes.java $R/CaveHalls.java $R/CaveFormationLayout.java tools/caves/FormationProbe.java
 *   (cd /tmp/fp && java -cp . FormationProbe [outdir])
 */
public class FormationProbe {
    static final long SEED = 1234567L;
    static final int W = 512, MIN_Y = -64, TOP = 100, H = TOP - MIN_Y;
    static CaveShapes S = CaveShapes.forSeed(SEED);
    static CaveBiomes B = CaveBiomes.forSeed(SEED);
    static CaveBiomes.Surface SURF = new CaveBiomes.Surface() {
        public short biomeAt(int x, int z) { return 1; }
        public int groundTop(int x, int z) { return TOP; }
    };
    static final byte AIR = 0, ROCK = 1, COL = 2, AMETHYST = 3, FIN = 4, DRIP = 5;
    static byte[][][] v = new byte[W][W][H];

    public static void main(String[] a) throws Exception {
        String out = a.length > 0 ? a[0] : ".";
        float[] col = new float[16 * 16 * H];
        CaveShapes.Scratch sc = new CaveShapes.Scratch();
        for (int cz = 0; cz < W; cz += 16) for (int cx = 0; cx < W; cx += 16) {
            S.fill(cx, cz, 16, 16, MIN_Y, H, col, sc, SURF);
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) for (int y = 0; y < H; y++) {
                float d = CaveShapes.roofed(col[(x + z * 16) * H + y], TOP - (MIN_Y + y), CaveShapes.ROOF_LAND);
                v[cx + x][cz + z][y] = d <= 0 ? AIR : ROCK;
            }
        }
        long t0 = System.nanoTime();
        Map<Object, Integer> colsHit = new HashMap<>(), boxHit = new HashMap<>(), crysHit = new HashMap<>();
        int stal = 0;
        // Runs per column: a, b, flags (1 floor, 2 ceiling, 4 used), as world/CaveFormations.
        int[][][] runs = new int[W][W][];
        for (int x = 0; x < W; x++) for (int z = 0; z < W; z++) {
            List<Integer> r = new ArrayList<>();
            byte[] c = v[x][z];
            int y = 1;
            while (y < H - 1) {
                if (c[y] != AIR) { y++; continue; }
                int s = y;
                while (y < H - 1 && c[y] == AIR) y++;
                int e = y - 1;
                if (e + 1 >= H - 1) break;
                int f = (c[s - 1] != AIR ? 1 : 0) | 2;
                r.add(s); r.add(e); r.add(f);
            }
            runs[x][z] = r.stream().mapToInt(i -> i).toArray();
        }
        List<CaveFormationLayout.Column> cols = new ArrayList<>();
        CaveFormationLayout.columns(SEED, B, SURF, 0, 0, W - 1, W - 1, MIN_Y, TOP, cols);
        int[] firstCol = null;
        for (CaveFormationLayout.Column cl : cols) {
            for (int x = Math.max(0, (int) (cl.x() - 7)); x <= Math.min(W - 1, (int) (cl.x() + 7)); x++)
                for (int z = Math.max(0, (int) (cl.z() - 7)); z <= Math.min(W - 1, (int) (cl.z() + 7)); z++) {
                    float dx = x + 0.5f - cl.x(), dz = z + 0.5f - cl.z(), d = (float) Math.sqrt(dx * dx + dz * dz);
                    int[] r = runs[x][z];
                    for (int i = 0; i < r.length; i += 3) {
                        int ra = r[i] + MIN_Y, rb = r[i + 1] + MIN_Y, hh = rb - ra + 1;
                        if (cl.y() < ra || cl.y() > rb || (r[i + 2] & 1) == 0) continue;
                        if (hh < CaveFormationLayout.COL_MIN_H || hh > CaveFormationLayout.COL_MAX_H) continue;
                        boolean any = false;
                        for (int y = ra; y <= rb; y++) {
                            float rad = cl.radius(y, ra, rb);
                            if (rad < 0 || d > rad) continue;
                            v[x][z][y - MIN_Y] = COL;
                            any = true;
                        }
                        if (any) { r[i + 2] |= 4; colsHit.merge(cl, 1, Integer::sum); }
                    }
                }
        }
        List<CaveFormationLayout.Zone> zs = new ArrayList<>();
        int[] firstBox = null, firstCrys = null;
        for (int type = 1; type >= 0; type--) {
            CaveFormationLayout.zones(type, SEED, B, SURF, 0, MIN_Y, 0, W - 1, TOP, W - 1, zs);
            for (CaveFormationLayout.Zone zn : zs)
                for (int x = Math.max(0, zn.x() - 35); x <= Math.min(W - 1, zn.x() + 35); x++)
                    for (int z = Math.max(0, zn.z() - 35); z <= Math.min(W - 1, zn.z() + 35); z++) {
                        int[] r = runs[x][z];
                        for (int i = 0; i < r.length; i += 3) {
                            if ((r[i + 2] & 4) != 0) continue;
                            int ra = r[i] + MIN_Y, rb = r[i + 1] + MIN_Y;
                            if (type == CaveFormationLayout.ZONE_CRYSTAL) {
                                boolean did = false;
                                if (zn.dist(x, rb + 1, z) < 1f) { v[x][z][rb + 1 - MIN_Y] = AMETHYST; did = true; }
                                if ((r[i + 2] & 1) != 0 && zn.dist(x, ra - 1, z) < 1f) { v[x][z][ra - 1 - MIN_Y] = AMETHYST; did = true; }
                                if (did) { r[i + 2] |= 4; crysHit.merge(zn, 1, Integer::sum); }
                            } else {
                                if (CaveFormationLayout.boxworkEdge(SEED, x + 0.5f, z + 0.5f) > 0.55f) continue;
                                float d = zn.dist(x, rb + 1, z);
                                if (d >= 1f) continue;
                                int depth = Math.min(1 + Math.round(2.5f * (1f - d)), rb - ra + 1 - 2);
                                if (depth <= 0) continue;
                                for (int k = 0; k < depth; k++) v[x][z][rb - k - MIN_Y] = FIN;
                                r[i + 2] |= 4;
                                boxHit.merge(zn, 1, Integer::sum);
                            }
                        }
                    }
        }
        FastNoiseLite noise = new FastNoiseLite((int) (SEED ^ (SEED >>> 32)) + 0xD41F);
        noise.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        noise.SetFrequency(1f / 48f);
        for (int x = 0; x < W; x++) for (int z = 0; z < W; z++) {
            int[] r = runs[x][z];
            for (int i = 0; i < r.length; i += 3) {
                if ((r[i + 2] & 4) != 0) continue;
                int ra = r[i] + MIN_Y, rb = r[i + 1] + MIN_Y;
                if (rb - ra + 1 < 4) continue;
                float zf = (noise.GetNoise(x, rb, z) - 0.05f) / 0.35f;
                if (zf <= 0) continue;
                byte k = CaveFormationLayout.kind(B.pick((x & ~3) + 2, (rb & ~3) + 2, (z & ~3) + 2, SURF));
                float p = CaveFormationLayout.DRIP_CHANCE[k] * Math.min(1f, zf);
                long hh = CaveFormationLayout.hash(SEED ^ 0xD121L, x, rb, z);
                if (CaveFormationLayout.unit(hh, 0) < p) { stal++; v[x][z][rb - MIN_Y] = DRIP; }
            }
        }
        double km2 = W * W / 1e6;
        System.out.printf("fit: %.0f ms; per km^2 (Y %d..%d): columns %.0f (of %d anchors), boxwork zones %.1f, crystal rooms %.1f, stalactites %.0f%n",
                (System.nanoTime() - t0) / 1e6, MIN_Y, TOP, colsHit.size() / km2, cols.size(), boxHit.size() / km2, crysHit.size() / km2, stal / km2);
        firstCol = biggest(colsHit);
        firstBox = biggest(boxHit);
        firstCrys = biggest(crysHit);
        if (firstCol != null) section(out + "/form_column.png", firstCol);
        if (firstBox != null) section(out + "/form_boxwork.png", firstBox);
        if (firstCrys != null) section(out + "/form_crystal.png", firstCrys);
        System.out.printf("columns: mean %.1f block columns filled each; boxwork %.0f; crystal %.0f%n",
                colsHit.values().stream().mapToInt(i -> i).average().orElse(0),
                boxHit.values().stream().mapToInt(i -> i).average().orElse(0),
                crysHit.values().stream().mapToInt(i -> i).average().orElse(0));
    }

    static int[] biggest(Map<Object, Integer> m) {
        Object best = null;
        int n = -1;
        for (var e : m.entrySet()) if (e.getValue() > n) { n = e.getValue(); best = e.getKey(); }
        if (best instanceof CaveFormationLayout.Column c) return new int[]{(int) c.x(), c.y(), (int) c.z()};
        if (best instanceof CaveFormationLayout.Zone z) return new int[]{z.x(), z.y(), z.z()};
        return null;
    }

    /** A vertical section along x (scaled 3x) through a point, 96 blocks wide and the whole height. */
    static void section(String path, int[] p) throws Exception {
        int half = 48, sc = 3;
        BufferedImage img = new BufferedImage(half * 2 * sc, H * sc, BufferedImage.TYPE_INT_RGB);
        int z = Math.max(0, Math.min(W - 1, p[2]));
        for (int i = 0; i < half * 2; i++) for (int y = 0; y < H; y++) {
            int x = p[0] - half + i;
            int c = 0x000000;
            if (x >= 0 && x < W) c = switch (v[x][z][y]) {
                case AIR -> 0x101014; case ROCK -> 0x4a4a52; case COL -> 0xb08050; case AMETHYST -> 0xa060e0;
                case FIN -> 0xf0ece0; case DRIP -> 0xd09a60; default -> 0xff00ff; };
            for (int a = 0; a < sc; a++) for (int b = 0; b < sc; b++) img.setRGB(i * sc + a, (H - 1 - y) * sc + b, c);
        }
        ImageIO.write(img, "png", new File(path));
        BufferedImage plan = new BufferedImage(half * 2 * sc, half * 2 * sc, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < half * 2; i++) for (int j = 0; j < half * 2; j++) {
            int x = p[0] - half + i, zz = p[2] - half + j, c = 0x000000;
            if (x >= 0 && x < W && zz >= 0 && zz < W) {
                c = 0x4a4a52;
                boolean air = false;
                for (int y = Math.max(0, p[1] - MIN_Y - 20); y < Math.min(H, p[1] - MIN_Y + 20); y++) {
                    byte b = v[x][zz][y];
                    if (b == AIR) air = true;
                    if (b >= COL) { c = switch (b) { case COL -> 0xb08050; case AMETHYST -> 0xa060e0; case FIN -> 0xf0ece0; default -> 0xd09a60; }; break; }
                }
                if (c == 0x4a4a52 && air) c = 0x101014;
            }
            for (int a = 0; a < sc; a++) for (int b = 0; b < sc; b++) plan.setRGB(i * sc + a, j * sc + b, c);
        }
        ImageIO.write(plan, "png", new File(path.replace(".png", "_plan.png")));
        System.out.println("wrote " + path + " at " + Arrays.toString(p));
    }
}
