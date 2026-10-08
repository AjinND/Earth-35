package world.earth35.world;

import java.util.*;
import world.earth35.world.State.*;

/** Server-owned lending and timed study. Model-authored memories cannot grant knowledge. */
public final class Library {
    private Library() {}
    public static final String LOCATION="library";
    public static final String PURPOSE="A public library for gaining knowledge, reading and borrowing books. Quiet study is the main purpose; brief, considerate conversations are secondary.";
    public static void configure(State state) {
        Building library=state.buildings.get(LOCATION);
        if(library!=null)library.description=PURPOSE;
    }
    public static final int LOAN_DAYS=7,LOAN_LIMIT=3,MASTERY_MINUTES=120;
    private static final List<BookCatalog.Book> BOOKS=BookCatalog.books();
    private static final int COPIES=3;
    private static int mastery(BookCatalog.Book b) {return b.lessonMinutes()*b.lessons().size();}
    private static int weight(BookCatalog.Book b) {return switch(b.depth()) {case "advanced"->4;case "intermediate"->3;default->2;};}
    private static boolean unlocked(Resident a,BookCatalog.Book b) {
        SubjectKnowledge knowledge=a.knowledge.get(b.subject());
        return b.prerequisitePoints()==0||knowledge!=null&&knowledge.points>=b.prerequisitePoints();
    }
    /** Rebuild derived capability from saved, server-earned study. Also upgrades older residents without granting unread lessons. */
    public static void refreshKnowledge(Resident a) {
        for(SubjectKnowledge knowledge:a.knowledge.values()) {
            knowledge.points=0;knowledge.maximumPoints=0;knowledge.intermediateConcepts=0;knowledge.advancedConcepts=0;knowledge.researchPoints=0;
        }
        Map<String,Set<String>> counted=new HashMap<>();
        Map<String,Integer> maxima=new HashMap<>();
        for(BookCatalog.Book b:BOOKS) {
            maxima.merge(b.subject(),b.lessons().size()*weight(b),Integer::sum);
            BookStudy study=a.bookStudies.get(b.id());
            SubjectKnowledge knowledge=a.knowledge.get(b.subject());
            if(study!=null&&study.studyMinutes>0&&knowledge==null) {
                knowledge=new SubjectKnowledge();a.knowledge.put(b.subject(),knowledge);
            }
            if(knowledge==null)continue;
            if(study==null)continue;
            int learned=Math.min(b.lessons().size(),Math.max(0,study.studyMinutes)/b.lessonMinutes());
            for(String concept:b.lessons().subList(0,learned)) {
                knowledge.concepts.add(concept);
                if(!counted.computeIfAbsent(b.subject(),key->new HashSet<>()).add(concept))continue;
                knowledge.points+=weight(b);
                if(b.depth().equals("intermediate"))knowledge.intermediateConcepts++;
                if(b.depth().equals("advanced"))knowledge.advancedConcepts++;
            }
        }
        Map<String,Set<String>> researched=new HashMap<>();
        for(Projects.ResearchFinding finding:a.researchFindings.values()) {
            SubjectKnowledge knowledge=a.knowledge.get(finding.subject());
            if(knowledge==null)continue;
            if(!researched.computeIfAbsent(finding.subject(),key->new HashSet<>()).add(finding.lessonKey()))continue;
            knowledge.concepts.add(finding.concept());
            if(knowledge.researchPoints<12) {knowledge.researchPoints+=2;knowledge.points+=2;}
        }
        a.knowledge.forEach((subject,knowledge)->knowledge.maximumPoints=maxima.getOrDefault(subject,0)+knowledge.researchPoints);
    }
    public record BookView(String id,String title,String subject,String description,int copies,int availableCopies,
                           long readingMinutes,int studyMinutes,int masteryMinutes,String depth,int lessonMinutes,int lessonCount,int prerequisitePoints,boolean unlocked) {}
    public record LoanView(String book,String title,String subject,long borrowedMinute,long dueMinute,
                           long minutesUntilDue,boolean overdue) {}
    public record LibraryView(String location,int loanDays,int loanLimit,List<BookView> catalog,List<LoanView> loans,List<Reasoning> reasoning,List<Creation> creations) {}
    private static BookCatalog.Book book(String id) {
        return BOOKS.stream().filter(b->b.id().equals(id)).findFirst().orElseThrow(()->new Engine.Invalid("Choose a book ID from library.catalog"));
    }
    private static int available(State s,String id) {
        long loans=s.agents.values().stream().filter(a->a.bookLoans.containsKey(id)).count();
        long readers=s.agents.values().stream().filter(a->a.pending!=null&&"READ_BOOK".equals(a.pending.type)
            &&a.pending.readingOnSite&&id.equals(a.pending.target)).count();
        return (int)Math.max(0,COPIES-loans-readers);
    }
    public static LibraryView view(State s,Resident a) {
        refreshKnowledge(a);
        return new LibraryView(LOCATION,LOAN_DAYS,LOAN_LIMIT,BOOKS.stream().map(b->{
            BookStudy study=a.bookStudies.get(b.id());
            return new BookView(b.id(),b.title(),b.subject(),b.description(),COPIES,available(s,b.id()),
                study==null?0:study.readingMinutes,study==null?0:study.studyMinutes,mastery(b),b.depth(),b.lessonMinutes(),b.lessons().size(),b.prerequisitePoints(),unlocked(a,b));
        }).toList(),a.bookLoans.values().stream().map(loan->{
            BookCatalog.Book b=book(loan.book);
            return new LoanView(b.id(),b.title(),b.subject(),loan.borrowedMinute,loan.dueMinute,loan.dueMinute-s.minute,s.minute>=loan.dueMinute);
        }).sorted(Comparator.comparingLong(LoanView::dueMinute)).toList(),
            a.reasoning.values().stream().sorted(Comparator.comparingLong(Reasoning::minute).reversed()).limit(20).toList(),
            a.creations.values().stream().sorted(Comparator.comparingLong(Creation::minute).reversed()).limit(20).toList());
    }
    private static void atLibrary(State s,Resident a) {
        Engine.at(a,LOCATION);Places.requireOpen(s.buildings.get(LOCATION),s.minute);
    }
    static String borrow(State s,Resident a,String id,Engine.Effects e) {
        BookCatalog.Book b=book(id);atLibrary(s,a);
        Engine.require(!a.bookLoans.containsKey(id),"You already borrowed this book");
        Engine.require(a.bookLoans.values().stream().noneMatch(l->s.minute>=l.dueMinute),"Return your overdue books before borrowing another");
        Engine.require(a.bookLoans.size()<LOAN_LIMIT,"Return a book before borrowing more; the loan limit is three");
        Engine.require(available(s,id)>0,"No copy is available; choose another book or wait for a return");
        BookLoan loan=new BookLoan();loan.book=id;loan.borrowedMinute=s.minute;loan.dueMinute=s.minute+LOAN_DAYS*1440L;
        a.bookLoans.put(id,loan);
        e.facts.add(new Engine.Fact("library",a.id,LOCATION,a.name+" borrowed "+b.title()+"; due on day "+(loan.dueMinute/1440+1)+" at "+NightRoutine.timeOfDay(loan.dueMinute)+"."));
        return "Borrowed "+b.title()+" free of charge; return to the library by world minute "+loan.dueMinute;
    }
    static String returnBook(State s,Resident a,String id,Engine.Effects e) {
        BookCatalog.Book b=book(id);atLibrary(s,a);
        Engine.require(a.bookLoans.containsKey(id),"You have not borrowed this book");
        a.bookLoans.remove(id);
        e.facts.add(new Engine.Fact("library",a.id,LOCATION,a.name+" returned "+b.title()+" to the library."));
        return "Returned "+b.title()+"; learned knowledge is retained";
    }
    static void read(State s,Resident a,String id,int minutes,String actionId,Engine.Effects e) {
        BookCatalog.Book b=book(id);refreshKnowledge(a);
        Engine.require(unlocked(a,b),"This "+b.depth()+" book requires "+b.prerequisitePoints()+" knowledge points in "+b.subject()+"; read introductory or intermediate books first");
        BookLoan loan=a.bookLoans.get(id);
        Engine.require(minutes>=1&&minutes<=100,"Choose one to one hundred minutes of reading");
        Engine.require(a.energy>=5&&a.hunger<95,"Eat or rest before reading");
        boolean onsite=loan==null;
        if(onsite) {
            atLibrary(s,a);Engine.require(available(s,id)>0,"No copy is available to read on site");
        } else {
            Engine.require(s.minute<loan.dueMinute,"This book is overdue; return it to the library");
            Building place=s.buildings.get(a.location);
            Engine.require(a.home.equals(a.location)||place!=null&&place.restAllowed,"Read at home or a place marked restAllowed; finish travelling first");
            Places.requireOpen(place,s.minute);
        }
        Pending p=Engine.pending(actionId,"READ_BOOK",id,s.minute+minutes);p.startedAt=s.minute;p.readingOnSite=onsite;
        Seating.reserve(s,a,p,false);
        a.pending=p;
        e.facts.add(new Engine.Fact("library",a.id,id,a.name+" began reading "+b.title()+" about "+b.subject()+"."));
    }
    static void dueNotices(State s,Resident a,Engine.Effects e) {
        for(BookLoan loan:a.bookLoans.values())if(s.minute>=loan.dueMinute&&!loan.overdueNotified) {
            loan.overdueNotified=true;
            e.facts.add(new Engine.Fact("library",a.id,LOCATION,a.name+" has an overdue book: "+book(loan.book).title()+". Return it before borrowing again."));
        }
    }
    static void tickReading(State s,Resident a,Engine.Effects e) {
        Pending p=a.pending;BookCatalog.Book b=book(p.target);BookLoan loan=a.bookLoans.get(b.id());Building place=s.buildings.get(a.location);
        boolean readablePlace=place!=null&&(a.home.equals(a.location)||place.restAllowed)&&Places.open(place,s.minute-1);
        boolean access=p.readingOnSite?LOCATION.equals(a.location)&&Places.open(s.buildings.get(LOCATION),s.minute-1):
            readablePlace&&loan!=null&&s.minute<=loan.dueMinute;
        if(access&&a.energy>0&&a.hunger<98&&s.minute<=p.until) {
            p.readingMinutes++;
            BookStudy study=a.bookStudies.computeIfAbsent(b.id(),id->new BookStudy());
            SubjectKnowledge knowledge=a.knowledge.computeIfAbsent(b.subject(),id->new SubjectKnowledge());
            study.readingMinutes++;study.lastReadMinute=s.minute;knowledge.readingMinutes++;knowledge.lastStudiedMinute=s.minute;
            if(study.studyMinutes<mastery(b)) {
                int credit=1+(a.studyBoostUntil>=s.minute&&p.readingMinutes%4==0?1:0);
                for(int i=0;i<credit&&study.studyMinutes<mastery(b);i++) {
                    study.studyMinutes++;knowledge.studyMinutes++;
                    if(study.studyMinutes%b.lessonMinutes()==0) {
                        knowledge.concepts.add(b.lessons().get(study.studyMinutes/b.lessonMinutes()-1));
                        refreshKnowledge(a);
                        e.facts.add(new Engine.Fact("learning",a.id,b.id(),a.name+" learned a concept about "+b.subject()+" from "+b.title()+"."));
                    }
                }
            }
        }
        boolean closed=place!=null&&!Places.open(place,s.minute);
        boolean due=!p.readingOnSite&&(loan==null||s.minute>=loan.dueMinute);
        if(!access||closed||due||a.energy<=0||a.hunger>=98||s.minute>=p.until) {
            String reason=closed?"; "+place.name+" has closed":due?"; return the book by its due date":"";
            e.facts.add(new Engine.Fact("library",a.id,b.id(),a.name+" finished reading "+b.title()+" after "+p.readingMinutes+" minutes."));
            Engine.finish(a,e,"Read "+b.title()+" for "+p.readingMinutes+" minutes; "+b.subject()+" knowledge retained"+reason);
        }
    }
}
