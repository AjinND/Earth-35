package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import world.earth35.memory.MemoryService;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.*;

class HostedDecisionFeedbackTest {
    private final ObjectMapper json=new ObjectMapper();
    private final WorldStore store=spy(new WorldStore(mock(JdbcTemplate.class),json));
    private final AgentService agents=mock(AgentService.class);
    private final MemoryService memory=mock(MemoryService.class);
    private final HostedAgentService runtime=spy(new HostedAgentService(store,agents,memory,json,"","https://example.test/completions",mock(HttpClient.class)));
    private final State state=State.seed();
    private final State.Resident self=new State.Resident();
    HostedDecisionFeedbackTest() {
        self.id="alex";self.name="Alex";self.home="apt-101";self.location="park";
        self.x=30;self.z=30;self.offer="job-coffee";self.ownerId="owner";
        state.minute=720;state.agents.put(self.id,self);
    }
    @AfterEach void stop() {runtime.stop();}
    AgentService.Observation observation() {
        return new AgentService.Observation(720,1,"Clear","12:00",false,1800,self,List.of(),
            state.buildings.values().stream().filter(b->b.capacity>1||b.id.equals(self.home)).toList(),
            List.copyOf(state.jobs.values()),List.of(),List.of(ActionType.values()),List.of("park-bench"),AgentService.gatheringPlaces(state));
    }
    @Test void nextDecisionReceivesRejectedPurchaseAndItsPrerequisite() throws Exception {
        var rejected=new HostedAgentService.ActionFeedback("BUY","grocery","meal","rejected","Move to grocery first",719);
        doReturn(List.of(rejected)).when(store.db).query(contains("FROM agent_actions WHERE agent_id=?"),any(RowMapper.class),eq("alex"));
        var context=json.valueToTree(runtime.decisionContext("alex",observation(),List.of()));
        assertEquals("Move to grocery first",context.path("recentActions").path(0).path("result").asText());
        assertEquals("BUY",context.path("recentActions").path(0).path("action").asText());
        assertEquals("rejected",context.path("recentActions").path(0).path("status").asText());
        assertEquals("park",context.path("self").path("location").asText());
        verify(store.db).query(contains("LIMIT 5"),any(RowMapper.class),eq("alex"));
    }
    @Test void nextDecisionRetainsTheExchangeAndTravelAfterTheEventCursorAdvances() throws Exception {
        self.location="courtyard";self.x=54;self.z=-6;state.minute=1184;
        State.Resident other=new State.Resident();other.id="sam";other.name="Sam";other.location="courtyard";
        other.x=self.x;other.z=self.z;state.agents.put(other.id,other);
        State.Conversation chat=new State.Conversation();chat.a=self.id;chat.b=other.id;chat.closed=true;
        chat.lastSpeaker=other.id;chat.lastMinute=1096;
        chat.turns.add(new State.Turn(self.id,"Ready to head to the Lantern Courtyard?",1094,"bistro"));
        chat.turns.add(new State.Turn(other.id,"Ready! Let's head there together.",1096,"bistro"));
        chat.travel.add(new State.ConversationTravel(self.id,"walk-alex","bistro","courtyard",1097,1183L,"completed"));
        chat.travel.add(new State.ConversationTravel(other.id,"walk-sam","bistro","courtyard",1098,1184L,"completed"));
        state.conversations.put(Conversations.key(self.id,other.id),chat);
        doReturn(state).when(store).read(true);
        doReturn(List.of()).when(store).events(Long.MAX_VALUE,100,self.id);
        var observed=new AgentService(store).observe(self.id,Long.MAX_VALUE);
        var context=json.valueToTree(runtime.decisionContext(self.id,observed,List.of()));
        assertTrue(context.path("recentEvents").isEmpty());
        var thread=context.path("conversations").get(0);
        assertEquals("bistro",thread.path("turns").get(0).path("location").asText());
        assertTrue(thread.path("turns").get(1).path("message").asText().contains("together"));
        assertEquals(2,thread.path("travel").size());
        assertEquals(1184,thread.path("travel").get(1).path("arrivedMinute").asLong());
        assertEquals("completed",thread.path("travel").get(1).path("status").asText());
        assertEquals("courtyard",context.path("self").path("location").asText());
    }
    @Test void latestFarewellIsHighlightedAfterEarlierEventsAreConsumed() {
        State.Resident maya=new State.Resident();maya.id="maya";maya.name="Maya";maya.location=self.location;
        maya.x=self.x;maya.z=self.z;state.agents.put(maya.id,maya);
        State.Conversation chat=new State.Conversation();chat.a=self.id;chat.b=maya.id;
        chat.lastSpeaker=maya.id;chat.lastMinute=720;
        chat.turns.add(new State.Turn(maya.id,"Hello, nice to meet you!",710,"bistro"));
        chat.turns.add(new State.Turn(self.id,"Hi Maya! What brings you here?",715,"bistro"));
        chat.turns.add(new State.Turn(maya.id,"Thanks for the insights. I should head to the office. It was great chatting!",720,"bistro"));
        state.conversations.put(Conversations.key(self.id,maya.id),chat);
        doReturn(state).when(store).read(true);doReturn(List.of()).when(store).events(Long.MAX_VALUE,100,self.id);
        var observed=new AgentService(store).observe(self.id,Long.MAX_VALUE);
        var focus=json.valueToTree(runtime.decisionContext(self.id,observed,List.of())).path("conversationFocus").get(0);
        assertEquals("maya",focus.path("other").asText());
        assertTrue(focus.path("latestTurn").path("message").asText().contains("head to the office"));
        assertEquals("Hi Maya! What brings you here?",focus.path("lastOwnTurn").path("message").asText());
        assertEquals(focus,json.valueToTree(observed).path("conversationFocus").get(0));
    }
    @Test void learnedConceptsReachFutureHostedDecisionsAfterReturnAndRestart() throws Exception {
        self.location="library";self.x=state.buildings.get("library").x;self.z=state.buildings.get("library").z;
        Engine.apply(state,self,new Command("borrow",ActionType.BORROW_BOOK,"science-everyday",null,null,null,null),"borrow",new Engine.Effects());
        Engine.apply(state,self,new Command("read",ActionType.READ_BOOK,"science-everyday",null,null,30,null),"read",new Engine.Effects());
        for(int i=0;i<30;i++)Engine.tick(state);
        Engine.apply(state,self,new Command("return",ActionType.RETURN_BOOK,"science-everyday",null,null,null,null),"return",new Engine.Effects());
        State persisted=json.readValue(json.writeValueAsString(state),State.class);
        doReturn(persisted).when(store).read(true);doReturn(List.of()).when(store).events(Long.MAX_VALUE,100,self.id);
        var observed=new AgentService(store).observe(self.id,Long.MAX_VALUE);
        var context=json.valueToTree(runtime.decisionContext(self.id,observed,List.of()));
        assertTrue(context.path("recentEvents").isEmpty());assertTrue(context.path("self").path("bookLoans").isEmpty());
        var knowledge=context.path("self").path("knowledge").path("science");
        assertEquals(30,knowledge.path("studyMinutes").asInt());assertEquals("introductory",knowledge.path("level").asText());
        assertEquals(1,knowledge.path("concepts").size());assertTrue(knowledge.path("concepts").get(0).asText().contains("observation"));
        assertTrue(context.path("self").path("knowledge").path("gardening").isMissingNode());
        assertEquals(7,context.path("library").path("loanDays").asInt());assertTrue(context.path("library").path("catalog").size()>=100);
    }
    @Test void privateReasoningAndWorkReachTheOwnersHostedContextAfterRestart() throws Exception {
        var reasoning=new State.Reasoning("reason-alex","science","How can a neighborhood garden conserve water?",
            "Alex's private comparison of irrigation alternatives.",List.of("Observation records evidence."),710);
        var creation=new State.Creation("work-alex","science","product","Garden irrigation draft",
            "Alex's private product proposal with a test plan.",reasoning.id(),reasoning.concepts(),720);
        self.reasoning.put(reasoning.id(),reasoning);self.creations.put(creation.id(),creation);
        for(int i=0;i<5;i++) {
            var earlier=new State.Reasoning("reason-alex-old-"+i,"science","Earlier problem "+i,
                "Alex's earlier private analysis "+i,reasoning.concepts(),700+i);
            var draft=new State.Creation("work-alex-old-"+i,"science","research","Earlier draft "+i,
                "Alex's earlier private draft "+i,earlier.id(),earlier.concepts(),700+i);
            self.reasoning.put(earlier.id(),earlier);self.creations.put(draft.id(),draft);
        }
        State.Resident other=new State.Resident();other.id="maya";other.name="Maya";other.location=self.location;
        other.home="apt-102";other.x=self.x;other.z=self.z;
        var privateOther=new State.Reasoning("reason-maya","arts","Maya's undisclosed composition problem",
            "Maya's undisclosed composition analysis",List.of("Color conveys mood."),719);
        other.reasoning.put(privateOther.id(),privateOther);state.agents.put(other.id,other);
        State persisted=json.readValue(json.writeValueAsString(state),State.class);
        doReturn(persisted).when(store).read(true);doReturn(List.of()).when(store).events(Long.MAX_VALUE,100,self.id);
        var observed=new AgentService(store).observe(self.id,Long.MAX_VALUE);
        var context=json.valueToTree(runtime.decisionContext(self.id,observed,List.of()));
        assertEquals(6,observed.library().reasoning().size());assertEquals(6,observed.library().creations().size());
        assertFalse(context.path("self").has("reasoning"));assertFalse(context.path("self").has("creations"));
        assertEquals(5,context.path("library").path("reasoning").size());assertEquals(5,context.path("library").path("creations").size());
        assertEquals(reasoning.analysis(),context.path("library").path("reasoning").get(0).path("analysis").asText());
        assertEquals(creation.content(),context.path("library").path("creations").get(0).path("content").asText());
        assertEquals(reasoning.id(),context.path("library").path("reasoning").get(0).path("id").asText());
        assertEquals(creation.reasoningId(),context.path("library").path("creations").get(0).path("reasoningId").asText());
        assertFalse(context.toString().contains("Alex's earlier private analysis 0"));
        assertFalse(context.toString().contains("Alex's earlier private draft 0"));
        assertFalse(context.toString().contains(privateOther.analysis()));
        assertFalse(context.toString().contains(privateOther.problem()));
        assertFalse(json.valueToTree(WorldStore.publicAgent(persisted.agents.get(self.id))).has("reasoning"));
        assertFalse(json.valueToTree(WorldStore.publicAgent(persisted.agents.get(self.id))).has("creations"));
    }
    @Test void contextIncludesShopFactsOfferAndCoordinatesWithoutOtherPrivateHomes() {
        var context=json.valueToTree(runtime.decisionContext("alex",observation(),List.of()));
        assertEquals("job-coffee",context.path("self").path("offer").asText());
        assertEquals(30,context.path("self").path("x").asDouble());
        assertEquals("park-bench",context.path("objects").path(0).asText());
        assertEquals("park",context.path("seating").path("location").asText());
        assertEquals(4,context.path("seating").path("availableSeats").asInt());
        assertEquals(json.valueToTree(observation()).path("seating"),context.path("seating"));
        boolean grocery=false;
        for(var location:context.path("locations")) {
            assertNotEquals("apt-102",location.path("id").asText());
            if(location.path("id").asText().equals("grocery")) {
                grocery=true;assertEquals(1200,location.path("prices").path("meal").asLong());
                assertEquals(100,location.path("stock").path("meal").asInt());
                assertEquals(State.seed().buildings.get("grocery").x,location.path("x").asDouble());
            }
        }
        assertTrue(grocery);
    }
    @Test void projectEvidenceConsentAndActualBusinessFactsReachHostedDecisionsAfterRestart() throws Exception {
        self.interests.add("science");self.studyBoostUntil=840;self.weatherProtectionUntil=850;
        var project=new Projects.Project();project.id="project-alex";project.founder=self.id;
        project.title="Useful neighborhood lunch";project.subject="science";project.recipe="nourishment";
        project.location="market";project.problem="How can we relieve actual neighborhood hunger?";
        project.description="Prototype a nourishment product, then test it and offer manufactured stock.";
        project.members.add(self.id);project.businessEndorsements.add(self.id);
        for(int i=0;i<10;i++) {
            var finding=new Projects.ResearchFinding();finding.id="finding-"+i;finding.project=project.id;
            finding.subject="science";finding.method="observation";finding.lessonKey="science:nourishment:observation";
            finding.concept="Observe measurable needs before making a product.";finding.result="Measured evidence "+i;
            finding.minute=700+i;finding.evidence.put("hunger",40.0+i);project.research.put(self.id+":"+i,finding);
        }
        var invitation=new Projects.Invitation();invitation.id="invitation-alex";invitation.project=project.id;
        invitation.inviter=self.id;invitation.invited="maya";invitation.message="Research together; decide separately about business ownership.";
        var business=new Ventures.Business();business.id="business-alex";business.project=project.id;
        business.name="Useful lunch";business.location="market";business.productId="product-alex";business.jobId="job-business-alex";
        business.owners.add(self.id);business.stock=3;business.priceCents=1200;business.sales=2;business.revenueCents=2400;
        state.projects.put(project.id,project);state.projectInvitations.put(invitation.id,invitation);
        state.businesses.put(business.id,business);state.accounts.put(business.id,23000L);
        State persisted=json.readValue(json.writeValueAsString(state),State.class);
        doReturn(persisted).when(store).read(true);doReturn(List.of()).when(store).events(Long.MAX_VALUE,100,self.id);
        var observed=new AgentService(store).observe(self.id,Long.MAX_VALUE);
        var context=json.valueToTree(runtime.decisionContext(self.id,observed,List.of()));
        assertEquals("science",context.path("self").path("interests").get(0).asText());
        assertEquals(840,context.path("self").path("studyBoostUntil").asLong());
        assertEquals(850,context.path("self").path("weatherProtectionUntil").asLong());
        var innovations=context.path("innovations");
        assertEquals(8,innovations.path("projects").get(0).path("research").size());
        assertTrue(innovations.toString().contains("Measured evidence 9"));
        assertFalse(innovations.toString().contains("Measured evidence 0"));
        assertEquals("proposed",innovations.path("invitations").get(0).path("status").asText());
        assertEquals(3,innovations.path("businesses").get(0).path("stock").asInt());
        assertEquals(23000,innovations.path("businesses").get(0).path("balanceCents").asLong());
        assertTrue(innovations.path("problems").isEmpty());
        assertEquals(2,innovations.path("sharedConditions").size());
        assertEquals("community-needs",innovations.path("sharedConditions").get(0).path("kind").asText());
        assertEquals("weather",innovations.path("sharedConditions").get(1).path("kind").asText());
        var outsider=new State.Resident();outsider.id="customer";
        var otherView=json.valueToTree(Projects.view(persisted,outsider));
        assertTrue(otherView.path("projects").get(0).path("research").isEmpty());
        assertTrue(otherView.path("invitations").isEmpty());
        assertTrue(otherView.path("businesses").get(0).path("balanceCents").isNull());
        assertTrue(otherView.path("businesses").get(0).path("revenueCents").isNull());
    }
    @Test void rejectedActionDisplaysTheWorldReasonAndSchedulesAnotherDecision() {
        when(agents.observe(eq("alex"),anyLong())).thenReturn(observation());
        when(memory.recall(anyString(),any())).thenReturn(List.of());
        Command buy=new Command("buy",ActionType.BUY,"grocery",null,"meal",1,null);
        doReturn(new HostedAgentService.Decision(buy,null,null,27,null)).when(runtime).decide(any(),any(),anyList());
        when(agents.hostedAction("alex",buy,null)).thenReturn(new Receipt("action","rejected","Move to grocery first",720));
        runtime.run(new HostedAgentService.Job("alex","nvidia","test-model","unused",0,0,0,0));
        verify(store.db).update(contains("UPDATE hosted_agents SET"),eq("running"),
            eq("Action rejected: Move to grocery first. The agent will reconsider on its next decision."),
            eq(0L),eq(1),eq(27L),eq(0L),eq(1),eq(27L),eq(8),eq("alex"));
    }
    @Test void resumeWakesTheRuntimeAndPreservesAnAcceptedAction() {
        self.paused=true;State.Pending pending=new State.Pending();pending.type="MOVE";self.pending=pending;
        doReturn(state).when(store).read(true);
        new AgentService(store).pause("owner","alex",false);
        assertFalse(self.paused);assertSame(pending,self.pending);
        verify(store.db).update(eq("UPDATE hosted_agents SET status=?,last_error='',next_run_at=now() WHERE agent_id=? AND status<>'needs_attention'"),eq("ready"),eq("alex"));
    }
    @Test void invalidOutputRetriesThenRecoversWithoutResettingBudgets() {
        when(agents.observe(eq("alex"),anyLong())).thenReturn(observation());
        when(memory.recall(anyString(),any())).thenReturn(List.of());
        var failure=new HostedAgentService.ProviderFailure("The model omitted the action name.",false,73,true);
        Command look=new Command("look",ActionType.LOOK,null,null,null,null,null);
        doThrow(failure).doReturn(new HostedAgentService.Decision(look,null,null,27,null)).when(runtime).decide(any(),any(),anyList());
        when(agents.hostedAction("alex",look,null)).thenReturn(new Receipt("action","completed","Looked around",720));
        runtime.run(new HostedAgentService.Job("alex","nvidia","test-model","unused",0,0,4,100));
        verify(agents,never()).hostedAction(eq("alex"),any(),any());
        verify(store.db).update(contains("UPDATE hosted_agents SET"),eq("backoff"),eq(failure.getMessage()),
            eq(0L),eq(5),eq(173L),eq(0L),eq(1),eq(73L),eq(8),eq("alex"));
        runtime.run(new HostedAgentService.Job("alex","nvidia","test-model","unused",0,0,5,173,failure.getMessage()));
        verify(agents).hostedAction("alex",look,null);
        verify(store.db).update(contains("UPDATE hosted_agents SET"),eq("running"),eq(""),
            eq(0L),eq(6),eq(200L),eq(0L),eq(1),eq(27L),eq(8),eq("alex"));
    }
    @Test void decisionsContinueAtAndAboveTheFormerCallAndTokenLimitsAndTrackUsage() {
        when(agents.observe(eq("alex"),anyLong())).thenReturn(observation());
        when(memory.recall(anyString(),any())).thenReturn(List.of());
        Command look=new Command("look",ActionType.LOOK,null,null,null,null,null);
        doReturn(new HostedAgentService.Decision(look,null,null,75_000,null)).when(runtime).decide(any(),any(),anyList());
        when(agents.hostedAction("alex",look,null)).thenReturn(new Receipt("action","completed","Looked around",720));
        runtime.run(new HostedAgentService.Job("alex","nvidia","test-model","unused",0,0,100,50_000));
        verify(store.db).update(contains("UPDATE hosted_agents SET"),eq("running"),eq(""),
            eq(0L),eq(101),eq(125_000L),eq(0L),eq(1),eq(75_000L),eq(8),eq("alex"));
        runtime.run(new HostedAgentService.Job("alex","nvidia","test-model","unused",0,0,199,125_000,"Daily provider call limit reached; retries next simulated day"));
        verify(store.db).update(contains("UPDATE hosted_agents SET"),eq("running"),eq(""),
            eq(0L),eq(200),eq(200_000L),eq(0L),eq(1),eq(75_000L),eq(8),eq("alex"));
        verify(runtime,times(2)).decide(any(),any(),anyList());
        verify(agents,times(2)).hostedAction("alex",look,null);
    }
    @Test void repeatedBadOutputBacksOffAndStillHonorsTheDailyBudget() {
        when(agents.observe(eq("alex"),anyLong())).thenReturn(observation());
        when(memory.recall(anyString(),any())).thenReturn(List.of());
        var failure=new HostedAgentService.ProviderFailure("Invalid output",false,73,true);
        doThrow(failure).when(runtime).decide(any(),any(),anyList());
        runtime.run(new HostedAgentService.Job("alex","nvidia","test-model","unused",0,0,199,100,"previous failure"));
        verify(store.db).update(contains("UPDATE hosted_agents SET"),eq("backoff"),eq("Invalid output"),
            eq(0L),eq(200),eq(173L),eq(0L),eq(1),eq(73L),eq(60),eq("alex"));
        runtime.run(new HostedAgentService.Job("alex","nvidia","test-model","unused",0,0,200,173,"Invalid output"));
        verify(runtime,times(1)).decide(any(),any(),anyList());
        verify(store.db).update(contains("UPDATE hosted_agents SET"),eq("budget_paused"),anyString(),
            eq(0L),eq(200),eq(173L),eq(0L),eq(0),eq(0L),eq(30),eq("alex"));
    }
    @Test void invalidOptionalPlanAndRoutineCannotPreventPrimaryAction() {
        doReturn(state).when(store).read(true);
        var receipt=new AgentService(store).hostedAction("alex",new Command("look",ActionType.LOOK,null,null,null,null,null),
            new NightPlanInput("",999999),new RoutineInput(0,0,"invalid"));
        assertEquals("completed",receipt.status());
        assertTrue(receipt.result().contains("Routine change ignored"));assertTrue(receipt.result().contains("Night plan ignored"));
        assertNull(self.nightPlan);
    }
    @Test void recoverySendsCorrectiveInstructionsAndMoreOutputSpace() throws Exception {
        byte[] key=new byte[32],nonce=new byte[12];
        var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),new javax.crypto.spec.GCMParameterSpec(128,nonce));
        byte[] encrypted=cipher.doFinal("test-key".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String packed=java.util.Base64.getEncoder().encodeToString(java.nio.ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array());
        var connected=spy(new HostedAgentService(store,agents,memory,json,java.util.Base64.getEncoder().encodeToString(key),"https://example.test/completions",mock(HttpClient.class)));
        try {
            var response=json.readTree("{\"choices\":[{\"message\":{\"content\":\"{\\\"action\\\":\\\"LOOK\\\"}\"}}]}");
            doReturn(response).when(connected).providerCall(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt());
            connected.decide(new HostedAgentService.Job("alex","nvidia","model",packed,0,0,0,0),observation(),List.of());
            verify(connected).providerCall(eq("nvidia"),eq("test-key"),eq("model"),anyString(),anyString(),eq(2048));
            connected.decide(new HostedAgentService.Job("alex","nvidia","model",packed,0,0,1,73,"Invalid output"),observation(),List.of());
            verify(connected).providerCall(eq("nvidia"),eq("test-key"),eq("model"),contains("Your previous decision failed"),anyString(),eq(4096));
        } finally {connected.stop();}
    }
    @Test void familiarEncounterWakesTheModelDuringTravelAndProvidesPrivateContext() {
        self.pending=new State.Pending();self.pending.type="MOVE";self.pending.id="journey";
        self.relationships.put("maya","close friend");
        var social=new Social.Situation(List.of(new Social.Encounter("maya","Maya","close friend",true,4,true,"closed",true)),List.of(),true);
        var original=observation();
        var encountered=new AgentService.Observation(original.worldMinute(),original.version(),original.weather(),original.timeOfDay(),original.night(),original.nextMorning(),self,
            original.nearby(),original.locations(),original.jobs(),original.events(),original.actions(),original.objects(),original.gatheringPlaces(),null,List.of(),List.of(),null,
            new Travel.Situation(false,false,false,0,null,null,List.of()),social);
        assertTrue(HostedAgentService.decisionDue(encountered));
        var context=json.valueToTree(runtime.decisionContext("alex",encountered,List.of()));
        assertEquals("close friend",context.path("self").path("relationships").path("maya").asText());
        assertEquals("maya",context.path("social").path("encounters").path(0).path("other").asText());
        Command talk=new Command("hello",ActionType.TALK,"maya","Nice to see you",null,null,null);
        when(agents.observe(eq("alex"),anyLong())).thenReturn(encountered);
        when(memory.recall(anyString(),any())).thenReturn(List.of());
        doReturn(new HostedAgentService.Decision(talk,null,null,27,null)).when(runtime).decide(any(),any(),anyList());
        when(agents.hostedAction("alex",talk,null)).thenReturn(new Receipt("hello","completed","Spoke to Maya",720));
        runtime.run(new HostedAgentService.Job("alex","nvidia","test-model","unused",0,0,0,0));
        verify(agents).hostedAction("alex",talk,null);
        self.pending.type="WORK";assertFalse(HostedAgentService.decisionDue(encountered));
        self.pending.type="MOVE";self.paused=true;assertFalse(HostedAgentService.decisionDue(encountered));
    }
}
