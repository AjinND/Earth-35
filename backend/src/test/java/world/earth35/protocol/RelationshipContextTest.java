package world.earth35.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.converter.ModelConverters;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import world.earth35.memory.MemoryService;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.*;

class RelationshipContextTest {
    private final ObjectMapper json=new ObjectMapper();
    private final WorldStore store=spy(new WorldStore(mock(JdbcTemplate.class),json));
    private final HostedAgentService runtime=new HostedAgentService(store,mock(AgentService.class),mock(MemoryService.class),json,"","https://example.test/completions",mock(HttpClient.class));
    @AfterEach void stop() {runtime.stop();}
    private State.Resident resident(State state,String id) {
        State.Resident resident=new State.Resident();resident.id=id;resident.name=id;resident.home="apt-101";
        resident.location="park";resident.x=state.buildings.get("park").x;resident.z=state.buildings.get("park").z;
        state.agents.put(id,resident);return resident;
    }
    private void appraise(State state,State.Resident author,String other,String evidence,String feeling) {
        Relationships.update(state,author,new Relationships.Draft(other,"An acquaintance",feeling,"They listened to my concern",
            List.of(evidence),70,25,60,20,null,35));
    }
    private HostedAgentService.Decision parse(String content) {
        var response=json.createObjectNode();response.putArray("choices").addObject().putObject("message").put("content",content);
        return HostedDecisionParser.parse(json,response);
    }
    @Test void relationshipAndCognitionReflectionsHaveDistinctGeneratedSchemas() throws Exception {
        var schemas=ModelConverters.getInstance().readAll(State.Resident.class);var components=json.valueToTree(schemas);
        assertTrue(components.has("RelationshipReflection"));assertTrue(components.has("Reflection"));
        var relationship=components.path("RelationshipReflection").path("properties");
        assertEquals("#/components/schemas/RelationshipAppraisal",relationship.path("appraisal").path("$ref").asText());
        assertEquals("#/components/schemas/RelationshipEvidence",relationship.path("evidence").path("items").path("$ref").asText());
        assertTrue(relationship.has("minute"));assertFalse(relationship.has("lesson"));assertFalse(relationship.has("outcomeId"));
        var cognition=components.path("Reflection").path("properties");
        assertTrue(cognition.has("lesson"));assertTrue(cognition.has("outcomeId"));assertTrue(cognition.has("minute"));
        assertFalse(cognition.has("appraisal"));assertFalse(cognition.has("evidence"));
        assertEquals("#/components/schemas/RelationshipReflection",components.path("RelationshipBond").path("properties").path("history").path("items").path("$ref").asText());
        assertEquals("#/components/schemas/Reflection",components.path("Mind").path("properties").path("reflections").path("items").path("$ref").asText());
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/relationships-schemas.json"),io.swagger.v3.core.util.Json.mapper().writeValueAsString(Map.of(
            "openapi","3.0.1","info",Map.of("title","Relationship schema","version","1"),"paths",Map.of(),"components",Map.of("schemas",schemas))));
    }
    @ParameterizedTest @ValueSource(strings={"warmth","trust","respect","closeness","attraction","tension"})
    void relationshipDimensionsAcceptOnlyIntegerJsonNumbersOrNull(String dimension) throws Exception {
        var draft=json.createObjectNode().put("target","sam").put("feeling","Cautiously fond").put("reason","They listened");
        draft.putArray("evidence").add("exchange");
        for(int score:List.of(0,37,100)) {
            draft.put(dimension,score);
            var parsed=json.readValue(draft.toString(),Relationships.Draft.class);
            assertEquals(score,json.valueToTree(parsed).path(dimension).asInt());
        }
        draft.putNull(dimension);
        assertTrue(json.valueToTree(json.readValue(draft.toString(),Relationships.Draft.class)).path(dimension).isNull());
        for(String invalid:List.of("37.5","\"37\"","true")) {
            draft.set(dimension,json.readTree(invalid));
            assertThrows(JsonProcessingException.class,()->json.readValue(draft.toString(),Relationships.Draft.class),dimension+" must reject "+invalid);
        }
    }
    @Test void hostedParserRetainsARealRelationshipDraftAndMalformedScoresDoNotReplaceThePrimaryAction() {
        State state=State.prototypeSeed();State.Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        Relationships.record(state,"exchange","conversation",alex.id,sam.id,"They talked through a difficult day");
        String evidence=Relationships.views(alex,state.minute).getFirst().evidence().getFirst().id();
        var answer=json.createObjectNode().put("action","LOOK");
        var draft=answer.putObject("cognition").putObject("relationship").put("target",sam.id)
            .put("label","A promising acquaintance").put("feeling","Warm but still cautious").put("reason","They listened to my concern")
            .put("warmth",75).put("trust",30).put("tension",25);
        draft.putArray("evidence").add(evidence);draft.putNull("attraction");
        var decision=parse(answer.toString());
        assertEquals(ActionType.LOOK,decision.command().action());assertNotNull(decision.cognition());
        assertEquals(sam.id,decision.cognition().relationship().target());
        assertEquals(List.of(evidence),decision.cognition().relationship().evidence());
        assertEquals(75,decision.cognition().relationship().warmth());assertNull(decision.cognition().relationship().attraction());
        Cognition.update(state,alex,decision.cognition());
        assertEquals("Warm but still cautious",Relationships.views(alex,state.minute).getFirst().appraisal().feeling());
        draft.put("warmth",75.5);
        var malformed=parse(answer.toString());
        assertEquals(ActionType.LOOK,malformed.command().action());assertNull(malformed.cognition());
    }
    @Test void relationshipEndpointRequiresExperienceWithThatPersonAndNoUnrelatedDatabaseEventPrerequisite() {
        State state=State.prototypeSeed();State.Resident alex=resident(state,"alex"),sam=resident(state,"sam"),stranger=resident(state,"stranger");
        Relationships.record(state,"exchange","conversation",alex.id,sam.id,"They exchanged greetings");
        doReturn(state).when(store).read(true);doReturn(List.of()).when(store).events(0,1,alex.id);
        AgentService agents=new AgentService(store);
        var denied=assertThrows(ResponseStatusException.class,()->agents.relationship(alex.id,new Relationship(stranger.id,"Close friend")));
        assertEquals(HttpStatus.BAD_REQUEST,denied.getStatusCode());assertTrue(alex.relationships.isEmpty());
        verify(store,never()).save(state);
        agents.relationship(alex.id,new Relationship(sam.id,"An acquaintance"));
        assertEquals("An acquaintance",alex.relationships.get(sam.id));assertFalse(alex.relationships.containsKey(stranger.id));
        assertTrue(sam.relationships.isEmpty());assertNull(Relationships.views(alex,state.minute).getFirst().appraisal());
        verify(store).save(state);verify(store,never()).events(0,1,alex.id);
    }
    @Test void hostedAndIndependentObservationsCarryOnlyTheAuthorsPrivateInterpretationAfterRestart() throws Exception {
        State state=State.prototypeSeed();state.minute=720;State.Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        Relationships.record(state,"exchange","conversation",alex.id,sam.id,"Alex and Sam discussed their day");
        String evidence=Relationships.views(alex,state.minute).getFirst().evidence().getFirst().id();
        appraise(state,alex,sam.id,evidence,"Alex's private warmth mixed with caution");
        appraise(state,sam,alex.id,evidence,"Sam's undisclosed resentment");
        State persisted=json.readValue(json.writeValueAsString(state),State.class);
        doReturn(persisted).when(store).read(true);doReturn(List.of()).when(store).events(Long.MAX_VALUE,100,alex.id);
        var observed=new AgentService(store).observe(alex.id,Long.MAX_VALUE);
        var independent=json.valueToTree(observed);var hosted=json.valueToTree(runtime.decisionContext(alex.id,observed,List.of()));
        assertEquals("Alex's private warmth mixed with caution",independent.path("relationships").get(0).path("appraisal").path("feeling").asText());
        assertEquals(independent.path("relationships"),hosted.path("relationships"));
        assertTrue(hosted.path("recentEvents").isEmpty());
        assertEquals("An acquaintance",hosted.path("self").path("relationships").path(sam.id).asText());
        assertFalse(independent.toString().contains("Sam's undisclosed resentment"));
        assertFalse(hosted.toString().contains("Sam's undisclosed resentment"));
        assertFalse(independent.path("nearby").get(0).has("bonds"));
        assertFalse(hosted.path("nearby").get(0).has("relationships"));
    }
    @Test void publicProfilesAndWorldSnapshotsNeverExposePrivateBondAssessments() throws Exception {
        State state=State.prototypeSeed();State.Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        Relationships.record(state,"exchange","conversation",alex.id,sam.id,"They talked about a public garden");
        String evidence=Relationships.views(alex,state.minute).getFirst().evidence().getFirst().id();
        appraise(state,alex,sam.id,evidence,"A deeply private and uncertain attraction");
        var publicAgent=json.valueToTree(WorldStore.publicAgent(alex));
        assertFalse(publicAgent.has("bonds"));assertFalse(publicAgent.has("relationships"));assertFalse(publicAgent.has("appraisal"));
        assertFalse(publicAgent.toString().contains("A deeply private and uncertain attraction"));
        doReturn(state).when(store).read(false);doReturn(List.of()).when(store).events(0,50,"");
        var snapshot=json.valueToTree(store.snapshot());
        assertFalse(snapshot.toString().contains("A deeply private and uncertain attraction"));
        assertFalse(snapshot.path("agents").get(0).has("bonds"));
    }
    @Test void observingExistingEvidenceDoesNotCountAsAnotherInteractionOrAppraiseForTheResident() {
        State state=State.prototypeSeed();State.Resident alex=resident(state,"alex"),sam=resident(state,"sam");
        Relationships.record(state,"exchange","conversation",alex.id,sam.id,"They exchanged greetings");
        doReturn(state).when(store).read(true);doReturn(List.of()).when(store).events(Long.MAX_VALUE,100,alex.id);
        AgentService agents=new AgentService(store);
        var first=json.valueToTree(agents.observe(alex.id,Long.MAX_VALUE)).path("relationships");
        var next=json.valueToTree(agents.observe(alex.id,Long.MAX_VALUE)).path("relationships");
        assertEquals(first,next);assertEquals(1,next.get(0).path("interactionCount").asInt());
        assertTrue(next.get(0).path("appraisal").isNull());assertTrue(alex.relationships.isEmpty());assertTrue(sam.relationships.isEmpty());
    }
}
