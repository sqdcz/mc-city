package dev.ship.citygen;

import citygen.CityPlan;
import citygen.Field;
import citygen.Roads;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * 把规划结果刷到真实方块上：地面平整 + 建筑体 + 街道设施。
 *
 * <p>核心约束没变——**逐列独立决策，绝不跨区块读写**。地块是轴对齐矩形（见 {@code Parcels}），
 * 所以「离外墙多远」「这栋楼多高」「这一级退台在哪」全都能只靠本列坐标算出来。
 *
 * <p>这一版新增的三件事：
 *
 * <h2>1. 平滑标高（挖山与隧道的根据）</h2>
 * 上一版把地面按 2 格量化，于是楼群顺着山坡逐级爬升，每栋楼的室内地面都是斜的。
 * 现在改用 {@link ChunkGenerator#getBaseHeight} 采样出**平滑地形标高**：
 * 4 格网格给建筑与支路用（贴近地形），32 格网格给高速与主干道用（拉直、压平）。
 * 两个网格都是世界坐标的纯函数，所以邻区块算出来的值完全一致，不会有接缝。
 * <p>铺装高度改为「按平滑标高削/填」，坡地于是被削成台地，楼也就立得住了。
 *
 * <h2>2. 隧道</h2>
 * 高速在山里的平滑标高远低于地表。这时**只挖出净空、保留上方山体**，
 * 天然形成穿山隧道——不需要任何额外的隧道生成器。平地则挖不到东西，不受影响。
 *
 * <h2>3. 建筑可进入</h2>
 * 外墙每 29 格开一个 2 格高的门洞，楼板每 9 格留一个 3×3 贯通竖井，
 * 于是楼不再是实心盒子：能进去、能往上爬。
 */
public final class CitySurfacePainter {

    /**
     * 采样网格步长。细网格贴地形给建筑用；粗网格给道路——**网格越粗，纵向剖面越平**，
     * 陡坡处路面就会高出地面，靠桥墩撑住，长出来就是高架桥，而不是贴着山壁斜下去。
     */
    private static final int FINE_STEP = 4;
    private static final int COARSE_STEP = 64;
    /** 削方深度超过这个值就当隧道处理，只挖净空、保留上方山体。 */
    private static final int TUNNEL_CLEARANCE = 4;
    /**
     * 填方上限。放得很宽是因为**这里填的就是地基**：
     * 陡坡上的地块统一标高之后，低的那一侧需要一路垒到标高，
     * 那根石柱就是楼的基座，不是「土台」。
     */
    private static final int MAX_FILL = 64;
    /** 单列削方上限（格）。超过就抬高地面，宁可填也不挖坑。 */
    private static final int MAX_CUT = 7;
    /** 地块统一标高相对周围平滑地形的最大下挖深度：坡地上把山削掉一块。 */
    private static final int MAX_PAD_DROP = 14;
    /** 最大上抬高度：免得低洼处被垫成高台。 */
    private static final int MAX_PAD_RISE = 6;
    /** 塔楼每几层退台一次。 */

    private static final int STYLES = 4;

    private static final BlockState ROAD_LOCAL = Blocks.CONCRETE.pick(DyeColor.GRAY).defaultBlockState();
    private static final BlockState ROAD_MAJOR = Blocks.CONCRETE.pick(DyeColor.BLACK).defaultBlockState();
    private static final BlockState PAVEMENT = Blocks.SMOOTH_STONE.defaultBlockState();
    /** 斑马线：白色混凝土。 */
    private static final BlockState CROSSWALK = Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState();
    private static final BlockState CHEST = Blocks.CHEST.defaultBlockState();
    private static final BlockState SPAWNER = Blocks.SPAWNER.defaultBlockState();
    /** 街道导航牌：橡木立地告示牌。旋转值 0/4/8/12 分别对应南/西/北/东。 */
    private static final BlockState[] SIGN_BY_ROT = new BlockState[16];

    static {
        for (int i = 0; i < 16; i++) {
            SIGN_BY_ROT[i] = Blocks.OAK_SIGN.defaultBlockState()
                    .setValue(StandingSignBlock.ROTATION, i);
        }
    }
    /** 换乘竖井用脚手架上下的，因为它不需要附着在实心面上，井道四周挖空也不会掉。 */
    private static final BlockState SCAFFOLD = Blocks.SCAFFOLDING.defaultBlockState();
    private static final BlockState FOUNDATION = Blocks.STONE.defaultBlockState();
    private static final BlockState RETAINING = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState SLAB = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState LAMP = Blocks.SEA_LANTERN.defaultBlockState();
    /** 铁轨：南北向 / 东西向。 */
    private static final BlockState RAIL_Z = Blocks.RAIL.defaultBlockState()
            .setValue(net.minecraft.world.level.block.RailBlock.SHAPE,
                    net.minecraft.world.level.block.state.properties.RailShape.NORTH_SOUTH);
    private static final BlockState RAIL_X = Blocks.RAIL.defaultBlockState()
            .setValue(net.minecraft.world.level.block.RailBlock.SHAPE,
                    net.minecraft.world.level.block.state.properties.RailShape.EAST_WEST);
    /** 梯子朝北 = 贴在它南侧那格实心方块上，井壁正好在 +z 方向。 */
    private static final BlockState LADDER = Blocks.LADDER.defaultBlockState()
            .setValue(net.minecraft.world.level.block.LadderBlock.FACING,
                    net.minecraft.core.Direction.NORTH);
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static final BlockState[] WALL = new BlockState[STYLES];
    private static final BlockState[] GLAZING = new BlockState[STYLES];
    private static final BlockState[] ROOF = new BlockState[STYLES];

    static {
        // 0 核心商务区：浅灰混凝土 + 灰玻璃幕墙
        WALL[0] = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
        GLAZING[0] = Blocks.STAINED_GLASS.pick(DyeColor.GRAY).defaultBlockState();
        ROOF[0] = Blocks.SMOOTH_STONE.defaultBlockState();
        // 1 商业：红砖 + 清玻璃
        WALL[1] = Blocks.BRICKS.defaultBlockState();
        GLAZING[1] = Blocks.GLASS.defaultBlockState();
        ROOF[1] = Blocks.STONE_BRICKS.defaultBlockState();
        // 2 住宅：白墙 + 浅蓝玻璃
        WALL[2] = Blocks.CONCRETE.pick(DyeColor.WHITE).defaultBlockState();
        GLAZING[2] = Blocks.STAINED_GLASS.pick(DyeColor.LIGHT_BLUE).defaultBlockState();
        ROOF[2] = Blocks.SMOOTH_STONE.defaultBlockState();
        // 3 工业：浅灰混凝土 + 清玻璃 + 金属屋顶
        WALL[3] = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
        GLAZING[3] = Blocks.GLASS.defaultBlockState();
        ROOF[3] = Blocks.IRON_BLOCK.defaultBlockState();
    }

    private CitySurfacePainter() {
    }

    public static void paint(ChunkAccess chunk, CityPlanManager manager,
                             ChunkGenerator generator, RandomState randomState) {
        ChunkPos chunkPos = chunk.getPos();
        int megachunkX = CityPlanManager.megachunkOf(chunkPos.getMinBlockX());
        int megachunkZ = CityPlanManager.megachunkOf(chunkPos.getMinBlockZ());
        CityPlan plan = manager.plan(megachunkX, megachunkZ);
        CityPlan.ChunkSlice slice = plan.slice(
                Math.floorMod(chunkPos.x(), CityPlanManager.CHUNKS_PER_SIDE),
                Math.floorMod(chunkPos.z(), CityPlanManager.CHUNKS_PER_SIDE));

        HeightGrid fine = HeightGrid.sample(generator, randomState, chunk, FINE_STEP);
        HeightGrid coarse = HeightGrid.sample(generator, randomState, chunk, COARSE_STEP);

        // 每块地取一个统一标高：把地块矩形五个点（四角 + 中心）的地形高度取中位数。
        // 这一步就是「挖山」的来源——坡地上的一块地会被削成一个平坝，而不是让楼顺着坡爬。
        int[] slotBase = new int[slice.slotCount];
        for (int i = 0; i < slice.slotCount; i++) {
            slotBase[i] = parcelBase(generator, randomState, chunk,
                    slice.slotRect[i * 4], slice.slotRect[i * 4 + 1],
                    slice.slotRect[i * 4 + 2], slice.slotRect[i * 4 + 3]);
        }

        Heightmap heightmap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // 预扫描：地块矩形如果跨到水面上，整块不建。
        // 否则水面上的列被跳过、陆地上的列照常建，剩下的就是「半个楼」立在水边。
        boolean[] slotOnWater = new boolean[Math.max(1, slice.slotCount)];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int slot = slice.slot[z * 16 + x];
                if (slot < 0 || slotOnWater[slot]) {
                    continue;
                }
                int sy = heightmap.getHighestTaken(x, z);
                if (chunk.getBlockState(cursor.set(
                        chunk.getPos().getMinBlockX() + x, sy,
                        chunk.getPos().getMinBlockZ() + z)).is(Blocks.WATER)) {
                    slotOnWater[slot] = true;
                }
            }
        }
        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY();
        int topLimit = maxY - 2;

        int built = 0;
        int blocks = 0;
        long chestStart = CHESTS.get();
        int tunnels = 0;
        int maxCut = 0;
        int lost = 0;

        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = z * 16 + x;
                byte grade = slice.grade[index];
                boolean isRoad = grade != Roads.NONE;
                boolean hasParcel = slice.parcel[index] >= 0;
                byte metroFlag = slice.metro[index];
                // 环线和主干线可能会穿过绿地/空地，那些列也得挖，否则隧道会断
                if (!isRoad && !hasParcel && metroFlag == 0) {
                    continue;
                }
                int wx = chunkPos.getMinBlockX() + x;
                int wz = chunkPos.getMinBlockZ() + z;
                try {

                int surfaceY = surfaceOf(chunk, heightmap, cursor, x, z, wx, wz, minY, maxY);
                if (surfaceY < minY) {
                    continue;
                }
                // 遇到水面：道路就架桥，非道路保持水体不动
                if (chunk.getBlockState(cursor.set(wx, surfaceY, wz)).is(Blocks.WATER)) {
                    if (isRoad) {
                        blocks += bridge(chunk, cursor, slice, index, x, z, wx, wz,
                                surfaceY, isRoad && Roads.isMajor(grade), minY, topLimit);
                    }
                    continue;
                }

                // 道路一律用「拉直压平」的粗网格标高：越平越容易和地表拉开差距，
                // 于是山区里连支路也会自动变成隧道 / 路堑。建筑则用地块统一标高。
                double smooth = isRoad ? coarse.at(wx, wz) : fine.at(wx, wz);
                int groundY = (int) Math.round(smooth) - 1;

                // 地块内的列一律压到**同一个**地块标高。
                // 上一版这里还把地块标高压回各列自己的平滑地形（上下各留一点余量），
                // 结果陡坡上每列各自下坠，整栋楼像沙子一样塌下去——逐列夹紧就是罪魁祸首。
                int slotId = slice.slot[index];
                boolean buildHere = slice.bldEdge[index] != 0;
                boolean unified = slotId >= 0 && slotBase[slotId] != NO_PARCEL
                        && !slotOnWater[slotId];
                if (unified) {
                    groundY = slotBase[slotId];
                } else if (slotId >= 0) {
                    // 陡坡地块：不统一标高、不建房，只把这一列当成普通地面铺装，
                    // 保住了原地形，也就不会有坑
                    buildHere = false;
                }
                groundY = Math.max(minY + 2, Math.min(topLimit - 2, groundY));

                // 填方上限：山谷不能被填成几十格高的土台
                if (groundY - surfaceY > MAX_FILL) {
                    groundY = surfaceY + MAX_FILL;
                }
                // 削方上限对**所有列**生效。
                // 超过的列把地面抬到「山顶往下 7 格」，并且**这一列不建楼**，
                // 只当铺装平台。楼只会立在统一标高的那一部分上，
                // 山包变成楼旁边的小平台——而不是被挖出一个天坑。
                if (surfaceY - groundY > MAX_CUT) {
                    groundY = surfaceY - MAX_CUT;
                    buildHere = false;
                }
                // 填方分三种情况处理，这是「地基 / 桥墩 / 普通补土」的区别：
                //   ① 道路且抬得很高 → 只每隔 8 格立一根桥墩，上面架桥面（架空）
                //   ② 建筑且抬得很高 → 砌成实心石砖基座（楼不能悬空）
                //   ③ 其余浅填 → 普通石头
                int fillDepth = groundY - surfaceY;
                boolean parcelEdge = hasParcel && slice.bldInset[index] == 0;
                if (isRoad && fillDepth > 8 && Math.floorMod(wx + wz, 8) != 0) {
                    // 架空段：不放柱，桥面由前后的桥墩和自身承重
                } else {
                    BlockState fillMat = (parcelEdge || (!isRoad && fillDepth > 8))
                            ? RETAINING : FOUNDATION;
                    for (int y = surfaceY + 1; y <= groundY; y++) {
                        chunk.setBlockState(cursor.set(wx, y, wz), fillMat, 0);
                    }
                }

                // 削方：削太深就只挖净空、保留上方山体 → 隧道
                maxCut = Math.max(maxCut, surfaceY - groundY);
                int cutTo = surfaceY;
                if (isRoad && surfaceY > groundY + TUNNEL_CLEARANCE) {
                    cutTo = groundY + TUNNEL_CLEARANCE;
                    tunnels++;
                }
                for (int y = groundY + 1; y <= cutTo; y++) {
                    chunk.setBlockState(cursor.set(wx, y, wz), AIR, 0);
                }

                BlockState ground;
                if (!isRoad) {
                    ground = PAVEMENT;
                } else {
                    byte mark = slice.mark[index];
                    if (mark == 3) {
                        // 路口：统一用深色沥青，转角一眼看得出是路口
                        ground = ROAD_MAJOR;
                    } else if (mark == 1) {
                        ground = Math.floorMod(wx, 2) == 0 ? CROSSWALK : ROAD_LOCAL;
                    } else if (mark == 2) {
                        ground = Math.floorMod(wz, 2) == 0 ? CROSSWALK : ROAD_LOCAL;
                    } else {
                        ground = Roads.isMajor(grade) ? ROAD_MAJOR : ROAD_LOCAL;
                    }
                }
                chunk.setBlockState(cursor.set(wx, groundY, wz), ground, 0);
                if (chunk.getBlockState(cursor) != ground) {
                    lost++;
                }
                blocks++;

                int height = slice.bldHeight[index];
                if (buildHere) {
                    blocks += building(chunk, cursor, slice, index, wx, wz, groundY, height, topLimit);
                    built++;
                } else if (!isRoad && hasParcel) {
                    // 退让带：阳台只给真正建起来的地块做，陡坡地块不建，否则阳台会长在半空
                    if (unified) {
                        blocks += facadeDetail(chunk, cursor, slice, index, wx, wz, groundY, topLimit);
                        blocks += streetSign(chunk, cursor, slice, index, plan, x, z, wx, wz,
                                groundY, topLimit);
                    }
                    blocks += lamp(chunk, cursor, slice, x, z, wx, wz, groundY, topLimit);
                }

                if (metroFlag != 0) {
                    blocks += carveMetro(chunk, cursor, metroFlag, wx, wz, groundY);
                }
                } catch (RuntimeException e) {
                    // 单列出问题只丢这一列，不能让整块 16×16 连坐
                    System.out.println("[CityGen] 列涂装异常 (" + wx + "," + wz + "): " + e);
                }
            }
        }
        report(chunkPos, built, blocks, (int) (CHESTS.get() - chestStart), tunnels, maxCut, lost);
    }

    // ------------------------------------------------------------------
    // 地面标高
    // ------------------------------------------------------------------

    /**
     * 一格里预采样的平滑地形高度，双线性插值后可按世界坐标查询。
     *
     * <p>为什么必须用 {@code getBaseHeight} 而不是读高度图：高度图只能读本区块，
     * 想让「一个地块/一段路用统一标高」就必然要跨越区块边界读邻居——
     * 那在生成阶段是死路。{@code getBaseHeight} 是纯函数，任意坐标都能算，
     * 于是整片区域的标高变得可预测、可跨区块一致。
     */
    private static final class HeightGrid {
        private final int step;
        private final int x0;
        private final int z0;
        private final int n;
        private final int[] h;

        private HeightGrid(int step, int x0, int z0, int n, int[] h) {
            this.step = step;
            this.x0 = x0;
            this.z0 = z0;
            this.n = n;
            this.h = h;
        }

        static HeightGrid sample(ChunkGenerator generator, RandomState randomState,
                                 ChunkAccess chunk, int step) {
            LevelHeightAccessor levels = chunk;
            int x0 = Math.floorDiv(chunk.getPos().getMinBlockX(), step) * step;
            int z0 = Math.floorDiv(chunk.getPos().getMinBlockZ(), step) * step;
            int n = 16 / step + 2;
            int[] h = new int[n * n];
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    h[j * n + i] = generator.getBaseHeight(
                            x0 + i * step, z0 + j * step,
                            Heightmap.Types.WORLD_SURFACE_WG, levels, randomState);
                }
            }
            return new HeightGrid(step, x0, z0, n, h);
        }

        double at(int wx, int wz) {
            double fx = (wx - x0) / (double) step;
            double fz = (wz - z0) / (double) step;
            int i = clamp((int) Math.floor(fx), 0, n - 2);
            int j = clamp((int) Math.floor(fz), 0, n - 2);
            double tx = Math.min(1, Math.max(0, fx - i));
            double tz = Math.min(1, Math.max(0, fz - j));
            double a = h[j * n + i];
            double b = h[j * n + i + 1];
            double c = h[(j + 1) * n + i];
            double d = h[(j + 1) * n + i + 1];
            return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz;
        }

        private static int clamp(int v, int lo, int hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }
    }

    /**
     * 一块地的统一标高：矩形四角 + 中心共五点取样，取中位数。
     *
     * <p>为什么用中位数而不是最小值：最小值会被一个掉进峡谷的角拖着整块地往下挖，
     * 挖出十几格深的坑；中位数能忽略单点异常，又确实比最高点低——坡地被削平的效果就出来了。
     */
    private static int parcelBase(ChunkGenerator generator, RandomState randomState, ChunkAccess chunk,
                                  int rx, int rz, int rw, int rh) {
        int[] ys = {
                base(generator, randomState, chunk, rx, rz),
                base(generator, randomState, chunk, rx + rw - 1, rz),
                base(generator, randomState, chunk, rx, rz + rh - 1),
                base(generator, randomState, chunk, rx + rw - 1, rz + rh - 1),
                base(generator, randomState, chunk, rx + rw / 2, rz + rh / 2),
        };
        java.util.Arrays.sort(ys);
        return ys[2] - 1;
    }

    /** 地块内高低差超过这个值就判定为陡坡，不建（避免挖天坑）。 */
    private static final int MAX_PARCEL_RELIEF = 8;
    /** 标记：这块地太陡，不建。 */
    private static final int NO_PARCEL = Integer.MIN_VALUE;

    private static int base(ChunkGenerator generator, RandomState randomState, ChunkAccess chunk,
                            int wx, int wz) {
        return generator.getBaseHeight(wx, wz, Heightmap.Types.WORLD_SURFACE_WG, chunk, randomState);
    }

    /**
     * 跨水桥面。
     *
     * <p>关键是**不能把水填掉**：只在海平面之上铺一层桥面，水体原样保留，
     * 再每隔 8 格往水下打一根桥墩。这样河流、湖泊、海湾都不会被路截断。
     *
     * <p>海底高度用 {@code OCEAN_FLOOR_WG} 高度图拿——它会忽略水，直接给出水下的地面。
     */
    private static int bridge(ChunkAccess chunk, BlockPos.MutableBlockPos cursor,
                              CityPlan.ChunkSlice slice, int index,
                              int x, int z, int wx, int wz, int surfaceY,
                              boolean major, int minY, int topLimit) {
        int deckY = Math.min(surfaceY + 1, topLimit);
        if (deckY <= minY) {
            return 0;
        }
        byte mark = slice.mark[index];
        BlockState deck;
        if (mark == 3) {
            deck = ROAD_MAJOR;
        } else if (mark == 1) {
            deck = Math.floorMod(wx, 2) == 0 ? CROSSWALK : ROAD_LOCAL;
        } else if (mark == 2) {
            deck = Math.floorMod(wz, 2) == 0 ? CROSSWALK : ROAD_LOCAL;
        } else {
            deck = major ? ROAD_MAJOR : ROAD_LOCAL;
        }
        chunk.setBlockState(cursor.set(wx, deckY, wz), deck, 0);
        int written = 1;

        // 栏杆：桥面两侧的路缘立一格，免得走上去看不出是桥
        boolean edgeRoad = (x > 0 && slice.grade[z * 16 + x - 1] == 0)
                || (x < 15 && slice.grade[z * 16 + x + 1] == 0)
                || (z > 0 && slice.grade[(z - 1) * 16 + x] == 0)
                || (z < 15 && slice.grade[(z + 1) * 16 + x] == 0);
        if (edgeRoad && deckY + 1 <= topLimit) {
            chunk.setBlockState(cursor.set(wx, deckY + 1, wz), RETAINING, 0);
            written++;
        }

        // 桥墩：每 8 格一根，从水下地面一直打到桥面
        if (Math.floorMod(wx + wz, 8) == 0) {
            int floorY = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG)
                    .getHighestTaken(x, z);
            for (int y = Math.max(minY + 1, floorY); y < deckY; y++) {
                chunk.setBlockState(cursor.set(wx, y, wz), RETAINING, 0);
                written++;
            }
        }
        return written;
    }

    /** 地表高度：优先读高度图，读不到就从顶部往下扫第一个非空气方块。 */
    private static int surfaceOf(ChunkAccess chunk, Heightmap heightmap, BlockPos.MutableBlockPos cursor,
                                 int x, int z, int wx, int wz, int minY, int maxY) {
        int surfaceY = heightmap.getHighestTaken(x, z);
        if (surfaceY >= minY) {
            return surfaceY;
        }
        for (int y = maxY; y >= minY; y--) {
            if (!chunk.getBlockState(cursor.set(wx, y, wz)).isAir()) {
                return y;
            }
        }
        return minY - 1;
    }

    // ------------------------------------------------------------------
    // 建筑
    // ------------------------------------------------------------------

    private static int building(ChunkAccess chunk, BlockPos.MutableBlockPos cursor,
                                CityPlan.ChunkSlice slice, int index,
                                int wx, int wz, int groundY, int height, int topLimit) {
        int style = Math.floorMod(slice.bldStyle[index], STYLES);
        int floorHeight = Math.max(2, slice.bldFloor[index]);
        int kind = slice.bldKind[index];
        int inset = slice.bldInset[index];
        int setback = slice.bldSetback[index];
        int tier = Math.max(0, inset - setback);

        // 退台：只在**顶部**收分，主体直上。
        // 反过来的写法（每 8 层收一格从地面就开始收）会把塔做成台阶金字塔。
        boolean stepped = kind == citygen.Parcels.KIND_TOWER || kind == citygen.Parcels.KIND_LANDMARK;
        int crownFloors = kind == citygen.Parcels.KIND_LANDMARK ? 6 : 4;
        int heightLimit = height;
        if (stepped) {
            int floorsTotal = Math.max(1, height / floorHeight);
            int taperFrom = Math.max(1, floorsTotal - crownFloors);
            heightLimit = Math.min(height, (taperFrom + tier) * floorHeight);
        }
        // 联排：沿长边每 6 格一个单元，屋顶高度按单元抖动，形成锯齿天际线
        if (kind == citygen.Parcels.KIND_ROW) {
            int along = slice.bldAlongX[index] ? wx : wz;
            int jitter = Math.floorMod(mix(Math.floorDiv(along, 6)), 3) - 1;
            heightLimit = Math.max(floorHeight + 1, heightLimit + jitter * floorHeight);
        }

        int top = Math.min(groundY + heightLimit, topLimit);
        int floors = Math.max(1, (top - groundY) / floorHeight);

        // 借鉴 Lost Cities 的「楼层 part」思路，但用剖面实现：
        // 外墙做成 2 格厚（inset <= setback+1），于是墙有体积、窗能凹进去，
        // 不再是一张纸片。首两层是挑高大堂（整面玻璃、无隔墙）。
        // ---- 立面造型 ----
        // 借鉴 Lost Cities 的 part/palette：模板表达「立面长什么样」，
        // 但我们不做字符网格（每个地块尺寸都要一套模板，等于推倒重来），
        // 而是把立面拆成四种角色，逐列判定。角色决定「外层放什么、内层放什么」：
        //   实墙   outer=墙 inner=腰线     → 凸出的实体墙
        //   凹窗   outer=空 inner=玻璃     → 窗洞退进一格，有深度
        //   凹槽   outer=空 inner=玻璃     → 塔楼的竖向深槽
        //   转角柱 outer=墙 inner=墙       → 角部做实，轮廓立得住
        boolean tower = kind == citygen.Parcels.KIND_TOWER || kind == citygen.Parcels.KIND_LANDMARK;
        byte face = slice.bldFace[index];
        int corner = slice.bldCorner[index];
        // 有南北面就说明这面墙沿 x 延伸，开间要按 wx 排；否则按 wz
        boolean alongX = (face & 3) != 0;
        int along = alongX ? wx : wz;

        int role; // 0 实墙 1 凹窗 2 转角柱 3 凹槽
        if (corner <= 1) {
            role = 2;
        } else if (tower) {
            role = Math.floorMod(along, 9) == 0 ? 3 : 1;
        } else {
            int bay = Math.floorMod(along, 5);
            role = bay == 0 || bay == 2 ? 1 : (bay == 1 ? 2 : 0);
        }

        // 内层是否存在：窗和凹槽的玻璃就在内层，其余列内层只放腰线
        boolean openOuter = role == 1 || role == 3;
        int written = 0;

        if (inset <= setback + 1) {
            boolean outerFace = inset <= setback;
            for (int y = groundY + 1; y <= top; y++) {
                int rel = y - groundY - 1;
                int fl = rel / floorHeight;
                int fy = rel % floorHeight;
                boolean lobby = fl < 2;
                // 住宅每 6 层来一层内凹阳台：整圈退进，层底挑出一圈楼板
                boolean balconyFloor = !tower && fl > 0 && fl % 6 == 5;

                BlockState state;
                if (y == top) {
                    state = (kind == citygen.Parcels.KIND_LANDMARK) ? LAMP : ROOF[style];
                } else if (outerFace) {
                    // 外层
                    if (balconyFloor && fy == 0) {
                        state = SLAB;                     // 阳台地面
                    } else if (balconyFloor || lobby || openOuter) {
                        state = AIR;                      // 退进去 / 窗洞 / 大堂
                    } else if (fy == 0) {
                        state = WALL[style];              // 层间腰线
                    } else if (fy == floorHeight - 1) {
                        state = WALL[style];              // 窗上过梁
                    } else {
                        state = WALL[style];
                    }
                } else {
                    // 内层：窗玻璃在这里，实墙列只保留腰线
                    if (openOuter || lobby || balconyFloor) {
                        state = GLAZING[style];
                    } else if (fy == 0 || fy == floorHeight - 1) {
                        state = WALL[style];
                    } else {
                        state = AIR;
                    }
                }
                chunk.setBlockState(cursor.set(wx, y, wz), state, 0);
                written++;
            }
            if (top + 1 <= topLimit && outerFace) {
                chunk.setBlockState(cursor.set(wx, top + 1, wz), WALL[style], 0);
                written++;
            }
            // 门洞：底层外墙每 29 格开一个 3 格宽、2 格高的口子
            if (outerFace && Math.floorMod(wx + wz * 3, 29) < 3) {
                if (groundY + 2 <= top) {
                    chunk.setBlockState(cursor.set(wx, groundY + 1, wz), AIR, 0);
                    chunk.setBlockState(cursor.set(wx, groundY + 2, wz), AIR, 0);
                    written += 2;
                }
            }
        } else {
            // 内部：每层一块楼板，顶部是屋顶；另做梯子井、井壁、房间隔墙
            boolean well = Math.floorMod(wx, 9) == 1 && Math.floorMod(wz, 9) == 1;
            boolean wellWall = Math.floorMod(wx, 9) == 1 && Math.floorMod(wz, 9) == 2;
            boolean partition = !well && !wellWall
                    && Math.floorMod(wz, 8) == 0 && Math.floorMod(wx, 7) != 0
                    && Math.floorMod(wx, 9) >= 3;
            boolean lobby = top - groundY > floorHeight * 2;

            if (well || wellWall) {
                if (wellWall) {
                    for (int y = groundY + 1; y < top; y++) {
                        chunk.setBlockState(cursor.set(wx, y, wz), WALL[style], 0);
                        written++;
                    }
                } else {
                    for (int y = groundY + 1; y < top; y++) {
                        chunk.setBlockState(cursor.set(wx, y, wz), LADDER, 0);
                        written++;
                    }
                }
            } else {
                for (int k = 1; k < floors; k++) {
                    int y = groundY + k * floorHeight;
                    if (y > top) {
                        break;
                    }
                    // 每 6 格嵌一盏灯，室内不再漆黑一片
                    boolean lit = Math.floorMod(wx, 6) == 0 && Math.floorMod(wz, 6) == 0;
                    chunk.setBlockState(cursor.set(wx, y, wz), lit ? LAMP : SLAB, 0);
                    written++;
                    // 箱子：稀疏哈希决定，一栋楼大概一两个，高层和 CBD 出贵重物品
                    if (Math.floorMod(wx * 71 + wz * 37 + k * 101, 121) == 0) {
                        int above = y + 1;
                        if (above < top) {
                            BlockPos chestPos = new BlockPos(wx, above, wz);
                            chunk.setBlockState(cursor.set(wx, above, wz), CHEST, 0);
                            stockChest(chunk, chestPos, wx, wz, k, tower);
                            written++;
                        }
                    }
                }
                // 大堂两层不砌隔墙，进去是一整个通透空间
                if (partition) {
                    for (int y = groundY + 1; y < top; y++) {
                        if ((y - groundY - 1) % floorHeight == 0) {
                            continue;
                        }
                        if (lobby && y < groundY + floorHeight * 2) {
                            continue;
                        }
                        chunk.setBlockState(cursor.set(wx, y, wz), WALL[style], 0);
                        written++;
                    }
                }
            }
            chunk.setBlockState(cursor.set(wx, top, wz), ROOF[style], 0);
            written++;
        }
        return written;
    }

    /** 地铁埋深（绝对高度）。取定值是为了让轨道全程水平、天然连通，不用处理坡道。 */
    private static final int METRO_Y = 40;
    /** 地铁净空高度。 */
    private static final int METRO_CLEAR = 3;
    /** 站厅净空高度。 */
    private static final int METRO_HALL_CLEAR = 4;
    /** 低层线路（主干线、环线）埋深，与高速线错开一层，平面相交也不会抢轨道。 */
    private static final int METRO_Y_LOW = 36;

    /**
     * 地铁：高速公路正下方挖一条三格宽的隧道。
     *
     * <p>为什么这件事在我们的架构里反而好办：地铁沿高速走，而高速是**世界坐标的纯函数**，
     * 所以地铁线也天然跨块连续——不需要 Lost Cities 那种「跨区块连通性」的全局数据结构。
     * 代价是只能做直线，不能自己拐弯。
     *
     * <p>轨道高度取绝对常量 {@link #METRO_Y}，全程水平，于是铁轨在任何地方都能连上；
     * 地面离得太近（不足 8 格）就不挖，宁可这一段没有地铁。
     */
    private static int carveMetro(ChunkAccess chunk, BlockPos.MutableBlockPos cursor,
                                  byte metro, int wx, int wz, int groundY) {
        boolean low = (metro & Roads.METRO_LOW) != 0;
        int base = low ? METRO_Y_LOW : METRO_Y;
        if (groundY - base < 8) {
            return 0;
        }
        boolean alongX = (metro & Roads.METRO_ALONG_X) != 0;
        boolean rail = (metro & Roads.METRO_RAIL) != 0;
        boolean hall = (metro & Roads.METRO_HALL) != 0;
        boolean station = (metro & Roads.METRO_STATION) != 0;
        int along = alongX ? wx : wz;
        int ceil = base + (hall ? METRO_HALL_CLEAR : METRO_CLEAR);
        int written = 0;

        for (int y = base; y < ceil; y++) {
            chunk.setBlockState(cursor.set(wx, y, wz), AIR, 0);
            written++;
        }
        chunk.setBlockState(cursor.set(wx, base - 1, wz), FOUNDATION, 0);
        written++;

        boolean transfer = (metro & Roads.METRO_TRANSFER) != 0;
        // 站厅：柱网撑住上面的路面。
        // 两个必要条件：让开换乘竖井；让开中央轨道那一列，
        // 否则每隔几格就是一道整排柱子，正中间的通道被彻底堵死。
        if (hall && Math.floorMod(along, 9) == 0 && !transfer && !rail) {
            for (int y = base; y < ceil; y++) {
                chunk.setBlockState(cursor.set(wx, y, wz), WALL[0], 0);
                written++;
            }
            return written;
        }
        if (rail) {
            chunk.setBlockState(cursor.set(wx, base, wz), alongX ? RAIL_X : RAIL_Z, 0);
        }
        if (Math.floorMod(along, 12) == 0) {
            chunk.setBlockState(cursor.set(wx, ceil - 1, wz), LAMP, 0);
        }
        // 刷怪笼：只出现在站厅地面，稀疏分布，避开轨道
        if (hall && !rail && Math.floorMod(wx * 37 + wz * 91, 397) == 0) {
            placeSpawner(chunk, new BlockPos(wx, base, wz), wx * 131 + wz);
            written++;
        }
        // 换乘竖井：把高速线站厅和下面的主干线隧道打通
        if (transfer && !rail) {
            for (int y = METRO_Y_LOW; y < base; y++) {
                chunk.setBlockState(cursor.set(wx, y, wz), AIR, 0);
                written++;
            }
            for (int y = METRO_Y_LOW; y < ceil; y++) {
                chunk.setBlockState(cursor.set(wx, y, wz), SCAFFOLD, 0);
                written++;
            }
        }

        boolean hut = (metro & Roads.METRO_HUT) != 0;
        // 竖井只开在洞口（hut 那 3×5 一小块）。
        // 之前写成整个 station 范围，结果整段 28 格长的路面全被挖穿。
        if (hut) {
            for (int y = ceil; y < groundY; y++) {
                boolean rung = alongX
                        ? Math.floorMod(wz, 2) == 0
                        : Math.floorMod(wx, 2) == 0;
                chunk.setBlockState(cursor.set(wx, y, wz), rung ? LADDER : AIR, 0);
                written++;
            }
            chunk.setBlockState(cursor.set(wx, groundY, wz), AIR, 0);
            written++;
            // 站亭：洞口之外盖个棚子，柱子撑起来，中间留空给梯子
            if (!rail) {
                if (Math.floorMod(wx + wz, 2) == 0) {
                    for (int y = groundY + 1; y <= groundY + 3; y++) {
                        chunk.setBlockState(cursor.set(wx, y, wz), WALL[0], 0);
                        written++;
                    }
                }
                chunk.setBlockState(cursor.set(wx, groundY + 4, wz), ROOF[0], 0);
                written++;
            }
        }
        return written;
    }

    /**
     * 立面细节：处理建筑外墙**外面那一圈**（inset == setback-1），做出体积。
     *
     * <p>为什么这一圈能安全地写方块：它在退让带里，本来是空地/人行道，
     * 往外挑一格不会撞到邻居（地块之间至少隔着 1 格退让 + 1 格路）。
     *
     * <p>三种细节叠加：
     * <ul>
     *   <li><b>凸腰线</b>：每层楼板高度往外挑一圈板，楼身有了水平分层；</li>
     *   <li><b>飘窗</b>：开间中间两格整面玻璃，形成凸出的窗体；</li>
     *   <li><b>遮阳板</b>：飘窗顶上一块板，就是最简单可靠的百叶；</li>
     *   <li><b>内凹阳台</b>：住宅每 6 层整层退进，挑出楼板 + 栏杆。</li>
     * </ul>
     */
    private static int facadeDetail(ChunkAccess chunk, BlockPos.MutableBlockPos cursor,
                                    CityPlan.ChunkSlice slice, int index,
                                    int wx, int wz, int groundY, int topLimit) {
        int height = slice.bldHeight[index];
        if (height <= 0) {
            return 0;
        }
        int inset = slice.bldInset[index];
        int setback = slice.bldSetback[index];
        if (inset != setback - 1) {
            return 0;
        }
        int floorHeight = Math.max(2, slice.bldFloor[index]);
        int style = Math.floorMod(slice.bldStyle[index], STYLES);
        int kind = slice.bldKind[index];
        boolean tower = kind == citygen.Parcels.KIND_TOWER || kind == citygen.Parcels.KIND_LANDMARK;
        // 这列在墙外一格，沿墙坐标与它背后的墙列相同，所以开间能对齐
        byte face = slice.bldFace[index];
        int along = ((face & 3) != 0) ? wx : wz;
        int bay = Math.floorMod(along, 5);
        boolean window = !tower && (bay == 0 || bay == 2);

        int top = Math.min(groundY + height, topLimit);
        int written = 0;
        for (int y = groundY + 1; y < top; y++) {
            int rel = y - groundY - 1;
            int fl = rel / floorHeight;
            int fy = rel % floorHeight;
            boolean balconyFloor = !tower && fl > 0 && fl % 6 == 5;

            if (balconyFloor) {
                if (fy == 0 || fy == 1) {
                    // 阳台地面 + 栏杆（不封顶，人站在阳台上能看出去）
                    chunk.setBlockState(cursor.set(wx, y, wz),
                            fy == 0 ? SLAB : WALL[style], 0);
                    written++;
                }
            } else if (fy == 0 && fl % 3 == 0) {
                // 凸腰线：**每 3 层**一道。原来每层都挑，
                // 楼一高就变成一圈圈叠上去的千层饼，正是截图里那个样子
                chunk.setBlockState(cursor.set(wx, y, wz), SLAB, 0);
                written++;
            } else if (window && (fy == 1 || fy == 2)) {
                // 飘窗玻璃，凸出一格
                chunk.setBlockState(cursor.set(wx, y, wz), GLAZING[style], 0);
                written++;
            } else if (window && fy == floorHeight - 1) {
                // 遮阳板（百叶）：飘窗顶上挑一块
                chunk.setBlockState(cursor.set(wx, y, wz), SLAB, 0);
                written++;
            }
        }
        return written;
    }

    /**
     * 给箱子塞东西。
     *
     * <p>为什么不挂战利品表：战利品表是数据包资源，而 Fabric 下模组自带的 {@code data/}
     * 需要 Fabric API 的资源加载模块才会被注册（这个坑在接缝那节踩过）。
     * 直接写 ItemStack 反而简单可靠，代价是内容写死在代码里。
     */
    private static boolean stockChest(ChunkAccess chunk, BlockPos pos, int wx, int wz, int floor, boolean tower) {
        try {
            ChestBlockEntity chest = new ChestBlockEntity(pos, CHEST);
            int hash = wx * 131 + wz * 71 + floor * 17;
            boolean rich = tower || floor >= 8;
            int slots = 2 + Math.floorMod(hash, 3);
            for (int i = 0; i < slots; i++) {
                ItemStack stack = loot(hash, i, rich);
                if (stack != null) {
                    chest.setItem(Math.floorMod(hash * 31 + i * 7, 27), stack);
                }
            }
            chunk.setBlockEntity(chest);
            CHESTS.incrementAndGet();
            return true;
        } catch (RuntimeException ignored) {
            // 塞不进去就当空箱子，不影响地形
            return false;
        }
    }

    private static ItemStack loot(int hash, int slot, boolean rich) {
        int h = Math.floorMod(hash * 977 + slot * 131, 100);
        if (rich) {
            if (h < 12) {
                return new ItemStack(Items.EMERALD, 1 + h % 3);
            }
            if (h < 30) {
                return new ItemStack(Items.GOLD_INGOT, 1 + h % 2);
            }
            if (h < 62) {
                return new ItemStack(Items.IRON_INGOT, 2 + h % 4);
            }
            if (h < 78) {
                return new ItemStack(Items.LAPIS_LAZULI, 2 + h % 6);
            }
            return new ItemStack(Items.BREAD, 2 + h % 4);
        }
        if (h < 24) {
            return new ItemStack(Items.BREAD, 1 + h % 3);
        }
        if (h < 48) {
            return new ItemStack(Items.COAL, 1 + h % 6);
        }
        if (h < 66) {
            return new ItemStack(Items.STICK, 2 + h % 6);
        }
        if (h < 84) {
            return new ItemStack(Items.IRON_NUGGET, 2 + h % 8);
        }
        return null;
    }

    /** 地下刷怪笼：只放在地铁站厅里，刷什么由位置哈希决定。 */
    private static void placeSpawner(ChunkAccess chunk, BlockPos pos, int hash) {
        try {
            chunk.setBlockState(pos, SPAWNER, 0);
            SpawnerBlockEntity spawner = new SpawnerBlockEntity(pos, SPAWNER);
            int pick = Math.floorMod(hash, 3);
            spawner.setEntityId(switch (pick) {
                case 0 -> EntityTypes.ZOMBIE;
                case 1 -> EntityTypes.SKELETON;
                default -> EntityTypes.SPIDER;
            }, net.minecraft.util.RandomSource.create(hash));
            chunk.setBlockEntity(spawner);
        } catch (RuntimeException ignored) {
            // 设不了就当普通方块，不影响地形
        }
    }

    /**
     * HowToGo 路牌。主路口立「功能导航」牌（CBD 方向 + 距离 + 街名），
     * 次路口立街名牌。文字用发光字，夜里也能看清。
     *
     * <p>牌子的朝向由「城市中心在哪个方向」决定：指向 CBD，
     * 玩家顺着牌子走就能到市中心——这就是导航的全部语义。
     *
     * <p>文字用 {@link SignText}，正反两面写同样的内容；
     * 告示牌方块本体是 {@code OAK_SIGN}，旋转值 0/4/8/12 对应南/西/北/东。
     */
    private static int streetSign(ChunkAccess chunk, BlockPos.MutableBlockPos cursor,
                                  CityPlan.ChunkSlice slice, int index, CityPlan plan,
                                  int x, int z, int wx, int wz, int groundY, int topLimit) {
        // 主路口（十字）才立功能导航牌，其余地方立街名牌，密度都靠取模控制
        boolean mainCross = slice.mark[index] == 3;
        boolean major = slice.grade[index] == Roads.HIGHWAY || slice.grade[index] == Roads.TRUNK;
        int step = mainCross ? 21 : 47;
        if (Math.floorMod(wx + wz * 3, step) != 0) {
            return 0;
        }
        if (groundY + 2 > topLimit) {
            return 0;
        }

        int rot = signRotation(plan, wx, wz);
        String street = major ? CityNames.avenueName(wx, wz) : CityNames.streetName(wx, wz);
        String l1;
        String l2;
        // 第一行固定是 HowToGo 品牌名——导航系统要有辨识度
        if (mainCross) {
            long dist = Math.round(cbdDistance(plan, wx, wz));
            l1 = "HowToGo";
            l2 = "→ CBD " + dist + "m · " + street;
        } else {
            l1 = "HowToGo";
            l2 = street + (major ? " · 主干道" : "");
        }

        BlockState sign = SIGN_BY_ROT[rot];
        BlockPos pos = new BlockPos(wx, groundY + 1, wz);
        chunk.setBlockState(cursor.set(wx, groundY + 1, wz), sign, 0);
        try {
            SignBlockEntity be = new SignBlockEntity(pos, sign);
            java.util.List<Component> lines = java.util.List.of(
                    Component.literal(l1),
                    Component.literal(l2),
                    Component.literal(""),
                    Component.literal(""));
            be.setText(new SignText(lines, lines, DyeColor.BLACK, true), SignTextSlot.FRONT);
            be.setText(new SignText(lines, lines, DyeColor.BLACK, true), SignTextSlot.BACK);
            chunk.setBlockEntity(be);
        } catch (RuntimeException ignored) {
            // 文字写不上就当空牌子
        }
        return 1;
    }

    /** 到最近 CBD 中心的距离（格）。 */
    private static double cbdDistance(CityPlan plan, int wx, int wz) {
        Field.City c = plan.field.nearestCity(wx - plan.originX, wz - plan.originZ);
        return c == null ? 0 : c.dist;
    }

    /** 牌子朝向：指向城市中心。MC 立牌旋转 0=南(+z) 4=西(-x) 8=北(-z) 12=东(+x)。 */
    private static int signRotation(CityPlan plan, int wx, int wz) {
        Field.City c = plan.field.nearestCity(wx - plan.originX, wz - plan.originZ);
        if (c == null) {
            return 0;
        }
        double dx = c.x - wx;
        double dz = c.z - wz;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? 12 : 4;
        }
        return dz > 0 ? 0 : 8;
    }

    private static int lamp(ChunkAccess chunk, BlockPos.MutableBlockPos cursor,
                            CityPlan.ChunkSlice slice, int x, int z,
                            int wx, int wz, int groundY, int topLimit) {
        if (Math.floorMod(wx + wz, 13) != 0 || !touchesRoad(slice, x, z)) {
            return 0;
        }
        if (groundY + 5 > topLimit) {
            return 0;
        }
        for (int y = groundY + 1; y <= groundY + 4; y++) {
            chunk.setBlockState(cursor.set(wx, y, wz), PAVEMENT, 0);
        }
        chunk.setBlockState(cursor.set(wx, groundY + 5, wz), LAMP, 0);
        return 5;
    }

    private static boolean touchesRoad(CityPlan.ChunkSlice slice, int x, int z) {
        return (x > 0 && slice.grade[z * 16 + x - 1] != 0)
                || (x < 15 && slice.grade[z * 16 + x + 1] != 0)
                || (z > 0 && slice.grade[(z - 1) * 16 + x] != 0)
                || (z < 15 && slice.grade[(z + 1) * 16 + x] != 0);
    }

    /** 小整数散列，给联排单元抖动屋顶用。 */
    private static int mix(int v) {
        int h = v * 0x9E3779B9;
        h ^= h >>> 15;
        return h;
    }

    /** 累计装好的箱子数，只用于日志。 */
    private static final java.util.concurrent.atomic.AtomicLong CHESTS =
            new java.util.concurrent.atomic.AtomicLong();

    private static final java.util.concurrent.atomic.AtomicLong SEEN =
            new java.util.concurrent.atomic.AtomicLong();

    private static void report(ChunkPos pos, int built, int blocks, int chests, int tunnels, int maxCut, int lost) {
        long n = SEEN.incrementAndGet();
        if (lost > 0 || n % 64 == 0) {
            System.out.println("[CityGen] chunk(" + pos.x() + "," + pos.z() + ") 建筑列 " + built
                    + " / 方块 " + blocks + " / 箱子 " + chests + " / 隧道列 " + tunnels + " / 最大削深 " + maxCut
                    + " / 读回失败 " + lost);
        }
    }
}
