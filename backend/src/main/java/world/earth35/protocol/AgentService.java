package world.earth35.protocol;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import world.earth35.identity.Identity;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.*;
import world.earth35.world.State.*;

@Service
public class AgentService {
    private final WorldStore store;
    public AgentService(WorldStore store) { this.store=store; }
    public record Registered(PublicAgent agent,String token) {}
    public record Detail(Resident state,long memoryCount,Long reportedTokens,List<Map<String,Object>> transactions,List<Map<String,Object>> actions,DailyRoutines.WorkSchedule workSchedule,List<SocialPlan> socialPlans,Library.LibraryView library,Projects.View innovations,Livelihood.View livelihood,Map<String,Object> worldObjects) {}
    public record GatheringPlace(String id,String name,int residentsHere) {}
    public record Contact(String agent,boolean available) {}
    public record Observation(long worldMinute,long version,String weather,String timeOfDay,boolean night,long nextMorning,Resident self,List<PublicAgent> nearby,List<Building> locations,List<Job> jobs,List<Event> events,List<ActionType> actions,List<String> objects,List<GatheringPlace> gatheringPlaces,Traffic.Network transport,List<Conversations.View> conversations,List<Contact> contacts,DailyRoutines.WorkSchedule workSchedule,Travel.Situation travel,Social.Situation social,Economy.Summary economy,Library.LibraryView library,CityLayout geography,Projects.View innovations,Livelihood.View livelihood,Map<String,Object> worldObjects) {
        public Observation(long minute,long version,String weather,String time,boolean night,long morning,Resident self,List<PublicAgent> nearby,List<Building> locations,List<Job> jobs,List<Event> events,List<ActionType> actions,List<String> objects,List<GatheringPlace> gathering,Traffic.Network transport,List<Conversations.View> conversations,List<Contact> contacts,DailyRoutines.WorkSchedule work,Travel.Situation travel,Social.Situation social,Economy.Summary economy,Library.LibraryView library,CityLayout geography,Projects.View innovations,Livelihood.View livelihood) {
            this(minute,version,weather,time,night,morning,self,nearby,locations,jobs,events,actions,objects,gathering,transport,conversations,contacts,work,travel,social,economy,library,geography,innovations,livelihood,Map.of());
        }
        public Observation(long minute,long version,String weather,String time,boolean night,long morning,Resident self,List<PublicAgent> nearby,List<Building> locations,List<Job> jobs,List<Event> events,List<ActionType> actions,List<String> objects,List<GatheringPlace> gathering,Traffic.Network transport,List<Conversations.View> conversations,List<Contact> contacts,DailyRoutines.WorkSchedule work,Travel.Situation travel,Social.Situation social,Economy.Summary economy,Library.LibraryView library,CityLayout geography,Projects.View innovations) {
            this(minute,version,weather,time,night,morning,self,nearby,locations,jobs,events,actions,objects,gathering,transport,conversations,contacts,work,travel,social,economy,library,geography,innovations,null);
        }
        public Observation(long minute,long version,String weather,String time,boolean night,long morning,Resident self,List<PublicAgent> nearby,List<Building> locations,List<Job> jobs,List<Event> events,List<ActionType> actions,List<String> objects,List<GatheringPlace> gathering,Traffic.Network transport,List<Conversations.View> conversations,List<Contact> contacts,DailyRoutines.WorkSchedule work,Travel.Situation travel,Social.Situation social,Economy.Summary economy,Library.LibraryView library,CityLayout geography) {
            this(minute,version,weather,time,night,morning,self,nearby,locations,jobs,events,actions,objects,gathering,transport,conversations,contacts,work,travel,social,economy,library,geography,null);
        }
        public DailyRoutines.SleepNeeds getSleep() {return DailyRoutines.sleepNeeds(self,worldMinute);}
        public DailyRoutines.Supplies getSupplies() {return DailyRoutines.supplies(self);}
        public IslandGeography.Exploration getExploration() {return geography.version()>=2?IslandGeography.exploration():null;}
        public Seating.View getSeating() {
            Building place=locations.stream().filter(b->b.id.equals(self.location)).findFirst().orElse(null);
            List<Integer> occupied=new ArrayList<>(nearby.stream().filter(a->a.location().equals(self.location)).map(PublicAgent::seatIndex).toList());
            occupied.add(Seating.seatIndex(self));return Seating.view(place,occupied);
        }
        public List<Conversations.Focus> getConversationFocus() {
            Set<String> close=new HashSet<>();
            nearby.stream().filter(a->Math.hypot(a.x()-self.x,a.z()-self.z)<=10).forEach(a->close.add(a.id()));
            return Conversations.focus(conversations,self.id,close);
        }
        public Observation(long minute,long version,String weather,String time,boolean night,long morning,Resident self,List<PublicAgent> nearby,List<Building> locations,List<Job> jobs,List<Event> events,List<ActionType> actions,List<String> objects,List<GatheringPlace> gathering,Traffic.Network transport,List<Conversations.View> conversations,List<Contact> contacts,DailyRoutines.WorkSchedule work,Travel.Situation travel,Social.Situation social,Economy.Summary economy,Library.LibraryView library) {
            this(minute,version,weather,time,night,morning,self,nearby,locations,jobs,events,actions,objects,gathering,transport,conversations,contacts,work,travel,social,economy,library,CityLayout.PROTOTYPE);
        }
        public Observation(long minute,long version,String weather,String time,boolean night,long morning,Resident self,List<PublicAgent> nearby,List<Building> locations,List<Job> jobs,List<Event> events,List<ActionType> actions,List<String> objects,List<GatheringPlace> gathering,Traffic.Network transport,List<Conversations.View> conversations,List<Contact> contacts,DailyRoutines.WorkSchedule work,Travel.Situation travel,Social.Situation social,Economy.Summary economy) {
            this(minute,version,weather,time,night,morning,self,nearby,locations,jobs,events,actions,objects,gathering,transport,conversations,contacts,work,travel,social,economy,null,CityLayout.PROTOTYPE);
        }
        public Observation(long minute,long version,String weather,String time,boolean night,long morning,Resident self,List<PublicAgent> nearby,List<Building> locations,List<Job> jobs,List<Event> events,List<ActionType> actions,List<String> objects,List<GatheringPlace> gathering,Traffic.Network transport,List<Conversations.View> conversations,List<Contact> contacts,DailyRoutines.WorkSchedule work,Travel.Situation travel,Social.Situation social) {
            this(minute,version,weather,time,night,morning,self,nearby,locations,jobs,events,actions,objects,gathering,transport,conversations,contacts,work,travel,social,null);
        }
        public Observation(long minute,long version,String weather,String time,boolean night,long morning,Resident self,List<PublicAgent> nearby,List<Building> locations,List<Job> jobs,List<Event> events,List<ActionType> actions,List<String> objects,List<GatheringPlace> gathering,Traffic.Network transport,List<Conversations.View> conversations,List<Contact> contacts,DailyRoutines.WorkSchedule work,Travel.Situation travel) {
            this(minute,version,weather,time,night,morning,self,nearby,locations,jobs,events,actions,objects,gathering,transport,conversations,contacts,work,travel,null);
        }
        public Observation(long minute,long version,String weather,String time,boolean night,long morning,Resident self,List<PublicAgent> nearby,List<Building> locations,List<Job> jobs,List<Event> events,List<ActionType> actions,List<String> objects,List<GatheringPlace> gathering) {
            this(minute,version,weather,time,night,morning,self,nearby,locations,jobs,events,actions,objects,gathering,null,List.of(),List.of(),null,null);
        }
    }
    public static List<GatheringPlace> gatheringPlaces(State state) {
        return state.buildings.values().stream().filter(b->b.social)
            .map(b->new GatheringPlace(b.id,b.name,(int)state.agents.values().stream().filter(a->b.id.equals(a.location)).count()))
            .sorted(Comparator.comparingInt(GatheringPlace::residentsHere).reversed()).toList();
    }
    public Resident owned(State s,String id,String owner) {
        Resident a=s.agents.get(id);
        if(a==null||!a.ownerId.equals(owner)) throw Identity.error(HttpStatus.NOT_FOUND,"Agent not found");
        return a;
    }
    @Transactional public Registered register(String owner,Registration input) {
        State s=store.read(true);
        if(s.agents.size()>=50) throw Identity.error(HttpStatus.CONFLICT,"New Eden has reached its 50-resident capacity");
        Resident a=new Resident();a.id=UUID.randomUUID().toString();a.ownerId=owner;a.name=input.name().strip();a.description=input.description();a.personality=input.personality();a.goals=input.goals();a.fixture=input.fixture();
        a.routine=DailyRoutines.derive(a);
        String preference=input.homeType()==null?"any":input.homeType();
        a.home=s.buildings.values().stream().filter(b->b.capacity==1&&(preference.equals("any")||b.type.equals(preference))&&s.agents.values().stream().noneMatch(r->r.home.equals(b.id))).findFirst()
            .orElseThrow(()->Identity.error(HttpStatus.CONFLICT,"No available home of that type")).id;
        Building home=s.buildings.get(a.home);a.location=home.id;a.x=home.x;a.z=home.z;
        s.agents.put(a.id,a);String token=Identity.token();
        store.db.update("INSERT INTO agent_credentials VALUES(?,?,?)",a.id,Identity.hash(token),owner);
        store.db.update("INSERT INTO transactions(world_minute,source,destination,amount,reason) VALUES(?,'city-grant',?,?,'arrival_grant')",s.minute,a.id,a.money);
        store.event(s.minute,"arrival",a.id,a.home,a.name+" arrived in New Eden"+(a.fixture?" (scripted test agent).":"."));store.save(s);
        return new Registered(WorldStore.publicAgent(a),token);
    }
    public List<PublicAgent> mine(String owner) { return store.read(false).agents.values().stream().filter(a->a.ownerId.equals(owner)).map(WorldStore::publicAgent).toList(); }
    public Detail detail(String owner,String id) {
        State s=store.read(false);Resident a=owned(s,id,owner);
        return new Detail(a,store.db.queryForObject("SELECT count(*) FROM active_agent_memories WHERE agent_id=?",Long.class,id),
            store.db.queryForObject("SELECT sum(tokens) FROM runtime_usage WHERE agent_id=?",Long.class,id),
            store.db.queryForList("SELECT * FROM transactions WHERE source=? OR destination=? ORDER BY id DESC LIMIT 50",id,id),
            store.db.queryForList("SELECT id,request_id,status,result,world_minute FROM agent_actions WHERE agent_id=? ORDER BY world_minute DESC,id LIMIT 50",id),DailyRoutines.schedule(s,a),Social.plans(s,a),Library.view(s,a),Projects.view(s,a),Livelihood.view(s,a),WorldObjects.observation(s));
    }
    @Transactional public void pause(String owner,String id,boolean paused) {
        State s=store.read(true);owned(s,id,owner).paused=paused;store.save(s);
        // Wake the decision loop on resume, while retaining any in-flight lease and
        // permanent provider errors that still need the owner's attention.
        store.db.update("UPDATE hosted_agents SET status=?,last_error='',next_run_at=now() WHERE agent_id=? AND status<>'needs_attention'",paused?"paused":"ready",id);
    }
    @Transactional public String rotate(String owner,String id,boolean revoke) {
        State s=store.read(true);owned(s,id,owner);String token=revoke?null:Identity.token();
        store.db.update("UPDATE agent_credentials SET token_hash=? WHERE agent_id=?",token==null?null:Identity.hash(token),id);return token;
    }
    @Transactional public Observation observe(String id,long after) {
        State s=store.read(true);Resident a=s.agents.get(id);a.lastSeen=System.currentTimeMillis();store.save(s);
        Map<String,Boolean> availability=availability(s);
        return new Observation(s.minute,s.version,Engine.weather(s.minute),NightRoutine.timeOfDay(s.minute),DailyRoutines.sleepingTime(a,s.minute),DailyRoutines.nextWake(a,s.minute),a,
            s.agents.values().stream().filter(b->!b.id.equals(id)&&Social.perceivable(s,a,b)&&Math.hypot(a.x-b.x,a.z-b.z)<=20).map(WorldStore::publicAgent).toList(),
            s.buildings.values().stream().filter(b->b.capacity>1||b.id.equals(a.home)).toList(),new ArrayList<>(s.jobs.values()),store.events(after,100,id),List.of(ActionType.values()),
            s.buildings.values().stream().flatMap(b->b.objects.keySet().stream()).toList(),gatheringPlaces(s),Traffic.observation(s,a),Conversations.views(s,a,availability),
            s.agents.values().stream().filter(b->!b.id.equals(id)&&Social.perceivable(s,a,b)&&Math.hypot(a.x-b.x,a.z-b.z)<=20).map(b->new Contact(b.id,availability.get(b.id))).toList(),DailyRoutines.schedule(s,a),Travel.situation(s,a),Social.situation(s,a,availability),Economy.summary(s),Library.view(s,a),CityLayout.of(s),Projects.view(s,a),Livelihood.view(s,a),WorldObjects.observation(s));
    }
    private Map<String,Boolean> availability(State s) {
        Map<String,String> hosted=new HashMap<>();
        for(var row:store.db.queryForList("SELECT agent_id,status FROM hosted_agents"))hosted.put((String)row.get("agent_id"),(String)row.get("status"));
        Map<String,Boolean> result=new HashMap<>();
        for(Resident a:s.agents.values()) {
            boolean connected=hosted.containsKey(a.id)?List.of("ready","running").contains(hosted.get(a.id)):System.currentTimeMillis()-a.lastSeen<45000;
            boolean busy=Engine.busy(a);
            result.put(a.id,connected&&!a.paused&&!busy);
        }
        return result;
    }
    @Transactional public void planRoutine(String id,RoutineInput input) {
        State s=store.read(true);setRoutine(s.agents.get(id),input);store.save(s);
    }
    private void setRoutine(Resident a,RoutineInput input) {
        if(a.paused||a.pending!=null)throw Identity.error(HttpStatus.CONFLICT,"Finish the current action before choosing a new daily routine");
        int duration=Math.floorMod(input.wakeMinute()-input.sleepMinute(),1440);
        if(input.wakeMinute()<0||input.wakeMinute()>1439||input.sleepMinute()<0||input.sleepMinute()>1439
            ||duration<360||duration>600||!List.of("breakfast","jogging","reading","meditation").contains(input.morningActivity()))
            throw Identity.error(HttpStatus.BAD_REQUEST,"Choose valid clock times, a morning activity, and a sleep window between six and ten hours");
        DailyRoutine r=new DailyRoutine();r.wakeMinute=input.wakeMinute();r.sleepMinute=input.sleepMinute();r.morningActivity=input.morningActivity();a.routine=r;
        a.bedtimeRoutine=false;
    }
    @Transactional public Receipt action(String id,Command input) {
        State s=store.read(true);
        return recordAction(s,id,input);
    }
    @Transactional public Receipt hostedAction(String id,Command proposed,NightPlanInput plan) {
        return hostedAction(id,proposed,plan,null);
    }
    @Transactional public Receipt hostedAction(String id,Command proposed,NightPlanInput plan,RoutineInput routine) {
        State s=store.read(true);Resident a=s.agents.get(id);
        String notes="";
        try {if(routine!=null)setRoutine(a,routine);}
        catch(org.springframework.web.server.ResponseStatusException e) {notes+=" Routine change ignored: "+e.getReason()+".";}
        try {if(plan!=null) setNightPlan(s,a,plan);}
        catch(org.springframework.web.server.ResponseStatusException e) {notes+=" Night plan ignored: "+e.getReason()+".";}
        Command resolved=NightRoutine.resolve(s,a,proposed);
        if(resolved!=proposed) a.bedtimeRoutine=true;
        return recordAction(s,id,resolved,notes);
    }
    @Transactional public Receipt nightRoutine(String id) {
        return nightRoutine(id,UUID.randomUUID().toString());
    }
    @Transactional public Receipt nightRoutine(String id,String requestId) {
        State s=store.read(true);Resident a=s.agents.get(id);
        var previous=store.db.queryForList("SELECT *, command->>'action' AS routine_action, command->>'target' AS routine_target FROM agent_actions WHERE agent_id=? AND request_id=?",id,requestId);
        if(!previous.isEmpty()) {
            var row=previous.getFirst();
            if(!"SLEEP".equals(row.get("routine_action"))&&!("MOVE".equals(row.get("routine_action"))&&a.home.equals(row.get("routine_target"))))
                throw Identity.error(HttpStatus.CONFLICT,"Request ID was already used for a different command");
            return receipt(row);
        }
        Command command=NightRoutine.defaultAction(s,a,requestId);
        if(command==null) return null;
        a.bedtimeRoutine=true;
        return recordAction(s,id,command);
    }
    @Transactional public void planNight(String id,NightPlanInput input) {
        State s=store.read(true);setNightPlan(s,s.agents.get(id),input);store.save(s);
    }
    private void setNightPlan(State s,Resident a,NightPlanInput input) {
        if(a.paused||a.pending!=null) throw Identity.error(HttpStatus.CONFLICT,"Wait until the current action finishes before changing a night plan");
        if(input.until()==0) {a.nightPlan=null;return;}
        if(input.reason()==null||input.reason().isBlank()||input.reason().length()>300
            ||input.until()<=s.minute||input.until()>s.minute+720)
            throw Identity.error(HttpStatus.BAD_REQUEST,"A night plan needs a reason and a future end time within twelve hours; recovery sleep may extend beyond your preferred wake time");
        a.nightPlan=new NightPlan(input.reason().strip(),input.until());
        a.bedtimeRoutine=false;
    }
    private Receipt recordAction(State s,String id,Command input) {
        return recordAction(s,id,input,"");
    }
    private Receipt recordAction(State s,String id,Command input,String notes) {
        var previous=store.db.queryForList("SELECT * FROM agent_actions WHERE agent_id=? AND request_id=?",id,input.requestId());
        if(!previous.isEmpty()) {
            var row=previous.getFirst();
            Boolean matches=store.db.queryForObject("SELECT jsonb_strip_nulls(command)=jsonb_strip_nulls(?::jsonb) FROM agent_actions WHERE id=?",Boolean.class,store.encode(input),row.get("id"));
            if(!Boolean.TRUE.equals(matches)) throw Identity.error(HttpStatus.CONFLICT,"Request ID was already used for a different command");
            return receipt(row);
        }
        Resident a=s.agents.get(id);String actionId=UUID.randomUUID().toString();Engine.Effects effects=new Engine.Effects();
        String result,status;
        try {
            if(List.of(ActionType.TALK,ActionType.CALL_OUT,ActionType.PAUSE_TRAVEL,ActionType.PROPOSE_PLAN).contains(input.action())&&!Boolean.TRUE.equals(availability(s).get(input.target())))throw new Engine.Invalid("This resident is unavailable for conversation; no reply has been received. Choose another activity");
            result=Engine.apply(s,a,input,actionId,effects);status=a.pending!=null&&actionId.equals(a.pending.id)?"accepted":"completed";
        }
        catch(Engine.Invalid e) { result=e.getMessage();status="rejected"; }
        result+=notes;
        store.db.update("INSERT INTO agent_actions VALUES(?,?,?,?::jsonb,?,?,?)",actionId,id,input.requestId(),store.encode(input),status,result,s.minute);
        store.effects(s,effects);store.save(s);return new Receipt(actionId,status,result,s.minute);
    }
    public Receipt result(String agent,String id) {
        var rows=store.db.queryForList("SELECT * FROM agent_actions WHERE agent_id=? AND id=?",agent,id);
        if(rows.isEmpty()) throw Identity.error(HttpStatus.NOT_FOUND,"Action not found");return receipt(rows.getFirst());
    }
    private Receipt receipt(Map<String,Object> row) { return new Receipt((String)row.get("id"),(String)row.get("status"),(String)row.get("result"),((Number)row.get("world_minute")).longValue()); }
    public void usage(String id,Usage usage) { store.db.update("INSERT INTO runtime_usage VALUES(?,?,?) ON CONFLICT DO NOTHING",id,usage.requestId(),usage.tokens()); }
    @Transactional public void relationship(String id,Relationship input) {
        State s=store.read(true);Resident a=s.agents.get(id);
        if(!s.agents.containsKey(input.target())||id.equals(input.target())) throw Identity.error(HttpStatus.BAD_REQUEST,"Unknown other agent");
        if(store.events(0,1,id).isEmpty()) throw Identity.error(HttpStatus.BAD_REQUEST,"No recorded experience yet");
        a.relationships.put(input.target(),input.label());store.save(s);
    }
}

