package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Timetabled transit and signals share the authoritative simulated clock. */
public final class Traffic {
    private Traffic() {}
    public record Platform(String route,double x,double z,double heading) {}
    public record Stop(String id,String name,double x,double z,double roadX,double roadZ,double heading,List<Platform> platforms) {
        public Stop(String id,String name,double x,double z,double roadX,double roadZ) {this(id,name,x,z,roadX,roadZ,0,List.of());}
    }
    public record Route(String id,String name,List<String> stops,int firstDeparture,int lastDeparture,int headway,int capacity,long fareCents) {}
    public record Pose(double x,double z,double heading) {}
    public record Vehicle(String id,String type,String route,String name,double x,double z,double heading,String stop,int seatsAvailable,List<Pose> motion) {}
    public record Signal(double x,double z,String vehicleGreen,boolean pedestrianGreen) {}
    // Bus-route turning junctions plus the central square and park approach.
    private static final List<Pose> SIGNAL_JUNCTIONS=List.of(
        new Pose(-48,-48,0),new Pose(24,-48,0),new Pose(24,-24,0),new Pose(48,-24,0),
        new Pose(48,36,0),new Pose(-48,36,0),new Pose(24,0,0),new Pose(24,24,0));
    public static boolean hasSignal(double x,double z) {
        return hasSignal(CityLayout.PROTOTYPE,x,z);
    }
    public static boolean hasSignal(CityLayout layout,double x,double z) {return SIGNAL_JUNCTIONS.stream().anyMatch(p->layout.x(p.x)==x&&layout.z(p.z)==z);}
    public record Departure(String bus,String route,String stop,long arrivalMinute,long departureMinute,List<String> remainingStops,int seatsAvailable) {}
    public record Network(List<Stop> stops,List<Route> routes,List<Vehicle> vehicles,List<Signal> signals,List<Departure> departures) {}
    public static final List<Stop> STOPS=List.of(
        new Stop("stop-residential","Residential bus stop",-45.6,-42,-48,-42),
        new Stop("stop-downtown","Downtown bus stop",30,-21.6,30,-24),
        new Stop("stop-market","Market bus stop",42,-21.6,42,-24),
        new Stop("stop-park","Central Park bus stop",50.4,30,48,30),
        new Stop("stop-garden","Garden Quarter bus stop",-45.6,30,-48,30));
    public static final List<Route> ROUTES=List.of(
        new Route("R1","City loop clockwise",List.of("stop-residential","stop-downtown","stop-market","stop-park","stop-garden","stop-residential"),360,1260,30,20,250),
        new Route("R2","City loop anticlockwise",List.of("stop-residential","stop-garden","stop-park","stop-market","stop-downtown","stop-residential"),375,1275,30,20,250));
    record Frame(double x,double z,double heading,int stopIndex) {}
    public record Trip(String id,Route route,long start,List<Long> arrivals,List<Long> departures) {}
    public static Stop stop(String id) {return STOPS.stream().filter(s->s.id.equals(id)).findFirst().orElse(null);}
    public static List<Stop> stops(State s) {return stops(CityLayout.of(s));}
    public static List<Stop> stops(CityLayout layout) {return STOPS.stream().map(stop->{
        double x=layout.x(stop.roadX),z=layout.z(stop.roadZ);
        double defaultHeading=stop.x!=stop.roadX?0:-Math.PI/2;
        Pose road=new Pose(x,z,defaultHeading);
        if(layout.version()>=2)road=IslandGeography.pose(road);
        double r1=switch(stop.id){case "stop-residential","stop-garden"->Math.PI;case "stop-downtown","stop-market"->Math.PI/2;default->0;};
        Pose clockwise=new Pose(x,z,r1);if(layout.version()>=2)clockwise=IslandGeography.pose(clockwise);
        Platform p1=curb("R1",clockwise),p2=curb("R2",new Pose(clockwise.x,clockwise.z,clockwise.heading+Math.PI));
        // Warp the centerline once, then offset along its physical normal. A
        // warped logical offset otherwise shrinks and tilts the platform.
        Platform anchor=curb("",road);
        return new Stop(stop.id,stop.name,anchor.x,anchor.z,road.x,road.z,road.heading,List.of(p1,p2));
    }).toList();}
    private static Platform curb(String route,Pose road) {return new Platform(route,road.x+2.4*Math.cos(road.heading),road.z-2.4*Math.sin(road.heading),road.heading);}
    public static Stop stop(State s,String id) {return stops(s).stream().filter(stop->stop.id.equals(id)).findFirst().orElse(null);}
    public static Platform platform(State s,String stop,String route) {
        Stop location=stop(s,stop);return location==null?null:location.platforms.stream().filter(p->p.route.equals(route)).findFirst().orElse(null);
    }
    static Building platformBuilding(State s,String stop,String route) {
        Platform p=platform(s,stop,route);Building source=s.buildings.get(stop);
        Building target=new Building(source.id,source.name,"bus_stop",source.district,p.x,p.z,source.capacity);
        target.entrance=new Places.Entrance("open",p.x,p.z);return target;
    }
    public static Route route(String id) {return ROUTES.stream().filter(r->r.id.equals(id)).findFirst().orElse(null);}
    public static void populate(State s) {
        for(Stop stop:stops(s)) {
            Building b=new Building(stop.id,stop.name,"bus_stop","Transit",stop.x,stop.z,40);
            b.description="Public bus platform. Check the route, remaining stops and next departures before boarding.";
            s.buildings.putIfAbsent(b.id,b);
        }
        s.accounts.putIfAbsent("transit",0L);
    }
    /** Repair saved platforms and transfer active itineraries to the new dwell timetable. */
    public static List<Engine.Completion> upgradeStops(State s) {
        if(s.transitVersion>=1)return List.of();
        Engine.Effects effects=new Engine.Effects();
        for(Stop stop:stops(s)) {
            Building b=s.buildings.get(stop.id);if(b==null)continue;
            double dx=stop.x-b.x,dz=stop.z-b.z;
            for(Resident a:s.agents.values())if(stop.id.equals(a.location)&&(a.pending==null||!List.of("WAIT_BUS","BOARD_BUS").contains(a.pending.type))){a.x+=dx;a.z+=dz;}
            b.x=stop.x;b.z=stop.z;b.entrance=new Places.Entrance("open",b.x,b.z);
        }
        for(Resident a:s.agents.values())if(a.pending!=null) {
            Pending p=a.pending;
            if(Travel.moving(a)&&stop(s,p.target)!=null) {
                p.route=Travel.path(s,a,Travel.walkingDestination(s,p),p.routeChoice==null?"shortest":p.routeChoice);p.frontDoorRoute=true;
            }
            if(!List.of("WAIT_BUS","BOARD_BUS","RIDE_BUS").contains(p.type))continue;
            Trip t=trip(s,p.vehicleId);if(t==null)continue;
            int boarding=t.route.stops.indexOf(p.boardingStop),alighting=-1;
            for(int i=boarding+1;i<t.route.stops.size();i++)if(t.route.stops.get(i).equals(p.target)){alighting=i;break;}
            if(boarding<0||alighting<0)continue;
            p.until=t.arrivals.get(alighting);
            if(p.type.equals("WAIT_BUS")) {
                p.boardingMinute=Math.max(s.minute,t.arrivals.get(boarding));p.queueIndex=queueIndex(s,p.boardingStop,t.route.id,a.id);
                p.waitingPoint=waitingPoint(s,p.boardingStop,t.route.id,p.queueIndex);p.route=approach(s,a,p.boardingStop,p.waitingPoint);
            }else if(p.type.equals("RIDE_BUS")) {
                Frame f=frame(s,t,s.minute);if(f!=null){a.x=f.x;a.z=f.z;}
                if(p.until<=s.minute)tickPassenger(s,a,effects);
            }
        }
        s.transitVersion=1;s.version++;return effects.completions;
    }
    public static List<Engine.Completion> upgradeStops(com.fasterxml.jackson.databind.node.ObjectNode data) throws Exception {
        if(data.path("transitVersion").asInt()>=1)return List.of();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        State s=json.treeToValue(data,State.class);var completions=upgradeStops(s);
        var buildings=(com.fasterxml.jackson.databind.node.ObjectNode)data.path("buildings");
        for(Stop stop:stops(s))if(buildings.path(stop.id) instanceof com.fasterxml.jackson.databind.node.ObjectNode saved) {
            Building b=s.buildings.get(stop.id);saved.put("x",b.x);saved.put("z",b.z);saved.set("entrance",json.valueToTree(b.entrance));
        }
        for(Resident a:s.agents.values())if(data.path("agents").path(a.id) instanceof com.fasterxml.jackson.databind.node.ObjectNode saved) {
            saved.put("x",a.x);saved.put("z",a.z);saved.put("location",a.location);
            // Merge owned fields so private extensions in existing saves survive.
            var next=json.valueToTree(a.pending);
            if(a.pending==null)saved.putNull("pending");
            else if(saved.path("pending") instanceof com.fasterxml.jackson.databind.node.ObjectNode pending)next.fields().forEachRemaining(field->pending.set(field.getKey(),field.getValue()));
            else saved.set("pending",next);
        }
        data.put("transitVersion",s.transitVersion);data.put("version",s.version);return completions;
    }
    public static String green(long minute) {
        int phase=(int)Math.floorMod(minute,12);
        return phase<3?"east_west":phase==3?"clearance":phase<7?"north_south":phase==7?"clearance":phase<11?"pedestrians":"clearance";
    }
    public static String green(long minute,double x,double z) {
        return green(minute,x,z,CityLayout.PROTOTYPE);
    }
    public static String green(long minute,double x,double z,CityLayout layout) {
        if(layout.version()>=2){var logical=IslandGeography.inverse(x,z);x=logical.x();z=logical.z();}
        // Stable offsets keep each junction independent across restarts and days.
        long offset=Math.floorMod(Math.round((x-layout.centerX())/layout.spacing())*5+Math.round((z-layout.centerZ())/layout.spacing())*7,12);
        return green(minute+offset);
    }
    private static Trip trip(State s,Route r,long day,int departure) {
        return TrafficFlow.schedule(day,CityLayout.of(s)).trips.get("bus:"+r.id+":"+day+":"+departure);
    }
    public static Trip trip(String id) {
        return trip(null,id);
    }
    public static Trip trip(State s,String id) {
        if(id==null)return null;
        try {
            String[] parts=id.split(":");if(parts.length!=4||!parts[0].equals("bus"))return null;
            Route r=route(parts[1]);if(r==null)return null;
            long day=Long.parseLong(parts[2]);int departure=Integer.parseInt(parts[3]);
            if(day<0||departure<r.firstDeparture||departure>r.lastDeparture||(departure-r.firstDeparture)%r.headway!=0)return null;
            return trip(s,r,day,departure);
        }catch(NumberFormatException e){return null;}
    }
    private static Frame frame(State s,Trip t,long minute) {
        Frame f=TrafficFlow.schedule(Math.floorDiv(minute,1440),CityLayout.of(s)).frames.getOrDefault(minute,Map.of()).get(t.id);
        if(f!=null&&s!=null&&s.geographyVersion>=2){var pose=IslandGeography.pose(new Pose(f.x,f.z,f.heading));return new Frame(pose.x(),pose.z(),pose.heading(),f.stopIndex);}
        return f;
    }

