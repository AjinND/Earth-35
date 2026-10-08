package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import world.earth35.memory.MemoryService;

class ConversationSummaryTest {
    ObjectMapper json=new ObjectMapper();
    com.fasterxml.jackson.databind.JsonNode response(String text) {
        var response=json.createObjectNode();response.putArray("choices").addObject().putObject("message").put("content",text);return response;
    }
    @Test void acceptsConciseParaphrasesAndRejectsMissingLongOrUnstructuredOutput() {
        assertEquals("A friendly chat about gardening.",HostedAgentService.parseSummary(json,response("{\"summary\":\"A friendly chat about gardening.\"}")));
        assertEquals("A neutral greeting.",HostedAgentService.parseSummary(json,response("```json\n{\"summary\":\"A neutral greeting.\"}\n```")));
        for(String value:List.of("not JSON","{}","{\"summary\":\"\"}","{\"summary\":\""+"x".repeat(601)+"\"}","{\"summary\":\"ok\"} {}"))
            assertThrows(HostedAgentService.ProviderFailure.class,()->HostedAgentService.parseSummary(json,response(value)));
    }
    @Test void transcriptCopiesAreRejectedButTopicsCanBeParaphrased() {
        var lines=List.of("Maya to Alex: I plan to study gardening at the library tomorrow.");
        assertTrue(MemoryService.copiesDialogue("I plan to study gardening at the library tomorrow.",lines));
        assertTrue(MemoryService.copiesDialogue("I PLAN to study gardening, at the library tomorrow!",lines));
        assertFalse(MemoryService.copiesDialogue("Maya mentioned plans to learn about plants. It was a neutral exchange, without a shared commitment.",lines));
    }
    @Test void parsesReflectionApprovalsAndRejectsMalformedLists() {
        var result=HostedAgentService.parseSummaryResult(json,response("{\"summary\":\"A friendly chat.\",\"approvedReflections\":[\"one\"]}"));
        assertEquals(List.of("one"),result.approvedReflections());
        assertEquals(List.of(),HostedAgentService.parseSummaryResult(json,response("{\"summary\":\"A friendly chat.\"}")).approvedReflections());
        for(String selections:List.of("null","{}","[5]","[\"\"]"))
            assertThrows(HostedAgentService.ProviderFailure.class,()->HostedAgentService.parseSummaryResult(json,response("{\"summary\":\"ok\",\"approvedReflections\":"+selections+"}")));
    }
}
