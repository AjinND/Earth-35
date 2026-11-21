package world.earth35.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class RestConversationTest {
    private Resident resident(State s,String id) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location="park";
        Building park=s.buildings.get("park");a.x=park.x;a.z=park.z;s.agents.put(id,a);return a;
    }
    private void act(State s,Resident a,ActionType type,String target,String message,String item) {
        Engine.apply(s,a,new Command(UUID.randomUUID().toString(),type,target,message,item,null,null),UUID.randomUUID().toString(),new Engine.Effects());
    }
    @Test void seatedResidentsCanChooseARealConversationAndPlanWithoutLosingTheirSeats() {
        State s=State.prototypeSeed();s.minute=720;Resident a=resident(s,"alex"),b=resident(s,"sam");
        act(s,a,ActionType.USE_OBJECT,"park-bench",null,null);
        act(s,b,ActionType.USE_OBJECT,"park-bench",null,null);
        Pending aRest=a.pending,bRest=b.pending;Integer aSeat=Seating.seatIndex(a),bSeat=Seating.seatIndex(b);
        Social.reviewed(s,a);Social.reviewed(s,b);
        assertFalse(Social.situation(s,b,Map.of()).reconsider());
        act(s,a,ActionType.TALK,b.id,"Would you like to take a walk after our break?",null);
        assertTrue(Social.situation(s,b,Map.of()).reconsider(),"A real incoming turn must wake a resting listener");
        assertFalse(Conversations.active(s,b),"A greeting alone never commits its listener");
        act(s,b,ActionType.TALK,a.id,"Yes, let's meet at the courtyard after resting.",null);
        assertTrue(Conversations.active(s,b));
        act(s,a,ActionType.PROPOSE_PLAN,b.id,"Walk around the courtyard","courtyard");
        String plan=s.socialPlans.values().iterator().next().id;
        act(s,b,ActionType.ACCEPT_PLAN,plan,null,null);
        assertEquals("accepted",s.socialPlans.get(plan).status);
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.MOVE,"courtyard",null,null));
        act(s,a,ActionType.END_CHAT,b.id,"See you there after the break!",null);
        act(s,b,ActionType.END_CHAT,a.id,"See you there!",null);
        assertSame(aRest,a.pending);assertSame(bRest,b.pending);
        assertEquals(aSeat,Seating.seatIndex(a));assertEquals(bSeat,Seating.seatIndex(b));
        assertNotEquals(aSeat,bSeat);
        assertFalse(Conversations.active(s,a));assertFalse(Conversations.active(s,b));
        for(int i=0;i<60&&("REST".equals(a.pending.type)||"REST".equals(b.pending.type));i++)Engine.tick(s);
        assertEquals("MOVE",a.pending.type);assertEquals("MOVE",b.pending.type);
        assertEquals("courtyard",a.pending.target);assertEquals("courtyard",b.pending.target);
        assertEquals(plan,a.pending.socialPlanId);assertEquals(plan,b.pending.socialPlanId);
        assertNull(Seating.seatIndex(a));assertNull(Seating.seatIndex(b));
    }
    @Test void choosingToIgnoreAnEncounterDoesNotWakeTheResidentAgainImmediately() {
        State s=State.prototypeSeed();s.minute=720;Resident a=resident(s,"alex"),b=resident(s,"sam");
        act(s,b,ActionType.REST,null,null,null);
        assertTrue(Social.situation(s,b,Map.of()).reconsider());
        act(s,b,ActionType.LOOK,null,null,null);
        assertFalse(Social.situation(s,b,Map.of()).reconsider());
        act(s,a,ActionType.TALK,b.id,"Hello!",null);
        assertTrue(Social.situation(s,b,Map.of()).reconsider());
        act(s,b,ActionType.LOOK,null,null,null);
        assertFalse(Social.situation(s,b,Map.of()).reconsider());
        assertFalse(Conversations.active(s,b));assertTrue(b.relationships.isEmpty());
    }
    @Test void sleepingWorkingStudyingAndTransitRemainExclusive() {
        for(String type:List.of("SLEEP","WORK","READ_BOOK","WAIT_BUS","WAIT_RAIN","RIDE_BUS")) {
            State s=State.prototypeSeed();Resident a=resident(s,"alex"),b=resident(s,"sam");
            a.pending=new Pending();a.pending.type=type;a.pending.id="original";
            assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.TALK,b.id,"Hi",null),type);
            assertEquals("original",a.pending.id);assertTrue(s.conversations.isEmpty());
        }
    }
    @Test void waitingListenersAreUnavailableForTalkButCanStillHearPublicCalls() {
        State s=State.prototypeSeed();Resident a=resident(s,"alex"),b=resident(s,"sam");
        b.pending=new Pending();b.pending.type="WAIT_BUS";b.pending.id="wait";
        assertFalse(Social.encounters(s,a,Map.of()).getFirst().available());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.TALK,b.id,"Hello",null));
        act(s,a,ActionType.CALL_OUT,b.id,"Hello over there",null);
        assertEquals(1,s.publicCalls.size());assertEquals("wait",b.pending.id);
        act(s,b,ActionType.ACKNOWLEDGE,a.id,"Hello! Waiting for my bus.",null);
        assertEquals("wait",b.pending.id);assertTrue(s.conversations.isEmpty());
    }
}
