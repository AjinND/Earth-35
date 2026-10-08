package world.earth35.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class NightRoutineTest {
    State state(long minute,String location) {
        State s=State.seed();s.minute=minute;
        Resident a=new Resident();a.id="resident";a.name="Alex";a.home="apt-101";a.location=location;a.energy=45;
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(a.id,a);return s;
    }
    Command command(ActionType action,String target) {return new Command("request",action,target,null,null,null,null);}
    @Test void nightWrapsAtMidnightAndEndsAtSix() {
        assertFalse(NightRoutine.isNight(1199));assertTrue(NightRoutine.isNight(1200));
        assertTrue(NightRoutine.isNight(1439));assertTrue(NightRoutine.isNight(1440));
        assertTrue(NightRoutine.isNight(1799));assertFalse(NightRoutine.isNight(1800));
        assertEquals(1800,NightRoutine.nextMorning(1200));assertEquals(1800,NightRoutine.nextMorning(1440));
        assertEquals("03:00",NightRoutine.timeOfDay(1440*20+180));
    }
    @Test void anUnplannedNightReturnsHomeByRoadThenSleepsUntilMorning() {
        State s=state(1200,"park");Resident a=s.agents.get("resident");
        Command home=NightRoutine.resolve(s,a,command(ActionType.LOOK,null));
        assertEquals(ActionType.MOVE,home.action());assertEquals(a.home,home.target());
        Engine.apply(s,a,home,"home",new Engine.Effects());
        assertEquals("road",a.location);assertEquals(s.buildings.get("park").x,a.x);assertEquals(s.buildings.get("park").z,a.z);
        assertNull(NightRoutine.defaultAction(s,a,"pending"));
        for(int i=0;i<400&&a.pending!=null;i++) Engine.tick(s);
        assertEquals(a.home,a.location);
        Engine.Effects effects=new Engine.Effects();
        Engine.apply(s,a,NightRoutine.defaultAction(s,a,"sleep"),"sleep",effects);
        assertEquals("SLEEP",a.pending.type);assertEquals(1800,a.pending.until);
        assertTrue(effects.facts.stream().anyMatch(f->f.message().contains("went to sleep")));
        while(s.minute<1799) Engine.tick(s);
        assertNotNull(a.pending);assertEquals(100,a.energy);
        var morning=Engine.tick(s);assertNull(a.pending);assertEquals(a.home,a.location);
        assertTrue(morning.facts.stream().anyMatch(f->f.message().contains("woke up")));
        assertNull(NightRoutine.defaultAction(s,a,"day"));
    }
    @Test void explicitRestAndActivitiesRemainChoicesWhileTiredIdleResidentsReturnHome() {
        State s=state(1200,"park");Resident a=s.agents.get("resident");
        assertEquals(ActionType.MOVE,NightRoutine.resolve(s,a,command(ActionType.LOOK,null)).action());
        for(ActionType action:new ActionType[]{ActionType.REST,ActionType.SLEEP,ActionType.USE_OBJECT,ActionType.WORK,ActionType.TALK,ActionType.BUY,ActionType.EAT,ActionType.MOVE}) {
            Command plan=command(action,"office");assertSame(plan,NightRoutine.resolve(s,a,plan));
        }
    }
    @Test void declaredNightPlansExpireAndDoNotPersistAcrossNights() {
        State s=state(1200,"park");Resident a=s.agents.get("resident");
        a.nightPlan=new NightPlan("Meet a friend at Central Park",1260);
        Command look=command(ActionType.LOOK,null);assertSame(look,NightRoutine.resolve(s,a,look));
        assertNull(NightRoutine.defaultAction(s,a,"fallback"));
        s.minute=1259;Engine.tick(s);assertNull(a.nightPlan);
        assertEquals(ActionType.MOVE,NightRoutine.defaultAction(s,a,"expired").action());
    }
    @Test void daytimeAndPausedResidentsKeepTheirDecisions() {
        State s=state(720,"park");Resident a=s.agents.get("resident");
        assertNull(NightRoutine.defaultAction(s,a,"day"));
        s.minute=1200;a.paused=true;assertNull(NightRoutine.defaultAction(s,a,"paused"));
    }
    @Test void aWellRestedResidentCanStayAwakeAndStillChooseToSleep() {
        State s=state(1440,"apt-101");Resident a=s.agents.get("resident");a.energy=100;
        assertNull(NightRoutine.defaultAction(s,a,"rested"));
        Engine.apply(s,a,command(ActionType.SLEEP,null),"sleep",new Engine.Effects());
        assertEquals(1800,a.pending.until);
    }
    @Test void daytimeNapsRetainTheirTwoHourDuration() {
        State s=state(720,"apt-101");Resident a=s.agents.get("resident");
        a.energy=100;
        Engine.apply(s,a,command(ActionType.SLEEP,null),"nap",new Engine.Effects());
        assertEquals(840,a.pending.until);
    }
}
