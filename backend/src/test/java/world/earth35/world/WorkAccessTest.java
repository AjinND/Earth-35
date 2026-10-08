package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class WorkAccessTest {
    State closedOffice() {
        State s=State.seed();s.minute=450;
        Building office=s.buildings.get("office");office.openMinute=540;office.closeMinute=1020;
        return s;
    }
    Resident resident(State s,String id,String location) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=location;a.job="job-office";
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    Engine.Effects act(State s,Resident a,ActionType action,String target,String item,String id) {
        var effects=new Engine.Effects();Engine.apply(s,a,new Command(id,action,target,null,item,null,null),id,effects);return effects;
    }
    @Test void requestEnablesEarlyEntryAndPaidWorkWithoutOpeningToVisitors() {
        State s=closedOffice();Resident a=resident(s,"employee","office"),visitor=resident(s,"visitor","office");visitor.job="";
        Building office=s.buildings.get("office");a.location="road";var door=Places.entrance(office);a.x=door.x();a.z=door.z();
        assertFalse(DailyRoutines.schedule(s,a).overtimeAvailable());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.MOVE,"office",null,"blocked"));
        long money=a.money,employer=s.accounts.get("office");
        var request=act(s,a,ActionType.REQUEST_OVERTIME,null,null,"request");
        assertTrue(request.payments.isEmpty());assertNull(a.pending);assertEquals(money,a.money);assertEquals(employer,s.accounts.get("office"));
        assertTrue(DailyRoutines.schedule(s,a).staffAccess().approved());assertTrue(DailyRoutines.schedule(s,a).overtimeAvailable());
        assertFalse(Places.open(office,s.minute));assertTrue(Places.canEnter(s,a,office,s.minute));assertFalse(Places.canEnter(s,visitor,office,s.minute));
        act(s,a,ActionType.MOVE,"office",null,"commute");while(a.pending!=null)Engine.tick(s);
        assertEquals("office",a.location);
        act(s,a,ActionType.WORK,null,"overtime","early");assertEquals(3000,s.accounts.get("escrow:early"));
        while(a.pending!=null)Engine.tick(s);assertEquals(money+3000,a.money);
    }
    @Test void permissionDoesNotEnableClosedShopPurchases() {
        State s=State.seed();s.minute=300;Resident a=resident(s,"barista","coffee");a.job="job-coffee";
        act(s,a,ActionType.REQUEST_OVERTIME,null,null,"request");
        assertTrue(Places.canEnter(s,a,s.buildings.get("coffee"),s.minute));
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.BUY,"coffee","coffee","purchase"));
        act(s,a,ActionType.WORK,null,"overtime","prep");assertEquals(360,a.pending.until);
    }
    @Test void disabledPolicyTooEarlyLeaveAndLowFundsRejectWithoutEffects() {
        State s=closedOffice();Resident a=resident(s,"employee","apt-101");Job j=s.jobs.get(a.job);
        j.outsideHoursWorkAllowed=false;
        assertFalse(DailyRoutines.schedule(s,a).staffAccess().canRequest());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.REQUEST_OVERTIME,null,null,"disabled"));
        j.outsideHoursWorkAllowed=true;s.minute=400;
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.REQUEST_OVERTIME,null,null,"too-early"));
        s.minute=450;act(s,a,ActionType.TAKE_LEAVE,null,null,"leave");
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.REQUEST_OVERTIME,null,null,"on-leave"));
        act(s,a,ActionType.CANCEL_LEAVE,null,null,"return");s.accounts.put("office",Economy.remainingPayroll(s,"office")+2999);
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.REQUEST_OVERTIME,null,null,"unfunded"));
        assertNull(a.workPermit);assertNull(a.pending);assertEquals(100000,a.money);assertEquals(0,a.workedMinutes);
    }
    @Test void permitsSurviveRestartButCannotBeCopiedToAnotherJobOrUsedOnLeave() throws Exception {
        State s=closedOffice();Resident a=resident(s,"employee","office");
        act(s,a,ActionType.REQUEST_OVERTIME,null,null,"request");var json=new ObjectMapper();
        State restored=json.readValue(json.writeValueAsString(s),State.class);Resident saved=restored.agents.get(a.id);
        assertTrue(Places.canEnter(restored,saved,restored.buildings.get("office"),restored.minute));
        saved.job="job-coffee";assertFalse(Places.canEnter(restored,saved,restored.buildings.get("office"),restored.minute));
        saved.job="job-office";act(restored,saved,ActionType.TAKE_LEAVE,null,null,"leave");assertNull(saved.workPermit);
        act(restored,saved,ActionType.CANCEL_LEAVE,null,null,"cancel");assertFalse(Places.canEnter(restored,saved,restored.buildings.get("office"),restored.minute));
    }
    @Test void expiredPermitAtArrivalLeavesResidentAtTheDoor() {
        State s=closedOffice();Resident a=resident(s,"employee","apt-101");
        act(s,a,ActionType.REQUEST_OVERTIME,null,null,"request");a.workPermit.until=s.minute+2;
        act(s,a,ActionType.MOVE,"office",null,"commute");
        while(a.pending!=null)Engine.tick(s);
        assertNull(a.workPermit);assertEquals("road",a.location);
        var door=Places.entrance(s.buildings.get("office"));assertEquals(door.x(),a.x);assertEquals(door.z(),a.z);
    }
    @Test void wholeHourMustFitAndEmployerFundsAreRecheckedWhenWorkStarts() {
        State s=closedOffice();Resident a=resident(s,"employee","office");
        act(s,a,ActionType.REQUEST_OVERTIME,null,null,"request");a.workPermit.until=s.minute+30;
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.WORK,null,"overtime","too-short"));
        act(s,a,ActionType.REQUEST_OVERTIME,null,null,"renew");s.accounts.put("office",Economy.remainingPayroll(s,"office"));
        assertFalse(DailyRoutines.schedule(s,a).overtimeAvailable());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.WORK,null,"overtime","funds-spent"));
        assertNull(a.pending);assertFalse(s.accounts.containsKey("escrow:funds-spent"));
    }
    @Test void afterHoursAccessEndsAtEmployerBoundary() {
        State s=closedOffice();s.minute=1080;Resident a=resident(s,"employee","office");
        act(s,a,ActionType.REQUEST_OVERTIME,null,null,"evening");assertEquals(1140,a.workPermit.until);
        act(s,a,ActionType.WORK,null,"overtime","extra");while(a.pending!=null)Engine.tick(s);
        assertEquals(103000,a.money);assertNull(a.workPermit);
        assertFalse(DailyRoutines.schedule(s,a).staffAccess().canRequest());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.REQUEST_OVERTIME,null,null,"late"));
    }
    @Test void anApprovedEmployeeCanUseBusAndFinalWalkToAClosedOffice() {
        State s=closedOffice();s.minute=560;Resident a=resident(s,"employee","apt-101");
        Job j=s.jobs.get(a.job);j.startMinute=660;j.endMinute=1140;
        Building office=s.buildings.get("office");office.openMinute=840;office.closeMinute=1140;
        var before=Travel.situation(s,a).destinations().stream().filter(d->d.destination().equals("office")).findFirst().orElseThrow();
        assertTrue(before.transit().isEmpty());
        act(s,a,ActionType.REQUEST_OVERTIME,null,null,"permit");
        var destination=Travel.situation(s,a).destinations().stream().filter(d->d.destination().equals("office")).findFirst().orElseThrow();
        assertFalse(destination.transit().isEmpty());
        long money=a.money;act(s,a,ActionType.TRAVEL_BUS,"office",destination.transit().getFirst().id(),"commute");
        boolean boarded=false;
        for(int i=0;i<300&&a.pending!=null;i++) {
            Engine.tick(s);if(a.pending!=null&&"RIDE_BUS".equals(a.pending.type))boarded=true;
        }
        assertTrue(boarded);assertNull(a.pending);assertEquals("office",a.location);
        assertFalse(Places.open(office,s.minute));assertEquals(money-250,a.money);
    }
}
