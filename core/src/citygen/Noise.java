package citygen;

/**
 * 基于坐标哈希的二维值噪声与 fBm。
 * 无状态：任意坐标可独立求值，因此可乱序、可并行、可分块。
 */
public final class Noise {

    private final long seed;

    public Noise(long seed) {
        this.seed = seed;
    }

    private double lattice(int x, int z, int salt) {
        long h = Rng.derive(seed, salt, x, z);
        return ((h >>> 11) / (double) (1L << 53)) * 2.0 - 1.0;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public double value(double x, double z, int salt) {
        double fx = Math.floor(x);
        double fz = Math.floor(z);
        int x0 = (int) fx;
        int z0 = (int) fz;
        double u = fade(x - fx);
        double v = fade(z - fz);
        double a = lattice(x0, z0, salt);
        double b = lattice(x0 + 1, z0, salt);
        double c = lattice(x0, z0 + 1, salt);
        double d = lattice(x0 + 1, z0 + 1, salt);
        return lerp(lerp(a, b, u), lerp(c, d, u), v);
    }

    /** 多倍频叠加：低频定大形，高频加细节，输出约在 [-1,1]。 */
    public double fbm(double x, double z, int octaves, int salt) {
        double sum = 0.0;
        double amp = 1.0;
        double norm = 0.0;
        double freq = 1.0;
        for (int i = 0; i < octaves; i++) {
            sum += value(x * freq, z * freq, salt + i * 1013) * amp;
            norm += amp;
            amp *= 0.5;
            freq *= 2.0;
        }
        return sum / norm;
    }
}
