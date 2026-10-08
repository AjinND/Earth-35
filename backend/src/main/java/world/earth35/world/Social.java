package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Encounter opportunities and invitations; each resident chooses their own response. */
public final class Social {
    private Social() {}
    public record Encounter(String other,String name,String relationship,boolean metBefore,double distance,
                            boolean available,String conversationStatus,boolean decisionNeeded,String kind) {
        public Encounter(String other,String name,String relationship,boolean metBefore,double distance,
                         boolean available,String conversationStatus,boolean decisionNeeded) {
            this(other,name,relationship,metBefore,distance,available,conversationStatus,decisionNeeded,"familiar");
        }
    }
    public record Situation(List<Encounter> encounters,List<SocialPlan> plans,List<PublicCall> calls,boolean reconsider) {
        public Situation(List<Encounter> encounters,List<SocialPlan> plans,boolean reconsider) {
            this(encounters,plans,List.of(),reconsider);
        }
    }
    static boolean available(Resident a) {
        return a!=null&&!a.paused&&!Engine.busy(a);
    }
    static boolean known(State s,Resident a,Resident b) {
        return a.relationships.containsKey(b.id)||s.conversations.containsKey(Conversations.key(a.id,b.id));
    }
    public static boolean outdoors(State s,Resident a) {
        return a!=null&&!inVehicle(a)&&room(s,a)==null;
    }
    private static Building room(State s,Resident a) {
        Building location=s.buildings.get(a.location);
        if(Places.enclosed(location))return location;
        // Walking changes location to road before the resident has actually crossed the front door.
        if(Travel.moving(a)&&a.pending.exitDoor!=null)
            return s.buildings.values().stream().filter(Places::enclosed)
                .filter(b->Math.abs(a.x-b.x)<3.35&&Math.abs(a.z-b.z)<3.35).findFirst().orElse(null);
        return null;
    }
    private static boolean inVehicle(Resident a) {
        return a.pending!=null&&List.of("BOARD_BUS","RIDE_BUS").contains(a.pending.type);
    }
    /** Residents can perceive their shared room or unobstructed outdoor space, never through walls. */
    public static boolean perceivable(State s,Resident a,Resident b) {
        if(a==null||b==null||a.id.equals(b.id)||inVehicle(a)||inVehicle(b))return false;
        Building aRoom=room(s,a),bRoom=room(s,b);
        if(aRoom!=null||bRoom!=null)return aRoom!=null&&bRoom!=null&&Objects.equals(aRoom.id,bRoom.id);
        return s.buildings.values().stream().filter(Places::enclosed)
            .noneMatch(building->blocksSight(a,b,building));
    }
    private static boolean blocksSight(Resident a,Resident b,Building building) {
        double lower=0,upper=1;
        double[] origin={a.x,a.z},delta={b.x-a.x,b.z-a.z},center={building.x,building.z};
        for(int axis=0;axis<2;axis++) {
            double min=center[axis]-3.5,max=center[axis]+3.5;
            if(Math.abs(delta[axis])<.000001) {
                if(origin[axis]<=min||origin[axis]>=max)return false;
            } else {
                double first=(min-origin[axis])/delta[axis],last=(max-origin[axis])/delta[axis];
                lower=Math.max(lower,Math.min(first,last));upper=Math.min(upper,Math.max(first,last));
                if(lower>=upper)return false;
            }
        }
        return lower<upper&&upper>0&&lower<1;
    }
    public static List<Encounter> encounters(State s,Resident a,Map<String,Boolean> availability) {
        return s.agents.values().stream().filter(b->perceivable(s,a,b))
            .filter(b->Engine.distance(a.x,a.z,b.x,b.z)<=20).map(b->{
                Conversation c=s.conversations.get(Conversations.key(a.id,b.id));
                boolean ready=available(b)&&availability.getOrDefault(b.id,true);
                boolean incoming=c!=null&&!Conversations.closed(s,c)&&!a.id.equals(c.lastSpeaker)
                    &&c.turnSequence>a.encounterReviewed.getOrDefault("turn:"+Conversations.key(a.id,b.id),-1L);
                boolean due=ready&&(incoming||s.minute>=a.encounterReviewed.getOrDefault(b.id,-30L)+30);
                return new Encounter(b.id,b.name,a.relationships.get(b.id),c!=null,
                    Math.round(Engine.distance(a.x,a.z,b.x,b.z)*10)/10.0,ready,
                    c==null?"new":Conversations.status(s,a,c,ready),due,
                    Engine.distance(a.x,a.z,b.x,b.z)<=3?"near_passing":known(s,a,b)?"familiar":"noticed");
            }).toList();
    }
    public static Situation situation(State s,Resident a,Map<String,Boolean> availability) {
        List<Encounter> encounters=encounters(s,a,availability);List<SocialPlan> plans=plans(s,a);
        boolean reconsider=Travel.moving(a)&&(a.pending.socialUntil>s.minute
            ||encounters.stream().anyMatch(Encounter::decisionNeeded)
            ||PublicEncounters.decisionNeeded(s,a)
            ||plans.stream().anyMatch(p->p.expires>s.minute
                &&s.minute>=a.encounterReviewed.getOrDefault("plan:"+p.id,-15L)+15
                &&(p.status.equals("proposed")&&p.invited.equals(a.id)
                    ||p.status.equals("accepted")&&s.minute>=p.meetingMinute-15&&!p.id.equals(a.pending.socialPlanId))));
        return new Situation(encounters,plans,PublicEncounters.calls(s,a),reconsider);
    }
    public static void reviewed(State s,Resident a) {
        PublicEncounters.reviewResponses(s,a);
        for(Encounter encounter:encounters(s,a,Map.of())) {
            a.encounterReviewed.put(encounter.other,s.minute);
            String key=Conversations.key(a.id,encounter.other);Conversation conversation=s.conversations.get(key);
            if(conversation!=null)a.encounterReviewed.put("turn:"+key,conversation.turnSequence);
        }
        for(SocialPlan plan:plans(s,a))a.encounterReviewed.put("plan:"+plan.id,s.minute);
    }
    static void pause(State s,Resident a,Resident other,int minutes) {
        Engine.require(Travel.moving(a),"There is no walking journey to pause");
        Engine.require(minutes>=1&&minutes<=15,"Pause for one to fifteen minutes");
        Engine.together(a,other);Engine.require(available(other),"This resident is unavailable for conversation");
        Engine.require(!onRoad(s,a),"Clear the road before stopping to chat");
        a.pending.socialWith=other.id;a.pending.socialUntil=s.minute+minutes;
        a.pending.waitingReason="Paused to chat with "+other.name;
        reviewed(s,a);
    }
    static boolean onRoad(Resident a) {
        return onRoad(null,a);
    }
    static boolean onRoad(State s,Resident a) {return CityLayout.of(s).onRoad(a.x,a.z);}
    public static void end(State s,Resident a) {
        Conversations.end(s,a);
        if(Travel.moving(a)) {
            a.pending.socialWith=null;a.pending.socialUntil=0;a.pending.waitingReason=null;
        }
        reviewed(s,a);
    }
    public static List<SocialPlan> plans(State s,Resident a) {
        return s.socialPlans.values().stream().filter(p->p.proposer.equals(a.id)||p.invited.equals(a.id))
            .sorted(Comparator.comparingLong((SocialPlan p)->p.meetingMinute).reversed()).limit(20).toList();
    }
    static void propose(State s,Resident a,String otherId,String destination,String activity,int delay,String id,Engine.Effects e) {
        Resident other=s.agents.get(otherId);Engine.together(a,other);
        Engine.require(available(other),"This resident is unavailable for a plan invitation");
        Building b=s.buildings.get(destination);
        Engine.require(b!=null&&b.social,"Choose a public gathering place for your shared plan");
        Engine.require(activity!=null&&!activity.isBlank(),"Describe the activity you are proposing");
        Engine.require(delay>=1&&delay<=100,"Choose a meeting one to one hundred minutes from now");
        Engine.require(s.socialPlans.values().stream().noneMatch(p->p.expires>s.minute
            &&List.of("proposed","accepted").contains(p.status)
            &&Conversations.key(p.proposer,p.invited).equals(Conversations.key(a.id,otherId))),"An active plan already exists with this resident");
        SocialPlan p=new SocialPlan();p.id=id;p.proposer=a.id;p.invited=otherId;p.destination=destination;
        p.activity=activity.strip();p.meetingMinute=s.minute+delay;p.expires=p.meetingMinute+60;s.socialPlans.put(id,p);
        e.facts.add(new Engine.Fact("social_plan",a.id,otherId,a.name+" invited "+other.name+" to "+b.name+" at "+NightRoutine.timeOfDay(p.meetingMinute)+": "+p.activity));
        reviewed(s,a);
    }
    static SocialPlan plan(State s,Resident a,String id) {
        SocialPlan p=s.socialPlans.get(id);
        Engine.require(p!=null&&(a.id.equals(p.proposer)||a.id.equals(p.invited)),"Choose one of your own shared plans");
        Engine.require(p.expires>s.minute&&List.of("proposed","accepted").contains(p.status),"This plan is no longer active");
        return p;
    }
    static void respond(State s,Resident a,String id,boolean accept,Engine.Effects e) {
        SocialPlan p=plan(s,a,id);
        Engine.require(a.id.equals(p.invited)&&p.status.equals("proposed"),"Only the invited resident can answer a proposed plan");
        p.status=accept?"accepted":"declined";
        e.facts.add(new Engine.Fact("social_plan",a.id,p.proposer,a.name+(accept?" accepted":" declined")+" the invitation to "+s.buildings.get(p.destination).name+"."));
        reviewed(s,a);
    }
    static void cancel(State s,Resident a,String id,Engine.Effects e) {
        SocialPlan p=plan(s,a,id);p.status="cancelled";
        e.facts.add(new Engine.Fact("social_plan",a.id,a.id.equals(p.proposer)?p.invited:p.proposer,a.name+" cancelled the shared plan at "+s.buildings.get(p.destination).name+"."));
    }
    static void tick(State s,Engine.Effects e) {
        for(SocialPlan p:s.socialPlans.values()) {
            if(!List.of("proposed","accepted").contains(p.status))continue;
            Resident a=s.agents.get(p.proposer),b=s.agents.get(p.invited);
            if(p.status.equals("accepted")&&s.minute>=p.meetingMinute&&available(a)&&available(b)
                &&p.destination.equals(a.location)&&p.destination.equals(b.location)) {
                p.status="completed";
                e.facts.add(new Engine.Fact("social_plan",a.id,b.id,a.name+" and "+b.name+" met at "+s.buildings.get(p.destination).name+" for their agreed plan: "+p.activity));
            } else if(s.minute>=p.expires) {
                p.status="expired";
                e.facts.add(new Engine.Fact("social_plan",p.proposer,p.invited,"The shared plan at "+s.buildings.get(p.destination).name+" expired."));
            }
        }
        s.socialPlans.values().removeIf(p->p.expires<s.minute-1440&&!List.of("proposed","accepted").contains(p.status));
    }
}
