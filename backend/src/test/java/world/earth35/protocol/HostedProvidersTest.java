package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HostedProvidersTest {
    private final ObjectMapper json=new ObjectMapper();
    private final HttpClient http=mock(HttpClient.class);
    private final HostedAgentService service=new HostedAgentService(null,null,null,json,
        "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=","https://example.test/nvidia",http);
    @AfterEach void close() {service.stop();}
    @SuppressWarnings("unchecked")
    private void response(int status,String body) throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
    private String body(HttpRequest request) throws Exception {
        var result=new CompletableFuture<String>();var bytes=new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) {subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer buffer) {byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);}
            public void onError(Throwable error) {result.completeExceptionally(error);}
            public void onComplete() {result.complete(bytes.toString(StandardCharsets.UTF_8));}
        });
        return result.get(2,TimeUnit.SECONDS);
    }
    @ParameterizedTest @ValueSource(strings={"nvidia","openrouter","openai","anthropic"})
    void sendsTheSelectedProvidersEndpointHeadersAndPayload(String id) throws Exception {
        response(200,id.equals("anthropic")?"{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}],\"usage\":{\"input_tokens\":20,\"output_tokens\":7}}"
            :"{\"choices\":[{\"message\":{\"content\":\"OK\"}}],\"usage\":{\"total_tokens\":27}}");
        HostedProvider provider=HostedProvider.find(id);
        var reply=service.providerCall(id,"test-key",provider.defaultModel,"System instruction","World facts",128);
        assertEquals("OK",reply.path("choices").path(0).path("message").path("content").asText());
        assertEquals(27,reply.path("usage").path("total_tokens").asLong());
        var captured=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captured.capture(),any(HttpResponse.BodyHandler.class));
        HttpRequest request=captured.getValue();var payload=json.readTree(body(request));
        assertEquals(URI.create(id.equals("nvidia")?"https://example.test/nvidia":provider.endpoint),request.uri());
        assertEquals(provider.defaultModel,payload.path("model").asText());
        assertFalse(payload.path("stream").asBoolean(true));
        assertEquals(128,payload.path(id.equals("openai")?"max_completion_tokens":"max_tokens").asInt());
        assertEquals(id.equals("nvidia"),payload.has("reasoning_effort"));
        if(id.equals("anthropic")) {
            assertEquals("test-key",request.headers().firstValue("x-api-key").orElseThrow());
            assertEquals("2023-06-01",request.headers().firstValue("anthropic-version").orElseThrow());
            assertTrue(request.headers().firstValue("Authorization").isEmpty());
            assertEquals("System instruction",payload.path("system").asText());
            assertEquals(1,payload.path("messages").size());
            assertEquals("user",payload.path("messages").path(0).path("role").asText());
        } else {
            assertEquals("Bearer test-key",request.headers().firstValue("Authorization").orElseThrow());
            assertTrue(request.headers().firstValue("x-api-key").isEmpty());
            assertEquals("System instruction",payload.path("messages").path(0).path("content").asText());
        }
    }
    @Test void anthropicJoinsTextIgnoresThinkingAndCountsCachedTokens() throws Exception {
        response(200,"{\"content\":[{\"type\":\"thinking\",\"thinking\":\"private reasoning\"},{\"type\":\"text\",\"text\":\"O\"},{\"type\":\"text\",\"text\":\"K\"}],\"usage\":{\"input_tokens\":20,\"output_tokens\":7,\"cache_creation_input_tokens\":3,\"cache_read_input_tokens\":5}}");
        var result=service.providerCall("anthropic","key","claude-haiku-4-5","system","facts",128);
        assertEquals("OK",result.path("choices").path(0).path("message").path("content").asText());
        assertEquals(35,result.path("usage").path("total_tokens").asLong());
        assertFalse(result.toString().contains("private reasoning"));
    }
    @ParameterizedTest @ValueSource(strings={"nvidia","openrouter"})
    void nemotronRequestsReserveTheOutputForTheFinalAction(String provider) throws Exception {
        response(200,"{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");
        for(String model:List.of("nvidia/nemotron-3-super-120b-a12b","nvidia/nemotron-3-ultra-550b-a55b:free")) {
            clearInvocations(http);
            service.providerCall(provider,"test-key",model,"instruction","facts",2048);
            var captured=org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
            verify(http).send(captured.capture(),any(HttpResponse.BodyHandler.class));
            var payload=json.readTree(body(captured.getValue()));
            assertEquals(java.time.Duration.ofSeconds(60),captured.getValue().timeout().orElseThrow());
            assertEquals(model,payload.path("model").asText());
            assertFalse(provider.equals("nvidia")?payload.path("chat_template_kwargs").path("enable_thinking").asBoolean(true):payload.path("reasoning").path("enabled").asBoolean(true));
        }
    }
    @Test void anthropicIncompleteAndTruncatedResponsesHaveSafeRetryableErrors() throws Exception {
        for(String body:new String[]{"{}","null","not-json","{\"content\":[],\"stop_reason\":\"max_tokens\"}",
            "{\"content\":[{\"type\":\"thinking\",\"thinking\":\"private reasoning\"}]}"}) {
            response(200,body);
            var failure=assertThrows(HostedAgentService.ProviderFailure.class,()->service.providerCall("anthropic","key","claude-haiku-4-5","system","facts",128));
            assertFalse(failure.permanent);assertTrue(failure.getMessage().contains("Anthropic"));
            assertFalse(failure.getMessage().contains("private reasoning"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"nvidia","openrouter","openai","anthropic"})
    void errorsIdentifyTheProviderWithoutLeakingKeysOrResponseBodies(String id) throws Exception {
        for(var entry:Map.of(401,true,402,true,404,true,429,false,503,false).entrySet()) {
            response(entry.getKey(),"private-provider-body test-key");
            var failure=assertThrows(HostedAgentService.ProviderFailure.class,()->service.providerCall(id,"test-key","model","system","facts",128));
            assertEquals(entry.getValue(),failure.permanent);
            assertTrue(failure.getMessage().contains(id.equals("nvidia")?"NVIDIA":HostedProvider.find(id).label));
            assertFalse(failure.getMessage().contains("private-provider-body"));assertFalse(failure.getMessage().contains("test-key"));
        }
    }
    @Test void configurationPublishesDefaultsAndRejectsInvalidSetupsBeforeAnyNetworkCall() {
        assertEquals(java.util.List.of("nvidia","openrouter","openai","anthropic"),service.configuration().get("providers"));
        assertEquals(4,HostedProvider.options().size());
        for(var setup:new Contracts.HostedSetup[]{null,new Contracts.HostedSetup("unknown","model","key"),
            new Contracts.HostedSetup("anthropic",null,"key"),new Contracts.HostedSetup("openai","model",null),
            new Contracts.HostedSetup("openrouter","invalid model","key"),new Contracts.HostedSetup("nvidia","model","key\r\nheader")}) {
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.register("owner",
                new Contracts.Registration("Alex","description","personality","goals","any",false,setup)));
        }
        verifyNoInteractions(http);
    }
}
