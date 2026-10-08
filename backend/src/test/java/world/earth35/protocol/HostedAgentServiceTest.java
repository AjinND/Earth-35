package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HostedAgentServiceTest {
    private static final String ENDPOINT="https://integrate.api.nvidia.com/v1/chat/completions";
    private final HttpClient http=mock(HttpClient.class);
    private final HostedAgentService service=new HostedAgentService(null,null,null,new ObjectMapper(),"",ENDPOINT,http);

    @AfterEach void close() {service.stop();}

    @SuppressWarnings("unchecked")
    private void response(int status,String body) throws Exception {
        HttpResponse<String> response=mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }
    private HostedAgentService.ProviderFailure failure() {
        return assertThrows(HostedAgentService.ProviderFailure.class,
            ()->service.providerCall("test-key-no-real-secret","openai/gpt-oss-20b","Reply OK","Connection check",128));
    }

    @Test void acceptsFinalAnswerAlongsideReasoning() throws Exception {
        response(200,"{\"choices\":[{\"message\":{\"content\":\"OK\",\"reasoning_content\":\"private model reasoning\"}}]}");
        var result=service.providerCall("test-key-no-real-secret","openai/gpt-oss-20b","Reply OK","Connection check",128);
        assertEquals("OK",result.path("choices").path(0).path("message").path("content").asText());
        ArgumentCaptor<HttpRequest> request=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(),any(HttpResponse.BodyHandler.class));
        assertEquals(URI.create(ENDPOINT),request.getValue().uri());
        assertEquals("POST",request.getValue().method());
        assertEquals("Bearer test-key-no-real-secret",request.getValue().headers().firstValue("Authorization").orElseThrow());
        assertEquals("application/json",request.getValue().headers().firstValue("Accept").orElseThrow());
        assertEquals("openai/gpt-oss-20b",service.configuration().get("defaultModel"));
    }

    @ParameterizedTest
    @CsvSource({"401,true,API key","403,true,API key","404,true,model","402,true,credits","429,false,rate-limiting","503,false,temporarily unavailable"})
    void providerErrorsAreActionableAndNeverEchoProviderBody(int status,boolean permanent,String expected) throws Exception {
        response(status,"provider-body-with-private-key");
        var error=failure();
        assertEquals(permanent,error.permanent);
        assertTrue(error.getMessage().contains(expected),error.getMessage());
        assertFalse(error.getMessage().contains("provider-body"));
        assertFalse(error.getMessage().contains("test-key"));
    }

    @Test void modelTimeoutDoesNotClaimTheKeyIsRejected() throws Exception {
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("private-network-detail"));
        var error=failure();
        assertFalse(error.permanent);
        assertTrue(error.getMessage().contains("model response timed out"));
        assertTrue(error.getMessage().contains("key may be valid"));
        assertFalse(error.getMessage().contains("private-network-detail"));
    }

    @Test void connectionTimeoutIsDifferentFromInferenceTimeout() throws Exception {
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new HttpConnectTimeoutException("private-network-detail"));
        var error=failure();
        assertFalse(error.permanent);
        assertTrue(error.getMessage().contains("Connection to NVIDIA timed out"));
    }

    @Test void tlsFailureIsReportedWithoutPrivateExceptionDetails() throws Exception {
        when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenThrow(new SSLHandshakeException("private-certificate-detail"));
        var error=failure();
        assertFalse(error.permanent);
        assertTrue(error.getMessage().contains("secure TLS connection"));
        assertFalse(error.getMessage().contains("private-certificate-detail"));
    }

    @Test void tokenExhaustionIsRetryableNotAnInvalidModel() throws Exception {
        response(200,"{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":null,\"reasoning_content\":\"private reasoning\"}}]}");
        var error=failure();
        assertFalse(error.permanent);
        assertTrue(error.getMessage().contains("output limit"));
        assertFalse(error.getMessage().contains("private reasoning"));
    }

    @Test void missingChoicesAndMalformedJsonAreRetryable() throws Exception {
        response(200,"{}");
        assertFalse(failure().permanent);
        response(200,"null");
        assertFalse(failure().permanent);
        response(200,"not-json");
        assertTrue(failure().getMessage().contains("unreadable model response"));
    }

    @Test void reasoningWithoutFinalAnswerIsRetryable() throws Exception {
        response(200,"{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"reasoning_content\":\"private reasoning\"}}]}");
        var error=failure();
        assertFalse(error.permanent);
        assertTrue(error.getMessage().contains("without a final answer"));
        assertFalse(error.getMessage().contains("private reasoning"));
    }
    @Test void truncatedNonemptyAnswerIsNeverExecutedAndUsageIsPreserved() throws Exception {
        response(200,"{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"action\\\":\\\"LOOK\\\"}\"}}],\"usage\":{\"total_tokens\":2048}}");
        var error=failure();
        assertTrue(error.invalidOutput);assertFalse(error.permanent);assertEquals(2048,error.tokens);
        assertTrue(error.getMessage().contains("output limit"));
    }
}
