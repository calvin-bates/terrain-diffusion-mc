import com.github.xandergos.terraindiffusionmc.hydrology.DrainageNetwork;
import com.github.xandergos.terraindiffusionmc.pipeline.FastNoiseLite;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Standalone preview: synthetic terrain -> drainage network -> river classes -> PNG. */
public class RiverPreview {
    public static void main(String[] a) throws Exception {
        int N = 1024;
        FastNoiseLite base = new FastNoiseLite(7);
        base.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        base.SetFractalType(FastNoiseLite.FractalType.Ridged);
        base.SetFractalOctaves(6); base.SetFrequency(1f / 380f);
        FastNoiseLite cont = new FastNoiseLite(11);
        cont.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        cont.SetFractalType(FastNoiseLite.FractalType.FBm);
        cont.SetFractalOctaves(3); cont.SetFrequency(1f / 900f);
        float[] elev = new float[N * N];
        float[] runoff = new float[N * N];
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++) {
            float cv = cont.GetNoise(c, r);               // continent shape
            float m = base.GetNoise(c, r) * 0.5f + 0.5f;  // mountain ridges 0..1
            float land = Math.max(0f, cv + 0.15f);
            elev[r * N + c] = (cv - 0.05f) * 600f + land * m * 3800f;
            runoff[r * N + c] = 1f;
        }
        // Break up D8 parallel-flow artifacts on smooth ramps with a small routing-only jitter.
        FastNoiseLite jit = new FastNoiseLite(99);
        jit.SetNoiseType(FastNoiseLite.NoiseType.OpenSimplex2);
        jit.SetFrequency(1f / 9f);
        float[] routing = elev.clone();
        for (int r = 0; r < N; r++) for (int c = 0; c < N; c++)
            if (elev[r * N + c] > 0f) routing[r * N + c] += jit.GetNoise(c, r) * 6f;
        DrainageNetwork d = DrainageNetwork.compute(routing, runoff, N, N, 0f, true);
        BufferedImage img = new BufferedImage(N, N, BufferedImage.TYPE_INT_RGB);
        int streams = 0, rivers = 0, mains = 0;
        for (int i = 0; i < N * N; i++) {
            float e = elev[i];
            int rgb;
            if (e <= 0f) rgb = 0x1c3f7a;
            else {
                int g = (int) Math.min(255, 60 + e / 20f);
                rgb = (g << 16) | (g << 8) | (g - 20 < 0 ? 0 : g - 20);
            }
            float q = d.discharge[i];
            if (e > 0f) {
                if (q >= 15000f) { rgb = 0x00e5ff; mains++; }
                else if (q >= 2500f) { rgb = 0x2f7bff; rivers++; }
                else if (q >= 350f) { rgb = 0x7fb0ff; streams++; }
            }
            img.setRGB(i % N, i / N, rgb);
        }
        ImageIO.write(img, "png", new File(a[0]));
        System.out.println("stream=" + streams + " river=" + rivers + " main=" + mains);
    }
}
