package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import world.earth35.world.IslandLandscapes;

/** Complete the connected island without replacing occupied properties or resident state. */
public class V25__connected_island_landscapes extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        ObjectMapper json=new ObjectMapper();
        try(var query=context.getConnection().prepareStatement("SELECT data::text FROM world_state WHERE id=1 FOR UPDATE");var rows=query.executeQuery()) {
            if(!rows.next())return;
            ObjectNode data=(ObjectNode)json.readTree(rows.getString(1));IslandLandscapes.completePhase3(data);
            try(var update=context.getConnection().prepareStatement("UPDATE world_state SET data=?::jsonb WHERE id=1")) {
                update.setString(1,json.writeValueAsString(data));update.executeUpdate();
            }
        }
    }
}
