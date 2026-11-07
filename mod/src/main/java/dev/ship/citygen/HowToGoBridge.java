package dev.ship.citygen;

import bili.dongsz.howtogo.api.HowToGoApi;
import bili.dongsz.howtogo.api.PointKind;
import bili.dongsz.howtogo.api.RoadClass;
import bili.dongsz.howtogo.api.RoadDataWriter;
import bili.dongsz.howtogo.api.RoadDataSource;
import citygen.CityPlan;
import citygen.Roads;

/**
 * HowToGo（问道）桥接：把城市模组生成的道路折线注册成它的附属图层。
 *
 * <p>类加载的次序很讲究：HowToGo 没装时，{@link #register()} 在碰到任何
 * stub 类引用之前就返回——所以那些类不会被类加载器解析，客户端不会崩。
 * register 里那句 isModLoaded 判断不是可有可无的。
 */
public final class HowToGoBridge {

    private HowToGoBridge() {
    }

    public static boolean available() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("howtogo");
    }

    public static void register() {
        if (!available()) {
            return;
        }
        registerInner();
    }

    private static void registerInner() {
        HowToGoApi.registerRoadDataSource("citygen", new RoadDataSource() {
            @Override
            public int version() {
                return (int) CityPlanManager.revision();
            }

            @Override
            public void write(RoadDataWriter w, String dimension) {
                if (!"minecraft:overworld".equals(dimension)) {
                    return;
                }
                for (CityPlan plan : CityPlanManager.all()) {
                    for (Roads.LineOut line : plan.roadLines()) {
                        writeLine(w, line);
                    }
                }
            }
        });
    }

    private static void writeLine(RoadDataWriter w, Roads.LineOut line) {
        String[] ids = new String[line.xs.length];
        for (int i = 0; i < line.xs.length; i++) {
            ids[i] = line.id + "#" + i;
            String name = null;
            if (i == 0 || i == line.xs.length - 1) {
                name = line.level >= 2
                        ? CityNames.avenueName((int) line.xs[i], (int) line.zs[i])
                        : CityNames.streetName((int) line.xs[i], (int) line.zs[i]);
            }
            w.point(ids[i], (int) Math.round(line.xs[i]), 64,
                    (int) Math.round(line.zs[i]), name,
                    (i == 0 || i == line.xs.length - 1) ? PointKind.JUNCTION : PointKind.PLACE);
        }
        RoadClass rc = line.level == 1 ? RoadClass.HIGHWAY : RoadClass.ROAD;
        for (int i = 0; i < line.xs.length - 1; i++) {
            w.line(line.id + "#" + i, ids[i], ids[i + 1], rc, 0, false);
        }
    }
}
