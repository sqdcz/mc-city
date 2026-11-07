package citygen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 分区层：抖动网格采样站点 -> 最近邻 Voronoi -> 用地性质判定 -> 邻接兼容修复。
 * 对应 Minecraft 的 MultiNoiseBiomeSource，但额外引入了显式的相邻兼容约束。
 */
public final class Zoning {

    public static final class Site {
        public final int id;
        public final double x;
        public final double z;
        public Zone zone;
        public double axisX = 1.0;
        public double axisZ = 0.0;

        Site(int id, double x, double z, Zone zone) {
            this.id = id;
            this.x = x;
            this.z = z;
            this.zone = zone;
        }
    }

    public final int size;
    public final List<Site> sites = new ArrayList<>();
    public final Zone[] zoneOf;
    public final double[] d1;
    public final double[] d2;
    public final int[] nearest;
    public final int[] second;

    private final Set<Long> trunk = new HashSet<>();
    private final int originX;
    private final int originZ;

    /** Voronoi 站点网格边长（世界坐标）。越小分区越碎。 */
    private static final int SITE_CELL = 52;

    public Zoning(long seed, Field field, int size, int originX, int originZ) {
        this.size = size;
        this.originX = originX;
        this.originZ = originZ;
        int n = size * size;
        this.zoneOf = new Zone[n];
        this.d1 = new double[n];
        this.d2 = new double[n];
        this.nearest = new int[n];
        this.second = new int[n];

        buildSites(seed, field);
        rasterize();
        resolveAxes();
        buildTrunk();
        repair(field);
        // 兼容修复只改变站点的用地类别，距离场（最近/次近站点）不受影响，无需重算
        applySiteZones();
        applyWater(field);
    }

    /** 按当前站点用地类别刷新逐格 zoning，跳过昂贵的 Voronoi 距离重算。 */
    private void applySiteZones() {
        for (int i = 0; i < zoneOf.length; i++) {
            zoneOf[i] = sites.get(nearest[i]).zone;
        }
    }

