package citygen;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 megachunk 的完整规划结果。
 * 设计约束：构造完成后不可变；任意区块可按坐标单独查询，不依赖生成顺序。
 */
public final class CityPlan {

    public static final int CHUNK = 16;

    public static final class ChunkSummary {
        public final int cx;
        public final int cz;
        public final Map<Zone, Integer> zones = new EnumMap<>(Zone.class);
        public int roadCells;
        public int parcels;
        public double avgFar;

        ChunkSummary(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    /**
     * 单个区块的逐格视图：这是交给 Minecraft 侧的最小数据接口。
     * 16×16 的用地、道路、所属地块索引，以及逐列的建筑剖面，
     * 不含任何 MC 类型，便于本地验证。
     */
    public static final class ChunkSlice {
        public final int cx;
        public final int cz;
        public final Zone[] zones = new Zone[CHUNK * CHUNK];
        public final boolean[] road = new boolean[CHUNK * CHUNK];
        /** 道路等级，0 = 非道路，见 {@link Roads}。 */
        public final byte[] grade = new byte[CHUNK * CHUNK];
        /** 路面标线：0 无 / 1 斑马线(条纹沿x) / 2 斑马线(条纹沿z) / 3 路口。 */
        public final byte[] mark = new byte[CHUNK * CHUNK];
        /** 地铁位标志，见 {@link Roads#metro}。 */
        public final byte[] metro = new byte[CHUNK * CHUNK];
        /** 该格所属地块在 {@link Parcels#list} 中的下标，-1 表示无地块（路面或碎片）。 */
        public final int[] parcel = new int[CHUNK * CHUNK];
        /** 该列建筑高度（格，含屋顶）；0 表示不建。 */
        public final short[] bldHeight = new short[CHUNK * CHUNK];
        /** 该列在建筑里的角色：0 = 无（退让/无地块），1 = 外墙，2 = 内部。 */
        public final byte[] bldEdge = new byte[CHUNK * CHUNK];
        /** 到地块矩形四边的最小距离，封顶 15。用来做塔楼退台与尖顶收分。 */
        public final byte[] bldInset = new byte[CHUNK * CHUNK];
        /** 该地块的退让距离，配合 bldInset 算出「当前在第几级退台」。 */
        public final byte[] bldSetback = new byte[CHUNK * CHUNK];
        /**
         * 该列贴着哪几个面：bit0 北(-z)、bit1 南(+z)、bit2 西(-x)、bit3 东(+x)。
         * 立面造型要知道「沿墙方向」是哪条轴，否则开间会横着排。
         */
        public final byte[] bldFace = new byte[CHUNK * CHUNK];
        /** 到最近一个建筑角点的切比雪夫距离，封顶 3。做转角柱用。 */
        public final byte[] bldCorner = new byte[CHUNK * CHUNK];
        /** 立面风格 0..3。 */
        public final byte[] bldStyle = new byte[CHUNK * CHUNK];
        /** 建筑形态，见 {@link Parcels} 的 KIND_*。 */
        public final byte[] bldKind = new byte[CHUNK * CHUNK];
        /** 该地块长边是否沿 x 轴，联排要按它决定单元怎么排。 */
        public final boolean[] bldAlongX = new boolean[CHUNK * CHUNK];
        /** 层高（格），同一地块内一致。 */
        public final byte[] bldFloor = new byte[CHUNK * CHUNK];

        /**
         * 本区块涉及的地块槽位表。
         *
         * <p>为什么要把矩形也传过去：MC 侧要给每块地做**统一标高**（否则楼群顺坡爬，
         * 室内地面是斜的）。统一标高必须知道地块的完整范围，而地块可能横跨区块。
         * 矩形是规划层已知的、与地形无关的确定性数据，传过去不会有任何一致性问题。
         */
        public int slotCount;
        public final int[] slotRect = new int[MAX_SLOTS * 4];
        /** 每格属于哪个槽位，-1 = 无地块。 */
        public final byte[] slot = new byte[CHUNK * CHUNK];

        ChunkSlice(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
            Arrays.fill(parcel, -1);
            Arrays.fill(slot, (byte) -1);
            Arrays.fill(bldFloor, (byte) 4);
        }
    }

    /** 一个区块最多登记多少个地块，超出的按「无槽位」处理（不影响铺装，只是没有统一标高）。 */
    public static final int MAX_SLOTS = 24;

    public final long seed;
    public final int size;
    /** 本块规划在全局坐标系里的原点（世界坐标），路牌导航要用它算「到 CBD 还有多远」。 */
    public final int originX;
    public final int originZ;

    /**
     * 主干线地铁隧道沿线的远古城市放置点。
     *
     * <p>判定与主干线地铁同源：128 格网格的中心带（±2.5 格）、且在城市范围内
     * （主干线只铺在城里）。沿隧道方向每 384 格一个、偏移 48 格避开车站——
     * 玩家坐几站地铁就会遇到一个，正是「隧道沿线密集」的频率。
     *
     * <p>判定用的是未弯曲的网格线，而实际隧道被 warp 推开 ±6 格；
     * 远古城市 60 格见方，这点偏差无所谓。
     */
    public boolean isAncientCitySpot(int wx, int wz) {
        Field.City city = field.nearestCity(wx - originX, wz - originZ);
        if (city == null || city.dist >= city.radius * 0.72) {
            return false;
        }
        // 到最近网格中心的距离，与 Roads.edgeDistance 的中心定义一致
        double cx = Math.abs(wx - Math.floor(wx / (double) Roads.TRUNK_SPACING)
                * Roads.TRUNK_SPACING) > Roads.TRUNK_SPACING / 2.0
                ? Roads.TRUNK_SPACING - Math.abs(wx % Roads.TRUNK_SPACING)
                : Math.abs(wx % Roads.TRUNK_SPACING);
        double cz = Math.abs(wz % Roads.TRUNK_SPACING) > Roads.TRUNK_SPACING / 2.0
                ? Roads.TRUNK_SPACING - Math.abs(wz % Roads.TRUNK_SPACING)
                : Math.abs(wz % Roads.TRUNK_SPACING);
        if (cx < 2.5 && cz >= 2.5) {
            return Math.floorMod(wz - 48, 384) < 24;
        }
        if (cz < 2.5 && cx >= 2.5) {
            return Math.floorMod(wx - 48, 384) < 24;
        }
        return false;
    }

    /**
     * 把命中列规范化为该远古城市的**唯一**中心（x<<32 ^ z 编码）。
     *
     * <p>没有这一步，一个 60 格的城市横跨 6 个区块，每个区块各自命中
     * 就会连排放置 6 个城市。规范化后：同一条隧道线上的所有命中列
     * 都收敛到同一个中心点，先到的区块放置，其余用 PLACED 集合去重。
     */
    public long ancientCityCenter(int wx, int wz) {
        double cx = Math.abs(wx % (double) Roads.TRUNK_SPACING) > Roads.TRUNK_SPACING / 2.0
                ? Roads.TRUNK_SPACING - Math.abs(wx % (double) Roads.TRUNK_SPACING)
                : Math.abs(wx % (double) Roads.TRUNK_SPACING);
        double cz = Math.abs(wz % (double) Roads.TRUNK_SPACING) > Roads.TRUNK_SPACING / 2.0
                ? Roads.TRUNK_SPACING - Math.abs(wz % (double) Roads.TRUNK_SPACING)
                : Math.abs(wz % (double) Roads.TRUNK_SPACING);
        if (cx < 2.5 && cz >= 2.5) {
            // 竖线：x 固定在 k*128，沿线 along = wz，取区间中点
            int kx = (int) Math.round(wx / (double) Roads.TRUNK_SPACING);
            int kz = Math.floorDiv(wz - 48, 384);
            return enc(kx * Roads.TRUNK_SPACING, kz * 384 + 60);
        }
        // 横线：z 固定在 k*128，沿线 along = wx
        int kz = (int) Math.round(wz / (double) Roads.TRUNK_SPACING);
        int kx = Math.floorDiv(wx - 48, 384);
        return enc(kx * 384 + 60, kz * Roads.TRUNK_SPACING);
    }

    private static long enc(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private java.util.List<Roads.LineOut> lineCache;

    /** 世界坐标的道路折线，供外部地图（HowToGo）消费；懒生成一次，线程安全。 */
    public synchronized java.util.List<Roads.LineOut> roadLines() {
        if (lineCache == null) {
            lineCache = Roads.buildLines(field, size, originX, originZ);
        }
        return lineCache;
    }

    public final Field field;
    public final Zoning zoning;
    public final Roads roads;
    public final Parcels parcels;
    /** 各层耗时（毫秒），用于性能定位。 */
    public final Map<String, Long> timing = new java.util.LinkedHashMap<>();
    private final Map<Long, ChunkSummary> chunks = new java.util.HashMap<>();

    public CityPlan(long seed, int size, int originX, int originZ) {
        this.seed = seed;
        this.size = size;
        this.originX = originX;
        this.originZ = originZ;
        long t0 = System.nanoTime();
        this.field = new Field(seed, originX, originZ, size);
        this.zoning = new Zoning(seed, field, size, originX, originZ);
        long t1 = System.nanoTime();
        this.roads = new Roads(size, field, zoning);
        long t2 = System.nanoTime();
        this.parcels = new Parcels(seed, size, zoning, roads);
        long t3 = System.nanoTime();
        summarise();
        long t4 = System.nanoTime();
        timing.put("zoning", (t1 - t0) / 1_000_000);
        timing.put("roads", (t2 - t1) / 1_000_000);
        timing.put("parcels", (t3 - t2) / 1_000_000);
        timing.put("summarise", (t4 - t3) / 1_000_000);
    }

    private void summarise() {
        int cpr = size / CHUNK;
        for (int cz = 0; cz < cpr; cz++) {
            for (int cx = 0; cx < cpr; cx++) {
                ChunkSummary s = new ChunkSummary(cx, cz);
                for (int z = cz * CHUNK; z < (cz + 1) * CHUNK; z++) {
                    for (int x = cx * CHUNK; x < (cx + 1) * CHUNK; x++) {
                        s.zones.merge(zoning.zoneAt(x, z), 1, Integer::sum);
                        if (roads.at(x, z)) {
                            s.roadCells++;
                        }
                    }
                }
                chunks.put(key(cx, cz), s);
            }
        }
        for (Parcels.Parcel p : parcels.list) {
            int cx = (p.x + p.w / 2) / CHUNK;
            int cz = (p.y + p.h / 2) / CHUNK;
            ChunkSummary s = chunks.get(key(cx, cz));
            if (s == null) {
                continue;
            }
            s.parcels++;
            s.avgFar += p.far;
        }
        for (ChunkSummary s : chunks.values()) {
            s.avgFar = s.parcels == 0 ? 0 : s.avgFar / s.parcels;
        }
    }

    public static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /**
     * 取某个区块的逐格视图。规划在构造时已全部完成，这里只是切片读取，
     * 不触发任何重算，也不会因为调用顺序不同而改变结果。
     */
    public ChunkSlice slice(int cx, int cz) {
        ChunkSlice s = new ChunkSlice(cx, cz);
        int x0 = cx * CHUNK;
        int z0 = cz * CHUNK;
        for (int z = 0; z < CHUNK; z++) {
            for (int x = 0; x < CHUNK; x++) {
                int i = z * CHUNK + x;
                int wx = x0 + x;
                int wz = z0 + z;
                s.zones[i] = zoning.zoneAt(wx, wz);
                s.road[i] = roads.at(wx, wz);
                s.grade[i] = roads.gradeAt(wx, wz);
                s.mark[i] = roads.markAt(wx, wz);
                s.metro[i] = roads.metroAt(wx, wz);
            }
        }
        List<Parcels.Parcel> list = parcels.list;
        for (int pi = 0; pi < list.size(); pi++) {
            Parcels.Parcel p = list.get(pi);
            int fromX = Math.max(p.x, x0);
            int toX = Math.min(p.x + p.w, x0 + CHUNK);
            int fromZ = Math.max(p.y, z0);
            int toZ = Math.min(p.y + p.h, z0 + CHUNK);
            if (fromX >= toX || fromZ >= toZ) {
                continue;
            }
            int slotId = -1;
            if (p.height > 0 && s.slotCount < MAX_SLOTS) {
                slotId = s.slotCount++;
                s.slotRect[slotId * 4] = p.x;
                s.slotRect[slotId * 4 + 1] = p.y;
                s.slotRect[slotId * 4 + 2] = p.w;
                s.slotRect[slotId * 4 + 3] = p.h;
            }
            for (int z = fromZ; z < toZ; z++) {
                for (int x = fromX; x < toX; x++) {
                    int i = (z - z0) * CHUNK + (x - x0);
                    s.parcel[i] = pi;
                    s.slot[i] = (byte) slotId;
                    if (p.height <= 0) {
                        continue;
                    }
                    // 地块本身是轴对齐矩形（见 Parcels 的贪心分解），所以只看四个方向就够，
                    // 不需要知道地块的完整轮廓——这正是「按列独立决策、可以并行生成」的关键。
                    int inset = Math.min(
                            Math.min(x - p.x, p.x + p.w - 1 - x),
                            Math.min(z - p.y, p.y + p.h - 1 - z));
                    int dx0 = x - p.x;
                    int dx1 = p.x + p.w - 1 - x;
                    int dz0 = z - p.y;
                    int dz1 = p.y + p.h - 1 - z;
                    byte face = 0;
                    if (dz0 == inset) {
                        face |= 1;
                    }
                    if (dz1 == inset) {
                        face |= 2;
                    }
                    if (dx0 == inset) {
                        face |= 4;
                    }
                    if (dx1 == inset) {
                        face |= 8;
                    }
                    int corner = Math.min(
                            Math.min(Math.max(dx0, dz0), Math.max(dx0, dz1)),
                            Math.min(Math.max(dx1, dz0), Math.max(dx1, dz1)));

                    s.bldHeight[i] = (short) p.height;
                    s.bldInset[i] = (byte) Math.min(15, inset);
                    s.bldSetback[i] = (byte) p.setback;
                    s.bldFace[i] = face;
                    s.bldCorner[i] = (byte) Math.min(3, corner);
                    if (inset < p.setback) {
                        // 退让格不算建筑，但保留高度与 inset：外墙外的阳台靠它们定位
                        continue;
                    }
                    s.bldEdge[i] = (byte) (inset == p.setback ? 1 : 2);
                    s.bldStyle[i] = (byte) p.style;
                    s.bldKind[i] = (byte) p.kind;
                    s.bldAlongX[i] = p.alongX();
                    s.bldFloor[i] = (byte) p.floorHeight;
                }
            }
        }
        return s;
    }

    /** 按区块坐标查询；不存在的区块返回空摘要，绝不触发全局重算。 */
    public ChunkSummary chunk(int cx, int cz) {
        return chunks.get(key(cx, cz));
    }

    public Map<Zone, Integer> zoneTotals() {
        Map<Zone, Integer> m = new EnumMap<>(Zone.class);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                m.merge(zoning.zoneAt(x, z), 1, Integer::sum);
            }
        }
        return m;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(1 << 16);
        sb.append("{\n");
        sb.append("  \"seed\": ").append(seed).append(",\n");
        sb.append("  \"megachunk\": { \"size\": ").append(size)
          .append(", \"chunkSize\": ").append(CHUNK)
          .append(", \"chunksPerSide\": ").append(size / CHUNK).append(" },\n");
        sb.append("  \"sites\": ").append(zoning.sites.size()).append(",\n");

        sb.append("  \"zones\": {");
        Map<Zone, Integer> totals = zoneTotals();
        boolean first = true;
        for (Map.Entry<Zone, Integer> e : totals.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("\"").append(e.getKey().name()).append("\": ").append(e.getValue());
            first = false;
        }
        sb.append("},\n");

        int roadCells = roads.count();
        sb.append("  \"roads\": { \"cells\": ").append(roadCells)
          .append(", \"ratio\": ").append(String.format("%.4f", roadCells / (double) (size * size)))
          .append(" },\n");

        List<Parcels.Parcel> list = parcels.list;
        double avgArea = list.stream().mapToInt(Parcels.Parcel::area).average().orElse(0);
        sb.append("  \"parcels\": { \"count\": ").append(list.size())
          .append(", \"avgArea\": ").append(String.format("%.1f", avgArea))
          .append(", \"frontageRatio\": ").append(String.format("%.3f",
                  list.isEmpty() ? 0 : parcels.frontageCount() / (double) list.size()))
          .append(" },\n");

        sb.append("  \"samples\": [\n");
        int[][] probe = {{0, 0}, {0, size / CHUNK - 1}, {size / CHUNK - 1, size / CHUNK - 1}, {size / CHUNK / 2, size / CHUNK / 2}};
        for (int i = 0; i < probe.length; i++) {
            ChunkSummary s = chunk(probe[i][0], probe[i][1]);
            sb.append("    { \"cx\": ").append(probe[i][0]).append(", \"cz\": ").append(probe[i][1])
              .append(", \"roadCells\": ").append(s == null ? 0 : s.roadCells)
              .append(", \"parcels\": ").append(s == null ? 0 : s.parcels)
              .append(", \"avgFar\": ").append(String.format("%.2f", s == null ? 0 : s.avgFar))
              .append(", \"zones\": {");
            if (s != null) {
                boolean f2 = true;
                for (Map.Entry<Zone, Integer> e : s.zones.entrySet()) {
                    if (!f2) {
                        sb.append(", ");
                    }
                    sb.append("\"").append(e.getKey().name()).append("\":").append(e.getValue());
                    f2 = false;
                }
            }
            sb.append("} }").append(i == probe.length - 1 ? "" : ",").append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }
}
