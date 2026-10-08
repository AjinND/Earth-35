package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class LibraryTest {
    private Resident resident(State s,String id) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";place(s,a,"library");s.agents.put(id,a);return a;
    }
    private void place(State s,Resident a,String id) {a.location=id;a.x=s.buildings.get(id).x;a.z=s.buildings.get(id).z;}
    private Engine.Effects act(State s,Resident a,ActionType type,String book,Integer minutes) {
        var e=new Engine.Effects();String id=UUID.randomUUID().toString();
        Engine.apply(s,a,new Command(id,type,book,null,null,minutes,null),id,e);return e;
    }
    private void ticks(State s,int minutes) {for(int i=0;i<minutes;i++)Engine.tick(s);}
    private Library.BookView book(State s,Resident a,String id) {return Library.view(s,a).catalog().stream().filter(b->id.equals(b.id())).findFirst().orElseThrow();}
    @Test void borrowingIsFreeAndDoesNotGrantKnowledgeAndReturnsReleaseTheCopy() {
        State s=State.seed();Resident a=resident(s,"a");long money=a.money;
        assertTrue(a.knowledge.isEmpty());assertEquals(3,book(s,a,"garden-basics").availableCopies());
        act(s,a,ActionType.BORROW_BOOK,"garden-basics",null);
        assertEquals(money,a.money);assertTrue(a.knowledge.isEmpty());
        assertEquals(s.minute+7*1440,a.bookLoans.get("garden-basics").dueMinute);
        assertEquals(2,book(s,a,"garden-basics").availableCopies());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.BORROW_BOOK,"garden-basics",null));
        act(s,a,ActionType.RETURN_BOOK,"garden-basics",null);
        assertTrue(a.bookLoans.isEmpty());assertEquals(3,book(s,a,"garden-basics").availableCopies());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.RETURN_BOOK,"garden-basics",null));
    }
    @Test void onSiteReadingCreditsElapsedMinutesAndUnlocksOnlyReachedConcepts() {
        State s=State.seed();Resident a=resident(s,"a"),b=resident(s,"b");
        act(s,a,ActionType.READ_BOOK,"garden-basics",30);
        assertTrue(a.knowledge.isEmpty());assertEquals("READ_BOOK",a.pending.type);
        assertEquals(2,book(s,b,"garden-basics").availableCopies());assertFalse(Social.available(a));
        assertThrows(Engine.Invalid.class,()->Conversations.speak(s,b,a,"Hello"));
        ticks(s,10);assertEquals(10,a.knowledge.get("gardening").studyMinutes);assertTrue(a.knowledge.get("gardening").concepts.isEmpty());
        ticks(s,19);var e=Engine.tick(s);
        assertNull(a.pending);assertEquals("completed",e.completions.getFirst().status());assertTrue(e.completions.getFirst().result().contains("30 minutes"));
        assertEquals(30,a.knowledge.get("gardening").readingMinutes);assertEquals("introductory",a.knowledge.get("gardening").getLevel());
        assertEquals(1,a.knowledge.get("gardening").concepts.size());assertTrue(a.knowledge.get("gardening").concepts.iterator().next().contains("roots"));
        assertEquals(3,book(s,b,"garden-basics").availableCopies());ticks(s,10);
        assertEquals(30,a.knowledge.get("gardening").readingMinutes);
    }
    @Test void borrowedBooksCanBeReadAwayFromTheLibraryAndKnowledgeSurvivesReturnAndNewDays() {
        State s=State.seed();Resident a=resident(s,"a");
        act(s,a,ActionType.BORROW_BOOK,"listening-well",null);place(s,a,a.home);
        act(s,a,ActionType.READ_BOOK,"listening-well",60);ticks(s,60);
        var knowledge=a.knowledge.get("communication");assertEquals(60,knowledge.studyMinutes);assertEquals(2,knowledge.concepts.size());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.RETURN_BOOK,"listening-well",null));
        place(s,a,"library");act(s,a,ActionType.RETURN_BOOK,"listening-well",null);
        s.minute+=1440;Engine.tick(s);
        assertSame(knowledge,a.knowledge.get("communication"));assertEquals(60,knowledge.studyMinutes);
        act(s,a,ActionType.READ_BOOK,"telling-stories",30);ticks(s,30);
        assertEquals(90,knowledge.studyMinutes);assertEquals("familiar",knowledge.getLevel());assertEquals(3,knowledge.concepts.size());
    }
    @Test void finiteCopiesAreSharedByLoansAndOnSiteReadersAndLoansHaveALimit() {
        State s=State.seed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c"),d=resident(s,"d");
        act(s,a,ActionType.READ_BOOK,"science-everyday",10);
        act(s,b,ActionType.BORROW_BOOK,"science-everyday",null);act(s,c,ActionType.BORROW_BOOK,"science-everyday",null);
        assertEquals(0,book(s,d,"science-everyday").availableCopies());
        assertThrows(Engine.Invalid.class,()->act(s,d,ActionType.BORROW_BOOK,"science-everyday",null));
        assertThrows(Engine.Invalid.class,()->act(s,d,ActionType.READ_BOOK,"science-everyday",10));
        ticks(s,10);act(s,d,ActionType.BORROW_BOOK,"science-everyday",null);
        act(s,d,ActionType.BORROW_BOOK,"reading-history",null);act(s,d,ActionType.BORROW_BOOK,"city-histories",null);
        assertThrows(Engine.Invalid.class,()->act(s,d,ActionType.BORROW_BOOK,"soil-and-seasons",null));
        assertEquals(3,d.bookLoans.size());
    }
    @Test void reachingTheDueDateStopsReadingAndOverdueLoansBlockBorrowingUntilReturned() throws Exception {
        State s=State.seed();Resident a=resident(s,"a");s.minute=540;
        act(s,a,ActionType.BORROW_BOOK,"garden-basics",null);long due=a.bookLoans.get("garden-basics").dueMinute;
        s.minute=due-5;place(s,a,a.home);act(s,a,ActionType.READ_BOOK,"garden-basics",20);ticks(s,4);
        var e=Engine.tick(s);assertNull(a.pending);assertEquals(5,a.knowledge.get("gardening").studyMinutes);
        assertTrue(e.facts.stream().anyMatch(f->f.message().contains("overdue book")));assertTrue(Library.view(s,a).loans().getFirst().overdue());
        ObjectMapper json=new ObjectMapper();s=json.readValue(json.writeValueAsString(s),State.class);a=s.agents.get("a");
        assertTrue(Engine.tick(s).facts.stream().noneMatch(f->f.message().contains("overdue book")));
        final State restored=s;final Resident reader=a;
        assertThrows(Engine.Invalid.class,()->act(restored,reader,ActionType.READ_BOOK,"garden-basics",10));
        place(s,a,"library");assertThrows(Engine.Invalid.class,()->act(restored,reader,ActionType.BORROW_BOOK,"soil-and-seasons",null));
        act(s,a,ActionType.RETURN_BOOK,"garden-basics",null);act(s,a,ActionType.BORROW_BOOK,"soil-and-seasons",null);
        assertEquals(5,a.knowledge.get("gardening").studyMinutes);
    }
    @Test void libraryClosureEndsOnSiteReadingButBorrowedBooksRemainReadableAtHome() {
        State s=State.seed();Resident a=resident(s,"a"),b=resident(s,"b");s.minute=1190;
        act(s,a,ActionType.BORROW_BOOK,"reading-history",null);
        act(s,b,ActionType.BORROW_BOOK,"science-everyday",null);act(s,b,ActionType.READ_BOOK,"science-everyday",30);
        act(s,a,ActionType.READ_BOOK,"garden-basics",30);ticks(s,10);
        assertNull(a.pending);assertEquals(10,a.knowledge.get("gardening").studyMinutes);
        assertNull(b.pending);assertEquals(10,b.knowledge.get("science").studyMinutes);
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.READ_BOOK,"garden-basics",10));
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.RETURN_BOOK,"reading-history",null));
        place(s,a,a.home);act(s,a,ActionType.READ_BOOK,"reading-history",30);ticks(s,30);
        assertEquals(30,a.knowledge.get("history").studyMinutes);
    }
    @Test void rereadingCountsTimeButDoesNotManufactureUnlimitedNewKnowledge() {
        State s=State.seed();Resident a=resident(s,"a");
        act(s,a,ActionType.READ_BOOK,"garden-basics",100);ticks(s,100);
        act(s,a,ActionType.READ_BOOK,"garden-basics",80);ticks(s,80);
        var knowledge=a.knowledge.get("gardening");assertEquals(180,knowledge.readingMinutes);assertEquals(120,knowledge.studyMinutes);assertEquals(4,knowledge.concepts.size());
        assertEquals(120,book(s,a,"garden-basics").studyMinutes());
        assertTrue(a.knowledge.get("gardening").concepts.stream().noneMatch(c->c.contains("Mulch")));
    }
    @Test void restartPreservesPartialStudyWithoutDoubleCreditAndOldResidentsStartWithoutKnowledge() throws Exception {
        State s=State.seed();Resident a=resident(s,"a");act(s,a,ActionType.READ_BOOK,"science-everyday",30);ticks(s,10);
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);
        ticks(restored,20);a=restored.agents.get("a");assertNull(a.pending);
        assertEquals(30,a.knowledge.get("science").studyMinutes);assertEquals(30,a.bookStudies.get("science-everyday").readingMinutes);
        assertEquals("introductory",json.valueToTree(a).path("knowledge").path("science").path("level").asText());
        Resident old=json.readValue("{\"id\":\"old\"}",Resident.class);assertTrue(old.knowledge.isEmpty());assertTrue(old.bookLoans.isEmpty());assertTrue(old.bookStudies.isEmpty());
        assertFalse(json.valueToTree(WorldStore.publicAgent(a)).has("knowledge"));assertFalse(json.valueToTree(WorldStore.publicAgent(a)).has("bookLoans"));
    }
    @Test void invalidOrInaccessibleReadingHasNoEffectsAndReadingDoesNotPermitOtherActions() {
        State s=State.seed();Resident a=resident(s,"a");place(s,a,a.home);
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.READ_BOOK,"garden-basics",10));
        place(s,a,"library");assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.READ_BOOK,"unknown",10));
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.READ_BOOK,"garden-basics",0));assertTrue(a.knowledge.isEmpty());assertNull(a.pending);
        act(s,a,ActionType.BORROW_BOOK,"garden-basics",null);a.location="road";
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.READ_BOOK,"garden-basics",10));
        place(s,a,a.home);act(s,a,ActionType.READ_BOOK,"garden-basics",10);
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.MOVE,"park",null));
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.BORROW_BOOK,"soil-and-seasons",null));
        ticks(s,10);assertEquals(10,a.knowledge.get("gardening").studyMinutes);
    }
}
