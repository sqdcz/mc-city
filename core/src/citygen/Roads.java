package citygen;

/**
 * 路网层：四级道路，全部是**世界坐标的纯函数**。
 *
 * <p>这一版最重要的改动是**去掉了片区相位**。上一版每个 Voronoi 站点有自己随机的
 * {@code phaseX/phaseZ}，让相邻片区错落——但站点是按 megachunk 独立生成的，
 * 于是两个相邻 megachunk 算出的相位不同，边界处路网直接断开，形成一道很明显的接缝。
 *
 * <p>现在「某格是不是路、是几级路」只由 (世界坐标, 种子派生噪声) 决定，
 * 不含任何局部状态，因此任意区块、任意顺序算出来完全一致，跨块天然连续。
 * 错落感改由低频噪声 {@code Field.warp} 提供——它本来就是全局函数。
 *
 * <p>等级与宽度：
 * <pre>
 *   高速    间距 512 格   路宽 11 格   （压在 megachunk 边界上，天然对接）
 *   主干道  间距 128 格   路宽  8 格
 *   次干道  间距  64 格   路宽  6 格
 *   支路    间距 32–44    路宽  3 格   （按用地）
 * </pre>
 */
public final class Roads {

    public static final byte NONE = 0;
    public static final byte HIGHWAY = 1;
    public static final byte TRUNK = 2;
    public static final byte SECONDARY = 3;
    public static final byte LOCAL = 4;

    /** 高速间距 = megachunk 边长，正好压在块边界上，天然跨块对接。 */
    public static final int HIGHWAY_SPACING = 512;
    public static final int TRUNK_SPACING = 128;
    public static final int SECONDARY_SPACING = 64;

    public final int size;
    /** 0 = 非道路，1..4 = 道路等级。 */
    public final byte[] grade;
    public final boolean[] road;
    /**
     * 路面标线：0 无，1 斑马线（条纹沿 x，横跨南北向道路），
     * 2 斑马线（条纹沿 z），3 路口。
     *
     * <p>借鉴 Mesophil's Cities 的做法——路口不靠随机拼，而是有明确形态。
     * 我们没有 NBT 预制件，就用「到另一条路边缘的距离 &lt; 3 格」这一个判据：
     * 那正好是路口外侧人行横道该在的位置。
     */
    public final byte[] mark;
    /**
     * 地铁：位标志。bit0 = 沿 x 走（否则沿 z），bit1 = 轨道中心，bit2 = 车站（要开竖井）。
     * 0 = 没有地铁。
     *
     * <p>只铺在高速公路正下方的中心 3 格上。因为高速本身就是世界坐标的纯函数，
     * 地铁线于是也天然跨块连续——不需要任何跨区块的连通性约束。
     */
    public final byte[] metro;

    public static final int METRO_ALONG_X = 1;
    public static final int METRO_RAIL = 2;
    public static final int METRO_STATION = 4;
    /** 站厅：高速整幅路面下方都挖空，做月台。 */
    public static final int METRO_HALL = 8;
    /** 低层线路：埋深更浅/更深一层，避免与高速线同层打架。 */
    public static final int METRO_LOW = 16;
    /** 地面站亭：洞口上方盖个小棚子。 */
    public static final int METRO_HUT = 32;
    /**
     * 换乘竖井：把 y=40 的高速线和 y=36 的主干线打通。
     *
     * <p>只标在**高速线**的列上（那层有站厅、有地面出入口），
     * 条件是该列正下方就是主干线隧道穿过的地方——也就是两条线的交点附近。
     */
    public static final int METRO_TRANSFER = 64;

    public Roads(int size, Field field, Zoning zoning) {
        this.size = size;
        this.grade = new byte[size * size];
        this.road = new boolean[size * size];
        this.mark = new byte[size * size];
        this.metro = new byte[size * size];
        build(field, zoning);
    }

    /** 支路间距：越靠中心街区越大，郊区更疏。 */
    public static double localSpacingOf(Zone zone) {
        return switch (zone) {
            case CBD -> 40;
            case COMMERCIAL -> 36;
            case RES_HIGH -> 32;
            case INDUSTRIAL -> 44;
            default -> 32;
        };
    }

