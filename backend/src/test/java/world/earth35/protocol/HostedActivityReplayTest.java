package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import world.earth35.memory.MemoryService;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.*;
import world.earth35.world.State.*;

class HostedActivityReplayTest {
    private final State state=State.prototypeSeed();
    private final Resident self=new Resident();
    HostedActivityReplayTest() {
        state.minute=720;self.id="mei";self.name="Mei Lin";self.home="apt-101";self.location="park";
        self.activityHistory.add(new ActivityExecution.Outcome("search-1","Search for native plants","park","search",15,3,18,719));
        Cognition.action(self,719,"search-1","DO_ACTIVITY","park","completed","Inspected three stops; no verified discovery");
    }
    private AgentService.Observation observation() {
        return new AgentService.Observation(state.minute,1,"Clear","12:00",false,1800,self,List.of(),
            List.copyOf(state.buildings.values()),List.of(),List.of(),List.of(),List.of(),List.of());
    }
    @Test void completedActivityProvidesEvidenceWithoutRequiringPermissionToContinue() {
        String result=HostedAgentService.activityReplayFeedback(observation(),List.of());
        assertNotNull(result);assertTrue(result.contains("search-1"));
        assertTrue(result.contains("15 active minutes, 3 inspected stops and 18.0 units travelled"));
        assertTrue(result.contains("Repetition remains your choice"));assertTrue(result.contains("no lesson is required"));
        assertTrue(result.contains("does not establish a discovery"));
    }
    @Test void feedbackDoesNotMutateIntentionsOrInventAReflection() {
        var intention=new Cognition.Intention("Find a quiet place","Continue my walk","I enjoy the garden",null,700,760);
        self.mind.intention=intention;
        HostedAgentService.activityReplayFeedback(observation(),List.of());
        assertSame(intention,self.mind.intention);assertTrue(self.mind.reflections.isEmpty());
        assertNull(self.pending);assertEquals(1,self.mind.outcomes.size());
    }
    @Test void aCompletedActivityNeverBecomesARuntimeGateForTheNextChosenActivity() {
        AgentService agents=mock(AgentService.class);MemoryService memory=mock(MemoryService.class);
        ObjectMapper json=new ObjectMapper();WorldStore store=new WorldStore(mock(JdbcTemplate.class),json);
        HostedAgentService runtime=spy(new HostedAgentService(store,agents,memory,json,"","https://example.test/completions",mock(HttpClient.class)));
        try {
            when(agents.observe(eq(self.id),anyLong())).thenReturn(observation());when(memory.recall(eq(self.id),any())).thenReturn(List.of());
            Command repeated=new Command("continue-search",ActionType.DO_ACTIVITY,"park","Search for native plants","search",15,null);
            doReturn(new HostedAgentService.Decision(repeated,null,null,20,null)).when(runtime).decide(any(),any(),anyList());
            when(agents.hostedAction(self.id,repeated,null)).thenReturn(new Receipt("next-block","accepted","Activity started",720));
            runtime.run(new HostedAgentService.Job(self.id,"nvidia","test-model","unused",0,0,0,0));
            verify(agents).hostedAction(eq(self.id),same(repeated),isNull());
            verify(agents,never()).nightRoutine(anyString());
            assertTrue(self.mind.reflections.isEmpty());
        } finally {runtime.stop();}
    }
    @Test void activityFeedbackIsVisibleBeforeTheModelMakesItsChoice() {
        ObjectMapper json=new ObjectMapper();WorldStore store=new WorldStore(mock(JdbcTemplate.class),json);
        HostedAgentService runtime=new HostedAgentService(store,mock(AgentService.class),mock(MemoryService.class),json,"","https://example.test/completions",mock(HttpClient.class));
        try {
            Object feedback=runtime.decisionContext(self.id,observation(),List.of()).get("decisionFeedback");
            assertTrue(feedback.toString().contains("search-1"));assertTrue(feedback.toString().contains("no lesson is required"));
        } finally {runtime.stop();}
    }
    @Test void staleOrInProgressActivitiesDoNotProduceRecentCompletionFeedback() {
        state.minute=2160;assertNull(HostedAgentService.activityReplayFeedback(observation(),List.of()));
        state.minute=720;self.pending=new Pending();self.pending.type="DO_ACTIVITY";
        assertNull(HostedAgentService.activityReplayFeedback(observation(),List.of()));
    }
    @Test void legacyReceiptsRemainFactualContextWithoutClaimingMeasuredExecution() {
        self.activityHistory.clear();
        var look=new HostedAgentService.ActionFeedback("LOOK",null,null,"completed","Observed",720,"look",null);
        var previous=new HostedAgentService.ActionFeedback("DO_ACTIVITY","park",null,"completed","DO_ACTIVITY completed",704,"legacy-search","Search for native plants");
        String result=HostedAgentService.activityReplayFeedback(observation(),List.of(look,previous));
        assertNotNull(result);assertTrue(result.contains("legacy-search"));assertTrue(result.contains("no measured physical progress"));
    }
    @Test void awarenessPresentsPhysicalEvidenceWithoutClaimingAnyDiscovery() {
        self.activityHistory.add(new ActivityExecution.Outcome("search-2","Look for native plants","park","search",15,4,22,720));
        String attention=String.join(" ",observation().getAwareness().attention());
        assertTrue(attention.contains("15 active minutes, 4 inspected stops and 22.0 units travelled"));
        assertTrue(attention.contains("2 search block(s)"));assertTrue(attention.contains("no discovery"));
    }
}
