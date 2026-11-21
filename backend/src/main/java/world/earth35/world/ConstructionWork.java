package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Physical worker access and floor space, shared with the rendered construction site. */
public final class ConstructionWork {
    private ConstructionWork() {}
    private static final double STEP=.25,MIN=-5.75;
    private static final int WIDTH=47;
    private static final String PREFIX="construction:";
    private record LocalArea(String id,double x,double z,double targetX,double targetZ) {}
    private static final List<LocalArea> AREAS=List.of(
        new LocalArea("front-left",-1.7,1.2,-2.5,2.3),new LocalArea("front-right",1.9,1.2,2.5,2.3),
        new LocalArea("rear-left",-1.5,-1.6,-2.5,-2.3),new LocalArea("rear-right",1.9,-1.5,2.5,-2.3));
    public record Area(String id,double x,double z,double heading) {}
    public record View(String phase,String task,String area,double x,double z,double heading,int activeMinutes) {}
    public static List<Area> areas(Building b) {return AREAS.stream().map(a->new Area(a.id,b.x+a.x,b.z+a.z,heading(a))).toList();}
    private static double heading(LocalArea area) {return Math.atan2(area.targetX-area.x,area.targetZ-area.z);}
    private static String areaId(Pending p) {
        if(p.detail==null||!p.detail.startsWith(PREFIX))return null;
        return p.detail.substring(PREFIX.length()).split("\\|",2)[0];
    }
    private static LocalArea area(Pending p) {String id=areaId(p);return AREAS.stream().filter(a->a.id.equals(id)).findFirst().orElse(null);}
    public static View view(Resident a) {
        Pending p=a.pending;if(p==null||!"BUILD_CONSTRUCTION".equals(p.type))return null;
        LocalArea area=area(p);if(area==null)return new View("approach","Reach a construction work area",null,a.x,a.z,a.facingHeading==null?0:a.facingHeading,p.workMinutes);
        String task=p.detail.contains("|")?p.detail.substring(p.detail.indexOf('|')+1):"Construction work";
        String phase=p.activityPaused?"paused":p.waitingReason!=null?"blocked":atStation(a,p)?"perform":"approach";
        return new View(phase,task,area.id,p.objectX,p.objectZ,heading(area),p.workMinutes);
    }
    public static View view(State s,Resident a) {return view(a);}
    static void prepare(State s,Resident a,Building b,Pending p,String requested) {
        Engine.require(requested==null||requested.isBlank()||AREAS.stream().anyMatch(area->area.id.equals(requested)),
            "Choose a construction work area: front-left, front-right, rear-left or rear-right, or leave item empty for a free area");
        List<LocalArea> candidates=AREAS.stream().filter(area->requested==null||requested.isBlank()||area.id.equals(requested))
            .sorted(Comparator.comparingDouble(area->Math.hypot(a.x-b.x-area.x,a.z-b.z-area.z))).toList();
        for(LocalArea area:candidates) {
            boolean occupied=s.agents.values().stream().filter(other->!other.id.equals(a.id)).map(other->other.pending)
                .anyMatch(other->other!=null&&"BUILD_CONSTRUCTION".equals(other.type)&&b.id.equals(other.target)&&area.id.equals(areaId(other)));
            if(occupied||s.agents.values().stream().anyMatch(other->!other.id.equals(a.id)&&Math.hypot(other.x-b.x-area.x,other.z-b.z-area.z)<.65))continue;
            List<double[]> route=path(s,b,a.x,a.z,b.x+area.x,b.z+area.z);
            if(route.isEmpty()&&Math.hypot(a.x-b.x-area.x,a.z-b.z-area.z)>.001)continue;
            p.objectX=b.x+area.x;p.objectZ=b.z+area.z;p.route=route;p.detail=PREFIX+area.id+"|"+task(s,b);
            return;
        }
        throw new Engine.Invalid("No clear unoccupied construction work area is reachable through the worker gate; choose another area or wait");
    }
    private static String task(State s,Building b) {
        if(!s.construction.containsKey(b.id))return "Construction work";
        double fraction=progress(s,b);
        return fraction<.12?"Setting foundations":fraction<.76?"Assembling the structural frame":fraction<.95?"Installing walls and services":"Finishing construction";
    }
    private static double progress(State s,Building b) {
        Construction.Site site=s.construction.get(b.id);if(site==null)return 0;
        int actual=site.completedHours*60+s.agents.values().stream().filter(a->a.pending!=null&&"BUILD_CONSTRUCTION".equals(a.pending.type)&&b.id.equals(a.pending.target))
            .mapToInt(a->a.pending.workMinutes).sum();
        return site.requiredHours<=0?0:(double)actual/(site.requiredHours*60);
    }
    static boolean initialized(Pending p) {return area(p)!=null;}
    static boolean atStation(Resident a,Pending p) {return Math.hypot(a.x-p.objectX,a.z-p.objectZ)<.001&&p.route.isEmpty();}
    static boolean onSite(State s,Resident a,Building b) {return allowed(s,b,a.x,a.z);}
    static Building exitingSite(State s,Resident a) {
        if(s==null||s.buildings.containsKey(a.location))return null;
        return s.construction.values().stream().map(site->s.buildings.get(site.id)).filter(Objects::nonNull)
            .filter(b->Math.abs(a.x-b.x)<3.35&&Math.abs(a.z-b.z)<3.35).findFirst().orElse(null);
    }
    public static List<double[]> exitRoute(State s,Resident a,Building b) {
        var door=Places.entrance(b);List<double[]> route=path(s,b,a.x,a.z,door.x(),door.z());
        Engine.require(!route.isEmpty()||Math.hypot(a.x-door.x(),a.z-door.z())<.001,
            "The construction worker gate cannot be reached safely; wait for its path to clear");
        return route;
    }
    /** No labor is credited in a minute containing approach movement. */
    static boolean perform(State s,Resident a,Building b) {
        Pending p=a.pending;LocalArea area=area(p);
        if(area==null) {prepare(s,a,b,p,null);return false;}
        p.detail=PREFIX+area.id+"|"+task(s,b);
        if(atStation(a,p)) {
            if(s.agents.values().stream().anyMatch(other->!other.id.equals(a.id)&&!Places.enclosed(s.buildings.get(other.location))&&Math.hypot(a.x-other.x,a.z-other.z)<.65)) {
                p.waitingReason="Another resident is occupying the work area; no labor recorded";return false;
            }
            p.waitingReason=null;a.facingHeading=heading(area);a.facingUntil=s.minute+2;
            return true;
        }
        if(p.route.isEmpty())p.route=path(s,b,a.x,a.z,p.objectX,p.objectZ);
        if(p.route.isEmpty()) {p.waitingReason="Construction work area is obstructed; no labor recorded";return false;}
        p.motion.clear();
        Engine.walkRouteBudget(s,a,p,Traffic.vehicles(s),Traffic.crossingTraffic(s),false,(x,z)->allowed(s,b,x,z)&&clearOfResidents(s,a,x,z),CityLayout.of(s).walkingSpeed());
        if(p.motion.size()>1)a.location="road";
        a.activityMotion=List.copyOf(p.motion);
        if(p.route.isEmpty()) {a.facingHeading=heading(area);a.facingUntil=s.minute+2;}
        return false;
    }
    private static boolean clearOfResidents(State s,Resident a,double x,double z) {
        for(Resident other:s.agents.values())if(!other.id.equals(a.id)&&!Places.enclosed(s.buildings.get(other.location))) {
            double next=Math.hypot(x-other.x,z-other.z),previous=Math.hypot(a.x-other.x,a.z-other.z);
            // Old arrivals may share an entrance. They may separate, never move further into one another.
            if(next<.65&&next<previous-1e-7)return false;
        }
        return true;
    }
    static boolean allowed(State s,Building b,double x,double z) {
        double lx=x-b.x,lz=z-b.z;
        boolean inside=Math.abs(lx)<=3.15+1e-8&&Math.abs(lz)<=3.15+1e-8;
        boolean gate=Math.abs(lx)<=1.15+1e-8&&lz>=3.15-1e-8&&lz<=3.8+1e-8;
        boolean apron=Math.abs(lx)<=2.25+1e-8&&lz>=3.8-1e-8&&lz<=5.6+1e-8;
        if(!Double.isFinite(x)||!Double.isFinite(z)||!inside&&!gate&&!apron)return false;
        if(s.geographyVersion>=2&&(!IslandGeography.dryLand(x,z)||s.geographyVersion>=4&&!IslandLandscapes.clear(x,z)))return false;
        for(Building other:s.buildings.values())if(!other.id.equals(b.id)&&Places.enclosed(other)&&Math.abs(x-other.x)<3.6&&Math.abs(z-other.z)<3.6)return false;
        for(WorldObjects.PlacedObject object:s.worldObjects.values())if(Math.abs(x-object.x)<1.3&&Math.abs(z-object.z)<1.3)return false;
        // Footings, core, tower crane, material stacks and the completed west wall.
        for(double cx:new double[]{-2.5,2.5})for(double cz:new double[]{-2.3,2.3})if(box(lx,lz,cx,cz,.7,.7))return false;
        if(box(lx,lz,.6,-.6,1,1)||box(lx,lz,-2.8,-2.8,.5,.5))return false;
        for(double cx:new double[]{-2,-.8,.4})if(box(lx,lz,cx,2.7,.6,.6))return false;
        if(progress(s,b)>.76&&box(lx,lz,-2.65,0,.4,2.55))return false;
        return true;
    }
    private static boolean box(double x,double z,double cx,double cz,double rx,double rz) {return Math.abs(x-cx)<rx-1e-8&&Math.abs(z-cz)<rz-1e-8;}
    private static boolean clear(State s,Building b,double x,double z,double nx,double nz) {
        int count=Math.max(1,(int)Math.ceil(Math.hypot(nx-x,nz-z)/.1));
        for(int i=0;i<=count;i++)if(!allowed(s,b,x+(nx-x)*i/count,z+(nz-z)*i/count))return false;
        return true;
    }
    private record Visit(int id,double score) {}
    private static double x(Building b,int id) {return b.x+MIN+(id%WIDTH)*STEP;}
    private static double z(Building b,int id) {return b.z+MIN+(id/WIDTH)*STEP;}
    static List<double[]> path(State s,Building b,double sx,double sz,double tx,double tz) {
        if(!allowed(s,b,sx,sz)||!allowed(s,b,tx,tz))return new ArrayList<>();
        if(Math.hypot(tx-sx,tz-sz)<.001)return new ArrayList<>();
        if(clear(s,b,sx,sz,tx,tz))return new ArrayList<>(List.of(new double[]{tx,tz}));
        int size=WIDTH*WIDTH;double[] distance=new double[size];Arrays.fill(distance,Double.POSITIVE_INFINITY);
        int[] parent=new int[size];Arrays.fill(parent,-1);boolean[] visited=new boolean[size];
        PriorityQueue<Visit> queue=new PriorityQueue<>(Comparator.comparingDouble(Visit::score).thenComparingInt(Visit::id));
        for(int id=0;id<size;id++) {
            double d=Math.hypot(x(b,id)-sx,z(b,id)-sz);
            if(d<=.55&&clear(s,b,sx,sz,x(b,id),z(b,id))) {distance[id]=d;queue.add(new Visit(id,d+Math.hypot(x(b,id)-tx,z(b,id)-tz)));}
        }
        int end=-1;
        while(!queue.isEmpty()) {
            int id=queue.remove().id;if(visited[id])continue;visited[id]=true;
            double px=x(b,id),pz=z(b,id);
            if(Math.hypot(tx-px,tz-pz)<=.55&&clear(s,b,px,pz,tx,tz)){end=id;break;}
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
                int col=id%WIDTH+dx,row=id/WIDTH+dz;if(dx==0&&dz==0||col<0||col>=WIDTH||row<0||row>=WIDTH)continue;
                int next=row*WIDTH+col;double nx=x(b,next),nz=z(b,next);
                double cost=distance[id]+Math.hypot(nx-px,nz-pz);
                if(cost+1e-8>=distance[next]||!clear(s,b,px,pz,nx,nz))continue;
                distance[next]=cost;parent[next]=id;queue.add(new Visit(next,cost+Math.hypot(nx-tx,nz-tz)));
            }
        }
        if(end<0)return new ArrayList<>();
        List<double[]> points=new ArrayList<>();for(int id=end;id>=0;id=parent[id])points.add(new double[]{x(b,id),z(b,id)});
        Collections.reverse(points);points.add(new double[]{tx,tz});
        List<double[]> result=new ArrayList<>();double px=sx,pz=sz;
        for(int i=0;i<points.size();) {
            int last=i;for(int j=i+1;j<points.size();j++){double[] q=points.get(j);if(!clear(s,b,px,pz,q[0],q[1]))break;last=j;}
            double[] q=points.get(last);result.add(q);px=q[0];pz=q[1];i=last+1;
        }
        return result;
    }
}
