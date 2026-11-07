package citygen;

/**
 * 48 位线性同余发生器，与 Minecraft 的 Java Random 同构。
 * 关键纪律：所有随机都必须由 (seed, salt, 坐标) 派生，绝不依赖调用顺序。
 */
public final class Rng {

    private static final long MULT = 0x5DEECE66DL;
    private static final long ADD = 0xBL;
    private static final long MASK = (1L << 48) - 1;

    private long state;

    public Rng(long seed) {
        this.state = (seed ^ MULT) & MASK;
    }

    public int next(int bits) {
        state = (state * MULT + ADD) & MASK;
        return (int) (state >>> (48 - bits));
    }

    public double nextDouble() {
        return (((long) next(26) << 27) + next(27)) / (double) (1L << 53);
    }

    public double nextSigned() {
        return nextDouble() * 2.0 - 1.0;
    }

    public int nextInt(int bound) {
        if (bound <= 0) {
            return 0;
        }
        int r = next(31);
        int m = bound - 1;
        if ((bound & m) == 0) {
            return (int) ((bound * (long) r) >> 31);
        }
        int u = r;
        while (u - (r = u % bound) + m < 0) {
            u = next(31);
        }
        return r;
    }

    /** 雪崩混合：把元组压成互不相关的随机源。 */
    public static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    public static long derive(long seed, int salt, int x, int z) {
        long h = mix(seed);
        h = mix(h ^ (salt * 0x9E3779B97F4A7C15L));
        h = mix(h ^ ((long) x * 0x27D4EB2F165667C5L));
        h = mix(h ^ ((long) z * 0x165667B19E3779F9L));
        return h;
    }

    public static Rng at(long seed, int salt, int x, int z) {
        return new Rng(derive(seed, salt, x, z));
    }

    /** 单点取值，区间 [0,1)。 */
    public static double unit(long seed, int salt, int x, int z) {
        return (derive(seed, salt, x, z) >>> 11) / (double) (1L << 53);
    }
}
