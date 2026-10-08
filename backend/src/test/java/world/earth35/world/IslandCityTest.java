package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;
import world.earth35.protocol.Contracts.*;

class IslandCityTest {
    private State islandSeed() {State s=State.prototypeSeed();IslandCity.upgrade(s);return s;}
    Resident resident(State s,String id,String location) {
        Resident a=new Resident();a.id=id;a.name=id;a.ownerId="owner";a.home="apt-101";a.location=location;
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    void move(State s,Resident a,ActionType pace,String destination) {
        Engine.apply(s,a,new Command("request",pace,destination,null,null,null,null),"journey",new Engine.Effects());
    }
    @Test void districtsSpreadAcrossTheIslandWithStableBuildingSizesAndPlatforms() {
        State old=State.prototypeSeed(),s=islandSeed();CityLayout layout=CityLayout.of(s);
        assertEquals(1,layout.version());assertEquals(48,layout.spacing());assertEquals(10,layout.metersPerUnit());
        assertEquals(old.buildings.keySet(),s.buildings.keySet());assertEquals(old.jobs.keySet(),s.jobs.keySet());
        for(Building b:s.buildings.values()) {
            assertTrue(Math.abs(b.x-layout.centerX())<layout.halfExtent());assertTrue(Math.abs(b.z-layout.centerZ())<layout.halfExtent());
            assertEquals(old.buildings.get(b.id).capacity,b.capacity);
            assertEquals(Places.enclosed(b)?3.6:0,Places.entrance(b).z()-b.z,.000001);
        }
        double oldWidth=old.buildings.get("house-150").x-old.buildings.get("apt-101").x;
        assertEquals(oldWidth*4,s.buildings.get("house-150").x-s.buildings.get("apt-101").x,.000001);
        for(var stop:Traffic.stops(s)) {
            assertEquals(stop.x(),s.buildings.get(stop.id()).x,.000001);
            assertEquals(2.4,Math.hypot(stop.x()-stop.roadX(),stop.z()-stop.roadZ()),.000001);
            assertFalse(layout.onRoad(stop.x(),stop.z()));
        }
    }
    @Test void migrationIsIdempotentAndPreservesPrivateStateAndAcceptedJourney() throws Exception {
        State s=State.prototypeSeed();s.minute=560;Resident a=resident(s,"walker","apt-101");a.money=12345;a.job="job-office";
        a.relationships.put("friend","close friend");a.routine=new DailyRoutine();a.nightPlan=new NightPlan("Watch stars",1400);
        move(s,a,ActionType.MOVE,"office");Engine.tick(s);String action=a.pending.id;long minute=s.minute;
        Resident resting=resident(s,"resting","park");resting.pending=Engine.pending("rest","REST","park",600);
        ObjectMapper json=new ObjectMapper();ObjectNode data=json.valueToTree(s);data.put("futureState","keep");
        ((ObjectNode)data.at("/agents/walker")).put("privateMemory","unchanged");
        ((ObjectNode)data.at("/agents/walker/pending")).put("futureJourneyField","keep");
        var beforeResident=data.at("/agents/walker").deepCopy();var beforeRest=data.at("/agents/resting/pending").deepCopy();
        assertTrue(IslandCity.upgrade(data).isEmpty());assertEquals(minute,data.path("minute").asLong());
        for(String field:List.of("ownerId","home","job","money","inventory","relationships","routine","nightPlan","privateMemory"))
            assertEquals(beforeResident.path(field),data.at("/agents/walker").path(field),field);
        assertEquals(action,data.at("/agents/walker/pending/id").asText());
        assertEquals("keep",data.at("/agents/walker/pending/futureJourneyField").asText());
        assertEquals(beforeRest,data.at("/agents/resting/pending"));assertEquals("keep",data.path("futureState").asText());
        var once=data.deepCopy();assertTrue(IslandCity.upgrade(data).isEmpty());assertEquals(once,data);
        State restored=json.readerFor(State.class).without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(data);
        Resident walker=restored.agents.get(a.id);Engine.Effects effects=null;
        for(int i=0;i<400&&walker.pending!=null;i++)effects=Engine.tick(restored);
        assertNull(walker.pending);assertEquals("office",walker.location);assertEquals(action,effects.completions.getFirst().id());
    }
    @Test void paidBusPassengersKeepTheirFareAndFinishTheirOriginalItinerary() {
        State s=State.prototypeSeed();var trip=Traffic.trip("bus:R1:0:600");s.minute=trip.arrivals().getFirst();
        Resident a=resident(s,"rider","stop-residential");Engine.Effects boarding=new Engine.Effects();
        var platform=Traffic.platform(s,a.location,trip.route().id());a.x=platform.x();a.z=platform.z();
        Traffic.board(s,a,trip.id(),"stop-park","paid-journey",boarding,false);a.pending.finalDestination="office";
        assertEquals("BOARD_BUS",a.pending.type);
        for(int i=0;i<6&&a.pending.type.equals("BOARD_BUS");i++)boarding.payments.addAll(Engine.tick(s).payments);
        assertEquals("RIDE_BUS",a.pending.type);
        long paidBalance=a.money;assertEquals(1,boarding.payments.size());assertTrue(IslandCity.upgrade(s).isEmpty());
        assertEquals("paid-journey",a.pending.id);assertEquals(paidBalance,a.money);
        List<Engine.Completion> completions=new ArrayList<>();List<Engine.Payment> payments=new ArrayList<>();
        for(int i=0;i<400&&a.pending!=null;i++){var effects=Engine.tick(s);completions.addAll(effects.completions);payments.addAll(effects.payments);}
        assertNull(a.pending);assertEquals("office",a.location);assertEquals(paidBalance,a.money);assertTrue(payments.isEmpty());
        assertEquals(1,completions.size());assertEquals("paid-journey",completions.getFirst().id());assertEquals("completed",completions.getFirst().status());
    }
    @Test void waitingPassengersRetainTheirServiceAndPayOnlyAtBoarding() {
        State s=State.prototypeSeed();s.minute=599;Resident a=resident(s,"waiting","stop-residential");
        Traffic.board(s,a,"R1","stop-park","wait-journey",new Engine.Effects(),true);assertEquals("WAIT_BUS",a.pending.type);
        long balance=a.money;IslandCity.upgrade(s);assertEquals(balance,a.money);assertEquals("wait-journey",a.pending.id);
        int fares=0;for(int i=0;i<200&&a.pending!=null;i++)fares+=Engine.tick(s).payments.size();
        assertEquals(1,fares);assertEquals(balance-250,a.money);assertEquals("stop-park",a.location);
    }
    @Test void interiorLegsAndActiveConversationsKeepTheirLocalPositions() {
        State s=State.prototypeSeed();Resident inside=resident(s,"inside","office");inside.location="road";
        inside.z+=1.6;inside.pending=Engine.pending("entry","MOVE","office",0);inside.pending.entering=true;inside.pending.frontDoorRoute=true;
        Resident a=resident(s,"a","park"),b=resident(s,"b","park");b.x+=2;
        Conversation chat=new Conversation();chat.a=a.id;chat.b=b.id;chat.lastMinute=s.minute;chat.turns.add(new Turn(a.id,"Hello",s.minute));
        s.conversations.put(Conversations.key(a.id,b.id),chat);IslandCity.upgrade(s);
        assertEquals(2,Math.hypot(a.x-b.x,a.z-b.z),.000001);assertEquals(1.6,inside.z-s.buildings.get("office").z,.000001);
        assertEquals("entry",inside.pending.id);Engine.tick(s);assertEquals("office",inside.location);assertNull(inside.pending);
    }
    @Test void updatedWalkingAndRunningSpeedsApplyAfterTheFrontDoorPause() {
        State walk=islandSeed(),run=islandSeed();Resident a=resident(walk,"a","apt-101"),b=resident(run,"b","apt-101");
        move(walk,a,ActionType.MOVE,"park");move(run,b,ActionType.RUN,"park");Engine.tick(walk);Engine.tick(run);
        assertNull(a.pending.exitDoor);assertNull(b.pending.exitDoor);double ax=a.x,az=a.z,bx=b.x,bz=b.z;
        Engine.tick(walk);Engine.tick(run);
        assertEquals(8,Math.abs(a.x-ax)+Math.abs(a.z-az),.000001);assertEquals(16,Math.abs(b.x-bx)+Math.abs(b.z-bz),.000001);
        for(var signal:Traffic.network(walk).signals())assertEquals(Traffic.green(walk.minute,signal.x(),signal.z(),CityLayout.ISLAND),signal.vehicleGreen());
    }
}
