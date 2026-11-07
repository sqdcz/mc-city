package dev.ship.citygen;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组入口。
 *
 * <p>区块生成器的注册放在 {@link CityGenPreLaunch}——因为 26.3 的注册表在
 * {@code onInitialize} 之前就已冻结。这里只做启动日志。
 *
 * <p>整个模组不依赖 Fabric API：世界种子通过 {@code WorldGenRegion.getSeed()}
 * 在区块生成线程上直接取得，规划器按种子惰性创建。
 */
public class CityGenMod implements ModInitializer {

    public static final String MOD_ID = "citygen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("CityGen 已加载，区块生成器 {}:city 可用", MOD_ID);
    }
}
