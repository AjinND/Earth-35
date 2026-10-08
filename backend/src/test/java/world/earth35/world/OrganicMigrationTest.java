package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;
import world.earth35.protocol.Contracts.*;

class OrganicMigrationTest {
    private State islandSeed() {State s=State.prototypeSeed();IslandCity.upgrade(s);return s;}
    private Resident resident(State s,String id,String location) {
        Resident a=new Resident();a.id=id;a.ownerId="owner";a.name=id;a.home="apt-101";a.location=location;
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    @Test void freshWorldsUseTheSameOrganicLayoutAsMigratedWorlds() {
        State migrated=islandSeed();OrganicCity.upgrade(migrated);CityExpansion.upgrade(migrated);IslandLandscapes.upgrade(migrated);IslandLandscapes.completePhase3(migrated);State fresh=State.seed();
        assertEquals(4,fresh.geographyVersion);assertEquals(migrated.buildings.keySet(),fresh.buildings.keySet());
        for(Building b:fresh.buildings.values()){assertEquals(migrated.buildings.get(b.id).x,b.x);assertEquals(migrated.buildings.get(b.id).z,b.z);}
    }
    @Test void jsonMigrationPreservesUnknownAndPrivateFieldsAndOnlyRewritesJourneyGeometry() throws Exception {
        State s=islandSeed();s.minute=560;Resident a=resident(s,"walker","apt-101"),resting=resident(s,"resting","park");
        a.money=12345;a.job="job-office";a.relationships.put("friend","close friend");a.routine=new DailyRoutine();a.nightPlan=new NightPlan("Stars",1400);
        resting.pending=Engine.pending("rest","REST","park",600);
        Engine.apply(s,a,new Command("request",ActionType.MOVE,"office",null,"north-south-first",null,null),"saved-walk",new Engine.Effects());Engine.tick(s);
        ObjectMapper json=new ObjectMapper();ObjectNode data=json.valueToTree(s);data.put("futureRoot","keep");
        ((ObjectNode)data.at("/agents/walker")).put("privateMemory","unchanged");
        ((ObjectNode)data.at("/agents/walker/pending")).put("futureJourney","keep");
        ((ObjectNode)data.at("/buildings/apt-101")).put("futureProperty","keep");
        var original=data.deepCopy();assertTrue(OrganicCity.upgrade(data).isEmpty());
        assertEquals(2,data.path("geographyVersion").asInt());assertEquals(original.path("minute"),data.path("minute"));
        for(String field:List.of("ownerId","home","job","money","inventory","relationships","routine","nightPlan","privateMemory"))
            assertEquals(original.at("/agents/walker").path(field),data.at("/agents/walker").path(field),field);
        for(String field:List.of("id","type","target","routeChoice","travelStarted","travelWeather","futureJourney"))
            assertEquals(original.at("/agents/walker/pending").path(field),data.at("/agents/walker/pending").path(field),field);
        for(String field:List.of("jobs","accounts","conversations","socialPlans","economy","futureRoot"))assertEquals(original.path(field),data.path(field),field);
        assertEquals(original.at("/agents/resting/pending"),data.at("/agents/resting/pending"));
        assertEquals("keep",data.at("/buildings/apt-101/futureProperty").asText());
        var once=data.deepCopy();assertTrue(OrganicCity.upgrade(data).isEmpty());assertEquals(once,data);
        State restored=json.readerFor(State.class).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(data);
        Resident walker=restored.agents.get(a.id);List<Engine.Completion> completions=new ArrayList<>();
        for(int i=0;i<400&&walker.pending!=null;i++)completions.addAll(Engine.tick(restored).completions);
        assertNull(walker.pending);assertEquals("office",walker.location);
        assertTrue(completions.stream().anyMatch(c->c.id().equals("saved-walk")&&c.status().equals("completed")));
    }
    @Test void paidAndWaitingBusPassengersKeepTheirServiceAndFareState() {
        for(boolean waiting:List.of(false,true)) {
            State s=islandSeed();var trip=Traffic.trip(s,"bus:R1:0:600");s.minute=waiting?599:trip.arrivals().getFirst();
            Resident a=resident(s,"rider","stop-residential");Engine.Effects boarding=new Engine.Effects();
            if(!waiting){var curb=Traffic.platform(s,a.location,"R1");a.x=curb.x();a.z=curb.z();}
            Traffic.board(s,a,waiting?"R1":trip.id(),"stop-park","saved-bus",boarding,waiting);a.pending.finalDestination="office";
            if(!waiting)while(a.pending.type.equals("BOARD_BUS"))Engine.tick(s);
            String bus=a.pending.vehicleId,type=a.pending.type;long balance=a.money;
            OrganicCity.upgrade(s);assertEquals(balance,a.money);assertEquals(bus,a.pending.vehicleId);assertEquals(type,a.pending.type);
            assertEquals(Traffic.trip(s,bus).arrivals().get(3),a.pending.until);
            List<Engine.Completion> completions=new ArrayList<>();int fares=0;
            for(int i=0;i<400&&a.pending!=null;i++){var e=Engine.tick(s);completions.addAll(e.completions);fares+=e.payments.size();}
            assertNull(a.pending);assertEquals("office",a.location);assertEquals(waiting?1:0,fares);assertEquals(balance-(waiting?250:0),a.money);
            assertEquals(1,completions.size());assertEquals("saved-bus",completions.getFirst().id());
        }
    }
    @Test void interiorEntryAndActiveConversationRemainIntact() {
        State s=islandSeed();Resident inside=resident(s,"inside","office");inside.location="road";inside.z+=1.6;
        inside.pending=Engine.pending("entry","MOVE","office",0);inside.pending.entering=true;inside.pending.frontDoorRoute=true;
        Resident a=resident(s,"a","park"),b=resident(s,"b","park");b.x+=2;
        Conversation chat=new Conversation();chat.a=a.id;chat.b=b.id;chat.lastMinute=s.minute;chat.turns.add(new Turn(a.id,"Hello",s.minute));
        s.conversations.put(Conversations.key(a.id,b.id),chat);OrganicCity.upgrade(s);
        assertEquals(2,Math.hypot(a.x-b.x,a.z-b.z),.000001);assertEquals(1.6,inside.z-s.buildings.get("office").z,.000001);
        assertEquals("entry",inside.pending.id);Engine.tick(s);assertEquals("office",inside.location);assertNull(inside.pending);
    }
}
