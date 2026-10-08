package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import world.earth35.world.State.*;
import world.earth35.world.IslandGeography.Point;

/** Additive city growth: keep occupied properties, residents and accepted routes in place. */
public final class CityExpansion {
    private CityExpansion() {}
    private static final Map<String,Double> OUTDOORS=Map.ofEntries(
        Map.entry("park",30d),Map.entry("willow-park",26d),Map.entry("community-garden",22d),
        Map.entry("town-square",20d),Map.entry("promenade",30d),Map.entry("courtyard",18d),
        Map.entry("mountain",42d),Map.entry("hill-station",34d),Map.entry("trekking-trail",50d),
        Map.entry("river",36d),Map.entry("riverside-walk",40d),Map.entry("north-beach",52d),
        Map.entry("south-beach",52d),Map.entry("western-woods",58d),Map.entry("eastern-shore",45d));
    public static void upgrade(State s) {
        if(s.expansionVersion>=1)return;
        if(s.geographyVersion<2)throw new IllegalStateException("Expand the organic island after its layout migration");
        OUTDOORS.forEach((id,radius)->{Building b=s.buildings.get(id);if(b!=null)b.outdoorRadius=radius;});
        park(s,"meadow-commons","Meadow Commons",-416,12,38);
        park(s,"south-gardens","South Gardens",-220,290,32);
        for(int i=0;i<60;i++) {
            boolean house=i<36;String id=(house?"house-":"apt-")+(201+i);
            Building b=new Building(id,(house?"House ":"Apartment ")+(201+i),house?"house":"apartment",house?"Garden Quarter":"Residential",0,0,1);
            b.description=house?"A completed home in New Eden's growing garden neighborhoods.":"A completed apartment home near the city's workplaces and winding streets.";
            place(s,b,i);s.buildings.putIfAbsent(id,b);
        }
        for(int i=0;i<16;i++) {
            String type=i<10?"office":List.of("studio","studio","coffee_shop","grocery","restaurant","market").get(i-10);
            String id=i<10?"office-"+(201+i):"business-"+(201+i);
            String name=i<10?List.of("Willow Exchange","Harbor House","Cedar Works","Eastbank Offices","Founders Annex","Maple Offices","Garden Exchange","Juniper House","Lantern Works","Southbank Offices").get(i):List.of("Oak Design Studio","Cedar Workshop","Meadow Coffee","Garden Grocer","The Willow Kitchen","Southbank Market").get(i-10);
            Building b=new Building(id,name,type,"Downtown",0,0,20);
            b.description="An open workplace in New Eden's growing city.";
            if(i>=12) {
                b.description="A new neighborhood business offering meals, coffee and a place to meet.";b.social=true;b.restAllowed=true;
                b.stock.put("meal",100);b.stock.put("coffee",100);b.prices.put("meal",1200L);b.prices.put("coffee",400L);
                b.objects.put(id+"-seat","Shared seating");
            }
            switch(type) {
                case "coffee_shop"->{b.openMinute=360;b.closeMinute=1200;}
                case "grocery","market"->{b.openMinute=480;b.closeMinute=1200;}
                case "restaurant"->{b.openMinute=660;b.closeMinute=1380;}
                default->{}
            }
            place(s,b,i+60);s.buildings.putIfAbsent(id,b);s.accounts.putIfAbsent(id,100000000L);
            Job job=new Job("job-"+id,i<10?"City analyst":i<12?"Designer":"Shop assistant",id,6);
            s.jobs.putIfAbsent(job.id,job);
        }
        for(int i=0;i<10;i++) {
            String planned=i<4?"house":i<7?"apartment":"office",id="construction-"+(201+i);
            Building b=new Building(id,(planned.equals("house")?"Willow Homes":planned.equals("apartment")?"Cedar Apartments":"Founders Offices")+" · Site "+(i+1),"construction","Development",0,0,0);
            b.plannedType=planned;b.description="Under construction: a future "+planned+" building. The fenced work site is closed to residents until completion.";
            place(s,b,i+76);s.buildings.putIfAbsent(id,b);
        }
        s.expansionVersion=1;s.version++;
    }
    private static void park(State s,String id,String name,double x,double z,double radius) {
        if(s.buildings.containsKey(id))return;
        Building b=new Building(id,name,"park","Garden Quarter",x,z,100);b.outdoorRadius=radius;b.social=true;b.restAllowed=true;
        b.description="A spacious destination with open lawns, winding walks, shaded seating and tree groves.";
        b.objects.put(id+"-bench","Shaded park bench");b.entrance=Places.entrance(b);s.buildings.put(id,b);
    }
    private static void place(State s,Building b,int salt) {
        if(s.buildings.containsKey(b.id))return;
        Random random=new Random(35L*Integer.toUnsignedLong(b.id.hashCode())+salt);
        for(int attempt=0;attempt<5000;attempt++) {
            double x,z;
            if(b.type.equals("house")){x=-440+random.nextDouble()*310;z=65+random.nextDouble()*210;}
            else if(b.type.equals("apartment")){x=-345+random.nextDouble()*280;z=-220+random.nextDouble()*365;}
            else if(b.type.equals("office")){x=-170+random.nextDouble()*250;z=-215+random.nextDouble()*275;}
            else{x=-430+random.nextDouble()*520;z=-245+random.nextDouble()*495;}
            Point p=IslandGeography.survey(x,z);
            if(Math.abs(p.x()+150)>282||Math.abs(p.z())>282||!IslandGeography.dryLand(p.x(),p.z())||IslandGeography.roadDistance(p.x(),p.z())<8)continue;
            if(s.buildings.values().stream().anyMatch(other->Math.hypot(other.x-p.x(),other.z-p.z())<Math.max(13,other.outdoorRadius+7)))continue;
            if(s.agents.values().stream().anyMatch(a->Math.hypot(a.x-p.x(),a.z-p.z())<8||crossesAcceptedRoute(a,p)))continue;
            // The complete footprint and front entrance must remain on land.
            if(!IslandGeography.dryLand(p.x()-4,p.z()-4)||!IslandGeography.dryLand(p.x()+4,p.z()+4)||!IslandGeography.dryLand(p.x()-4,p.z()+4)||!IslandGeography.dryLand(p.x()+4,p.z()-4))continue;
            b.x=p.x();b.z=p.z();b.entrance=Places.entrance(b);return;
        }
        throw new IllegalStateException("No safe expansion site for "+b.id);
    }
    private static boolean crossesAcceptedRoute(Resident a,Point candidate) {
        if(!Travel.moving(a))return false;
        Building obstacle=new Building();obstacle.x=candidate.x();obstacle.z=candidate.z();Point from=new Point(a.x,a.z);
        for(double[] step:a.pending.route){Point to=new Point(step[0],step[1]);if(IslandNavigation.intersects(from,to,obstacle,5))return true;from=to;}
        return false;
    }
    public static void upgrade(ObjectNode data) throws Exception {
        if(data.path("expansionVersion").asInt()>=1)return;
        ObjectMapper json=new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        State s=json.treeToValue(data,State.class);upgrade(s);
        ObjectNode buildings=data.withObject("/buildings");
        for(Building b:s.buildings.values()) {
            if(!buildings.has(b.id))buildings.set(b.id,json.valueToTree(b));
            else if(b.outdoorRadius>0)((ObjectNode)buildings.path(b.id)).put("outdoorRadius",b.outdoorRadius);
        }
        ObjectNode accounts=data.withObject("/accounts"),jobs=data.withObject("/jobs");
        s.accounts.forEach((id,balance)->{if(!accounts.has(id))accounts.put(id,balance);});
        s.jobs.forEach((id,job)->{if(!jobs.has(id))jobs.set(id,json.valueToTree(job));});
        data.put("expansionVersion",s.expansionVersion);data.put("version",s.version);
    }
}
