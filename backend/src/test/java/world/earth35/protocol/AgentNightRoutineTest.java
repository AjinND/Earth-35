package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.*;

class AgentNightRoutineTest {
    private final WorldStore store=spy(new WorldStore(mock(JdbcTemplate.class),new ObjectMapper()));
    private final State state=State.seed();
    private final State.Resident resident=new State.Resident();
    private final AgentService agents=new AgentService(store);
    AgentNightRoutineTest() {
        resident.id="alex";resident.name="Alex";resident.home="apt-101";resident.location="park";
        resident.x=30;resident.z=30;resident.energy=45;state.minute=1200;state.agents.put(resident.id,resident);
        doReturn(state).when(store).read(true);
    }
    @Test void plansHaveAFutureExpiryWithinTwelveHoursAndCanBeCancelled() {
        for(NightPlanInput plan:new NightPlanInput[]{new NightPlanInput("",1260),new NightPlanInput("Shift",1200),new NightPlanInput("Shift",1921)})
            assertThrows(ResponseStatusException.class,()->agents.planNight("alex",plan));
        agents.planNight("alex",new NightPlanInput("Late night outing",1860));
        assertEquals(1860,resident.nightPlan.until);
        agents.planNight("alex",new NightPlanInput("Night shift",1500));
        assertEquals(1500,resident.nightPlan.until);assertEquals("Night shift",resident.nightPlan.reason);
        agents.planNight("alex",new NightPlanInput("",0));assertNull(resident.nightPlan);
    }
    @Test void pausedAndBusyResidentsCannotAcquireANewPlan() {
        resident.paused=true;
        assertThrows(ResponseStatusException.class,()->agents.planNight("alex",new NightPlanInput("Shift",1500)));
        resident.paused=false;resident.pending=new State.Pending();
        assertThrows(ResponseStatusException.class,()->agents.planNight("alex",new NightPlanInput("Shift",1500)));
    }
    @Test void fallbackLeavesPlannedBusyAndDaytimeResidentsUntouched() {
        resident.nightPlan=new State.NightPlan("Visit a friend",1500);assertNull(agents.nightRoutine("alex"));
        resident.nightPlan=null;resident.pending=new State.Pending();assertNull(agents.nightRoutine("alex"));
        resident.pending=null;state.minute=720;assertNull(agents.nightRoutine("alex"));
        verify(store,never()).save(any());
    }
    @Test void plansSurvivePersistingTheWorldAndOldWorldsLoadWithoutAPlan() throws Exception {
        ObjectMapper json=new ObjectMapper();
        resident.nightPlan=new State.NightPlan("Visit a friend",1500);
        State restored=json.readValue(json.writeValueAsString(state),State.class);
        assertEquals("Visit a friend",restored.agents.get("alex").nightPlan.reason);
        assertEquals(1500,restored.agents.get("alex").nightPlan.until);
        assertNull(json.readValue("{\"name\":\"Existing resident\"}",State.Resident.class).nightPlan);
    }
    @Test void hostedIdleChoicesRecordRealMovementAndThenSleepActions() {
        Command look=new Command("idle",ActionType.LOOK,null,null,null,null,null);
        Receipt home=agents.hostedAction("alex",look,null);
        assertEquals("accepted",home.status());assertEquals("MOVE",resident.pending.type);
        assertTrue(resident.bedtimeRoutine);
        assertEquals(resident.home,resident.pending.target);assertEquals("road",resident.location);
        while(resident.pending!=null) Engine.tick(state);
        Receipt sleep=agents.nightRoutine("alex");
        assertEquals("accepted",sleep.status());assertEquals("SLEEP",resident.pending.type);
        assertEquals(1800,resident.pending.until);
        assertNull(agents.nightRoutine("alex"));
    }
    @Test void aHostedNightPlanIsPersistedBeforeItsActivityIsResolved() {
        Command look=new Command("planned-wait",ActionType.LOOK,null,null,null,null,null);
        Receipt waiting=agents.hostedAction("alex",look,new NightPlanInput("Meet a friend at the park",1500));
        assertEquals("completed",waiting.status());assertEquals("park",resident.location);
        assertNull(resident.pending);assertNotNull(resident.nightPlan);
        assertFalse(resident.bedtimeRoutine);
        assertNull(agents.nightRoutine("alex"));
        assertEquals("accepted",agents.hostedAction("alex",new Command("cancel",ActionType.LOOK,null,null,null,null,null),new NightPlanInput("",0)).status());
        assertNull(resident.nightPlan);assertEquals("MOVE",resident.pending.type);
    }
    @Test void retryingANightRoutineReturnsTheOriginalReceiptDuringMovement() {
        resident.pending=new State.Pending();
        when(store.db.queryForList(contains("routine_action"),eq("alex"),eq("retry"))).thenReturn(java.util.List.of(java.util.Map.of(
            "id","home-action","status","accepted","result","Action accepted","world_minute",1200L,"routine_action","MOVE","routine_target",resident.home)));
        assertEquals("home-action",agents.nightRoutine("alex","retry").id());
        verify(store,never()).save(any());
    }
    @Test void residentCanChooseTheirOwnRoutineAndCannotChangeItWhileBusy() {
        agents.planRoutine("alex",new RoutineInput(330,1290,"jogging"));
        assertEquals(330,resident.routine.wakeMinute);assertEquals("jogging",resident.routine.morningActivity);
        assertThrows(ResponseStatusException.class,()->agents.planRoutine("alex",new RoutineInput(330,300,"jogging")));
        resident.pending=new State.Pending();resident.pending.type="SLEEP";
        assertThrows(ResponseStatusException.class,()->agents.planRoutine("alex",new RoutineInput(420,1380,"reading")));
        assertEquals(330,resident.routine.wakeMinute);
    }
    @Test void hostedRoutineChoiceChangesOnlyThatResidentBeforeResolvingTheAction() {
        state.minute=1290;
        Receipt sleep=agents.hostedAction("alex",new Command("sleep-choice",ActionType.LOOK,null,null,null,null,null),null,new RoutineInput(330,1290,"jogging"));
        assertEquals("accepted",sleep.status());assertEquals(330,resident.routine.wakeMinute);
        assertEquals("MOVE",resident.pending.type);assertEquals(resident.home,resident.pending.target);
    }
}