    /** 主干路网：最小生成树保证全网连通，再补一条最近的非树边形成环路。 */
    private void buildTrunk() {
        int n = sites.size();
        List<long[]> edges = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double dx = sites.get(i).x - sites.get(j).x;
                double dz = sites.get(i).z - sites.get(j).z;
                edges.add(new long[]{(long) ((dx * dx + dz * dz) * 100.0), i, j});
            }
        }
        edges.sort((a, b) -> Long.compare(a[0], b[0]));
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (long[] e : edges) {
            int a = find(parent, (int) e[1]);
            int b = find(parent, (int) e[2]);
            if (a != b) {
                parent[a] = b;
                trunk.add(edgeKey((int) e[1], (int) e[2]));
            }
        }
        for (int i = 0; i < n; i++) {
            int best = -1;
            double bd = Double.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (i == j || trunk.contains(edgeKey(i, j))) {
                    continue;
                }
                double dx = sites.get(i).x - sites.get(j).x;
                double dz = sites.get(i).z - sites.get(j).z;
                double dd = dx * dx + dz * dz;
                if (dd < bd) {
                    bd = dd;
                    best = j;
                }
            }
            if (best >= 0) {
                trunk.add(edgeKey(i, best));
            }
        }
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static long edgeKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | hi;
    }

    public boolean isTrunk(int a, int b) {
        return trunk.contains(edgeKey(a, b));
    }

    /**
     * 全局抖动网格撒站点。
     *
     * <p>上一版是「从 18 开始按 stepX/stepZ 走格子」，那两个 step 依赖局部坐标上的中心度——
     * 于是相邻 megachunk 的站点阵列各走各的，边界上 Voronoi 分区直接错位。
     * 现在站点位置只由 (世界坐标所属格子, 种子) 决定：格子固定 44 格、抖动 ±36%，
     * 任何区块、任何顺序算出来的站点集合完全一致，跨块天然吻合。
     */
    private void buildSites(long seed, Field field) {
        int gx0 = Math.floorDiv(originX - SITE_CELL, SITE_CELL);
        int gx1 = Math.floorDiv(originX + size + SITE_CELL, SITE_CELL);
        int gz0 = Math.floorDiv(originZ - SITE_CELL, SITE_CELL);
        int gz1 = Math.floorDiv(originZ + size + SITE_CELL, SITE_CELL);
        for (int gz = gz0; gz <= gz1; gz++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                Rng r = Rng.at(seed, 101, gx, gz);
                // 站点存世界坐标，后面的距离场直接在局部坐标下减掉原点即可
                double wx = gx * SITE_CELL + SITE_CELL * 0.5 + r.nextSigned() * SITE_CELL * 0.36;
                double wz = gz * SITE_CELL + SITE_CELL * 0.5 + r.nextSigned() * SITE_CELL * 0.36;
                sites.add(new Site(sites.size(), wx, wz,
                        classify(field, wx - originX, wz - originZ, r)));
            }
        }
    }

    private static Zone classify(Field field, double x, double z, Rng r) {
        double c = field.center(x, z);
        double pop = field.population(x, z);
        double slope = field.slope(x, z);
        double green = field.green(x, z);

        if (c < 0.06) {
            return green > 0.58 ? Zone.GREEN : Zone.RES_LOW;
        }
        if (c > 0.74) {
            return Zone.CBD;
        }
        if (c > 0.52) {
            return r.nextDouble() < 0.72 ? Zone.COMMERCIAL : Zone.RES_HIGH;
        }
        // 外环工业带：平缓、少绿，允许连成片而不是零散孤岛
        if (c <= 0.40 && slope < 0.30 && green < 0.42) {
            return Zone.INDUSTRIAL;
        }
        // 绿地集中在城市外围和工业区外沿；城市核心里只留少量公园，
        // 否则噪声判定会在楼群里随机戳出一片片绿斑，把街区切碎。
        if (slope > 0.55 || green > (field.center(x, z) > 0.30 ? 0.78 : 0.63)) {
            return Zone.GREEN;
        }
        return pop > 0.42 ? Zone.RES_HIGH : Zone.RES_LOW;
    }

    private void rasterize() {
        // 防御：站点列表理论上有 12×12 个，不该为空；
        // 但万一上游改动导致为空，这里直接越界崩掉整块规划
        if (sites.isEmpty()) {
            Arrays.fill(zoneOf, Zone.RES_LOW);
            return;
        }
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                double px = x + 0.5;
                double pz = z + 0.5;
                double best = Double.MAX_VALUE;
                double secondD = Double.MAX_VALUE;
                int bestId = -1;
                int secondId = -1;
                for (Site s : sites) {
                    // 站点存的是世界坐标，格点是局部坐标，减掉原点再比距离
                    double dx = px - (s.x - originX);
                    double dz = pz - (s.z - originZ);
                    double dd = dx * dx + dz * dz;
                    if (dd < best) {
                        secondD = best;
                        secondId = bestId;
                        best = dd;
                        bestId = s.id;
                    } else if (dd < secondD) {
                        secondD = dd;
                        secondId = s.id;
                    }
                }
                int idx = z * size + x;
                d1[idx] = Math.sqrt(best);
                d2[idx] = Math.sqrt(secondD);
                nearest[idx] = bestId;
                second[idx] = secondId;
                zoneOf[idx] = sites.get(bestId).zone;
            }
        }
    }

    private void resolveAxes() {
        for (Site s : sites) {
            Site best = null;
            double bd = Double.MAX_VALUE;
            for (Site o : sites) {
                if (o == s) {
                    continue;
                }
                double dx = o.x - s.x;
                double dz = o.z - s.z;
                double dd = dx * dx + dz * dz;
                if (dd < bd) {
                    bd = dd;
                    best = o;
                }
            }
            if (best != null) {
                double dx = best.x - s.x;
                double dz = best.z - s.z;
                double len = Math.hypot(dx, dz);
                if (len > 1e-6) {
                    s.axisX = -dz / len;
                    s.axisZ = dx / len;
                }
            }
        }
    }

    /** 站点级邻接兼容修复：消除工业区贴邻住宅/CBD 的孤岛冲突。 */
    private void repair(Field field) {
        List<Set<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < sites.size(); i++) {
            adj.add(new HashSet<>());
        }
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int a = nearest[z * size + x];
                if (x + 1 < size) {
                    int b = nearest[z * size + x + 1];
                    if (a != b) {
                        adj.get(a).add(b);
                        adj.get(b).add(a);
                    }
                }
                if (z + 1 < size) {
                    int b = nearest[(z + 1) * size + x];
                    if (a != b) {
                        adj.get(a).add(b);
                        adj.get(b).add(a);
                    }
                }
            }
        }
        for (int iter = 0; iter < 5; iter++) {
            boolean changed = false;
            for (Site s : sites) {
                int bad = 0;
                for (int nb : adj.get(s.id)) {
                    if (!s.zone.compatible(sites.get(nb).zone)) {
                        bad++;
                    }
                }
                if (bad >= 2) {
                    if (s.zone == Zone.INDUSTRIAL) {
                        // 只有被住宅彻底包围的孤岛才退为缓冲绿地，成片的工业带保留
                        if (bad >= 3 && bad >= adj.get(s.id).size() - 1) {
                            s.zone = Zone.GREEN;
                            changed = true;
                        }
                    } else if (s.zone == Zone.RES_HIGH && bad >= 4) {
                        s.zone = Zone.RES_LOW;
                        changed = true;
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
    }

    private void applyWater(Field field) {
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                // 城市核心区（center 高）不铺水体：噪声逐点判定会把湖切进 CBD，
                // 桥再怎么架，水面也会把街区割得七零八落。
                if (field.water(x, z) > 0.72 && field.center(x, z) < 0.45) {
                    zoneOf[z * size + x] = Zone.WATER;
                }
            }
        }
    }

    public Zone zoneAt(int x, int z) {
        if (x < 0 || z < 0 || x >= size || z >= size) {
            return Zone.GREEN;
        }
        return zoneOf[z * size + x];
    }

    public Site siteAt(int x, int z) {
        if (x < 0 || z < 0 || x >= size || z >= size) {
            return sites.get(0);
        }
        return sites.get(nearest[z * size + x]);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
