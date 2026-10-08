package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Facts and route alternatives for the resident to choose from. */
public final class Travel {
    private Travel() {}
    public record Option(String id,int walkingMinutes,int runningMinutes,double distance,List<String> passes,boolean shortest,double walkingEffort,double runningEnergy) {}
    public record TransitOption(String id,String route,String boardingStop,String alightingStop,String bus,
        int walkToStopMinutes,long departureMinute,long arrivalMinute,int walkFromStopMinutes,int totalMinutes,
        double walkingDistanceSaved,long fareCents) {}
    public record Destination(String destination,boolean sheltered,List<Option> routes,List<TransitOption> transit,Places.Entrance entrance,Places.Hours hours) {}
    public record Commute(String action,String target,String item,int estimatedMinutes,int lateByMinutes,String reason) {}
    public record Situation(boolean reconsider,boolean sheltered,boolean rainProtected,double wetness,
                            Long shiftStart,Integer minutesUntilShift,List<Destination> destinations,Commute commute,boolean raincoatAvailable) {
        public Situation(boolean reconsider,boolean sheltered,boolean rainProtected,double wetness,Long shiftStart,Integer minutesUntilShift,List<Destination> destinations) {
            this(reconsider,sheltered,rainProtected,wetness,shiftStart,minutesUntilShift,destinations,null,false);
        }
    }
    public static boolean moving(Resident a) {return a.pending!=null&&("MOVE".equals(a.pending.type)||"RUN".equals(a.pending.type));}
    public static boolean sheltered(State s,Resident a) {
        if(a.pending!=null&&a.pending.type.equals("RIDE_BUS"))return true;
        if(moving(a)&&Places.enclosed(source(s,a)))return true;
        return sheltered(s.buildings.get(a.location));
    }
    public static boolean sheltered(Building b) {
        return Places.enclosed(b);
    }
    public static boolean reconsider(State s,Resident a) {
        return moving(a)&&(s.minute>=a.pending.reviewAfter||!Engine.weather(s.minute).equals(a.pending.travelWeather));
    }
    public static void reviewed(State s,Resident a) {a.pending.reviewAfter=s.minute+15;a.pending.travelWeather=Engine.weather(s.minute);Social.reviewed(s,a);}
    public static String defaultRoute(Resident a) {
        return "shortest";
    }
    public static Building destination(State s,String target) {
        Building b=s.buildings.get(target);
        return b!=null?b:s.geographyVersion>=2?IslandGeography.coordinate(target):null;
    }
    public static List<double[]> path(Resident a,Building b,String choice) {
        return path(null,a,b,choice);
    }
    private static Building source(State s,Resident a) {
        if(s==null)return null;
        Building source=s.buildings.get(a.location);
        if(source==null)source=s.buildings.values().stream().filter(p->Places.enclosed(p)&&Math.abs(a.x-p.x)<3.35&&Math.abs(a.z-p.z)<3.35).findFirst().orElse(null);
        return source;
    }
    public static double[] exitDoor(State s,Resident a) {
        Building source=source(s,a);
        if(!Places.enclosed(source))return null;
        var door=Places.entrance(source);
        return Math.hypot(a.x-door.x(),a.z-door.z())<.001?null:new double[]{door.x(),door.z()};
    }
    public static List<double[]> path(State s,Resident a,Building b,String choice) {
        Engine.require(List.of("shortest","east-west-first","north-south-first").contains(choice),"Choose a route ID from travel.destinations");
        if(b.id.equals(a.location)&&(!b.type.equals("bus_stop")||Math.hypot(a.x-b.x,a.z-b.z)<.001))return new ArrayList<>();
        if(s!=null&&s.geographyVersion>=2)return IslandNavigation.path(s,a,b);
        var goal=Places.entrance(b);Resident start=new Resident();start.x=a.x;start.z=a.z;
        List<double[]> exit=new ArrayList<>();
        Building source=source(s,a);
        if(Places.enclosed(source)) {
            var door=Places.entrance(source);
            exit.add(new double[]{door.x(),a.z});exit.add(new double[]{door.x(),door.z()});start.x=door.x();start.z=door.z();
        }
        if(choice.equals("shortest")) {
            var east=path(s,a,b,"east-west-first");var north=path(s,a,b,"north-south-first");
            return routeDistance(a,east)<=routeDistance(a,north)?east:north;
        }
        List<double[]> best=null;double bestDistance=Double.POSITIVE_INFINITY;
        // Both sides of every street are usable. Enumerate the adjacent network
        // junctions and choose the shortest legal orthogonal connection.
        CityLayout layout=CityLayout.of(s);
        for(double sx:layout.sidewalks(start.x,true))for(double sz:layout.sidewalks(start.z,false))for(boolean horizontalExit:new boolean[]{true,false})
            for(double gx:layout.sidewalks(goal.x(),true))for(double gz:Places.enclosed(b)?new double[]{goal.z()}:layout.sidewalks(goal.z(),false))for(boolean horizontalEntry:new boolean[]{true,false}) {
                if(Places.enclosed(source)&&!horizontalExit)continue;
                if(Places.enclosed(b)&&!horizontalEntry)continue;
                List<double[]> points=new ArrayList<>();
                points.add(horizontalExit?new double[]{sx,start.z}:new double[]{start.x,sz});points.add(new double[]{sx,sz});
                points.add(choice.equals("east-west-first")?new double[]{gx,sz}:new double[]{sx,gz});points.add(new double[]{gx,gz});
                points.add(horizontalEntry?new double[]{goal.x(),gz}:new double[]{gx,goal.z()});points.add(new double[]{goal.x(),goal.z()});
                var outside=remainingPath(start,b,points);
                if(!clearOfBuildings(s,start,outside))continue;
                List<double[]> route=new ArrayList<>();double px=a.x,pz=a.z;
                for(double[] point:exit)if(Math.hypot(point[0]-px,point[1]-pz)>.000001){route.add(point);px=point[0];pz=point[1];}
                route.addAll(outside);double length=routeDistance(a,route);
                if(length<bestDistance-.000001) {best=route;bestDistance=length;}
            }
        if(best==null)throw new IllegalStateException("No legal sidewalk connection to "+b.id);
        return best;
    }
    private static boolean clearOfBuildings(State s,Resident start,List<double[]> route) {
        if(s==null)return true;
        double x=start.x,z=start.z;
        for(double[] point:route) {
            for(Building b:s.buildings.values())if(Places.enclosed(b)) {
                boolean vertical=Math.abs(x-point[0])<.000001;
                if(vertical&&Math.abs(x-b.x)<3.35-.000001&&Math.min(z,point[1])<b.z+3.35-.000001&&Math.max(z,point[1])>b.z-3.35+.000001)return false;
                if(!vertical&&Math.abs(z-b.z)<3.35-.000001&&Math.min(x,point[0])<b.x+3.35-.000001&&Math.max(x,point[0])>b.x-3.35+.000001)return false;
            }
            x=point[0];z=point[1];
        }
        return true;
    }
    public static double routeDistance(Resident a,List<double[]> points) {
        double x=a.x,z=a.z,distance=0;
        for(var p:points){distance+=Math.hypot(p[0]-x,p[1]-z);x=p[0];z=p[1];}return distance;
    }
    public static double segmentEffort(State s,double x,double z,double nx,double nz) {
        return s!=null&&s.geographyVersion>=4?TerrainElevation.segmentEffort(x,z,nx,nz):Math.hypot(nx-x,nz-z);
    }
    public record RouteCost(double distance,double walkingEffort,double runningEnergyEffort) {}
    public static RouteCost routeCost(State s,Resident a,List<double[]> points) {
        double x=a.x,z=a.z,distance=0,effort=0,energy=0;
        for(var p:points) {
            double d=Math.hypot(p[0]-x,p[1]-z);distance+=d;
            if(s!=null&&s.geographyVersion>=4) {var segment=TerrainElevation.segment(x,z,p[0],p[1]);effort+=segment.walkingEffort();energy+=segment.runningEnergyEffort();}
            else {effort+=d;energy+=d;}
            x=p[0];z=p[1];
        }
        return new RouteCost(distance,effort,energy);
    }
    public static double routeEffort(State s,Resident a,List<double[]> points) {return routeCost(s,a,points).walkingEffort();}
    /** Running until an energy reserve is reached, then walking the remaining route. */
    private static double pacedMinutes(State s,Resident a,List<double[]> points,double energyBudget) {
        CityLayout layout=CityLayout.of(s);double x=a.x,z=a.z,total=0;
        for(var p:points) {
            double distance=Math.hypot(p[0]-x,p[1]-z);int samples=Math.max(1,(int)Math.ceil(distance/TerrainElevation.SAMPLE_STEP));
            double sx=x,sz=z;
            for(int i=1;i<=samples;i++) {
                double nx=x+(p[0]-x)*i/samples,nz=z+(p[1]-z)*i/samples;
                var segment=s.geographyVersion>=4?TerrainElevation.segment(sx,sz,nx,nz):new TerrainElevation.Segment(Math.hypot(nx-sx,nz-sz),Math.hypot(nx-sx,nz-sz),0);
                double duration=segment.walkingEffort()/layout.runningSpeed();
                double energy=(.025*segment.walkingEffort()+.2*segment.runningEnergyEffort())/layout.runningSpeed();
                double runFraction=energy>0?Math.min(1,Math.max(0,energyBudget)/energy):1;
                total+=duration*runFraction+segment.walkingEffort()/layout.walkingSpeed()*(1-runFraction);energyBudget-=energy*runFraction;sx=nx;sz=nz;
            }
            x=p[0];z=p[1];
        }
        return total;
    }
    static List<double[]> remainingPath(Resident a,Building destination,List<double[]> points) {
        // Saved routes can also pass the destination before their final waypoint.
        // Stop at the first arrival and remove redundant straight-line detours.
        var entrance=Places.entrance(destination);
        Building endpoint=new Building();endpoint.x=entrance.x();endpoint.z=entrance.z();
        if(Math.hypot(a.x-endpoint.x,a.z-endpoint.z)<.000001)return new ArrayList<>();
        List<double[]> route=new ArrayList<>();route.add(new double[]{a.x,a.z});
        for(double[] waypoint:points) {
            double[] previous=route.getLast();
            boolean arrived=onSegment(previous,waypoint,endpoint);
            double[] point=arrived?new double[]{endpoint.x,endpoint.z}:waypoint;
            if(Math.hypot(point[0]-previous[0],point[1]-previous[1])>=.000001) {
                while(route.size()>1) {
                    double[] before=route.get(route.size()-2),last=route.getLast();
                    boolean vertical=Math.abs(before[0]-last[0])<.000001&&Math.abs(last[0]-point[0])<.000001;
                    boolean horizontal=Math.abs(before[1]-last[1])<.000001&&Math.abs(last[1]-point[1])<.000001;
                    if(!vertical&&!horizontal)break;
                    route.removeLast();
                }
                route.add(point);
            }
            if(arrived)break;
        }
        route.removeFirst();return route;
    }
    private static boolean onSegment(double[] from,double[] to,Building destination) {
        double dx=to[0]-from[0],dz=to[1]-from[1],length=Math.hypot(dx,dz);
        if(length<.000001)return Math.hypot(destination.x-from[0],destination.z-from[1])<.000001;
        double along=((destination.x-from[0])*dx+(destination.z-from[1])*dz)/length;
        double across=Math.abs((destination.x-from[0])*dz-(destination.z-from[1])*dx)/length;
        return along>=-.000001&&along<=length+.000001&&across<.000001;
    }
    public static Situation situation(State s,Resident a) {
        CityLayout layout=CityLayout.of(s);
        List<Destination> destinations=new ArrayList<>();
        Traffic.Network transport=Traffic.network(s);
        Map<String,Leg> access=new HashMap<>();
        for(Building b:s.buildings.values()) {
            if(b.type.equals("construction"))continue;
            if(b.capacity==1&&!b.id.equals(a.home))continue;
            List<Option> options=new ArrayList<>();
            for(String id:layout.version()>=2?List.of("shortest"):List.of("east-west-first","north-south-first")) {
                double x=a.x,z=a.z,distance=0;int crossings=0;Set<String> passes=new LinkedHashSet<>();
                List<double[]> points=path(s,a,b,id);
                for(double[] p:points) {
                    distance+=Math.hypot(p[0]-x,p[1]-z);crossings+=(int)(Math.abs(p[0]-x)/layout.spacing())+(int)(Math.abs(p[1]-z)/layout.spacing());
                    for(Building landmark:s.buildings.values())if(landmark.capacity>1&&!landmark.id.equals(b.id)
                        &&landmark.x>=Math.min(x,p[0])-5&&landmark.x<=Math.max(x,p[0])+5&&landmark.z>=Math.min(z,p[1])-5&&landmark.z<=Math.max(z,p[1])+5)passes.add(landmark.id);
                    x=p[0];z=p[1];
                }
                double indoor=Places.enclosed(b)&&!b.id.equals(a.location)?Math.hypot(b.x-Places.entrance(b).x(),b.z-Places.entrance(b).z()):0;
                RouteCost cost=routeCost(s,a,points);distance+=indoor;double effort=cost.walkingEffort()+indoor;
                int walkingMinutes=(int)Math.ceil(effort/layout.walkingSpeed())+crossings*3,runningMinutes=(int)Math.ceil(effort/layout.runningSpeed())+crossings*3;
                double energy=.225*runningMinutes+.2*(cost.runningEnergyEffort()-cost.walkingEffort())/layout.runningSpeed();
                options.add(new Option(id,walkingMinutes,runningMinutes,Math.round(distance*10)/10.0,passes.stream().limit(6).toList(),false,Math.round(effort*100)/100.0,Math.round(energy*100)/100.0));
            }
            double shortest=options.stream().mapToDouble(Option::distance).min().orElse(0);
            options=options.stream().map(o->new Option(o.id(),o.walkingMinutes(),o.runningMinutes(),o.distance(),o.passes(),o.distance()<=shortest+.000001,o.walkingEffort(),o.runningEnergy())).sorted(Comparator.comparingDouble(Option::distance)).toList();
            destinations.add(new Destination(b.id,sheltered(b),options,transit(s,a,b,transport,access,shortest),Places.entrance(b),Places.travelHours(s,a,b)));
        }
        Job job=s.jobs.get(a.job);Long start=null;Integer remaining=null;
        if(job!=null&&!DailyRoutines.onLeave(a,s.minute)&&(a.workDay!=Math.floorDiv(s.minute,1440)||a.workedMinutes==0)) {
            long day=Math.floorDiv(s.minute,1440);
            if(job.workDays.contains((int)Math.floorMod(day,7))&&Math.floorMod(s.minute,1440)+60<=job.endMinute)start=day*1440+job.startMinute;
            else start=DailyRoutines.nextShift(job,s.minute);
            remaining=(int)(start-s.minute);
        }
        Commute commute=null;
        if(job!=null&&start!=null) {
            Destination destination=destinations.stream().filter(d->d.destination().equals(job.building)).findFirst().orElseThrow();
            Option route=destination.routes().getFirst();String action="MOVE",item=route.id();int minutes=route.walkingMinutes();
            if(a.location.equals(job.building)){action=DailyRoutines.workOpen(job,s.minute)?"WORK":"LOOK";item=null;minutes=0;}
            else if(remaining<minutes) {
                // Running drains energy; estimate walking for the part that cannot
                // be run while keeping a small reserve for the shift itself.
                Building workplace=s.buildings.get(job.building);List<double[]> points=path(s,a,workplace,route.id());
                if(Places.enclosed(workplace)&&!workplace.id.equals(a.location)){points=new ArrayList<>(points);points.add(new double[]{workplace.x,workplace.z});}
                double effort=routeEffort(s,a,points);
                int waitingMinutes=route.walkingMinutes()-(int)Math.ceil(effort/layout.walkingSpeed());
                int pacedMinutes=(int)Math.ceil(pacedMinutes(s,a,points,Math.max(0,a.energy-5-.225*waitingMinutes)))+waitingMinutes;
                if(a.energy>=10&&pacedMinutes<minutes){action="RUN";minutes=pacedMinutes;}
                TransitOption bus=destination.transit().stream().min(Comparator.comparingInt(TransitOption::totalMinutes)).orElse(null);
                if(bus!=null&&bus.totalMinutes()<minutes){action="TRAVEL_BUS";item=bus.id();minutes=bus.totalMinutes();}
            }
            commute=new Commute(action,job.building,item,minutes,Math.max(0,minutes-remaining),"If you choose to attend work, compare arrival with the shift start, energy and fare; free time is yours");
        }
        return new Situation(reconsider(s,a),sheltered(s,a),a.weatherProtectionUntil>s.minute||a.raincoatWorn&&a.inventory.getOrDefault("raincoat",0)>0,a.wetness,start,remaining,destinations,commute,a.inventory.getOrDefault("raincoat",0)>0);
    }
    private static double distance(State s,Resident a,Building b) {
        return routeDistance(a,path(s,a,b,"shortest"))+(Places.enclosed(b)&&!b.id.equals(a.location)?Math.hypot(b.x-Places.entrance(b).x(),b.z-Places.entrance(b).z()):0);
    }
    private record Leg(double distance,int minutes) {}
    private static Leg leg(State s,Resident a,Building b) {
        CityLayout layout=CityLayout.of(s);
        if(a.location.equals(b.id)&&(!b.type.equals("bus_stop")||Math.hypot(a.x-b.x,a.z-b.z)<.001))return new Leg(0,0);
        List<double[]> points=path(s,a,b,"shortest");double indoor=Places.enclosed(b)&&!b.id.equals(a.location)?Math.hypot(b.x-Places.entrance(b).x(),b.z-Places.entrance(b).z()):0;
        double d=routeDistance(a,points)+indoor,effort=routeEffort(s,a,points)+indoor;
        return new Leg(d,(int)Math.ceil(effort/layout.walkingSpeed())+3*(int)(d/layout.spacing()));
    }
    static List<TransitOption> transit(State s,Resident a,Building destination,Traffic.Network network) {
        return transit(s,a,destination,network,new HashMap<>(),distance(s,a,destination));
    }
    private static List<TransitOption> transit(State s,Resident a,Building destination,Traffic.Network network,Map<String,Leg> accessLegs,double direct) {
        List<TransitOption> options=new ArrayList<>();Map<String,Leg> egressLegs=new HashMap<>();
        for(Traffic.Departure departure:network.departures()) {
            Building boarding=Traffic.platformBuilding(s,departure.stop(),departure.route());Leg first=accessLegs.computeIfAbsent(boarding.id+":"+departure.route(),id->leg(s,a,boarding));int access=first.minutes();
            if(s.minute+access+2>departure.departureMinute()||departure.arrivalMinute()>s.minute+access+60||departure.seatsAvailable()<1)continue;
            Traffic.Trip trip=Traffic.trip(s,departure.bus());Traffic.Route route=trip.route();
            if(a.money<route.fareCents())continue;
            int index=route.stops().indexOf(departure.stop());
            for(int j=index+1;j<route.stops().size();j++) {
                Building alighting=Traffic.platformBuilding(s,route.stops().get(j),route.id());Resident from=new Resident();from.id=a.id;from.location=alighting.id;from.x=alighting.x;from.z=alighting.z;
                Leg finalLeg=egressLegs.computeIfAbsent(alighting.id+":"+route.id(),id->leg(s,from,destination));
                double last=finalLeg.distance(),accessDistance=first.distance();
                if(s.geographyVersion<2&&(last>=direct||accessDistance+last>=direct-4))continue;
                int egress=finalLeg.minutes();long arrival=trip.arrivals().get(j);
                if(!Places.canEnter(s,a,destination,arrival+egress))continue;
                options.add(new TransitOption(route.id()+"|"+boarding.id+"|"+alighting.id,route.id(),boarding.id,alighting.id,trip.id(),access,
                    departure.departureMinute(),arrival,egress,(int)(arrival-s.minute)+egress,Math.round((direct-accessDistance-last)*10)/10.0,route.fareCents()));
            }
        }
        options.sort(Comparator.comparingInt(TransitOption::totalMinutes).thenComparing(TransitOption::id));
        return options.stream().limit(3).toList();
    }
    static void startWalk(State s,Resident a,String target,String actionId,String finalDestination,String boardingRoute,String alightingStop) {
        // Internal continuation of an accepted itinerary. Discover a closure at
        // the venue entrance, not remotely when stepping off the bus.
        Pending p=Engine.pending(actionId,"MOVE",target,0);p.routeChoice=defaultRoute(a);
        Building endpoint=boardingRoute==null?s.buildings.get(target):Traffic.platformBuilding(s,target,boardingRoute);
        p.route.addAll(path(s,a,endpoint,p.routeChoice));p.frontDoorRoute=true;p.finalDestination=finalDestination;p.boardingRoute=boardingRoute;p.alightingStop=alightingStop;
        p.exitDoor=exitDoor(s,a);a.pending=p;a.location="road";reviewed(s,a);
    }
    static void startBus(State s,Resident a,String target,String choice,String actionId,Engine.Effects effects) {
        Building b=s.buildings.get(target);Engine.require(b!=null,"Unknown destination");
        Places.requireEntry(s,a,b,s.minute);
        Engine.require(b.capacity!=1||b.id.equals(a.home),"Only your own home is accessible");
        TransitOption plan=transit(s,a,b,Traffic.network(s)).stream().filter(p->p.id.equals(choice)).findFirst().orElse(null);
        Engine.require(plan!=null,"Choose an available itinerary from travel.destinations.transit");
        if(a.pending!=null)effects.completions.add(new Engine.Completion(a.pending.id,"cancelled","Resident chose transit"));
        if(a.location.equals(plan.boardingStop)) {
            a.pending=null;Traffic.board(s,a,plan.route,plan.alightingStop,actionId,effects,true);a.pending.finalDestination=target;
        }else startWalk(s,a,plan.boardingStop,actionId,target,plan.route,plan.alightingStop);
        effects.facts.add(new Engine.Fact("transit",a.id,target,a.name+" chose a bus journey with a walk to the destination."));
    }
    static void walkingArrived(State s,Resident a,Engine.Effects effects) {
        Pending p=a.pending;Building destination=walkingDestination(s,p);
        if(p.entering) {
            a.x=destination.x;a.z=destination.z;a.location=p.target;
            Engine.finish(a,effects,"Arrived inside "+destination.name);return;
        }
        var door=Places.entrance(destination);a.x=door.x();a.z=door.z();
        if(!Places.canEnter(s,a,destination,s.minute)) {
            a.location="road";effects.completions.add(new Engine.Completion(p.id,"rejected",destination.name+" closed during the journey; you remain outside its front entrance"));a.pending=null;return;
        }
        if(Places.enclosed(destination)) {
            // Admission is checked at the door. Complete the indoor leg on
            // later ticks so both entry and exit are visibly through this door.
            p.entering=true;p.route.add(new double[]{destination.x,destination.z});return;
        }
        a.location=p.target;
        if(p.finalDestination!=null&&p.boardingRoute!=null) {
            try {a.pending=null;Traffic.board(s,a,p.boardingRoute,p.alightingStop,p.id,effects,true);a.pending.finalDestination=p.finalDestination;}
            catch(Engine.Invalid unavailable){effects.completions.add(new Engine.Completion(p.id,"rejected",unavailable.getMessage()));a.pending=null;}
        }else Engine.finish(a,effects,"Arrived at "+destination.name);
    }
    static Building walkingDestination(State s,Pending p) {return p.boardingRoute==null?destination(s,p.target):Traffic.platformBuilding(s,p.target,p.boardingRoute);}
}
