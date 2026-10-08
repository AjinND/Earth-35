package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import world.earth35.world.ScenicPlaces;

public class V15__scenic_places extends BaseJavaMigration {
    @Override public Integer getChecksum() { return ScenicPlaces.catalogChecksum(); }
    @Override public void migrate(Context context) throws Exception {
        var json = new ObjectMapper();
        try (var select = context.getConnection().prepareStatement("SELECT data::text FROM world_state WHERE id=1 FOR UPDATE");
             var rows = select.executeQuery()) {
            if (!rows.next()) return;
            ObjectNode data = (ObjectNode) json.readTree(rows.getString(1));
            ScenicPlaces.upgrade(data);
            try (var update = context.getConnection().prepareStatement("UPDATE world_state SET data=?::jsonb WHERE id=1")) {
                update.setString(1, json.writeValueAsString(data)); update.executeUpdate();
            }
        }
    }
}
