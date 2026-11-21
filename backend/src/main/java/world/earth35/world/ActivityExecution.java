package world.earth35.world;

import java.util.*;
import java.util.regex.Pattern;
import world.earth35.world.State.*;

/** Measured local activity, with physical search steps and no inferred world effects. */
public final class ActivityExecution {
    private ActivityExecution() {}
    private static final Set<String> MODES=Set.of("search","walk","observe","stationary");
    private static final Pattern SEARCH=Pattern.compile("\\b(?:search(?:ing)?|find|finding|look(?:ing)? for|explor(?:e|ing))\\b");
    private static final Pattern WALK=Pattern.compile("\\b(?:walk(?:ing)?|stroll(?:ing)?|jog(?:ging)?|hik(?:e|ing))\\b");
    private static final Pattern OBSERVE=Pattern.compile("^(?:observe|watch|look at)\\b");
    private static final Pattern INSPECT=Pattern.compile("\\b(?:check(?:ing)?|inspect(?:ing)?|assess(?:ing)?|survey(?:ing)?|examin(?:e|ing)|evaluat(?:e|ing)|measur(?:e|ing)|scout(?:ing)?)\\b");
    private static final Pattern GROUNDS=Pattern.compile("\\b(?:sunlight|sun exposure|direct sun|shade|shadows?|drainage|water access|soil|garden|site|plot|grounds|terrain|trail|park|plants?|flowers?)\\b");
    private static final Pattern SUNLIGHT=Pattern.compile("\\b(?:sunlight|sun exposure|direct sun|shade|shadows?)\\b");
    private static final Pattern DISCUSSION=Pattern.compile("^(?:i (?:will|want to|plan to) )?(?:spend(?:ing)? (?:\\d+ minutes?|some time|time) )?(?:discuss(?:ing)?|talk(?:ing)?|chat(?:ting)?|brainstorm(?:ing)?|plan(?:ning)?|review(?:ing)?|reflect(?:ing)?|think(?:ing)?|read(?:ing)?|writ(?:e|ing)|sketch(?:ing)?|draw(?:ing)?|study(?:ing)?|compar(?:e|ing) notes)\\b");
    private static final Pattern RECORDS=Pattern.compile("\\b(?:notes|memories|ideas?|thoughts|online|internet|catalog|records|documents|files)\\b");
    private static final Pattern UNSUPPORTED=Pattern.compile("^(?:i (?:will|want to|plan to) )?(?:collect|harvest|pick|plant|build|construct|buy|sell|purchase|produce|manufacture|install|research|borrow|pay|transfer|read (?:a |the )?book|move (?:a |the )?(?:sign|object))\\b");

