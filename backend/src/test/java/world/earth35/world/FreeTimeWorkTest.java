package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class FreeTimeWorkTest {
    Resident resident(State s,String location) {
        Resident a=new Resident();a.id="resident";a.name="Resident";a.home="apt-101";a.location=location;a.job="job-office";
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(a.id,a);return a;
    }
    Command command(ActionType action,String target,String item) {return new Command("request",action,target,null,item,null,null);}
    void act(State s,Resident a,ActionType action,String target,String item,String id) {Engine.apply(s,a,command(action,target,item),id,new Engine.Effects());}
    @Test void freeTimeAndSkippedWorkNeverStartAnAutomaticCommute() {
        State s=State.seed();s.minute=300;Resident a=resident(s,"apt-101");
        a.routine=new DailyRoutine();a.routine.wakeMinute=300;
        assertNull(NightRoutine.defaultAction(s,a,"wake"));
        act(s,a,ActionType.RUN,"park",null,"jog");
        while(a.pending!=null)Engine.tick(s);
        while(s.minute<660)Engine.tick(s);
        assertEquals("park",a.location);assertNull(a.pending);assertEquals(0,a.workedMinutes);
        assertEquals(100000,a.money);
    }
    @Test void earlyShopVisitsRespectFixedHours() {
        State s=State.seed();s.minute=330;Resident a=resident(s,"apt-101");
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.MOVE,"coffee",null,"closed"));
        s.minute=360;act(s,a,ActionType.MOVE,"coffee",null,"open");assertEquals("coffee",a.pending.target);
    }
    @Test void leavePersistsSuppressesTodaysCommuteAndExpiresWithoutPausing() throws Exception {
        State s=State.seed();s.minute=540;Resident a=resident(s,"office");
        act(s,a,ActionType.TAKE_LEAVE,null,null,"leave");
        assertFalse(a.paused);assertNull(a.pending);assertTrue(DailyRoutines.schedule(s,a).onLeave());
        assertFalse(DailyRoutines.schedule(s,a).open());assertEquals(1980,DailyRoutines.schedule(s,a).nextWorkMinute());
        assertNull(Travel.situation(s,a).commute());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.WORK,null,null,"work"));
        var json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);
        Resident saved=restored.agents.get(a.id);assertTrue(DailyRoutines.onLeave(saved,restored.minute));
        act(restored,saved,ActionType.MOVE,"park",null,"explore");assertNotNull(saved.pending);
        restored.minute=1440;assertFalse(DailyRoutines.onLeave(saved,restored.minute));
        act(s,a,ActionType.CANCEL_LEAVE,null,null,"cancel");
        act(s,a,ActionType.WORK,null,null,"return");assertEquals(600,a.pending.until);
    }
    @Test void earlyOvertimeIsOptInAndReservedAndPaidExactlyOnceAfterRestart() throws Exception {
        State s=State.seed();s.minute=480;Resident a=resident(s,"office");long balance=a.money;
        assertTrue(DailyRoutines.schedule(s,a).overtimeAvailable());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.WORK,null,null,"unpaid"));
        assertNull(a.pending);assertFalse(s.accounts.containsKey("escrow:unpaid"));
        act(s,a,ActionType.WORK,null,"overtime","extra");
        assertEquals(3000,s.accounts.get("escrow:extra"));assertEquals(balance,a.money);
        assertEquals(60,a.overtimeMinutes);assertEquals(0,DailyRoutines.regularMinutesToday(a,s.minute));
        var json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);Resident saved=restored.agents.get("resident");
        Engine.Effects completion=null;while(restored.minute<540)completion=Engine.tick(restored);
        assertEquals(balance+3000,saved.money);assertFalse(restored.accounts.containsKey("escrow:extra"));
        assertTrue(completion.payments.stream().anyMatch(p->p.reason().equals("overtime_wage")));
        Engine.tick(restored);assertEquals(balance+3000,saved.money);
        act(restored,saved,ActionType.WORK,null,null,"regular");assertEquals(2000,restored.accounts.get("escrow:regular"));
    }
    @Test void hourCrossingTheShiftBoundaryProratesExtraMinutes() {
        State s=State.seed();s.minute=990;Resident a=resident(s,"office");
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.WORK,null,null,"regular"));
        act(s,a,ActionType.WORK,null,"overtime","mixed");
        assertEquals(2500,s.accounts.get("escrow:mixed"));assertEquals(30,a.overtimeMinutes);
        assertEquals(30,DailyRoutines.regularMinutesToday(a,s.minute));
    }
    @Test void extraHoursDoNotConsumeRegularPayrollOrReduceItsReserve() {
        State s=State.seed();s.minute=480;Resident a=resident(s,"office");long payroll=Economy.remainingPayroll(s,"office");
        s.accounts.put("office",payroll+2999);
        assertFalse(DailyRoutines.schedule(s,a).overtimeAvailable());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.WORK,null,"overtime","poor"));
        assertNull(a.pending);assertEquals(0,a.workedMinutes);assertEquals(payroll+2999,s.accounts.get("office"));
        s.accounts.put("office",payroll+3000);act(s,a,ActionType.WORK,null,"overtime","funded");
        assertEquals(payroll,Economy.remainingPayroll(s,"office"));assertEquals(payroll,s.accounts.get("office"));
    }
    @Test void completedRegularHoursAndWeekendsRequirePaidExtraWork() {
        State s=State.seed();s.minute=600;Resident a=resident(s,"office");a.workDay=0;a.workedMinutes=480;
        assertFalse(DailyRoutines.schedule(s,a).open());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.WORK,null,null,"limit"));
        act(s,a,ActionType.WORK,null,"overtime","extra");assertEquals(3000,s.accounts.get("escrow:extra"));
        a.pending=null;s.minute=5*1440+600;
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.WORK,null,null,"weekend"));
        act(s,a,ActionType.WORK,null,"overtime","weekend-extra");assertEquals(60,a.overtimeMinutes);
    }
    @Test void overtimeCannotOpenAClosedVenueOrRunPastItsClosingTime() {
        State s=State.seed();Resident a=resident(s,"coffee");a.job="job-coffee";
        for(long minute:new long[]{330,1170,1200}) {
            s.minute=minute;assertFalse(DailyRoutines.schedule(s,a).overtimeAvailable());
            assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.WORK,null,"overtime","closed"));
            assertNull(a.pending);
        }
        s.minute=1140;act(s,a,ActionType.WORK,null,"overtime","last-hour");assertEquals(1200,a.pending.until);
    }
}
