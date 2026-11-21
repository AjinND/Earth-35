package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.*;

class NightReturnProtocolTest {
    private final WorldStore store=spy(new WorldStore(mock(JdbcTemplate.class),new ObjectMapper()));
    private final State state=State.prototypeSeed();
    private final State.Resident self=new State.Resident(),other=new State.Resident();
    private final AgentService agents=new AgentService(store);

    NightReturnProtocolTest() {
        state.minute=1200;
        self.id="alex";self.name="Alex";self.home="apt-101";self.location="park";self.energy=100;
        other.id="maya";other.name="Maya";other.home="apt-102";other.location="park";
        self.x=other.x=state.buildings.get("park").x;self.z=other.z=state.buildings.get("park").z;
        self.lastSeen=other.lastSeen=System.currentTimeMillis();state.agents.put(self.id,self);state.agents.put(other.id,other);
        doReturn(state).when(store).read(anyBoolean());doReturn(List.of()).when(store).events(anyLong(),anyInt(),anyString());
    }
    private Command command(String request,ActionType action,String target,String message) {
        return new Command(request,action,target,message,null,null,null);
    }

    @Test void unplannedNightConversationRemainsAResidentChoice() {
        String words="Want to keep chatting?";
        Receipt spoken=agents.action(self.id,command("late-chat",ActionType.TALK,other.id,words));
        assertEquals("completed",spoken.status());assertFalse(self.bedtimeRoutine);
        assertNull(self.pending);assertEquals("park",self.location);
        State.Conversation chat=state.conversations.get(Conversations.key(self.id,other.id));
        assertEquals(1,chat.turns.size());assertEquals(words,chat.turns.getFirst().message());
        assertEquals("completed",self.mind.outcomes.getLast().status());
        assertNull(agents.nightRoutine(self.id,"go-home"));assertNull(self.pending);
    }

    @Test void retryingAChosenNightConversationReturnsItsReceiptWithoutRepeatingSpeech() {
        Command talk=command("retry-chat",ActionType.TALK,other.id,"How is your night going?");
        Receipt spoken=agents.action(self.id,talk);assertEquals("completed",spoken.status());
        when(store.db.queryForList(eq("SELECT * FROM agent_actions WHERE agent_id=? AND request_id=?"),eq(self.id),eq(talk.requestId())))
            .thenReturn(List.of(Map.of("id",spoken.id(),"status",spoken.status(),"result",spoken.result(),"world_minute",spoken.worldMinute())));
        when(store.db.queryForObject(contains("jsonb_strip_nulls"),eq(Boolean.class),anyString(),eq(spoken.id()))).thenReturn(true);
        assertEquals(spoken,agents.action(self.id,talk));
        assertEquals(1,self.mind.outcomes.size());assertEquals(1,state.conversations.get(Conversations.key(self.id,other.id)).turns.size());
        verify(store,times(1)).save(state);
    }

    @Test void legacyNightEndpointReturnsHistoricalSnackReceiptsWithoutEatingAgain() {
        self.location=self.home;self.energy=40;self.hunger=100;int meals=self.inventory.get("meal");
        Cognition.Mind originalMind=self.mind;
        assertNull(agents.nightRoutine(self.id,"new-snack"));assertEquals(meals,self.inventory.get("meal"));
        Receipt snack=new Receipt("old-snack","completed","Consumed meal",1200);
        when(store.db.queryForList(contains("routine_item"),eq(self.id),eq("night-snack"))).thenReturn(List.of(Map.of(
            "id",snack.id(),"status",snack.status(),"result",snack.result(),"world_minute",snack.worldMinute(),
            "routine_action","EAT","routine_item","meal")));
        assertEquals(snack,agents.nightRoutine(self.id,"night-snack"));
        assertEquals(meals,self.inventory.get("meal"));assertSame(originalMind,self.mind);
        assertTrue(self.mind.outcomes.isEmpty());assertNull(self.pending);
        verify(store,never()).save(state);
    }

