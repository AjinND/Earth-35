package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Finite, persistent furniture affordances. Residents choose the task; this class only reaches its physical workspace. */
public final class Workspaces {
    private Workspaces() {}
    private static final Set<String> ACTIONS=Set.of("WORK","RESEARCH_PROJECT","BUILD_PROJECT","PRODUCE_PRODUCT");
    private static final double STEP=.25,MIN=-3.25;
    private static final int COLS=27,ROWS=29;
    private static final Map<String,List<double[]>> ROOM_ROUTES=new java.util.concurrent.ConcurrentHashMap<>();
    @io.swagger.v3.oas.annotations.media.Schema(name="WorkspaceStation")
    public record Station(String id,String kind,double x,double z,double heading) {}
    public record View(String phase,String task,Double heading) {}
    public static boolean supports(Pending p) {return p!=null&&ACTIONS.contains(p.type);}
    public static List<double[]> exitRoute(State s,Resident a,Building b) {
        List<Station> stations=stations(b);if(stations.isEmpty())return null;
        var door=Places.entrance(b);
        return roomRoute(b,a.x,a.z,door.x(),door.z(),()->{
            // A seated stance is inside its chair clearance. Step sideways out
            // before joining the room mesh, whose nodes need not align with it.
            for(Station station:stations)if("desk".equals(station.kind)) {
                Box chair=obstacles(List.of(station)).stream().filter(box->box.chair!=null).findFirst().orElseThrow();
                if(chair.outside(a.x,a.z)>=1-1e-8)continue;
                List<double[]> best=null;double cost=Double.POSITIVE_INFINITY;
                double along=(a.x-station.x)*Math.sin(station.heading)+(a.z-station.z)*Math.cos(station.heading);
                for(int side:new int[]{1,-1}) {
                    double sx=station.x+side*.78*Math.cos(station.heading)+along*Math.sin(station.heading),sz=station.z-side*.78*Math.sin(station.heading)+along*Math.cos(station.heading);
                    if(!clear(b,stations,a.x,a.z,sx,sz,station.id))continue;
                    List<double[]> tail=path(b,stations,sx,sz,door.x(),door.z());if(tail==null)continue;
                    List<double[]> route=new ArrayList<>();route.add(new double[]{sx,sz});route.addAll(tail);
                    double length=Travel.routeDistance(a,route);if(length<cost){best=route;cost=length;}
                }
                return best;
            }
            return path(b,stations,a.x,a.z,door.x(),door.z());
        });
    }
    public static List<double[]> entryRoute(State s,Resident a,Building b) {
        List<Station> stations=stations(b);if(stations.isEmpty())return new ArrayList<>(List.of(new double[]{b.x,b.z}));
        return roomRoute(b,a.x,a.z,b.x,b.z,()->access(s,a,b,stations,stations.getFirst()));
    }
    private static List<double[]> roomRoute(Building b,double x,double z,double tx,double tz,java.util.function.Supplier<List<double[]>> planner) {
        String key=b.id+":"+b.type+":"+b.capacity+":"+b.x+":"+b.z+":"+x+":"+z+":"+tx+":"+tz;
        List<double[]> cached=ROOM_ROUTES.get(key);
        if(cached==null) {
            cached=planner.get();Engine.require(cached!=null,"The furniture blocks this indoor route; choose another action");
            if(ROOM_ROUTES.size()>256)ROOM_ROUTES.clear();ROOM_ROUTES.put(key,List.copyOf(cached));
        }
        List<double[]> result=new ArrayList<>();for(double[] point:cached)result.add(point.clone());return result;
    }
    public static List<Station> stations(Building b) {
        if(b==null||!Places.enclosed(b)||b.capacity<=0||List.of("house","apartment","construction").contains(b.type))return List.of();
        String primary=switch(b.type) {
            case "studio","workshop" -> "workbench";
            case "market","grocery","restaurant","coffee_shop","bakery","fruit-shop","mall" -> "counter";
            default -> "desk";
        };
        List<Station> stations=new ArrayList<>();
        double[][] positions={{0,0},{-1.8,1.6},{1.8,1.6},{-1.8,-1.6},{1.8,-1.6}};
        for(int index=0;index<Math.min(b.capacity,positions.length);index++) {
            String kind=index==1?"desk":index>=3&&primary.equals("desk")?"workbench":primary;
            stations.add(new Station(b.id+":workspace:"+index,kind,b.x+positions[index][0],b.z+positions[index][1],Math.PI));
        }
        return List.copyOf(stations);
    }
    public static View view(Resident a) {
        Pending p=a.pending;if(!supports(p))return null;
        String phase=p.activityPaused?"paused":Engine.present(p.waitingReason)?"blocked":p.workspaceReady&&Math.hypot(a.x-p.workspaceX,a.z-p.workspaceZ)<.001?"perform":"approach";
        String task=switch(p.type) {
            case "WORK" -> Engine.present(a.occupation)?a.occupation:"Work";
            case "RESEARCH_PROJECT" -> Objects.toString(p.projectMethod,"Project")+" research";
            case "BUILD_PROJECT" -> "Building a prototype";
            default -> "Producing the chosen product";
        };
        Double heading=a.facingHeading;if(p.workspaceId!=null)heading=p.workspaceHeading;
        return new View(phase,task,heading);
    }
    public static boolean seated(Resident a) {return supports(a.pending)&&a.pending.workspaceReady&&"desk".equals(a.pending.workspaceKind)&&Math.hypot(a.x-a.pending.workspaceX,a.z-a.pending.workspaceZ)<.001;}
    private static Building workplace(State s,Resident a) {
        Pending p=a.pending;String location=switch(p.type) {
            case "WORK" -> {Job job=s.jobs.get(a.job);yield job==null?null:job.building;}
            case "RESEARCH_PROJECT","BUILD_PROJECT" -> {Projects.Project project=s.projects.get(p.target);yield project==null?null:project.location;}
            default -> {Ventures.Business business=s.businesses.get(p.target);yield business==null?null:business.location;}
        };
        return Objects.equals(a.location,location)?s.buildings.get(location):null;
    }
    private static boolean suitable(Pending p,Station station) {
        // A desk also supports a small prototype or test. Furniture never changes when the resident changes tasks.
        return !"RESEARCH_PROJECT".equals(p.type)||!Set.of("observation","economics").contains(Objects.toString(p.projectMethod,""))||"desk".equals(station.kind);
    }
    private static boolean bodyAt(State s,Resident a,double x,double z) {
        return s.agents.values().stream().anyMatch(other->!other.id.equals(a.id)&&Objects.equals(a.location,other.location)&&Math.hypot(x-other.x,z-other.z)<.65);
    }
    /** True means no productive minute occurred, including a blocked approach. */
    public static boolean approach(State s,Resident a,List<Traffic.Vehicle> vehicles,List<Traffic.RoadMotion> traffic) {
        Pending p=a.pending;if(!supports(p))return false;
        Building building=workplace(s,a);if(building==null||!Places.enclosed(building))return false;
        if(a.paused||p.activityPaused)return true;
        List<Station> furniture=stations(building);
        if(p.workspaceId==null) {
            List<Station> candidates=furniture.stream().filter(station->suitable(p,station))
                .sorted(Comparator.comparingDouble(station->Math.hypot(a.x-station.x,a.z-station.z))).toList();
            for(Station station:candidates) {
                if(bodyAt(s,a,station.x,station.z)||s.agents.values().stream().anyMatch(other->!other.id.equals(a.id)&&other.pending!=null&&station.id.equals(other.pending.workspaceId)))continue;
                List<double[]> route=access(s,a,building,furniture,station);
                if(route==null)continue;
                p.workspaceId=station.id;p.workspaceKind=station.kind;p.workspaceX=station.x;p.workspaceZ=station.z;p.workspaceHeading=station.heading;
                p.workspaceReady=Math.hypot(a.x-station.x,a.z-station.z)<.001;p.route=route;p.waitingReason=null;
                break;
            }
            if(p.workspaceId==null) {p.waitingReason="No free reachable workstation; no work or learning recorded";hold(s,p);return true;}
        }
        Station selected=furniture.stream().filter(station->station.id.equals(p.workspaceId)).findFirst().orElse(null);
        if(selected==null) {p.waitingReason="The reserved workstation is unavailable; no work recorded";hold(s,p);return true;}
        if(p.workspaceReady&&Math.hypot(a.x-p.workspaceX,a.z-p.workspaceZ)<.001) {
            if(bodyAt(s,a,a.x,a.z)) {p.waitingReason="Another resident occupies this workstation; no work recorded";hold(s,p);return true;}
            p.waitingReason=null;a.facingHeading=p.workspaceHeading;a.facingUntil=s.minute+2;return false;
        }
        p.workspaceReady=false;
        if(p.route.isEmpty()||Engine.present(p.waitingReason)) {
            List<double[]> route=access(s,a,building,furniture,selected);
            if(route==null) {p.waitingReason="The workstation approach is obstructed; no work recorded";hold(s,p);return true;}
            p.route=route;
        }
        p.motion.clear();
        Engine.walkRouteBudget(s,a,p,vehicles,traffic,false,(x,z)->{
            String seatedAccess="desk".equals(selected.kind)&&p.route.size()==1?selected.id:null;
            if(!clear(building,furniture,a.x,a.z,x,z,seatedAccess))return false;
            for(Resident other:s.agents.values())if(!other.id.equals(a.id)&&Objects.equals(a.location,other.location)) {
                double next=Math.hypot(x-other.x,z-other.z),before=Math.hypot(a.x-other.x,a.z-other.z);
                if(next<.65&&next<before-1e-7)return false;
            }
            return true;
        },CityLayout.of(s).walkingSpeed());
        a.activityMotion=List.copyOf(p.motion);
        if(p.route.isEmpty()&&Math.hypot(a.x-p.workspaceX,a.z-p.workspaceZ)<.001) {
            p.workspaceReady=true;a.facingHeading=p.workspaceHeading;a.facingUntil=s.minute+2;
        }
        hold(s,p);return true;
    }
    private static void hold(State s,Pending p) {
        p.lastWorkedMinute=s.minute;
        if(!"WORK".equals(p.type)){p.startedAt++;p.until++;}
    }
    private static List<double[]> access(State s,Resident a,Building b,List<Station> stations,Station selected) {
        if(Math.hypot(a.x-selected.x,a.z-selected.z)<.001)return new ArrayList<>();
        if(!"desk".equals(selected.kind))return path(b,stations,a.x,a.z,selected.x,selected.z);
        List<double[]> best=null;double cost=Double.POSITIVE_INFINITY;
        for(int side:new int[]{1,-1}) {
            double sx=selected.x+side*.78*Math.cos(selected.heading),sz=selected.z-side*.78*Math.sin(selected.heading);
            double ox=sx-.85*Math.sin(selected.heading),oz=sz-.85*Math.cos(selected.heading);
            if(!clear(b,stations,ox,oz,sx,sz,null)||!clear(b,stations,sx,sz,selected.x,selected.z,selected.id))continue;
            List<double[]> route=path(b,stations,a.x,a.z,ox,oz);if(route==null)continue;
            route.add(new double[]{sx,sz});route.add(new double[]{selected.x,selected.z});
            double length=Travel.routeDistance(a,route);if(length<cost){best=route;cost=length;}
        }
        return best;
    }
    private record Box(double x,double z,double heading,double width,double depth,String chair) {
        double localX(double px,double pz){return (px-x)*Math.cos(heading)-(pz-z)*Math.sin(heading);}
        double localZ(double px,double pz){return (px-x)*Math.sin(heading)+(pz-z)*Math.cos(heading);}
        double outside(double px,double pz){return Math.max(Math.abs(localX(px,pz))/width,Math.abs(localZ(px,pz))/depth);}
        boolean intersects(double x,double z,double nx,double nz) {
            double low=0,high=1;
            for(double[] axis:new double[][]{{localX(x,z),localX(nx,nz)-localX(x,z),width},{localZ(x,z),localZ(nx,nz)-localZ(x,z),depth}}) {
                if(Math.abs(axis[1])<1e-12){if(Math.abs(axis[0])>=axis[2]-1e-8)return false;continue;}
                double from=(-axis[2]+1e-8-axis[0])/axis[1],to=(axis[2]-1e-8-axis[0])/axis[1];
                low=Math.max(low,Math.min(from,to));high=Math.min(high,Math.max(from,to));if(low>=high)return false;
            }
            return low<high;
        }
    }
    private static List<Box> obstacles(List<Station> stations) {
        List<Box> result=new ArrayList<>();
        for(Station station:stations) {
            result.add(new Box(station.x+.7*Math.sin(station.heading),station.z+.7*Math.cos(station.heading),station.heading,.975,.625,null));
            if("desk".equals(station.kind))result.add(new Box(station.x-.31*Math.sin(station.heading),station.z-.31*Math.cos(station.heading),station.heading,.7,.34,station.id));
        }
        return result;
    }
    private static boolean floor(Building b,double x,double z) {
        double lx=x-b.x,lz=z-b.z;
        return Double.isFinite(x)&&Double.isFinite(z)&&(Math.abs(lx)<=3.1+1e-8&&Math.abs(lz)<=3.1+1e-8||Math.abs(lx)<=1+1e-8&&lz>=3.1-1e-8&&lz<=3.8+1e-8);
    }
    private static boolean clear(Building b,List<Station> stations,double x,double z,double nx,double nz,String seatedAccess) {
        if(!floor(b,x,z)||!floor(b,nx,nz))return false;
        for(Box obstacle:obstacles(stations)) {
            if(obstacle.chair!=null&&obstacle.chair.equals(seatedAccess))continue;
            if(obstacle.chair!=null&&obstacle.outside(x,z)<1-1e-8) {
                double previous=obstacle.outside(x,z);int count=Math.max(1,(int)Math.ceil(Math.hypot(nx-x,nz-z)/.1));
                for(int i=1;i<=count;i++){double next=obstacle.outside(x+(nx-x)*i/count,z+(nz-z)*i/count);if(next<previous-1e-7)return false;previous=next;}
            } else if(obstacle.intersects(x,z,nx,nz))return false;
        }
        return true;
    }
    private static boolean safe(Building b,List<Station> stations,double x,double z) {
        return floor(b,x,z)&&obstacles(stations).stream().noneMatch(box->box.outside(x,z)<1-1e-8);
    }
    private record Visit(int id,double score) {}
    private static double x(Building b,int id){return b.x+MIN+(id%COLS)*STEP;}
    private static double z(Building b,int id){return b.z+MIN+(id/COLS)*STEP;}
    private static List<double[]> path(Building b,List<Station> stations,double sx,double sz,double tx,double tz) {
        if(!floor(b,sx,sz)||!safe(b,stations,tx,tz))return null;
        if(Math.hypot(sx-tx,sz-tz)<.001)return new ArrayList<>();
        if(clear(b,stations,sx,sz,tx,tz,null))return new ArrayList<>(List.of(new double[]{tx,tz}));
        int count=COLS*ROWS;double[] costs=new double[count];Arrays.fill(costs,Double.POSITIVE_INFINITY);int[] parent=new int[count];Arrays.fill(parent,-1);boolean[] visited=new boolean[count];
        PriorityQueue<Visit> queue=new PriorityQueue<>(Comparator.comparingDouble(Visit::score).thenComparingInt(Visit::id));
        for(int id=0;id<count;id++) {
            double d=Math.hypot(x(b,id)-sx,z(b,id)-sz);
            if(d<=1.1&&safe(b,stations,x(b,id),z(b,id))&&clear(b,stations,sx,sz,x(b,id),z(b,id),null)){costs[id]=d;queue.add(new Visit(id,d+Math.hypot(x(b,id)-tx,z(b,id)-tz)));}
        }
        int end=-1;
        while(!queue.isEmpty()) {
            int id=queue.remove().id;if(visited[id])continue;visited[id]=true;double px=x(b,id),pz=z(b,id);
            if(Math.hypot(tx-px,tz-pz)<=.6&&clear(b,stations,px,pz,tx,tz,null)){end=id;break;}
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
                int col=id%COLS+dx,row=id/COLS+dz;if(dx==0&&dz==0||col<0||col>=COLS||row<0||row>=ROWS)continue;
                int next=row*COLS+col;double nx=x(b,next),nz=z(b,next),cost=costs[id]+Math.hypot(nx-px,nz-pz);
                if(cost+1e-8>=costs[next]||!safe(b,stations,nx,nz)||!clear(b,stations,px,pz,nx,nz,null))continue;
                costs[next]=cost;parent[next]=id;queue.add(new Visit(next,cost+Math.hypot(nx-tx,nz-tz)));
            }
        }
        if(end<0)return null;
        List<double[]> nodes=new ArrayList<>();for(int id=end;id>=0;id=parent[id])nodes.add(new double[]{x(b,id),z(b,id)});
        Collections.reverse(nodes);nodes.add(new double[]{tx,tz});List<double[]> result=new ArrayList<>();double px=sx,pz=sz;
        for(int i=0;i<nodes.size();) {
            int last=i;for(int j=i+1;j<nodes.size();j++){double[] q=nodes.get(j);if(!clear(b,stations,px,pz,q[0],q[1],null))break;last=j;}
            double[] q=nodes.get(last);result.add(q);px=q[0];pz=q[1];i=last+1;
        }
        return result;
    }
}
