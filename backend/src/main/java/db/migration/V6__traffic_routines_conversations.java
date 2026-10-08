package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import world.earth35.world.*;
import world.earth35.world.State.*;

/** Upgrade the existing city and recover whose turn it is from recorded dialogue. */
public class V6__traffic_routines_conversations extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        ObjectMapper json=new ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        try(var query=context.getConnection().prepareStatement("SELECT data::text FROM world_state WHERE id=1 FOR UPDATE");var rows=query.executeQuery()) {
            if(!rows.next())return;
            ObjectNode data=(ObjectNode)json.readTree(rows.getString(1));State s=json.treeToValue(data,State.class);
            for(Resident a:s.agents.values()) {
                if(a.routine==null)a.routine=DailyRoutines.derive(a);
                if(a.pending!=null&&"SLEEP".equals(a.pending.type)&&a.bedtimeRoutine)a.pending.until=DailyRoutines.nextWake(a,s.minute);
                ((ObjectNode)data.path("agents").path(a.id)).set("routine",json.valueToTree(a.routine));
                if(a.pending!=null)((ObjectNode)data.path("agents").path(a.id)).set("pending",json.valueToTree(a.pending));
            }
            Traffic.populate(s);DailyRoutines.configureJobs(s);
            for(var stop:Traffic.STOPS)((ObjectNode)data.path("buildings")).set(stop.id(),json.valueToTree(s.buildings.get(stop.id())));
            for(Job job:s.jobs.values()) {
                ObjectNode saved=(ObjectNode)data.path("jobs").path(job.id);
                saved.put("startMinute",job.startMinute);saved.put("endMinute",job.endMinute);saved.set("workDays",json.valueToTree(job.workDays));
            }
            ((ObjectNode)data.path("accounts")).put("transit",s.accounts.get("transit"));
            try(var events=context.getConnection().prepareStatement("SELECT actor_id,target_id,message,world_minute FROM world_events WHERE kind='conversation' ORDER BY id");var history=events.executeQuery()) {
                while(history.next()) {
                    String a=history.getString(1),b=history.getString(2);if(!s.agents.containsKey(a)||!s.agents.containsKey(b)||a.equals(b))continue;
                    Conversation c=s.conversations.computeIfAbsent(Conversations.key(a,b),key->{Conversation v=new Conversation();v.a=a;v.b=b;return v;});
                    c.lastSpeaker=a;c.lastMinute=history.getLong(4);c.turns.add(new Turn(a,history.getString(3),c.lastMinute));
                    while(c.turns.size()>12)c.turns.removeFirst();
                }
            }
            data.set("conversations",json.valueToTree(s.conversations));data.put("version",s.version+1);
            try(var update=context.getConnection().prepareStatement("UPDATE world_state SET data=?::jsonb WHERE id=1")) {
                update.setString(1,json.writeValueAsString(data));update.executeUpdate();
            }
        }
    }
}
