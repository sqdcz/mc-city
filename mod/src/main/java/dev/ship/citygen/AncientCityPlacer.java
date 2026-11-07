package dev.ship.citygen;

import citygen.CityPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地下远古城市放置器：主干线地铁隧道沿线，玩家走到附近时
 * 把原版 city_center 结构模板放进地下（y = -8）。
 *
 * <p>为什么不放在生成阶段：placeInWorld 需要 ServerLevelAccessor，
 * 而我们的涂装发生在 ProtoChunk 阶段，拿不到。所以采用与市民相同的
 * tick 挂载——等区块变成真正的 LevelChunk 后再放置，原版战利品表
 * （箱子、潜声传感器、监守者机制）全部原样生效。
 *
 * <p>60 格见方的模板跨 4×4 个区块，放置前必须确认全部加载完成；
 * 没齐就先记着，每 tick 重试。每 tick 最多放置一个，防止卡顿。
 */
public final class AncientCityPlacer {

    private static final Set<Long> DONE = ConcurrentHashMap.newKeySet();
    /** 已放置的远古城市中心（规范化后）。防止相邻区块把同一座城连放几次。 */
    private static final Set<Long> PLACED = ConcurrentHashMap.newKeySet();
    private static final String[] TEMPLATES = {
            "ancient_city/city_center/city_center_1",
            "ancient_city/city_center/city_center_2",
            "ancient_city/city_center/city_center_3",
    };
    /** 放置基点。模板向上展开约 25 格，-8..+17 完全埋在地下，不会戳穿地表。 */
    private static final int CITY_Y = -8;
    /** 模板半径约 30 格 = 2 个区块，放置前确认中心 ±2 的 5×5 区块全部加载。 */
    private static final int SPAN_CHUNKS = 2;
    /** 扫描半径（区块）。必须 ≥ 3，否则站在放置点正旁边的玩家也扫不到它。 */
    private static final int SCAN_RADIUS_CHUNKS = 4;

    private AncientCityPlacer() {
    }

    public static void tick(ServerLevel level) {
        final int R = SCAN_RADIUS_CHUNKS;
        boolean scanned = false;
        for (ServerPlayer p : level.players()) {
            scanned = true;
            int pcx = p.chunkPosition().x();
            int pcz = p.chunkPosition().z();
            for (int dz = -R; dz <= R; dz++) {
                for (int dx = -R; dx <= R; dx++) {
                    if (tryChunk(level, pcx + dx, pcz + dz)) {
                        return;   // 每 tick 最多放一个
                    }
                }
            }
        }
        // 无头测试模式：没有玩家时以原点为中心扫描，供自动化验证
        if (!scanned && Boolean.getBoolean("citygen.testCitizens")) {
            for (int dz = -R; dz <= R; dz++) {
                for (int dx = -R; dx <= R; dx++) {
                    if (tryChunk(level, dx, dz)) {
                        return;
                    }
                }
            }
        }
    }

    private static boolean tryChunk(ServerLevel level, int cx, int cz) {
        // 26.3 的 ChunkPos 是 Record，没有 asLong 静态方法，自己编码
        long key = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
        if (DONE.contains(key)) {
            return false;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
            return false;
        }
        int mcx = Math.floorDiv(cx << 4, CityPlanManager.MEGACHUNK);
        int mcz = Math.floorDiv(cz << 4, CityPlanManager.MEGACHUNK);
        CityPlanManager mgr = CityPlanManager.forSeed(level.getSeed());
        CityPlan plan = mgr.plan(mcx, mcz);
        // 逐列判定：中心带只有 ±2.5 格宽，chunk 中心（16k+8）永远不会落进去，
        // 必须扫整块 256 列。判定是纯算术，成本可忽略。命中即记住放置点。
        int spotX = -1;
        int spotZ = -1;
        scan:
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                int wx = (cx << 4) + i;
                int wz = (cz << 4) + j;
                if (plan.isAncientCitySpot(wx, wz)) {
                    spotX = wx;
                    spotZ = wz;
                    break scan;
                }
            }
        }
        // 规范化到唯一中心；别的区块若已经放了这个中心，这里直接跳过
        long center = plan.ancientCityCenter(spotX, spotZ);
        int px = (int) (center >> 32);
        int pz = (int) (center & 0xFFFFFFFFL);
        if (!PLACED.add(center)) {
            DONE.add(key);
            return false;
        }
        // 中心可能不在当前 chunk：5×5 加载检查要围绕**中心**做
        int ccx = Math.floorDiv(px, 16);
        int ccz = Math.floorDiv(pz, 16);
        if (spotX < 0) {
            DONE.add(key);
            if (Math.floorMod(key, 40) == 0) {
                System.out.println("[CityGen] 远古城市扫描: chunk(" + cx + "," + cz + ") 无放置点");
            }
            return false;
        }
        System.out.println("[CityGen] 远古城市命中: (" + spotX + "," + spotZ + ") chunk(" + cx + "," + cz + ")");
        // 模板范围跨 5×5 区块，没加载完就等下一 tick（不记 DONE，也不退 PLACED）
        for (int zz = -SPAN_CHUNKS; zz <= SPAN_CHUNKS; zz++) {
            for (int xx = -SPAN_CHUNKS; xx <= SPAN_CHUNKS; xx++) {
                if (level.getChunkSource().getChunkNow(ccx + xx, ccz + zz) == null) {
                    return false;
                }
            }
        }
        DONE.add(key);
        place(level, px, pz);
        return true;
    }

    private static void place(ServerLevel level, int wx, int wz) {
        try {
            StructureTemplateManager tm = level.getServer().getStructureTemplateManager();
            int pick = Math.floorMod(wx * 7 + wz * 13, TEMPLATES.length);
            StructureTemplate template = tm.get(
                    Identifier.fromNamespaceAndPath("minecraft", TEMPLATES[pick])).orElse(null);
            if (template == null) {
                return;
            }
            Rotation[] rotations = Rotation.values();
            StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(rotations[Math.floorMod(wx + wz, rotations.length)])
                    .setIgnoreEntities(true);
            BlockPos origin = new BlockPos(wx, CITY_Y, wz);
            template.placeInWorld(level, origin, origin, settings,
                    RandomSource.create(wx * 31L + wz), 2);
            System.out.println("[CityGen] 远古城市已放置: " + TEMPLATES[pick] + " @ (" + wx + "," + CITY_Y + "," + wz + ")");
        } catch (RuntimeException e) {
            // 放不出来就当没有，不能让 tick 抛异常
            System.out.println("[CityGen] 远古城市放置失败 (" + wx + "," + wz + "): " + e);
        }
    }

    /** 世界卸载时清干净，与市民的做法一致。 */
    public static void reset() {
        DONE.clear();
    }
}
