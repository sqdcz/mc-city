package citygen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20261106L;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 512;
        int ox = args.length > 3 ? Integer.parseInt(args[2]) : 0;
        int oz = args.length > 3 ? Integer.parseInt(args[3]) : 0;

        long t0 = System.nanoTime();
        CityPlan plan = new CityPlan(seed, size, ox, oz);
        long t1 = System.nanoTime();

        Path out = Path.of("out");
        Files.createDirectories(out);
        Preview.write(plan, out.resolve("preview.png"), 3);
        int zoomHalf = Math.min(80, size / 4);
        Preview.writeRegion(plan, out.resolve("zoom_center.png"), 6,
                size / 2 - zoomHalf, size / 2 - zoomHalf, zoomHalf * 2, zoomHalf * 2, false);
        Files.writeString(out.resolve("cityplan.json"), plan.toJson());
        Preview.writeHeights(plan, out.resolve("buildings.png"), 3);
        long t2 = System.nanoTime();

        // 确定性校验用较小的 megachunk，避免为了验证再跑两遍全量规划
        int probeSize = 192;
        CityPlan again = new CityPlan(seed, probeSize, 0, 0);
        CityPlan other = new CityPlan(seed + 1, probeSize, 0, 0);
        boolean deterministic = Arrays.equals(again.zoning.zoneOf, new CityPlan(seed, probeSize, 0, 0).zoning.zoneOf);
        boolean seedSensitive = !Arrays.equals(again.zoning.zoneOf, other.zoning.zoneOf);

        int total = size * size;
        System.out.println("== CityPlan megachunk " + size + "x" + size + " (seed " + seed
                + ", origin " + ox + "," + oz + ") ==");
        System.out.printf("center at middle: %.3f%n", plan.field.center(size / 2, size / 2));
        System.out.println("sites: " + plan.zoning.sites.size());
        for (Map.Entry<Zone, Integer> e : plan.zoneTotals().entrySet()) {
            System.out.printf("  %-8s %7d  %5.1f%%%n", e.getKey().name(), e.getValue(),
                    100.0 * e.getValue() / total);
        }
        int roadCells = plan.roads.count();
        System.out.printf("roads: %d cells (%.1f%%), est. length %d blocks%n",
                roadCells, 100.0 * roadCells / total, roadCells / 5);
        int[] byGrade = plan.roads.countByGrade();
        String[] gradeName = {"-", "高速", "主干", "次干", "支路"};
        StringBuilder gsb = new StringBuilder("  grades:");
        for (int i = 1; i < byGrade.length; i++) {
            gsb.append(' ').append(gradeName[i]).append('=').append(byGrade[i]);
        }
        System.out.println(gsb);
        double avgArea = plan.parcels.list.stream().mapToInt(Parcels.Parcel::area).average().orElse(0);
        System.out.printf("parcels: %d, avg %.0f blocks^2, frontage %.1f%%%n",
                plan.parcels.list.size(), avgArea,
                100.0 * plan.parcels.frontageCount() / Math.max(1, plan.parcels.list.size()));

        int cpr = size / CityPlan.CHUNK;
        for (int i = 0; i < 4; i++) {
            int cx = (i == 0) ? 0 : (i == 1 ? cpr - 1 : (i == 2 ? cpr / 2 : cpr / 3));
            int cz = (i == 0) ? 0 : (i == 1 ? cpr - 1 : (i == 2 ? cpr / 2 : 2 * cpr / 3));
            CityPlan.ChunkSummary s = plan.chunk(cx, cz);
            System.out.printf("  chunk(%2d,%2d) road=%3d parcels=%3d avgFAR=%.2f%n",
                    cx, cz, s.roadCells, s.parcels, s.avgFar);
        }

        // 按用地统计地块覆盖率，判断哪一类用地没被铺满
        Map<Zone, int[]> byZone = new java.util.EnumMap<>(Zone.class);
        for (Parcels.Parcel p : plan.parcels.list) {
            int[] agg = byZone.computeIfAbsent(p.zone, k -> new int[2]);
            agg[0]++;
            agg[1] += p.area();
        }
        System.out.println("parcel coverage by zone:");
        Map<Zone, Integer> zoneTotals = plan.zoneTotals();
        for (Zone z : Zone.values()) {
            int[] agg = byZone.get(z);
            int zoneCells = zoneTotals.getOrDefault(z, 0);
            if (agg == null || zoneCells == 0) {
                System.out.printf("  %-8s n=  0  zone=%6d  (无地块)%n", z.name(), zoneCells);
                continue;
            }
            System.out.printf("  %-8s n=%3d area=%6d  zone=%6d  cover=%5.1f%%%n",
                    z.name(), agg[0], agg[1], zoneCells, 100.0 * agg[1] / zoneCells);
        }

        // 每类用地里有多少格被道路占用，用来判断「覆盖率低」是路太多还是切分失败
        Map<Zone, int[]> roadByZone = new java.util.EnumMap<>(Zone.class);
        for (int i = 0; i < size * size; i++) {
            int x = i % size;
            int z = i / size;
            if (plan.roads.at(x, z)) {
                Zone zz = plan.zoning.zoneAt(x, z);
                roadByZone.computeIfAbsent(zz, k -> new int[1])[0]++;
            }
        }
        System.out.println("road share by zone:");
        for (Zone z : Zone.values()) {
            int zoneCells = zoneTotals.getOrDefault(z, 0);
            int[] r = roadByZone.get(z);
            if (zoneCells == 0) {
                continue;
            }
            int rc = r == null ? 0 : r[0];
            System.out.printf("  %-8s road=%6d / %6d  = %5.1f%%%n",
                    z.name(), rc, zoneCells, 100.0 * rc / zoneCells);
        }

        // 建筑层：层数分布、外墙/内部列数、大概要写多少个方块
        int builtParcels = 0;
        int emptyParcels = 0;
        int maxHeight = 0;
        long wallColumns = 0;
        long innerColumns = 0;
        long wallBlocks = 0;
        long slabBlocks = 0;
        Map<Zone, int[]> floorsByZone = new java.util.EnumMap<>(Zone.class);
        for (Parcels.Parcel p : plan.parcels.list) {
            if (p.height <= 0) {
                emptyParcels++;
                continue;
            }
            builtParcels++;
            maxHeight = Math.max(maxHeight, p.height);
            int[] agg = floorsByZone.computeIfAbsent(p.zone, k -> new int[2]);
            agg[0]++;
            agg[1] += p.floors;

            int nx = p.w - p.setback * 2;
            int nz = p.h - p.setback * 2;
            if (nx < 3 || nz < 3) {
                continue;
            }
            int ring = 2 * nx + 2 * nz - 4;
            long inner = (long) nx * nz - ring;
            wallColumns += ring;
            innerColumns += inner;
            // 外墙每列都要从底写到顶；内部只需每层一块楼板
            wallBlocks += (long) ring * p.height;
            slabBlocks += inner * p.floors;
        }
        System.out.printf("buildings: %d 建 / %d 空, max %d blocks (%d 层)%n",
                builtParcels, emptyParcels, maxHeight, maxHeight / 4);
        System.out.printf("  wall %d cols -> %d blocks, inner %d cols -> %d slabs, total ~%d blocks%n",
                wallColumns, wallBlocks, innerColumns, slabBlocks, wallBlocks + slabBlocks);
        System.out.println("floors by zone:");
        for (Zone z : Zone.values()) {
            int[] agg = floorsByZone.get(z);
            if (agg == null) {
                continue;
            }
            System.out.printf("  %-8s n=%3d  avg floors=%5.1f%n",
                    z.name(), agg[0], agg[1] / (double) agg[0]);
        }

        String[] kindName = {"塔楼", "地标", "板楼", "联排", "厂房"};
        int[] kindCount = new int[5];
        int[] kindMax = new int[5];
        for (Parcels.Parcel p : plan.parcels.list) {
            if (p.height <= 0) {
                continue;
            }
            kindCount[p.kind]++;
            kindMax[p.kind] = Math.max(kindMax[p.kind], p.floors);
        }
        System.out.println("building kinds:");
        for (int i = 0; i < kindCount.length; i++) {
            System.out.printf("  %-4s n=%3d  max floors=%3d%n", kindName[i], kindCount[i], kindMax[i]);
        }

        // 最高的几栋，进游戏时可以按这些坐标去看实际效果
        System.out.println("tallest parcels (megachunk-local coords):");
        plan.parcels.list.stream()
                .filter(p -> p.height > 0)
                .sorted((a, b) -> Integer.compare(b.height, a.height))
                .limit(5)
                .forEach(p -> System.out.printf("  (%3d,%3d) %dx%d %-8s %2d 层 / %3d 格  style=%d%n",
                        p.x, p.y, p.w, p.h, p.zone.name(), p.floors, p.height, p.style));

        // 校验交给 MC 侧的 ChunkSlice：逐格一致性 + 按坐标乱序读取也不受影响
        int[][] probes = {{cpr / 2, cpr / 2}, {cpr / 3, 2 * cpr / 3}, {0, 0}, {cpr - 1, cpr - 1}};
        boolean sliceOk = true;
        for (int[] pr : probes) {
            CityPlan.ChunkSlice s = plan.slice(pr[0], pr[1]);
            int roadN = 0;
            int parcelN = 0;
            int bldN = 0;
            for (int i = 0; i < CityPlan.CHUNK * CityPlan.CHUNK; i++) {
                int wx = pr[0] * CityPlan.CHUNK + i % CityPlan.CHUNK;
                int wz = pr[1] * CityPlan.CHUNK + i / CityPlan.CHUNK;
                if (s.road[i] != plan.roads.at(wx, wz) || s.zones[i] != plan.zoning.zoneAt(wx, wz)) {
                    sliceOk = false;
                }
                if (s.road[i]) {
                    roadN++;
                }
                if (s.parcel[i] >= 0) {
                    parcelN++;
                }
                if (s.bldHeight[i] > 0) {
                    bldN++;
                    // 同一地块在任何区块切出来的建筑高度必须相等，否则屋顶会串层
                    if (s.bldHeight[i] != plan.parcels.list.get(s.parcel[i]).height) {
                        sliceOk = false;
                    }
                }
            }
            System.out.printf("  slice(%2d,%2d) road=%3d parcelCells=%3d buildingCells=%3d%n",
                    pr[0], pr[1], roadN, parcelN, bldN);
        }
        System.out.println("chunk slice consistent: " + sliceOk);

        System.out.printf("planning %.0f ms, render+io %.0f ms%n", (t1 - t0) / 1e6, (t2 - t1) / 1e6);
        System.out.println("layers: " + plan.timing);
        System.out.println("deterministic(same seed): " + deterministic);
        System.out.println("seed sensitive: " + seedSensitive);
        System.out.println("output: " + out.toAbsolutePath());
    }
}