    @Test void authoredClosingDoesNotAutomaticallyDepartButAChosenJourneyStillStarts() {
        state.minute=1199;
        assertEquals("completed",agents.action(self.id,command("hello",ActionType.TALK,other.id,"Did you enjoy the garden today?")).status());
        assertEquals("completed",agents.action(other.id,command("answer",ActionType.TALK,self.id,"Yes, the flowers looked wonderful.")).status());
        state.minute=1200;assertNull(agents.nightRoutine(self.id,"no-generated-goodbye"));
        String farewell="It's getting late, so I'm heading home. See you tomorrow! Take care.";
        Receipt closing=agents.action(self.id,command("goodbye",ActionType.END_CHAT,null,farewell));
        assertEquals("completed",closing.status());assertFalse(self.bedtimeRoutine);
        State.Conversation chat=state.conversations.get(Conversations.key(self.id,other.id));
        assertTrue(chat.closed);assertEquals(3,chat.turns.size());assertEquals(farewell,chat.turns.getLast().message());
        assertNull(agents.nightRoutine(self.id,"after-closing"));assertNull(self.pending);
        assertEquals("accepted",agents.action(self.id,command("chosen-home",ActionType.MOVE,self.home,null)).status());
        assertEquals("MOVE",self.pending.type);assertEquals(self.home,self.pending.target);assertEquals(3,chat.turns.size());
    }

    @Test void optionalOutdoorObservationAndRestNeedNoNightPlanOrJustification() {
        assertEquals("completed",agents.action(self.id,command("look",ActionType.LOOK,null,null)).status());
        assertEquals("accepted",agents.action(self.id,command("rest",ActionType.REST,null,null)).status());
        assertEquals("REST",self.pending.type);assertEquals("park",self.location);
        assertNull(self.nightPlan);assertFalse(self.bedtimeRoutine);
    }

    @Test void specificNightPlansAndAcceptedCommitmentsKeepChosenActivitiesAvailable() {
        self.nightPlan=new State.NightPlan("Watch the meteor shower with Maya",1300);
        assertEquals("completed",agents.action(self.id,command("planned-chat",ActionType.TALK,other.id,"The meteor shower should start soon.")).status());
        assertFalse(self.bedtimeRoutine);assertNull(agents.nightRoutine(self.id,"planned"));
        self.nightPlan=null;Conversations.end(state,self);
        State.SocialPlan p=new State.SocialPlan();p.id="meeting";p.proposer=self.id;p.invited=other.id;p.status="accepted";
        p.destination="park";p.activity="Observe the stars";p.meetingMinute=1250;p.expires=1310;state.socialPlans.put(p.id,p);
        assertEquals("completed",agents.action(self.id,command("agreed-chat",ActionType.TALK,other.id,"We agreed to watch the stars together.")).status());
        assertFalse(self.bedtimeRoutine);assertNull(agents.nightRoutine(self.id,"committed"));
    }

    @Test void legacyBedtimeFlagsDoNotAddRestrictionsToAnAlreadyChosenJourney() {
        assertEquals("accepted",agents.action(self.id,command("home-trip",ActionType.MOVE,self.home,null)).status());
        self.bedtimeRoutine=true;
        State.Pending journey=self.pending;List<double[]> route=new ArrayList<>(journey.route);
        assertEquals("completed",agents.action(self.id,command("notice",ActionType.LOOK,null,null)).status());
        assertSame(journey,self.pending);assertEquals(route,self.pending.route);
        assertTrue(state.conversations.isEmpty());
        assertEquals("completed",agents.action(self.id,command("keep-going",ActionType.CONTINUE_TRAVEL,null,null)).status());
        assertSame(journey,self.pending);
    }

    @Test void privateObservationExposesAnAdvisorySleepCueWithoutCreatingAnAction() {
        AgentService.Observation observation=agents.observe(self.id,0);
        assertTrue(observation.getNightReturnDue());
        self.nightPlan=new State.NightPlan("An evening visit",1300);
        assertFalse(agents.observe(self.id,0).getNightReturnDue());
        assertNull(self.pending);
        self.nightPlan=null;self.location=self.home;
        assertFalse(agents.observe(self.id,0).getNightReturnDue());
        self.location="park";self.home="unavailable-home";
        assertFalse(agents.observe(self.id,0).getNightReturnDue());
    }
}
