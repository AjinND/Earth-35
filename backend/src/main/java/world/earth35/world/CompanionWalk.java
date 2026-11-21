package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;
import world.earth35.world.ActivityExecution.Progress;

/** Physical pacing for residents who have each chosen the same shared walk. */
final class CompanionWalk {
    private CompanionWalk() {}
    private static final double EPS=1e-6,MIN_STEP_BUDGET=1e-4,SPACING=1.4;

    static boolean tick(State s,Resident actor,Engine.Effects effects,List<Traffic.Vehicle> vehicles,List<Traffic.RoadMotion> traffic) {
        if(!ready(actor))return false;
        Progress initial=actor.pending.activityExecution;
        if(initial.walkCompanions!=null&&!initial.walkCompanions.isEmpty()&&initial.lastMinute>=s.minute)return true;
        List<Resident> group=group(s,actor);
        if(group.size()<2) {
            if(initial.walkCompanions!=null&&!initial.walkCompanions.isEmpty()) {
                ActivityExecution.cancel(actor,actor.pending,effects,"A companion stopped participating in the shared walk");return true;
            }
            return false;
        }
        // Saved membership is evidence of a specific shared outing. Do not silently
        // substitute a nearby person after one participant leaves or changes tasks.
        List<String> ids=group.stream().map(a->a.id).toList();
        if(group.stream().anyMatch(a->a.pending.activityPaused)) {
            for(Resident a:group){a.pending.activityExecution.lastMinute=s.minute;a.pending.motion.clear();}
            waitFor(group,"A companion is taking a break; choose whether to wait or stop the shared walk");return true;
        }
        for(Resident a:group) {
            Progress p=a.pending.activityExecution;
            if(p==null)continue;
            String interruption=ActivityExecution.interruption(s,a,p);
            if(interruption!=null) {cancel(group,effects,interruption);return true;}
            if(p.walkCompanions!=null&&!p.walkCompanions.isEmpty()&&!p.walkCompanions.equals(ids)) {
                cancel(group,effects,"A companion stopped participating in the shared walk");return true;
            }
        }
        // Each resident's own execution pass upgrades old saved progress. Hold
        // an already upgraded partner here so map order cannot send them alone.
        if(group.stream().anyMatch(a->a.pending.activityExecution==null||a.pending.activityExecution.walkVersion==0)) {
            initial.lastMinute=s.minute;actor.pending.motion.clear();
            ActivityExecution.waitFor(actor.pending,initial,"Waiting for companions to resume their saved shared walk");return true;
        }
        if(group.stream().anyMatch(a->!ready(a))) {cancel(group,effects,"A companion has no usable shared walking itinerary");return true;}
        boolean consecutive=true;
        for(Resident a:group)consecutive&=a.pending.activityExecution.lastMinute==s.minute-1;
        for(Resident a:group) {
            Progress p=a.pending.activityExecution;p.lastMinute=s.minute;a.pending.motion.clear();
            a.pending.until=s.minute+Math.max(1,p.requestedMinutes-p.activeMinutes);
        }
        if(!consecutive)return true;
        for(Resident a:group) {
            Progress p=a.pending.activityExecution;
            if(!Objects.equals(a.location,p.location)||!WalkingRoute.canStart(s,p.location,p.originX,p.originZ,p.radius,a.x,a.z)) {
                cancel(group,effects,"A companion left the safe shared walking grounds");return true;
            }
            if(a.paused) {waitFor(group,"Waiting for "+a.name+" to resume the shared walk");return true;}
        }
        if(initial.walkCompanions==null||initial.walkCompanions.isEmpty())initialize(s,group,ids);
        for(Resident a:group) {
            Progress p=a.pending.activityExecution;
            if("blocked".equals(p.phase)){p.phase="travel";p.resumePhase=null;}
            a.pending.waitingReason=null;
        }
        double speed=CityLayout.of(s).walkingSpeed();
        double elapsed=0;
        double[] budgets=new double[group.size()],moved=new double[group.size()];Arrays.fill(budgets,speed);
        for(Resident a:group)a.pending.motion.add(new Traffic.Pose(a.x,a.z,a.facingHeading==null?0:a.facingHeading,0.0));
        String blocked=null;
        // Trial each short simultaneous step before committing any participant.
        // Thus a blocked or traffic-stopped companion never gets left behind.
        for(int step=0;step<512;step++) {
            if(speed-elapsed<MIN_STEP_BUDGET)break;
            int reached=0;
            for(Resident a:group)if(atStop(a))reached++;
            if(reached==group.size()) {
                boolean approaching=group.stream().anyMatch(a->!a.pending.activityExecution.walkApproach.isEmpty());
                for(Resident a:group) {
                    Progress p=a.pending.activityExecution;
                    if(approaching) {if(!p.walkApproach.isEmpty())p.walkApproach.removeFirst();}
                    else p.nextStop=(p.nextStop+1)%p.stops.size();
                    a.pending.route.clear();
                }
                if(!approaching) {
                    List<double[]> centers=new ArrayList<>();
                    for(int waypoint=0;waypoint<group.getFirst().pending.activityExecution.stops.size();waypoint++) {
                        double x=0,z=0;for(Resident a:group){double[] point=a.pending.activityExecution.stops.get(waypoint);x+=point[0];z+=point[1];}
                        centers.add(new double[]{x/group.size(),z/group.size()});
                    }
                    approachTogether(s,group,centers,group.getFirst().pending.activityExecution.nextStop,
                        group.stream().mapToDouble(a->a.x).average().orElseThrow(),group.stream().mapToDouble(a->a.z).average().orElseThrow());
                }
                // Finishing an entry leg and reaching its circuit waypoint can
                // name the same point twice. Advance through those zero-time
                // transitions before deciding whether anyone can still move.
                continue;
            }
            double furthest=0,separation=0;
            for(Resident a:group) {
                furthest=Math.max(furthest,toStop(a));
                for(Resident b:group)separation=Math.max(separation,Math.hypot(a.x-b.x,a.z-b.z));
            }
            List<Trial> trials=new ArrayList<>();boolean exhausted=false;
            for(int index=0;index<group.size();index++) {
                Resident a=group.get(index);Progress p=a.pending.activityExecution;
                if(atStop(a))continue;
                // The resident nearer the next waypoint waits while a straggler
                // catches up. Waiting alone never accrues executed walking time.
                if(separation>3&&toStop(a)<furthest-SPACING)continue;
                // Terrain effort is inverted with finite precision. A microscopic
                // remainder is the end of this minute's budget, not an obstacle.
                if(budgets[index]<MIN_STEP_BUDGET){exhausted=true;break;}
                if(a.pending.route.isEmpty())a.pending.route=ActivityExecution.route(s,a,p,target(a));
                if(a.pending.route.isEmpty()){blocked="A companion's walking route is blocked; waiting together";break;}
                Resident shadow=new Resident();shadow.id=a.id;shadow.location=a.location;shadow.x=a.x;shadow.z=a.z;
                shadow.facingHeading=a.facingHeading;shadow.facingUntil=a.facingUntil;
                Pending pending=new Pending();pending.route=ActivityExecution.copy(a.pending.route);pending.motion=new ArrayList<>(a.pending.motion);
                Engine.WalkingStep walked=Engine.walkRouteBudget(s,shadow,pending,vehicles,traffic,false,
                    (x,z)->WalkingRoute.allowedFrom(s,p.location,p.originX,p.originZ,p.radius,shadow.x,shadow.z,x,z),Math.min(speed-elapsed,Math.min(.35,budgets[index])));
                if(walked.distance()<EPS) {
                    if(pending.waitingReason==null)exhausted=true;else blocked=pending.waitingReason;
                    break;
                }
                trials.add(new Trial(index,shadow,pending,walked));
            }
            if(blocked!=null||exhausted||trials.isEmpty())break;
            elapsed+=synchronizeMotion(s,group,trials,elapsed,speed);
            for(Trial trial:trials) {
                int index=trial.index;Resident a=group.get(index);Resident shadow=trial.resident;
                a.x=shadow.x;a.z=shadow.z;a.facingHeading=shadow.facingHeading;a.facingUntil=shadow.facingUntil;
                a.pending.route=trial.pending.route;
                moved[index]+=trial.walked.distance();budgets[index]=Math.max(0,budgets[index]-trial.walked.effort());
            }
        }
        boolean finishing=false;
        for(int index=0;index<group.size();index++) {
            Resident a=group.get(index);Pending pending=a.pending;Progress p=pending.activityExecution;
            if(moved[index]>EPS){p.distance+=moved[index];p.activeMinutes++;p.phase="travel";}
            else if(blocked==null)ActivityExecution.waitFor(pending,p,"Waiting for walking companions to catch up");
            if(blocked!=null)ActivityExecution.waitFor(pending,p,blocked);
            Traffic.Pose last=pending.motion.getLast();
            if(last.fraction()<1)pending.motion.add(new Traffic.Pose(last.x(),last.z(),last.heading(),1.0));
            a.activityMotion=List.copyOf(pending.motion);
            pending.until=s.minute+Math.max(0,p.requestedMinutes-p.activeMinutes);
            finishing|=p.activeMinutes>=p.requestedMinutes;
        }
        // A participant can name someone outside this walking group. Actual
        // movement must not turn that person's absence into a shared completion.
        for(Resident a:group) {
            String interruption=ActivityExecution.interruption(s,a,a.pending.activityExecution);
            if(interruption!=null) {cancel(group,effects,interruption);return true;}
        }
        // A finished companion cannot be kept walking to satisfy another
        // duration. Return the remaining resident's next choice to them too.
        if(finishing)for(Resident a:group) {
            Pending pending=a.pending;Progress p=pending.activityExecution;
            if(p.activeMinutes>=p.requestedMinutes)ActivityExecution.complete(s,a,pending,p,effects);
            else ActivityExecution.cancel(a,pending,effects,"A companion finished their chosen walking duration; choose whether to continue this outing");
        }
        return true;
    }

