package dev.ship.citygen;

/**
 * HowToGo 导航的名字来源：街名、市民名，全部由位置哈希确定性派生。
 *
 * <p>为什么不用随机数：同一块地的路牌如果每次生成名字不一样，
 * 玩家回头一看路名变了，城市就「假」了。所有名字都必须是
 * 「给定了世界坐标和种子，永远一个答案」。
 *
 * <p>中文取模要用 {@link Math#floorMod}——坐标有负数，直接 % 会出负下标。
 */
public final class CityNames {

    /** 大道名：主干道和环线用。 */
    private static final String[] AVENUES = {
            "中山大道", "解放大道", "人民大道", "建设大道",
            "和平大道", "长安大道", "光明大道", "振兴大道",
    };
    /** 街名：支路和次干道用。 */
    private static final String[] STREETS = {
            "梧桐街", "银杏街", "枫叶街", "翠竹街",
            "梅林街", "桂花街", "玉兰街", "茉莉街",
            "青石街", "柳荫街", "杏花街", "桃源街",
    };
    private static final String[] SURNAMES = {
            "王", "李", "张", "刘", "陈", "杨", "赵", "黄",
            "周", "吴", "徐", "孙", "胡", "朱", "高", "林",
    };
    private static final String[] GIVEN = {
            "伟", "芳", "娜", "敏", "静", "磊", "军", "洋",
            "勇", "艳", "杰", "涛", "明", "超", "秀英", "霞",
            "平", "刚", "桂英", "文轩", "雨欣", "浩然", "子涵", "思远",
    };

    private CityNames() {
    }

    /** 大道名：由世界坐标派生，同一条路相邻的路牌名字一致。 */
    public static String avenueName(int wx, int wz) {
        return AVENUES[Math.floorMod(wx * 13 + wz * 31, AVENUES.length)];
    }

    /** 街名。 */
    public static String streetName(int wx, int wz) {
        return STREETS[Math.floorMod(wx * 7 + wz * 23, STREETS.length)];
    }

    /** 市民姓名：姓 + 名，取自真实常见组合。 */
    public static String citizenName(int hash) {
        return SURNAMES[Math.floorMod(hash, SURNAMES.length)]
                + GIVEN[Math.floorMod(hash / SURNAMES.length, GIVEN.length)];
    }
}