    public static int seats(State s,String bus) {
        Trip t=trip(s,bus);if(t==null)return 0;
        return Math.max(0,t.route.capacity-(int)s.agents.values().stream().filter(a->a.pending!=null&&bus.equals(a.pending.vehicleId)).count());
    }
    private static List<Trip> tripsNear(State s,long minute,boolean future) {
        List<Trip> trips=new ArrayList<>();long day=Math.floorDiv(minute,1440);
        for(long d=Math.max(0,day-1);d<=day+(future?1:0);d++)for(Route r:ROUTES)
            for(int dep=r.firstDeparture;dep<=r.lastDeparture;dep+=r.headway) {
                Trip t=trip(s,r,d,dep);
                if(t.departures.getLast()>=minute&&t.start<=minute+(future?180:0))trips.add(t);
            }
        return trips;
    }
    public static List<Vehicle> vehicles(State s) {
        List<Vehicle> vehicles=new ArrayList<>();
        TrafficFlow.schedule(Math.floorDiv(s.minute,1440),CityLayout.of(s)).frames.getOrDefault(s.minute,Map.of()).forEach((id,f)->{
            Trip t=id.startsWith("bus:")?trip(s,id):null;
            Pose pose=new Pose(f.x,f.z,f.heading);List<Pose> motion=TrafficFlow.schedule(Math.floorDiv(s.minute,1440),CityLayout.of(s)).motion.getOrDefault(s.minute,Map.of()).get(id);
            if(s.geographyVersion>=2){pose=IslandGeography.pose(pose);if(motion!=null)motion=motion.stream().map(IslandGeography::pose).toList();}
            vehicles.add(new Vehicle(id,t==null?"car":"bus",t==null?"":t.route.id,t==null?"Car":t.route.name,
                pose.x(),pose.z(),pose.heading(),t!=null&&f.stopIndex>=0?t.route.stops.get(f.stopIndex):"",t==null?0:seats(s,id),motion));
        });
        return vehicles;
    }

