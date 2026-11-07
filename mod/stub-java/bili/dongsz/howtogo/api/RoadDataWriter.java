package bili.dongsz.howtogo.api;

/** HowToGo 编译桩，见 RoadClass 的说明。 */
public interface RoadDataWriter {
    void point(String id, int x, int y, int z, String name, PointKind kind);

    void line(String id, String fromPoint, String toPoint, RoadClass roadClass, int layer, boolean oneWay);
}
