package bili.dongsz.howtogo.api;

/** HowToGo 编译桩，见 RoadClass 的说明。 */
public interface RoadDataSource {
    int version();

    void write(RoadDataWriter writer, String dimension);
}
