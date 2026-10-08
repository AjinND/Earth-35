package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.*;

class AgentEmploymentTest {
    private final WorldStore store=spy(new WorldStore(mock(JdbcTemplate.class),new ObjectMapper()));
    private final AgentService agents=new AgentService(store);
    private final State state=State.seed();
    private final State.Resident self=new State.Resident();
    private static final String LOOKUP="SELECT * FROM agent_actions WHERE agent_id=? AND request_id=?";

    AgentEmploymentTest() {
        self.id="alex";self.name="Alex";self.home="apt-101";self.location="office";self.job="job-office";
        self.occupation="City analyst";self.x=state.buildings.get("office").x;self.z=state.buildings.get("office").z;
        state.minute=600;state.agents.put(self.id,self);doReturn(state).when(store).read(true);
        doNothing().when(store).save(any(State.class));
    }
    private Command quit(String request) {return new Command(request,ActionType.QUIT_JOB,null,null,null,null,null);}
    private Map<String,Object> row(Receipt receipt) {
        return Map.of("id",receipt.id(),"status",receipt.status(),"result",receipt.result(),"world_minute",receipt.worldMinute());
    }

    @Test void replayReturnsTheOriginalReceiptAndCannotQuitASubsequentlyAcceptedJob() {
        Command command=quit("quit-once");Receipt first=agents.action(self.id,command);
        assertEquals("completed",first.status());assertEquals("",self.job);
        self.job="job-studio";self.occupation="Designer";
        doReturn(List.of(row(first))).when(store.db).queryForList(LOOKUP,self.id,command.requestId());
        String encoded=store.encode(command);
        doReturn(true).when(store.db).queryForObject("SELECT jsonb_strip_nulls(command)=jsonb_strip_nulls(?::jsonb) FROM agent_actions WHERE id=?",Boolean.class,encoded,first.id());
        assertEquals(first,agents.action(self.id,command));
        assertEquals("job-studio",self.job);assertEquals("Designer",self.occupation);
        verify(store,times(1)).effects(eq(state),any(Engine.Effects.class));verify(store,times(1)).save(state);
    }

    @Test void activeShiftCancellationAndItsPaymentsArePersistedOnlyOnce() {
        Engine.apply(state,self,new Command("work",ActionType.WORK,null,null,null,null,null),"work-action",new Engine.Effects());
        for(int i=0;i<30;i++)Engine.tick(state);
        long money=self.money;Command command=quit("quit-shift");Receipt first=agents.action(self.id,command);
        assertEquals("completed",first.status());assertEquals(money+1000,self.money);assertNull(self.pending);
        verify(store.db).update("UPDATE agent_actions SET status=?,result=? WHERE id=?", "cancelled",
            "Resident quit their job after 30 minutes; earned wages of 1000 cents settled and unused reservation returned to employer","work-action");
        doReturn(List.of(row(first))).when(store.db).queryForList(LOOKUP,self.id,command.requestId());
        String encoded=store.encode(command);
        doReturn(true).when(store.db).queryForObject("SELECT jsonb_strip_nulls(command)=jsonb_strip_nulls(?::jsonb) FROM agent_actions WHERE id=?",Boolean.class,encoded,first.id());
        assertEquals(first,agents.action(self.id,command));assertEquals(money+1000,self.money);
        verify(store,times(1)).effects(eq(state),any(Engine.Effects.class));
    }

    @Test void aReusedRequestIdWithDifferentFieldsRemainsAConflict() {
        Receipt first=agents.action(self.id,quit("same-id"));
        Command different=new Command("same-id",ActionType.QUIT_JOB,"job-office",null,null,null,null);
        doReturn(List.of(row(first))).when(store.db).queryForList(LOOKUP,self.id,different.requestId());
        String encoded=store.encode(different);
        doReturn(false).when(store.db).queryForObject("SELECT jsonb_strip_nulls(command)=jsonb_strip_nulls(?::jsonb) FROM agent_actions WHERE id=?",Boolean.class,encoded,first.id());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->agents.action(self.id,different)).getStatusCode().value());
        verify(store,times(1)).effects(eq(state),any(Engine.Effects.class));
    }

    @Test void legacyCommandPayloadCanBeRetriedWithNewOptionalFieldsOmitted() throws Exception {
        var json=new ObjectMapper();
        Command decoded=json.readValue("{\"requestId\":\"legacy\",\"action\":\"QUIT_JOB\",\"target\":null,\"message\":null,\"item\":null,\"quantity\":null,\"amount\":null}",Command.class);
        assertNull(decoded.loanYears());assertNull(decoded.laborMode());
        Receipt original=new Receipt("legacy-action","completed","Original result",599);
        doReturn(List.of(row(original))).when(store.db).queryForList(LOOKUP,self.id,decoded.requestId());
        String encoded=store.encode(decoded);
        assertTrue(json.readTree(encoded).path("loanYears").isNull());
        doReturn(true).when(store.db).queryForObject("SELECT jsonb_strip_nulls(command)=jsonb_strip_nulls(?::jsonb) FROM agent_actions WHERE id=?",Boolean.class,encoded,original.id());
        assertEquals(original,agents.action(self.id,decoded));
        assertEquals("job-office",self.job);
        verify(store,never()).effects(any(),any());
        verify(store.db).queryForObject("SELECT jsonb_strip_nulls(command)=jsonb_strip_nulls(?::jsonb) FROM agent_actions WHERE id=?",Boolean.class,encoded,original.id());
    }

    @Test void unemploymentProducesARejectedReceiptRatherThanAnAutomaticCareerChoice() {
        self.job="";self.occupation="Unemployed";
        Receipt rejected=agents.action(self.id,quit("no-job"));
        assertEquals("rejected",rejected.status());assertEquals("You have no current job to quit",rejected.result());
        assertEquals("",self.job);assertTrue(state.businesses.isEmpty());assertNull(self.pending);
    }
}
