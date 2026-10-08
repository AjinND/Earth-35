package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class LearningTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private Resident resident(State s) {
        Resident a=new Resident();a.id="learner";a.name="Learner";a.home="apt-101";a.location="library";
        a.x=s.buildings.get("library").x;a.z=s.buildings.get("library").z;s.agents.put(a.id,a);return a;
    }
    private String act(State s,Resident a,ActionType type,String target,String message,String kind,String id) {
        return Engine.apply(s,a,new Command(id,type,target,message,kind,null,null),id,new Engine.Effects());
    }
    private List<BookCatalog.Book> books(String depth) {
        return BookCatalog.books().stream().filter(b->b.subject().equals("business")&&b.depth().equals(depth)).toList();
    }
    private void read(State s,Resident a,BookCatalog.Book book,int minutes) {
        if(!a.bookLoans.containsKey(book.id())) {
            // Use the actual lending/return actions; borrowing is not learning.
            if(!Places.open(s.buildings.get("library"),s.minute))s.minute=(s.minute/1440+1)*1440+540;
            a.location="library";
            if(a.bookLoans.size()==3)act(s,a,ActionType.RETURN_BOOK,a.bookLoans.keySet().iterator().next(),null,null,UUID.randomUUID().toString());
            act(s,a,ActionType.BORROW_BOOK,book.id(),null,null,UUID.randomUUID().toString());
        }
        a.location=a.home;
        while(minutes>0) {
            int window=Math.min(100,minutes);a.energy=100;a.hunger=10;
            String id=UUID.randomUUID().toString();
            Engine.apply(s,a,new Command(id,ActionType.READ_BOOK,book.id(),null,null,window,null),id,new Engine.Effects());
            for(int i=0;i<window;i++)Engine.tick(s);
            assertNull(a.pending);minutes-=window;
        }
    }
    private void foundation(State s,Resident a) {for(var b:books("introductory"))read(s,a,b,b.lessons().size()*b.lessonMinutes());}
    private List<String> concepts(Resident a) {return a.knowledge.get("business").concepts.stream().limit(2).toList();}
    private String reasonMessage(Resident a) throws Exception {
        var evidence=concepts(a);
        return JSON.writeValueAsString(Map.of("problem","How could a resident test demand for a new local delivery service?",
            "analysis",evidence.get(0)+" "+evidence.get(1)+" Apply these principles by interviewing prospective customers, comparing delivery costs with willingness to pay, and testing a small pilot before expanding. Treat initial interest as an uncertain hypothesis, not proven demand.","concepts",evidence));
    }
    private String workMessage(Resident a,String reasoningId) throws Exception {
        return JSON.writeValueAsString(Map.of("title","Neighborhood delivery pilot","reasoningId",reasoningId,
            "content","Propose a limited neighborhood delivery service that applies this principle: "+concepts(a).getFirst()+" Interview ten residents, estimate costs for a single route, and trial advance orders for a week. Compare completed orders and net margin with assumptions before investing further."));
    }
    @Test void studyBuildsDepthAndPrerequisitesRequireKnowledgeInTheSameSubject() {
        State s=State.seed();Resident a=resident(s);var intermediate=books("intermediate").getFirst();
        assertFalse(Library.view(s,a).catalog().stream().filter(b->b.id().equals(intermediate.id())).findFirst().orElseThrow().unlocked());
        assertThrows(Engine.Invalid.class,()->read(s,a,intermediate,40));assertTrue(a.knowledge.isEmpty());
        read(s,a,books("introductory").getFirst(),29);
        assertEquals(0,a.knowledge.get("business").points);assertTrue(a.knowledge.get("business").concepts.isEmpty());
        read(s,a,books("introductory").getFirst(),1);assertEquals(2,a.knowledge.get("business").points);
        foundation(s,a);var knowledge=a.knowledge.get("business");
        assertEquals(16,knowledge.points);assertTrue(knowledge.getCanReason());assertFalse(knowledge.getCanCreate());
        assertFalse(a.knowledge.containsKey("physics"));
        read(s,a,intermediate,39);assertEquals(16,knowledge.points);
        read(s,a,intermediate,1);assertEquals(19,knowledge.points);assertEquals("intermediate",knowledge.getDepth());
        assertThrows(Engine.Invalid.class,()->read(s,a,books("advanced").getFirst(),60));
        read(s,a,intermediate,120);assertEquals(28,knowledge.points);assertTrue(knowledge.getCanCreate());
        read(s,a,books("advanced").getFirst(),59);assertEquals(28,knowledge.points);
        read(s,a,books("advanced").getFirst(),1);assertEquals(32,knowledge.points);assertEquals("advanced",knowledge.getDepth());
    }
    @Test void rereadingNeverInflatesUnderstandingOrUnlocksCreationFromOnlyBeginnerBooks() {
        State s=State.seed();Resident a=resident(s);foundation(s,a);var knowledge=a.knowledge.get("business");
        int understanding=knowledge.getUnderstanding();read(s,a,books("introductory").getFirst(),300);
        assertEquals(16,knowledge.points);assertEquals(8,knowledge.concepts.size());assertEquals(understanding,knowledge.getUnderstanding());assertFalse(knowledge.getCanCreate());
    }
    @Test void fullSubjectCollectionReachesOneHundredWithoutLeakingUnreadLessons() {
        State s=State.seed();Resident a=resident(s);foundation(s,a);
        for(var b:books("intermediate"))read(s,a,b,b.lessonMinutes()*b.lessons().size());
        var advanced=books("advanced").getFirst();read(s,a,advanced,advanced.lessonMinutes()*advanced.lessons().size());
        assertEquals(56,a.knowledge.get("business").points);assertEquals(100,a.knowledge.get("business").getUnderstanding());
        assertEquals(20,a.knowledge.get("business").concepts.size());
        assertFalse(JSON.valueToTree(Library.view(s,a)).path("catalog").get(0).has("lessons"));
    }
    @Test void learnedConceptsEnableGroundedReasoningThenPersistentOriginalDrafts() throws Exception {
        State s=State.seed();Resident a=resident(s);foundation(s,a);
        assertTrue(act(s,a,ActionType.REASON,"business",reasonMessage(a),null,"reason-1").contains("Recorded reasoning"));
        int points=a.knowledge.get("business").points;
        assertEquals(concepts(a),a.reasoning.get("reason-1").concepts());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.CREATE_WORK,"business",workMessage(a,"reason-1"),"business","work-1"));
        read(s,a,books("intermediate").getFirst(),160);
        assertTrue(act(s,a,ActionType.CREATE_WORK,"business",workMessage(a,"reason-1"),"business","work-1").contains("Saved business draft"));
        assertEquals(points+12,a.knowledge.get("business").points);
        assertEquals("reason-1",a.creations.get("work-1").reasoningId());assertEquals("Unemployed",a.occupation);
        State restored=JSON.readValue(JSON.writeValueAsString(s),State.class);Resident again=restored.agents.get(a.id);Library.refreshKnowledge(again);
        assertEquals(28,again.knowledge.get("business").points);assertEquals(a.reasoning,again.reasoning);assertEquals(a.creations,again.creations);
        assertEquals("work-1",Library.view(restored,again).creations().getFirst().id());
        assertFalse(JSON.valueToTree(WorldStore.publicAgent(again)).has("reasoning"));assertFalse(JSON.valueToTree(WorldStore.publicAgent(again)).has("creations"));
        assertThrows(Engine.Invalid.class,()->act(restored,again,ActionType.CREATE_WORK,"business",workMessage(again,"reason-1"),"business","duplicate"));
    }
    @Test void unreadConceptsUnrelatedSubjectsAndUnownedReasoningAreRejectedWithoutArtifacts() throws Exception {
        State s=State.seed();Resident a=resident(s);
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.REASON,"business","{}",null,"empty"));
        foundation(s,a);read(s,a,books("intermediate").getFirst(),160);
        String unknown=JSON.writeValueAsString(Map.of("problem","Should we invent a new faster engine?","analysis","An unsupported claim repeated here cannot establish new knowledge or reasoning ability.","concepts",List.of("Unread concept one","Unread concept two")));
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.REASON,"business",unknown,null,"unread"));
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.REASON,"physics",reasonMessage(a),null,"unrelated"));
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.CREATE_WORK,"business",workMessage(a,"another-resident-reason"),"product","stolen"));
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.REASON,"business","{bad",null,"malformed"));
        assertTrue(a.reasoning.isEmpty());assertTrue(a.creations.isEmpty());assertEquals(28,a.knowledge.get("business").points);
    }
    @Test void legacyStudyRestoresExactEarnedDepthAndAuthoredKnowledgeCannotGrantPoints() throws Exception {
        Resident old=JSON.readValue("{\"id\":\"old\",\"bookStudies\":{\"science-everyday\":{\"studyMinutes\":60,\"readingMinutes\":60}},\"knowledge\":{\"science\":{\"studyMinutes\":60,\"concepts\":[],\"points\":999}}}",Resident.class);
        Library.refreshKnowledge(old);assertEquals(4,old.knowledge.get("science").points);assertEquals(2,old.knowledge.get("science").concepts.size());assertFalse(old.knowledge.get("science").getCanReason());
        assertTrue(old.reasoning.isEmpty());assertTrue(old.creations.isEmpty());
        SubjectKnowledge fabricated=new SubjectKnowledge();fabricated.studyMinutes=99999;fabricated.concepts.add("Claimed expertise from a memory");old.knowledge.put("business",fabricated);
        Library.refreshKnowledge(old);assertEquals(0,fabricated.points);assertFalse(fabricated.getCanReason());
    }
}
