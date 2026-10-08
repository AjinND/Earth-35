package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import world.earth35.world.State.*;
import world.earth35.world.IslandGeography.Point;

/** Deterministic, once-only island layout migration; saved identities and private state survive. */
public final class OrganicCity {
    private OrganicCity() {}
    public static State preview(State saved) {
        State copy=new ObjectMapper().convertValue(saved,State.class);upgrade(copy);return copy;
    }
    private static double random(String id,int salt) {
        long value=Integer.toUnsignedLong((id+":"+salt).hashCode());value=(value*1664525+1013904223L)&0xffffffffL;
        value^=value>>>16;value=(value*2246822519L)&0xffffffffL;return value/4294967296.0;
    }
    public static List<Engine.Completion> upgrade(State s) {
        if(s.geographyVersion>=2)return List.of();
        List<Engine.Completion> completions=new ArrayList<>(IslandCity.upgrade(s));
        Map<String,Point> old=new HashMap<>(),residents=new HashMap<>();
        s.buildings.values().forEach(b->old.put(b.id,new Point(b.x,b.z)));
        s.agents.values().forEach(a->residents.put(a.id,new Point(a.x,a.z)));
        List<Building> placed=new ArrayList<>();
        for(Building b:s.buildings.values().stream().sorted(Comparator.comparing(p->p.id)).toList()) {
            Point p=old.get(b.id),next=null;
            Traffic.Stop stop=Traffic.stops(CityLayout.ORGANIC).stream().filter(t->t.id().equals(b.id)).findFirst().orElse(null);
            if(stop!=null)next=new Point(stop.x(),stop.z());
            else next=switch(b.id) {
                case "mountain"->new Point(-225,-450);
                case "hill-station"->new Point(-365,-330);
                case "trekking-trail"->new Point(-480,-275);
                case "river"->new Point(158,70);
                case "riverside-walk"->new Point(181,110);
                default->null;
            };
            if(next==null)for(int attempt=0;attempt<1000;attempt++) {
                double x=p.x(),z=p.z();
                if(b.type.equals("apartment")) {
                    double[][] centers={{-305,-120},{-230,-20},{-325,70},{-170,-140}};
                    double[] center=centers[(int)(random(b.id,1)*centers.length)];
                    x=center[0]+(random(b.id,attempt*2+11)-.5)*125;z=center[1]+(random(b.id,attempt*2+12)-.5)*115;
                }else if(b.type.equals("house")) {
                    x=-390+random(b.id,attempt*2+11)*235;z=100+random(b.id,attempt*2+12)*112;
                }else {x+=(random(b.id,attempt*2+11)-.5)*46;z+=(random(b.id,attempt*2+12)-.5)*38;}
                Point candidate=IslandGeography.survey(x,z);
                double radius=Places.enclosed(b)?7:10;
                if(!IslandGeography.dryLand(candidate.x(),candidate.z())||IslandGeography.roadDistance(candidate.x(),candidate.z())<radius)continue;
                if(placed.stream().anyMatch(other->Math.hypot(other.x-candidate.x(),other.z-candidate.z())<(Places.enclosed(other)&&Places.enclosed(b)?11:16)))continue;
                next=candidate;break;
            }
            if(next==null)throw new IllegalStateException("Unable to place "+b.id+" without overlap");
            b.x=next.x();b.z=next.z();b.entrance=new Places.Entrance(Places.enclosed(b)?"front":"open",b.x,b.z+(Places.enclosed(b)?3.6:0));placed.add(b);
        }
        add(s,"north-beach","North Shore Beach",-355,-645);add(s,"south-beach","South Coast Beach",-150,350);
        add(s,"western-woods","Western Woodland",-595,-100);add(s,"eastern-shore","Eastern Shore",250,-80);
        s.geographyVersion=2;
        for(Resident a:s.agents.values()) {
            Point before=residents.get(a.id);Building anchor=s.buildings.get(a.location);
            if(anchor==null)anchor=s.buildings.values().stream().filter(b->old.containsKey(b.id)&&Places.enclosed(b)&&Math.abs(before.x()-old.get(b.id).x())<3.35&&Math.abs(before.z()-old.get(b.id).z())<3.35).findFirst().orElse(null);
            Point next=anchor!=null&&old.containsKey(anchor.id)?new Point(anchor.x+before.x()-old.get(anchor.id).x(),anchor.z+before.z()-old.get(anchor.id).z()):IslandGeography.survey(before.x(),before.z());
            a.x=next.x();a.z=next.z();
            if(anchor==null)for(int i=0;i<100&&(!IslandGeography.dryLand(a.x,a.z)||s.buildings.values().stream().anyMatch(b->Places.enclosed(b)&&Math.abs(a.x-b.x)<3.5&&Math.abs(a.z-b.z)<3.5));i++) {a.x=next.x()+Math.cos(i*2.4)*(i+1);a.z=next.z()+Math.sin(i*2.4)*(i+1);}
        }
        for(Conversation c:s.conversations.values())if(!Conversations.closed(s,c)) {
            Resident a=s.agents.get(c.a),b=s.agents.get(c.b);Point pa=residents.get(c.a),pb=residents.get(c.b);
            if(a!=null&&b!=null&&Math.hypot(a.x-b.x,a.z-b.z)>10){b.x=a.x+pb.x()-pa.x();b.z=a.z+pb.z()-pa.z();}
        }
        for(Resident a:s.agents.values())if(a.pending!=null) {
            Pending p=a.pending;
            if(Travel.moving(a)) {
                Building target=Travel.walkingDestination(s,p);
                if(target==null)throw new IllegalStateException("Saved journey has no destination: "+p.target);
                p.route=p.entering?new ArrayList<>(List.of(new double[]{target.x,target.z})):Travel.path(s,a,target,p.routeChoice==null?"shortest":p.routeChoice);
                p.exitDoor=p.entering?null:Travel.exitDoor(s,a);p.frontDoorRoute=true;
            }else if(p.type.equals("WAIT_BUS")||p.type.equals("RIDE_BUS"))relocatePassenger(s,a,completions);
        }
        s.version++;return completions;
    }
    private static void relocatePassenger(State s,Resident a,List<Engine.Completion> completions) {
        Pending p=a.pending;Traffic.Trip trip=Traffic.trip(s,p.vehicleId);
        if(trip==null)throw new IllegalStateException("Saved bus service is unavailable: "+p.vehicleId);
        int boarding=trip.route().stops().indexOf(p.boardingStop),alighting=-1;
        for(int i=boarding+1;i<trip.route().stops().size();i++)if(trip.route().stops().get(i).equals(p.target)){alighting=i;break;}
        if(boarding<0||alighting<0)throw new IllegalStateException("Saved bus itinerary is invalid");
        if(p.type.equals("WAIT_BUS")&&trip.departures().get(boarding)<s.minute) {
            String route=trip.route().id();
            var next=Traffic.network(s).departures().stream().filter(d->d.route().equals(route)&&d.stop().equals(p.boardingStop)&&d.remainingStops().contains(p.target)).findFirst().orElse(null);
            trip=next!=null?Traffic.trip(s,next.bus()):Traffic.trip(s,"bus:"+route+":"+(Math.floorDiv(s.minute,1440)+1)+":"+trip.route().firstDeparture());
            p.vehicleId=trip.id();
        }
        p.until=trip.arrivals().get(alighting);
        if(p.type.equals("WAIT_BUS")) {
            p.boardingMinute=Math.max(s.minute,trip.arrivals().get(boarding));
            var stop=Traffic.stop(s,p.boardingStop);a.x=stop.x();a.z=stop.z();
            p.waitingPoint=null;p.queueIndex=null;p.route.clear();
        }else {
            var vehicle=Traffic.vehicles(s).stream().filter(v->v.id().equals(p.vehicleId)).findFirst().orElse(null);
            if(vehicle!=null){a.x=vehicle.x();a.z=vehicle.z();}
            if(p.until<=s.minute) {
                var stop=Traffic.stop(s,p.target);a.x=stop.x();a.z=stop.z();a.location=stop.id();
                if(p.finalDestination!=null&&!p.finalDestination.equals(stop.id()))Travel.startWalk(s,a,p.finalDestination,p.id,p.finalDestination,null,null);
                else {completions.add(new Engine.Completion(p.id,"completed","Arrived by bus at "+stop.name()));a.pending=null;}
            }
        }
    }
    public static List<Engine.Completion> upgrade(ObjectNode data) throws Exception {
        if(data.path("geographyVersion").asInt()>=2)return List.of();
        ObjectMapper json=new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        State s=json.treeToValue(data,State.class);
        Map<String,JsonNode> pendingBefore=new HashMap<>();
        s.agents.values().forEach(a->pendingBefore.put(a.id,json.valueToTree(a.pending)));
        var completions=upgrade(s);
        ObjectNode buildings=(ObjectNode)data.path("buildings");
        for(Building b:s.buildings.values()) {
            if(!buildings.has(b.id)){buildings.set(b.id,json.valueToTree(b));continue;}
            ObjectNode saved=(ObjectNode)buildings.path(b.id);
            saved.put("x",b.x);saved.put("z",b.z);saved.set("entrance",json.valueToTree(b.entrance));
        }
        for(Resident a:s.agents.values()) {
            ObjectNode saved=(ObjectNode)data.path("agents").path(a.id);
            saved.put("x",a.x);saved.put("z",a.z);
            if(!Objects.equals(saved.path("location").asText(null),a.location))saved.put("location",a.location);
            JsonNode before=pendingBefore.get(a.id),next=json.valueToTree(a.pending);
            if(before.equals(next))continue;
            if(a.pending==null)saved.putNull("pending");
            else if(!(saved.path("pending") instanceof ObjectNode))saved.set("pending",next);
            else {
                ObjectNode pending=(ObjectNode)saved.path("pending");
                next.fields().forEachRemaining(field->{
                    if(!Objects.equals(before.get(field.getKey()),field.getValue()))pending.set(field.getKey(),field.getValue());
                });
            }
        }
        data.put("geographyVersion",s.geographyVersion);data.put("version",s.version);return completions;
    }
    private static void add(State s,String id,String name,double x,double z) {
        Building b=new Building(id,name,"wilderness","Wilderness",x,z,100);b.restAllowed=true;b.social=true;
        b.description="Open island landscape, freely explorable on foot. You may choose any nearby dry-land coordinate with MOVE or RUN target point:x,z.";
        b.entrance=Places.entrance(b);s.buildings.putIfAbsent(id,b);
    }
}
