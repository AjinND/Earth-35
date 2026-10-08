package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;
import world.earth35.world.IslandGeography.Point;

/** Resident-owned signs with funded, physical installation and reserved clear footprints. */
public final class WorldObjects {
    private WorldObjects() {}
    public static final long COST_CENTS=2500;
    public static final int INSTALL_MINUTES=15,MOVE_MINUTES=10;
    public static final double REACH=6,OBSTACLE_RADIUS=1;
    public static class PlacedObject {
        public String id,owner,kind,text,business;
        public double x,z;
        public long placedMinute;
    }
    public record ObjectView(String id,String owner,String kind,String text,String business,double x,double z,String status) {}
    public static List<ObjectView> view(State s) {
        return s.worldObjects.values().stream().map(o->new ObjectView(o.id,o.owner,o.kind,o.text,o.business,o.x,o.z,"installed")).toList();
    }
    public static Map<String,Object> observation(State s) {
        return Map.of("objects",view(s),"placementCostCents",COST_CENTS,"installationMinutes",INSTALL_MINUTES,
            "moveMinutes",MOVE_MINUTES,"reachUnits",REACH,"reachMeters",REACH*CityLayout.of(s).metersPerUnit(),
            "rules","Install on nearby clear dry land outside roads, footbridges, public grounds, entrances and accepted travel routes. MOVE_OBJECT may move only your own sign, with target its ID and item point:x,z. Advertisements use item advertisement:<owned business ID>. Installation and moving require uninterrupted outdoor work.");
    }
    private static Building obstacle(double x,double z) {Building b=new Building();b.x=x;b.z=z;return b;}
    static List<Building> obstacles(State s) {return s.worldObjects.values().stream().map(o->obstacle(o.x,o.z)).toList();}
    private static boolean editing(Pending p) {return p!=null&&List.of("PLACE_OBJECT","MOVE_OBJECT").contains(p.type);}
    private static void outdoor(State s,Resident a) {
        Engine.require(!Places.enclosed(s.buildings.get(a.location)),"Install or move signs outdoors");
        Engine.require(a.energy>=5&&a.hunger<98,"Eat or rest before installing a sign");
    }
    private static Building point(String target) {
        Building p=IslandGeography.coordinate(target);
        Engine.require(p!=null,"Choose a dry island location using point:x,z");return p;
    }
    private static double segmentDistance(double x,double z,Point from,Point to) {
        double dx=to.x()-from.x(),dz=to.z()-from.z(),length=dx*dx+dz*dz;
        double t=length==0?0:Math.max(0,Math.min(1,((x-from.x())*dx+(z-from.z())*dz)/length));
        return Math.hypot(x-from.x()-t*dx,z-from.z()-t*dz);
    }
    private static Point closestRoad(Point p) {
        Point nearest=null;double distance=Double.POSITIVE_INFINITY;
        for(var road:IslandGeography.ROADS)for(int i=1;i<road.size();i++) {
            Point a=road.get(i-1),b=road.get(i);double dx=b.x()-a.x(),dz=b.z()-a.z();
            double t=Math.max(0,Math.min(1,((p.x()-a.x())*dx+(p.z()-a.z())*dz)/(dx*dx+dz*dz)));
            Point candidate=new Point(a.x()+t*dx,a.z()+t*dz);double d=Math.hypot(p.x()-candidate.x(),p.z()-candidate.z());
            if(d<distance){distance=d;nearest=candidate;}
        }
        return nearest;
    }
    static void requireClear(State s,double x,double z,String excludedObject,String actor) {
        Engine.require(s.geographyVersion>=2,"Object placement requires the island geography");
        if(s.geographyVersion>=4)Engine.require(IslandLandscapes.footprintClear(x,z,OBSTACLE_RADIUS),"Keep the complete sign footprint clear of marked trails and protected cliffs");
        Engine.require(IslandGeography.roadDistance(x,z)>=5.5,"Keep roads, sidewalks and crossings clear");
        Building footprint=obstacle(x,z);
        for(double dx:new double[]{-1,0,1})for(double dz:new double[]{-1,0,1})
            Engine.require(IslandGeography.dryLand(x+dx,z+dz),"The complete sign footprint must be on dry land");
        for(int i=0;i<IslandGeography.COAST.size();i++)
            Engine.require(!IslandNavigation.intersects(IslandGeography.COAST.get(i),IslandGeography.COAST.get((i+1)%IslandGeography.COAST.size()),footprint,1.2),"Keep signs away from the coastline");
        for(int i=1;i<IslandGeography.RIVER.size();i++)
            Engine.require(!IslandNavigation.intersects(IslandGeography.RIVER.get(i-1),IslandGeography.RIVER.get(i),footprint,4.5),"Keep riverbanks and footbridges clear");
        for(Building b:s.buildings.values()) {
            if(Places.enclosed(b)) {
                Engine.require(Math.abs(x-b.x)>=5||Math.abs(z-b.z)>=5,"Keep signs outside building and construction footprints");
                var entrance=Places.entrance(b);Point door=new Point(entrance.x(),entrance.z());Point road=closestRoad(door);
                Engine.require(Math.hypot(x-door.x(),z-door.z())>=3&&segmentDistance(x,z,door,road)>=2,"Keep entrances and their access paths clear");
            } else Engine.require(Math.hypot(x-b.x,z-b.z)>=Math.max(5,b.outdoorRadius+1.5),"Keep public grounds, seating and bus stops clear");
        }
        for(PlacedObject o:s.worldObjects.values())if(!o.id.equals(excludedObject))
            Engine.require(Math.hypot(x-o.x,z-o.z)>=2.5,"Signs cannot overlap existing objects");
        for(Resident r:s.agents.values()) {
            if(r.id.equals(actor))Engine.require(Math.hypot(x-r.x,z-r.z)>=1.2,"Stand back from the sign footprint while installing it");
            if(!r.id.equals(actor))Engine.require(Math.hypot(x-r.x,z-r.z)>=2,"Keep clear of other residents");
            if(editing(r.pending)&&!r.id.equals(actor))
                Engine.require(Math.hypot(x-r.pending.objectX,z-r.pending.objectZ)>=2.5,"Another resident has reserved this object placement");
            if(Travel.moving(r)) {
                Point from=new Point(r.x,r.z);
                for(double[] step:r.pending.route) {
                    Point to=new Point(step[0],step[1]);
                    Engine.require(!IslandNavigation.intersects(from,to,footprint,1.5),"Keep accepted walking routes clear");from=to;
                }
            }
        }
    }
    public static void requireConstructionClear(State s,double x,double z) {
        for(PlacedObject o:s.worldObjects.values())
            Engine.require(Math.abs(x-o.x)>=6||Math.abs(z-o.z)>=6,"Choose a construction site away from installed signs");
        for(Resident r:s.agents.values())if(editing(r.pending))
            Engine.require(Math.abs(x-r.pending.objectX)>=6||Math.abs(z-r.pending.objectZ)>=6,"Choose a construction site away from reserved object placements");
    }
    public static String place(State s,Resident a,String target,String kind,String text,String id,Engine.Effects e) {
        Engine.require(a.pending==null&&!a.paused,"Finish your current action before installing a sign");outdoor(s,a);
        Engine.require(text!=null&&!text.isBlank()&&text.strip().length()<=160,"Provide sign text in up to 160 characters");
        String business=null;
        if(kind!=null&&kind.startsWith("advertisement:")) {
            business=kind.substring("advertisement:".length());var owned=s.businesses.get(business);
            Engine.require(owned!=null&&owned.owners.contains(a.id),"Advertise a business you own");kind="advertisement";
        } else Engine.require("signboard".equals(kind),"Choose item signboard or advertisement:<owned business ID>");
        Engine.require(s.worldObjects.values().stream().filter(o->a.id.equals(o.owner)).count()<20,"A resident may maintain at most twenty signs");
        Engine.require(!s.worldObjects.containsKey("object:"+id)&&!s.accounts.containsKey("escrow:object:"+id),"This sign already exists");
        Building location=point(target);
        Engine.require(Math.hypot(a.x-location.x,a.z-location.z)<=REACH,"Move within six world units of the installation point");
        requireClear(s,location.x,location.z,null,a.id);
        Engine.require(a.money>=COST_CENTS,"A new sign costs 2500 cents");
        Engine.pay(s,a.id,"escrow:object:"+id,COST_CENTS,"object_installation_reservation",e);
        Pending p=Engine.pending(id,"PLACE_OBJECT",target,s.minute+INSTALL_MINUTES);p.startedAt=s.minute;p.lastWorkedMinute=s.minute;
        p.objectId="object:"+id;p.objectKind=kind;p.objectText=text.strip();p.objectBusiness=business;p.objectX=location.x;p.objectZ=location.z;
        a.pending=p;e.facts.add(new Engine.Fact("world_object",a.id,p.objectId,a.name+" began installing a sign."));
        return "Sign funded; fifteen minutes of on-site installation required";
    }
    public static String move(State s,Resident a,String target,String destination,String id,Engine.Effects e) {
        Engine.require(a.pending==null&&!a.paused,"Finish your current action before moving a sign");outdoor(s,a);
        PlacedObject o=s.worldObjects.get(target);Engine.require(o!=null&&a.id.equals(o.owner),"Move a sign you own");
        Building location=point(destination);
        Engine.require(Math.hypot(a.x-o.x,a.z-o.z)<=REACH&&Math.hypot(a.x-location.x,a.z-location.z)<=REACH,"Stand within six world units of both the sign and its new position");
        Engine.require(Math.hypot(location.x-o.x,location.z-o.z)>=.2,"Choose a new position for the sign");
        requireClear(s,location.x,location.z,o.id,a.id);
        // Carrying must have a clear outdoor corridor rather than teleporting across a wall or river.
        Point source=new Point(o.x,o.z),end=new Point(location.x,location.z);
        for(Building b:s.buildings.values())if(Places.enclosed(b))
            Engine.require(!IslandNavigation.intersects(source,end,b,4),"Choose a clear carrying path around buildings");
        for(PlacedObject other:s.worldObjects.values())if(!other.id.equals(o.id))
            Engine.require(!IslandNavigation.intersects(source,end,obstacle(other.x,other.z),1.2),"Choose a carrying path clear of other signs");
        int steps=(int)Math.ceil(Math.hypot(end.x()-source.x(),end.z()-source.z())*2);
        for(int i=0;i<=steps;i++)Engine.require(IslandGeography.dryLand(source.x()+(end.x()-source.x())*i/steps,source.z()+(end.z()-source.z())*i/steps),"Carry the sign over dry land");
        Pending p=Engine.pending(id,"MOVE_OBJECT",o.id,s.minute+MOVE_MINUTES);p.startedAt=s.minute;p.lastWorkedMinute=s.minute;
        p.objectId=o.id;p.objectX=location.x;p.objectZ=location.z;a.pending=p;
        e.facts.add(new Engine.Fact("world_object",a.id,o.id,a.name+" began repositioning a sign."));
        return "Sign move accepted; ten minutes of on-site work required";
    }
    public static void tick(State s,Resident a,Engine.Effects e) {
        Pending p=a.pending;if(!editing(p)||s.minute<=p.lastWorkedMinute)return;
        boolean placing="PLACE_OBJECT".equals(p.type);int duration=placing?INSTALL_MINUTES:MOVE_MINUTES;
        try {
            Engine.require(!a.paused&&s.minute==p.lastWorkedMinute+1,"Installation interrupted");outdoor(s,a);
            Engine.require(Math.hypot(a.x-p.objectX,a.z-p.objectZ)<=REACH,"Installer left the site");
            if(placing)Engine.require(s.accounts.getOrDefault("escrow:object:"+p.id,0L)==COST_CENTS,"Installation funds are unavailable");
            else {PlacedObject o=s.worldObjects.get(p.objectId);Engine.require(o!=null&&a.id.equals(o.owner)&&Math.hypot(a.x-o.x,a.z-o.z)<=REACH,"Sign is no longer available here");}
            requireClear(s,p.objectX,p.objectZ,placing?null:p.objectId,a.id);
        } catch(Engine.Invalid interrupted) {
            String escrow="escrow:object:"+p.id;long funds=s.accounts.getOrDefault(escrow,0L);
            if(funds>0)Engine.pay(s,escrow,a.id,funds,"object_installation_refund",e);s.accounts.remove(escrow);
            e.completions.add(new Engine.Completion(p.id,"cancelled",interrupted.getMessage()+"; sign unchanged and reserved funds refunded"));a.pending=null;return;
        }
        a.energy=Math.max(0,a.energy-.05);p.workMinutes++;p.lastWorkedMinute=s.minute;if(p.workMinutes<duration)return;
        PlacedObject o;
        if(placing) {
            o=new PlacedObject();o.id=p.objectId;o.owner=a.id;o.kind=p.objectKind;o.text=p.objectText;o.business=p.objectBusiness;
            Engine.pay(s,"escrow:object:"+p.id,"city-supply",COST_CENTS,"object_materials",e);s.accounts.remove("escrow:object:"+p.id);
            s.worldObjects.put(o.id,o);
        } else o=s.worldObjects.get(p.objectId);
        o.x=p.objectX;o.z=p.objectZ;o.placedMinute=s.minute;
        e.facts.add(new Engine.Fact("world_object",a.id,o.id,a.name+(placing?" installed ":" repositioned ")+"a "+o.kind+": "+o.text));
        Engine.finish(a,e,placing?"Sign installed: "+o.id:"Sign repositioned: "+o.id);
    }
}
