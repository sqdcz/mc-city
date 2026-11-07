package citygen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 地块层：先把道路之间的空白切成连通街区，再在每个街区内做「最大内接矩形」贪心分解。
 *
 * <p>为什么不用包围盒 BSP：街区被斜向主干道切过后是三角形/五边形，包围盒的绝大部分
 * 落在道路或邻块上，切出来的矩形填充率不足，只能整块丢弃——中心高密度区会直接空掉。
 * 改为反复求内部纯矩形、切完挖掉、再求下一块，任意形状的街区都能被铺满，
 * 且地块边界天然落在街区内部，绝不压路。
 */
public final class Parcels {

    public static final class Parcel {
        public final int x;
        public final int y;
        public final int w;
        public final int h;
        public final Zone zone;
        public final boolean frontage;
        public final double far;
        /** 退让距离（格）：地块边界到建筑外墙之间留出的空地，做门前广场/人行道。 */
        public final int setback;
        /** 层数。0 表示这块地不建（尺寸不够）。 */
        public final int floors;
        /** 层高（格）。 */
        public final int floorHeight;
        /** 立面风格编号，0..3，交给渲染侧映射材质。 */
        public final int style;
        /** 建筑形态，见 {@link #KIND_*}。 */
        public final int kind;
        /** 建筑总高（格，含屋顶层）；0 表示不建。 */
        public final int height;

        Parcel(int x, int y, int w, int h, Zone zone, boolean frontage, double far,
               int setback, int floors, int floorHeight, int style, int kind) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.zone = zone;
            this.frontage = frontage;
            this.far = far;
            this.setback = setback;
            this.floors = floors;
            this.floorHeight = floorHeight;
            this.style = style;
            this.kind = kind;
            this.height = floors * floorHeight + 1;
        }

        public int area() {
            return w * h;
        }

