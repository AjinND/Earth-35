package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.AgentService;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class ScenicPlacesTest {
    private static final List<String> IDS=List.of("river","riverside-walk","mountain","hill-station","trekking-trail","mall");
    private Command command(ActionType type,String target) {return new Command("request",type,target,null,null,null,null);}
    @Test void migrationIsAdditiveAndIdempotent() {
        var json=new ObjectMapper();State state=State.seed();IDS.forEach(state.buildings::remove);
        state.minute=4567;state.version=123;Resident a=new Resident();a.id="maya";a.location="road";a.money=4321;
        a.pending=new Pending();a.pending.type="MOVE";a.pending.target="park";state.agents.put(a.id,a);
        ObjectNode data=json.valueToTree(state);data.put("futureField","preserve");ObjectNode before=data.deepCopy();
        ScenicPlaces.upgrade(data);
        for(String id:IDS)assertTrue(data.path("buildings").has(id));
        ObjectNode once=data.deepCopy();ScenicPlaces.upgrade(data);assertEquals(once,data);
        IDS.forEach(data.withObject("/buildings")::remove);assertEquals(before,data);
    }
    @Test void everyScenicPlaceSupportsExplicitVisitsRestAndConversation() {
        for(String id:IDS) {
            State state=State.seed();state.minute=720;Resident a=new Resident();a.id="maya";a.name="Maya";a.home="apt-101";a.location=a.home;
            a.x=state.buildings.get(a.home).x;a.z=state.buildings.get(a.home).z;state.agents.put(a.id,a);
            assertTrue(AgentService.gatheringPlaces(state).stream().anyMatch(p->p.id().equals(id)),id);
            Engine.apply(state,a,command(ActionType.MOVE,id),"move",new Engine.Effects());
            for(int i=0;i<300&&a.pending!=null;i++)Engine.tick(state);
            assertNull(a.pending,id);assertEquals(id,a.location);
            Building b=state.buildings.get(id);assertEquals(b.x,a.x);assertEquals(b.z,a.z);
            assertEquals(id.equals("mall"),Travel.sheltered(b));
            a.energy=40;Engine.apply(state,a,command(ActionType.USE_OBJECT,b.objects.keySet().iterator().next()),"rest",new Engine.Effects());
            for(int i=0;i<30;i++)Engine.tick(state);
            assertNull(a.pending);assertTrue(a.energy>40,id);
            Resident friend=new Resident();friend.id="alex";friend.name="Alex";friend.location=id;friend.x=b.x;friend.z=b.z;state.agents.put(friend.id,friend);
            var effects=new Engine.Effects();Engine.apply(state,a,new Command("talk",ActionType.TALK,"alex","Lovely place for a visit!",null,null,null),"talk",effects);
            assertFalse(effects.facts.isEmpty(),id);
        }
    }
    @Test void mallHasHoursAndOutdoorsStayOpenWithoutSchedulingAnOuting() {
        State state=State.seed();state.minute=540;
        assertFalse(Places.open(state.buildings.get("mall"),state.minute));
        assertTrue(Places.open(state.buildings.get("mall"),600));
        assertFalse(Places.open(state.buildings.get("mall"),1320));
        for(String id:IDS)if(!id.equals("mall"))assertTrue(Places.open(state.buildings.get(id),0));
        Resident a=new Resident();a.id="maya";a.name="Maya";a.home="apt-101";a.location="park";a.x=30;a.z=30;state.agents.put(a.id,a);
        for(int i=0;i<60;i++)Engine.tick(state);
        assertNull(a.pending);assertEquals("park",a.location);
    }
    @Test void newPlotsDoNotOverlapExistingPlaces() {
        State state=State.seed();
        for(String id:IDS) {
            Building place=state.buildings.get(id);
            for(Building other:state.buildings.values())if(!id.equals(other.id))
                assertTrue(Math.abs(place.x-other.x)>=8.4||Math.abs(place.z-other.z)>=8.4,id+" overlaps "+other.id);
        }
    }
}
