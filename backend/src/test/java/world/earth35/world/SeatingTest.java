package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.AgentService;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class SeatingTest {
    private Resident visitor(State s,String id,String location) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=location;
        Building place=s.buildings.get(location);a.x=place.x;a.z=place.z;s.agents.put(id,a);return a;
    }
    private void act(State s,Resident a,ActionType action,String target) {
        String id=UUID.randomUUID().toString();
        Engine.apply(s,a,new Command(id,action,target,null,null,null,null),id,new Engine.Effects());
    }
    private AgentService.Observation observation(State s,Resident a) {
        return new AgentService.Observation(s.minute,s.version,"Clear","12:00",false,1800,a,
            s.agents.values().stream().filter(b->!a.id.equals(b.id)).map(WorldStore::publicAgent).toList(),
            List.copyOf(s.buildings.values()),List.of(),List.of(),List.of(ActionType.values()),List.of(),AgentService.gatheringPlaces(s));
    }
    @Test void arrivalDoesNotChooseSeatingAndFullBenchesRejectOnlyExplicitSitting() {
        State s=State.seed();Resident observer=visitor(s,"observer","community-garden");
        assertNull(observer.pending);assertEquals(4,observation(s,observer).getSeating().availableSeats());
        for(int i=0;i<4;i++) {
            Resident a=visitor(s,"sitter-"+i,"community-garden");act(s,a,ActionType.USE_OBJECT,"garden-table");
            assertEquals(i,a.pending.seatIndex);assertEquals("REST",a.pending.type);
        }
        var seats=observation(s,observer).getSeating();assertEquals(4,seats.occupied());assertEquals(0,seats.availableSeats());
        assertEquals(List.of("garden-table"),seats.objects());
        assertThrows(Engine.Invalid.class,()->act(s,observer,ActionType.USE_OBJECT,"garden-table"));assertNull(observer.pending);
        act(s,observer,ActionType.REST,null);assertNull(observer.pending.seatIndex);
        assertEquals(0,observation(s,observer).getSeating().availableSeats());
        act(s,visitor(s,"elsewhere","park"),ActionType.USE_OBJECT,"park-bench");
        assertEquals(0,observation(s,observer).getSeating().availableSeats());
    }
    @Test void completionReleasesOneSlotWithoutMovingOtherSittersAndRestartPreservesReservations() throws Exception {
        State s=State.seed();Resident first=visitor(s,"z-first","park"),second=visitor(s,"a-second","park");
        act(s,first,ActionType.USE_OBJECT,"park-bench");Engine.tick(s);act(s,second,ActionType.USE_OBJECT,"park-bench");
        assertEquals(0,first.pending.seatIndex);assertEquals(1,second.pending.seatIndex);
        ObjectMapper json=new ObjectMapper();s=json.readValue(json.writeValueAsString(s),State.class);
        first=s.agents.get(first.id);second=s.agents.get(second.id);
        assertEquals(1,WorldStore.publicAgent(second).seatIndex());
        for(int i=0;i<29;i++)Engine.tick(s);
        assertNull(first.pending);assertEquals(1,second.pending.seatIndex);
        Resident next=visitor(s,"next","park");act(s,next,ActionType.USE_OBJECT,"park-bench");assertEquals(0,next.pending.seatIndex);
        Engine.tick(s);assertNull(second.pending);assertEquals(0,next.pending.seatIndex);
        assertEquals(3,observation(s,next).getSeating().availableSeats());
    }
    @Test void readingSharesSeatingButStudyStillWorksWhenChairsAreOccupied() {
        State s=State.seed();Resident sitter=visitor(s,"sitter","library"),reader=visitor(s,"reader","library");
        assertEquals(1,Seating.capacity(s.buildings.get("library")));
        assertTrue(s.buildings.get("library").description.contains("Quiet study is the main purpose"));
        act(s,sitter,ActionType.USE_OBJECT,"library-chair");act(s,reader,ActionType.READ_BOOK,"science-everyday");
        assertNull(reader.pending.seatIndex);assertEquals(0,observation(s,reader).getSeating().availableSeats());
        for(int i=0;i<30;i++)Engine.tick(s);
        assertEquals(30,reader.knowledge.get("science").studyMinutes);assertNull(reader.pending);
        act(s,reader,ActionType.READ_BOOK,"science-everyday");assertEquals(0,reader.pending.seatIndex);
        assertEquals(0,observation(s,reader).getSeating().availableSeats());
    }
    @Test void seatingWorksForDiningAndScenicPlacesAndStillRequiresLocalOpenAccess() {
        State s=State.seed();s.minute=720;
        for(String id:List.of("bistro","river","riverside-walk","mountain","hill-station","trekking-trail")) {
            Resident a=visitor(s,id,id);String object=s.buildings.get(id).objects.keySet().iterator().next();
            act(s,a,ActionType.USE_OBJECT,object);assertEquals(0,a.pending.seatIndex);
            assertEquals(id,a.pending.target);
        }
        Resident wrong=visitor(s,"wrong","park");
        assertThrows(Engine.Invalid.class,()->act(s,wrong,ActionType.USE_OBJECT,"bistro-table"));assertNull(wrong.pending);
        s.minute=1400;Resident closed=visitor(s,"closed","bistro");
        assertThrows(Engine.Invalid.class,()->act(s,closed,ActionType.USE_OBJECT,"bistro-table"));assertNull(closed.pending);
        var noSeat=observation(s,visitor(s,"home","apt-101")).getSeating();assertEquals(0,noSeat.capacity());assertEquals(0,noSeat.availableSeats());
    }
}
