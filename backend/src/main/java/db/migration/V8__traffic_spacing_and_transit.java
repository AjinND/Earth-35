package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import world.earth35.world.Traffic;

/** Move platforms away from junctions, preserving the city and refunding old active services. */
public class V8__traffic_spacing_and_transit extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        ObjectMapper json=new ObjectMapper();
        try(var query=context.getConnection().prepareStatement("SELECT data::text FROM world_state WHERE id=1 FOR UPDATE");var rows=query.executeQuery()) {
            if(!rows.next())return;
            ObjectNode data=(ObjectNode)json.readTree(rows.getString(1));
            for(var stop:Traffic.STOPS) {
                ObjectNode building=(ObjectNode)data.path("buildings").path(stop.id());building.put("x",stop.x());building.put("z",stop.z());
            }
            for(var node:data.path("agents")) {
                ObjectNode agent=(ObjectNode)node;var pending=agent.path("pending");String type=pending.path("type").asText();
                if(type.equals("WAIT_BUS")||type.equals("RIDE_BUS")) {
                    if(type.equals("RIDE_BUS")) {
                        agent.put("money",agent.path("money").asLong()+250);
                        ObjectNode accounts=(ObjectNode)data.path("accounts");accounts.put("transit",accounts.path("transit").asLong()-250);
                        try(var refund=context.getConnection().prepareStatement("INSERT INTO transactions(world_minute,source,destination,amount,reason) VALUES (?,'transit',?,250,'bus_fare_refund')")) {
                            refund.setLong(1,data.path("minute").asLong());refund.setString(2,agent.path("id").asText());refund.executeUpdate();
                        }
                    }
                    agent.put("location",pending.path("boardingStop").asText("stop-residential"));agent.putNull("pending");
                    try(var action=context.getConnection().prepareStatement("UPDATE agent_actions SET status='cancelled',result='Transit network updated; choose a new journey. Any paid fare was refunded.' WHERE id=?")) {
                        action.setString(1,pending.path("id").asText());action.executeUpdate();
                    }
                }
                var stop=Traffic.stop(agent.path("location").asText());
                if(stop!=null){agent.put("x",stop.x());agent.put("z",stop.z());}
            }
            data.put("version",data.path("version").asLong()+1);
            try(var update=context.getConnection().prepareStatement("UPDATE world_state SET data=?::jsonb WHERE id=1")) {update.setString(1,json.writeValueAsString(data));update.executeUpdate();}
        }
    }
}
