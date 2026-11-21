package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Finite seating chosen by residents, held only for the duration of an activity. */
public final class Seating {
    private Seating() {}
    public record View(String location,List<String> objects,int capacity,int occupied,int availableSeats) {}
    public static int capacity(Building place) {
        if(place==null||!place.restAllowed||place.objects.isEmpty())return 0;
        // Outdoor slots match gathering-furniture.ts: two residents per bench.
        int seats=switch(place.type) {
            case "park","garden","plaza","promenade","hangout" -> 4;
            case "river","riverside_walk","mountain","hill_station","trekking_trail" -> 2;
            default -> place.objects.values().stream().mapToInt(label->{
                String name=label.toLowerCase(Locale.ROOT);
                return name.contains("table")?4:name.contains("sofa")?3:name.contains("bench")?2:1;
            }).sum();
        };
        return Math.min(place.capacity,seats);
    }
    public static Integer seatIndex(Resident resident) {
        Pending p=resident.pending;
        return p!=null&&List.of("REST","READ_BOOK").contains(p.type)?p.seatIndex:null;
    }
    public record Position(double x,double z,double heading) {}
    /** Shared by seating, planting and walking; matches gathering-furniture.ts. */
    static double[][] benches(Building b) {
        return switch(b.type) {
            case "park" -> new double[][]{{-3.2,0,Math.PI/2},{3.2,0,-Math.PI/2}};
            case "promenade" -> new double[][]{{-2.5,-1.5,Math.PI/2},{2.5,1.5,-Math.PI/2}};
            case "plaza" -> new double[][]{{-2.2,2.8,0},{2.2,2.8,0}};
            case "garden","hangout" -> new double[][]{{-2.2,2.2,0},{2.2,2.2,0}};
            case "river","riverside_walk","mountain","hill_station","trekking_trail" -> new double[][]{{-2.2,2.5,0}};
            default -> new double[0][];
        };
    }
    /** The same bench dimensions and slots as gathering-furniture.ts, in world coordinates. */
    public static Position position(Building b,Integer index) {
        if(b==null||index==null||index<0)return null;
        double[][] benches=benches(b);
        if(index/2>=benches.length)return null;
        double[] bench=benches[index/2];double offset=index%2==0?-.7:.7;
        return new Position(b.x+bench[0]+offset*Math.cos(bench[2]),b.z+bench[1]-offset*Math.sin(bench[2]),bench[2]);
    }
    public static boolean seated(Resident a) {
        return seatIndex(a)!=null&&a.pending.seatReady;
    }
    /** Approach the front of a reserved bench before sitting; approach time is not reading/rest. */
    static boolean approach(State s,Resident a,List<Traffic.Vehicle> vehicles,List<Traffic.RoadMotion> traffic) {
        Pending p=a.pending;Building b=s.buildings.get(a.location);Position seat=position(b,seatIndex(a));
        if(seat==null||p.seatReady)return false;
        double radius=Math.max(8,b.outdoorRadius);
        if(!p.seatApproachInitialized) {
            p.route=new ArrayList<>();
            for(double frontDistance:new double[]{1.15,.85}) {
                for(double side:new double[]{0,.55,-.55,1.2,-1.2}) {
                    double[] front={seat.x+frontDistance*Math.sin(seat.heading)+side*Math.cos(seat.heading),seat.z+frontDistance*Math.cos(seat.heading)-side*Math.sin(seat.heading)};
                    List<double[]> route=WalkingRoute.path(s,b.id,b.x,b.z,radius,a.x,a.z,front);
                    if(!route.isEmpty()||Engine.distance(a.x,a.z,front[0],front[1])<.01){p.route=route;p.waitingPoint=front;break;}
                }
                if(p.waitingPoint!=null)break;
            }
            p.seatApproachInitialized=true;
            if(p.waitingPoint==null) {
                p.waitingReason="Reserved bench cannot be reached safely; choose another activity";return true;
            }
            p.route.add(new double[]{seat.x,seat.z});
        }
        if(p.route.isEmpty())return true;
        p.motion.clear();
        Engine.walkRoute(s,a,p,vehicles,traffic,false,(x,z)->{
            for(Resident other:s.agents.values())if(!other.id.equals(a.id)&&!Places.enclosed(s.buildings.get(other.location))) {
                double next=Engine.distance(x,z,other.x,other.z),previous=Engine.distance(a.x,a.z,other.x,other.z);
                if(next<.65&&next<previous-1e-7)return false;
            }
            double dx=x-seat.x,dz=z-seat.z,front=dx*Math.sin(seat.heading)+dz*Math.cos(seat.heading),side=dx*Math.cos(seat.heading)-dz*Math.sin(seat.heading);
            double fx=p.waitingPoint[0]-seat.x,fz=p.waitingPoint[1]-seat.z,length=fx*fx+fz*fz;
            double along=Math.max(0,Math.min(1,(dx*fx+dz*fz)/length));
            boolean seatAccess=Math.hypot(dx-along*fx,dz-along*fz)<.26&&front>=-.01;
            return seatAccess||WalkingRoute.allowedFrom(s,b.id,b.x,b.z,radius,a.x,a.z,x,z);
        });
        a.activityMotion=new ArrayList<>(p.motion);
        if(p.route.isEmpty()){p.seatReady=true;a.facingHeading=seat.heading;a.facingUntil=Long.MAX_VALUE;}
        p.startedAt++;p.until++;
        return true;
    }
    public static View view(Building place,Collection<Integer> indices) {
        int capacity=capacity(place);
        long occupied=indices.stream().filter(Objects::nonNull).filter(i->i>=0&&i<capacity).distinct().count();
        return new View(place==null?null:place.id,place==null?List.of():List.copyOf(place.objects.keySet()),
            capacity,(int)occupied,capacity-(int)occupied);
    }
    static void reserve(State state,Resident resident,Pending activity,boolean required) {
        int capacity=capacity(state.buildings.get(resident.location));
        BitSet occupied=new BitSet(capacity);
        for(Resident other:state.agents.values())if(resident.location.equals(other.location)) {
            Integer index=seatIndex(other);
            if(index!=null&&index>=0&&index<capacity)occupied.set(index);
        }
        int index=occupied.nextClearBit(0);
        Engine.require(!required||index<capacity,"All seating here is occupied; choose another activity, REST without a seat, or wait for a free seat");
        activity.seatIndex=index<capacity?index:null;
        activity.seatReady=activity.seatIndex!=null&&position(state.buildings.get(resident.location),activity.seatIndex)==null;
    }
}
