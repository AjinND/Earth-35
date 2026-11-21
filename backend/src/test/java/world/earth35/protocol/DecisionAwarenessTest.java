package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.*;
import world.earth35.world.State.*;

class DecisionAwarenessTest {
    private AgentService.Observation observation(State s,Resident a,Map<String,Boolean> available) {
        return new AgentService.Observation(s.minute,1,"Clear","12:00",false,1800,a,
            s.agents.values().stream().filter(b->!b.id.equals(a.id)&&Social.perceivable(s,a,b)&&Math.hypot(a.x-b.x,a.z-b.z)<=20).map(WorldStore::publicAgent).toList(),
            s.buildings.values().stream().filter(b->b.capacity>1||b.id.equals(a.home)).toList(),List.of(),List.of(),List.of(),List.of(),
            AgentService.gatheringPlaces(s),null,Conversations.views(s,a,available),List.of(),null,null,Social.situation(s,a,available),null);
    }
    private Resident resident(State s,String id,String location) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=location;
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    @Test void usefulPlacesExcludeClosedVenuesAndSelfFromVisitorCounts() {
        State s=State.prototypeSeed();s.minute=720;Resident a=resident(s,"self","park");
        resident(s,"peer","courtyard");
        Building coffee=s.buildings.get("coffee");coffee.openMinute=780;coffee.closeMinute=900;
        var awareness=observation(s,a,Map.of()).getAwareness();
        assertEquals("courtyard",awareness.placesToConsider().getFirst().id());
        assertEquals(1,awareness.placesToConsider().getFirst().otherResidentsHere());
        assertEquals(0,awareness.placesToConsider().stream().filter(p->p.id().equals("park")).findFirst().orElseThrow().otherResidentsHere());
        assertTrue(awareness.placesToConsider().stream().noneMatch(p->p.id().equals("coffee")));
        assertTrue(awareness.placesToConsider().size()<=8);
        assertNull(awareness.minutesSinceConversation());
    }
    @Test void currentPresenceExcludesAbsentPlanPartnersAndSeparatesAvailability() throws Exception {
        State s=State.prototypeSeed();s.minute=1179;
        Resident a=resident(s,"oliver","community-garden"),theo=resident(s,"theo","community-garden");
        Resident aisha=resident(s,"aisha","park"),diego=resident(s,"diego","courtyard");
        // Keep an observed passerby in the encounter radius, but outside actual talking range.
        Resident passer=resident(s,"passer","community-garden");passer.x=a.x+15;
        a.mind.intention=new Cognition.Intention("Meet with Aisha and Diego","Discuss the garden","An invitation",a.location,s.minute,s.minute+30);
        var observed=observation(s,a,Map.of(theo.id,false,passer.id,true));
        var presence=observed.getAwareness().presence();
        assertEquals(a.location,presence.location());
        assertEquals(List.of(new DecisionAwareness.PresentPerson(theo.id,theo.name)),presence.people());
        assertFalse(observed.getAwareness().nearbyPeople().stream().filter(p->p.id().equals(theo.id)).findFirst().orElseThrow().canTalk());
        assertTrue(observed.getAwareness().nearbyPeople().stream().anyMatch(p->p.id().equals(passer.id)));
        String serialized=new ObjectMapper().writeValueAsString(presence);
        assertFalse(serialized.contains(aisha.id));assertFalse(serialized.contains(diego.id));
        // No remote position or destination is disclosed by the attendance view.
        assertFalse(serialized.contains("courtyard"));assertFalse(serialized.contains("park"));
    }
    @Test void availabilityConstraintsAndLearnedCapabilitiesAreGroundedAndPrivate() throws Exception {
        State s=State.prototypeSeed();s.minute=720;Resident a=resident(s,"self","park"),b=resident(s,"peer","park");
        b.goals="private peer goal";b.money=987654;SubjectKnowledge knowledge=new SubjectKnowledge();
        knowledge.points=28;knowledge.concepts.addAll(List.of("one","two","three","four"));knowledge.intermediateConcepts=4;a.knowledge.put("design",knowledge);
        a.pending=new Pending();a.pending.type="REST";
        var awareness=observation(s,a,Map.of(b.id,false)).getAwareness();
        assertFalse(awareness.nearbyPeople().getFirst().canTalk());
        assertTrue(awareness.nearbyPeople().getFirst().reason().contains("unavailable"));
        assertTrue(awareness.learning().getFirst().canCreate());
        String serialized=new ObjectMapper().writeValueAsString(awareness);
        assertFalse(serialized.contains(b.goals));assertFalse(serialized.contains("987654"));
        assertTrue(observation(s,a,Map.of(b.id,true)).getAwareness().nearbyPeople().getFirst().canTalk());
        a.pending.type="WAIT_BUS";
        assertFalse(observation(s,a,Map.of(b.id,true)).getAwareness().nearbyPeople().getFirst().canTalk());
    }
    @Test void hostedRestDecisionsWakeForIncomingTurnsAndRespectIgnoringThem() {
        State s=State.prototypeSeed();s.minute=720;Resident a=resident(s,"self","park"),b=resident(s,"peer","park");
        a.pending=new Pending();a.pending.type="REST";
        Social.reviewed(s,a);
        assertFalse(HostedAgentService.decisionDue(observation(s,a,Map.of(b.id,true))));
        Conversations.speak(s,b,a,"Shall we talk about what we read?");
        var incoming=observation(s,a,Map.of(b.id,true));
        assertTrue(HostedAgentService.decisionDue(incoming));assertTrue(HostedAgentService.socialDecisionDue(incoming));
        Social.reviewed(s,a);
        assertFalse(HostedAgentService.decisionDue(observation(s,a,Map.of(b.id,true))));
        for(String activity:List.of("WORK","READ_BOOK","SLEEP")) {
            a.pending.type=activity;
            assertFalse(HostedAgentService.decisionDue(incoming));
        }
    }
    @Test void repetitionFeedbackDistinguishesRejectedChoicesFromUsefulActivity() {
        Resident a=new Resident();
        var rejected=new HostedAgentService.ActionFeedback("TALK","peer",null,"rejected","Resident is unavailable",700);
        assertTrue(HostedAgentService.decisionFeedback(List.of(rejected,rejected,rejected),a).getFirst().contains("prerequisite"));
        var look=new HostedAgentService.ActionFeedback("LOOK",null,null,"completed","Observation refreshed",700);
        assertFalse(HostedAgentService.decisionFeedback(List.of(look,look,look),a).isEmpty());
        var study=new HostedAgentService.ActionFeedback("READ_BOOK",null,null,"completed","New lesson",700);
        assertTrue(HostedAgentService.decisionFeedback(List.of(study,study,study),a).isEmpty());
        assertTrue(HostedAgentService.decisionFeedback(List.of(look,rejected,study),a).isEmpty());
    }
}
