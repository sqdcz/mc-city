package dev.ship.citygen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import citygen.CityPlan;
import citygen.Zone;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 市民生成：城市盖好了总得有人住。
 *
 * <p>为什么不在区块生成阶段直接放实体——{@code buildTerrain} 跑在生成线程上，
 * 那时区块还不是 {@code LevelChunk}，实体无处安放，也没法持久化。
 * 所以改在**区块已经进世界之后**补人：挂在 {@code ServerChunkCache.tick} 上，
 * 每秒扫一次玩家周围的已加载区块，给还没处理过的区块撒几个村民。
 *
 * <p>三条纪律，缺一条都会把存档或帧率搞坏：
 * <ol>
 *   <li><b>只放在城市铺装上</b>：检查脚下是不是混凝土/平滑石/石砖，
 *       野外一格不放，免得整片大陆都是无业游民；</li>
 *   <li><b>幂等</b>：放之前先查周围 6 格有没有村民，有就跳过。
 *       这样重进存档、服务器重启都不会越堆越多；</li>
 *   <li><b>限速</b>：每秒最多处理几十个区块、每区块最多 2 人，
 *       并且只处理玩家 96 格内的，远处不烧 CPU。</li>
 * </ol>
 */
public final class CityCitizens {

    /** 每 20 tick（约 1 秒）扫一次。 */
    private static final long INTERVAL_TICKS = 20;
    /** 扫描半径（区块）。6 区块 = 96 格，正好是大多数客户端的实体可见距离。 */
    private static final int RADIUS = 6;
    /** 每个区块最多几个市民。 */
    private static final int PER_CHUNK = 2;
    /** 一秒内最多处理多少个新区块，防止刚进游戏时瞬移式加载导致卡顿。 */
    private static final int PROCESS_PER_TICK = 6;

    /** 已处理过的区块，按 (cx, cz) 打包成 long。 */
    private static final Set<Long> DONE = ConcurrentHashMap.newKeySet();
    /** 玩家上次所在的区域名，变化时弹 HUD。 */
    private static final java.util.Map<java.util.UUID, String> LAST_ZONE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static long lastTick = Long.MIN_VALUE;

