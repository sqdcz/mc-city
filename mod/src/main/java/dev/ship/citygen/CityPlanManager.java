package dev.ship.citygen;

import citygen.CityPlan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 跨区块规划的并发缓存。
 *
 * <p>要解决的矛盾：城市是有全局约束的（路网连通、地块不重叠），而 Minecraft 是
 * 逐区块、多线程、任意顺序生成的。折中办法是把地图切成 megachunk（默认 512×512 格
 * = 32×32 区块），每个 megachunk 一次性算完整张规划，之后所有区块都只做切片读取。
 *
 * <p>三条铁律：
 * <ol>
 *   <li>同一个 megachunk 只允许算一次 —— 用 {@link #pending} 的 computeIfAbsent 保证；</li>
 *   <li>规划结果发布后不可变 —— {@link CityPlan} 构造完就是只读的，此后任何线程只读不写；</li>
 *   <li>算规划不占用区块生成线程 —— 交给独立的后台线程池，生成线程只在 join 上等待。</li>
 * </ol>
 *
 * <p>内存：一个 512×512 的 CityPlan 约 7.5 MB（两层 double 距离场各 2 MB、两张 int 索引表各 1 MB、
 * 用地表 1 MB、道路 0.25 MB），因此缓存上限必须卡住，用 LRU 淘汰，超出后重算
 * （重算是确定性的，结果完全一致）。
 *
 * <p>注意 {@link #pending} 只用来合并并发请求，算完必须摘掉——否则它会把走过的每个
 * megachunk 永久持有，LRU 上限就白设了。
 */
public final class CityPlanManager {

    /** megachunk 边长（格）。 */
    public static final int MEGACHUNK = 512;

    /** 已完成规划的累计次数；外部地图数据源以此作为重读信号。 */
    public static long revision() {
        return REVISION.get();
    }

    /** 当前缓存里的所有规划（已完成的）。 */
    public static java.util.List<CityPlan> all() {
        java.util.List<CityPlan> out = new java.util.ArrayList<>();
        CityPlanManager inst = instance;
        if (inst != null) {
            // cache 的读写都以它自身为锁，这里保持一致；完成后的规划存在 cache，
            // pending 只承担「合并并发请求」的临时职责，算完就摘了
            synchronized (inst.cache) {
                out.addAll(inst.cache.values());
            }
        }
        return out;
    }
    /** 每个 megachunk 边长上有多少区块。 */
    public static final int CHUNKS_PER_SIDE = MEGACHUNK / 16;
    /** 同时保留几个 megachunk 的规划。单个约 7.5 MB，4 个约 30 MB，低配客户端也扛得住。 */
    private static final int MAX_CACHED = 4;

    private static volatile CityPlanManager instance;
    /** 每有一块新规划算完就 +1；HowToGo 数据源拿它当 version 通知重读。 */
    private static final java.util.concurrent.atomic.AtomicLong REVISION =
            new java.util.concurrent.atomic.AtomicLong();

    private final long seed;
    private final ExecutorService pool;
    private final Map<Long, CompletableFuture<CityPlan>> pending = new ConcurrentHashMap<>();
    private final Map<Long, CityPlan> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, CityPlan> eldest) {
            return size() > MAX_CACHED;
        }
    };

    private CityPlanManager(long seed) {
        this.seed = seed;
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "citygen-planner");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        };
        // 两个线程足够：单个 megachunk 约 0.4 s，重要的是别跟区块生成抢 CPU
        this.pool = Executors.newFixedThreadPool(2, factory);
    }

    public static void install(long seed) {
        CityPlanManager old = instance;
        if (old != null && old.seed == seed) {
            return;
        }
        if (old != null) {
            old.shutdown();
        }
        instance = new CityPlanManager(seed);
    }

    /**
     * 按世界种子取规划器。26.3 的 {@code WorldGenRegion.getSeed()} 在区块生成线程上直接可用，
     * 所以不需要监听服务端生命周期事件，也就不依赖 Fabric API 的事件模块。
     */
    public static CityPlanManager forSeed(long seed) {
        CityPlanManager current = instance;
        if (current != null && current.seed == seed) {
            return current;
        }
        synchronized (CityPlanManager.class) {
            current = instance;
            if (current != null && current.seed == seed) {
                return current;
            }
            if (current != null) {
                current.shutdown();
            }
            instance = new CityPlanManager(seed);
            return instance;
        }
    }

    public static void uninstall() {
        CityPlanManager old = instance;
        instance = null;
        if (old != null) {
            old.shutdown();
        }
    }

    /** 没有安装实例时返回 null，调用方自行降级为原版生成。 */
    public static CityPlanManager get() {
        return instance;
    }

    public static long key(int megachunkX, int megachunkZ) {
        return ((long) megachunkX << 32) ^ (megachunkZ & 0xFFFFFFFFL);
    }

    /** 世界坐标 → megachunk 坐标（向下取整，负数也正确）。 */
    public static int megachunkOf(int blockCoord) {
        return Math.floorDiv(blockCoord, MEGACHUNK);
    }

    /**
     * 取某个 megachunk 的规划，必要时同步等待计算完成。
     * 生成线程会阻塞在这里，但同一 megachunk 只会阻塞一次。
     */
    public CityPlan plan(int megachunkX, int megachunkZ) {
        long k = key(megachunkX, megachunkZ);
        synchronized (cache) {
            CityPlan hit = cache.get(k);
            if (hit != null) {
                return hit;
            }
        }
        CityPlan plan = computeOnce(k);
        synchronized (cache) {
            cache.put(k, plan);
        }
        return plan;
    }

    private CityPlan computeOnce(long k) {
        CompletableFuture<CityPlan> future =
                pending.computeIfAbsent(k, ignore -> CompletableFuture.supplyAsync(() -> compute(k), pool));
        try {
            return future.join();
        } catch (CompletionException e) {
            // 算失败就把占位删掉，下个区块还能重试，而不是永久卡在异常 future 上
            pending.remove(k, future);
            throw e;
        } finally {
            // 算成功后也要摘掉。pending 只负责「把并发的同一请求合并成一次计算」，
            // 结果由 cache 持有；不摘的话走过的每个 megachunk 都会被 pending 永久引用，
            // LRU 上限形同虚设，探索越远吃内存越多。
            if (future.isDone() && !future.isCompletedExceptionally()) {
                pending.remove(k, future);
            }
        }
    }

    private CityPlan compute(long k) {
        // 从 key 反解 megachunk 坐标：规划必须知道自己在世界里的位置，
        // 否则城市中心、路网弯曲这些全局函数会退化成「每块各自一套」
        int mcx = (int) (k >> 32);
        int mcz = (int) k;
        CityPlan plan = new CityPlan(seed, MEGACHUNK, mcx * MEGACHUNK, mcz * MEGACHUNK);
        REVISION.incrementAndGet();
        return plan;
    }

    /** 缓存里已有多少个 megachunk，调试用。 */
    public int cachedCount() {
        synchronized (cache) {
            return cache.size();
        }
    }

    public long seed() {
        return seed;
    }

    private void shutdown() {
        pool.shutdownNow();
        pending.clear();
        synchronized (cache) {
            cache.clear();
        }
    }
}
