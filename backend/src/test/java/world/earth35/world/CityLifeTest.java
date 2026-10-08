package world.earth35.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class CityLifeTest {
    Resident resident(State s,String id,String place) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=place;
        Building b=s.buildings.get(place);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    Command talk(String target,String message) {return new Command("request",ActionType.TALK,target,message,null,null,null);}
    Command cmd(ActionType type,String target,String item) {return new Command("request",type,target,type==ActionType.TALK?"Hello":type==ActionType.END_CHAT?"Talk soon! Take care.":null,item,null,null);}
    @Test void silenceNeverBecomesAReplyAndRealRepliesUnlockTheNextTurn() {
        State s=State.seed();Resident a=resident(s,"maya","park"),b=resident(s,"test","park");
        Engine.apply(s,a,cmd(ActionType.TALK,b.id,null),"hello",new Engine.Effects());
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.TALK,b.id,null),"followup",new Engine.Effects()));
        assertEquals("waiting_for_reply",Conversations.views(s,a,java.util.Map.of()).getFirst().status());
        Engine.apply(s,b,cmd(ActionType.TALK,a.id,null),"reply",new Engine.Effects());
        Engine.apply(s,a,talk(b.id,"Thanks! Shall we take a walk?"),"next",new Engine.Effects());
        assertEquals(3,s.conversations.values().iterator().next().turns.size());
    }
    @Test void silenceClosesAnOldChatWithoutInventingAReplyAndPermitsAFreshGreeting() {
        State s=State.seed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        Engine.apply(s,a,cmd(ActionType.TALK,b.id,null),"hello",new Engine.Effects());s.minute+=60;
        assertEquals("closed",Conversations.views(s,a,java.util.Map.of()).getFirst().status());
        assertEquals(1,s.conversations.values().iterator().next().turns.size());
        Engine.apply(s,a,talk(b.id,"Good to see you again. How was your afternoon?"),"new-greeting",new Engine.Effects());
        assertEquals(2,s.conversations.values().iterator().next().turns.size());
        assertEquals("waiting_for_reply",Conversations.views(s,a,java.util.Map.of()).getFirst().status());
    }
    @Test void pausedAndSleepingResidentsCannotBeTalkedTo() {
        State s=State.seed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");b.paused=true;
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.TALK,b.id,null),"x",new Engine.Effects()));
        b.paused=false;b.pending=new Pending();b.pending.type="SLEEP";
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.TALK,b.id,null),"x",new Engine.Effects()));
        assertTrue(s.conversations.isEmpty());
    }
    @Test void eitherParticipantCanEndTheChatAndTheLastSpeakerCanStartFreshAtTheSamePlace() {
        for(String endingResident:new String[]{"a","b"}) {
            State s=State.seed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
            Engine.apply(s,a,cmd(ActionType.TALK,b.id,null),"hello",new Engine.Effects());
            Engine.apply(s,b,cmd(ActionType.TALK,a.id,null),"reply",new Engine.Effects());
            Engine.apply(s,s.agents.get(endingResident),cmd(ActionType.END_CHAT,null,null),"end",new Engine.Effects());
            assertEquals("closed",Conversations.views(s,b,java.util.Map.of()).getFirst().status());
            assertEquals("closed",Social.encounters(s,b,java.util.Map.of()).getFirst().conversationStatus());
            Engine.apply(s,b,talk(a.id,"Shall we discuss our plans for tomorrow?"),"fresh",new Engine.Effects());
            assertEquals(4,s.conversations.values().iterator().next().turns.size());
            assertThrows(Engine.Invalid.class,()->Engine.apply(s,b,cmd(ActionType.TALK,a.id,null),"duplicate",new Engine.Effects()));
        }
    }
    @Test void travellingTogetherEndsTheOldChatBeforeEitherResidentStartsAnother() {
        State s=State.seed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        Engine.apply(s,a,cmd(ActionType.TALK,b.id,null),"hello",new Engine.Effects());
        Engine.apply(s,b,cmd(ActionType.TALK,a.id,null),"reply",new Engine.Effects());
        Engine.apply(s,a,cmd(ActionType.END_CHAT,null,null),"closing",new Engine.Effects());
        Engine.apply(s,a,cmd(ActionType.MOVE,"office",null),"walk-a",new Engine.Effects());
        Engine.apply(s,b,cmd(ActionType.MOVE,"office",null),"walk-b",new Engine.Effects());
        assertEquals("closed",Conversations.views(s,b,java.util.Map.of()).getFirst().status());
        for(int i=0;i<500&&(a.pending!=null||b.pending!=null);i++)Engine.tick(s);
        assertEquals("office",a.location);assertEquals("office",b.location);
        assertTrue(s.minute-s.conversations.values().iterator().next().lastMinute<60);
        Engine.apply(s,b,talk(a.id,"Shall we discuss our plans for tomorrow?"),"fresh",new Engine.Effects());
        assertEquals(4,s.conversations.values().iterator().next().turns.size());
    }
    @Test void anotherActivityNeedsAClosingButObservationAndRejectedTravelDoNot() {
        State s=State.seed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        Engine.apply(s,a,cmd(ActionType.TALK,b.id,null),"hello",new Engine.Effects());
        Engine.apply(s,a,cmd(ActionType.LOOK,null,null),"look",new Engine.Effects());
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.MOVE,"unknown",null),"bad-trip",new Engine.Effects()));
        assertEquals("waiting_for_reply",Conversations.views(s,a,java.util.Map.of()).getFirst().status());
        Engine.apply(s,b,talk(a.id,"Hello, I have a moment to chat."),"reply",new Engine.Effects());
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,b,cmd(ActionType.REST,null,null),"abrupt-rest",new Engine.Effects()));
        Engine.apply(s,b,cmd(ActionType.END_CHAT,null,null),"closing",new Engine.Effects());
        Engine.apply(s,b,cmd(ActionType.REST,null,null),"rest",new Engine.Effects());
        assertEquals("closed",Conversations.views(s,a,java.util.Map.of()).getFirst().status());
        while(b.pending!=null)Engine.tick(s);
        Engine.apply(s,a,talk(b.id,"Shall we discuss our plans for tomorrow?"),"fresh",new Engine.Effects());
        assertEquals(4,s.conversations.values().iterator().next().turns.size());
    }
    @Test void closureSurvivesRestartAndLegacyChatsStillRequireRealReplies() throws Exception {
        State s=State.seed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        Engine.apply(s,b,cmd(ActionType.TALK,a.id,null),"hello",new Engine.Effects());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        String legacy=json.writeValueAsString(s).replace("\"closed\":false,", "");
        State restored=json.readValue(legacy,State.class);
        assertEquals("waiting_for_reply",Conversations.views(restored,restored.agents.get("b"),java.util.Map.of()).getFirst().status());
        Engine.apply(s,a,cmd(ActionType.END_CHAT,null,null),"end",new Engine.Effects());
        restored=json.readValue(json.writeValueAsString(s),State.class);
        Engine.apply(restored,restored.agents.get("b"),talk(a.id,"Shall we discuss our plans for tomorrow?"),"fresh",new Engine.Effects());
        assertEquals(3,restored.conversations.values().iterator().next().turns.size());
    }
    @Test void separationClosesTheChatEvenIfResidentsMeetAgainBeforeTheTimeout() {
        State s=State.seed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        Engine.apply(s,b,cmd(ActionType.TALK,a.id,null),"hello",new Engine.Effects());
        b.x+=11;Engine.tick(s);b.x=a.x;
        assertEquals("closed",Conversations.views(s,b,java.util.Map.of()).getFirst().status());
        Engine.apply(s,b,talk(a.id,"Shall we discuss our plans for tomorrow?"),"fresh",new Engine.Effects());
        assertEquals(2,s.conversations.values().iterator().next().turns.size());
    }
    @Test void earlyAndLatePersonalitiesKeepDistinctPersistentSleepWindows() {
        State s=State.seed();Resident early=resident(s,"early","apt-101"),late=resident(s,"late","apt-101");
        early.personality="Early bird, athletic runner";late.personality="Night owl who sleeps in";
        early.routine=DailyRoutines.derive(early);late.routine=DailyRoutines.derive(late);
        assertTrue(early.routine.wakeMinute<late.routine.wakeMinute);assertEquals("jogging",early.routine.morningActivity);
        assertEquals(early.routine.sleepMinute,DailyRoutines.derive(early).sleepMinute);
        s.minute=1440+early.routine.wakeMinute;
        assertFalse(DailyRoutines.sleepingTime(early,s.minute));assertTrue(DailyRoutines.sleepingTime(late,s.minute));
        s.minute=1439;Engine.apply(s,early,cmd(ActionType.SLEEP,null,null),"sleep",new Engine.Effects());
        assertEquals(1440+early.routine.wakeMinute,early.pending.until);
    }
    @Test void officeRejectsEarlyWeekendAndAfterHoursWorkWithoutReservingWages() {
        State s=State.seed();Resident a=resident(s,"a","office");a.job="job-office";
        long money=s.accounts.get("office");
        for(long minute:new long[]{390,1020,5*1440+600}) {
            s.minute=minute;assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.WORK,null,null),"x",new Engine.Effects()));
            assertEquals(money,s.accounts.get("office"));assertNull(a.pending);
        }
        s.minute=540;Engine.apply(s,a,cmd(ActionType.WORK,null,null),"work",new Engine.Effects());assertEquals(600,a.pending.until);
    }
    @Test void pedestriansUseEmptyCrossingsAndCanClearTheRoadAfterWalkEnds() {
        assertTrue(Traffic.canWalk(480,-2,2.4,-1.5,2.4));
        assertTrue(Traffic.canWalk(488,-2,2.4,-1.5,2.4));
        assertTrue(Traffic.canWalk(480,-1,2.4,0,2.4));
        assertEquals("clearance",Traffic.green(491));
    }
    @Test void busReservationsFollowRoutesTimingFareAndActualVehiclePosition() {
        State s=State.seed();s.minute=599;Resident a=resident(s,"a","stop-residential");long money=a.money;
        Engine.Effects effects=new Engine.Effects();
        Engine.apply(s,a,cmd(ActionType.WAIT_BUS,"R1","stop-park"),"ride",effects);
        assertEquals("WAIT_BUS",a.pending.type);assertEquals(money,a.money);
        long arrival=a.pending.until;String bus=a.pending.vehicleId;
        long boarding=a.pending.boardingMinute;
        while(s.minute<boarding)Engine.tick(s);
        for(int i=0;i<10&&a.pending.type.equals("WAIT_BUS");i++)Engine.tick(s);
        assertEquals("BOARD_BUS",a.pending.type);assertEquals(money,a.money);
        while(a.pending.type.equals("BOARD_BUS"))Engine.tick(s);
        assertEquals("RIDE_BUS",a.pending.type);assertEquals(money-250,a.money);
        Engine.tick(s);
        Traffic.Vehicle actual=Traffic.vehicles(s).stream().filter(v->v.id().equals(bus)).findFirst().orElseThrow();
        assertEquals(actual.x(),a.x,.001);
        while(s.minute<arrival)Engine.tick(s);
        assertEquals("stop-park",a.location);assertNull(a.pending);assertEquals(money-250,a.money);assertEquals(250,s.accounts.get("transit"));
    }
    @Test void arbitraryBusesWrongDirectionsFullServicesAndMissedTripsAreRejected() {
        State s=State.seed();s.minute=600;Resident a=resident(s,"a","stop-downtown");
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.BOARD_BUS,"bus:R1:0:600","stop-park"),"x",new Engine.Effects()));
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.WAIT_BUS,"R1","stop-residential-bogus"),"x",new Engine.Effects()));
        // A clockwise bus has already passed Garden before reaching the final Residential stop.
        s.minute=1439;assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.WAIT_BUS,"R1","stop-garden"),"x",new Engine.Effects()));
        s.minute=600;a.location="stop-residential";
        var platform=Traffic.platform(s,a.location,"R2");a.x=platform.x();a.z=platform.z();
        for(int i=0;i<20;i++) {
            Resident passenger=resident(s,"p"+i,"stop-residential");
            Engine.apply(s,passenger,cmd(ActionType.WAIT_BUS,"R1","stop-market"),"p"+i,new Engine.Effects());
        }
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.WAIT_BUS,"R1","stop-market"),"x",new Engine.Effects()));
        assertNull(a.pending);
    }
    @Test void aBusCannotReturnToAStopItAlreadyPassedOnTheSameService() {
        State s=State.seed();Resident a=resident(s,"a","stop-market");
        Traffic.Trip t=Traffic.trip(s,"bus:R1:0:600");s.minute=t.arrivals().get(2);
        var curb=Traffic.platform(s,a.location,"R1");a.x=curb.x();a.z=curb.z();
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,cmd(ActionType.BOARD_BUS,t.id(),"stop-downtown"),"wrong-direction",new Engine.Effects()));
        Engine.apply(s,a,cmd(ActionType.BOARD_BUS,t.id(),"stop-park"),"right-direction",new Engine.Effects());
        assertEquals("BOARD_BUS",a.pending.type);
    }
    @Test void movementCrossesAnEmptyRoadWithoutWaitingForWalk() {
        State s=State.prototypeSeed();Resident a=resident(s,"a","park");a.x=-2;a.z=2.4;
        Engine.apply(s,a,cmd(ActionType.MOVE,"office",null),"move",new Engine.Effects());
        Engine.tick(s);
        assertTrue(a.x>-1.6);assertNull(a.pending.waitingReason);
        for(int i=0;i<300&&a.pending!=null;i++)Engine.tick(s);
        assertEquals("office",a.location);assertNull(a.pending);
    }
    @Test void aPendingBusTripAndConversationTurnSurviveSerialization() throws Exception {
        State s=State.seed();s.minute=599;Resident a=resident(s,"a","stop-residential"),b=resident(s,"b","stop-residential");
        Engine.apply(s,a,cmd(ActionType.TALK,b.id,null),"talk",new Engine.Effects());a.routine=DailyRoutines.derive(a);
        Engine.apply(s,a,cmd(ActionType.END_CHAT,null,null),"closing",new Engine.Effects());
        Engine.apply(s,a,cmd(ActionType.WAIT_BUS,"R1","stop-park"),"ride",new Engine.Effects());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);
        Resident rider=restored.agents.get("a");long until=rider.pending.until;
        while(restored.minute<until)Engine.tick(restored);
        assertNull(rider.pending);assertEquals("stop-park",rider.location);assertEquals(99750,rider.money);
        assertEquals(a.routine.wakeMinute,rider.routine.wakeMinute);
        assertEquals("a",restored.conversations.values().iterator().next().lastSpeaker);
    }
}