    /** 城市地面材质：只有踩在这些方块上才认为是「城里」。 */
    private static final BlockState[] CITY_GROUND = {
            Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.GRAY).defaultBlockState(),
            Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.BLACK).defaultBlockState(),
            Blocks.SMOOTH_STONE.defaultBlockState(),
            Blocks.STONE_BRICKS.defaultBlockState(),
    };

    private static final String[] PROFESSIONS = {
            "farmer", "librarian", "toolsmith", "butcher", "cleric", "armorer",
            "fisherman", "shepherd", "mason", "cartographer", "weaponsmith", "leatherworker",
            "fletcher",
    };

    /**
     * 开发用开关：没有玩家时也按原点为中心扫一圈。
     * 服务端无头测试没有玩家，否则没法验证这条链路。正式运行不会打开。
     */
    private static final boolean TEST_MODE = Boolean.getBoolean("citygen.testCitizens");

    private CityCitizens() {
    }

    /** 由 {@code ServerChunkCacheMixin} 在每个 tick 调用。 */
    public static void tick(ServerLevel level) {
        // 只处理主世界：下界末地没有城市，也不该突然冒村民
        if (level != level.getServer().overworld()) {
            return;
        }
        long now = level.getGameTime();
        if (lastTick != Long.MIN_VALUE && now - lastTick < INTERVAL_TICKS) {
            return;
        }
        lastTick = now;
        hudTick(level);
        AncientCityPlacer.tick(level);
        if (TEST_MODE) {
            System.out.println("[CityGen] citizens sweep: chunks=" + STAT_CHUNKS
                    + " notCity=" + STAT_NOT_CITY + " blocked=" + STAT_BLOCKED
                    + " dup=" + STAT_DUPLICATE + " spawned=" + STAT_SPAWNED);
        }

        List<ServerPlayer> players = level.players();
        if (players.isEmpty() && !TEST_MODE) {
            return;
        }
        // 探索久了 DONE 会很大；清空一次只是让已经去过的区块重新走一遍幂等检查，代价很低
        if (DONE.size() > 40_000) {
            DONE.clear();
        }

        var cache = level.getChunkSource();
        int budget = PROCESS_PER_TICK;
        if (players.isEmpty()) {
            // 测试模式：以原点为中心扫
            budget = sweepAround(level, cache, 0, 0, budget);
            return;
        }
        for (ServerPlayer player : players) {
            int pcx = player.getBlockX() >> 4;
            int pcz = player.getBlockZ() >> 4;
            budget = sweepAround(level, cache, pcx, pcz, budget);
            if (budget <= 0) {
                break;
            }
        }
    }

    /** 扫以 (pcx, pcz) 为中心的一片区块，返回剩余的预算。 */
    private static int sweepAround(ServerLevel level, ServerChunkCache cache, int pcx, int pcz, int budget) {
        for (int dz = -RADIUS; dz <= RADIUS && budget > 0; dz++) {
            for (int dx = -RADIUS; dx <= RADIUS && budget > 0; dx++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                long key = (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
                if (DONE.contains(key)) {
                    continue;
                }
                LevelChunk chunk = cache.getChunkNow(cx, cz);
                if (chunk == null) {
                    // 还没加载，下次再看（不推进 DONE）
                    continue;
                }
                DONE.add(key);
                budget--;
                populate(level, chunk);
            }
        }
        return budget;
    }

    /**
     * HowToGo 的 HUD 部分：玩家跨区时在屏幕上方弹一行「📍 您已进入 XX区」。
     *
     * <p>为什么要小心：查 zone 需要规划数据，而 {@code mgr.plan()} 在规划没算过时会
     * 阻塞主线程半秒。规避办法是先用 {@code getChunkNow} 确认区块已加载——
     * 已加载说明规划必然算过，plan() 只会命中缓存。
     */
    private static void hudTick(ServerLevel level) {
        var cache = level.getChunkSource();
        for (ServerPlayer p : level.players()) {
            int wx = p.getBlockX();
            int wz = p.getBlockZ();
            int mcx = Math.floorDiv(wx, CityPlanManager.MEGACHUNK);
            int mcz = Math.floorDiv(wz, CityPlanManager.MEGACHUNK);
            if (cache.getChunkNow(mcx, mcz) == null) {
                continue;
            }
            CityPlanManager mgr = CityPlanManager.forSeed(level.getSeed());
            CityPlan plan = mgr.plan(mcx, mcz);
            int lx = wx - plan.originX;
            int lz = wz - plan.originZ;
            if (lx < 0 || lz < 0 || lx >= plan.size || lz >= plan.size) {
                continue;
            }
            String name = zoneLabel(plan.zoning.zoneAt(lx, lz));
            String last = LAST_ZONE.get(p.getUUID());
            if (!name.equals(last)) {
                LAST_ZONE.put(p.getUUID(), name);
                if (last != null) {
                    p.sendOverlayMessage(Component.literal("🧭 HowToGo · " + name));
                }
            }
        }
    }

    private static String zoneLabel(Zone z) {
        return switch (z) {
            case CBD -> "CBD 核心商务区";
            case COMMERCIAL -> "商业街";
            case RES_HIGH -> "高密度住宅区";
            case RES_LOW -> "居住区";
            case INDUSTRIAL -> "工业区";
            case GREEN -> "城市绿带";
            case WATER -> "水岸";
        };
    }

    private static void populate(ServerLevel level, LevelChunk chunk) {
        RandomSource random = level.getRandom();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int wanted = random.nextInt(PER_CHUNK + 1);
        STAT_CHUNKS.incrementAndGet();
        for (int i = 0; i < wanted; i++) {
            int x = baseX + 2 + random.nextInt(12);
            int z = baseZ + 2 + random.nextInt(12);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= level.getMinY() || y >= level.getMaxY() - 2) {
                continue;
            }
            BlockPos feet = new BlockPos(x, y, z);
            if (!isCityGround(level.getBlockState(feet.below()))) {
                STAT_NOT_CITY.incrementAndGet();
                continue;
            }
            if (!level.getBlockState(feet).isAir() || !level.getBlockState(feet.above()).isAir()) {
                STAT_BLOCKED.incrementAndGet();
                continue;
            }
            // 幂等：周围六格已经有村民就不再加，重开存档也不会越堆越多
            if (!level.getEntitiesOfClass(Villager.class,
                    new net.minecraft.world.phys.AABB(x - 6, y - 6, z - 6, x + 7, y + 7, z + 7)).isEmpty()) {
                STAT_DUPLICATE.incrementAndGet();
                continue;
            }
            spawn(level, x, y, z, random);
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong STAT_CHUNKS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong STAT_NOT_CITY =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong STAT_BLOCKED =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong STAT_DUPLICATE =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong STAT_SPAWNED =
            new java.util.concurrent.atomic.AtomicLong();

    private static boolean isCityGround(BlockState state) {
        for (BlockState city : CITY_GROUND) {
            if (state == city) {
                return true;
            }
        }
        return false;
    }

    /** 一半的市民有名字，名牌在 10 格内可见——城市里的人不是匿名的。 */
    private static void giveName(Villager villager, RandomSource random) {
        if (random.nextInt(2) != 0) {
            return;
        }
        String job = switch (random.nextInt(6)) {
            case 0 -> "面馆";
            case 1 -> "杂货铺";
            case 2 -> "裁缝铺";
            case 3 -> "铁匠铺";
            case 4 -> "书报亭";
            default -> "花店";
        };
        String name = CityNames.citizenName(random.nextInt());
        villager.setCustomName(Component.literal(name + " 的" + job));
        villager.setCustomNameVisible(random.nextInt(3) == 0);
    }

    private static void spawn(ServerLevel level, int x, int y, int z, RandomSource random) {
        Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.STRUCTURE);
        if (villager == null) {
            return;
        }
        villager.snapTo(x + 0.5, y, z + 0.5);
        villager.setYRot(random.nextFloat() * 360.0f);
        assignProfession(level, villager, random);
        giveName(villager, random);
        if (level.addFreshEntity(villager)) {
            STAT_SPAWNED.incrementAndGet();
        }
    }

    /**
     * 随机给个职业。不设的话是「无业村民」，只会慢慢晃，看着还是很空。
     *
     * <p>整段包在 try 里：职业注册表万一因为数据包改动取不到，
     * 也宁可是个无业村民，不能让区块 tick 抛异常。
     */
    private static void assignProfession(ServerLevel level, Villager villager, RandomSource random) {
        try {
            Registry<VillagerProfession> registry =
                    level.registryAccess().lookupOrThrow(Registries.VILLAGER_PROFESSION);
            String name = PROFESSIONS[random.nextInt(PROFESSIONS.length)];
            Holder<VillagerProfession> holder =
                    registry.get(Identifier.fromNamespaceAndPath("minecraft", name)).orElse(null);
            if (holder == null) {
                return;
            }
            VillagerData data = villager.getVillagerData();
            villager.setVillagerData(data.withProfession(holder).withLevel(1));
        } catch (RuntimeException ignored) {
            // 保留默认（无业）村民
        }
    }

    /** 世界卸载时清干净，免得换存档还带着上一个世界的区块记录。 */
    public static void reset() {
        DONE.clear();
        AncientCityPlacer.reset();
        LAST_ZONE.clear();
        lastTick = Long.MIN_VALUE;
    }

    /** 兼容旧调用点，避免误用。 */
    static boolean isOverworld(Level level) {
        return level == level.getServer().overworld();
    }
}