    @io.swagger.v3.oas.annotations.media.Schema(name="ActivityExecutionProgress")
    public static class Progress {
        public String mode,phase,label,location,resumePhase;
        public double originX,originZ,radius,distance;
        public int requestedMinutes,activeMinutes,inspectedStops,nextStop;
        public long lastMinute;
        public List<double[]> stops=new ArrayList<>();
        public Set<String> inspected=new LinkedHashSet<>();
        public int walkVersion;
        public double entryX,entryZ;
        public String walkLeader;
        public List<String> walkCompanions=new ArrayList<>();
        public List<double[]> walkApproach=new ArrayList<>();
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="ActivityProgress")
    public record View(String mode,String phase,String label,int activeMinutes,int requestedMinutes,int inspectedStops,double distance) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="ActivityOutcome")
    public record Outcome(String id,String label,String location,String mode,int activeMinutes,int inspectedStops,double distance,long minute) {}

    public static View view(Resident resident) {
        Progress p=resident==null||resident.pending==null?null:resident.pending.activityExecution;
        return p==null?null:new View(p.mode,p.phase,p.label,p.activeMinutes,p.requestedMinutes,p.inspectedStops,p.distance);
    }
    /** Physical inspection labels require measured search steps even if a caller requests stationary execution. */
    public static String mode(String item,String label) {
        String text=label==null?"":label.strip().toLowerCase(Locale.ROOT);
        if(item!=null&&!item.isBlank()) {
            String selected=item.strip().toLowerCase(Locale.ROOT);
            Engine.require(MODES.contains(selected),"DO_ACTIVITY item must be search, walk, observe or stationary");
            if(inspection(text))return "search";
            return selected;
        }
        if(inspection(text))return "search";
        if(OBSERVE.matcher(text).find())return "observe";
        if(SEARCH.matcher(text).find())return "search";
        if(WALK.matcher(text).find())return "walk";
        return "stationary";
    }
    private static boolean inspection(String text) {
        return !DISCUSSION.matcher(text).find()&&(INSPECT.matcher(text).find()&&GROUNDS.matcher(text).find()
            ||SEARCH.matcher(text).find()&&!RECORDS.matcher(text).find());
    }
    private static boolean needsDaylight(String label) {
        String text=label==null?"":label.strip().toLowerCase(Locale.ROOT);
        return SUNLIGHT.matcher(text).find()&&!DISCUSSION.matcher(text).find();
    }
    private static boolean sunlightAt(long minute) {
        // Match the rendered sun's shared-time path: above the horizon between 06:00 and 18:00.
        long time=Math.floorMod(minute,1440);return time>=360&&time<1080;
    }
    private static void requireConditions(State s,Resident resident,String label,int minutes) {
        Engine.require(!needsDaylight(label)||sunlightAt(s.minute)&&Math.floorMod(s.minute,1440)+minutes<=1080,
            "Sunlight and shade inspections need daylight (06:00–18:00) for the whole activity; return during daylight or discuss the plan instead");
        for(Resident companion:ActivityGrounding.activityCompanions(s,resident,label))Engine.require(ActivityGrounding.present(s,resident,companion),
            companion.name+" is not physically present nearby. Meet them first, or describe an activity you will do alone; mentioning a name does not establish attendance");
    }
    public static String start(State s,Resident resident,String id,String target,String label,String item,int minutes,Engine.Effects effects) {
        Engine.require(label!=null&&!label.isBlank(),"Describe the activity you will actually spend time doing");
        Engine.require(minutes>=1&&minutes<=100,"Choose an activity lasting one to one hundred minutes");
        Engine.require(target==null||target.isBlank()||target.equals(resident.location),"Move to the activity destination first");
        String text=label.strip();
        Engine.require(!UNSUPPORTED.matcher(text.toLowerCase(Locale.ROOT)).find(),
            "DO_ACTIVITY cannot perform that operation. Use the supported world action (for example BUILD_CONSTRUCTION, PLACE_OBJECT, READ_BOOK, RESEARCH_PROJECT, BUY or PRODUCE_PRODUCT); plant harvesting and planting are not supported");
        Progress progress=initialize(s,resident,text,item,minutes);
        Pending pending=Engine.pending(id,"DO_ACTIVITY",resident.location,s.minute+minutes);
        pending.startedAt=s.minute;pending.detail=text;pending.activityExecution=progress;
        if(moving(progress))pending.route=progress.mode.equals("walk")?copy(progress.walkApproach):route(s,resident,progress,progress.stops.getFirst());
        resident.pending=pending;
        effects.facts.add(new Engine.Fact("daily_life",resident.id,resident.location,resident.name+" began an activity: "+text
            +". Execution: "+progress.mode+(moving(progress)?"; moving to a nearby "+(progress.mode.equals("search")?"inspection stop":"waypoint"):
                "; only time spent "+(progress.mode.equals("observe")?"observing":"at this location")+" is recorded")+"."));
        return "Activity accepted: "+progress.mode+"; completion requires "+minutes+" executed minutes";
    }
    private static Progress initialize(State s,Resident resident,String label,String item,int minutes) {
        requireConditions(s,resident,label,minutes);
        Progress p=new Progress();p.mode=mode(item,label);p.label=label;p.location=resident.location;
        p.requestedMinutes=minutes;p.lastMinute=s.minute;p.originX=resident.x;p.originZ=resident.z;
        p.radius=s.geographyVersion>=2?6:4;
        Building grounds=Travel.destination(s,resident.location);
        if(grounds!=null&&grounds.outdoorRadius>0)p.radius=Math.min(p.radius,Math.max(.5,grounds.outdoorRadius*.5));
        p.phase=moving(p)?"travel":"perform";
        if(!moving(p))return p;
        Engine.require(!Places.enclosed(grounds),"Choose an outdoor place for a search or walk; use MOVE to reach it first");
        if(p.mode.equals("walk")) {
            WalkingRoute.Plan walk=WalkingRoute.plan(s,resident,p.location,minutes);
            Engine.require(!walk.points().isEmpty(),"No safe walking route is available through these grounds; move to an open outdoor area first");
            p.walkVersion=1;p.entryX=resident.x;p.entryZ=resident.z;
            p.originX=walk.centerX();p.originZ=walk.centerZ();p.radius=walk.radius();
            p.stops=copy(walk.points());p.walkApproach=copy(walk.approach());
            return p;
        }
        Engine.require(!p.mode.equals("search")||minutes>=2,"A search needs at least two minutes to move to a distinct stop and inspect it");
        Engine.require(allowed(s,p,resident.x,resident.z),"Stand on safe dry ground away from roads, buildings and signs before starting a search or walk");
        p.stops=stops(s,resident,p);
        Engine.require(!p.stops.isEmpty(),"No safe nearby activity route is available here; move to an open outdoor area first");
        return p;
    }
    private static boolean moving(Progress progress) {return progress.mode.equals("search")||progress.mode.equals("walk");}
    private static List<double[]> stops(State s,Resident resident,Progress progress) {
        List<double[]> result=new ArrayList<>();
        double rotation=Math.floorMod(Objects.hash(resident.id,progress.label),16)*Math.PI/8;
        // The first stop must be reachable in one active minute, including terrain effort.
        for(double radius:new double[]{Math.min(1.2,progress.radius*.4),.7,.4,.2}) {
            for(int i=0;i<16&&result.isEmpty();i++) {
                double angle=rotation+i*Math.PI/8;
                double[] point={progress.originX+radius*Math.sin(angle),progress.originZ+radius*Math.cos(angle)};
                if(clearSegment(s,progress,resident.x,resident.z,point[0],point[1])
                    &&Travel.segmentEffort(s,resident.x,resident.z,point[0],point[1])<=CityLayout.of(s).walkingSpeed()*.9)result.add(point);
            }
            if(!result.isEmpty())break;
        }
        if(result.isEmpty())return result;
        double[] previous=result.getFirst();
        for(int i=1;i<=24&&result.size()<8;i++) {
            double angle=rotation+i*Math.PI/6,radius=progress.radius*(i%2==0?.75:.45);
            double[] point={progress.originX+radius*Math.sin(angle),progress.originZ+radius*Math.cos(angle)};
            if(Math.hypot(point[0]-previous[0],point[1]-previous[1])<.4)continue;
            double[] first=result.getFirst();
            if(clearSegment(s,progress,previous[0],previous[1],point[0],point[1])
                &&clearSegment(s,progress,point[0],point[1],first[0],first[1])) {result.add(point);previous=point;}
        }
        // A second distinct point prevents a stationary fallback when only one direction is clear.
        if(result.size()==1)result.add(new double[]{progress.originX,progress.originZ});
        return result;
    }
    static boolean allowed(State s,Progress p,double x,double z) {
        if(p.mode.equals("walk")&&p.walkVersion>0)return WalkingRoute.allowed(s,p.location,p.originX,p.originZ,p.radius,x,z);
        if(!Double.isFinite(x)||!Double.isFinite(z)||Math.hypot(x-p.originX,z-p.originZ)>p.radius+1e-7)return false;
        Building grounds=Travel.destination(s,p.location);
        if(grounds!=null&&grounds.outdoorRadius>0&&Math.hypot(x-grounds.x,z-grounds.z)>grounds.outdoorRadius+1e-7)return false;
        if(s.geographyVersion>=2) {
            if(!IslandGeography.dryLand(x,z)||s.geographyVersion>=4&&!IslandLandscapes.clear(x,z)||IslandGeography.roadDistance(x,z)<2.2)return false;
        } else {
            CityLayout layout=CityLayout.of(s);
            if(Math.abs(x-layout.nearestRoad(x,true))<2.2||Math.abs(z-layout.nearestRoad(z,false))<2.2)return false;
        }
        for(Building building:s.buildings.values())if(Places.enclosed(building)&&Math.abs(x-building.x)<3.6&&Math.abs(z-building.z)<3.6)return false;
        for(WorldObjects.PlacedObject object:s.worldObjects.values())
            if(Math.abs(x-object.x)<WorldObjects.OBSTACLE_RADIUS+.25&&Math.abs(z-object.z)<WorldObjects.OBSTACLE_RADIUS+.25)return false;
        return true;
    }
    private static boolean clearSegment(State s,Progress p,double x,double z,double nx,double nz) {
        int samples=Math.max(1,(int)Math.ceil(Math.hypot(nx-x,nz-z)/.25));
        for(int i=0;i<=samples;i++)if(!allowed(s,p,x+(nx-x)*i/samples,z+(nz-z)*i/samples))return false;
        return true;
    }
    static List<double[]> route(State s,Resident resident,Progress progress,double[] destination) {
        if(progress.mode.equals("walk")&&progress.walkVersion>0)
            return WalkingRoute.path(s,progress.location,progress.originX,progress.originZ,progress.radius,resident.x,resident.z,destination);
        if(clearSegment(s,progress,resident.x,resident.z,destination[0],destination[1]))
            return new ArrayList<>(List.of(destination.clone()));
        if(s.geographyVersion>=2) {
            Building point=new Building("activity-waypoint", "Activity waypoint","wilderness","",destination[0],destination[1],1);
            try {
                List<double[]> route=IslandNavigation.path(s,resident,point);
                double x=resident.x,z=resident.z;
                for(double[] step:route) {
                    if(!clearSegment(s,progress,x,z,step[0],step[1]))return new ArrayList<>();
                    x=step[0];z=step[1];
                }
                return new ArrayList<>(route);
            } catch(Engine.Invalid|IllegalStateException unavailable) {return new ArrayList<>();}
        }
        return new ArrayList<>();
    }
    public static void tick(State s,Resident resident,Engine.Effects effects,List<Traffic.Vehicle> vehicles,List<Traffic.RoadMotion> traffic) {
        Pending pending=resident.pending;Progress p=pending.activityExecution;
        if(p==null) {
            // Old timers contain no physical evidence. Resume from this minute rather than crediting elapsed time.
            int requested=pending.startedAt>0&&pending.until-pending.startedAt>=1&&pending.until-pending.startedAt<=100?
                (int)(pending.until-pending.startedAt):15;
            try {
                p=initialize(s,resident,pending.detail==null?"Saved activity":pending.detail,null,requested);
                pending.activityExecution=p;pending.startedAt=s.minute;pending.until=s.minute+requested;
                if(moving(p))pending.route=p.mode.equals("walk")?copy(p.walkApproach):route(s,resident,p,p.stops.getFirst());
                effects.facts.add(new Engine.Fact("daily_life",resident.id,p.location,
                    resident.name+" resumed a saved activity with no recorded execution; progress starts now: "+p.label+"."));
            } catch(Engine.Invalid impossible) {
                effects.completions.add(new Engine.Completion(pending.id,"cancelled","Saved activity has no executed evidence: "+impossible.getMessage()));
                resident.pending=null;
            }
            return;
        }
        if(p.mode.equals("walk")&&p.walkVersion==0) {
            // Upgrade an in-flight central loop without teleporting or erasing real distance/time.
            try {
                Progress revised=initialize(s,resident,p.label,"walk",p.requestedMinutes);
                revised.activeMinutes=p.activeMinutes;revised.distance=p.distance;
                // All saved partners get a chance to prepare their route before anyone moves.
                revised.lastMinute=s.minute;
                pending.activityExecution=revised;pending.route=copy(revised.walkApproach);p=revised;
            } catch(Engine.Invalid unavailable) {cancel(resident,pending,effects,unavailable.getMessage());return;}
        }
        if(s.minute<=p.lastMinute)return;
        String currentInterruption=interruption(s,resident,p);
        if(currentInterruption!=null){cancel(resident,pending,effects,currentInterruption);return;}
        if(p.mode.equals("walk")&&CompanionWalk.tick(s,resident,effects,vehicles,traffic))return;
        pending.motion.clear();
        boolean consecutive=s.minute==p.lastMinute+1;p.lastMinute=s.minute;
        if(!consecutive) {pending.until=s.minute+p.requestedMinutes-p.activeMinutes;return;}
        if(resident.paused) {waitFor(pending,p,"Participation is paused");return;}
        boolean onGround=p.mode.equals("walk")?WalkingRoute.canStart(s,p.location,p.originX,p.originZ,p.radius,resident.x,resident.z):allowed(s,p,resident.x,resident.z);
        if(!Objects.equals(resident.location,p.location)||moving(p)&&!onGround) {
            effects.completions.add(new Engine.Completion(pending.id,"cancelled","Activity interrupted; resident left the safe activity grounds"));
            resident.pending=null;return;
        }
        Building place=Travel.destination(s,p.location);
        if(Places.enclosed(place)&&!Places.canEnter(s,resident,place,s.minute)) {waitFor(pending,p,"Activity place is closed");return;}
        if(p.phase.equals("blocked")) {p.phase=p.resumePhase==null?(moving(p)?"travel":"perform"):p.resumePhase;p.resumePhase=null;}
        if(!moving(p)) {
            p.phase="perform";
            p.activeMinutes++;pending.waitingReason=null;
        } else if(p.phase.equals("inspect")) {
            // One full tick at a reached physical stop is an inspection, with no species result inferred.
            double[] point=p.stops.get(p.nextStop);
            if(Math.hypot(resident.x-point[0],resident.z-point[1])>.001) {waitFor(pending,p,"Inspection stop has not been reached");return;}
            String key=point[0]+","+point[1];boolean newlyInspected=p.inspected.add(key);if(newlyInspected)p.inspectedStops++;
            p.activeMinutes++;
            pending.waitingReason=null;
            if(newlyInspected&&p.inspectedStops==1)effects.facts.add(new Engine.Fact("daily_life",resident.id,p.location,
                resident.name+" inspected the first reached nearby stop during: "+p.label+". No species discovery was verified."));
            p.nextStop=(p.nextStop+1)%p.stops.size();p.phase="travel";pending.route.clear();
        } else if(p.mode.equals("walk")) {
            // Carry unused walking effort through corners instead of stopping at each tiny waypoint.
            if(pending.route.isEmpty())pending.route=route(s,resident,p,p.stops.get(p.nextStop));
            if(pending.route.isEmpty()&&Math.hypot(resident.x-p.stops.get(p.nextStop)[0],resident.z-p.stops.get(p.nextStop)[1])>.001) {
                waitFor(pending,p,"Walking route is blocked; no physical progress recorded");return;
            }
            int appendFrom=(p.nextStop+1)%p.stops.size();
            if(pending.route.size()<=1)for(int i=0;i<p.stops.size();i++)pending.route.add(p.stops.get((appendFrom+i)%p.stops.size()).clone());
            Progress walking=p;
            double moved=Engine.walkRoute(s,resident,pending,vehicles,traffic,false,(x,z)->
                WalkingRoute.allowedFrom(s,walking.location,walking.originX,walking.originZ,walking.radius,resident.x,resident.z,x,z));
            if(moved<=1e-7) {waitFor(pending,p,pending.waitingReason==null?"Walking route is blocked":pending.waitingReason);return;}
            // The saved remainder includes the whole circuit and resumes from its actual next point.
            if(!pending.route.isEmpty())for(int i=0;i<p.stops.size();i++) {
                double[] point=p.stops.get(i),next=pending.route.getFirst();
                if(Math.hypot(point[0]-next[0],point[1]-next[1])<1e-6){p.nextStop=i;break;}
            }
            p.distance+=moved;p.activeMinutes++;p.phase="travel";resident.activityMotion=List.copyOf(pending.motion);
        } else {
            double[] destination=p.stops.get(p.nextStop);
            if(pending.route.isEmpty())pending.route=route(s,resident,p,destination);
            if(pending.route.isEmpty()) {waitFor(pending,p,"Activity route is blocked; no physical progress recorded");return;}
            double moved=Engine.walkRoute(s,resident,pending,vehicles,traffic,false,(x,z)->allowed(s,pending.activityExecution,x,z));
            if(moved<=1e-7) {waitFor(pending,p,pending.waitingReason==null?"Activity route is blocked; no physical progress recorded":pending.waitingReason);return;}
            p.distance+=moved;p.activeMinutes++;p.phase=pending.route.isEmpty()&&p.mode.equals("search")?"inspect":"travel";
            resident.activityMotion=List.copyOf(pending.motion);
            if(pending.route.isEmpty()&&p.mode.equals("walk"))p.nextStop=(p.nextStop+1)%p.stops.size();
        }
        // A final walking step can leave a companion's visible range; it must not produce a shared completion.
        for(Resident companion:ActivityGrounding.activityCompanions(s,resident,p.label))if(!ActivityGrounding.present(s,resident,companion)) {
            cancel(resident,pending,effects,companion.name+" is no longer physically present nearby; the shared activity was not completed");return;
        }
        pending.until=s.minute+Math.max(0,p.requestedMinutes-p.activeMinutes);
        if(p.activeMinutes>=p.requestedMinutes&&(!p.mode.equals("search")||p.inspectedStops>0))complete(s,resident,pending,p,effects);
    }
    static String interruption(State s,Resident resident,Progress p) {
        try {
            if(!Objects.equals(mode(p.mode,p.label),p.mode))return "This saved activity requires a physical inspection route; restart it to record actual movement and inspection";
            if(needsDaylight(p.label)&&!sunlightAt(s.minute-1))return "Daylight ended; sunlight and shade inspection was not completed. Choose a daytime activity window";
            for(Resident companion:ActivityGrounding.activityCompanions(s,resident,p.label))if(!ActivityGrounding.present(s,resident,companion))
                return companion.name+" is no longer physically present nearby; the shared activity was not completed";
        } catch(Engine.Invalid invalid) {return invalid.getMessage();}
        return null;
    }
    static List<double[]> copy(List<double[]> points) {return points.stream().map(double[]::clone).collect(java.util.stream.Collectors.toCollection(ArrayList::new));}
    static void waitFor(Pending pending,Progress progress,String reason) {
        if(!progress.phase.equals("blocked"))progress.resumePhase=progress.phase;
        progress.phase="blocked";pending.waitingReason=reason;
        pending.until=progress.lastMinute+Math.max(1,progress.requestedMinutes-progress.activeMinutes);
    }
    static void cancel(Resident resident,Pending pending,Engine.Effects effects,String reason) {
        effects.completions.add(new Engine.Completion(pending.id,"cancelled",reason+"; no completion or unverified world effect was recorded"));
        resident.pending=null;
    }
    static void complete(State s,Resident resident,Pending pending,Progress p,Engine.Effects effects) {
        String evidence="mode "+p.mode+", "+p.activeMinutes+" executed minutes, "+String.format(Locale.ROOT,"%.2f",p.distance)
            +" world units travelled, "+p.inspectedStops+" distinct stops inspected";
        String scope=p.mode.equals("stationary")?"Only time spent was recorded; the activity label is not proof that an operation occurred":
            "No species discoveries, inventory changes or other world effects were verified";
        effects.facts.add(new Engine.Fact("daily_life",resident.id,p.location,resident.name+" finished "+p.activeMinutes
            +" minutes of activity: "+p.label+". Measured execution: "+evidence+". "+scope+"."));
        if(resident.activityHistory==null)resident.activityHistory=new ArrayList<>();
        resident.activityHistory.add(new Outcome(pending.id,p.label,p.location,p.mode,p.activeMinutes,p.inspectedStops,p.distance,s.minute));
        while(resident.activityHistory.size()>12)resident.activityHistory.removeFirst();
        Engine.finish(resident,effects,"Activity execution complete: "+p.label+"; "+evidence+". "+scope+".");
    }
}
