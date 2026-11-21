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

class CognitionProtocolTest {
    @Test void modelCanReturnAnIntentionWithItsActionAndMalformedOptionalInputDoesNotLoseTheAction() throws Exception {
        ObjectMapper json=new ObjectMapper();
        var response=json.createObjectNode();
        response.putArray("choices").addObject().putObject("message").put("content", """
            {"action":"MOVE","target":"library","cognition":{"intention":{"focus":"Learn something useful","nextStep":"Visit the library","reason":"I enjoy reading","target":"library","reviewAfterMinutes":60}}}
            """);
        var decision=HostedDecisionParser.parse(json,response);
        assertEquals(ActionType.MOVE,decision.command().action());assertEquals("Learn something useful",decision.cognition().intention().focus());
        ((com.fasterxml.jackson.databind.node.ObjectNode)response.path("choices").get(0).path("message")).put("content","{\"action\":\"LOOK\",\"cognition\":{\"intention\":12}}");
        var malformed=HostedDecisionParser.parse(json,response);assertEquals(ActionType.LOOK,malformed.command().action());assertNull(malformed.cognition());
    }
    @Test void observePersistsPrivatePerceptionAndRuntimeReceivesItWithoutLeakingOtherMinds() throws Exception {
        var json=new ObjectMapper();var store=spy(new WorldStore(mock(JdbcTemplate.class),json));State s=State.prototypeSeed();s.minute=720;
        var a=new State.Resident();a.id="self";a.ownerId="owner";a.name="Alex";a.home="apt-101";a.location="park";s.agents.put(a.id,a);
        var b=new State.Resident();b.id="other";b.name="Sam";b.home="apt-102";b.location="park";b.mind.feeling="Private unshared concern";s.agents.put(b.id,b);
        doReturn(s).when(store).read(anyBoolean());doReturn(List.of()).when(store).events(anyLong(),anyInt(),anyString());
        DailyLife.completed(a,719,"READ_BOOK");DailyLife.completed(b,719,"PRIVATE_OTHER_ACTIVITY");
        var workday=new WorkPayroll.Workday();workday.id="own-pay";workday.job="job-office";workday.building="office";
        workday.payAtMinute=1020;workday.workedMinutes=60;workday.earnedCents=2000;a.workPayroll.put(workday.id,workday);
        var agents=new AgentService(store);var obs=agents.observe(a.id,0);
        assertEquals("park",obs.getCognition().surroundings().location());verify(store).save(s);
        var runtime=new HostedAgentService(store,agents,mock(MemoryService.class),json,"","https://example.test",mock(HttpClient.class));
        try {
            var context=json.valueToTree(runtime.decisionContext(a.id,obs,List.of()));
            assertEquals("park",context.path("cognition").path("surroundings").path("location").asText());
            assertEquals(1,context.path("dailyLife").path("rhythm").path("recentDays").get(0).path("completedActions").path("READ_BOOK").asInt());
            assertEquals(context.path("dailyLife"),json.valueToTree(obs).path("dailyLife"));
            assertFalse(context.toString().contains("PRIVATE_OTHER_ACTIVITY"));
            assertFalse(context.path("self").has("mind"));
            assertEquals(2000,context.path("payroll").get(0).path("earnedCents").asLong());
            assertEquals(1020,context.path("payroll").get(0).path("payAtMinute").asLong());
            assertEquals(context.path("payroll"),json.valueToTree(obs).path("payroll"));
            assertFalse(context.toString().contains(b.mind.feeling));
            assertFalse(json.writeValueAsString(WorldStore.publicAgent(a)).contains("noticedChanges"));
            assertFalse(json.writeValueAsString(WorldStore.publicAgent(b)).contains(b.mind.feeling));
        } finally {runtime.stop();}
        var invalid=new Cognition.Update(new Cognition.IntentionDraft("Aim","Step","Reason","apt-102",60),false,null,null,null);
        var receipt=agents.hostedAction(a.id,new Command("look",ActionType.LOOK,null,null,null,null,null),null,null,invalid);
        assertEquals("completed",receipt.status());assertTrue(receipt.result().contains("Cognition update ignored"));assertNull(a.mind.intention);
        assertEquals("LOOK",a.mind.outcomes.getLast().action());
    }
}
