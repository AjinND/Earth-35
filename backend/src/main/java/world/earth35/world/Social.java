package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Encounter opportunities and invitations; each resident chooses their own response. */
public final class Social {
    private Social() {}
    // Legacy observation markers only; residents may choose to keep talking beyond them.
    public static final int INTERRUPTION_MINUTES=3,INTERRUPTION_TURNS=2;
    public record Encounter(String other,String name,String relationship,boolean metBefore,double distance,
                            boolean available,String conversationStatus,boolean decisionNeeded,String kind) {
        public Encounter(String other,String name,String relationship,boolean metBefore,double distance,
                         boolean available,String conversationStatus,boolean decisionNeeded) {
            this(other,name,relationship,metBefore,distance,available,conversationStatus,decisionNeeded,"familiar");
        }
    }
    public record Commitment(String plan,String other,String otherName,String destination,String destinationName,
                             String activity,long meetingMinute,long expires,int priority,boolean due,String progress,
                             String nextAction,Long interruptionStartedMinute,Long interruptionUntil,int interruptionTurns,
                             int remainingInterruptionTurns,boolean returnDue) {}
    public record Situation(List<Encounter> encounters,List<SocialPlan> plans,List<PublicCall> calls,
                            List<Commitment> commitments,boolean reconsider,List<SocialExperience> sharedExperiences,
                            Map<String,Integer> sharedExperienceCounts) {
        public Situation(List<Encounter> encounters,List<SocialPlan> plans,List<PublicCall> calls,List<Commitment> commitments,boolean reconsider) {
            this(encounters,plans,calls,commitments,reconsider,List.of(),Map.of());
        }
        public Situation(List<Encounter> encounters,List<SocialPlan> plans,List<PublicCall> calls,boolean reconsider) {
            this(encounters,plans,calls,List.of(),reconsider);
        }
        public Situation(List<Encounter> encounters,List<SocialPlan> plans,boolean reconsider) {
            this(encounters,plans,List.of(),List.of(),reconsider);
        }
    }
    static boolean available(Resident a) {
        return a!=null&&!a.paused&&!Engine.busy(a);
    }
    public static boolean conversationAvailable(Resident a) {
        return available(a)&&(a.pending==null||a.pending.activityPaused||Travel.moving(a)||List.of("REST","PLAN_WAIT").contains(a.pending.type));
    }
    static boolean known(State s,Resident a,Resident b) {
        return a.relationships.containsKey(b.id)||s.conversations.containsKey(Conversations.key(a.id,b.id))
            ||a.sharedExperienceCounts.getOrDefault(b.id,0)>0;
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
        return s.buildings.values().stream().filter(Places::enclosed).filter(building->!"construction".equals(building.type))
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
                boolean ready=conversationAvailable(b)&&availability.getOrDefault(b.id,true);
                boolean incoming=c!=null&&!Conversations.closed(s,c)&&!a.id.equals(c.lastSpeaker)
                    &&c.turnSequence>a.encounterReviewed.getOrDefault("turn:"+Conversations.key(a.id,b.id),-1L);
                boolean due=ready&&(incoming||s.minute>=a.encounterReviewed.getOrDefault(b.id,-30L)+30);
                return new Encounter(b.id,b.name,a.relationships.get(b.id),c!=null||a.sharedExperienceCounts.getOrDefault(b.id,0)>0,
                    Math.round(Engine.distance(a.x,a.z,b.x,b.z)*10)/10.0,ready,
                    c==null?"new":Conversations.status(s,a,c,ready),due,
                    Engine.distance(a.x,a.z,b.x,b.z)<=3?"near_passing":known(s,a,b)?"familiar":"noticed");
            }).toList();
    }
    public static Situation situation(State s,Resident a,Map<String,Boolean> availability) {
        List<Encounter> encounters=encounters(s,a,availability);List<SocialPlan> plans=plans(s,a);
        List<Commitment> commitments=commitments(s,a);
        boolean reconsider=(Travel.moving(a)||a.pending!=null&&List.of("REST","PLAN_WAIT").contains(a.pending.type))&&(a.pending.socialUntil>s.minute
            ||encounters.stream().anyMatch(Encounter::decisionNeeded)
            ||PublicEncounters.decisionNeeded(s,a)
            ||plans.stream().anyMatch(p->p.expires>s.minute
                &&s.minute>=a.encounterReviewed.getOrDefault("plan:"+p.id,-15L)+15
                &&p.status.equals("proposed")&&p.invited.equals(a.id))
            ||commitments.stream().anyMatch(c->c.priority()==1&&c.due()&&!c.progress().equals("at_destination")
                &&(c.nextAction().equals("END_CHAT")||c.returnDue()&&a.pending.socialWith!=null
                    ||c.nextAction().equals("JOIN_PLAN")&&s.minute>=a.encounterReviewed.getOrDefault("plan:"+c.plan(),-15L)+15)));
        return new Situation(encounters,plans,PublicEncounters.calls(s,a),commitments,reconsider,List.copyOf(a.sharedExperiences),Map.copyOf(a.sharedExperienceCounts));
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
        interrupted(s,a,other,false);
        a.pending.socialWith=other.id;
        a.pending.socialUntil=s.minute+minutes;
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
        List<SocialPlan> own=s.socialPlans.values().stream().filter(p->p.proposer.equals(a.id)||p.invited.equals(a.id))
            .sorted(Comparator.comparingLong((SocialPlan p)->p.meetingMinute).reversed()).toList();
        // History limits must never hide an outstanding invitation or commitment.
        return java.util.stream.Stream.concat(own.stream().filter(p->active(s,p)),
            own.stream().filter(p->!active(s,p)).limit(20)).toList();
    }
    private static boolean active(State s,SocialPlan p) {
        return p.expires>s.minute&&List.of("proposed","accepted","in_progress").contains(p.status);
    }
    private static List<SocialPlan> accepted(State s,Resident a) {
        return s.socialPlans.values().stream().filter(p->(a.id.equals(p.proposer)||a.id.equals(p.invited))
            &&active(s,p)&&List.of("accepted","in_progress").contains(p.status))
            .sorted(Comparator.comparingLong((SocialPlan p)->p.meetingMinute).thenComparing(p->p.id)).toList();
    }
    private static boolean travelling(Resident a,SocialPlan p) {
        return a.pending!=null&&(Travel.moving(a)||List.of("WAIT_BUS","BOARD_BUS","RIDE_BUS").contains(a.pending.type))
            &&(p.id.equals(a.pending.socialPlanId)||p.destination.equals(a.pending.finalDestination)||p.destination.equals(a.pending.target));
    }
    private static boolean due(State s,Resident a,SocialPlan p) {
        if("in_progress".equals(p.status)||travelling(a,p))return true;
        int walking;
        try {walking=walkingMinutes(s,a,p.destination);}
        catch(Engine.Invalid|IllegalStateException blocked) {walking=15;}
        return s.minute>=p.meetingMinute-Math.max(15,walking+5);
    }
    public static List<Commitment> commitments(State s,Resident a) {
        List<Commitment> result=new ArrayList<>();
        for(SocialPlan p:accepted(s,a)) {
            String other=a.id.equals(p.proposer)?p.invited:p.proposer;
            Resident partner=s.agents.get(other);PlanTask task="in_progress".equals(p.status)?p.tasks.get(a.id):null;
            String target=task==null?p.destination:task.destination();Building destination=Travel.destination(s,target);
            boolean arrived=target.equals(a.location),travelling=travelling(a,p),due=due(s,a,p);
            CommitmentInterruption interruption=a.commitmentInterruptions.get(p.id);
            Long until=interruption==null?null:interruption.startedMinute+INTERRUPTION_MINUTES;
            int turns=interruption==null?0:interruption.turns;
            boolean doing=a.pending!=null&&p.id.equals(a.pending.socialPlanId)&&Objects.equals(a.pending.id,p.activityActions.get(a.id));
            String next=p.finished.contains(a.id)?"WAIT":Conversations.active(s,a)?"END_CHAT":doing?"CONTINUE_ACTIVITY":
                arrived?task==null?"WAIT":task.action()==null?"DO_ACTIVITY":task.action():travelling?"CONTINUE_TRAVEL":task==null?"JOIN_PLAN":"MOVE";
            result.add(new Commitment(p.id,other,partner==null?other:partner.name,target,
                destination==null?target:destination.name,task==null?p.activity:task.activity(),p.meetingMinute,p.expires,result.size()+1,due,
                doing?"doing_activity":p.finished.contains(a.id)?"finished":arrived?"at_destination":travelling?"travelling":"pending",next,
                interruption==null?null:interruption.startedMinute,until,turns,Math.max(0,INTERRUPTION_TURNS-turns),
                due&&!arrived&&interruption!=null&&(s.minute>=until||turns>=INTERRUPTION_TURNS)));
        }
        return List.copyOf(result);
    }
    /** Track time spent away from a due agreement across people, chats and restarts. */
    private static SocialPlan interruptionPlan(State s,Resident a,Resident other) {
        SocialPlan p=accepted(s,a).stream().findFirst().orElse(null);
        PlanTask task=p!=null&&"in_progress".equals(p.status)?p.tasks.get(a.id):null;
        String target=task==null?p==null?null:p.destination:task.destination();
        return p==null||!due(s,a,p)||target.equals(a.location)||p.finished.contains(a.id)
            ||other.id.equals(a.id.equals(p.proposer)?p.invited:p.proposer)?null:p;
    }
    static void interrupted(State s,Resident a,Resident other,boolean spoke) {
        SocialPlan p=interruptionPlan(s,a,other);if(p==null)return;
        CommitmentInterruption interruption=a.commitmentInterruptions.computeIfAbsent(p.id,id->{
            CommitmentInterruption created=new CommitmentInterruption();created.startedMinute=s.minute;return created;
        });
        if(spoke)interruption.turns++;
    }
    static void propose(State s,Resident a,String otherId,String destination,String activity,int delay,String id,Engine.Effects e) {
        Resident other=s.agents.get(otherId);Engine.together(a,other);
        Engine.require(conversationAvailable(other),"This resident is unavailable for a plan invitation");
        Building b=Travel.destination(s,destination);
        Engine.require(b!=null&&(b.social||destination.startsWith("point:")),"Choose a public gathering place or dry island point for your shared plan");
        requireDestination(s,a,destination);requireDestination(s,other,destination);
        Engine.require(activity!=null&&!activity.isBlank(),"Describe the activity you are proposing");
        Engine.require(delay>=1&&delay<=100,"Choose a meeting one to one hundred minutes from now");
        Engine.require(s.socialPlans.values().stream().noneMatch(p->p.expires>s.minute
            &&List.of("proposed","accepted","in_progress").contains(p.status)
            &&Conversations.key(p.proposer,p.invited).equals(Conversations.key(a.id,otherId))),"An active plan already exists with this resident");
        SocialPlan p=new SocialPlan();p.id=id;p.proposer=a.id;p.invited=otherId;p.destination=destination;
        p.activity=activity.strip();p.meetingMinute=s.minute+Math.max(delay,Math.max(walkingMinutes(s,a,destination),walkingMinutes(s,other,destination))+5);
        p.expires=p.meetingMinute+60;
        p.tasks.put(a.id,new PlanTask(a.id,destination,p.activity,15));
        p.tasks.put(other.id,new PlanTask(other.id,destination,p.activity,15));s.socialPlans.put(id,p);
        Relationships.record(s,"plan:"+id+":proposed","plan_proposed",a.id,otherId,a.name+" invited "+other.name+" to "+b.name+": "+p.activity);
        e.facts.add(new Engine.Fact("social_plan",a.id,otherId,a.name+" invited "+other.name+" to "+b.name+" at "+NightRoutine.timeOfDay(p.meetingMinute)+": "+p.activity));
        reviewed(s,a);
    }
    static SocialPlan plan(State s,Resident a,String id) {
        SocialPlan p=s.socialPlans.get(id);
        Engine.require(p!=null&&(a.id.equals(p.proposer)||a.id.equals(p.invited)),"Choose one of your own shared plans");
        Engine.require(p.expires>s.minute&&List.of("proposed","accepted","in_progress").contains(p.status),"This plan is no longer active");
        return p;
    }
    static void respond(State s,Resident a,String id,boolean accept,Engine.Effects e) {
        SocialPlan p=plan(s,a,id);
        Engine.require(a.id.equals(p.invited)&&p.status.equals("proposed"),"Only the invited resident can answer a proposed plan");
        p.status=accept?"accepted":"declined";
        Relationships.record(s,"plan:"+id+":"+p.status,"plan_"+p.status,a.id,p.proposer,
            a.name+(accept?" accepted":" declined")+" the invitation for "+p.activity+".");
        e.facts.add(new Engine.Fact("social_plan",a.id,p.proposer,a.name+(accept?" accepted":" declined")+" the invitation to "+destinationName(s,p.destination)+"."));
        reviewed(s,a);
    }
    static void cancel(State s,Resident a,String id,Engine.Effects e) {
        SocialPlan p=plan(s,a,id);p.status="cancelled";
        Relationships.record(s,"plan:"+id+":cancelled","plan_cancelled",a.id,a.id.equals(p.proposer)?p.invited:p.proposer,
            a.name+" cancelled the plan for "+p.activity+".");
        PlanExecution.release(s,p,e,"Shared plan cancelled");
        e.facts.add(new Engine.Fact("social_plan",a.id,a.id.equals(p.proposer)?p.invited:p.proposer,a.name+" cancelled the shared plan at "+destinationName(s,p.destination)+"."));
    }
    static void tick(State s,Engine.Effects e) {
        PlanExecution.tick(s,e);
        for(SocialPlan p:s.socialPlans.values()) {
            if(!List.of("proposed","accepted","in_progress").contains(p.status))continue;
            Resident a=s.agents.get(p.proposer),b=s.agents.get(p.invited);
            if(p.tasks.isEmpty()&&p.status.equals("accepted")&&s.minute>=p.meetingMinute&&available(a)&&available(b)
                &&p.destination.equals(a.location)&&p.destination.equals(b.location)) {
                p.status="completed";
                p.completedMinute=s.minute;
                Relationships.record(s,"plan:"+p.id+":completed","plan_completed",a.id,b.id,
                    a.name+" and "+b.name+" met at "+destinationName(s,p.destination)+" for "+p.activity+".");
                e.facts.add(new Engine.Fact("social_plan",a.id,b.id,a.name+" and "+b.name+" met at "+destinationName(s,p.destination)+" for their agreed plan: "+p.activity));
            } else if(s.minute>=p.expires) {
                p.status="expired";
                Relationships.record(s,"plan:"+p.id+":expired","plan_expired",p.proposer,p.invited,
                    "The plan for "+p.activity+" expired without a recorded shared completion; the reason is unknown.");
                PlanExecution.release(s,p,e,"Shared plan expired");
                e.facts.add(new Engine.Fact("social_plan",p.proposer,p.invited,"The shared plan at "+destinationName(s,p.destination)+" expired."));
            }
        }
        s.socialPlans.values().removeIf(p->p.expires<s.minute-1440&&!List.of("proposed","accepted","in_progress").contains(p.status));
        for(Resident a:s.agents.values())a.commitmentInterruptions.keySet().removeIf(id->{
            SocialPlan p=s.socialPlans.get(id);return p==null||!active(s,p);
        });
    }
    public static String destinationName(State s,String destination) {
        Building b=Travel.destination(s,destination);return b==null?destination:b.name;
    }
    static void requireDestination(State s,Resident a,String destination) {
        Building b=Travel.destination(s,destination);
        Engine.require(b!=null,"Choose a known place or valid dry island point");
        Engine.require(!destination.startsWith("point:")||s.buildings.values().stream().noneMatch(place->Places.enclosed(place)
            &&Math.abs(b.x-place.x)<3.5&&Math.abs(b.z-place.z)<3.5),"Choose dry land outside building footprints");
        Engine.require(!destination.startsWith("point:")||s.worldObjects.values().stream().noneMatch(object->Math.abs(b.x-object.x)<1&&Math.abs(b.z-object.z)<1),"Choose a destination clear of sign footprints");
        Engine.require(b.social||destination.startsWith("point:"),"Choose a public gathering place or dry island point");
        try {Travel.path(s,a,b,"shortest");}
        catch(IllegalStateException blocked) {throw new Engine.Invalid("No available route to the shared-plan destination");}
    }
    public static int walkingMinutes(State s,Resident a,String target) {
        if(target.equals(a.location))return 0;
        Building destination=Travel.destination(s,target);if(destination==null)return 0;
        List<double[]> path=Travel.path(s,a,destination,"shortest");CityLayout layout=CityLayout.of(s);
        double indoor=Places.enclosed(destination)?Math.hypot(destination.x-Places.entrance(destination).x(),destination.z-Places.entrance(destination).z()):0;
        double x=a.x,z=a.z;int crossings=0;
        for(double[] point:path) {
            crossings+=(int)(Math.abs(point[0]-x)/layout.spacing())+(int)(Math.abs(point[1]-z)/layout.spacing());x=point[0];z=point[1];
        }
        return (int)Math.ceil((Travel.routeEffort(s,a,path)+indoor)/layout.walkingSpeed())+crossings*3;
    }
    /** A real departure receives enough time to finish its route; repeated reviews cannot renew it. */
    static void joined(State s,Resident a,SocialPlan p) {
        if(p.joined.add(a.id))p.expires=Math.max(p.expires,s.minute+walkingMinutes(s,a,p.destination)+60);
    }
}