        /** 长边方向是否沿 x 轴，联排与板楼要靠它决定单元怎么排。 */
        public boolean alongX() {
            return w >= h;
        }
    }

    /** 塔楼：方正、细高，CBD 与高密住宅的主力。 */
    public static final int KIND_TOWER = 0;
    /** 地标：极高，带尖顶，只出现在核心区，数量很少。 */
    public static final int KIND_LANDMARK = 1;
    /** 板楼：长条状，住宅区常见。 */
    public static final int KIND_SLAB = 2;
    /** 联排：一栋矮房沿长边重复若干单元，屋顶锯齿状。 */
    public static final int KIND_ROW = 3;
    /** 厂房/仓库：低矮、大平层、金属屋顶。 */
    public static final int KIND_BOX = 4;

    /** 小于这个面积（格²）的碎片不再成地，留给内部小径或绿化。 */
    private static final int MIN_PARCEL = 90;
    /** 小于这个面积的街区整体忽略。 */
    private static final int MIN_BLOCK = 100;

    public final List<Parcel> list = new ArrayList<>();

    private final int size;
    private final Roads roads;
    private final Zoning zoning;
    private final long seed;

    // maxRect 的复用缓冲，避免每个街区都重新分配
    private final int[] mh;
    private final int[] mstack;
    private final int[] mlefts;

    public Parcels(long seed, int size, Zoning zoning, Roads roads) {
        this.seed = seed;
        this.size = size;
        this.roads = roads;
        this.zoning = zoning;
        this.mh = new int[size + 1];
        this.mstack = new int[size + 1];
        this.mlefts = new int[size + 1];

        int n = size * size;
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        int[] stack = new int[n];
        int[] cells = new int[n];
        boolean[] avail = new boolean[n];

        for (int start = 0; start < n; start++) {
            if (roads.road[start] || comp[start] >= 0) {
                continue;
            }
            // 连通只在「同一大类」内延伸：建成区互相连通，绿地与水面各自成组。
            // 若严格按同一 Zone 连通，Voronoi 分区的斜边界会在正交街区里切出三角碎片，
            // 这些碎片既成不了矩形地块，也放不下正交建筑。
            Zone cellZone = zoning.zoneAt(start % size, start / size);
            int id = start;
            int sp = 0;
            int cellCount = 0;
            int minX = size;
            int maxX = -1;
            int minZ = size;
            int maxZ = -1;
            stack[sp++] = start;
            comp[start] = id;
            while (sp > 0) {
                int c = stack[--sp];
                int cx = c % size;
                int cz = c / size;
                cells[cellCount++] = c;
                if (cx < minX) {
                    minX = cx;
                }
                if (cx > maxX) {
                    maxX = cx;
                }
                if (cz < minZ) {
                    minZ = cz;
                }
                if (cz > maxZ) {
                    maxZ = cz;
                }
                if (cx > 0) {
                    int o = c - 1;
                    if (!roads.road[o] && comp[o] < 0
                            && sameGroup(cellZone, zoning.zoneAt(cx - 1, cz))) {
                        comp[o] = id;
                        stack[sp++] = o;
                    }
                }
                if (cx + 1 < size) {
                    int o = c + 1;
                    if (!roads.road[o] && comp[o] < 0
                            && sameGroup(cellZone, zoning.zoneAt(cx + 1, cz))) {
                        comp[o] = id;
                        stack[sp++] = o;
                    }
                }
                if (cz > 0) {
                    int o = c - size;
                    if (!roads.road[o] && comp[o] < 0
                            && sameGroup(cellZone, zoning.zoneAt(cx, cz - 1))) {
                        comp[o] = id;
                        stack[sp++] = o;
                    }
                }
                if (cz + 1 < size) {
                    int o = c + size;
                    if (!roads.road[o] && comp[o] < 0
                            && sameGroup(cellZone, zoning.zoneAt(cx, cz + 1))) {
                        comp[o] = id;
                        stack[sp++] = o;
                    }
                }
            }
            // 绿地与水面不产生地块
            if (!buildable(cellZone) || cellCount < MIN_BLOCK) {
                continue;
            }

            for (int i = 0; i < cellCount; i++) {
                avail[cells[i]] = true;
            }
            // 贪心拆解：每轮取内部最大纯矩形，切完挖掉，直到只剩碎片
            int left = cellCount;
            for (int guard = 0; guard < 64 && left >= MIN_PARCEL; guard++) {
                int[] rect = maxRect(avail, minX, minZ, maxX, maxZ);
                if (rect == null) {
                    break;
                }
                int area = rect[2] * rect[3];
                if (area < MIN_PARCEL) {
                    break;
                }
                for (int z = rect[1]; z < rect[1] + rect[3]; z++) {
                    for (int x = rect[0]; x < rect[0] + rect[2]; x++) {
                        avail[z * size + x] = false;
                    }
                }
                left -= area;
                // 街区可能跨多类用地，用矩形内的众数决定这一块是商住还是别的
                Zone zone = dominantZone(rect[0], rect[1], rect[2], rect[3]);
                if (buildable(zone)) {
                    Rng rng = Rng.at(seed, 733, rect[0], rect[1]);
                    split(rect[0], rect[1], rect[2], rect[3], zone, rng, 0);
                }
                // 这块已占剩余面积九成半以上，说明街区本身是规整矩形，剩余只是斜切碎角
                if (area >= left * 19) {
                    break;
                }
            }
            for (int i = 0; i < cellCount; i++) {
                avail[cells[i]] = false;
            }
        }
    }

    /**
     * 在给定包围盒内求全为 avail 的最大轴对齐矩形（直方图 + 单调栈，O(面积)）。
     * 返回 {x, y, w, h}，找不到返回 null。
     */
    private int[] maxRect(boolean[] avail, int x0, int z0, int x1, int z1) {
        int width = x1 - x0 + 1;
        int[] height = mh;
        int[] stack = mstack;
        int[] lefts = mlefts;
        Arrays.fill(height, 0, width, 0);
        int bestArea = 0;
        int[] best = null;
        for (int z = z0; z <= z1; z++) {
            int rowBase = z * size + x0;
            for (int i = 0; i < width; i++) {
                height[i] = avail[rowBase + i] ? height[i] + 1 : 0;
            }
            int sp = 0;
            for (int i = 0; i <= width; i++) {
                int cur = i < width ? height[i] : -1;
                int start = i;
                while (sp > 0 && stack[sp - 1] >= cur) {
                    sp--;
                    int h = stack[sp];
                    int left = lefts[sp];
                    int area = h * (i - left);
                    if (area > bestArea) {
                        bestArea = area;
                        best = new int[]{x0 + left, z - h + 1, i - left, h};
                    }
                    start = left;
                }
                stack[sp] = cur;
                lefts[sp] = start;
                sp++;
            }
        }
        return best;
    }

    /** 绿地与水面不承载地块。 */
    private static boolean buildable(Zone z) {
        return z != Zone.GREEN && z != Zone.WATER;
    }

    /** 两类用地是否属于同一大类：建成区彼此连通，绿地与水面各自连通。 */
    private static boolean sameGroup(Zone a, Zone b) {
        return buildable(a) == buildable(b);
    }

    /** 矩形内出现次数最多的用地类别。 */
    private Zone dominantZone(int x, int y, int w, int h) {
        int[] tally = new int[Zone.values().length];
        for (int z = y; z < y + h; z++) {
            for (int xx = x; xx < x + w; xx++) {
                tally[zoning.zoneAt(xx, z).ordinal()]++;
            }
        }
        int best = 0;
        for (int i = 1; i < tally.length; i++) {
            if (tally[i] > tally[best]) {
                best = i;
            }
        }
        return Zone.values()[best];
    }

    /**
     * 每类用地的目标地块面积（格²），决定街区最终切到多大一块。
     * 数值对应 Minecraft 里的实际体量：住宅 14×14、商业 21×21、CBD 塔楼 26×26。
     */
    private static int targetArea(Zone zone) {
        return switch (zone) {
            case CBD -> 700;
            case COMMERCIAL -> 450;
            case RES_HIGH -> 280;
            case RES_LOW -> 200;
            case INDUSTRIAL -> 1200;
            default -> 300;
        };
    }

    private void split(int x, int y, int w, int h, Zone zone, Rng rng, int depth) {
        final int minSide = 8;
        int target = targetArea(zone);
        boolean canW = w >= minSide * 2;
        boolean canH = h >= minSide * 2;
        if (depth > 8 || w * h <= target * 1.5 || (!canW && !canH)) {
            emit(x, y, w, h, zone, rng);
            return;
        }
        boolean vertical = canW && (!canH || w >= h);
        if (vertical) {
            int cut = (int) (w * (0.40 + rng.nextDouble() * 0.20));
            cut = Math.max(minSide, Math.min(w - minSide, cut));
            split(x, y, cut, h, zone, rng, depth + 1);
            split(x + cut, y, w - cut, h, zone, rng, depth + 1);
        } else {
            int cut = (int) (h * (0.40 + rng.nextDouble() * 0.20));
            cut = Math.max(minSide, Math.min(h - minSide, cut));
            split(x, y, w, cut, zone, rng, depth + 1);
            split(x, y + cut, w, h - cut, zone, rng, depth + 1);
        }
    }

    /** 矩形来自 maxRect，天然整体落在街区内部，因此不需要再做填充率校验。 */
    private void emit(int x, int y, int w, int h, Zone zone, Rng rng) {
        // 细长条无法承载建筑，直接放弃（留给小径或绿化）
        if (w * h < MIN_PARCEL || Math.min(w, h) < 6) {
            return;
        }
        boolean frontage = hasFrontage(x, y, w, h);
        double far = zone.far * (0.72 + rng.nextDouble() * 0.56);

        // 建筑参数必须只由 (seed, 地块矩形, 用地) 决定：
        // 同一个地块可能被好几个区块切成碎片，每片都得算出完全一致的层数，
        // 否则屋顶会串成台阶。所以这里绝不能用上面那个 split 的 rng。
        Rng br = new Rng(Rng.derive(seed, 911,
                x * 31 + y, w * 17 + h + zone.ordinal() * 7));
        int setback = (zone == Zone.RES_LOW || zone == Zone.INDUSTRIAL) ? 2 : 3;
        int floorHeight = 4;
        int kind = kindFor(zone, w, h, br);
        int floors = floorsFor(zone, kind, w, h, setback, far, br);
        int style = styleFor(zone, kind, br);
        list.add(new Parcel(x, y, w, h, zone, frontage, far,
                setback, floors, floorHeight, style, kind));
    }

    /**
     * 形态：由用地决定大方向，地块长宽比决定细节。
     * 长条地块做板楼或联排，方正地块做塔楼；核心区有极小概率出地标。
     */
    private static int kindFor(Zone zone, int w, int h, Rng rng) {
        double ratio = Math.max(w, h) / (double) Math.max(1, Math.min(w, h));
        return switch (zone) {
            case CBD -> rng.nextDouble() < 0.30 ? KIND_LANDMARK : KIND_TOWER;
            case COMMERCIAL -> ratio > 1.7 ? KIND_SLAB : KIND_TOWER;
            case RES_HIGH -> ratio > 1.6 ? KIND_SLAB : KIND_TOWER;
            case RES_LOW -> ratio > 1.5 && rng.nextDouble() < 0.7 ? KIND_ROW : KIND_SLAB;
            case INDUSTRIAL -> KIND_BOX;
            default -> KIND_SLAB;
        };
    }

    /**
     * 层数：先按 (用地 × 形态) 给区间，再被地块尺度收口，最后用容积率做随机。
     *
     * <p>三个约束缺一不可：地标的 60 层放不进 10×10 的地块；住宅盖 40 层会跟容积率矛盾；
     * 而如果只按面积算，同一片街区会全是同一高度，看起来像兵营。
     */
    private static int floorsFor(Zone zone, int kind, int w, int h, int setback, double far, Rng rng) {
        int usable = Math.min(w, h) - setback * 2;
        // 退让之后塞不下 4×4 的建筑，就不建
        if (usable < 4) {
            return 0;
        }
        int lo = 1;
        int hi = 3;
        switch (kind) {
            case KIND_LANDMARK -> {
                lo = 20;
                hi = 30;
            }
            case KIND_TOWER -> {
                switch (zone) {
                    case CBD -> { lo = 10; hi = 22; }
                    case COMMERCIAL -> { lo = 5; hi = 12; }
                    case RES_HIGH -> { lo = 6; hi = 14; }
                    default -> { lo = 4; hi = 10; }
                }
            }
            case KIND_SLAB -> {
                switch (zone) {
                    case CBD -> { lo = 8; hi = 14; }
                    case COMMERCIAL -> { lo = 4; hi = 8; }
                    case RES_HIGH -> { lo = 4; hi = 9; }
                    default -> { lo = 2; hi = 5; }
                }
            }
            case KIND_ROW -> { lo = 2; hi = 4; }
            case KIND_BOX -> { lo = 1; hi = 3; }
            default -> { }
        }
        // 尺度收口：防止一个 8 宽的地盖出 50 层像根杆子。
        // 但塔楼和地标必须放得很松——现实里 13 米进深的细高塔很常见，
        // 上一版用 2 + usable × 1.2 会把它压到 20 层以下，地标直接被压没
        double sizeFactor = switch (kind) {
            case KIND_LANDMARK -> 3.0;
            case KIND_TOWER -> 2.2;
            case KIND_SLAB -> 1.2;
            default -> 0.6;
        };
        int sizeCap = Math.max(1, (int) (2 + usable * sizeFactor));
        hi = Math.min(hi, sizeCap);
        lo = Math.min(lo, hi);
        // far 在 0.72–1.28 倍之间抖动，用它当「这块地愿意盖多高」的比例
        double willingness = Math.min(1.0, far / zone.far);
        int span = hi - lo;
        int floors = lo + (int) (willingness * span * (0.55 + rng.nextDouble() * 0.45));
        return Math.max(1, Math.min(hi, floors));
    }

    /** 立面风格：CBD 玻璃幕墙、商业砖石、住宅白墙、工业金属板。 */
    private static int styleFor(Zone zone, int kind, Rng rng) {
        if (kind == KIND_LANDMARK) {
            return 0;
        }
        if (kind == KIND_BOX) {
            return 3;
        }
        return switch (zone) {
            case CBD -> 0;
            case COMMERCIAL -> 1;
            case RES_HIGH, RES_LOW -> rng.nextDouble() < 0.6 ? 2 : 1;
            default -> 2;
        };
    }

    /** 只统计真正贴路的边；由道路围合出的街区本身就临街，这里用于区分「正面」与「背面」。 */
    private boolean hasFrontage(int x, int y, int w, int h) {
        int hits = 0;
        for (int xx = x; xx < x + w; xx++) {
            if (roads.at(xx, y - 1)) {
                hits++;
            }
            if (roads.at(xx, y + h)) {
                hits++;
            }
        }
        for (int zz = y; zz < y + h; zz++) {
            if (roads.at(x - 1, zz)) {
                hits++;
            }
            if (roads.at(x + w, zz)) {
                hits++;
            }
        }
        return hits >= 4;
    }

    public long frontageCount() {
        return list.stream().filter(p -> p.frontage).count();
    }
}
