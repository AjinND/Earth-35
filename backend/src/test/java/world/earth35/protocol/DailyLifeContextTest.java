package world.earth35.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.*;
import world.earth35.world.State.*;

class DailyLifeContextTest {
    private final State state=State.prototypeSeed();
    private final Resident self=new Resident();
    DailyLifeContextTest() {self.id="alex";self.name="Alex";self.home="apt-101";self.location="park";state.minute=720;}
    private AgentService.Observation observation(List<SocialPlan> plans,DailyRoutines.WorkSchedule work) {
        var routes=List.of(new Travel.Option("shortest",25,15,30,List.of(),true,30,1));
        var travel=new Travel.Situation(false,false,false,0,null,null,
            List.of(new Travel.Destination("library",true,routes,List.of(),null,null)));
        return new AgentService.Observation(state.minute,1,"Clear","12:00",false,1800,self,List.of(),
            List.copyOf(state.buildings.values()),List.of(),List.of(),List.of(),List.of(),List.of(),null,List.of(),List.of(),work,travel,
            new Social.Situation(List.of(),plans,false));
    }
    private SocialPlan plan(String id,String status,String proposer,String invited) {
        SocialPlan p=new SocialPlan();p.id=id;p.status=status;p.proposer=proposer;p.invited=invited;
        p.destination="library";p.activity="Discuss our books";p.meetingMinute=750;p.expires=810;return p;
    }
    @Test void onlyActualAcceptedPersonalPlansCreateCommitmentsWithTravelTime() {
        var accepted=plan("meeting","accepted",self.id,"sam");
        var expired=plan("expired","accepted",self.id,"sam");expired.expires=720;
        var obs=observation(List.of(accepted,expired,plan("proposal","proposed",self.id,"sam"),
            plan("others","accepted","sam","maya"),plan("done","completed",self.id,"sam")),null);
        var context=obs.getDailyLife();assertEquals(1,context.upcoming().size());
        var meeting=context.upcoming().getFirst();
        assertEquals("accepted_meeting",meeting.kind());assertEquals(25,meeting.walkingMinutes());
        assertEquals(725,meeting.leaveByMinute());assertEquals(810,meeting.expiresMinute());
        self.location="library";assertEquals(0,obs.getDailyLife().upcoming().getFirst().walkingMinutes());
    }
    @Test void optionalWorkAndSleepStayDistinctFromOverdueBookReturns() {
        self.routine=new DailyRoutine();self.routine.sleepMinute=1380;self.routine.wakeMinute=420;
        BookLoan loan=new BookLoan();loan.book="book";loan.dueMinute=700;self.bookLoans.put(loan.book,loan);
        var work=new DailyRoutines.WorkSchedule("job","office",540,1020,List.of(0),false,1980,1950,0,true,0,3000,false,null);
        var context=observation(List.of(),work).getDailyLife();
        assertEquals(List.of("book_return","preferred_sleep","optional_work"),context.upcoming().stream().map(DailyLifeContext.Commitment::kind).toList());
        assertTrue(context.upcoming().getFirst().description().contains("overdue"));
        assertEquals(1380,context.upcoming().get(1).minute());
        assertNull(context.upcoming().get(1).walkingMinutes());assertNull(context.upcoming().get(1).leaveByMinute());
        assertTrue(context.upcoming().getLast().description().contains("leave today"));
        assertNull(self.pending);assertNull(self.mind.intention);
    }
    @Test void sleepPreferenceHandlesMidnightAndNightPlansWithoutCreatingADirective() {
        self.routine=new DailyRoutine();self.routine.sleepMinute=1380;self.routine.wakeMinute=420;
        state.minute=1500;self.nightPlan=new NightPlan("Watch the sky",1600);
        var sleep=observation(List.of(),null).getDailyLife().upcoming().getFirst();
        assertEquals(1500,sleep.minute());assertTrue(sleep.description().contains("1600"));
        state.minute=1900;assertEquals(2820,observation(List.of(),null).getDailyLife().upcoming().getFirst().minute());
    }
    @Test void needsAndFollowThroughAreGroundedAndDoNotMutateTheResidentsChoices() throws Exception {
        self.lastMealMinute=600;self.lastWakeMinute=420;self.hunger=60;self.arrears=2000;
        self.mind.intention=new Cognition.Intention("Learn","Choose a book","Curiosity","park",600,800);
        String before=new ObjectMapper().writeValueAsString(self);
        var view=observation(List.of(),null).getDailyLife();
        assertEquals(120,view.minutesSinceMeal());assertEquals(300,view.minutesAwake());
        assertTrue(view.considerations().stream().anyMatch(c->c.contains("before a long activity")));
        assertTrue(view.considerations().stream().anyMatch(c->c.contains("next step")));
        assertTrue(view.considerations().stream().anyMatch(c->c.contains("2000")));
        assertEquals(before,new ObjectMapper().writeValueAsString(self));
        self.pending=new Pending();self.pending.type="SLEEP";
        assertNull(observation(List.of(),null).getDailyLife().minutesAwake());
    }
}