    /** 各级道路的半宽（格）。宽度 = 2 × 半宽。 */
    public static double halfWidthOf(byte grade) {
        return switch (grade) {
            case HIGHWAY -> 5.5;
            case TRUNK -> 4.0;
            case SECONDARY -> 3.0;
            case LOCAL -> 1.5;
            default -> 0;
        };
    }

    /** 等级越高越应该「削平、走直线」，用来决定地面平滑的强度。 */
    public static boolean isMajor(byte grade) {
        return grade == HIGHWAY || grade == TRUNK;
    }

    private void build(Field field, Zoning zoning) {
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                Zone cellZone = zoning.zoneAt(x, z);
                // 绿地和水面都不再跳过——路要能穿过公园、跨过河。
                // 水面只保留高速/主干/次干（架桥），支路不过水，免得整片湖面全是小路。
                boolean water = cellZone == Zone.WATER;
                // 弯曲用全局低频噪声：同一世界坐标永远得到同一个偏移，
                // 所以接缝两侧的路网是同一条曲线，不会错开
                double wx = x + field.warp(x, z);
                double wz = z + field.warp(x, z) * 0.8;

                Field.City city = field.nearestCity(x, z);
                byte gx = gradeOf(wx, cellZone);
                byte gz = gradeOf(wz, cellZone);
                if (gx == NONE && gz == NONE) {
                    continue;
                }
                int idx = z * size + x;
                grade[idx] = gx == NONE ? gz : (gz == NONE ? gx : (byte) Math.min(gx, gz));
                road[idx] = true;

                // 标线：两个方向都是路 = 路口；只有一个方向是路、且另一方向的路边在 3 格内 = 斑马线
                double clearZ = clearance(wz, cellZone);
                double clearX = clearance(wx, cellZone);
                if (gx != NONE && gz != NONE) {
                    mark[idx] = 3;
                } else if (gx != NONE && clearZ >= 0 && clearZ < 3) {
                    mark[idx] = 1;
                } else if (gz != NONE && clearX >= 0 && clearX < 3) {
                    mark[idx] = 2;
                }

                // ---- 地铁线网 ----
                // 三层思路：高速线埋 y=40，主干线与环线埋 y=36。
                // 不同深度是刻意的——两条线平面相交时不会在同一格抢轨道，
                // 换乘靠车站竖井把上下两层打通。
                if (grade[idx] == HIGHWAY) {
                    double half = halfWidthOf(HIGHWAY);
                    double offX = edgeDistance(wx, HIGHWAY_SPACING, half) + half;
                    double offZ = edgeDistance(wz, HIGHWAY_SPACING, half) + half;
                    boolean alongZ = gx == HIGHWAY && offX < 1.6;
                    boolean alongX = gz == HIGHWAY && offZ < 1.6;
                    if (alongZ != alongX) {
                        int m = alongX ? METRO_ALONG_X : 0;
                        double off = alongX ? offZ : offX;
                        if (off < 0.6) {
                            m |= METRO_RAIL;
                        }
                        double perp = alongX
                                ? edgeDistance(wx, HIGHWAY_SPACING, half)
                                : edgeDistance(wz, HIGHWAY_SPACING, half);
                        if (perp < 14) {
                            m |= METRO_STATION | METRO_HALL;
                            if (perp < 2) {
                                m |= METRO_HUT;
                            }
                        }
                        // 换乘竖井：这一列的正下方正好是主干线隧道（但不压主干线轨道）
                        double halfT = halfWidthOf(TRUNK);
                        double offTrunk = (alongX
                                ? edgeDistance(wx, TRUNK_SPACING, halfT)
                                : edgeDistance(wz, TRUNK_SPACING, halfT)) + halfT;
                        if (city != null && city.dist < city.radius * 0.92
                                && offTrunk >= 0.6 && offTrunk < 1.6 && off >= 0.6) {
                            m |= METRO_TRANSFER;
                        }
                        metro[idx] = (byte) m;
                    } else if (alongZ) {
                        metro[idx] = (byte) (METRO_STATION | METRO_RAIL | METRO_HALL | METRO_HUT);
                    }
                    if (metro[idx] == 0) {
                        double hw = halfWidthOf(HIGHWAY);
                        double perp = gx == HIGHWAY
                                ? edgeDistance(wz, HIGHWAY_SPACING, hw)
                                : edgeDistance(wx, HIGHWAY_SPACING, hw);
                        if (perp < 14) {
                            metro[idx] = (byte) METRO_HALL;
                        }
                    }
                }

                // 主干线：只铺在城里，128 格一张网，换乘站自然落在与高速线的交叉处
                if (city != null && grade[idx] == TRUNK && city.dist < city.radius * 0.92) {
                    double half = halfWidthOf(TRUNK);
                    double offX = edgeDistance(wx, TRUNK_SPACING, half) + half;
                    double offZ = edgeDistance(wz, TRUNK_SPACING, half) + half;
                    boolean alongZ = gx == TRUNK && offX < 1.6;
                    boolean alongX = gz == TRUNK && offZ < 1.6;
                    if (alongZ != alongX) {
                        int m = (alongX ? METRO_ALONG_X : 0) | METRO_LOW;
                        double off = alongX ? offZ : offX;
                        if (off < 0.6) {
                            m |= METRO_RAIL;
                        }
                        // 主干线自己的车站：沿路每 96 格一个。
                        // 间距不能太大——主干线只在城里铺，小城市整条线可能还不到 130 格，
                        // 256 的间距会让整条线一个站都没有，又退回「只能靠换乘井」的状态。
                        double alongPos = alongX ? wx : wz;
                        double toStation = Math.abs(alongPos - Math.rint(alongPos / 96.0) * 96.0);
                        if (toStation < 14) {
                            m |= METRO_STATION | METRO_HALL;
                            if (toStation < 2 && off >= 0.6) {
                                m |= METRO_HUT;
                            }
                        }
                        metro[idx] = (byte) (metro[idx] | m);
                    }
                }

                // 环线：以城市中心为心的方形环。做成方形而不是圆，是因为
                // 铁轨在圆弧上要拼 ASCENDING/曲线形状，逐列生成几乎必然接错；
                // 方形环全是直线，四条边各用 NORTH_SOUTH / EAST_WEST 就完事。
                if (city != null) {
                    double r = city.radius * 0.62;
                    double dx = Math.abs(wx - city.x);
                    double dz = Math.abs(wz - city.z);
                    boolean legX = Math.abs(dx - r) < 1.6 && dz < r;   // x = 常数，沿 z 走
                    boolean legZ = Math.abs(dz - r) < 1.6 && dx < r;   // z = 常数，沿 x 走
                    if (legX != legZ) {
                        int m = METRO_LOW | (legZ ? METRO_ALONG_X : 0);
                        double off = legZ ? Math.abs(dz - r) : Math.abs(dx - r);
                        if (off < 0.6) {
                            m |= METRO_RAIL;
                        }
                        metro[idx] = (byte) (metro[idx] | m);
                    }
                }
            }
        }
    }

    /** 该坐标方向上是几级路（取覆盖它的最高等级）。 */
    private static byte gradeOf(double value, Zone zone) {
        if (near(value, HIGHWAY_SPACING, halfWidthOf(HIGHWAY))) {
            return HIGHWAY;
        }
        if (near(value, TRUNK_SPACING, halfWidthOf(TRUNK))) {
            return TRUNK;
        }
        if (near(value, SECONDARY_SPACING, halfWidthOf(SECONDARY))) {
            return SECONDARY;
        }
        if (near(value, localSpacingOf(zone), halfWidthOf(LOCAL))) {
            return LOCAL;
        }
        return NONE;
    }

    /** 到该方向上最近一条路**边缘**的距离；路上返回负数，路外返回 &gt;=0。 */
    private static double clearance(double value, Zone zone) {
        double best = Double.MAX_VALUE;
        best = Math.min(best, edgeDistance(value, HIGHWAY_SPACING, halfWidthOf(HIGHWAY)));
        best = Math.min(best, edgeDistance(value, TRUNK_SPACING, halfWidthOf(TRUNK)));
        best = Math.min(best, edgeDistance(value, SECONDARY_SPACING, halfWidthOf(SECONDARY)));
        best = Math.min(best, edgeDistance(value, localSpacingOf(zone), halfWidthOf(LOCAL)));
        return best;
    }

    private static double edgeDistance(double value, double spacing, double halfWidth) {
        double m = value - spacing * Math.floor(value / spacing);
        double dist = Math.min(m, spacing - m);
        return dist - halfWidth;
    }

    /** 到最近整数倍的距离小于半宽即算在路面上。 */
    private static boolean near(double value, double spacing, double halfWidth) {
        double m = value - spacing * Math.floor(value / spacing);
        double dist = Math.min(m, spacing - m);
        return dist < halfWidth;
    }

    public boolean at(int x, int z) {
        if (x < 0 || z < 0 || x >= size || z >= size) {
            return false;
        }
        return road[z * size + x];
    }

    public byte gradeAt(int x, int z) {
        if (x < 0 || z < 0 || x >= size || z >= size) {
            return NONE;
        }
        return grade[z * size + x];
    }

    /**
     * 一条路的折线（世界坐标），供外部地图（HowToGo）消费。
     * 线是折线不是直线：弯道由 warp 采样而来，与实际铺设的路面走向一致。
     */
    public static final class LineOut {
        public final byte level;
        public final String id;
        public final double[] xs;
        public final double[] zs;

        LineOut(byte level, String id, double[] xs, double[] zs) {
            this.level = level;
            this.id = id;
            this.xs = xs;
            this.zs = zs;
        }
    }

    /**
     * 把路网参数化成折线集合。只输出高速/主干/次干三级——
     * 支路间距 32~44 格，全画上去地图会糊成一片。
     */
    public static java.util.List<LineOut> buildLines(Field field, int size, int originX, int originZ) {
        java.util.List<LineOut> out = new java.util.ArrayList<>();
        int[][] specs = {{HIGHWAY, HIGHWAY_SPACING}, {TRUNK, TRUNK_SPACING}, {SECONDARY, SECONDARY_SPACING}};
        int steps = Math.max(2, size / 32 + 1);
        for (int[] spec : specs) {
            byte level = (byte) spec[0];
            int spacing = spec[1];
            // 沿 x 走的线：z = k * spacing
            int k0 = Math.floorDiv(originZ - spacing / 2, spacing);
            int k1 = Math.floorDiv(originZ + size + spacing / 2, spacing);
            for (int k = k0; k <= k1; k++) {
                double zLine = k * spacing;
                double[] xs = new double[steps];
                double[] zs = new double[steps];
                for (int i = 0; i < steps; i++) {
                    double lx = originX + i * (size / (double) (steps - 1));
                    xs[i] = lx;
                    zs[i] = zLine + field.warp(lx - originX, zLine - originZ) * 0.8;
                }
                out.add(new LineOut(level, level + "-x-" + k + "-" + originX, xs, zs));
            }
            // 沿 z 走的线：x = j * spacing
            int j0 = Math.floorDiv(originX - spacing / 2, spacing);
            int j1 = Math.floorDiv(originX + size + spacing / 2, spacing);
            for (int j = j0; j <= j1; j++) {
                double xLine = j * spacing;
                double[] xs = new double[steps];
                double[] zs = new double[steps];
                for (int i = 0; i < steps; i++) {
                    double lz = originZ + i * (size / (double) (steps - 1));
                    xs[i] = xLine + field.warp(xLine - originX, lz - originZ);
                    zs[i] = lz;
                }
                out.add(new LineOut(level, level + "-z-" + j + "-" + originZ, xs, zs));
            }
        }
        return out;
    }

    public byte metroAt(int x, int z) {
        if (x < 0 || z < 0 || x >= size || z >= size) {
            return 0;
        }
        return metro[z * size + x];
    }

    public byte markAt(int x, int z) {
        if (x < 0 || z < 0 || x >= size || z >= size) {
            return 0;
        }
        return mark[z * size + x];
    }

    public int count() {
        int n = 0;
        for (boolean b : road) {
            if (b) {
                n++;
            }
        }
        return n;
    }

    /** 各级道路各占多少格，用于确认等级比例合理。 */
    public int[] countByGrade() {
        int[] n = new int[5];
        for (byte b : grade) {
            n[b]++;
        }
        return n;
    }
}