    public static Network network(State s) {
        List<Signal> signals=new ArrayList<>();
        for(Pose junction:SIGNAL_JUNCTIONS) {
            CityLayout layout=CityLayout.of(s);double x=layout.x(junction.x),z=layout.z(junction.z);
            if(layout.version()>=2){var p=IslandGeography.survey(x,z);x=p.x();z=p.z();}
            String green=green(s.minute,x,z,layout);
            signals.add(new Signal(x,z,green,green.equals("pedestrians")));
        }
        List<Departure> departures=new ArrayList<>();
        for(Trip t:tripsNear(s,s.minute,true))for(int i=0;i<t.route.stops.size()-1;i++)
            if(t.departures.get(i)>=s.minute&&t.arrivals.get(i)<=s.minute+90)
                departures.add(new Departure(t.id,t.route.id,t.route.stops.get(i),t.arrivals.get(i),t.departures.get(i),t.route.stops.subList(i+1,t.route.stops.size()),seats(s,t.id)));
        departures.sort(Comparator.comparingLong(Departure::departureMinute));
        return new Network(stops(s),ROUTES,vehicles(s),signals,List.copyOf(departures));
    }
    public static Network observation(State s,Resident a) {
        Network all=network(s);Map<String,Departure> next=new LinkedHashMap<>();
        for(Departure d:all.departures)next.putIfAbsent(d.route+":"+d.stop,d);
        return new Network(stops(s),ROUTES,
            all.vehicles.stream().filter(v->Math.hypot(v.x-a.x,v.z-a.z)<=24).toList(),
            all.signals.stream().filter(signal->Math.hypot(signal.x-a.x,signal.z-a.z)<=12).toList(),List.copyOf(next.values()));
    }
    public static void board(State s,Resident a,String bus,String destination,String actionId,Engine.Effects e,boolean wait) {
        Stop platform=stop(s,a.location);Engine.require(platform!=null,"Move to a bus stop first");
        Engine.require(stop(s,destination)!=null&&!destination.equals(a.location),"Choose a different destination bus stop in item");
        Trip chosen=null;int boarding=-1,alighting=-1;
        if(!wait)Engine.require(vehicles(s).stream().anyMatch(v->v.id.equals(bus)&&v.type.equals("bus")),"That bus is not currently in service here");
        List<Trip> options=wait?tripsNear(s,s.minute,true):Collections.singletonList(trip(s,bus));
        for(Trip t:options) {
            if(t==null||wait&&!t.route.id.equals(bus))continue;
            for(int i=0;i<t.route.stops.size()-1;i++) {
                if(!t.route.stops.get(i).equals(platform.id)||t.departures.get(i)<s.minute)continue;
                if(wait&&t.arrivals.get(i)>s.minute+60)continue;
                if(!wait&&(t.arrivals.get(i)>s.minute||t.departures.get(i)<s.minute))continue;
                // A service cannot accept someone who would still be crossing
                // the street or approaching its door after departure.
                int queue=queueIndex(s,platform.id,t.route.id,a.id);
                double[] waiting=waitingPoint(s,platform.id,t.route.id,queue);
                List<double[]> approach=approach(s,a,platform.id,waiting);
                long ready=s.minute+(long)Math.ceil(Travel.routeDistance(a,approach)/CityLayout.of(s).walkingSpeed());
                if(Math.max(ready,t.arrivals.get(i))+boardingMinutes(s,waiting,platform(s,platform.id,t.route.id))>t.departures.get(i))continue;
                for(int j=i+1;j<t.route.stops.size();j++)if(t.route.stops.get(j).equals(destination)) {
                    if(chosen==null||t.departures.get(i)<chosen.departures.get(boarding)){chosen=t;boarding=i;alighting=j;}break;
                }
            }
        }
        Engine.require(chosen!=null,wait?"No service on this route reaches that stop from here within 60 minutes":"That bus is not at your stop or does not reach your destination on this trip");
        Engine.require(seats(s,chosen.id)>0,"This bus is full; choose another service");
        Engine.require(a.money>=chosen.route.fareCents,"Insufficient funds for the bus fare");
        Pending p=Engine.pending(actionId,"WAIT_BUS",destination,chosen.arrivals.get(alighting));
        p.vehicleId=chosen.id;p.boardingStop=platform.id;p.boardingMinute=Math.max(s.minute,chosen.arrivals.get(boarding));
        p.queueIndex=queueIndex(s,platform.id,chosen.route.id,a.id);p.waitingPoint=waitingPoint(s,platform.id,chosen.route.id,p.queueIndex);
        p.route.addAll(approach(s,a,platform.id,p.waitingPoint));a.pending=p;
        if(p.route.isEmpty()&&p.boardingMinute<=s.minute)startBoarding(s,a,e);
        else e.facts.add(new Engine.Fact("transit",a.id,bus,a.name+" is waiting on the "+chosen.route.name+" platform for "+stop(destination).name+"."));
    }
    private static int queueIndex(State s,String stop,String route,String resident) {
        Set<Integer> occupied=new HashSet<>();
        for(Resident other:s.agents.values()) {
            Pending p=other.pending;if(other.id.equals(resident)||p==null||!stop.equals(p.boardingStop)||p.queueIndex==null||!List.of("WAIT_BUS","BOARD_BUS").contains(p.type))continue;
            Trip t=trip(s,p.vehicleId);if(t!=null&&route.equals(t.route.id))occupied.add(p.queueIndex);
        }
        int index=0;while(occupied.contains(index))index++;return index;
    }
    private static double[] waitingPoint(State s,String stop,String route,int index) {
        Platform p=platform(s,stop,route);
        // The original 12-unit blocks leave only a thin curb corridor beside
        // apartments. Preserve their compatibility without putting queue slots
        // inside buildings; the current island has spacious .7-unit spacing.
        boolean prototype=CityLayout.of(s).version()==0;
        double along=-(prototype?.3:.7)*(index%(prototype?10:5)),outside=(prototype?.2:.7)*(index/(prototype?10:5));
        return new double[]{p.x+Math.sin(p.heading)*along+Math.cos(p.heading)*outside,p.z+Math.cos(p.heading)*along-Math.sin(p.heading)*outside};
    }
    private static List<double[]> approach(State s,Resident a,String stop,double[] waiting) {
        Trip booked=a.pending!=null&&a.pending.vehicleId!=null?trip(s,a.pending.vehicleId):null;
        String direction=booked!=null?booked.route.id:null;
        // The target's normal identifies its route even before a reservation
        // has been assigned to this resident.
        Stop location=stop(s,stop);
        if(direction==null)direction=location.platforms.stream().min(Comparator.comparingDouble(p->Math.hypot(p.x-waiting[0],p.z-waiting[1]))).orElseThrow().route;
        Platform platform=platform(s,stop,direction);
        Building target=new Building("platform:"+stop,"Bus platform","bus_stop","Transit",waiting[0],waiting[1],40);
        target.entrance=new Places.Entrance("open",waiting[0],waiting[1]);
        double across=(a.x-location.roadX)*Math.cos(platform.heading)-(a.z-location.roadZ)*Math.sin(platform.heading);
        if(across>=0)return Travel.path(s,a,target,"shortest");
        // Crossing at the stop's center would intersect the parked bus and
        // leave riders permanently waiting for their own service to clear.
        // Cross behind its rear instead, checking traffic at every footstep.
        double rightX=Math.cos(platform.heading),rightZ=-Math.sin(platform.heading),forwardX=Math.sin(platform.heading),forwardZ=Math.cos(platform.heading);
        double farX=location.roadX-rightX*2.4-forwardX*4,farZ=location.roadZ-rightZ*2.4-forwardZ*4;
        Building crossing=new Building("platform-crossing:"+stop,"Bus platform crossing","bus_stop","Transit",farX,farZ,40);
        crossing.entrance=new Places.Entrance("open",farX,farZ);
        List<double[]> path=new ArrayList<>(Travel.path(s,a,crossing,"shortest"));
        path.add(new double[]{location.roadX+rightX*2.4-forwardX*4,location.roadZ+rightZ*2.4-forwardZ*4});
        path.add(new double[]{waiting[0],waiting[1]});return path;
    }
    private static int boardingMinutes(State s,double[] waiting,Platform p) {
        double along=(waiting[0]-p.x)*Math.sin(p.heading)+(waiting[1]-p.z)*Math.cos(p.heading);
        double outside=(waiting[0]-p.x)*Math.cos(p.heading)-(waiting[1]-p.z)*Math.sin(p.heading);
        return Math.max(1,(int)Math.ceil((Math.abs(1.35-along)+1.6+outside)/CityLayout.of(s).walkingSpeed()))+1;
    }
    private static void startBoarding(State s,Resident a,Engine.Effects e) {
        Pending p=a.pending;Frame f=frame(s,trip(s,p.vehicleId),s.minute);
        if(f==null||f.stopIndex<0)return;
        double rightX=Math.cos(f.heading),rightZ=-Math.sin(f.heading),forwardX=Math.sin(f.heading),forwardZ=Math.cos(f.heading);
        double across=(a.x-f.x)*rightX+(a.z-f.z)*rightZ;
        p.type="BOARD_BUS";p.startedAt=s.minute;p.route.clear();
        p.route.add(new double[]{f.x+rightX*across+forwardX*1.35,f.z+rightZ*across+forwardZ*1.35});
        p.route.add(new double[]{f.x+rightX*1.02+forwardX*1.35,f.z+rightZ*1.02+forwardZ*1.35});
        p.route.add(new double[]{f.x+rightX*.25+forwardX*1.35,f.z+rightZ*.25+forwardZ*1.35});
        e.facts.add(new Engine.Fact("transit",a.id,trip(s,p.vehicleId).route.id,a.name+" is walking to the bus door."));
    }
    private static void startRide(State s,Resident a,Engine.Effects e) {
        Pending p=a.pending;Trip t=trip(s,p.vehicleId);
        Engine.pay(s,a.id,"transit",t.route.fareCents,"bus_fare",e);p.type="RIDE_BUS";p.boardingCompletedMinute=s.minute;a.location="bus:"+p.vehicleId;
        e.facts.add(new Engine.Fact("transit",a.id,t.route.id,a.name+" boarded "+t.route.name+" for "+stop(p.target).name+"."));
    }
    public static void tickPassenger(State s,Resident a,Engine.Effects e) {
        Pending p=a.pending;p.motion.clear();
        if(p.type.equals("WAIT_BUS")) {
            if(p.waitingPoint==null) {
                Trip t=trip(s,p.vehicleId);p.queueIndex=queueIndex(s,p.boardingStop,t.route.id,a.id);
                p.waitingPoint=waitingPoint(s,p.boardingStop,t.route.id,p.queueIndex);p.route=approach(s,a,p.boardingStop,p.waitingPoint);
            }
            movePassenger(s,a,false);
            Trip t=trip(s,p.vehicleId);
            int stopIndex=t.route.stops.indexOf(p.boardingStop);
            if(s.minute>=p.boardingMinute&&p.route.isEmpty()) {
                if(a.money<t.route.fareCents){e.completions.add(new Engine.Completion(p.id,"rejected","Insufficient funds at boarding"));a.pending=null;return;}
                if(s.minute+boardingMinutes(s,p.waitingPoint,platform(s,p.boardingStop,t.route.id))<=t.departures.get(stopIndex))startBoarding(s,a,e);
                else if(s.minute>t.departures.get(stopIndex)-boardingMinutes(s,p.waitingPoint,platform(s,p.boardingStop,t.route.id))) {
                    // Traffic can delay the curb approach. Remain visible here
                    // and catch the next matching service instead of vanishing.
                    String finalDestination=p.finalDestination;
                    try {a.pending=null;board(s,a,t.route.id,p.target,p.id,e,true);a.pending.finalDestination=finalDestination;}
                    catch(Engine.Invalid unavailable){e.completions.add(new Engine.Completion(p.id,"rejected",unavailable.getMessage()));a.pending=null;}
                }
            }
            return;
        }
        if(p.type.equals("BOARD_BUS")) {
            Frame f=frame(s,trip(s,p.vehicleId),s.minute);
            Engine.require(f!=null&&f.stopIndex>=0,"The bus must remain at its platform while boarding");
            movePassenger(s,a,true);
            if(p.route.isEmpty())startRide(s,a,e);
            return;
        }
        if(p.type.equals("RIDE_BUS")) {
            Frame f=frame(s,trip(s,p.vehicleId),s.minute);if(f!=null){a.x=f.x;a.z=f.z;}
            if(s.minute>=p.until) {
                Stop stop=stop(s,p.target);Platform curb=platform(s,p.target,trip(s,p.vehicleId).route.id);a.x=curb.x;a.z=curb.z;a.location=stop.id;
                e.facts.add(new Engine.Fact("transit",a.id,stop.id,a.name+" left the bus at "+stop.name+"."));
                if(p.finalDestination!=null&&!p.finalDestination.equals(stop.id))Travel.startWalk(s,a,p.finalDestination,p.id,p.finalDestination,null,null);
                else Engine.finish(a,e,"Arrived by bus at "+stop.name);
            }
        }
    }
    private static void movePassenger(State s,Resident a,boolean boarding) {
        Pending p=a.pending;double budget=CityLayout.of(s).walkingSpeed();
        double heading=platform(s,p.boardingStop,trip(s,p.vehicleId).route.id).heading;
        p.motion.add(new Pose(a.x,a.z,heading));
        List<RoadMotion> crossing=boarding?List.of():crossingTraffic(s);List<Vehicle> vehicles=boarding?List.of():vehicles(s);
        while(budget>0&&!p.route.isEmpty()) {
            double[] point=p.route.getFirst();double d=Math.hypot(point[0]-a.x,point[1]-a.z);
            if(d<.001){p.route.removeFirst();continue;}
            double step=Math.min(.5,Math.min(d,budget)),nx=a.x+(point[0]-a.x)*step/d,nz=a.z+(point[1]-a.z)*step/d;
            boolean occupied=vehicles.stream().anyMatch(v->{
                double dx=nx-v.x,dz=nz-v.z,across=dx*Math.cos(v.heading)-dz*Math.sin(v.heading),along=dx*Math.sin(v.heading)+dz*Math.cos(v.heading);
                return Math.abs(across)<1.05&&Math.abs(along)<(v.type.equals("bus")?2.8:1.7);
            });
            if(!boarding&&(occupied||!canWalk(CityLayout.of(s),a.x,a.z,nx,nz,crossing))){p.waitingReason="Waiting for traffic to reach the correct bus platform";break;}
            p.waitingReason=null;heading=Math.atan2(nx-a.x,nz-a.z);a.x=nx;a.z=nz;budget-=step;p.motion.add(new Pose(a.x,a.z,heading));
            if(d<=step+.001)p.route.removeFirst();
        }
    }
    private static Double crossing(CityLayout layout,double from,double to,boolean east) {
        if(Math.abs(from-to)<.001)return null;
        double lo=Math.min(from,to),hi=Math.max(from,to);
        for(double road:layout.roads(east))if(lo<road-1.6&&hi>=road-1.6||lo<=road+1.6&&hi>road+1.6)return road;
        return null;
    }
    record RoadMotion(boolean bus,List<Pose> poses) {}
    static List<RoadMotion> crossingTraffic(long minute) {
        return crossingTraffic(minute,CityLayout.PROTOTYPE);
    }
    static List<RoadMotion> crossingTraffic(State s) {return crossingTraffic(s.minute,CityLayout.of(s));}
    private static List<RoadMotion> crossingTraffic(long minute,CityLayout layout) {
        List<RoadMotion> traffic=new ArrayList<>();
        // A walker needs two minutes to clear both lanes. Include the current
        // motion and two future intervals, including vehicles joining service.
        for(long t=minute;t<=minute+2;t++) {
            TrafficFlow.Schedule schedule=TrafficFlow.schedule(Math.floorDiv(t,1440),layout);
            schedule.motion.getOrDefault(t,Map.of()).forEach((id,poses)->traffic.add(new RoadMotion(id.startsWith("bus:"),poses)));
        }
        return traffic;
    }
    public static boolean canWalk(long minute,double x,double z,double nx,double nz) {
        return canWalk(x,z,nx,nz,crossingTraffic(minute));
    }
    static boolean canWalk(double x,double z,double nx,double nz,List<RoadMotion> traffic) {
        return canWalk(CityLayout.PROTOTYPE,x,z,nx,nz,traffic);
    }
    static boolean canWalk(CityLayout layout,double x,double z,double nx,double nz,List<RoadMotion> traffic) {
        if(layout.version()>=2) {
            var from=IslandGeography.inverse(x,z);var to=IslandGeography.inverse(nx,nz);
            return canWalk(CityLayout.ISLAND,from.x(),from.z(),to.x(),to.z(),traffic);
        }
        // A person already crossing clears the road even after WALK ends.
        boolean onRoad=layout.onRoad(x,z);
        if(onRoad)return true;
        Double roadX=crossing(layout,x,nx,true),roadZ=crossing(layout,z,nz,false);
        if(roadX==null&&roadZ==null)return true;
        // Check the whole crossing, not just the next footstep. Signal colour
        // alone cannot make an occupied road safe or an empty road dangerous.
        double loX=roadX==null?Math.min(x,nx)-.35:roadX-1.95;
        double hiX=roadX==null?Math.max(x,nx)+.35:roadX+1.95;
        double loZ=roadZ==null?Math.min(z,nz)-.35:roadZ-1.95;
        double hiZ=roadZ==null?Math.max(z,nz)+.35:roadZ+1.95;
        for(RoadMotion vehicle:traffic)for(int i=0;i<vehicle.poses.size();i++) {
            Pose a=vehicle.poses.get(i),b=vehicle.poses.get(Math.min(i+1,vehicle.poses.size()-1));
            double length=vehicle.bus?2.8:1.7;
            double ax=Math.abs(Math.sin(a.heading))*length+Math.abs(Math.cos(a.heading))*.77;
            double az=Math.abs(Math.cos(a.heading))*length+Math.abs(Math.sin(a.heading))*.77;
            double bx=Math.abs(Math.sin(b.heading))*length+Math.abs(Math.cos(b.heading))*.77;
            double bz=Math.abs(Math.cos(b.heading))*length+Math.abs(Math.sin(b.heading))*.77;
            if(Math.min(a.x-ax,b.x-bx)<hiX&&Math.max(a.x+ax,b.x+bx)>loX
                &&Math.min(a.z-az,b.z-bz)<hiZ&&Math.max(a.z+az,b.z+bz)>loZ)return false;
        }
        return true;
    }
}
