package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import world.earth35.world.Traffic;

/** Correct existing curb anchors without resetting residents or their private state. */
public class V20__directional_bus_platforms_and_boarding extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        ObjectMapper json=new ObjectMapper();
        try(var query=context.getConnection().prepareStatement("SELECT data::text FROM world_state WHERE id=1 FOR UPDATE");var rows=query.executeQuery()) {
            if(!rows.next())return;
            ObjectNode data=(ObjectNode)json.readTree(rows.getString(1));var completions=Traffic.upgradeStops(data);
            try(var update=context.getConnection().prepareStatement("UPDATE world_state SET data=?::jsonb WHERE id=1")) {
                update.setString(1,json.writeValueAsString(data));update.executeUpdate();
            }
            for(var completion:completions)try(var update=context.getConnection().prepareStatement("UPDATE agent_actions SET status=?,result=? WHERE id=?")) {
                update.setString(1,completion.status());update.setString(2,completion.result());update.setString(3,completion.id());update.executeUpdate();
            }
        }
    }
}
