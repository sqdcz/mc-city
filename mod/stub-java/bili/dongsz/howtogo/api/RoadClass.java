package bili.dongsz.howtogo.api;

/**
 * HowToGo 官方 API 的编译桩（stub）。
 *
 * <p>签名按官方文档 docs/addon-road-data-api.md 抄写，**只用于编译**，
 * 不打进 citygen 的 jar；运行期由用户安装的 HowToGo 本体提供真实实现。
 * 打包脚本会把这个包排除在产物之外。
 */
public enum RoadClass {
    HIGHWAY, ROAD, PATH, RAIL, WATER, ICE
}
