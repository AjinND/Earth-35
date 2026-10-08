package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.AgentService;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class CityPlacesTest {
    private static final List<String> ADDED=List.of("willow-park","community-garden","town-square","promenade","courtyard","community-center","library","bistro");
    private final ObjectMapper json=new ObjectMapper();
    private Command command(ActionType type,String target) {return new Command("request",type,target,null,null,null,null);}
    private Resident resident(State state,String id,String location) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=location;
        Building b=state.buildings.get(location);a.x=b.x;a.z=b.z;state.agents.put(id,a);return a;
    }
    @Test void migrationPreservesSavedStateAndIsIdempotent() {
        State state=State.prototypeSeed();OrganicCity.upgrade(state);ADDED.forEach(state.buildings::remove);state.jobs.remove("job-bistro");state.accounts.remove("bistro");
        state.minute=15390;state.version=123;resident(state,"maya","office").money=12345;
        state.buildings.get("restaurant").stock.put("meal",7);state.accounts.put("restaurant",321L);
        ObjectNode data=json.valueToTree(state);data.put("futureField","preserve");
        var agents=data.get("agents").deepCopy();var restaurant=data.path("buildings").path("restaurant").get("stock").deepCopy();
        CityPlaces.upgrade(data);
        assertEquals(agents,data.get("agents"));assertEquals(15390,data.path("minute").asLong());assertEquals(123,data.path("version").asLong());
        assertEquals(restaurant,data.path("buildings").path("restaurant").get("stock"));assertEquals(321,data.path("accounts").path("restaurant").asLong());
        assertEquals("preserve",data.path("futureField").asText());assertEquals(80,data.path("buildings").size());
        ObjectNode once=data.deepCopy();CityPlaces.upgrade(data);assertEquals(once,data);
        data.withObject("/buildings/bistro/stock").put("meal",4);data.withObject("/accounts").put("bistro",55);
        CityPlaces.upgrade(data);assertEquals(4,data.at("/buildings/bistro/stock/meal").asInt());assertEquals(55,data.at("/accounts/bistro").asLong());
    }
    @Test void everyAddedDestinationCanBeReachedRestedAtAndUsed() {
        for(String id:ADDED) {
            State state=State.seed();state.minute=720;Resident a=resident(state,"maya","apt-101");
            Engine.apply(state,a,command(ActionType.MOVE,id),"move",new Engine.Effects());
            for(int i=0;i<300&&a.pending!=null;i++)Engine.tick(state);
            assertNull(a.pending,id);assertEquals(id,a.location);var place=state.buildings.get(id);assertEquals(place.x,a.x);assertEquals(place.z,a.z);
            a.energy=40;double before=a.energy;Engine.apply(state,a,command(ActionType.REST,null),"rest",new Engine.Effects());
            for(int i=0;i<30;i++)Engine.tick(state);
            assertNull(a.pending);assertTrue(a.energy>before,id);
            String object=state.buildings.get(id).objects.keySet().iterator().next();
            Engine.apply(state,a,command(ActionType.USE_OBJECT,object),"seat",new Engine.Effects());
            assertEquals(id,a.pending.target);assertEquals("REST",a.pending.type);
        }
    }
    @Test void residentsCanTalkAtEveryAddedPlace() {
        for(String id:ADDED) {
            State state=State.seed();Resident a=resident(state,"maya",id);resident(state,"alex",id);
            var effects=new Engine.Effects();
            Engine.apply(state,a,new Command("talk",ActionType.TALK,"alex","Hello, nice to meet you!",null,null,null),"talk",effects);
            assertTrue(effects.facts.stream().anyMatch(f->f.actor().equals("maya")&&f.target().equals("alex")),id);
        }
    }
    @Test void seatingRequiresBeingAtItsOwnPlaceAndCannotBeUsedRemotely() {
        State state=State.seed();Resident a=resident(state,"maya","park");
        assertThrows(Engine.Invalid.class,()->Engine.apply(state,a,command(ActionType.USE_OBJECT,"garden-table"),"seat",new Engine.Effects()));
        assertThrows(Engine.Invalid.class,()->Engine.apply(state,a,command(ActionType.USE_OBJECT,"unknown"),"seat",new Engine.Effects()));
        assertNull(a.pending);
    }
    @Test void bistroSupportsMealsCoffeeAndARealPaidJob() {
        State state=State.seed();Resident a=resident(state,"maya","bistro");long initial=a.money;
        state.minute=660;
        Engine.apply(state,a,new Command("buy",ActionType.BUY,"bistro",null,"meal",1,null),"buy",new Engine.Effects());
        assertEquals(initial-1000,a.money);assertEquals(99,state.buildings.get("bistro").stock.get("meal"));
        Engine.apply(state,a,command(ActionType.APPLY_JOB,"job-bistro"),"apply",new Engine.Effects());
        Engine.apply(state,a,command(ActionType.ACCEPT_JOB,"job-bistro"),"accept",new Engine.Effects());
        Engine.apply(state,a,command(ActionType.WORK,null),"work",new Engine.Effects());
        for(int i=0;i<60;i++)Engine.tick(state);
        assertEquals(initial+1000,a.money);assertNull(a.pending);
    }
    @Test void gatheringCountsTrackArrivalsAndExcludePrivateHomes() {
        State state=State.seed();Resident a=resident(state,"maya","courtyard");resident(state,"alex","courtyard");resident(state,"sam","apt-101");
        var places=AgentService.gatheringPlaces(state);assertEquals(33,places.size());assertEquals("courtyard",places.getFirst().id());assertEquals(2,places.getFirst().residentsHere());
        assertTrue(places.stream().noneMatch(p->p.id().equals("apt-101")));
        a.location="road";assertEquals(1,AgentService.gatheringPlaces(state).getFirst().residentsHere());
    }
    @Test void newSeatingRemainsAnExplicitChoiceAtNight() {
        State state=State.seed();state.minute=1200;Resident a=resident(state,"maya","courtyard");
        Command proposed=command(ActionType.USE_OBJECT,"courtyard-table");Command resolved=NightRoutine.resolve(state,a,proposed);
        assertSame(proposed,resolved);
        a.nightPlan=new NightPlan("Meeting Alex at the courtyard",1260);assertSame(proposed,NightRoutine.resolve(state,a,proposed));
    }
}
