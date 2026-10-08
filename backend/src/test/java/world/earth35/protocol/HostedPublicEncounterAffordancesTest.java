package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import world.earth35.memory.MemoryService;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.*;

class HostedPublicEncounterAffordancesTest {
    @ParameterizedTest @ValueSource(strings={"CALL_OUT","ACKNOWLEDGE","DECLINE_CALL"})
    void restingListenerReceivesActualCallsAndOnlyTheModelsChosenActionRuns(String selected) throws Exception {
        var json=new ObjectMapper();
        var store=spy(new WorldStore(mock(JdbcTemplate.class),json));
        var agents=mock(AgentService.class);
        var memory=mock(MemoryService.class);
        State state=State.prototypeSeed();state.minute=720;
        var park=state.buildings.get("park");
        var self=new State.Resident();self.id="alex";self.name="Alex";self.ownerId="owner";
        self.home="apt-101";self.location="park";self.x=park.x;self.z=park.z;self.lastSeen=state.minute;
        var rest=new State.Pending();rest.id="chosen-rest";rest.type="REST";rest.until=state.minute+30;self.pending=rest;
        var caller=new State.Resident();caller.id="sam";caller.name="Sam";caller.ownerId="other-owner";
        caller.home="apt-102";caller.location="park";caller.x=park.x+5;caller.z=park.z;caller.lastSeen=state.minute;
        state.agents.put(self.id,self);state.agents.put(caller.id,caller);
        PublicEncounters.callOut(state,caller,self.id,"Hello! Do you know where the market is?","heard-call",new Engine.Effects());
        doReturn(state).when(store).read(true);
        doReturn(List.of()).when(store).events(anyLong(),anyInt(),eq(self.id));
        var observation=new AgentService(store).observe(self.id,0);
        when(agents.observe(self.id,0)).thenReturn(observation);
        when(memory.recall(eq(self.id),any())).thenReturn(List.of());
        when(agents.hostedAction(eq(self.id),any(Command.class),isNull())).thenAnswer(call->{
            Command command=call.getArgument(1);
            return new Receipt(command.requestId(),"completed","Chosen action received",state.minute);
        });
        byte[] key=new byte[32],nonce=new byte[12];
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
        byte[] encrypted=cipher.doFinal("test-key".getBytes(StandardCharsets.UTF_8));
        String packed=Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length+encrypted.length).put(nonce).put(encrypted).array());
        var runtime=spy(new HostedAgentService(store,agents,memory,json,Base64.getEncoder().encodeToString(key),
            "https://example.test/completions",mock(HttpClient.class)));
        try {
            doAnswer(call->{
                String instructions=call.getArgument(3);
                int allowedStart=instructions.indexOf("Allowed actions: ");
                assertTrue(allowedStart>=0);
                String allowed=instructions.substring(allowedStart+"Allowed actions: ".length()).split("\\.",2)[0];
                for(String action:List.of("CALL_OUT","ACKNOWLEDGE","DECLINE_CALL"))
                    assertTrue(Arrays.asList(allowed.split(", ")).contains(action),action+" must be model-selectable");
                var context=json.readTree((String)call.getArgument(4));
                assertEquals(json.readTree(json.writeValueAsString(observation.social().calls())),context.path("social").path("calls"));
                var heard=context.path("social").path("calls").get(0);
                assertEquals("heard-call",heard.path("id").asText());
                assertEquals(caller.id,heard.path("caller").asText());
                assertEquals(self.id,heard.path("listener").asText());
                assertEquals("Hello! Do you know where the market is?",heard.path("message").asText());
                assertEquals("pending",heard.path("status").asText());
                assertEquals(state.minute+10,heard.path("expires").asLong());
                assertEquals("REST",context.path("self").path("pending").path("type").asText());
                assertEquals("chosen-rest",context.path("self").path("pending").path("id").asText());
                assertTrue(context.path("conversations").isEmpty());
                assertTrue(context.path("relevantMemories").isEmpty());
                var command=json.createObjectNode().put("action",selected).put("target",caller.id);
                if("CALL_OUT".equals(selected))command.put("message","Hello, Sam!");
                else command.putNull("message");
                var response=json.createObjectNode();
                response.putArray("choices").addObject().putObject("message").put("content",command.toString());
                response.putObject("usage").put("total_tokens",30);
                return response;
            }).when(runtime).providerCall(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt());
            runtime.run(new HostedAgentService.Job(self.id,"nvidia","test-model",packed,0,0,0,0));
            verify(runtime,times(1)).providerCall(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt());
            verify(agents,times(1)).hostedAction(eq(self.id),argThat(command->command.action()==ActionType.valueOf(selected)
                &&caller.id.equals(command.target())&&("CALL_OUT".equals(selected)?"Hello, Sam!".equals(command.message()):command.message()==null)),isNull());
            verify(agents,times(1)).hostedAction(eq(self.id),any(Command.class),isNull());
            verify(agents,never()).nightRoutine(anyString());
            verify(memory,never()).write(anyString(),any(MemoryInput.class));
            verify(memory,never()).summarizeConversation(anyString(),anyLong(),any(MemoryService.SummaryInput.class));
            assertSame(rest,self.pending,"Presenting a call must retain the resident's chosen rest");
            assertNull(rest.socialWith);assertNull(self.facingHeading);
            assertTrue(state.conversations.isEmpty());assertTrue(self.relationships.isEmpty());
            assertEquals(1,state.publicCalls.size());assertEquals("pending",state.publicCalls.get("heard-call").status);
        } finally {runtime.stop();}
    }
}
