package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.BiPredicate;
import world.earth35.world.State.*;
import world.earth35.world.IslandGeography.Point;

/** Versioned connected terrain, bounded trails and safe outdoor arrivals shared with the renderer. */
public final class IslandLandscapes {
    private IslandLandscapes() {}
    public record Lake(String id,String name,double x,double z,double radiusX,double radiusZ,double level) {}
    public record Viewpoint(String id,String name,String district,double x,double z,String description) {}
    public record CoastalZone(String id,String name,String kind,double x,double z,double radiusX,double radiusZ) {}
    public record TerrainRelief(String id,double x,double z,double radiusX,double radiusZ,double height) {}
    public record ProtectedCliff(String id,double x,double z,double radiusX,double radiusZ) {}
    public record Trail(String id,String name,String surface,List<List<Double>> points) {}
    /** Every bridge runs east/west; width is the usable deck between its rails. */
    public record Bridge(String id,String name,double x,double z,double length,double width) {}
    public record Specification(int version,Lake lake,int lakeRiverStart,int lakeRiverEnd,int cliffRiverEnd,
                                List<List<Double>> trail,List<List<Double>> fallsTrail,List<CoastalZone> coastalZones,
                                List<TerrainRelief> terrainRelief,List<ProtectedCliff> protectedCliffs,List<Trail> trails,
                                List<Bridge> bridges,List<Viewpoint> viewpoints) {}
    public static final Specification SPEC=load();
    private static Specification load() {
        try(var stream=IslandLandscapes.class.getResourceAsStream("/city/island-landscapes-v1.json")) {
            if(stream==null)throw new IllegalStateException("Missing island landscape specification");
            return new ObjectMapper().readValue(stream,Specification.class);
        }catch(Exception error){throw new ExceptionInInitializerError(error);}
    }
    public static double lakeRadius(double x,double z) {
        Lake lake=SPEC.lake();double dx=(x-lake.x())/lake.radiusX(),dz=(z-lake.z())/lake.radiusZ(),angle=Math.atan2(dz,dx);
        return Math.hypot(dx,dz)/(1+.09*Math.sin(angle*3)+.045*Math.cos(angle*5));
    }
    public static boolean clear(double x,double z) {
        if(!highlandClear(x,z))return false;
        for(ProtectedCliff cliff:SPEC.protectedCliffs()) {
            double dx=(x-cliff.x())/cliff.radiusX(),dz=(z-cliff.z())/cliff.radiusZ();
            if(dx*dx+dz*dz<1)return false;
        }
        return true;
    }
    public static Bridge bridgeAt(double x,double z) {
        for(Bridge bridge:SPEC.bridges())
            if(Math.abs(x-bridge.x())<=bridge.length()/2+1e-9&&Math.abs(z-bridge.z())<=bridge.width()/2+1e-9)return bridge;
        return null;
    }
    public static List<List<List<Double>>> allTrails() {
        List<List<List<Double>>> trails=new ArrayList<>(List.of(SPEC.trail(),SPEC.fallsTrail()));
        SPEC.trails().forEach(trail->trails.add(trail.points()));return List.copyOf(trails);
    }
    /** Reserve only marked trail corridors and exact protected cliff footprints. */
    public static boolean footprintClear(double x,double z,double radius) {
        for(ProtectedCliff cliff:SPEC.protectedCliffs()) {
            double dx=Math.max(0,Math.abs(x-cliff.x())-radius)/cliff.radiusX();
            double dz=Math.max(0,Math.abs(z-cliff.z())-radius)/cliff.radiusZ();
            if(dx*dx+dz*dz<1)return false;
        }
        // Visible trails are 1.7 units wide; leave a half-unit verge outside them.
        double verge=.85+.5;
        for(var trail:allTrails())for(int i=1;i<trail.size();i++) {
            var a=trail.get(i-1);var b=trail.get(i);
            double ax=a.get(0),az=a.get(1),bx=b.get(0),bz=b.get(1);
            if(x+radius+verge<Math.min(ax,bx)||x-radius-verge>Math.max(ax,bx)
                ||z+radius+verge<Math.min(az,bz)||z-radius-verge>Math.max(az,bz))continue;
            Building box=new Building();box.x=x;box.z=z;
            if(IslandNavigation.intersects(new Point(ax,az),new Point(bx,bz),box,radius))return false;
            double distance=Math.min(boxDistanceSquared(ax,az,x,z,radius),boxDistanceSquared(bx,bz,x,z,radius));
            for(double dx:new double[]{-radius,radius})for(double dz:new double[]{-radius,radius})
                distance=Math.min(distance,segmentDistanceSquared(x+dx,z+dz,ax,az,bx,bz));
            if(distance<verge*verge)return false;
        }
        return true;
    }
    private static double boxDistanceSquared(double x,double z,double cx,double cz,double radius) {
        double dx=Math.max(0,Math.abs(x-cx)-radius),dz=Math.max(0,Math.abs(z-cz)-radius);return dx*dx+dz*dz;
    }
    private static double segmentDistanceSquared(double x,double z,double ax,double az,double bx,double bz) {
        double dx=bx-ax,dz=bz-az,length=dx*dx+dz*dz,t=length==0?0:Math.max(0,Math.min(1,((x-ax)*dx+(z-az)*dz)/length));
        double px=x-ax-t*dx,pz=z-az-t*dz;return px*px+pz*pz;
    }
    /** Keep V24's historical flood/cliff validation independent of later additions. */
    public static boolean highlandClear(double x,double z) {
        // Keep navigation mesh generation cheap outside this bounded highland region.
        if(x<-180||x>-60||z<-430||z>-330)return true;
        if(lakeRadius(x,z)<1.035)return false;
        double distance=Double.POSITIVE_INFINITY,position=0;
        for(int i=1;i<IslandGeography.RIVER.size();i++) {
            Point a=IslandGeography.RIVER.get(i-1),b=IslandGeography.RIVER.get(i);
            double dx=b.x()-a.x(),dz=b.z()-a.z(),t=Math.max(0,Math.min(1,((x-a.x())*dx+(z-a.z())*dz)/(dx*dx+dz*dz)));
            double d=Math.hypot(x-a.x()-t*dx,z-a.z()-t*dz);
            if(d<distance){distance=d;position=i-1+t;}
        }
        double width=2.2+3.2*position/(IslandGeography.RIVER.size()-1);
        return !(position>=SPEC.lakeRiverEnd()&&position<SPEC.cliffRiverEnd()&&distance<width/2+4.5);
    }
    private static boolean clearRoute(Resident resident,BiPredicate<Double,Double> boundary) {
        if(resident.pending==null)return true;
        double x=resident.x,z=resident.z;
        for(double[] waypoint:resident.pending.route) {
            int steps=Math.max(1,(int)Math.ceil(Math.hypot(waypoint[0]-x,waypoint[1]-z)/.5));
            for(int i=0;i<=steps;i++)if(!boundary.test(x+(waypoint[0]-x)*i/steps,z+(waypoint[1]-z)*i/steps))return false;
            x=waypoint[0];z=waypoint[1];
        }
        return true;
    }
    public static void upgrade(State state) {
        upgrade(state,3,IslandLandscapes::highlandClear,highlandViewpoints());
    }
    public static void completePhase3(State state) {
        upgrade(state,4,IslandLandscapes::upgradeClear,SPEC.viewpoints().stream().filter(place->!isHighland(place)).toList());
    }
    private static boolean upgradeClear(double x,double z) {
        if(!clear(x,z))return false;
        // Historical geography allowed river crossings anywhere in two z bands.
        // Catch only land made inaccessible by replacing those bands with deck
        // footprints, without rejecting public water records that were already wet.
        boolean oldCrossingBand=Math.abs(z+322)<1.8||Math.abs(z-70)<1.8;
        return !(oldCrossingBand&&IslandGeography.onIsland(x,z)&&!IslandGeography.dryLand(x,z));
    }
    private static boolean isHighland(Viewpoint place) {return place.id().equals("cloudmere-view")||place.id().equals("silverstream-falls");}
    private static List<Viewpoint> highlandViewpoints() {return SPEC.viewpoints().stream().filter(IslandLandscapes::isHighland).toList();}
    private static boolean acceptedPointClear(String target,BiPredicate<Double,Double> boundary) {
        try {
            String[] axes=target.substring(6).split(",",-1);if(axes.length!=2)return false;
            double x=Double.parseDouble(axes[0]),z=Double.parseDouble(axes[1]);
            return IslandGeography.onIsland(x,z)&&boundary.test(x,z);
        }catch(NumberFormatException invalid){return false;}
    }
    private static boolean siteClear(int version,double x,double z,double radius,BiPredicate<Double,Double> boundary) {
        // Exact rectangle/ellipse overlap catches a cliff intersecting only the
        // edge of a property or sign, including between sampled corner points.
        if(version>=4&&radius>0&&!footprintClear(x,z,radius))return false;
        for(double dx:new double[]{-radius,0,radius})for(double dz:new double[]{-radius,0,radius})
            if(!boundary.test(x+dx,z+dz))return false;
        return true;
    }
    private static void upgrade(State state,int version,BiPredicate<Double,Double> boundary,List<Viewpoint> additions) {
        if(state.geographyVersion>=version)return;
        if(state.geographyVersion<version-1)throw new IllegalStateException("Island landscapes require the preceding geography upgrade");
        // Never flood an existing property or move a resident as a side effect.
        for(Building building:state.buildings.values()) {
            double radius=Places.enclosed(building)?3.6:0;
            if(!siteClear(version,building.x,building.z,radius,boundary))throw new IllegalStateException("Landscape overlaps existing place: "+building.id);
        }
        for(var object:state.worldObjects.values())if(!siteClear(version,object.x,object.z,version>=4?WorldObjects.OBSTACLE_RADIUS:0,boundary))throw new IllegalStateException("Landscape overlaps resident object: "+object.id);
        for(Resident resident:state.agents.values()) {
            if(!boundary.test(resident.x,resident.z))throw new IllegalStateException("Landscape overlaps a resident; retry after they leave the landscape site");
            if(resident.pending!=null) {
                if(version>=4&&List.of("PLACE_OBJECT","MOVE_OBJECT").contains(resident.pending.type)
                    &&!siteClear(version,resident.pending.objectX,resident.pending.objectZ,WorldObjects.OBSTACLE_RADIUS,boundary))
                    throw new IllegalStateException("Landscape overlaps an accepted object placement; let the installation finish before upgrading");
                String target=resident.pending.finalDestination!=null?resident.pending.finalDestination:resident.pending.target;
                if(target!=null&&target.startsWith("point:")&&!acceptedPointClear(target,boundary))
                    throw new IllegalStateException("Landscape overlaps an accepted destination; let the journey finish before upgrading");
                if(!clearRoute(resident,boundary)&&!Travel.moving(resident))throw new IllegalStateException("Landscape overlaps an accepted activity route");
            }
        }
        // Compute replacements before changing state, so a failed route leaves it intact.
        Map<String,List<double[]>> routes=new LinkedHashMap<>();
        for(Resident resident:state.agents.values())if(Travel.moving(resident)&&!clearRoute(resident,boundary)) {
            Building target=Travel.walkingDestination(state,resident.pending);
            if(target==null)throw new IllegalStateException("Saved landscape journey has no valid destination");
            routes.put(resident.id,Travel.path(state,resident,target,resident.pending.routeChoice==null?"shortest":resident.pending.routeChoice));
        }
        for(Viewpoint place:additions) {
            if(state.buildings.containsKey(place.id()))throw new IllegalStateException("Landscape destination ID already exists: "+place.id());
        }
        for(Viewpoint place:additions) {
            Building building=new Building(place.id(),place.name(),"wilderness",place.district(),place.x(),place.z(),100);
            building.description=place.description();building.social=true;building.restAllowed=true;building.entrance=Places.entrance(building);
            state.buildings.put(building.id,building);
        }
        routes.forEach((id,route)->state.agents.get(id).pending.route=route);
        state.geographyVersion=version;state.version++;
    }
    public static void upgrade(ObjectNode data) throws Exception {
        upgrade(data,3,highlandViewpoints());
    }
    public static void completePhase3(ObjectNode data) throws Exception {
        upgrade(data,4,SPEC.viewpoints().stream().filter(place->!isHighland(place)).toList());
    }
    private static void upgrade(ObjectNode data,int version,List<Viewpoint> additions) throws Exception {
        if(data.path("geographyVersion").asInt()>=version)return;
        ObjectMapper json=new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        State state=json.treeToValue(data,State.class);Map<String,JsonNode> previousRoutes=new HashMap<>();
        for(Resident resident:state.agents.values())if(resident.pending!=null)previousRoutes.put(resident.id,json.valueToTree(resident.pending.route));
        if(version==3)upgrade(state);else completePhase3(state);
        ObjectNode buildings=data.withObject("/buildings");
        for(Viewpoint place:additions)buildings.set(place.id(),json.valueToTree(state.buildings.get(place.id())));
        // Patch only changed routes; retain unknown/private JSON on every other field.
        for(Resident resident:state.agents.values())if(resident.pending!=null) {
            ObjectNode pending=(ObjectNode)data.path("agents").path(resident.id).path("pending");
            JsonNode route=json.valueToTree(resident.pending.route);
            if(!route.equals(previousRoutes.get(resident.id)))pending.set("route",route);
        }
        data.put("geographyVersion",state.geographyVersion);data.put("version",state.version);
    }
}
