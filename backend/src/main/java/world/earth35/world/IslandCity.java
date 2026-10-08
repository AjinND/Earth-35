package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import world.earth35.world.State.*;

/** Explicit, once-only migration of the occupied city into its island districts. */
public final class IslandCity {
    private IslandCity() {}
    private record Point(double x,double z) {}
    public static List<Engine.Completion> upgrade(State s) {
        if(s.geographyVersion>=1)return List.of();
        CityLayout layout=CityLayout.ISLAND;
        Map<String,Point> buildings=new HashMap<>(),residents=new HashMap<>();
        for(Building b:s.buildings.values())buildings.put(b.id,new Point(b.x,b.z));
        for(Resident a:s.agents.values())residents.put(a.id,new Point(a.x,a.z));
        List<Conversation> chatting=s.conversations.values().stream().filter(c->!Conversations.closed(s,c)).toList();
        for(Building b:s.buildings.values()) {
            Places.Entrance door=Places.entrance(b);double dx=door.x()-b.x,dz=door.z()-b.z;
            b.x=layout.x(b.x);b.z=layout.z(b.z);
            Traffic.Stop stop=Traffic.stops(layout).stream().filter(p->p.id().equals(b.id)).findFirst().orElse(null);
            if(stop!=null){b.x=stop.x();b.z=stop.z();}
            b.entrance=new Places.Entrance(door.side(),b.x+dx,b.z+dz);
        }
        for(Resident a:s.agents.values()) {
            Point old=residents.get(a.id);Building anchor=s.buildings.get(a.location);
            if(anchor==null)anchor=s.buildings.values().stream().filter(b->{Point p=buildings.get(b.id);return Places.enclosed(b)&&Math.abs(old.x-p.x)<3.35&&Math.abs(old.z-p.z)<3.35;}).findFirst().orElse(null);
            if(anchor!=null) {Point p=buildings.get(anchor.id);a.x=anchor.x+old.x-p.x;a.z=anchor.z+old.z-p.z;}
            else {a.x=layout.migrateAxis(old.x,true);a.z=layout.migrateAxis(old.z,false);}
        }
        // An active conversation keeps its local spacing instead of stretching apart.
        for(Conversation c:chatting) {
            Resident a=s.agents.get(c.a),b=s.agents.get(c.b);Point pa=residents.get(c.a),pb=residents.get(c.b);
            if(a!=null&&b!=null&&Math.hypot(a.x-b.x,a.z-b.z)>10){b.x=a.x+pb.x-pa.x;b.z=a.z+pb.z-pa.z;}
        }
        s.geographyVersion=1;
        List<Engine.Completion> completions=new ArrayList<>();
        for(Resident a:s.agents.values()) {
            Pending p=a.pending;if(p==null)continue;
            if(Travel.moving(a)) {
                Building target=Travel.walkingDestination(s,p);
                if(target==null)throw new IllegalStateException("Saved journey has no destination: "+p.target);
                // Keep action ID, pace, review/social state and itinerary. Only the remaining geometry changes.
                if(p.entering)p.route=new ArrayList<>(List.of(new double[]{target.x,target.z}));
                else {
                    String choice=p.routeChoice==null?"shortest":p.routeChoice;
                    p.route=Travel.path(s,a,target,choice);p.exitDoor=Travel.exitDoor(s,a);
                }
                p.frontDoorRoute=true;
            }else if(p.type.equals("WAIT_BUS")||p.type.equals("RIDE_BUS")) {
                Traffic.Trip trip=Traffic.trip(s,p.vehicleId);
                if(trip==null)throw new IllegalStateException("Saved bus service is unavailable: "+p.vehicleId);
                int boarding=trip.route().stops().indexOf(p.boardingStop),alighting=-1;
                for(int i=boarding+1;i<trip.route().stops().size();i++)if(trip.route().stops().get(i).equals(p.target)){alighting=i;break;}
                if(boarding<0||alighting<0)throw new IllegalStateException("Saved bus itinerary is invalid");
                if(p.type.equals("WAIT_BUS")&&trip.departures().get(boarding)<s.minute) {
                    // Retain an unpaid wait on the next service of the same route.
                    String routeId=trip.route().id();
                    Traffic.Departure next=Traffic.network(s).departures().stream()
                        .filter(d->d.route().equals(routeId)&&d.stop().equals(p.boardingStop)&&d.remainingStops().contains(p.target))
                        .findFirst().orElse(null);
                    if(next==null) {
                        // Overnight wait remains accepted; find the next day's first service.
                        long day=Math.floorDiv(s.minute,1440)+1;
                        trip=Traffic.trip(s,"bus:"+trip.route().id()+":"+day+":"+trip.route().firstDeparture());
                    }else trip=Traffic.trip(s,next.bus());
                    p.vehicleId=trip.id();
                }
                p.boardingMinute=Math.max(s.minute,trip.arrivals().get(boarding));p.until=trip.arrivals().get(alighting);
                if(p.type.equals("WAIT_BUS")) {Traffic.Stop stop=Traffic.stop(s,p.boardingStop);a.x=stop.x();a.z=stop.z();p.waitingPoint=null;p.queueIndex=null;p.route.clear();}
                else {
                    Traffic.Vehicle bus=Traffic.vehicles(s).stream().filter(v->v.id().equals(p.vehicleId)).findFirst().orElse(null);
                    if(bus!=null){a.x=bus.x();a.z=bus.z();}
                    if(p.until<=s.minute) {
                        Traffic.Stop stop=Traffic.stop(s,p.target);a.x=stop.x();a.z=stop.z();a.location=stop.id();
                        if(p.finalDestination!=null&&!p.finalDestination.equals(stop.id()))Travel.startWalk(s,a,p.finalDestination,p.id,p.finalDestination,null,null);
                        else {completions.add(new Engine.Completion(p.id,"completed","Arrived by bus at "+stop.name()));a.pending=null;}
                    }
                }
            }
        }
        s.version++;return completions;
    }
    public static List<Engine.Completion> upgrade(ObjectNode data) throws Exception {
        if(data.path("geographyVersion").asInt()>=1)return List.of();
        ObjectMapper json=new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        State s=json.treeToValue(data,State.class);var completions=upgrade(s);
        for(Building b:s.buildings.values()) {
            ObjectNode saved=(ObjectNode)data.path("buildings").path(b.id);
            saved.put("x",b.x);saved.put("z",b.z);saved.set("entrance",json.valueToTree(b.entrance));
        }
        for(Resident a:s.agents.values()) {
            ObjectNode saved=(ObjectNode)data.path("agents").path(a.id);saved.put("x",a.x);saved.put("z",a.z);
            if(a.pending==null)saved.putNull("pending");
            else {
                ObjectNode pending=(ObjectNode)saved.path("pending"),next=json.valueToTree(a.pending);
                next.fields().forEachRemaining(field->pending.set(field.getKey(),field.getValue()));
            }
            saved.put("location",a.location);
        }
        data.put("geographyVersion",s.geographyVersion);data.put("version",s.version);return completions;
    }
}
