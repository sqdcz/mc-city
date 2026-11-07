package citygen;

/** 用地性质。far 为示意性容积率，用于建筑体量与填充浓度的演示。 */
public enum Zone {

    CBD("核心商务区", 0x8E5AA8, 9.0),
    COMMERCIAL("商业区", 0xC9635F, 4.5),
    RES_HIGH("高密度住宅", 0xD9A04B, 2.5),
    RES_LOW("低密度住宅", 0xEFD08A, 1.1),
    INDUSTRIAL("工业区", 0x8A8F98, 0.8),
    GREEN("绿地", 0x6FA86A, 0.05),
    WATER("水体", 0x5B8FC9, 0.0);

    public final String label;
    public final int rgb;
    public final double far;

    Zone(String label, int rgb, double far) {
        this.label = label;
        this.rgb = rgb;
        this.far = far;
    }

    public int r() {
        return (rgb >> 16) & 0xFF;
    }

    public int g() {
        return (rgb >> 8) & 0xFF;
    }

    public int b() {
        return rgb & 0xFF;
    }

    /** 相邻兼容矩阵：返回 true 表示两类用地可以直接贴邻。 */
    public boolean compatible(Zone other) {
        if (this == other || this == GREEN || other == GREEN || this == WATER || other == WATER) {
            return true;
        }
        if (this == INDUSTRIAL || other == INDUSTRIAL) {
            Zone a = this == INDUSTRIAL ? other : this;
            return a == INDUSTRIAL || a == COMMERCIAL;
        }
        return true;
    }
}
