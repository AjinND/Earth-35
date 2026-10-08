package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import world.earth35.protocol.Contracts.*;
import java.util.*;

@Component
public class WorldStore {
    public final JdbcTemplate db;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${earth35.tick-ms:5000}")
    private long tickMs=5000;
    public WorldStore(JdbcTemplate db,ObjectMapper json) { this.db=db; this.json=json; }
    @PostConstruct void seed() { db.update("INSERT INTO world_state(id,data) VALUES(1,?::jsonb) ON CONFLICT DO NOTHING",encode(State.seed())); }
    public String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException(e); } }
    public State read(boolean lock) {
        String value=db.queryForObject("SELECT data::text FROM world_state WHERE id=1"+(lock?" FOR UPDATE":""),String.class);
        try {
            State state=json.readValue(value,State.class);
            state.agents.values().forEach(Library::refreshKnowledge);
            return state;
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    public void save(State s) { s.version++;db.update("UPDATE world_state SET data=?::jsonb WHERE id=1",encode(s)); }
    public void effects(State s,Engine.Effects e) {
        for(var f:e.facts) {
            State.Conversation chat=s.conversations.get(Conversations.key(f.actor(),f.target()));
            if(f.kind().equals("conversation")&&chat!=null&&chat.closed&&chat.closingSpeaker!=null) {
                // Farewells and their acknowledgments belong to the exchange they close,
                // even though the saved chat was already closed before an acknowledgment.
                db.update("""
                    INSERT INTO world_events(world_minute,kind,actor_id,target_id,message,conversation_episode)
                    VALUES(?,?,?,?,?,(SELECT conversation_episode FROM world_events WHERE kind='conversation'
                      AND ((actor_id=? AND target_id=?) OR (actor_id=? AND target_id=?)) ORDER BY id DESC LIMIT 1))
                    """,s.minute,f.kind(),f.actor(),f.target(),f.message(),f.actor(),f.target(),f.target(),f.actor());
            } else event(s.minute,f.kind(),f.actor(),f.target(),f.message());
        }
        for(var p:e.payments) db.update("INSERT INTO transactions(world_minute,source,destination,amount,reason) VALUES(?,?,?,?,?)",s.minute,p.source(),p.destination(),p.amount(),p.reason());
        for(var c:e.completions) db.update("UPDATE agent_actions SET status=?,result=? WHERE id=?",c.status(),c.result(),c.id());
    }
    public void event(long minute,String kind,String actor,String target,String message) { db.update("INSERT INTO world_events(world_minute,kind,actor_id,target_id,message) VALUES(?,?,?,?,?)",minute,kind,actor,target,message); }
    public List<Event> events(long after,int limit,String agent) {
        return db.query("SELECT * FROM world_events WHERE id>? AND (?='' OR actor_id=? OR target_id=?) ORDER BY id LIMIT ?",
            (rs,row)->new Event(rs.getLong("id"),rs.getLong("world_minute"),rs.getString("kind"),rs.getString("actor_id"),rs.getString("target_id"),rs.getString("message")),after,agent,agent,agent,limit);
    }
    public World snapshot() {
        State s=read(false);
        long cursor=Optional.ofNullable(db.queryForObject("SELECT max(id) FROM world_events",Long.class)).orElse(0L);
        return new World(s.minute,s.version,Engine.weather(s.minute),Engine.temperature(s.minute),tickMs,
            s.buildings.values().stream().map(b->new StateBuilding(b.id,b.name,b.type,b.district,b.x,b.z,b.capacity,b.description,b.social,b.restAllowed,b.objects,Places.entrance(b),Places.hours(b,s.minute),b.outdoorRadius,b.plannedType)).toList(),
            s.agents.values().stream().map(WorldStore::publicAgent).toList(),events(Math.max(0,cursor-50),50,""),Traffic.network(s),Economy.summary(s),CityLayout.of(s),Projects.publicView(s),Construction.view(s),WorldObjects.view(s));
    }
    public static PublicAgent publicAgent(State.Resident a) {
        String status=a.paused?"paused":System.currentTimeMillis()-a.lastSeen<45000?"online":"offline";
        boolean transit=a.pending!=null&&List.of("WAIT_BUS","BOARD_BUS","RIDE_BUS").contains(a.pending.type);
        return new PublicAgent(a.id,a.name,a.description,a.home,a.occupation,a.location,a.x,a.z,Engine.activity(a),status,a.fixture,a.raincoatWorn,Seating.seatIndex(a),
            transit?a.pending.vehicleId:null,transit?List.copyOf(a.pending.motion):List.of(),a.facingHeading,a.facingUntil);
    }
}

