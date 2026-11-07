package citygen;

/**
 * 宏观势场层：只做连续的标量场，不含任何离散判定。
 * 这一层对应 Minecraft 的 noise + DensityFunction，可直接复用其思想。
 *
 * <p><b>所有方法吃的是 megachunk 局部坐标，算的却是世界坐标。</b>
 * 这一点是硬性的：噪声只要混进了局部坐标，相邻 megachunk 就会各算一套图案，
 * 边界上直接错开。之前修接缝时只把「路网相位」删了，但 {@link #warp} 仍然用局部坐标，
 * 于是路面在块边界处还是会歪开几格——这是残留接缝的真正来源。
 *
 * <p>城市中心也从「每个 megachunk 的正中央」改成了**全局抖动网格**：
 * 每 512 格一个格子，格子里按种子随机决定「有没有城、城在哪、城多大」，
 * 约 38% 的格子留空当郊野。这样不再每 512 格重复一座一模一样的城，
 * 也才有「都市圈 → 郊区 → 荒野」的过渡。
 */
public final class Field {

    /** 城市网格边长，与 megachunk 对齐，便于推理。 */
    public static final int CITY_CELL = 512;

    private final long seed;
    private final Noise noise;
    private final int originX;
    private final int originZ;

    // 城市网格查找表：City 只由 (格子坐标, 种子) 决定，与查询位置无关，
    // 却被 Roads.build / applyWater / classify 在**每一格**上重复推演——
    // 一块 512² 就是 26 万格 × 3×3 邻域 × Rng 派生，四百多万次纯重复计算。
    // 构造时算一次，之后全是查表。
    private City[] cityGrid;
    private int gridX0;
    private int gridZ0;
    private int gridSpan;

    public Field(long seed, int originX, int originZ, int size) {
        this.seed = seed;
        this.noise = new Noise(seed);
        this.originX = originX;
        this.originZ = originZ;
        buildCityGrid(size);
    }

    private void buildCityGrid(int size) {
        // 覆盖范围：原点往外扩两个格子，足够 3×3 邻域查询
        gridSpan = size / CITY_CELL + 5;
        gridX0 = Math.floorDiv(originX - 2 * CITY_CELL, CITY_CELL);
        gridZ0 = Math.floorDiv(originZ - 2 * CITY_CELL, CITY_CELL);
        cityGrid = new City[gridSpan * gridSpan];
        for (int gz = 0; gz < gridSpan; gz++) {
            for (int gx = 0; gx < gridSpan; gx++) {
                cityGrid[gz * gridSpan + gx] = cityAt(gridX0 + gx, gridZ0 + gz);
            }
        }
    }

    /** 按格子坐标取城市；这个格子本来就留空当郊野时返回 null。 */
    private City cityAt(int cx, int cz) {
        long h = Rng.derive(seed, 4242, cx, cz);
        // 约 38% 的格子不放城，留出真正的荒野
        if (((h >>> 40) & 0xFF) < 97) {
            return null;
        }
        Rng r = new Rng(h);
        double sx = cx * CITY_CELL + r.nextDouble() * CITY_CELL;
        double sz = cz * CITY_CELL + r.nextDouble() * CITY_CELL;
        double radius = 185 + r.nextDouble() * 155;
        double strength = 0.80 + r.nextDouble() * 0.20;
        return new City(sx, sz, radius, strength, 0);
    }

    private City cityInGrid(int cx, int cz) {
        int gi = cx - gridX0;
        int gj = cz - gridZ0;
        if (gi < 0 || gj < 0 || gi >= gridSpan || gj >= gridSpan) {
            // 超出缓存范围：按原逻辑现算，保证正确性优先
            return cityAt(cx, cz);
        }
        return cityGrid[gj * gridSpan + gi];
    }

    public static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    public static double smoothstep(double e0, double e1, double x) {
        double t = clamp01((x - e0) / (e1 - e0));
        return t * t * (3 - 2 * t);
    }

    /** 一座城市的中心与尺度，供路网判断「环线半径」用。 */
    public static final class City {
        public final double x;
        public final double z;
        public final double radius;
        public final double strength;
        /** 到中心的距离。 */
        public final double dist;