    private record Trial(int index,Resident resident,Pending pending,Engine.WalkingStep walked) {}
    private record MotionPart(List<Traffic.Pose> points,List<Double> fractions) {}
    private static double synchronizeMotion(State s,List<Resident> group,List<Trial> trials,double elapsed,double speed) {
        double duration=trials.stream().mapToDouble(trial->trial.walked.effort()).max().orElse(0);
        Map<Integer,Trial> moving=new HashMap<>();for(Trial trial:trials)moving.put(trial.index,trial);
        List<MotionPart> parts=new ArrayList<>();NavigableSet<Double> cuts=new TreeSet<>();cuts.add(1.0);
        for(int index=0;index<group.size();index++) {
            Resident a=group.get(index);List<Traffic.Pose> points=new ArrayList<>();List<Double> fractions=new ArrayList<>();
            points.add(a.pending.motion.getLast());fractions.add(0.0);double effort=0;
            Trial trial=moving.get(index);
            if(trial!=null)for(int vertex=a.pending.motion.size();vertex<trial.pending.motion.size();vertex++) {
                Traffic.Pose previous=points.getLast(),point=trial.pending.motion.get(vertex);
                effort+=s.geographyVersion>=4?TerrainElevation.segmentEffort(previous.x(),previous.z(),point.x(),point.z()):Math.hypot(point.x()-previous.x(),point.z()-previous.z());
                double fraction=Math.min(1,effort/duration);
                if(fraction<1e-9)fraction=0;else if(1-fraction<1e-9)fraction=1;
                points.add(point);fractions.add(fraction);
                if(fraction>0) {
                    Double before=cuts.floor(fraction),after=cuts.ceiling(fraction);
                    if((before==null||fraction-before>1e-9)&&(after==null||after-fraction>1e-9))cuts.add(fraction);
                }
            }
            parts.add(new MotionPart(points,fractions));
        }
        // Preserve each physical corner on a shared clock. Tiny terrain residue
        // receives tiny time instead of a full frame interval, while an early
        // arrival holds only until the other partner reaches this cross-section.
        for(double fraction:cuts)for(int index=0;index<group.size();index++) {
            Traffic.Pose pose=sample(parts.get(index),fraction);
            group.get(index).pending.motion.add(new Traffic.Pose(pose.x(),pose.z(),pose.heading(),Math.min(1,(elapsed+fraction*duration)/speed)));
        }
        return duration;
    }
    private static Traffic.Pose sample(MotionPart part,double fraction) {
        for(int index=1;index<part.points.size();index++)if(fraction<=part.fractions.get(index)) {
            Traffic.Pose a=part.points.get(index-1),b=part.points.get(index);
            double elapsed=part.fractions.get(index)-part.fractions.get(index-1);
            double t=elapsed<1e-12?1:(fraction-part.fractions.get(index-1))/elapsed;
            return new Traffic.Pose(a.x()+(b.x()-a.x())*t,a.z()+(b.z()-a.z())*t,b.heading());
        }
        return part.points.getLast();
    }
    private static boolean ready(Resident a) {
        return a!=null&&a.pending!=null&&"DO_ACTIVITY".equals(a.pending.type)&&a.pending.activityExecution!=null
            &&"walk".equals(a.pending.activityExecution.mode)&&a.pending.activityExecution.walkVersion>0
            &&a.pending.activityExecution.stops!=null&&!a.pending.activityExecution.stops.isEmpty();
    }
    private static boolean walkingCandidate(Resident a) {
        if(a==null||a.pending==null||!"DO_ACTIVITY".equals(a.pending.type))return false;
        if(a.pending.activityExecution!=null)return "walk".equals(a.pending.activityExecution.mode);
        try {return "walk".equals(ActivityExecution.mode(null,a.pending.detail));}
        catch(Engine.Invalid invalid) {return false;}
    }
    private static String label(Resident a) {return a.pending.activityExecution==null?a.pending.detail:a.pending.activityExecution.label;}
    private static String grounds(Resident a) {return a.pending.activityExecution==null?a.location:a.pending.activityExecution.location;}
    private static List<Resident> group(State s,Resident actor) {
        List<Resident> candidates=s.agents.values().stream().filter(CompanionWalk::walkingCandidate)
            .filter(a->Objects.equals(a.location,actor.location)&&Objects.equals(grounds(a),grounds(actor)))
            .sorted(Comparator.comparing(a->a.id)).toList();
        Set<String> found=new LinkedHashSet<>();found.add(actor.id);
        boolean added;do {
            added=false;
            for(Resident a:candidates)if(!found.contains(a.id))for(Resident b:candidates)
                if(found.contains(b.id)&&linked(s,a,b)){added|=found.add(a.id);break;}
        }while(added);
        return candidates.stream().filter(a->found.contains(a.id)).toList();
    }
    private static boolean linked(State s,Resident a,Resident b) {
        String id=a.pending.socialPlanId;
        if(id!=null&&id.equals(b.pending.socialPlanId)) {
            SocialPlan plan=s.socialPlans.get(id);
            if(plan!=null&&List.of("accepted","in_progress").contains(plan.status)&&plan.expires>s.minute
                &&participant(plan,a.id)&&participant(plan,b.id))return true;
        }
        try {
            return ActivityGrounding.activityCompanions(s,a,label(a)).contains(b)
                &&ActivityGrounding.activityCompanions(s,b,label(b)).contains(a);
        }catch(Engine.Invalid invalid){return false;}
    }
    private static boolean participant(SocialPlan plan,String id) {return id.equals(plan.proposer)||id.equals(plan.invited);}
    private static void initialize(State s,List<Resident> group,List<String> ids) {
        Resident leader=group.getFirst();Progress source=leader.pending.activityExecution;
        List<double[]> center=ActivityExecution.copy(source.stops);int next=source.nextStop;
        double entryX=group.stream().mapToDouble(a->a.x).average().orElse(leader.x);
        double entryZ=group.stream().mapToDouble(a->a.z).average().orElse(leader.z);
        for(Resident a:group) {
            Progress p=a.pending.activityExecution;p.originX=source.originX;p.originZ=source.originZ;p.radius=source.radius;
            p.stops=new ArrayList<>();p.nextStop=next;p.walkApproach.clear();p.walkLeader=leader.id;p.walkCompanions=new ArrayList<>(ids);
            a.pending.route.clear();
        }
        for(int waypoint=0;waypoint<center.size();waypoint++) {
            double[] previous=center.get(Math.floorMod(waypoint-1,center.size())),point=center.get(waypoint),following=center.get((waypoint+1)%center.size());
            double angle=Math.atan2(following[0]-previous[0],previous[1]-following[1]);double[][] section=null;
            // Rotate into single file near a narrow obstacle before reducing
            // personal space. A shared center point is never a pair of targets.
            for(double spacing:new double[]{SPACING,1.2,1.0,.8}) {
                for(int turn=0;turn<16;turn++) {
                    int increment=turn==0?0:((turn+1)/2)*(turn%2==1?1:-1);
                    double orientation=angle+increment*Math.PI/8;double[][] candidate=new double[group.size()][];
                    for(int index=0;index<group.size();index++) {
                        double offset=(index-(group.size()-1)/2.0)*spacing;
                        candidate[index]=new double[]{point[0]+Math.cos(orientation)*offset,point[1]+Math.sin(orientation)*offset};
                    }
                    if(safeFormation(s,group,candidate)){section=candidate;break;}
                }
                if(section!=null)break;
            }
            if(section==null) {
                // There is no standing room for the whole group at this point.
                // Leave it out; the next leg will be planned around it together.
                continue;
            }
            for(int index=0;index<group.size();index++)group.get(index).pending.activityExecution.stops.add(section[index]);
        }
        if(group.getFirst().pending.activityExecution.stops.isEmpty()) {
            // The original route is still safe for a person; wait for enough
            // walking room rather than assigning overlapping group goals.
            for(Resident a:group)a.pending.activityExecution.stops=ActivityExecution.copy(center);
        }
        for(Resident a:group)a.pending.activityExecution.nextStop=Math.min(next,a.pending.activityExecution.stops.size()-1);
        center=new ArrayList<>();
        for(int waypoint=0;waypoint<group.getFirst().pending.activityExecution.stops.size();waypoint++) {
            double x=0,z=0;for(Resident a:group){double[] point=a.pending.activityExecution.stops.get(waypoint);x+=point[0];z+=point[1];}
            center.add(new double[]{x/group.size(),z/group.size()});
        }
        next=group.getFirst().pending.activityExecution.nextStop;
        approachTogether(s,group,center,next,entryX,entryZ);
    }
    private record FormationNode(int id,double score) {}
    private static void approachTogether(State s,List<Resident> group,List<double[]> circuit,int next,double entryX,double entryZ) {
        double[] destination=circuit.get(next);
        double dx=destination[0]-entryX,dz=destination[1]-entryZ,distance=Math.hypot(dx,dz);
        List<double[]> start=group.stream().map(a->new double[]{a.x,a.z}).toList();
        List<double[]> finish=group.stream().map(a->a.pending.activityExecution.stops.get(next)).toList();
        double startDx=start.getLast()[0]-start.getFirst()[0],startDz=start.getLast()[1]-start.getFirst()[1];
        double startSpacing=Math.hypot(startDx,startDz)/(group.size()-1),endSpacing=Math.hypot(finish.getLast()[0]-finish.getFirst()[0],finish.getLast()[1]-finish.getFirst()[1])/(group.size()-1);
        double startAngle=startSpacing<.1?Math.atan2(dx,-dz):Math.atan2(startDz,startDx);
        double endAngle=Math.atan2(finish.getLast()[1]-finish.getFirst()[1],finish.getLast()[0]-finish.getFirst()[0]);
        double turn=Math.atan2(Math.sin(endAngle-startAngle),Math.cos(endAngle-startAngle));
        List<double[][]> direct=new ArrayList<>();List<double[]> from=start;boolean clear=true;
        int sections=Math.max(1,(int)Math.ceil(Math.max(distance/.75,Math.abs(turn)/(Math.PI/12))));
        for(int section=1;section<=sections;section++) {
            double fraction=section/(double)sections;
            double[][] points=formation(group.size(),entryX+dx*fraction,entryZ+dz*fraction,entryX,entryZ,dx,dz,Math.max(EPS,distance),startAngle,turn,startSpacing,endSpacing);
            if(!safeFormation(s,group,points)||!clearFormation(s,group,from,Arrays.asList(points),section==1)) {clear=false;break;}
            direct.add(points);from=Arrays.asList(points);
        }
        if(clear) {storeApproach(group,direct);return;}
        if(distance<EPS)return;
        double mesh=Math.min(1,Math.max(.2,distance/Math.max(1,Math.abs(turn)*4)));
        int cols=(int)Math.ceil((Math.abs(dx)+12)/mesh)+1,rows=(int)Math.ceil((Math.abs(dz)+12)/mesh)+1,count=cols*rows;
        double minX=Math.min(entryX,destination[0])-6,minZ=Math.min(entryZ,destination[1])-6;
        double[][][] positions=new double[count][][];boolean[] checked=new boolean[count],safe=new boolean[count],visited=new boolean[count];
        double[] costs=new double[count];Arrays.fill(costs,Double.POSITIVE_INFINITY);
        int[] parents=new int[count];Arrays.fill(parents,-1);
        PriorityQueue<FormationNode> queue=new PriorityQueue<>(Comparator.comparingDouble(FormationNode::score).thenComparingInt(FormationNode::id));
        for(int id=0;id<count;id++) {
            double x=minX+(id%cols)*mesh,z=minZ+(id/cols)*mesh;
            if(Math.hypot(x-entryX,z-entryZ)>2)continue;
            positions[id]=formation(group.size(),x,z,entryX,entryZ,dx,dz,distance,startAngle,turn,startSpacing,endSpacing);
            checked[id]=true;safe[id]=safeFormation(s,group,positions[id]);
            if(safe[id]&&clearFormation(s,group,start,Arrays.asList(positions[id]),true)) {
                costs[id]=Math.hypot(x-entryX,z-entryZ);queue.add(new FormationNode(id,costs[id]+Math.hypot(x-destination[0],z-destination[1])));
            }
        }
        int reached=-1;
        int[] across={1,1,0,-1,-1,-1,0,1},along={0,1,1,1,0,-1,-1,-1};
        while(!queue.isEmpty()) {
            int id=queue.remove().id();if(visited[id])continue;visited[id]=true;
            double x=minX+(id%cols)*mesh,z=minZ+(id/cols)*mesh;
            if(Math.hypot(x-destination[0],z-destination[1])<=2&&clearFormation(s,group,Arrays.asList(positions[id]),finish,false)){reached=id;break;}
            for(int direction=0;direction<8;direction++) {
                int col=id%cols+across[direction],row=id/cols+along[direction];if(col<0||col>=cols||row<0||row>=rows)continue;
                int neighbor=row*cols+col;if(visited[neighbor])continue;
                double nx=minX+col*mesh,nz=minZ+row*mesh;
                if(!checked[neighbor]) {
                    positions[neighbor]=formation(group.size(),nx,nz,entryX,entryZ,dx,dz,distance,startAngle,turn,startSpacing,endSpacing);
                    safe[neighbor]=safeFormation(s,group,positions[neighbor]);checked[neighbor]=true;
                }
                double cost=costs[id]+Math.hypot(nx-x,nz-z);
                if(!safe[neighbor]||cost>=costs[neighbor]||!clearFormation(s,group,Arrays.asList(positions[id]),Arrays.asList(positions[neighbor]),false))continue;
                parents[neighbor]=id;costs[neighbor]=cost;queue.add(new FormationNode(neighbor,cost+Math.hypot(nx-destination[0],nz-destination[1])));
            }
        }
        if(reached<0)return; // A narrow entry can still use the existing safe single-file route.
        List<double[][]> path=new ArrayList<>();
        for(int id=reached;id>=0;id=parents[id])path.add(positions[id]);Collections.reverse(path);
        path.add(finish.toArray(double[][]::new));
        // Each cross-section is reached by everyone before the next one begins.
        // This keeps a shared detour shared and rotates the formation without crossing lanes.
        storeApproach(group,path);
    }
    private static void storeApproach(List<Resident> group,List<double[][]> path) {
        for(int index=0;index<group.size();index++)for(double[][] section:path)group.get(index).pending.activityExecution.walkApproach.add(section[index].clone());
    }
    private static double[][] formation(int size,double x,double z,double entryX,double entryZ,double dx,double dz,double length,double angle,double turn,double startSpacing,double endSpacing) {
        double along=((x-entryX)*dx+(z-entryZ)*dz)/length;
        double rotation=Math.max(0,Math.min(1,(along-Math.max(0,length-6))/Math.min(6,length)));
        double spacing=startSpacing<.1?endSpacing*Math.max(0,Math.min(1,along/1.5)):startSpacing+(endSpacing-startSpacing)*rotation;
        double normalX=Math.cos(angle+turn*rotation),normalZ=Math.sin(angle+turn*rotation);
        double[][] result=new double[size][];
        for(int index=0;index<size;index++) {double offset=(index-(size-1)/2.0)*spacing;result[index]=new double[]{x+normalX*offset,z+normalZ*offset};}
        return result;
    }
    private static boolean safeFormation(State s,List<Resident> group,double[][] points) {
        for(int index=0;index<group.size();index++) {
            Progress p=group.get(index).pending.activityExecution;double[] point=points[index];
            if(!WalkingRoute.allowed(s,p.location,p.originX,p.originZ,p.radius,point[0],point[1]))return false;
        }
        return true;
    }
    private static boolean clearFormation(State s,List<Resident> group,List<double[]> from,List<double[]> to,boolean entry) {
        for(int first=0;first<group.size();first++)for(int second=first+1;second<group.size();second++) {
            double rx=from.get(first)[0]-from.get(second)[0],rz=from.get(first)[1]-from.get(second)[1];
            double dx=(to.get(first)[0]-to.get(second)[0])-rx,dz=(to.get(first)[1]-to.get(second)[1])-rz;
            double t=dx*dx+dz*dz<EPS?0:Math.max(0,Math.min(1,-(rx*dx+rz*dz)/(dx*dx+dz*dz)));
            if(Math.hypot(rx+dx*t,rz+dz*t)<Math.min(.7,Math.hypot(rx,rz))-1e-7)return false;
        }
        for(int index=0;index<group.size();index++) {
            Progress p=group.get(index).pending.activityExecution;double[] a=from.get(index),b=to.get(index);
            if(entry) {
                List<double[]> path=WalkingRoute.path(s,p.location,p.originX,p.originZ,p.radius,a[0],a[1],b);
                if(Math.hypot(a[0]-b[0],a[1]-b[1])>EPS&&(path.size()!=1||Math.hypot(path.getFirst()[0]-b[0],path.getFirst()[1]-b[1])>EPS))return false;
            } else if(!WalkingRoute.clearSegment(s,p.location,p.originX,p.originZ,p.radius,a[0],a[1],b[0],b[1]))return false;
        }
        return true;
    }
    private static double[] target(Resident a) {Progress p=a.pending.activityExecution;return p.walkApproach.isEmpty()?p.stops.get(p.nextStop):p.walkApproach.getFirst();}
    private static double toStop(Resident a) {double[] point=target(a);return Math.hypot(a.x-point[0],a.z-point[1]);}
    private static boolean atStop(Resident a) {return toStop(a)<.001;}
    private static void waitFor(List<Resident> group,String reason) {for(Resident a:group)ActivityExecution.waitFor(a.pending,a.pending.activityExecution,reason);}
    private static void cancel(List<Resident> group,Engine.Effects effects,String reason) {for(Resident a:group)ActivityExecution.cancel(a,a.pending,effects,reason);}
}
