package world.earth35.world;

import java.util.*;

/** Joint traffic forecast: the timetable and visible vehicles use the same queues. */
final class TrafficFlow {
    static final class Schedule {
        final Map<String,Traffic.Trip> trips=new HashMap<>();
        final Map<Long,Map<String,Traffic.Frame>> frames=new HashMap<>();
        final Map<Long,Map<String,List<Traffic.Pose>>> motion=new HashMap<>();
    }
    private record Node(double x,double z,int stop) {}
    private static final double LANE=.8;
    // Includes the visible queue-to-door walk and a full stationary snapshot
    // before departure, even in the slower prototype geography.
    private static final int BUS_DWELL=6;
    private record CacheKey(long day,int geographyVersion) {}
    private static final Map<CacheKey,Schedule> CACHE=new LinkedHashMap<>();
    static synchronized Schedule schedule(long day) {
        return schedule(day,CityLayout.PROTOTYPE);
    }
    static synchronized Schedule schedule(long day,CityLayout layout) {
        CacheKey key=new CacheKey(day,layout.version());Schedule found=CACHE.get(key);if(found!=null)return found;
        Schedule built=build(day,layout.version()>=2?CityLayout.ISLAND:layout,layout.version()>=2);CACHE.put(key,built);
        while(CACHE.size()>6)CACHE.remove(CACHE.keySet().iterator().next());
        return built;
    }
    private static final class Car {
        String id;Traffic.Route route;long start;List<Node> path;
        double x,z,heading;int next=1,stop=-1;long dwell;boolean done;
        List<Long> arrivals=new ArrayList<>(),departures=new ArrayList<>();
        Traffic.Pose physical;
        double length(){return route==null?1.7:2.8;}
    }
    private static List<Node> lane(List<Node> centers) {
        List<Node> result=new ArrayList<>();
        for(int i=0;i<centers.size();i++) {
            Node p=centers.get(i),prev=centers.get(Math.max(0,i-1)),next=centers.get(Math.min(centers.size()-1,i+1));
            boolean closed=centers.getFirst().x==centers.getLast().x&&centers.getFirst().z==centers.getLast().z;
            if(closed&&i==0)prev=centers.get(centers.size()-2);
            if(closed&&i==centers.size()-1)next=centers.get(1);
            double incoming=i==0&&!closed?Math.atan2(next.x-p.x,next.z-p.z):Math.atan2(p.x-prev.x,p.z-prev.z);
            double outgoing=i==centers.size()-1&&!closed?incoming:Math.atan2(next.x-p.x,next.z-p.z);
            boolean inH=Math.abs(Math.sin(incoming))>.5,outH=Math.abs(Math.sin(outgoing))>.5;
            double x=p.x+LANE*Math.cos(inH?outgoing:incoming),z=p.z-LANE*Math.sin(inH?incoming:outgoing);
            if(inH==outH){x=p.x+LANE*Math.cos(incoming);z=p.z-LANE*Math.sin(incoming);}
            result.add(new Node(x,z,p.stop));
        }
        return result;
    }
    private static List<Node> busPath(Traffic.Route r,CityLayout layout) {
        double[][] points={{-48,-42},{-48,-48},{24,-48},{24,-24},{30,-24},{42,-24},{48,-24},{48,30},{48,36},{-48,36},{-48,30},{-48,-42}};
        List<Node> nodes=new ArrayList<>();
        for(double[] p:points)nodes.add(new Node(layout.x(p[0]),layout.z(p[1]),-1));
        if(r.id().equals("R2"))Collections.reverse(nodes);
        int index=0;
        for(int i=0;i<nodes.size();i++) {
            Node n=nodes.get(i);Traffic.Stop stop=Traffic.stop(r.stops().get(index));
            if(n.x==layout.x(stop.roadX())&&n.z==layout.z(stop.roadZ())) {nodes.set(i,new Node(n.x,n.z,index));index++;if(index==r.stops().size())break;}
        }
        return lane(nodes);
    }
    private static double[] bounds(double x,double z,double heading,double length) {
        boolean horizontal=Math.abs(Math.sin(heading))>.5;
        double hx=horizontal?length:.77,hz=horizontal?.77:length;
        return new double[]{x-hx,x+hx,z-hz,z+hz};
    }
    private static boolean overlaps(double[] a,double[] b) {
        return a[0]<b[1]+.03&&a[1]+.03>b[0]&&a[2]<b[3]+.03&&a[3]+.03>b[2];
    }
    static boolean bodiesOverlap(Traffic.Pose a,double al,Traffic.Pose b,double bl) {
        double afx=Math.sin(a.heading()),afz=Math.cos(a.heading()),bfx=Math.sin(b.heading()),bfz=Math.cos(b.heading());
        double dx=a.x()-b.x(),dz=a.z()-b.z();
        return axisOverlaps(dx,dz,afx,afz,afx,afz,al,bfx,bfz,bl)&&axisOverlaps(dx,dz,afz,-afx,afx,afz,al,bfx,bfz,bl)
            &&axisOverlaps(dx,dz,bfx,bfz,afx,afz,al,bfx,bfz,bl)&&axisOverlaps(dx,dz,bfz,-bfx,afx,afz,al,bfx,bfz,bl);
    }
    private static boolean axisOverlaps(double dx,double dz,double x,double z,double afx,double afz,double al,double bfx,double bfz,double bl) {
        return Math.abs(dx*x+dz*z)<al*Math.abs(afx*x+afz*z)+.77*Math.abs(afz*x-afx*z)+bl*Math.abs(bfx*x+bfz*z)+.77*Math.abs(bfz*x-bfx*z)+.03;
    }
    private static boolean safe(Car c,double nx,double nz,double heading,List<Car> active,long minute,CityLayout layout,boolean organic) {
        double[] box=bounds(nx,nz,heading,c.length());
        for(Car other:active)if(other!=c&&overlaps(box,bounds(other.x,other.z,other.heading,other.length())))return false;
        if(organic) {
            var physical=IslandGeography.pose(new Traffic.Pose(nx,nz,heading));
            for(Car other:active)if(other!=c&&bodiesOverlap(physical,c.length(),other.physical,other.length()))return false;
        }
        // A junction is reserved until the complete vehicle has cleared it.
        double approach=organic?8:4.5,radius=organic?4:1.6;
        double firstX=layout.centerX()+Math.floor((nx-layout.centerX()-approach)/layout.spacing())*layout.spacing();
        double firstZ=layout.centerZ()+Math.floor((nz-layout.centerZ()-approach)/layout.spacing())*layout.spacing();
        for(double x=firstX;x<=nx+approach;x+=layout.spacing())for(double z=firstZ;z<=nz+approach;z+=layout.spacing()) {
            double[] junction={x-radius,x+radius,z-radius,z+radius};
            if(!overlaps(box,junction))continue;
            boolean inside=overlaps(bounds(c.x,c.z,c.heading,c.length()),junction);
            if(!inside&&Traffic.hasSignal(layout,x,z)&&!Traffic.green(minute,x,z,layout).equals(Math.abs(Math.sin(heading))>.5?"east_west":"north_south"))return false;
            for(Car other:active)if(other!=c&&overlaps(bounds(other.x,other.z,other.heading,other.length()),junction))return false;
        }
        return true;
    }
    private static Car service(Traffic.Route r,long day,int departure,CityLayout layout) {
        Car c=new Car();c.route=r;c.start=day*1440+departure;c.id="bus:"+r.id()+":"+day+":"+departure;c.path=busPath(r,layout);
        Node first=c.path.getFirst(),next=c.path.get(1);c.x=first.x;c.z=first.z;c.heading=Math.atan2(next.x-first.x,next.z-first.z);return c;
    }
    private static Schedule build(long day,CityLayout layout,boolean organic) {
        Schedule result=new Schedule();List<Car> active=new ArrayList<>(),waiting=new ArrayList<>();
        long beginning=0,end=(day+2)*1440;
        for(long d=0;d<=day+1;d++)for(Traffic.Route r:Traffic.ROUTES)
            for(int dep=r.firstDeparture();dep<=r.lastDeparture();dep+=r.headway())waiting.add(service(r,d,dep,layout));
        waiting.sort(Comparator.comparingLong(c->c.start));
        if(organic)for(Car c:waiting)c.physical=IslandGeography.pose(new Traffic.Pose(c.x,c.z,c.heading));
        // Cars share the same collision and junction rules on the perimeter roads.
        for(int i=0;i<12;i++) {
            Car c=new Car();c.id="car-"+i;c.path=lane(List.of(new Node(layout.x(-60),layout.z(-60),-1),new Node(layout.x(60),layout.z(-60),-1),new Node(layout.x(60),layout.z(60),-1),new Node(layout.x(-60),layout.z(60),-1),new Node(layout.x(-60),layout.z(-60),-1)));
            int side=i/3;Node a=c.path.get(side),b=c.path.get(side+1);double f=(i%3+.5)/3;
            c.x=a.x+(b.x-a.x)*f;c.z=a.z+(b.z-a.z)*f;c.next=side+1;c.heading=Math.atan2(b.x-a.x,b.z-a.z);
            if(organic)c.physical=IslandGeography.pose(new Traffic.Pose(c.x,c.z,c.heading));active.add(c);
        }
        for(long minute=beginning;minute<=end;minute++) {
            for(Iterator<Car> it=waiting.iterator();it.hasNext();) {
                Car c=it.next();if(c.start>minute)break;
                if(safe(c,c.x,c.z,c.heading,active,minute,layout,organic)) {c.stop=0;c.arrivals.add(minute);c.dwell=minute+BUS_DWELL;active.add(c);it.remove();}
            }
            boolean recordMotion=minute>=day*1440&&minute<(day+1)*1440;
            Map<String,List<Traffic.Pose>> motion=new HashMap<>();
            if(recordMotion)for(Car c:active){List<Traffic.Pose> poses=new ArrayList<>();poses.add(new Traffic.Pose(c.x,c.z,c.heading));motion.put(c.id,poses);}
            for(int step=0;step<12;step++){for(Car c:active) {
                if(c.done||minute<c.dwell)continue;
                if(c.stop>=0) {c.departures.add(minute-1);c.stop=-1;}
                Node n=c.path.get(c.next);double dx=n.x-c.x,dz=n.z-c.z,d=Math.hypot(dx,dz);
                double speed=layout.vehicleSpeed()/12;
                double h=Math.atan2(dx,dz),amount=Math.min(speed,d),nx=c.x+Math.sin(h)*amount,nz=c.z+Math.cos(h)*amount;
                if(!safe(c,nx,nz,h,active,minute,layout,organic))continue;
                c.x=nx;c.z=nz;c.heading=h;
                if(organic)c.physical=IslandGeography.pose(new Traffic.Pose(c.x,c.z,c.heading));
                if(d<=speed+.000001) {
                    c.next++;
                    if(n.stop>=0) {c.stop=n.stop;c.arrivals.add(minute);c.dwell=minute+BUS_DWELL;}
                    if(c.next==c.path.size()) {
                        if(c.route==null)c.next=1;
                        else {c.done=true;c.departures.add(minute+1);result.trips.put(c.id,new Traffic.Trip(c.id,c.route,c.start,List.copyOf(c.arrivals),List.copyOf(c.departures)));}
                    }
                }
            }
            if(recordMotion)for(Car c:active)motion.get(c.id).add(new Traffic.Pose(c.x,c.z,c.heading));
            }
            if(recordMotion)result.motion.put(minute,motion);
            Map<String,Traffic.Frame> snapshot=new LinkedHashMap<>();
            for(Car c:active)if(!c.done||c.dwell>minute)snapshot.put(c.id,new Traffic.Frame(c.x,c.z,c.heading,c.stop));
            if(minute>=Math.max(0,day-1)*1440)result.frames.put(minute,Collections.unmodifiableMap(snapshot));
            long now=minute;active.removeIf(c->c.done&&c.dwell<=now);
        }
        return result;
    }
}
