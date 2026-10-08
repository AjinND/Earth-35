package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;
import world.earth35.protocol.Contracts.*;

class CityExpansionTest {
    private State organicSeed(){State s=State.prototypeSeed();OrganicCity.upgrade(s);return s;}
    private Resident resident(State s,String id,String location){Resident a=new Resident();a.id=id;a.ownerId="owner";a.name=id;a.home="apt-101";a.location=location;Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;}
    @Test void growthPreservesOccupiedPropertiesPrivateStateAndAcceptedRoutes() throws Exception {
        State s=organicSeed();Resident a=resident(s,"walker","apt-101");a.money=12345;
        Engine.apply(s,a,new Command("walk",ActionType.MOVE,"office",null,null,null,null),"accepted-walk",new Engine.Effects());Engine.tick(s);
        ObjectMapper json=new ObjectMapper();ObjectNode data=json.valueToTree(s);data.put("futureRoot","keep");
        ((ObjectNode)data.at("/agents/walker")).put("privateMemory","keep");
        var before=data.deepCopy();CityExpansion.upgrade(data);
        assertEquals(before.path("agents"),data.path("agents"));assertEquals(before.path("minute"),data.path("minute"));
        for(String section:List.of("conversations","socialPlans","economy","futureRoot"))assertEquals(before.path(section),data.path(section));
        before.path("buildings").fields().forEachRemaining(entry->{
            ObjectNode expected=((ObjectNode)entry.getValue()).deepCopy();expected.put("outdoorRadius",data.path("buildings").path(entry.getKey()).path("outdoorRadius").asDouble());
            assertEquals(expected,data.path("buildings").path(entry.getKey()),entry.getKey());
        });
        before.path("jobs").fields().forEachRemaining(entry->assertEquals(entry.getValue(),data.path("jobs").path(entry.getKey())));
        before.path("accounts").fields().forEachRemaining(entry->assertEquals(entry.getValue(),data.path("accounts").path(entry.getKey())));
        var once=data.deepCopy();CityExpansion.upgrade(data);assertEquals(once,data);
        State expanded=json.readerFor(State.class).without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(data);
        for(Building b:expanded.buildings.values())if(!before.path("buildings").has(b.id)&&Places.enclosed(b)) {
            PointCheck.assertRouteClear(expanded.agents.get(a.id),b);
        }
        Resident walker=expanded.agents.get(a.id);for(int i=0;i<400&&walker.pending!=null;i++)Engine.tick(expanded);
        assertNull(walker.pending);assertEquals("office",walker.location);
    }
    private static class PointCheck {
        static void assertRouteClear(Resident a,Building b){var from=new IslandGeography.Point(a.x,a.z);for(double[] p:a.pending.route){var to=new IslandGeography.Point(p[0],p[1]);assertFalse(IslandNavigation.intersects(from,to,b,5));from=to;}}
    }
    @Test void addedSitesFitOnLandClearRoadsAndReserveTheLargerParks() {
        State before=organicSeed(),s=State.seed();assertEquals(1,s.expansionVersion);assertEquals(174,s.buildings.size());
        assertEquals(110,s.buildings.values().stream().filter(b->b.capacity==1).count());
        assertEquals(10,s.buildings.values().stream().filter(b->b.type.equals("construction")).count());
        assertTrue(s.buildings.get("town-square").outdoorRadius>=20);assertTrue(s.buildings.get("park").outdoorRadius>=30);
        for(Building b:s.buildings.values()) {
            assertTrue(IslandGeography.dryLand(b.x,b.z),b.id);
            if(before.buildings.containsKey(b.id)||!Places.enclosed(b))continue;
            assertTrue(IslandGeography.roadDistance(b.x,b.z)>=8,b.id);
            for(Building other:s.buildings.values())if(!other.id.equals(b.id))assertTrue(Math.hypot(b.x-other.x,b.z-other.z)>=Math.max(13,other.outdoorRadius+7),b.id+" too close to "+other.id);
        }
    }
    @Test void newBusinessesAreUsableAndConstructionSitesRemainUnavailable() {
        State s=State.seed();s.minute=720;Resident a=resident(s,"visitor","park");
        Building site=s.buildings.get("construction-201");assertFalse(Places.open(site,s.minute));assertEquals(-1,Places.nextOpen(site,s.minute));
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,new Command("site",ActionType.MOVE,site.id,null,null,null,null),"site",new Engine.Effects()));
        assertFalse(s.jobs.values().stream().anyMatch(j->j.building.equals(site.id)));assertEquals(0,site.capacity);
        for(String id:List.of("office-201","business-213","meadow-commons","south-gardens")) {
            a.location="park";a.x=s.buildings.get("park").x;a.z=s.buildings.get("park").z;
            Engine.apply(s,a,new Command(id,ActionType.MOVE,id,null,null,null,null),id,new Engine.Effects());
            for(int i=0;i<400&&a.pending!=null;i++)Engine.tick(s);
            assertNull(a.pending,id);assertEquals(id,a.location);
        }
        a.location="business-213";Building shop=s.buildings.get(a.location);a.x=shop.x;a.z=shop.z;long money=a.money;
        s.minute=720;Engine.apply(s,a,new Command("buy",ActionType.BUY,shop.id,null,"meal",1,null),"buy",new Engine.Effects());
        assertEquals(money-1200,a.money);assertEquals(99,shop.stock.get("meal"));
        assertTrue(Travel.situation(s,a).destinations().stream().noneMatch(d->d.destination().equals(site.id)));
    }
    @Test void exportExpandedWorldForVisualChecks() throws Exception {
        State s=State.seed();s.minute=720;Resident a=resident(s,"explorer","park");a.pending=Engine.pending("rest","REST","park",750);
        var buildings=s.buildings.values().stream().map(b->new StateBuilding(b.id,b.name,b.type,b.district,b.x,b.z,b.capacity,b.description,b.social,b.restAllowed,b.objects,Places.entrance(b),Places.hours(b,s.minute),b.outdoorRadius,b.plannedType)).toList();
        var agents=List.of(new PublicAgent(a.id,a.name,"Exploring the island",a.home,"Unemployed",a.location,a.x,a.z,"REST","online",false,false));
        Files.writeString(Path.of("target/city-growth-preview.json"),new ObjectMapper().writeValueAsString(new World(s.minute,s.version,"Clear",25,5000,buildings,agents,List.of(),Traffic.network(s),Economy.summary(s),CityLayout.of(s))));
    }
}
