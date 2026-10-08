package world.earth35.world;

import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BookCatalogTest {
    @Test void everySubjectHasDistinctLessonsAndAReachablePathThroughAllDepths() {
        List<BookCatalog.Book> books=BookCatalog.books();
        assertTrue(books.size()>=100);
        Map<String,List<BookCatalog.Book>> subjects=books.stream().collect(Collectors.groupingBy(BookCatalog.Book::subject));
        assertTrue(subjects.size()>=20);
        assertTrue(subjects.keySet().containsAll(Set.of("gardening","science","history","communication","technology","computing",
            "business","arts","mathematics","physics","chemistry","biology","engineering","economics","finance","design",
            "music","literature","philosophy","psychology","environment","geography")));
        assertEquals(books.size(),books.stream().map(BookCatalog.Book::id).distinct().count());
        long lessonCount=books.stream().mapToLong(b->b.lessons().size()).sum();
        assertTrue(lessonCount>=400);
        assertEquals(lessonCount,books.stream().flatMap(b->b.lessons().stream()).distinct().count());
        for(var entry:subjects.entrySet()) {
            List<BookCatalog.Book> subject=entry.getValue();
            assertEquals(Set.of("introductory","intermediate","advanced"),
                subject.stream().map(BookCatalog.Book::depth).collect(Collectors.toSet()),entry.getKey());
            int earned=0;
            for(String depth:List.of("introductory","intermediate","advanced")) {
                List<BookCatalog.Book> stage=subject.stream().filter(b->depth.equals(b.depth())).toList();
                for(BookCatalog.Book book:stage) {
                    assertTrue(earned>=book.prerequisitePoints(),"Unreachable book: "+book.id());
                    assertTrue(book.lessons().size()>=4);
                    assertFalse(book.description().isBlank());
                    assertEquals(switch(depth) {case "introductory"->30;case "intermediate"->40;default->60;},book.lessonMinutes());
                }
                int points=switch(depth) {case "introductory"->2;case "intermediate"->3;default->4;};
                earned+=stage.stream().mapToInt(b->b.lessons().size()*points).sum();
            }
        }
    }

    @Test void existingBookIdsKeepTheirOriginalTitlesSubjectsAndLessonOrder() {
        legacy("garden-basics","A Small Garden","gardening",
            "Plant roots take up water and mineral nutrients from soil.","Plants use light in photosynthesis to make sugars.",
            "Drainage helps prevent soil from remaining waterlogged.","Compost is decomposed organic matter that can improve soil.");
        legacy("soil-and-seasons","Soil and Seasons","gardening",
            "Soil texture affects how water drains and is retained.","Mulch can reduce evaporation from soil.",
            "Rotating crops can interrupt some pest and disease cycles.","Watering needs depend on plants, soil and weather.");
        legacy("science-everyday","Everyday Science","science",
            "An observation records what is noticed; an inference interprets it.","A fair comparison controls variables other than the one being tested.",
            "Energy can be transferred and transformed between forms.","Repeated observations help distinguish a pattern from a coincidence.");
        legacy("living-systems","Living Systems","science",
            "An ecosystem includes organisms and their physical environment.","Producers use an energy source to make organic matter.",
            "Food webs describe feeding relationships among organisms.","Changes to one population can affect other parts of an ecosystem.");
        legacy("reading-history","Reading the Past","history",
            "Chronology orders events and helps establish their sequence.","Primary sources come from the period or experience being studied.",
            "Accounts of the same event can reflect different perspectives.","Corroborating independent sources strengthens a historical explanation.");
        legacy("city-histories","How Cities Change","history",
            "Transport routes and resources can influence where settlements grow.","Institutions organize shared services and civic decisions.",
            "Buildings, maps and records can provide evidence of change over time.","A local legend should be distinguished from a documented historical claim.");
        legacy("listening-well","Listening Well","communication",
            "Open questions invite someone to explain their perspective.","Paraphrasing and checking with the speaker can clarify understanding.",
            "Separating observations from assumptions helps avoid misunderstandings.","Specific, respectful disagreement can keep a discussion constructive.");
        legacy("telling-stories","Telling Stories","communication",
            "A setting gives a story a place and context.","A character's goals and obstacles can shape a narrative.",
            "Cause and effect connect events into a coherent story.","Concrete details can make a scene easier to imagine.");
    }

    @Test void catalogAndLessonsCannotBeMutatedByCallers() {
        assertThrows(UnsupportedOperationException.class,()->BookCatalog.books().clear());
        assertThrows(UnsupportedOperationException.class,()->BookCatalog.books().getFirst().lessons().clear());
        List<String> lessons=new ArrayList<>(List.of("One concept","Another concept","Third concept","Fourth concept"));
        BookCatalog.Book book=new BookCatalog.Book("test-book","Test Book","science","introductory","A test description",lessons,30,0);
        lessons.clear();assertEquals(4,book.lessons().size());
    }

    private void legacy(String id,String title,String subject,String... lessons) {
        BookCatalog.Book book=BookCatalog.books().stream().filter(b->id.equals(b.id())).findFirst().orElseThrow();
        assertEquals(title,book.title());assertEquals(subject,book.subject());assertEquals(List.of(lessons),book.lessons());
        assertEquals("introductory",book.depth());assertEquals(30,book.lessonMinutes());assertEquals(0,book.prerequisitePoints());
    }
}
