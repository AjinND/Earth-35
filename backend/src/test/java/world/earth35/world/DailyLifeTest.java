package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class DailyLifeTest {
    private Resident resident(State s) {
        Resident a=new Resident();a.id="alex";a.name="Alex";a.home="apt-101";a.location="park";
        a.x=s.buildings.get("park").x;a.z=s.buildings.get("park").z;s.agents.put(a.id,a);return a;
    }
    @Test void actualRestAccumulatesAcrossTicksAndOutlivesTheReceiptWindow() {
        State s=State.prototypeSeed();s.minute=720;Resident a=resident(s);
        Engine.apply(s,a,new Command("rest",ActionType.REST,null,null,null,null,null),"rest",new Engine.Effects());
        Pending rest=a.pending;long started=s.minute;
        Cognition.action(a,s.minute,"rest","REST",null,"accepted","Resting");
        for(int i=0;i<60&&a.pending!=null;i++)Cognition.complete(s,Engine.tick(s));
        assertEquals(30,rest.activeMinutes);int elapsed=(int)(s.minute-started);
        for(int i=0;i<30;i++)Cognition.action(a,s.minute,"look-"+i,"LOOK",null,"completed","Observed");
        assertTrue(a.mind.outcomes.stream().noneMatch(o->o.id().equals("rest")));
        var day=DailyLife.view(a,s.minute).recentDays().getFirst();
        assertEquals(elapsed,day.observedMinutes());assertEquals(elapsed,day.activityMinutes().get("REST"));
        assertEquals(Map.of("REST",1),day.completedActions());
        assertEquals("UNSCHEDULED",DailyLife.view(a,s.minute).currentActivity());
        assertEquals(0,DailyLife.view(a,s.minute).currentStretchMinutes());
    }
    @Test void interruptedAndRejectedActionsAreNotCompletedAndCompletionIsIdempotent() {
        State s=State.prototypeSeed();s.minute=720;Resident a=resident(s);
        a.pending=new Pending();a.pending.id="trip";a.pending.type="MOVE";
        Cognition.action(a,s.minute,"trip","MOVE","library","accepted","Walking");
        DailyLife.tick(a,721);DailyLife.tick(a,721);
        var effects=new Engine.Effects();effects.completions.add(new Engine.Completion("trip","cancelled","Changed direction"));
        s.minute=721;Cognition.complete(s,effects);
        Cognition.action(a,721,"bad","BUY","grocery","rejected","Not here");
        assertEquals(1,DailyLife.view(a,721).recentDays().getFirst().observedMinutes());
        assertTrue(DailyLife.view(a,721).recentDays().getFirst().completedActions().isEmpty());
        Cognition.action(a,721,"rest","REST",null,"accepted","Resting");
        effects=new Engine.Effects();effects.completions.add(new Engine.Completion("rest","completed","Rested"));
        Cognition.complete(s,effects);Cognition.complete(s,effects);
        assertEquals(Map.of("REST",1),DailyLife.view(a,721).recentDays().getFirst().completedActions());
    }
    @Test void midnightSplitsElapsedTimeAndReadOnlyViewsNeverFillUnknownHistory() throws Exception {
        Resident a=new Resident();a.pending=new Pending();a.pending.type="SLEEP";
        DailyLife.tick(a,1440);DailyLife.tick(a,1441);
        var view=DailyLife.view(a,1441);
        assertEquals(1439,view.trackedSince());assertEquals(2,view.currentStretchMinutes());
        assertEquals(List.of(1L,0L),view.recentDays().stream().map(DailyLife.DayView::day).toList());
        assertTrue(view.recentDays().stream().allMatch(d->d.observedMinutes()==1));
        var json=new ObjectMapper();String before=json.writeValueAsString(a);
        DailyLife.view(a,9999);DailyLife.view(a,9999);
        assertEquals(before,json.writeValueAsString(a));
        DailyLife.tick(a,5000);
        assertEquals(1,DailyLife.view(a,5000).currentStretchMinutes());
        assertEquals(1,DailyLife.view(a,5000).recentDays().getFirst().observedMinutes());
    }
    @Test void historySurvivesRestartAndHasSevenDayBoundWithNoInventedLegacyActivity() throws Exception {
        ObjectMapper json=new ObjectMapper();Resident a=json.readValue("{\"id\":\"old\",\"mind\":null}",Resident.class);
        assertNull(DailyLife.view(a,700).trackedSince());assertTrue(DailyLife.view(a,700).recentDays().isEmpty());
        for(int i=0;i<20;i++)DailyLife.tick(a,i*1440+1);
        assertEquals(7,a.mind.dailyLife.days.size());
        String serialized=json.writeValueAsString(a);Resident restored=json.readValue(serialized,Resident.class);
        assertEquals(DailyLife.view(a,19*1440+1),DailyLife.view(restored,19*1440+1));
        assertFalse(json.writeValueAsString(WorldStore.publicAgent(a)).contains("dailyLife"));
    }
    @Test void pausedParticipationDoesNotInventLeisureOrStopAPhysicalActivity() {
        Resident a=new Resident();a.paused=true;DailyLife.tick(a,1);
        a.pending=new Pending();a.pending.type="SLEEP";DailyLife.tick(a,2);
        assertEquals(Map.of("PAUSED",1,"SLEEP",1),DailyLife.view(a,2).recentDays().getFirst().activityMinutes());
        assertEquals(1,DailyLife.view(a,2).currentStretchMinutes());
    }
    @Test void inheritedActivityWithoutItsCommandReceiptKeepsPartialCoverageAndFreshReceiptsSurviveRestart() throws Exception {
        ObjectMapper json=new ObjectMapper();State s=State.prototypeSeed();s.minute=720;
        Resident a=json.readValue("""
            {"id":"legacy","name":"Alex","home":"apt-101","location":"park",
             "pending":{"id":"unknown-command","type":"REST","until":721}}
            """,Resident.class);
        s.agents.put(a.id,a);
        Cognition.complete(s,Engine.tick(s));
        var inherited=DailyLife.view(a,s.minute).recentDays().getFirst();
        assertEquals(Map.of("REST",1),inherited.activityMinutes());
        assertTrue(inherited.completedActions().isEmpty());assertNull(a.pending);
        // REST could have come from USE_OBJECT; retain missing provenance rather than guessing.
        a.x=s.buildings.get("park").x;a.z=s.buildings.get("park").z;
        Engine.apply(s,a,new Command("fresh",ActionType.REST,null,null,null,null,null),"fresh",new Engine.Effects());
        Cognition.action(a,s.minute,"fresh","REST",null,"accepted","Resting");
        for(int i=0;i<10;i++)Cognition.complete(s,Engine.tick(s));
        s=json.readValue(json.writeValueAsString(s),State.class);a=s.agents.get(a.id);
        Pending fresh=a.pending;
        for(int i=0;i<50&&a.pending!=null;i++)Cognition.complete(s,Engine.tick(s));
        assertEquals(30,fresh.activeMinutes);
        var recorded=DailyLife.view(a,s.minute).recentDays().getFirst();
        assertEquals(Map.of("REST",(int)(s.minute-720)),recorded.activityMinutes());
        assertEquals(Map.of("REST",1),recorded.completedActions());
    }
}
