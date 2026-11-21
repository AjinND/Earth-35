package world.earth35.protocol;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import world.earth35.memory.MemoryService;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.*;
import world.earth35.world.State.*;

class AutonomyContextTest {
    private final ObjectMapper json=new ObjectMapper();
    private final WorldStore store=new WorldStore(mock(JdbcTemplate.class),json);
    private final byte[] providerKey=new byte[32];
    private final HostedAgentService runtime=spy(new HostedAgentService(store,mock(AgentService.class),mock(MemoryService.class),json,
        Base64.getEncoder().encodeToString(providerKey),"https://example.test/completions",mock(HttpClient.class)));

    private record SavedContext(State world,Map<String,List<MemoryService.Memory>> privateMemories) {}

    @AfterEach void close() {runtime.stop();}

    private Resident resident(State state,String id) {
        Resident resident=new Resident();resident.id=id;resident.name=id;resident.ownerId="owner-"+id;
        resident.home="alex".equals(id)?"apt-101":"apt-102";resident.location="park";
        Building park=state.buildings.get("park");resident.x=park.x;resident.z=park.z;
        resident.lastSeen=System.currentTimeMillis();state.agents.put(id,resident);return resident;
    }
    private AgentService.Observation observation(State state,Resident self) {
        Map<String,Boolean> available=new HashMap<>();state.agents.keySet().forEach(id->available.put(id,true));
        var nearby=state.agents.values().stream().filter(other->!other.id.equals(self.id)&&Social.perceivable(state,self,other)
            &&Math.hypot(self.x-other.x,self.z-other.z)<=20).toList();
        return new AgentService.Observation(state.minute,state.version,Engine.weather(state.minute),NightRoutine.timeOfDay(state.minute),
            DailyRoutines.sleepingTime(self,state.minute),DailyRoutines.nextWake(self,state.minute),self,
            nearby.stream().map(WorldStore::publicAgent).toList(),
            state.buildings.values().stream().filter(place->place.capacity>1||place.id.equals(self.home)).toList(),
            List.of(),List.of(),List.of(ActionType.values()),List.of(),AgentService.gatheringPlaces(state),null,
            Conversations.views(state,self,available),nearby.stream().map(other->new AgentService.Contact(other.id,true)).toList(),
            null,null,Social.situation(state,self,available));
    }
    private JsonNode context(State state,Resident self,List<MemoryService.Memory> memories) {
        return json.valueToTree(runtime.decisionContext(self.id,observation(state,self),memories));
    }

    @Test void authoredIdentityPrivateExperienceAndRememberedPreferencesSurviveRestart() throws Exception {
        State state=State.prototypeSeed();state.minute=4*1440+720;
        Resident self=resident(state,"alex"),other=resident(state,"sam");
        self.personality="I am reserved, curious and slow to trust.";self.goals="Understand the neighborhood at my own pace.";
        self.pending=new Pending();self.pending.type="READ_BOOK";
        for(int offset=5;offset>0;offset--)DailyLife.tick(self,state.minute-offset);
        self.pending=null;
        Cognition.action(self,state.minute-1,"read-streets","READ_BOOK","book-streets","completed","Read five minutes about walkable streets.");
        Cognition.action(self,state.minute,"empty-meal","EAT",null,"rejected","No owned meal was available.");
        Cognition.update(state,self,new Cognition.Update(new Cognition.IntentionDraft("Take a quiet break","Sit in the park",
            "I want time to absorb what I read","park",60),false,"Curious, but ready for quiet",
            "A short reading session suits me better than rushing to another task.","read-streets"));
        Cognition.perceive(state,self,Map.of(other.id,true));
        other.personality="OTHER_PRIVATE_PERSONALITY";other.goals="OTHER_PRIVATE_GOAL";
        Cognition.action(other,state.minute,"other-receipt","OTHER_PRIVATE_ACTIVITY",null,"completed","OTHER_PRIVATE_RESULT");
        Cognition.update(state,other,new Cognition.Update(new Cognition.IntentionDraft("OTHER_PRIVATE_INTENTION","Keep my own plans",
            "OTHER_PRIVATE_REASON","park",60),false,"OTHER_PRIVATE_FEELING","OTHER_PRIVATE_REFLECTION","other-receipt"));
        var preference=new MemoryService.Memory("own-preference","semantic","I prefer quiet park benches after reading.","[\"park\"]","[]",state.minute);
        var privateOther=new MemoryService.Memory("other-preference","semantic","OTHER_PRIVATE_PREFERENCE","[]","[]",state.minute);
        var snapshot=new SavedContext(state,Map.of(self.id,List.of(preference),other.id,List.of(privateOther)));
        JsonNode before=context(state,self,snapshot.privateMemories().get(self.id));
        SavedContext restored=json.readValue(json.writeValueAsString(snapshot),SavedContext.class);
        Resident savedSelf=restored.world().agents.get(self.id);
        JsonNode after=context(restored.world(),savedSelf,restored.privateMemories().get(self.id));

        assertEquals(self.personality,after.path("self").path("personality").asText());
        assertEquals(self.goals,after.path("self").path("goals").asText());
        assertEquals("Curious, but ready for quiet",after.path("cognition").path("feeling").asText());
        assertEquals("Take a quiet break",after.path("cognition").path("intention").path("focus").asText());
        assertEquals("read-streets",after.path("cognition").path("reflections").get(0).path("outcomeId").asText());
        assertEquals("A short reading session suits me better than rushing to another task.",
            after.path("cognition").path("reflections").get(0).path("lesson").asText());
        assertEquals(before.path("cognition"),after.path("cognition"));
        assertEquals(before.path("dailyLife").path("rhythm"),after.path("dailyLife").path("rhythm"));
        JsonNode day=after.path("dailyLife").path("rhythm").path("recentDays").get(0);
        assertEquals(4,after.path("dailyLife").path("day").asLong());
        assertEquals(4,day.path("day").asLong());assertEquals(5,day.path("activityMinutes").path("READ_BOOK").asInt());
        assertEquals(1,day.path("completedActions").path("READ_BOOK").asInt());
        assertFalse(day.path("completedActions").has("EAT"),"A rejected receipt must not become a completed activity");
        assertEquals("rejected",after.path("cognition").path("recentOutcomes").get(1).path("status").asText());
        assertEquals(preference.content(),after.path("relevantMemories").get(0).path("content").asText());
        assertEquals(1,after.path("nearby").size(),"The peer is physically visible while their private state stays private");
        assertFalse(after.toString().contains("OTHER_PRIVATE_"));
        assertFalse(after.path("self").has("mind"));assertNull(savedSelf.pending);
    }

