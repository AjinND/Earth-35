package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import world.earth35.memory.MemoryService;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.*;
import world.earth35.world.State.*;

class HostedHumanActivityTest {
    @ParameterizedTest @ValueSource(strings={"WORK","READ_BOOK","RESEARCH_PROJECT","BUILD_PROJECT","PRODUCE_PRODUCT","BUILD_CONSTRUCTION","PLACE_OBJECT","DO_ACTIVITY"})
    void anAwakeActivityReviewDispatchesTheModelsOwnChoiceWithoutInventingABreakOrReplacingItsTask(String type) {
        State state=State.prototypeSeed();state.minute=720;Resident self=new Resident();self.id="alex";self.name="Alex";self.home="apt-101";self.location="office";
        Pending pending=new Pending();pending.id="actual-task";pending.type=type;pending.target="office";pending.until=760;pending.reviewAfter=720;self.pending=pending;
        var observation=new AgentService.Observation(720,1,"Clear","12:00",false,1800,self,List.of(),List.copyOf(state.buildings.values()),List.of(),List.of(),List.of(),List.of(),List.of());
        assertTrue(HostedAgentService.decisionDue(observation));assertTrue(observation.getAwareness().actionScope().contains("CONTINUE_ACTIVITY"));
        AgentService agents=mock(AgentService.class);MemoryService memory=mock(MemoryService.class);ObjectMapper json=new ObjectMapper();
        WorldStore store=new WorldStore(mock(JdbcTemplate.class),json);
        HostedAgentService runtime=spy(new HostedAgentService(store,agents,memory,json,"","https://example.test",mock(HttpClient.class)));
        try {
            when(agents.observe(eq(self.id),anyLong())).thenReturn(observation);when(memory.recall(eq(self.id),any())).thenReturn(List.of());
            Command chosen=new Command("chosen",ActionType.CONTINUE_ACTIVITY,pending.id,null,null,null,null);
            doReturn(new HostedAgentService.Decision(chosen,null,null,20,null)).when(runtime).decide(any(),any(),anyList());
            when(agents.hostedAction(self.id,chosen,null)).thenReturn(new Receipt("chosen","completed","Continuing",720));
            runtime.run(new HostedAgentService.Job(self.id,"nvidia","test-model","unused",0,0,0,0));
            verify(agents).hostedAction(eq(self.id),same(chosen),isNull());verify(agents,never()).nightRoutine(anyString());
            assertSame(pending,self.pending);assertFalse(pending.activityPaused);assertEquals(type,pending.type);
        } finally {runtime.stop();}
    }
}
