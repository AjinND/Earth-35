package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

class HostedCareerAffordancesTest {
    @ParameterizedTest @ValueSource(strings={"LOOK","QUIT_JOB","APPLY_JOB","START_BUSINESS","START_CONSTRUCTION","BUILD_CONSTRUCTION","REPAY_LOAN","PLACE_OBJECT","MOVE_OBJECT"})
    void runtimePresentsFactsAndSendsOnlyTheModelsChosenAction(String selected) throws Exception {
        var json=new ObjectMapper();
        var store=spy(new WorldStore(mock(JdbcTemplate.class),json));
        var agents=mock(AgentService.class);
        var memory=mock(MemoryService.class);
        State state=State.seed();state.minute=720;
        var self=new State.Resident();self.id="alex";self.name="Alex";self.ownerId="owner";
        self.home="apt-101";self.location="park";self.job="job-coffee";self.occupation="Barista";
        state.agents.put(self.id,self);
        Construction.initializeCitySites(state,new Engine.Effects());
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
                var context=json.readTree((String)call.getArgument(4));
                assertTrue(instructions.contains("independently choose whether and when"));
                assertTrue(instructions.contains("not required steps or a prescribed sequence"));
                for(String action:List.of("QUIT_JOB","START_BUSINESS","START_CONSTRUCTION","BUILD_CONSTRUCTION","REPAY_LOAN","PLACE_OBJECT","MOVE_OBJECT"))
                    assertTrue(instructions.contains(action));
                assertTrue(instructions.contains("only the funding shortfall"));
                assertTrue(instructions.contains("No worker is assigned automatically"));
                assertTrue(instructions.contains("accepted travel routes"));
                assertEquals("job-coffee",context.path("self").path("job").asText());
                assertEquals(self.id,context.path("self").path("id").asText());
                assertFalse(context.path("livelihood").path("jobs").isEmpty());
                assertEquals(2,context.path("livelihood").path("smallBusinesses").size());
                assertFalse(context.path("livelihood").path("construction").path("sites").isEmpty());
                assertEquals(2,context.path("livelihood").path("financing").path("offers").size());
                assertEquals(2500,context.path("worldObjects").path("placementCostCents").asLong());
                assertTrue(context.path("worldObjects").path("objects").isArray());
                var command=json.createObjectNode().put("action",selected);
                if("START_BUSINESS".equals(selected))command.put("target","market").put("item","bakery").put("message","Morning Bread").put("quantity",0).put("amount",20000).put("loanYears",3);
                if("START_CONSTRUCTION".equals(selected))command.put("target","point:150,200").put("item","house").put("message","My Cottage").put("loanYears",5).put("laborMode","self");
                if("PLACE_OBJECT".equals(selected))command.put("target","point:150,200").put("item","signboard").put("message","Welcome");
                if("MOVE_OBJECT".equals(selected))command.put("target","object-1").put("item","point:151,200");
                if("REPAY_LOAN".equals(selected))command.put("target","loan-1").put("amount",500);
                var response=json.createObjectNode();
                response.putArray("choices").addObject().putObject("message").put("content",command.toString());
                response.putObject("usage").put("total_tokens",30);
                return response;
            }).when(runtime).providerCall(anyString(),anyString(),anyString(),anyString(),anyString(),anyInt());
            runtime.run(new HostedAgentService.Job(self.id,"nvidia","test-model",packed,0,0,0,0));
            verify(agents,times(1)).hostedAction(eq(self.id),argThat(command->command.action()==ActionType.valueOf(selected)),isNull());
            verify(agents,times(1)).hostedAction(eq(self.id),any(Command.class),isNull());
            if("START_CONSTRUCTION".equals(selected))verify(agents).hostedAction(eq(self.id),argThat(command->Integer.valueOf(5).equals(command.loanYears())&&"self".equals(command.laborMode())),isNull());
            if("START_BUSINESS".equals(selected))verify(agents).hostedAction(eq(self.id),argThat(command->Integer.valueOf(3).equals(command.loanYears())&&command.laborMode()==null),isNull());
            verify(agents,never()).nightRoutine(anyString());
            assertEquals("job-coffee",self.job,"Presenting choices must not alter employment before the chosen world command executes");
        } finally {runtime.stop();}
    }
}
