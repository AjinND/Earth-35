package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import world.earth35.world.*;

/** Correct old doorway positions without replaying arrivals or changing accepted actions. */
public class V11__move_indoor_residents_inside extends BaseJavaMigration {
    public static void upgrade(ObjectNode data) throws Exception {
        var json=new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        State state=json.treeToValue(data,State.class);
        for(var resident:state.agents.values()) {
            var building=state.buildings.get(resident.location);
            var saved=data.withObject("/agents/"+resident.id);
            if(!Travel.moving(resident)&&Places.enclosed(building)) {
                saved.put("x",building.x);saved.put("z",building.z);
            }else if(Travel.moving(resident)) {
                saved.withObject("/pending").set("exitDoor",json.valueToTree(Travel.exitDoor(state,resident)));
            }
        }
        data.put("version",state.version+1);
    }
    @Override public void migrate(Context context) throws Exception {
        var json=new ObjectMapper();
        try(var query=context.getConnection().prepareStatement("SELECT data::text FROM world_state WHERE id=1 FOR UPDATE");var rows=query.executeQuery()) {
            if(!rows.next())return;
            ObjectNode data=(ObjectNode)json.readTree(rows.getString(1));upgrade(data);
            try(var update=context.getConnection().prepareStatement("UPDATE world_state SET data=?::jsonb WHERE id=1")) {
                update.setString(1,json.writeValueAsString(data));update.executeUpdate();
            }
        }
    }
}