    @Test void preferredNightAttentionLeavesTheResidentFreeToTalkWithoutANightPlan() throws Exception {
        State state=State.prototypeSeed();state.minute=1380;Resident self=resident(state,"alex"),other=resident(state,"sam");
        self.routine=new DailyRoutine();self.routine.sleepMinute=1320;self.routine.wakeMinute=420;
        String before=json.writeValueAsString(self);var observed=observation(state,self);
        assertTrue(observed.getNightReturnDue());
        String attention=observed.getAwareness().attention().stream().filter(value->value.contains("preferred sleep window")).findFirst().orElseThrow();
        assertTrue(attention.contains("Consider"));assertTrue(attention.contains("no declared night plan"));
        assertTrue(observed.getAwareness().nearbyPeople().stream().filter(person->person.id().equals(other.id)).findFirst().orElseThrow().canTalk());
        assertEquals(before,json.writeValueAsString(self),"Sleep context must not choose a routine, intention or movement");
        Command talk=new Command("evening-chat",ActionType.TALK,other.id,"I feel like staying a little longer.",null,null,null);
        assertSame(talk,NightRoutine.resolve(state,self,talk));
        assertNull(self.nightPlan);assertNull(self.pending);
    }

    @Test void aDelayedCommitmentStaysVisibleWithoutSuppressingAnIncomingConversation() {
        State state=State.prototypeSeed();state.minute=720;
        Resident self=resident(state,"alex"),partner=resident(state,"sam"),visitor=resident(state,"maya");
        SocialPlan plan=new SocialPlan();plan.id="coffee-plan";plan.proposer=self.id;plan.invited=partner.id;
        plan.destination="coffee";plan.activity="Catch up";plan.status="accepted";plan.meetingMinute=720;plan.expires=780;
        state.socialPlans.put(plan.id,plan);
        Conversations.speak(state,visitor,self,"Do you have time to chat?");
        Conversations.speak(state,self,visitor,"I have a coffee plan, but I can listen.");
        Conversations.speak(state,visitor,self,"Can I tell you about my morning?");state.minute+=10;
        var awareness=observation(state,self).getAwareness();
        assertEquals(plan.id,awareness.commitment().plan());assertTrue(awareness.commitment().returnDue());
        var opportunity=awareness.nearbyPeople().stream().filter(person->person.id().equals(visitor.id)).findFirst().orElseThrow();
        assertTrue(opportunity.canTalk());assertTrue(opportunity.reason().contains("other resident chooses"));
        assertEquals("accepted",plan.status);assertNull(self.pending);assertNull(partner.pending);
    }

    @Test void actualDecisionsSendAutonomyGuidanceAndRetainTheModelsChosenAction() throws Exception {
        State state=State.prototypeSeed();state.minute=720;Resident self=resident(state,"alex"),other=resident(state,"sam");
        self.personality="I like quiet conversation more than busy gatherings.";
        var response=json.createObjectNode();response.putArray("choices").addObject().putObject("message")
            .put("content","{\"action\":\"TALK\",\"target\":\"sam\",\"message\":\"Would you like some quiet company?\"}");
        doReturn(response).when(runtime).providerCall(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt());
        byte[] nonce=new byte[12];Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(providerKey,"AES"),new GCMParameterSpec(128,nonce));
        byte[] encrypted=cipher.doFinal("test-key".getBytes(StandardCharsets.UTF_8));
        String packed=Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length+encrypted.length).put(nonce).put(encrypted).array());
        var decision=runtime.decide(new HostedAgentService.Job(self.id,"nvidia","autonomy-test",packed,0,0,0,0),observation(state,self),List.of());
        var instructions=ArgumentCaptor.forClass(String.class);var facts=ArgumentCaptor.forClass(String.class);
        verify(runtime).providerCall(eq("nvidia"),eq("test-key"),eq("autonomy-test"),instructions.capture(),facts.capture(),eq(2048));
        assertTrue(instructions.getValue().contains(DecisionAwareness.BEHAVIOR_ADVICE));
        assertTrue(instructions.getValue().contains("Choose the next action yourself"));
        assertTrue(instructions.getValue().contains("There is no daily quota"));
        assertTrue(instructions.getValue().contains("advisory cues, not a conversation limit"));
        assertEquals(self.personality,json.readTree(facts.getValue()).path("self").path("personality").asText());
        assertEquals(ActionType.TALK,decision.command().action());assertEquals(other.id,decision.command().target());
        assertNull(self.pending);assertTrue(state.conversations.isEmpty(),"Requesting a decision must not execute or replace it");
    }
}
