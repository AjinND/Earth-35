package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import world.earth35.world.*;
import world.earth35.world.State.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HostedAgreementFollowthroughTest {
    private final ObjectMapper json=new ObjectMapper();
    private Contracts.Command command(String answer) {
        var response=json.createObjectNode();
        response.putArray("choices").addObject().putObject("message").put("content",answer);
        return HostedDecisionParser.parse(json,response).command();
    }
    private Resident resident(State state,String id,double x) {
        Resident resident=new Resident();resident.id=id;resident.name=id;resident.home="home1";
        resident.location="road";resident.x=x;resident.z=2.4;state.agents.put(id,resident);return resident;
    }
    @Test void realProviderAnswerAgreementProducesDurableActionsAndTimedCompletionWithoutFurtherInference() throws Exception {
        State state=State.prototypeSeed();Resident diego=resident(state,"diego",2.4),mei=resident(state,"mei",4.4);
        Contracts.Command proposal=command("""
            {"action":"TALK","target":"mei","message":"Shall we search together?",
             "sharedPlan":{"operation":"propose","destination":"park","activity":"Search for native plants",
             "delayMinutes":1,"durationMinutes":5}}
            """);
        Engine.apply(state,diego,proposal,"proposal",new Engine.Effects());
        assertEquals("proposed",state.socialPlans.get("proposal").status);
        assertNull(mei.pending);
        Contracts.Command accept=command("""
            {"action":"END_CHAT","target":"diego","message":"Agreed. See you there!",
             "sharedPlan":{"operation":"accept","plan":"proposal"}}
            """);
        Engine.apply(state,mei,accept,"acceptance",new Engine.Effects());
        state=json.readValue(json.writeValueAsString(state),State.class);
        JdbcTemplate db=mock(JdbcTemplate.class);WorldStore store=new WorldStore(db,json);
        List<Engine.Started> starts=new ArrayList<>();List<Engine.Fact> facts=new ArrayList<>();
        for(int tick=0;tick<300&&!"completed".equals(state.socialPlans.get("proposal").status);tick++) {
            Engine.Effects effects=Engine.tick(state);starts.addAll(effects.starts);facts.addAll(effects.facts);store.effects(state,effects);
        }
        assertEquals("completed",state.socialPlans.get("proposal").status);
        assertEquals(Set.of("diego","mei"),state.socialPlans.get("proposal").finished);
        assertEquals(1,state.agents.get("diego").sharedExperienceCounts.get("mei"));
        assertEquals(1,state.agents.get("mei").sharedExperienceCounts.get("diego"));
        assertEquals("proposal",state.agents.get("diego").sharedExperiences.getFirst().plan());
        assertTrue(state.agents.get("diego").relationships.isEmpty(),"Experience evidence leaves relationship labels agent-authored");
        assertEquals(2,starts.stream().filter(s->s.command().action()==Contracts.ActionType.JOIN_PLAN).count());
        assertEquals(2,starts.stream().filter(s->s.command().action()==Contracts.ActionType.DO_ACTIVITY).count());
        assertEquals(2,facts.stream().filter(f->f.message().contains("finished 5 minutes of activity")).count());
        assertTrue(facts.stream().noneMatch(f->f.message().contains("found flowers")));
        for(Resident participant:state.agents.values()) {
            var search=participant.activityHistory.getLast();
            assertEquals("search",search.mode());
            assertEquals(5,search.activeMinutes());
            assertTrue(search.distance()>0,"A search must cover actual ground after reaching the meeting place");
            assertTrue(search.inspectedStops()>0,"Travel alone does not complete a search");
        }
        assertEquals(2,state.agents.values().stream().filter(a->a.mind.outcomes.stream()
            .anyMatch(o->o.action().equals("DO_ACTIVITY")&&o.status().equals("completed"))).count());
        verify(db,atLeast(4)).update(startsWith("INSERT INTO agent_actions"),any(Object[].class));
    }
}
