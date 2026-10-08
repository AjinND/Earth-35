package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import world.earth35.world.Traffic;

public class V7__travel_choices extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        ObjectMapper json=new ObjectMapper();
        try(var query=context.getConnection().prepareStatement("SELECT data::text FROM world_state WHERE id=1 FOR UPDATE");var rows=query.executeQuery()) {
            if(!rows.next())return;
            ObjectNode data=(ObjectNode)json.readTree(rows.getString(1));
            for(var stop:Traffic.STOPS) {
                ObjectNode building=(ObjectNode)data.path("buildings").path(stop.id());building.put("x",stop.x());building.put("z",stop.z());
                for(var node:data.path("agents"))if(stop.id().equals(node.path("location").asText())) {
                    ((ObjectNode)node).put("x",stop.x());((ObjectNode)node).put("z",stop.z());
                }
            }
            ObjectNode market=(ObjectNode)data.path("buildings").path("market");
            market.withObject("/stock").put("raincoat",30);market.withObject("/prices").put("raincoat",3500);
            data.put("version",data.path("version").asLong()+1);
            try(var update=context.getConnection().prepareStatement("UPDATE world_state SET data=?::jsonb WHERE id=1")) {update.setString(1,json.writeValueAsString(data));update.executeUpdate();}
        }
    }
}
