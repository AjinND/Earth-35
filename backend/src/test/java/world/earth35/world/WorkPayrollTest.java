package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class WorkPayrollTest {
    private Resident employee(State s) {
        Resident a=new Resident();a.id="alex";a.name="Alex";a.home="apt-101";a.location="office";a.job="job-office";
        a.x=s.buildings.get("office").x;a.z=s.buildings.get("office").z;s.agents.put(a.id,a);return a;
    }
    private void work(State s,Resident a,String id,boolean extra) {
        Engine.apply(s,a,new Command(id,ActionType.WORK,null,null,extra?"overtime":null,null,null),id,new Engine.Effects());
    }
    private List<Engine.Fact> until(State s,long end) {
        List<Engine.Fact> facts=new ArrayList<>();while(s.minute<end)facts.addAll(Engine.tick(s).facts);return facts;
    }
    private long completions(List<Engine.Fact> facts) {
        return facts.stream().filter(f->f.kind().equals("employment")&&f.message().contains("completed their paid shift")).count();
    }
    private long total(State s) {return s.accounts.values().stream().mapToLong(Long::longValue).sum()+s.agents.values().stream().mapToLong(a->a.money).sum();}

    @Test void consecutiveHoursProduceNoHourlyPayOrPublicCompletionAndPayOnceAtScheduledEnd() {
        State s=State.prototypeSeed();s.minute=540;Resident a=employee(s);long money=a.money,total=total(s);
        for(int i=0;i<8;i++) {
            work(s,a,"work-"+i,false);long end=s.minute+60;
            List<Engine.Fact> facts=until(s,end);
            assertEquals(i==7?1:0,completions(facts));
            assertEquals(i==7?money+16000:money,a.money);
            assertNull(a.pending);assertEquals(total,total(s));
        }
        assertTrue(a.workPayroll.isEmpty());assertEquals(0,completions(until(s,1100)));assertEquals(money+16000,a.money);
    }
    @Test void paySurvivesBreaksLeavingTheWorkplaceAndRestartAndIsNotSpendableEarly() throws Exception {
        State s=State.prototypeSeed();s.minute=600;Resident a=employee(s);a.money=0;
        work(s,a,"morning",false);until(s,660);assertEquals(0,a.money);
        var due=WorkPayroll.view(a).getFirst();assertEquals(2000,due.earnedCents());assertEquals(1020,due.payAtMinute());
        a.location="grocery";a.x=s.buildings.get("grocery").x;a.z=s.buildings.get("grocery").z;
        State before=s;Resident buyer=a;
        assertThrows(Engine.Invalid.class,()->Engine.apply(before,buyer,new Command("buy",ActionType.BUY,"grocery",null,"meal",1,null),"buy",new Engine.Effects()));
        ObjectMapper json=new ObjectMapper();s=json.readValue(json.writeValueAsString(s),State.class);a=s.agents.get("alex");
        a.paused=true;assertEquals(0,completions(until(s,1019)));assertEquals(0,a.money);
        assertEquals(1,completions(until(s,1020)));assertEquals(2000,a.money);
        s=json.readValue(json.writeValueAsString(s),State.class);a=s.agents.get("alex");
        assertEquals(0,completions(until(s,1080)));assertEquals(2000,a.money);
    }
    @Test void earlyOvertimeAndRegularWorkShareOneScheduledPayment() {
        State s=State.prototypeSeed();s.minute=480;Resident a=employee(s);long money=a.money;
        work(s,a,"early",true);assertEquals(0,completions(until(s,540)));
        work(s,a,"regular",false);assertEquals(0,completions(until(s,600)));
        assertEquals(money,a.money);assertEquals(5000,WorkPayroll.view(a).getFirst().earnedCents());
        var facts=until(s,1020);assertEquals(1,completions(facts));assertEquals(money+5000,a.money);
        assertTrue(facts.stream().anyMatch(f->f.message().contains("including overtime")));
    }
    @Test void explicitLateOvertimeDefersWholeUnpaidWorkdayToAccessibleClosing() {
        State s=State.prototypeSeed();s.minute=900;Resident a=employee(s);long money=a.money;
        Building b=s.buildings.get("office");b.openMinute=540;b.closeMinute=1140;
        work(s,a,"regular",false);until(s,960);until(s,990);
        work(s,a,"mixed",true);assertEquals(1140,WorkPayroll.view(a).getFirst().payAtMinute());
        assertEquals(0,completions(until(s,1050)));assertEquals(money,a.money);
        work(s,a,"extra",true);assertEquals(0,completions(until(s,1110)));assertEquals(money,a.money);
        assertEquals(1,completions(until(s,1140)));assertEquals(money+7500,a.money);assertTrue(a.workPayroll.isEmpty());
    }
    @Test void quittingReservesOnlyEarnedPayUntilClosingEvenAfterChangingJobs() {
        State s=State.prototypeSeed();s.minute=600;Resident a=employee(s);long money=a.money,total=total(s);
        long employer=s.accounts.get("office");work(s,a,"work",false);until(s,615);
        var effects=new Engine.Effects();Engine.apply(s,a,new Command("quit",ActionType.QUIT_JOB,null,null,null,null,null),"quit",effects);
        assertEquals(money,a.money);assertEquals(employer-500,s.accounts.get("office"));assertEquals(total,total(s));
        assertEquals(500,WorkPayroll.view(a).getFirst().earnedCents());
        assertFalse(effects.payments.stream().anyMatch(p->p.destination().equals(a.id)));
        a.job="job-studio";a.location="studio";
        assertEquals(1,completions(until(s,1020)));assertEquals(money+500,a.money);assertEquals("job-studio",a.job);
    }
    @Test void inheritedFundedHourIsAccruedWithoutRepayingEarlierLegacyWages() throws Exception {
        State s=State.prototypeSeed();s.minute=600;Resident a=employee(s);long money=a.money;
        work(s,a,"legacy",false);a.pending.payrollId=null;a.pending.payrollCloseMinute=0;a.workPayroll=null;
        a.workedMinutes=240; // Earlier hours may already have been paid by the old engine.
        ObjectMapper json=new ObjectMapper();s=json.readValue(json.writeValueAsString(s),State.class);a=s.agents.get("alex");
        assertEquals(0,completions(until(s,660)));assertEquals(money,a.money);
        assertEquals(2000,WorkPayroll.view(a).getFirst().earnedCents());
        assertEquals(1,completions(until(s,1020)));assertEquals(money+2000,a.money);
        assertFalse(json.writeValueAsString(WorldStore.publicAgent(a)).contains("workPayroll"));
    }
    @Test void lateOvertimeAtAllDayWorkplaceBatchesUntilMidnightAndPaysBeforeRent() {
        State s=State.prototypeSeed();s.minute=1080;Resident a=employee(s);long money=a.money;
        work(s,a,"late1",true);until(s,1140);work(s,a,"late2",true);until(s,1200);
        assertEquals(money,a.money);assertEquals(1440,WorkPayroll.view(a).getFirst().payAtMinute());
        assertEquals(0,completions(until(s,1439)));assertEquals(1,completions(until(s,1440)));
        assertEquals(money+6000-2000,a.money);assertTrue(a.workPayroll.isEmpty());
    }
    @Test void hourAcrossMidnightFinishesBeforeItsWorkdayPayment() {
        State s=State.prototypeSeed();s.minute=1410;Resident a=employee(s);long money=a.money;
        work(s,a,"overnight",true);assertEquals(1470,WorkPayroll.view(a).getFirst().payAtMinute());
        assertEquals(0,completions(until(s,1440)));assertEquals(money-2000,a.money);
        assertEquals(1,completions(until(s,1470)));assertEquals(money+3000-2000,a.money);
        assertEquals(0,completions(until(s,1500)));
    }
    @Test void lateFinalRegularBlockEndsAtScheduledCloseWithoutForcingOvertime() {
        State s=State.prototypeSeed();s.minute=1003;Resident a=employee(s);long money=a.money;
        s.buildings.get("office").openMinute=540;s.buildings.get("office").closeMinute=1020;
        assertTrue(DailyRoutines.workOpen(s.jobs.get(a.job),s.minute));
        assertEquals(1003,DailyRoutines.nextShift(s.jobs.get(a.job),s.minute));
        work(s,a,"final-minutes",false);
        assertEquals(1020,a.pending.until);assertEquals(17,a.pending.workDurationMinutes);
        assertEquals(0,a.pending.overtimeMinutes);assertEquals(566,s.accounts.get("escrow:final-minutes"));
        assertEquals(0,completions(until(s,1019)));assertEquals(money,a.money);
        assertEquals(1,completions(until(s,1020)));assertEquals(money+566,a.money);assertNull(a.pending);
    }
    @Test void quittingAShortFinalBlockRefundsOnlyUnworkedMinutes() {
        State s=State.prototypeSeed();s.minute=990;Resident a=employee(s);long money=a.money,employer=s.accounts.get("office");
        work(s,a,"short",false);until(s,1005);
        Engine.apply(s,a,new Command("quit",ActionType.QUIT_JOB,null,null,null,null,null),"quit",new Engine.Effects());
        assertEquals(money,a.money);assertEquals(15,a.workedMinutes);assertEquals(employer-500,s.accounts.get("office"));
        assertEquals(500,WorkPayroll.view(a).getFirst().earnedCents());
        assertEquals(1,completions(until(s,1020)));assertEquals(money+500,a.money);
    }
}
