package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class EmploymentTest {
    private Resident resident(State s,String id,String location) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=location;
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    private Command command(ActionType action,String target) {return new Command("request",action,target,null,null,null,null);}
    private Engine.Effects act(State s,Resident a,ActionType action,String target,String id) {
        Engine.Effects effects=new Engine.Effects();Engine.apply(s,a,command(action,target),id,effects);return effects;
    }
    private Resident employee(State s) {
        Resident a=resident(s,"alex","office");a.job="job-office";a.occupation="City analyst";return a;
    }

    @Test void resignationIsVoluntaryFromAnyLocationAndFreesThePositionWithoutChoosingANewLivelihood() {
        State s=State.seed();s.minute=600;Resident a=employee(s);s.jobs.get(a.job).capacity=1;
        a.location=a.home;a.leaveDay=0;a.offer="job-studio";a.workPermit=new WorkPermit();a.workPermit.job=a.job;
        a.workDay=0;a.workedMinutes=120;a.overtimeMinutes=30;long money=a.money;
        Engine.Effects effects=act(s,a,ActionType.QUIT_JOB,null,"quit");
        assertEquals("",a.job);assertEquals("",a.offer);assertEquals("Unemployed",a.occupation);
        assertEquals(-1,a.leaveDay);assertNull(a.workPermit);assertNull(a.pending);assertEquals(a.home,a.location);
        assertEquals(money,a.money);assertEquals(120,a.workedMinutes);assertEquals(30,a.overtimeMinutes);
        assertNull(DailyRoutines.schedule(s,a));assertNull(Travel.situation(s,a).commute());
        assertEquals(1,effects.facts.size());assertEquals("employment",effects.facts.getFirst().kind());
        Resident b=resident(s,"sam","office");
        act(s,b,ActionType.APPLY_JOB,"job-office","apply");act(s,b,ActionType.ACCEPT_JOB,"job-office","accept");
        assertEquals("job-office",b.job);assertEquals("",a.job);
    }

    @Test void rejectingAResignationDoesNotChangeEmploymentOrAccountBalances() {
        State s=State.seed();s.minute=600;Resident a=employee(s);long balance=a.money;
        Engine.Effects effects=new Engine.Effects();
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,command(ActionType.QUIT_JOB,"job-studio"),"wrong",effects));
        assertEquals("job-office",a.job);assertTrue(effects.facts.isEmpty());assertTrue(effects.payments.isEmpty());
        a.paused=true;
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.QUIT_JOB,null,"paused"));
        a.paused=false;act(s,a,ActionType.QUIT_JOB,null,"quit");
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.QUIT_JOB,null,"again"));
        assertEquals(balance,a.money);
    }

    @Test void regularShiftResignationSettlesOnlyElapsedWorkAndRefundsTheEmployerExactlyOnce() {
        State s=State.seed();s.minute=600;Resident a=employee(s);long money=a.money,employer=s.accounts.get("office");
        act(s,a,ActionType.WORK,null,"shift");for(int i=0;i<15;i++)Engine.tick(s);
        Engine.Effects quit=act(s,a,ActionType.QUIT_JOB,"job-office","quit");
        assertEquals(money+500,a.money);assertEquals(employer-500,s.accounts.get("office"));
        assertFalse(s.accounts.containsKey("escrow:shift"));assertEquals(15,a.workedMinutes);assertEquals(0,a.overtimeMinutes);
        assertNull(a.pending);assertEquals(1,quit.completions.size());assertEquals("shift",quit.completions.getFirst().id());
        assertEquals("cancelled",quit.completions.getFirst().status());
        assertEquals(1500,quit.payments.stream().filter(p->p.reason().equals("unused_wage_reservation")).findFirst().orElseThrow().amount());
        for(int i=0;i<60;i++)Engine.tick(s);
        assertEquals(money+500,a.money);assertEquals(employer-500,s.accounts.get("office"));
    }

    @Test void overtimeResignationRefundsTheStartupAccountAndPersistsItsSettlement() throws Exception {
        State s=State.seed();s.minute=480;Resident a=employee(s);Job job=s.jobs.get(a.job);
        job.employerAccount="resident-business";s.accounts.put(job.employerAccount,Economy.remainingPayroll(s,job.employerAccount)+3000);
        long money=a.money,employer=s.accounts.get(job.employerAccount);
        Engine.apply(s,a,new Command("overtime",ActionType.WORK,null,null,"overtime",null,null),"extra",new Engine.Effects());
        for(int i=0;i<20;i++)Engine.tick(s);
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);
        Resident saved=restored.agents.get(a.id);act(restored,saved,ActionType.QUIT_JOB,null,"quit");
        assertEquals(money+1000,saved.money);assertEquals(employer-1000,restored.accounts.get(job.employerAccount));
        assertEquals(20,saved.workedMinutes);assertEquals(20,saved.overtimeMinutes);assertNull(saved.pending);
        State settled=json.readValue(json.writeValueAsString(restored),State.class);
        while(settled.minute<550)Engine.tick(settled);
        assertEquals(money+1000,settled.agents.get(a.id).money);assertFalse(settled.accounts.containsKey("escrow:extra"));
    }

    @Test void mixedShiftResignationUsesTheActualMinutesBeforeAndAfterTheScheduledBoundary() {
        for(int elapsed:new int[]{15,45}) {
            State s=State.seed();s.minute=990;Resident a=employee(s);long money=a.money;
            Engine.apply(s,a,new Command("mixed",ActionType.WORK,null,null,"overtime",null,null),"mixed",new Engine.Effects());
            for(int i=0;i<elapsed;i++)Engine.tick(s);
            act(s,a,ActionType.QUIT_JOB,null,"quit");
            assertEquals(money+(elapsed==15?500:1750),a.money);
            assertEquals(elapsed,a.workedMinutes);assertEquals(elapsed==15?0:15,a.overtimeMinutes);
        }
    }

    @Test void quittingImmediatelyReturnsTheEntireReservationAndLegacyShiftsInferTheirStart() {
        State s=State.seed();s.minute=600;Resident a=employee(s);long money=a.money,employer=s.accounts.get("office");
        act(s,a,ActionType.WORK,null,"shift");a.pending.startedAt=0;
        act(s,a,ActionType.QUIT_JOB,null,"quit");
        assertEquals(money,a.money);assertEquals(employer,s.accounts.get("office"));assertEquals(0,a.workedMinutes);
    }

    @Test void resignationKeepsTravelAndConversationsAndLetsTheAgentApplyForADifferentJob() {
        State s=State.seed();s.minute=600;Resident a=employee(s),friend=resident(s,"friend","office");
        Conversations.speak(s,a,friend,"I am considering different work.");
        act(s,a,ActionType.QUIT_JOB,null,"quit");assertTrue(Conversations.active(s,a));
        Engine.apply(s,a,new Command("goodbye",ActionType.END_CHAT,null,"See you later.",null,null,null),"goodbye",new Engine.Effects());
        act(s,a,ActionType.MOVE,"studio","travel");Pending travel=a.pending;
        a.job="job-office";a.occupation="City analyst";
        act(s,a,ActionType.QUIT_JOB,null,"quit-travelling");assertSame(travel,a.pending);
        while(a.pending!=null)Engine.tick(s);
        act(s,a,ActionType.APPLY_JOB,"job-studio","apply");act(s,a,ActionType.ACCEPT_JOB,"job-studio","accept");
        assertEquals("job-studio",a.job);assertEquals("Designer",a.occupation);
    }

    @Test void staleOrOverbookedOffersAndAlreadyEmployedAcceptanceAreRejected() {
        State s=State.seed();s.minute=600;Resident a=employee(s);a.offer="job-office";
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.ACCEPT_JOB,"job-office","employed"));
        act(s,a,ActionType.QUIT_JOB,null,"quit");a.offer="missing";
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.ACCEPT_JOB,"missing","stale"));
        a.offer="job-office";s.jobs.get("job-office").capacity=1;
        Resident b=resident(s,"sam","office");b.job="job-office";
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.ACCEPT_JOB,"job-office","full"));
        assertEquals("",a.job);assertEquals("job-office",a.offer);
    }

    @Test void ordinaryTicksNeverQuitAJobOrAssignANewLivelihood() {
        State s=State.seed();s.minute=600;Resident a=employee(s);a.goals="I am no longer interested in my job";a.energy=10;
        for(int i=0;i<240;i++)Engine.tick(s);
        assertEquals("job-office",a.job);assertEquals("City analyst",a.occupation);assertNull(a.pending);
    }

    @Test void zeroQuantityCannotCreateFreeTradesAfterSoloBusinessesBecomeAvailable() {
        State s=State.seed();s.minute=600;Resident a=resident(s,"alex","grocery");
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,new Command("zero",ActionType.BUY,"grocery",null,"meal",0,null),"zero",new Engine.Effects()));
    }
}
