package world.earth35.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.zip.CRC32;

/** Shared, versioned catalog for new cities and the migration of saved cities. */
public final class CityPlaces {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] CATALOG_BYTES;
    private static final JsonNode CATALOG;
    static {
        try (var input = CityPlaces.class.getResourceAsStream("/city/social-places-v5.json")) {
            if (input == null) throw new IllegalStateException("Social place catalog is missing");
            CATALOG_BYTES = input.readAllBytes();
            CATALOG = JSON.readTree(CATALOG_BYTES);
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private CityPlaces() {}
    public static int catalogChecksum() {
        CRC32 checksum = new CRC32(); checksum.update(CATALOG_BYTES); return (int) checksum.getValue();
    }
    public static State populate(State state) {
        ObjectNode data = JSON.valueToTree(state);
        upgrade(data);
        try { return JSON.treeToValue(data, State.class); }
        catch (IOException e) { throw new IllegalStateException("Invalid social place catalog", e); }
    }
    public static void upgrade(ObjectNode data) {
        ObjectNode buildings = data.withObject("/buildings");
        for (JsonNode place : CATALOG.path("additions")) {
            String id = place.path("id").asText();
            if (!buildings.has(id)) buildings.set(id, place.deepCopy());
        }
        CATALOG.path("existing").fields().forEachRemaining(entry -> {
            if (buildings.get(entry.getKey()) instanceof ObjectNode building)
                entry.getValue().fields().forEachRemaining(field -> building.set(field.getKey(), field.getValue().deepCopy()));
        });
        for (String section : new String[]{"accounts", "jobs"}) {
            ObjectNode destination = data.withObject("/" + section);
            CATALOG.path(section).fields().forEachRemaining(entry -> {
                if (!destination.has(entry.getKey())) destination.set(entry.getKey(), entry.getValue().deepCopy());
            });
        }
    }
}
