package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class CognitionTest {
    private Resident resident(State s,String id,String location) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=location;
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    private Cognition.Update plan(String target) {
        return new Cognition.Update(new Cognition.IntentionDraft("Learn about gardening","Visit the library and choose a book",
            "I want to improve my garden",target,60),false,"Curious",null,null);
    }
    @Test void perceptionHasLocalBoundariesAndNeverConsumesAttentionOnRead() throws Exception {
        State s=State.prototypeSeed();s.minute=720;Resident a=resident(s,"alex","park"),b=resident(s,"sam","park"),hidden=resident(s,"hidden","apt-101");
        hidden.x=a.x;hidden.z=a.z;hidden.mind.feeling="Secret emotion";
        Cognition.perceive(s,a,Map.of());long revision=a.mind.revision;
        assertTrue(a.mind.perceived.containsKey("person:sam"));assertFalse(a.mind.perceived.containsKey("person:hidden"));
        Cognition.perceive(s,a,Map.of());assertEquals(revision,a.mind.revision);assertEquals(1,a.mind.visits.get("park").count());
        assertFalse(new ObjectMapper().writeValueAsString(Cognition.view(a,s.minute)).contains("Secret emotion"));
        Cognition.action(a,s.minute,"look","LOOK",null,"completed","Observation refreshed");
        assertTrue(Cognition.view(a,s.minute).noticedChanges().isEmpty());
        b.location="road";b.x=900;b.z=900;s.minute++;
        Cognition.perceive(s,a,Map.of());
        String departure=Cognition.view(a,s.minute).noticedChanges().getFirst().description();
        assertTrue(departure.contains("no longer in view"));assertFalse(departure.contains("900"));
    }
    @Test void changeAwarenessOffersAwakeActivityReviewWithoutSpamOrChangingTheTask() {
        State s=State.prototypeSeed();s.minute=720;Resident a=resident(s,"alex","park");
        a.pending=new Pending();a.pending.type="REST";
        Cognition.perceive(s,a,Map.of());assertTrue(Cognition.view(a,s.minute).reconsider());
        Cognition.action(a,s.minute,"look","LOOK",null,"completed","Observed");
        a.energy=15;Cognition.perceive(s,a,Map.of());
        assertFalse(Cognition.view(a,s.minute).reconsider());
        s.minute+=15;assertTrue(Cognition.view(a,s.minute).reconsider());
        for(String type:List.of("SLEEP","RIDE_BUS","BOARD_BUS")) {
            a.pending.type=type;assertFalse(Cognition.view(a,s.minute).reconsider(),type);
        }
        for(String type:List.of("WORK","READ_BOOK","WAIT_BUS","WAIT_RAIN","RESEARCH_PROJECT","BUILD_PROJECT","PRODUCE_PRODUCT","DO_ACTIVITY")) {
            a.pending.type=type;assertTrue(Cognition.view(a,s.minute).reconsider(),type);assertEquals(type,a.pending.type);
        }
        a.pending.type="MOVE";assertTrue(Cognition.view(a,s.minute).reconsider());
        a.paused=true;assertFalse(Cognition.view(a,s.minute).reconsider());
    }
    @Test void intentionAndPerceptionPersistAndArrivalsNeverAutomaticallyCompletePlans() throws Exception {
        State s=State.prototypeSeed();s.minute=720;Resident a=resident(s,"alex","park");
        Cognition.perceive(s,a,Map.of());Cognition.update(s,a,plan("library"));
        var json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);a=restored.agents.get(a.id);
        assertEquals("Learn about gardening",a.mind.intention.focus());assertEquals("Curious",a.mind.feeling);
        assertEquals(1,a.mind.visits.get("park").count());
        a.location="library";a.x=restored.buildings.get("library").x;a.z=restored.buildings.get("library").z;
        Cognition.perceive(restored,a,Map.of());
        assertNotNull(a.mind.intention);assertTrue(Cognition.view(a,720).noticedChanges().stream().anyMatch(n->n.description().contains("intended destination")));
        restored.minute=781;Cognition.perceive(restored,a,Map.of());assertTrue(Cognition.view(a,781).intentionReviewDue());
        Cognition.action(a,781,"look","LOOK",null,"completed","Observed");assertFalse(Cognition.view(a,781).intentionReviewDue());
        assertNotNull(a.mind.intention);
        Cognition.update(restored,a,new Cognition.Update(null,true,null,null,null));assertNull(a.mind.intention);
    }
    @Test void acceptedActionsStayInProgressUntilRealCompletionAndReflectionsNeedOwnEvidence() {
        State s=State.prototypeSeed();s.minute=720;Resident a=resident(s,"alex","park"),b=resident(s,"sam","park");
        Engine.apply(s,a,new Command("rest",ActionType.REST,null,null,null,null,null),"rest",new Engine.Effects());
        Cognition.action(a,s.minute,"rest","REST",null,"accepted","Action accepted");
        var reflection=new Cognition.Update(null,false,null,"A short break helped me recover","rest");
        assertThrows(Engine.Invalid.class,()->Cognition.update(s,a,reflection));
        for(int i=0;i<45&&a.pending!=null;i++)Cognition.complete(s,Engine.tick(s));
        assertEquals("completed",a.mind.outcomes.getFirst().status());
        Cognition.update(s,a,reflection);Cognition.update(s,a,reflection);
        assertEquals(1,a.mind.reflections.size());assertTrue(b.mind.reflections.isEmpty());
        assertThrows(Engine.Invalid.class,()->Cognition.update(s,b,reflection));
    }
    @Test void invalidUpdatesAreAtomicAndCannotGrantWorldCapabilities() {
        State s=State.prototypeSeed();Resident a=resident(s,"alex","park");Cognition.update(s,a,plan("library"));
        var original=a.mind.intention;long money=a.money;double energy=a.energy;
        assertThrows(Engine.Invalid.class,()->Cognition.update(s,a,new Cognition.Update(null,true,"Happy","I built a company","imaginary")));
        assertSame(original,a.mind.intention);assertEquals("Curious",a.mind.feeling);
        assertThrows(Engine.Invalid.class,()->Cognition.update(s,a,plan("apt-102")));
        assertEquals(money,a.money);assertEquals(energy,a.energy);assertTrue(a.knowledge.isEmpty());
    }
    @Test void oldSavesInitializeSafelyAndBoundedHistoryRetainsThePendingReceipt() throws Exception {
        var json=new ObjectMapper();Resident legacy=json.readValue("{\"id\":\"legacy\",\"home\":\"apt-101\",\"location\":\"park\"}",Resident.class);
        assertNotNull(legacy.mind);assertNull(Cognition.view(legacy,720).surroundings());
        Cognition.action(legacy,720,"trip","MOVE","library","accepted","Walking");
        for(int i=0;i<40;i++)Cognition.action(legacy,721+i,"look-"+i,"LOOK",null,"completed","Observed");
        assertEquals(Cognition.OUTCOME_LIMIT,legacy.mind.outcomes.size());
        assertEquals("trip",legacy.mind.outcomes.getFirst().id());
        State s=State.prototypeSeed();s.agents.put(legacy.id,legacy);
        var effects=new Engine.Effects();effects.completions.add(new Engine.Completion("trip","cancelled","Changed the plan"));Cognition.complete(s,effects);
        assertEquals("cancelled",legacy.mind.outcomes.getFirst().status());
    }
}
