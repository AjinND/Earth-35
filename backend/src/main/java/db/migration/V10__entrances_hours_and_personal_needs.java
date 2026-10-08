package db.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import world.earth35.world.*;

/** Preserve residents and accepted actions while moving old arrival points to their front doors. */
public class V10__entrances_hours_and_personal_needs extends BaseJavaMigration {
    public static void upgrade(ObjectNode data) throws Exception {
        var json=new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        State state=json.treeToValue(data,State.class);Places.configure(state);DailyRoutines.configureJobs(state);
        for(var resident:state.agents.values()) {
            var place=state.buildings.get(resident.location);
            if(place!=null) {var door=Places.entrance(place);resident.x=door.x();resident.z=door.z();}
            if(Travel.moving(resident)) {
                var destination=state.buildings.get(resident.pending.target);
                if(destination!=null) {
                    resident.pending.route=Travel.path(state,resident,destination,"shortest");
                    resident.pending.routeChoice="shortest";resident.pending.frontDoorRoute=true;
                }
            }
            if(resident.pending!=null&&"SLEEP".equals(resident.pending.type)) {
                int duration=resident.routine==null?600:Math.floorMod(resident.routine.wakeMinute-resident.routine.sleepMinute,1440);
                resident.pending.startedAt=Math.max(0,Math.min(state.minute,resident.pending.until-duration));
                resident.pending.mainSleep=DailyRoutines.sleepingTime(resident,resident.pending.startedAt);
            }
            var saved=data.withObject("/agents/"+resident.id);
            saved.put("x",resident.x);saved.put("z",resident.z);
            if(resident.pending!=null) {
                ObjectNode pending=json.valueToTree(resident.pending);
                for(String field:new String[]{"route","routeChoice","frontDoorRoute","startedAt","mainSleep"})
                    saved.withObject("/pending").set(field,pending.get(field));
            }
        }
        for(var building:state.buildings.values()) {
            var saved=data.withObject("/buildings/"+building.id);
            saved.set("entrance",json.valueToTree(building.entrance));saved.put("openMinute",building.openMinute);saved.put("closeMinute",building.closeMinute);
            saved.set("openDays",json.valueToTree(building.openDays));
        }
        for(var job:state.jobs.values()) {
            var saved=data.withObject("/jobs/"+job.id);saved.put("startMinute",job.startMinute);saved.put("endMinute",job.endMinute);
            saved.set("workDays",json.valueToTree(job.workDays));
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
