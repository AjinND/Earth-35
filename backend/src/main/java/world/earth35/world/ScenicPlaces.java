package world.earth35.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.zip.CRC32;

/** Additive catalog: saved residents, actions and existing venues are untouched. */
public final class ScenicPlaces {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] BYTES;
    private static final JsonNode CATALOG;
    static {
        try (var input = ScenicPlaces.class.getResourceAsStream("/city/scenic-places-v15.json")) {
            if (input == null) throw new IllegalStateException("Scenic place catalog is missing");
            BYTES = input.readAllBytes();
            CATALOG = JSON.readTree(BYTES);
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private ScenicPlaces() {}
    public static int catalogChecksum() {
        CRC32 checksum = new CRC32(); checksum.update(BYTES); return (int) checksum.getValue();
    }
    public static void upgrade(ObjectNode data) {
        ObjectNode buildings = data.withObject("/buildings");
        for (JsonNode place : CATALOG) {
            String id = place.path("id").asText();
            if (!buildings.has(id)) {
                try {
                    State.Building building = JSON.treeToValue(place, State.Building.class);
                    building.entrance = Places.entrance(building);
                    buildings.set(id, JSON.valueToTree(building));
                } catch (IOException e) { throw new IllegalStateException("Invalid scenic place", e); }
            }
        }
    }
    public static State populate(State state) {
        ObjectNode data = JSON.valueToTree(state); upgrade(data);
        try { return JSON.treeToValue(data, State.class); }
        catch (IOException e) { throw new IllegalStateException("Invalid scenic place catalog", e); }
    }
}
