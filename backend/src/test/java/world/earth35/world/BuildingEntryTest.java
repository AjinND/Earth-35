package world.earth35.world;

import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import db.migration.V11__move_indoor_residents_inside;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;
import world.earth35.protocol.Contracts.*;

class BuildingEntryTest {
    Resident resident(State s,String location) {
        Resident a=new Resident();a.id="test";a.name="Test";a.home="apt-101";a.location=location;
        var b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(a.id,a);return a;
    }
    void move(State s,Resident a,ActionType pace,String destination) {
        Engine.apply(s,a,new Command("request",pace,destination,null,null,null,null),"trip",new Engine.Effects());
    }
    @Test void admissionAtTheDoorContinuesInsideBeforeCompletingTheJourney() {
        for(ActionType pace:List.of(ActionType.MOVE,ActionType.RUN)) {
            State s=State.seed();Resident a=resident(s,"park");var b=s.buildings.get("office");var door=Places.entrance(b);
            a.location="road";a.x=door.x()-1;a.z=door.z();move(s,a,pace,b.id);
            var effects=Engine.tick(s);assertEquals(door.x(),a.x,.000001);assertEquals(door.z(),a.z,.000001);
            assertEquals("road",a.location);assertNotNull(a.pending);assertTrue(a.pending.entering);assertTrue(effects.completions.isEmpty());
            for(int i=0;i<3&&a.pending!=null;i++)effects=Engine.tick(s);
            assertNull(a.pending);assertEquals(b.id,a.location);assertEquals(b.x,a.x,.000001);assertEquals(b.z,a.z,.000001);
            assertEquals("completed",effects.completions.getFirst().status());assertEquals("trip",effects.completions.getFirst().id());
            Engine.tick(s);assertEquals(b.z,a.z,.000001);
        }
    }
    @Test void aClosingVenueRejectsAtTheDoorButDoesNotEvictAnAlreadyAdmittedResident() {
        State s=State.seed();s.minute=1198;Resident a=resident(s,"park");var b=s.buildings.get("coffee");var door=Places.entrance(b);
        a.location="road";a.x=door.x()-1;a.z=door.z();move(s,a,ActionType.MOVE,b.id);Engine.tick(s);
        assertTrue(a.pending.entering);Engine.tick(s);assertEquals(b.id,a.location);assertTrue(Travel.sheltered(s,a));
        Engine.tick(s);assertNull(a.pending);assertEquals(b.z,a.z,.000001);
        Resident late=resident(s,"park");late.location="road";late.x=door.x();late.z=door.z();
        late.pending=Engine.pending("late","MOVE",b.id,0);late.pending.frontDoorRoute=true;
        var rejection=Engine.tick(s);assertEquals("road",late.location);assertNull(late.pending);assertEquals(door.z(),late.z,.000001);
        assertEquals("rejected",rejection.completions.getFirst().status());
    }
    @Test void departuresPauseAtTheFrontDoorSoRenderingCannotCutAcrossASideWall() {
        for(ActionType pace:List.of(ActionType.MOVE,ActionType.RUN)) {
            State s=State.seed();Resident a=resident(s,"apt-101");var home=s.buildings.get(a.home);var door=Places.entrance(home);
            move(s,a,pace,"park");assertTrue(Travel.sheltered(s,a));
            for(int i=0;i<3&&a.pending.exitDoor!=null;i++)Engine.tick(s);
            assertNotNull(a.pending);assertNull(a.pending.exitDoor);assertEquals(door.x(),a.x,.000001);assertEquals(door.z(),a.z,.000001);
            Engine.tick(s);assertTrue(Math.hypot(a.x-door.x(),a.z-door.z())>0);
        }
    }
    @Test void anInteriorLegSurvivesReloadWithoutReturningToTheDoor() throws Exception {
        State s=State.seed();Resident a=resident(s,"park");var b=s.buildings.get("office");var door=Places.entrance(b);
        a.location="road";a.x=door.x();a.z=door.z();move(s,a,ActionType.MOVE,b.id);Engine.tick(s);
        var json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);Resident saved=restored.agents.get(a.id);
        assertTrue(saved.pending.entering);double previousZ=saved.z;Engine.tick(restored);
        assertTrue(saved.z<previousZ);assertNull(saved.pending);assertEquals(b.z,saved.z,.000001);
    }
    @Test void migrationMovesOnlyIndoorOccupantsAndPreservesActionsAndPrivateState() throws Exception {
        State s=State.seed();s.minute=1300;Resident a=resident(s,"apt-101");var door=Places.entrance(s.buildings.get(a.home));a.z=door.z();
        a.pending=Engine.pending("sleep","SLEEP",a.home,1800);a.money=12345;
        var waiting=new Resident();waiting.id="outside";waiting.location="road";waiting.x=door.x();waiting.z=door.z();s.agents.put(waiting.id,waiting);
        var json=new ObjectMapper();ObjectNode data=json.valueToTree(s);data.put("unknown","keep");var pending=data.at("/agents/test/pending").deepCopy();
        V11__move_indoor_residents_inside.upgrade(data);
        assertEquals(s.buildings.get(a.home).z,data.at("/agents/test/z").asDouble(),.000001);
        assertEquals(door.z(),data.at("/agents/outside/z").asDouble(),.000001);assertEquals(pending,data.at("/agents/test/pending"));
        assertEquals(12345,data.at("/agents/test/money").asLong());assertEquals(1300,data.path("minute").asLong());assertEquals("keep",data.path("unknown").asText());
    }
}
