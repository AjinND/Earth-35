package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;
import world.earth35.protocol.Contracts.*;

class OrganicIslandTest {
    private State islandSeed() {State s=State.prototypeSeed();IslandCity.upgrade(s);return s;}
    private Resident resident(State s,String location) {
        Resident a=new Resident();a.id="explorer";a.ownerId="preview-owner";a.name="Island Explorer";a.home="apt-101";a.location=location;a.energy=100;
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(a.id,a);return a;
    }
    @Test void previewIsDetachedAndHomesAreDiverseWithoutRoadOrBuildingOverlap() {
        State saved=islandSeed();State s=OrganicCity.preview(saved);assertEquals(1,saved.geographyVersion);assertEquals(2,s.geographyVersion);
        for(Building b:s.buildings.values()) {
            assertTrue(IslandGeography.dryLand(b.x,b.z),b.id+" must be on dry land");
            if(b.type.equals("bus_stop"))continue;
            if(Places.enclosed(b))assertTrue(IslandGeography.roadDistance(b.x,b.z)>6,b.id+" must clear curved streets");
            for(Building other:s.buildings.values())if(Places.enclosed(b)&&Places.enclosed(other)&&!b.id.equals(other.id))assertTrue(Math.hypot(b.x-other.x,b.z-other.z)>10,b.id+" overlaps "+other.id);
        }
        assertEquals(saved.buildings.get("apt-101").capacity,s.buildings.get("apt-101").capacity);
        for(var road:IslandGeography.ROADS)for(var point:road)assertTrue(IslandGeography.onIsland(point.x(),point.z()));
    }
    @Test void freelyChosenCoordinatesReachMountainWoodlandAndBeachAndCanReturnHome() {
        State s=OrganicCity.preview(State.seed());Resident a=resident(s,"park");
        for(String target:List.of("point:-225,-450","point:-595,-100","point:-150,350","point:250,-80",a.home)) {
            Engine.apply(s,a,new Command(UUID.randomUUID().toString(),ActionType.MOVE,target,null,null,null,null),"trip",new Engine.Effects());
            int minutes=0;while(a.pending!=null&&minutes++<1000){Engine.tick(s);assertTrue(IslandGeography.dryLand(a.x,a.z));for(Building b:s.buildings.values())if(Places.enclosed(b)&&!b.id.equals(a.location))assertFalse(Math.abs(a.x-b.x)<3.35&&Math.abs(a.z-b.z)<3.35,"Route crossed "+b.id);}
            assertNull(a.pending,"Journey stuck to "+target);assertEquals(target,a.location);
            if(target.startsWith("point:")){Engine.apply(s,a,new Command("rest",ActionType.REST,null,null,null,null,null),"rest",new Engine.Effects());while(a.pending!=null)Engine.tick(s);}
        }
    }
    @Test void oceanRiverAndOtherHomesCannotBeEnteredByCoordinate() {
        State s=OrganicCity.preview(State.seed());Resident a=resident(s,"park");
        Building other=s.buildings.get("apt-102");
        for(String target:List.of("point:3000,3000","point:NaN,0","point:Infinity,0","point:185,-5","point:"+other.x+","+other.z)) {
            assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,new Command("invalid",ActionType.MOVE,target,null,null,null,null),"invalid",new Engine.Effects()),target);
            assertNull(a.pending);
        }
    }
    @Test void savedJourneysAndPrivateStateSurviveDetachedPreview() {
        State s=islandSeed();Resident a=resident(s,"apt-101");a.money=12345;a.relationships.put("friend","close friend");
        Engine.apply(s,a,new Command("travel",ActionType.MOVE,"office",null,null,null,null),"accepted",new Engine.Effects());
        State next=OrganicCity.preview(s);Resident traveler=next.agents.get(a.id);
        assertEquals(12345,traveler.money);assertEquals(a.relationships,traveler.relationships);assertEquals("accepted",traveler.pending.id);
        for(int i=0;i<400&&traveler.pending!=null;i++)Engine.tick(next);
        assertNull(traveler.pending);assertEquals("office",traveler.location);
        assertEquals(1,s.geographyVersion);assertEquals("accepted",s.agents.get(a.id).pending.id);
    }
    @Test void vehiclesStayOnTheRenderedCurvedRoadsAndExportPreview() throws Exception {
        State s=OrganicCity.preview(State.seed());s.minute=720;Resident a=resident(s,"park");a.pending=Engine.pending("rest","REST","park",750);
        for(var v:Traffic.vehicles(s)) {
            assertTrue(IslandGeography.roadDistance(v.x(),v.z())<1.8,v.id());
            for(var pose:v.motion())assertTrue(IslandGeography.roadDistance(pose.x(),pose.z())<1.8,v.id());
        }
        for(var signal:Traffic.network(s).signals())assertEquals(Traffic.green(s.minute,signal.x(),signal.z(),CityLayout.ORGANIC),signal.vehicleGreen());
        long started=System.nanoTime();assertTrue(Travel.situation(s,a).destinations().size()>20);
        assertTrue((System.nanoTime()-started)/1e9<5,"Island travel observation should not stall decisions");
        var buildings=s.buildings.values().stream().map(b->new StateBuilding(b.id,b.name,b.type,b.district,b.x,b.z,b.capacity,b.description,b.social,b.restAllowed,b.objects,Places.entrance(b),Places.hours(b,s.minute))).toList();
        var agents=List.of(new PublicAgent(a.id,a.name,"Exploring the island",a.home,"Unemployed",a.location,a.x,a.z,"REST","online",false,false));
        World world=new World(s.minute,s.version,"Clear",25,5000,buildings,agents,List.of(),Traffic.network(s),Economy.summary(s),CityLayout.of(s));
        Files.writeString(Path.of("target/organic-preview.json"),new ObjectMapper().writeValueAsString(world));
    }
}
