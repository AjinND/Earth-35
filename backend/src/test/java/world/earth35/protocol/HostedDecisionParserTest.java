package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.ActionType;

class HostedDecisionParserTest {
    private final ObjectMapper json=new ObjectMapper();
    private HostedAgentService.Decision parse(String content) {
        var response=json.createObjectNode();
        response.putArray("choices").addObject().putObject("message").put("content",content);
        response.putObject("usage").put("total_tokens",73);
        return HostedDecisionParser.parse(json,response);
    }
    @Test void supportsBothKindsAndIgnoresInvalidMemoryWithoutLosingAction() {
        var result=parse("""
            {"action":"LOOK","memories":[
              {"kind":"semantic","content":"Maya enjoys painting.","entities":["maya"],"sourceEvents":[123]},
              {"kind":"episodic","content":"We met in the park.","entities":["park"],"sourceEvents":[123]},
              {"kind":"unknown","content":"bad","entities":[],"sourceEvents":[]}]}
            """);
        assertEquals(ActionType.LOOK,result.command().action());assertEquals(2,result.memories().size());
        assertEquals("semantic",result.memories().getFirst().kind());assertEquals(java.util.List.of("maya"),result.memories().getFirst().entities());
    }
    @Test void acceptsFencedLowercaseActionWithMalformedOptionalAnnotations() {
        var result=parse("```JSON\n{\"action\":\" move \",\"target\":\"park\",\"nightPlan\":{\"until\":\"later\"},\"routine\":{},\"memory\":\"hello\"}\n```");
        assertEquals(ActionType.MOVE,result.command().action());assertEquals("park",result.command().target());
        assertNull(result.nightPlan());assertNull(result.routine());assertNull(result.memory());assertEquals(73,result.tokens());
    }
    @ParameterizedTest @ValueSource(strings={"REASON","CREATE_WORK"})
    void acceptsAuthoredLearningMessagesThroughTheEightThousandCharacterBoundary(String action) {
        for(int length:java.util.List.of(1001,8000)) {
            String message="x".repeat(length);
            var answer=json.createObjectNode().put("action",action).put("target","science").put("message",message);
            var result=parse(answer.toString());
            assertEquals(ActionType.valueOf(action),result.command().action());
            assertEquals(message,result.command().message());assertEquals(73,result.tokens());
        }
    }
    @ParameterizedTest @ValueSource(strings={"REASON","CREATE_WORK"})
    void rejectsAuthoredLearningMessagesAboveTheEightThousandCharacterBoundary(String action) {
        var answer=json.createObjectNode().put("action",action).put("message","x".repeat(8001));
        var error=assertThrows(HostedAgentService.ProviderFailure.class,()->parse(answer.toString()));
        assertTrue(error.invalidOutput);assertFalse(error.permanent);assertEquals(73,error.tokens);
    }
    @Test void ordinaryMessagesRetainTheOneThousandCharacterLimit() {
        var answer=json.createObjectNode().put("action","TALK").put("target","maya").put("message","x".repeat(1000));
        assertEquals(1000,parse(answer.toString()).command().message().length());
        answer.put("message","x".repeat(1001));
        var error=assertThrows(HostedAgentService.ProviderFailure.class,()->parse(answer.toString()));
        assertTrue(error.invalidOutput);assertFalse(error.permanent);assertEquals(73,error.tokens);
    }
    @Test void generatedProductReferencesPassHttpAndHostedValidationWithoutAcceptingUnboundedItems() {
        String product="product:"+java.util.UUID.randomUUID();
        var answer=json.createObjectNode().put("action","BUY").put("target","business:"+java.util.UUID.randomUUID())
            .put("item",product).put("quantity",1);
        var command=parse(answer.toString()).command();
        assertEquals(product,command.item());
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(command).isEmpty());
            var tooLong=new world.earth35.protocol.Contracts.Command("long",ActionType.BUY,"business",
                null,"x".repeat(81),1,null);
            assertFalse(factory.getValidator().validate(tooLong).isEmpty());
        }
        answer.put("item","x".repeat(81));
        assertTrue(assertThrows(HostedAgentService.ProviderFailure.class,()->parse(answer.toString())).invalidOutput);
    }
    @Test void modelCanChooseAnOwnerOperatedShopWithNoEmployeeSeats() {
        var decision=parse("{\"action\":\"START_BUSINESS\",\"target\":\"market\",\"item\":\"bakery\",\"message\":\"Morning Bread\",\"quantity\":0,\"amount\":20000}");
        assertEquals(ActionType.START_BUSINESS,decision.command().action());
        assertEquals(0,decision.command().quantity());assertEquals(20000L,decision.command().amount());
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(decision.command()).isEmpty());
        }
    }
    @Test void financedSelfConstructionPreservesTheModelsExplicitCommitments() {
        var command=parse("{\"action\":\"START_CONSTRUCTION\",\"target\":\"point:150,200\",\"item\":\"house\",\"message\":\"My Cottage\",\"loanYears\":5,\"laborMode\":\"self\"}").command();
        assertEquals(5,command.loanYears());assertEquals("self",command.laborMode());
        assertFalse(json.valueToTree(command).has("loanTermValid"),"Validation helpers must not extend the wire command");
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(command).isEmpty());
            var unsupportedTerm=new world.earth35.protocol.Contracts.Command("invalid",ActionType.START_CONSTRUCTION,
                command.target(),command.message(),command.item(),null,null,4,"self");
            assertFalse(factory.getValidator().validate(unsupportedTerm).isEmpty());
        }
        var old=new world.earth35.protocol.Contracts.Command("old",ActionType.LOOK,null,null,null,null,null);
        assertNull(old.loanYears());assertNull(old.laborMode());
    }
    @ParameterizedTest @ValueSource(strings={"{\"action\":\"START_BUSINESS\",\"loanYears\":4}",
        "{\"action\":\"START_BUSINESS\",\"loanYears\":\"3\"}","{\"action\":\"START_CONSTRUCTION\",\"laborMode\":\"automatic\"}",
        "{\"action\":\"START_CONSTRUCTION\",\"laborMode\":{\"mode\":\"self\"}}"})
    void invalidFinanceAndLaborFieldsNeverBecomeImplicitCommitments(String content) {
        var error=assertThrows(HostedAgentService.ProviderFailure.class,()->parse(content));
        assertTrue(error.invalidOutput);assertEquals(73,error.tokens);
    }
    @Test void explicitRepaymentAndSignPlacementUseTheExistingCommandFields() {
        var repayment=parse("{\"action\":\"REPAY_LOAN\",\"target\":\"loan-1\",\"amount\":2500}").command();
        assertEquals(ActionType.REPAY_LOAN,repayment.action());assertEquals(2500L,repayment.amount());
        var board=parse("{\"action\":\"PLACE_OBJECT\",\"target\":\"point:150,200\",\"item\":\"advertisement:business-1\",\"message\":\"Fresh bread daily\"}").command();
        assertEquals(ActionType.PLACE_OBJECT,board.action());assertEquals("advertisement:business-1",board.item());
        var moved=parse("{\"action\":\"MOVE_OBJECT\",\"target\":\"object-1\",\"item\":\"point:151,200\",\"message\":null}").command();
        assertEquals(ActionType.MOVE_OBJECT,moved.action());assertEquals("point:151,200",moved.item());
    }
    @ParameterizedTest @ValueSource(strings={"BUY","SELL","READ_BOOK","LAUNCH_BUSINESS","BUILD_CONSTRUCTION"})
    void zeroQuantityRemainsInvalidForOtherActions(String action) {
        assertTrue(assertThrows(HostedAgentService.ProviderFailure.class,
            ()->parse("{\"action\":\""+action+"\",\"quantity\":0}")).invalidOutput);
    }
    @ParameterizedTest @ValueSource(strings={"null","[]","{}","{\"action\":\"DANCE\"}",
        "{\"action\":\"LOOK\"} {\"action\":\"WORK\"}","{\"action\":\"LOOK\",\"action\":\"WORK\"}",
        "<think>{\"action\":\"LOOK\"}</think>","{\"action\":\"MOVE\",\"target\":{\"id\":\"park\"}}",
        "{\"action\":\"BUY\",\"quantity\":4294967297}","{\"action\":\"TRANSFER\",\"amount\":1.5}"})
    void rejectsAmbiguousOrInvalidAnswersWithoutLosingUsage(String content) {
        var error=assertThrows(HostedAgentService.ProviderFailure.class,()->parse(content));
        assertFalse(error.permanent);assertTrue(error.invalidOutput);assertEquals(73,error.tokens);
        assertFalse(error.getMessage().contains(content));
    }
    @Test void preservesValidOptionalMetadata() {
        var result=parse("{\"action\":\"LOOK\",\"memory\":{\"content\":\"A conversation\",\"sourceEventId\":123},\"nightPlan\":{\"reason\":\"visit\",\"until\":1400},\"routine\":{\"wakeMinute\":360,\"sleepMinute\":1320,\"morningActivity\":\"reading\"}}");
        assertEquals("A conversation",result.memory());assertEquals(123L,result.sourceEvent());
        assertEquals(1400,result.nightPlan().until());assertEquals("reading",result.routine().morningActivity());
    }
    @Test void ignoresInlineReasoningAndExecutesOnlyTheFinalAction() {
        var result=parse("<think>Example: {\"action\":\"WORK\"}</think>\n```json\n{\"action\":\"MOVE\",\"target\":\"park\"}\n```");
        assertEquals(ActionType.MOVE,result.command().action());assertEquals("park",result.command().target());
    }
    @Test void acceptsAnUnambiguousProseWrappedAction() {
        assertEquals(ActionType.LOOK,parse("My next action is:\n{\"action\":\"LOOK\"}\n").command().action());
    }
    @ParameterizedTest @ValueSource(strings={"<think>{\"action\":\"WORK\"}","<think>{\"action\":\"WORK\"}</think>",
        "Here are actions: [{\"action\":\"LOOK\"}]","Example {\"action\":\"LOOK\"} then {\"action\":\"WORK\"}"})
    void neverUsesThinkingOrAListOfExamplesAsAnAction(String content) {
        assertThrows(HostedAgentService.ProviderFailure.class,()->parse(content));
    }
}
