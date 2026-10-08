package db.migration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import world.earth35.world.*;

/** Fund existing sites once, preserving residents, actions, balances and unknown saved fields. */
public class V21__autonomous_livelihood_and_construction extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        ObjectMapper json=new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        try(var query=context.getConnection().prepareStatement("SELECT data::text FROM world_state WHERE id=1 FOR UPDATE");var rows=query.executeQuery()) {
            if(!rows.next())return;
            ObjectNode data=(ObjectNode)json.readTree(rows.getString(1));State state=json.treeToValue(data,State.class);
            Engine.Effects effects=new Engine.Effects();Construction.initializeCitySites(state,effects);
            data.set("construction",json.valueToTree(state.construction));data.set("accounts",json.valueToTree(state.accounts));
            data.put("version",state.version+1);
            try(var update=context.getConnection().prepareStatement("UPDATE world_state SET data=?::jsonb WHERE id=1")) {
                update.setString(1,json.writeValueAsString(data));update.executeUpdate();
            }
            for(var payment:effects.payments)try(var insert=context.getConnection().prepareStatement("INSERT INTO transactions(world_minute,source,destination,amount,reason) VALUES(?,?,?,?,?)")) {
                insert.setLong(1,state.minute);insert.setString(2,payment.source());insert.setString(3,payment.destination());
                insert.setLong(4,payment.amount());insert.setString(5,payment.reason());insert.executeUpdate();
            }
        }
    }
}