        City(double x, double z, double radius, double strength, double dist) {
            this.x = x;
            this.z = z;
            this.radius = radius;
            this.strength = strength;
            this.dist = dist;
        }
    }

    /** 找最近的一座城；周围没有城返回 null。全部走查找表。 */
    public City nearestCity(double lx, double lz) {
        double wx = originX + lx;
        double wz = originZ + lz;
        int cellX = (int) Math.floor(wx / CITY_CELL);
        int cellZ = (int) Math.floor(wz / CITY_CELL);
        City best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                City c = cityInGrid(cellX + dx, cellZ + dz);
                if (c == null) {
                    continue;
                }
                double d = Math.hypot(wx - c.x, wz - c.z);
                if (d < bestDist) {
                    bestDist = d;
                    // dist 是与查询点相关的，所以每个查询点要一份新的 City
                    best = new City(c.x, c.z, c.radius, c.strength, d);
                }
            }
        }
        if (best != null && best.dist > best.radius * 1.6) {
            return null;
        }
        return best;
    }

    /**
     * 城市中心度：全局抖动网格上最近的一座城，径向衰减 × 城市强度。
     *
     * <p>查 3×3 邻域而不是只查当前格：城市半径最大 320 格，会跨过格子边界，
     * 只查本格会在边界上出现「城突然消失」的硬边。
     */
    public double center(double lx, double lz) {
        double wx = originX + lx;
        double wz = originZ + lz;
        int cellX = (int) Math.floor(wx / CITY_CELL);
        int cellZ = (int) Math.floor(wz / CITY_CELL);
        double best = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                City c0 = cityInGrid(cellX + dx, cellZ + dz);
                if (c0 == null) {
                    continue;
                }
                double d = Math.hypot(wx - c0.x, wz - c0.z) / c0.radius;
                if (d >= 1.0) {
                    continue;
                }
                // 内圈（d<0.15）压平到满强度，保证「城中心真的是中心」，
                // 否则站点附近也到不了 CBD 阈值，一座城市没有 downtown
                double c = smoothstep(1.0, 0.15, d) * c0.strength;
                if (c > best) {
                    best = c;
                }
            }
        }
        if (best <= 0) {
            return 0;
        }
        // 边界毛边：让城市轮廓不是完美圆，而是被地形噪声啃过
        double rough = 0.86 + 0.14 * (noise.fbm(wx * 0.008, wz * 0.008, 3, 11) * 0.5 + 0.5);
        return clamp01(best * rough);
    }

    /** 人口密度势：中心度 × 中频扰动。 */
    public double population(double lx, double lz) {
        double d = noise.fbm((originX + lx) * 0.030, (originZ + lz) * 0.030, 3, 23) * 0.5 + 0.5;
        return clamp01(center(lx, lz) * (0.55 + 0.45 * d));
    }

    /** 坡度：用 fBm 的有限差分近似，越大越崎岖。 */
    public double slope(double lx, double lz) {
        double x = originX + lx;
        double z = originZ + lz;
        double h = noise.fbm(x * 0.020, z * 0.020, 3, 31);
        double dx = noise.fbm((x + 1) * 0.020, z * 0.020, 3, 31) - h;
        double dz = noise.fbm(x * 0.020, (z + 1) * 0.020, 3, 31) - h;
        return clamp01(Math.hypot(dx, dz) * 8.0);
    }

    /** 绿地适宜度。 */
    public double green(double lx, double lz) {
        return noise.fbm((originX + lx) * 0.015, (originZ + lz) * 0.015, 3, 37) * 0.5 + 0.5;
    }

    /** 水体场。 */
    public double water(double lx, double lz) {
        return noise.fbm((originX + lx) * 0.006, (originZ + lz) * 0.006, 3, 43) * 0.5 + 0.5;
    }

    /** 街道曲率扰动：把正交路网整体做低频偏移，避免全域死板方格。 */
    public double warp(double lx, double lz) {
        return noise.fbm((originX + lx) * 0.011, (originZ + lz) * 0.011, 2, 47) * 6.0;
    }
}
