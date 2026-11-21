package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import world.earth35.memory.MemoryService;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.*;
import world.earth35.world.State.*;

class HostedCommitmentTest {
    private final ObjectMapper json=new ObjectMapper();
    private final WorldStore store=spy(new WorldStore(mock(JdbcTemplate.class),json));
    private final AgentService agents=mock(AgentService.class);
    private final MemoryService memory=mock(MemoryService.class);
    private final HostedAgentService runtime=spy(new HostedAgentService(store,agents,memory,json,"","https://example.test/completions",mock(HttpClient.class)));
    private final State state=State.prototypeSeed();
    private final Resident self=resident("alex","Alex","park");
    private final Resident partner=resident("sam","Sam","park");
    private final Resident interrupter=resident("maya","Maya","park");

    HostedCommitmentTest() {
        state.minute=720;
        doReturn(state).when(store).read(anyBoolean());
        doReturn(List.of()).when(store).events(anyLong(),anyInt(),anyString());
    }
    @AfterEach void stop() {runtime.stop();}
    private Resident resident(String id,String name,String location) {
        Resident a=new Resident();a.id=id;a.name=name;a.ownerId="owner";a.home="apt-101";a.location=location;
        Building b=state.buildings.get(location);a.x=b.x;a.z=b.z;a.lastSeen=System.currentTimeMillis();state.agents.put(id,a);return a;
    }
    private SocialPlan plan(String id,String status,long meeting) {
        SocialPlan plan=new SocialPlan();plan.id=id;plan.proposer=self.id;plan.invited=partner.id;
        plan.destination="courtyard";plan.activity="continue our walk together";plan.status=status;
        plan.meetingMinute=meeting;plan.expires=meeting+60;state.socialPlans.put(id,plan);return plan;
    }
    private AgentService.Observation observe() {return new AgentService(store).observe(self.id,Long.MAX_VALUE);}
    private void interruptedConversation() {
        state.minute=710;Conversations.speak(state,self,partner,"Shall we continue this at the courtyard?");
        state.minute=711;Conversations.speak(state,partner,self,"Yes, let's go together.");
        Conversations.end(state,self);
        state.minute=722;Conversations.speak(state,interrupter,self,"Hello Alex, how was your morning?");
        state.minute=723;Conversations.speak(state,self,interrupter,"Good, thanks! I have agreed to go for a walk with Sam.");
        state.minute=724;Conversations.speak(state,interrupter,self,"Have you seen the new books at the library?");
    }
    @Test void originalAgreementStaysActionableAheadOfTheNewerConversation() throws Exception {
        plan("outing","accepted",735);interruptedConversation();
        Cognition.update(state,self,new Cognition.Update(new Cognition.IntentionDraft("Read later","Visit the library","I enjoy books","library",60),false,null,null,null));
        var observation=observe();
        var context=json.valueToTree(runtime.decisionContext(self.id,observation,List.of()));
        assertEquals(interrupter.id,context.path("conversationFocus").get(0).path("other").asText());
        var commitment=context.path("awareness").path("commitment");
        assertEquals("outing",commitment.path("plan").asText());assertEquals(partner.id,commitment.path("other").asText());
        assertEquals("courtyard",commitment.path("destination").asText());assertEquals(735,commitment.path("meetingMinute").asLong());
        assertTrue(commitment.path("due").asBoolean());assertEquals("END_CHAT",commitment.path("nextAction").asText());
        assertEquals(commitment,context.path("social").path("commitments").get(0));
        assertEquals(commitment,json.valueToTree(observation).path("awareness").path("commitment"));
        List<String> attention=observation.getAwareness().attention();
        int agreement=-1,incoming=-1;
        for(int i=0;i<attention.size();i++) {
            if(attention.get(i).contains("plan outing"))agreement=i;
            if(attention.get(i).contains("nearby resident has spoken"))incoming=i;
        }
        assertTrue(agreement>=0&&incoming>agreement,"The original agreement needs attention before the newest thread");
        assertTrue(context.path("recentEvents").isEmpty(),"A consumed event cursor must not erase the commitment");
        assertEquals("Read later",context.path("cognition").path("intention").path("focus").asText());
    }
    @Test void proposalsAndIntentionalCancellationDoNotLeaveACommitmentOrInventASpokenReply() {
        SocialPlan agreed=plan("outing","proposed",735);
        assertNull(observe().getAwareness().commitment());
        agreed.status="accepted";interruptedConversation();
        assertNotNull(observe().getAwareness().commitment());
        Conversation chat=state.conversations.get(Conversations.key(self.id,interrupter.id));int turns=chat.turns.size();
        Engine.apply(state,self,new Command("cancel",ActionType.CANCEL_PLAN,agreed.id,null,null,null,null),"cancel",new Engine.Effects());
        var cancelled=observe();
        assertEquals("cancelled",agreed.status);assertNull(cancelled.getAwareness().commitment());
        assertTrue(cancelled.social().commitments().isEmpty());assertEquals(turns,chat.turns.size());
        assertTrue(cancelled.getAwareness().attention().stream().noneMatch(a->a.contains("plan outing")));
    }
    @Test void aDelayedCommitmentDoesNotHideTheChoiceToTalkWithOthers() {
        plan("outing","accepted",735);interruptedConversation();
        state.minute=726;
        var awareness=observe().getAwareness();assertTrue(awareness.commitment().returnDue());
        var interrupted=awareness.nearbyPeople().stream().filter(p->p.id().equals(interrupter.id)).findFirst().orElseThrow();
        assertTrue(interrupted.canTalk());assertTrue(interrupted.reason().contains("TALK is possible"));
        assertTrue(awareness.attention().stream().anyMatch(note->note.contains("conversation has delayed the agreement")));
        assertTrue(awareness.nearbyPeople().stream().filter(p->p.id().equals(partner.id)).findFirst().orElseThrow().canTalk());
    }
    @Test void dueCommitmentDefersSummaryWorkAfterClosingTheInterruption() {
        plan("outing","accepted",735);state.agents.remove(interrupter.id);
        partner.location="coffee";partner.x=state.buildings.get("coffee").x;partner.z=state.buildings.get("coffee").z;
        Social.reviewed(state,self);
        var observation=observe();assertTrue(HostedAgentService.socialDecisionDue(observation));
        assertEquals("JOIN_PLAN",observation.getAwareness().commitment().nextAction());
        when(agents.observe(eq(self.id),anyLong())).thenReturn(observation);
        when(memory.recall(anyString(),any())).thenReturn(List.of());
        Command join=new Command("join",ActionType.JOIN_PLAN,"outing",null,null,null,null);
        doReturn(new HostedAgentService.Decision(join,null,null,20,null)).when(runtime).decide(any(),any(),anyList());
        when(agents.hostedAction(self.id,join,null)).thenReturn(new Receipt("join","accepted","Walking to the meeting",state.minute));
        runtime.run(new HostedAgentService.Job(self.id,"nvidia","test-model","unused",0,0,0,0));
        verify(memory,never()).pendingConversation(self.id);verify(agents).hostedAction(self.id,join,null);
        var commitment=json.valueToTree(observation.getAwareness().commitment());
        assertFalse(commitment.has("otherLocation"));assertFalse(commitment.has("otherProgress"));assertFalse(commitment.has("otherReady"));
    }
    @Test void resumingTheMatchingJourneyDoesNotRepeatedlyRequestDecisionsForTheSpentInterruptionBudget() {
        plan("outing","accepted",735);interruptedConversation();state.minute=726;
        Engine.apply(state,self,new Command("goodbye",ActionType.END_CHAT,interrupter.id,"I should catch up with Sam. Take care!",null,null,null),"goodbye",new Engine.Effects());
        Engine.apply(state,self,new Command("join",ActionType.JOIN_PLAN,"outing",null,null,null,null),"join",new Engine.Effects());
        Cognition.perceive(state,self,Map.of());
        Cognition.action(self,state.minute,"join","JOIN_PLAN","outing","accepted","Walking to the meeting");
        var observation=observe();
        assertTrue(observation.getAwareness().commitment().returnDue());assertNull(self.pending.socialWith);
        assertEquals("CONTINUE_TRAVEL",observation.getAwareness().commitment().nextAction());
        assertTrue(HostedAgentService.socialDecisionDue(observation),"Summary work remains secondary to the live agreement");
        assertFalse(HostedAgentService.decisionDue(observation),"The matching journey continues without another immediate inference");
    }
    @Test void restingAtTheMeetingPlaceDoesNotWakeRepeatedlyOrDeferSummaryWork() {
        plan("outing","accepted",720);
        Building destination=state.buildings.get("courtyard");self.location=destination.id;self.x=destination.x;self.z=destination.z;
        self.pending=new Pending();self.pending.type="REST";self.pending.id="wait-at-meeting";self.pending.until=780;
        Social.reviewed(state,self);state.minute+=15;
        Cognition.perceive(state,self,Map.of());Cognition.action(self,state.minute,self.pending.id,"REST",self.location,"accepted","Resting while waiting");
        var observation=observe();assertEquals("WAIT",observation.getAwareness().commitment().nextAction());
        assertFalse(observation.social().reconsider());assertFalse(HostedAgentService.socialDecisionDue(observation));
        assertFalse(HostedAgentService.decisionDue(observation));
    }
    @Test void anActualIncomingTurnWakesAHostedResidentWaitingAtTheMeeting() {
        SocialPlan accepted=plan("outing","accepted",750);
        accepted.destination="park";
        Engine.apply(state,self,new Command("wait",ActionType.WAIT_PLAN,accepted.id,null,null,null,null),"wait",new Engine.Effects());
        Social.reviewed(state,self);
        Cognition.perceive(state,self,Map.of());Cognition.action(self,state.minute,"wait","WAIT_PLAN",accepted.id,"accepted","Waiting for the meeting");
        assertFalse(HostedAgentService.decisionDue(observe()));
        Conversations.speak(state,partner,self,"Shall we keep our plan and begin when it is time?");
        var observation=observe();
        assertEquals("PLAN_WAIT",observation.self().pending.type);
        assertTrue(observation.social().reconsider());
        assertTrue(HostedAgentService.decisionDue(observation),"The waiter can reply and close a real chat before activity starts");
    }
    @Test void reviewingADueCommitmentDuringRestKeepsDepartureFeasibleAndThrottlesTheNextReview() {
        plan("outing","accepted",735);
        self.pending=new Pending();self.pending.type="REST";self.pending.id="current-rest";self.pending.until=780;
        Social.reviewed(state,self);state.minute+=15;
        Cognition.perceive(state,self,Map.of());Cognition.action(self,state.minute,self.pending.id,"REST",self.location,"accepted","Still resting");
        var due=observe();assertEquals("JOIN_PLAN",due.getAwareness().commitment().nextAction());
        assertTrue(HostedAgentService.decisionDue(due));
        assertTrue(due.getAwareness().attention().stream().anyMatch(a->a.contains("keep resting or choose STOP_ACTIVITY")));
        assertTrue(DecisionAwareness.COMMITMENT_ADVICE.contains("STOP_ACTIVITY before JOIN_PLAN"));
        assertTrue(DecisionAwareness.COMMITMENT_ADVICE.contains("WAIT_BUS may be stopped before boarding"));
        Social.reviewed(state,self);
        assertFalse(HostedAgentService.decisionDue(observe()),"The resident keeps resting after reviewing the same due commitment");
    }
    @Test void aFuturePrimaryAgreementKeepsALaterMatchingTripFromTakingDecisionPriority() {
        SocialPlan earlier=plan("earlier","accepted",770);earlier.destination="library";
        SocialPlan later=plan("later","accepted",780);
        Engine.apply(state,self,new Command("trip",ActionType.JOIN_PLAN,later.id,null,null,null,null),"trip",new Engine.Effects());
        Cognition.perceive(state,self,Map.of());Cognition.action(self,state.minute,"trip","JOIN_PLAN",later.id,"accepted","Walking to the later meeting");
        var observation=observe();assertEquals(2,observation.social().commitments().size());
        assertEquals(earlier.id,observation.getAwareness().commitment().plan());assertFalse(observation.getAwareness().commitment().due());
        assertTrue(observation.social().commitments().stream().anyMatch(c->c.priority()==2&&c.due()));
        assertFalse(HostedAgentService.socialDecisionDue(observation));assertFalse(HostedAgentService.decisionDue(observation));
        self.pending.type="REST";
        assertFalse(HostedAgentService.decisionDue(observation),"A lower-priority snapshot must not repeatedly interrupt rest");
    }
    @Test void hostedPromptPreservesTheAgreementWhileKeepingCancellationAModelChoice() throws Exception {
        plan("outing","accepted",735);interruptedConversation();var observation=observe();
        byte[] key=new byte[32],nonce=new byte[12];Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        byte[] encrypted=cipher.doFinal("test-key".getBytes(StandardCharsets.UTF_8));
        String packed=Base64.getEncoder().encodeToString(ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array());
        var connected=spy(new HostedAgentService(store,agents,memory,json,Base64.getEncoder().encodeToString(key),"https://example.test/completions",mock(HttpClient.class)));
        try {
            doAnswer(call->{
                String instructions=call.getArgument(3);
                assertTrue(instructions.contains(DecisionAwareness.COMMITMENT_ADVICE));
                assertTrue(instructions.contains("PROPOSE_PLAN"));assertTrue(instructions.contains("ACCEPT_PLAN"));
                assertTrue(instructions.contains("END_CHAT needs no farewell reply"));assertTrue(instructions.contains("CANCEL_PLAN with target the plan ID stops it"));
                assertTrue(instructions.contains("advisory cues, not a conversation limit"));
                var context=json.readTree((String)call.getArgument(4));assertEquals("outing",context.path("awareness").path("commitment").path("plan").asText());
                var response=json.createObjectNode();
                response.putArray("choices").addObject().putObject("message").put("content","{\"action\":\"CANCEL_PLAN\",\"target\":\"outing\"}");
                return response;
            }).when(connected).providerCall(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt());
            var decision=connected.decide(new HostedAgentService.Job(self.id,"nvidia","model",packed,0,0,0,0),observation,List.of());
            assertEquals(ActionType.CANCEL_PLAN,decision.command().action());assertEquals("outing",decision.command().target());
            assertEquals("accepted",state.socialPlans.get("outing").status,"Displaying the commitment must not choose an action for the resident");
        } finally {connected.stop();}
    }
}
