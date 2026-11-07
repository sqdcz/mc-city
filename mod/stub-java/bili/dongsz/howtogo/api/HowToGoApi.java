package bili.dongsz.howtogo.api;

/** HowToGo 编译桩，见 RoadClass 的说明。 */
public final class HowToGoApi {
    public static final int API_VERSION = 1;

    private HowToGoApi() {
    }

    public static void registerRoadDataSource(String id, RoadDataSource source) {
        // 桩实现：运行期由 HowToGo 本体接管
        throw new LinkageError("stub only; howtogo 未安装");
    }
}
