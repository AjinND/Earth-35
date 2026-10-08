package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import world.earth35.memory.MemoryService;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.*;

class HostedNightRoutineTest {
    private final AgentService agents=mock(AgentService.class);
    private final MemoryService memory=mock(MemoryService.class);
    private final HttpClient http=mock(HttpClient.class);
    private final WorldStore store=new WorldStore(mock(JdbcTemplate.class),new ObjectMapper());
    private final HostedAgentService runtime=spy(new HostedAgentService(store,agents,memory,new ObjectMapper(),"","https://example.test/completions",http));
    private final State.Resident self=new State.Resident();
    HostedNightRoutineTest() {
        self.id="alex";self.name="Alex";self.home="apt-101";self.location="park";
        when(agents.observe(eq("alex"),anyLong())).thenAnswer(call->new AgentService.Observation(1200,1,"Clear","20:00",true,1800,self,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of()));
        when(memory.recall(anyString(),any())).thenReturn(List.of());
    }
    @AfterEach void stop() {runtime.stop();}
    HostedAgentService.Job job(int calls) {return new HostedAgentService.Job("alex","nvidia","test-model","unused",0,0,calls,0);}
    @Test void providerTimeoutAttemptsTheNightRoutineWithoutAnotherProviderCall() {
        doThrow(new HostedAgentService.ProviderFailure("Model timed out",false)).when(runtime).decide(any(),any(),anyList());
        runtime.run(job(0));
        verify(agents).nightRoutine("alex");verify(agents,never()).hostedAction(any(),any(),any());
        verifyNoInteractions(http);
    }
    @Test void exhaustedInferenceBudgetStillAllowsTheNightRoutine() {
        runtime.run(job(200));
        verify(agents).nightRoutine("alex");verify(runtime,never()).decide(any(),any(),anyList());
        verifyNoInteractions(memory,http);
    }
    @Test void activeAndPausedResidentsAreLeftAlone() {
        self.pending=new State.Pending();runtime.run(job(0));
        self.pending=null;self.paused=true;runtime.run(job(0));
        verify(agents,never()).nightRoutine(any());verify(runtime,never()).decide(any(),any(),anyList());
        verifyNoInteractions(memory,http);
    }
    @Test void aMovingResidentCanReconsiderWhenWeatherChanges() {
        self.pending=new State.Pending();self.pending.type="MOVE";self.pending.target="office";
        var travel=new Travel.Situation(true,false,false,4,null,null,List.of());
        when(agents.observe(eq("alex"),anyLong())).thenReturn(new AgentService.Observation(600,1,"Rain","10:00",false,1800,self,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),null,List.of(),List.of(),null,travel));
        Command run=new Command("rain",ActionType.RUN,"coffee",null,"north-south-first",null,null);
        doReturn(new HostedAgentService.Decision(run,null,null,27,null)).when(runtime).decide(any(),any(),anyList());
        when(agents.hostedAction("alex",run,null)).thenReturn(new Receipt("new-trip","accepted","Action accepted",600));
        runtime.run(job(0));verify(agents).hostedAction("alex",run,null);verify(runtime).decide(any(),any(),anyList());
    }
    @Test void reachingHomeContinuesTheBedtimeRoutineWithoutModelInference() {
        self.location=self.home;self.bedtimeRoutine=true;
        when(agents.nightRoutine("alex")).thenReturn(new Receipt("sleep","accepted","Action accepted",1200));
        runtime.run(job(0));
        verify(agents).nightRoutine("alex");verify(runtime,never()).decide(any(),any(),anyList());
        verifyNoInteractions(memory,http);
    }
    @Test void modelChosenNightPlansArePassedToTheValidatedActionService() {
        Command look=new Command("plan",ActionType.LOOK,null,null,null,null,null);
        NightPlanInput plan=new NightPlanInput("A visit with a friend",1500);
        doReturn(new HostedAgentService.Decision(look,null,null,27,plan)).when(runtime).decide(any(),any(),anyList());
        when(agents.hostedAction("alex",look,plan)).thenReturn(new Receipt("action","completed","Observation refreshed",1200));
        runtime.run(job(0));
        verify(agents).hostedAction("alex",look,plan);verify(agents,never()).nightRoutine(any());
    }
}
