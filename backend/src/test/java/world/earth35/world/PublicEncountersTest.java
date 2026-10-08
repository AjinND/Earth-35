package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class PublicEncountersTest {
    private Resident resident(State s,String id,double x,double z) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location="road";a.x=x;a.z=z;s.agents.put(id,a);return a;
    }
    private void walk(Resident a,String id) {
        a.pending=Engine.pending(id,"MOVE","office",0);a.pending.frontDoorRoute=true;
        a.pending.route.add(new double[]{10,2.4});a.pending.travelWeather=Engine.weather(480);a.pending.reviewAfter=495;
    }
    private Engine.Effects act(State s,Resident a,ActionType type,String target,String message,String id) {
        Engine.Effects e=new Engine.Effects();
        Engine.apply(s,a,new Command(id,type,target,message,null,null,null),id,e);return e;
    }
    private PublicCall call(State s,String id) {return s.publicCalls.get(id);}

    @Test void noticingStrangersAndNearPassingNeverMovesOrEngagesEitherResident() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4),c=resident(s,"c",15,2.4);
        walk(a,"a-trip");walk(b,"b-trip");
        Social.Situation social=Social.situation(s,a,Map.of());
        assertEquals(List.of("near_passing","noticed"),social.encounters().stream().map(Social.Encounter::kind).toList());
        assertTrue(social.reconsider());assertFalse(social.encounters().getFirst().metBefore());assertTrue(s.conversations.isEmpty());
        assertNull(a.pending.socialWith);assertNull(b.pending.socialWith);assertEquals(2.4,a.x);assertEquals(4.4,b.x);
        act(s,a,ActionType.CONTINUE_TRAVEL,null,null,"continue");
        assertFalse(Social.situation(s,a,Map.of()).reconsider());assertEquals("a-trip",a.pending.id);assertNull(c.pending);
    }
    @Test void aPublicCallWakesTheListenerDespiteEncounterCooldownAndPreservesBothJourneys() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);walk(a,"a-trip");walk(b,"b-trip");
        act(s,b,ActionType.CONTINUE_TRAVEL,null,null,"review");assertFalse(Social.situation(s,b,Map.of()).reconsider());
        Engine.Effects e=act(s,a,ActionType.CALL_OUT,b.id,"Hey b, a quick hello?","call");
        assertEquals("Hey b, a quick hello?",call(s,"call").message);assertEquals(490,call(s,"call").expires);
        assertTrue(PublicEncounters.decisionNeeded(s,b));assertTrue(Social.situation(s,b,Map.of()).reconsider());
        assertFalse(PublicEncounters.decisionNeeded(s,a));assertEquals(1,e.facts.size());assertEquals("public_call",e.facts.getFirst().kind());
        assertTrue(s.conversations.isEmpty());assertEquals("a-trip",a.pending.id);assertEquals("b-trip",b.pending.id);
        assertNull(a.pending.socialWith);assertNull(b.pending.socialWith);assertTrue(e.completions.isEmpty());
        Social.reviewed(s,b);assertTrue(PublicEncounters.decisionNeeded(s,b),"Routine encounter review cannot consume a heard call");
    }
    @Test void acknowledgingTurnsOnlyTheListenerAndDoesNotAcceptAConversationOrStopWalking() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);walk(a,"a-trip");walk(b,"b-trip");
        act(s,a,ActionType.CALL_OUT,b.id,"Hello there","call");
        Engine.Effects e=act(s,b,ActionType.ACKNOWLEDGE,a.id,"Hi! I'm on my way to work.","ack");
        assertEquals("acknowledged",call(s,"call").status);assertEquals("Hi! I'm on my way to work.",call(s,"call").response);
        assertEquals(Long.valueOf(480),call(s,"call").responseMinute);assertEquals(-Math.PI/2,b.facingHeading,.001);assertEquals(482,b.facingUntil);
        assertNull(a.facingHeading);assertNull(b.pending.socialWith);assertEquals("b-trip",b.pending.id);assertEquals(1,b.pending.route.size());
        assertFalse(PublicEncounters.decisionNeeded(s,b));assertTrue(s.conversations.isEmpty());assertTrue(e.completions.isEmpty());
        assertEquals(1,e.facts.size());assertTrue(e.facts.getFirst().message().endsWith(call(s,"call").response));
        s.minute=482;PublicEncounters.tick(s);assertNull(b.facingHeading);assertEquals(0,b.facingUntil);
    }
    @Test void anActualAcknowledgementOffersTheCallerAFreshVoluntaryChoiceWhileWalking() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);walk(a,"a-trip");walk(b,"b-trip");
        act(s,a,ActionType.CALL_OUT,b.id,"Hello over there!","call");
        assertFalse(Social.situation(s,a,Map.of()).reconsider());
        act(s,b,ActionType.ACKNOWLEDGE,a.id,null,"nod");
        assertTrue(Social.situation(s,a,Map.of()).reconsider());
        assertNull(a.pending.socialWith);assertEquals("a-trip",a.pending.id);assertTrue(s.conversations.isEmpty());
        act(s,a,ActionType.CONTINUE_TRAVEL,null,null,"continue");
        assertFalse(Social.situation(s,a,Map.of()).reconsider());
    }
    @Test void oldResponsesOutsideTheCallObservationWindowDoNotTriggerInvisibleDecisions() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);walk(a,"a-trip");
        act(s,a,ActionType.CALL_OUT,b.id,"Hello","call");act(s,b,ActionType.ACKNOWLEDGE,a.id,null,"nod");
        assertTrue(PublicEncounters.decisionNeeded(s,a));
        s.minute=500;assertTrue(PublicEncounters.calls(s,a).isEmpty());assertFalse(PublicEncounters.decisionNeeded(s,a));
    }
    @Test void aSilentDeclineNeverOffersTheCallerAResponseDecision() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);walk(a,"a-trip");
        act(s,a,ActionType.CALL_OUT,b.id,"Hello","call");
        act(s,b,ActionType.DECLINE_CALL,a.id,null,"silent");
        assertFalse(PublicEncounters.decisionNeeded(s,a));assertFalse(Social.situation(s,a,Map.of()).reconsider());
    }
    @Test void acknowledgementWithoutWordsDoesNotInventSpeechAndASecondResponseIsRejected() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);
        act(s,a,ActionType.CALL_OUT,b.id,"Hello","call");
        Engine.Effects e=act(s,b,ActionType.ACKNOWLEDGE,a.id,null,"ack");
        assertNull(call(s,"call").response);assertTrue(e.facts.getFirst().message().contains("acknowledged"));assertTrue(s.conversations.isEmpty());
        assertThrows(Engine.Invalid.class,()->act(s,b,ActionType.ACKNOWLEDGE,a.id,"Again","again"));
    }
    @Test void decliningAndIgnoringAreSilentChoicesThatPreserveRoutes() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4),c=resident(s,"c",6.4,2.4);walk(b,"b-trip");
        act(s,a,ActionType.CALL_OUT,b.id,"Hi b","first");
        Engine.Effects decline=act(s,b,ActionType.DECLINE_CALL,a.id,null,"decline");
        assertEquals("declined",call(s,"first").status);assertTrue(decline.facts.isEmpty());assertNull(b.facingHeading);
        act(s,c,ActionType.CALL_OUT,b.id,"Good morning","second");
        act(s,b,ActionType.CONTINUE_TRAVEL,null,null,"continue");
        assertEquals("ignored",call(s,"second").status);assertFalse(PublicEncounters.decisionNeeded(s,b));
        assertEquals("b-trip",b.pending.id);assertNull(b.pending.socialWith);assertTrue(s.conversations.isEmpty());
    }
    @Test void aSilentDeclineIsAllowedAfterTheCallerLeavesButWordsRequireHearingRange() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);
        act(s,a,ActionType.CALL_OUT,b.id,"Hello","call");a.x=100;
        assertThrows(Engine.Invalid.class,()->act(s,b,ActionType.DECLINE_CALL,a.id,"Can't chat now","spoken"));
        assertEquals("pending",call(s,"call").status);
        Engine.Effects e=act(s,b,ActionType.DECLINE_CALL,a.id,null,"silent");
        assertEquals("declined",call(s,"call").status);assertTrue(e.facts.isEmpty());
    }
    @Test void callsRespectRangeWallsPrivateRoomsVehiclesAndAvailability() {
        State s=new State();Resident a=resident(s,"a",0,0),b=resident(s,"b",20,0);
        act(s,a,ActionType.CALL_OUT,b.id,"At the boundary","boundary");assertNotNull(call(s,"boundary"));
        State far=new State();a=resident(far,"a",0,0);b=resident(far,"b",20.01,0);assertCannotCall(far,a,b);
        State walls=new State();a=resident(walls,"a",0,0);b=resident(walls,"b",10,0);
        Building home=new Building("home","Home","house","Residential",5,0,1);walls.buildings.put(home.id,home);
        assertFalse(Social.perceivable(walls,a,b));assertCannotCall(walls,a,b);
        a.location=b.location=home.id;assertTrue(Social.perceivable(walls,a,b));assertCannotCall(walls,a,b);
        a.location="road";assertFalse(Social.perceivable(walls,a,b));assertCannotCall(walls,a,b);
        State transit=new State();a=resident(transit,"a",0,0);b=resident(transit,"b",2,0);b.pending=Engine.pending("ride","RIDE_BUS","stop",1000);
        assertFalse(Social.perceivable(transit,a,b));assertCannotCall(transit,a,b);
        b.pending=Engine.pending("work","WORK","office",1000);assertCannotCall(transit,a,b);
        b.pending=null;b.paused=true;assertCannotCall(transit,a,b);
    }
    @Test void aWalkingResidentRemainsInsideUntilTheyActuallyCrossTheDoor() {
        State s=new State();Building cafe=new Building("cafe","Cafe","coffee_shop","Downtown",0,0,20);
        s.buildings.put(cafe.id,cafe);
        Resident exiting=resident(s,"exiting",0,0),reader=resident(s,"reader",0,0),outside=resident(s,"outside",10,0);
        reader.location=cafe.id;walk(exiting,"exit-trip");exiting.pending.exitDoor=new double[]{4,0};
        assertFalse(Social.outdoors(s,exiting));assertTrue(Social.perceivable(s,exiting,reader));
        assertFalse(Social.perceivable(s,exiting,outside));assertCannotCall(s,outside,exiting);
        exiting.x=4;exiting.pending.exitDoor=null;
        assertTrue(Social.outdoors(s,exiting));assertTrue(Social.perceivable(s,exiting,outside));
        assertFalse(Social.perceivable(s,exiting,reader));
        act(s,outside,ActionType.CALL_OUT,exiting.id,"Hello out there!","outside-call");
        assertTrue(PublicEncounters.decisionNeeded(s,exiting));
    }
    private void assertCannotCall(State s,Resident a,Resident b) {
        Engine.Effects e=new Engine.Effects();
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,new Command("bad",ActionType.CALL_OUT,b.id,"Hello",null,null,null),"bad",e));
        assertTrue(s.publicCalls.isEmpty());assertTrue(s.conversations.isEmpty());assertTrue(e.facts.isEmpty());
    }
    @Test void callCooldownExpiryAndResponsesSurviveRestartAndOldWorldsLoad() throws Exception {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);
        act(s,a,ActionType.CALL_OUT,b.id,"Hi","call");ObjectMapper json=new ObjectMapper();
        State restored=json.readValue(json.writeValueAsString(s),State.class);Resident savedA=restored.agents.get("a"),savedB=restored.agents.get("b");
        assertTrue(PublicEncounters.decisionNeeded(restored,savedB));assertEquals("Hi",call(restored,"call").message);
        restored.minute=490;PublicEncounters.tick(restored);assertEquals("expired",call(restored,"call").status);
        assertFalse(PublicEncounters.decisionNeeded(restored,savedB));
        assertThrows(Engine.Invalid.class,()->act(restored,savedA,ActionType.CALL_OUT,savedB.id,"Again","too-soon"));
        restored.minute=510;act(restored,savedA,ActionType.CALL_OUT,savedB.id,"Another greeting","later");
        assertEquals(1,restored.publicCalls.size());assertFalse(restored.publicCalls.containsKey("call"));
        act(restored,savedB,ActionType.ACKNOWLEDGE,savedA.id,"Hello","reply");
        State saved=json.readValue(json.writeValueAsString(restored),State.class);assertEquals("Hello",call(saved,"later").response);
        assertEquals("acknowledged",call(saved,"later").status);assertNotNull(saved.agents.get("b").facingHeading);
        saved.minute=1951;PublicEncounters.tick(saved);assertTrue(saved.publicCalls.isEmpty());assertNull(saved.agents.get("b").facingHeading);
        State old=json.readValue("{\"agents\":{\"old\":{\"id\":\"old\"}}}",State.class);
        assertTrue(old.publicCalls.isEmpty());assertNull(old.agents.get("old").facingHeading);
    }
    @Test void cooldownIsDirectedAndEachPairRetainsOnlyItsLatestCall() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);
        act(s,a,ActionType.CALL_OUT,b.id,"Hello b","outgoing");
        act(s,b,ActionType.CALL_OUT,a.id,"Hello a","reverse");
        assertEquals(2,s.publicCalls.size());assertTrue(PublicEncounters.decisionNeeded(s,a));assertTrue(PublicEncounters.decisionNeeded(s,b));
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.CALL_OUT,b.id,"Again","repeat"));
        s.minute+=30;PublicEncounters.tick(s);
        act(s,a,ActionType.CALL_OUT,b.id,"Another day on this street","new-outgoing");
        assertEquals(2,s.publicCalls.size());assertFalse(s.publicCalls.containsKey("outgoing"));assertTrue(s.publicCalls.containsKey("reverse"));
    }
    @Test void theCallerCannotSeeSilentDecisionsButCanSeeActualAcknowledgementAndSpokenDecline() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4),c=resident(s,"c",6.4,2.4);
        act(s,a,ActionType.CALL_OUT,b.id,"Hi b","silent-decline");
        act(s,b,ActionType.DECLINE_CALL,a.id,null,"decline");
        PublicCall callerView=PublicEncounters.calls(s,a).getFirst(),listenerView=PublicEncounters.calls(s,b).getFirst();
        assertEquals("pending",callerView.status);assertNull(callerView.responseMinute);
        assertEquals("declined",listenerView.status);assertEquals(Long.valueOf(480),listenerView.responseMinute);
        assertEquals("declined",call(s,"silent-decline").status,"Views must not change the authoritative decision");
        act(s,c,ActionType.CALL_OUT,b.id,"Hello b","ignored");act(s,b,ActionType.LOOK,null,null,"look");
        assertEquals("pending",PublicEncounters.calls(s,c).getFirst().status);assertNull(PublicEncounters.calls(s,c).getFirst().responseMinute);
        assertEquals("ignored",call(s,"ignored").status);
        s.minute=490;PublicEncounters.tick(s);
        assertEquals("expired",PublicEncounters.calls(s,a).getFirst().status);assertNull(PublicEncounters.calls(s,a).getFirst().responseMinute);
        s.minute=510;act(s,a,ActionType.CALL_OUT,b.id,"Hello again","acknowledged");
        act(s,b,ActionType.ACKNOWLEDGE,a.id,null,"ack");
        assertEquals("acknowledged",PublicEncounters.calls(s,a).getFirst().status);assertEquals(Long.valueOf(510),PublicEncounters.calls(s,a).getFirst().responseMinute);
        s.minute=540;act(s,a,ActionType.CALL_OUT,b.id,"A quick chat?","spoken-decline");
        act(s,b,ActionType.DECLINE_CALL,a.id,"I need to keep going.","answer");
        assertEquals("declined",PublicEncounters.calls(s,a).getFirst().status);
        assertEquals("I need to keep going.",PublicEncounters.calls(s,a).getFirst().response);
        assertEquals(Long.valueOf(540),PublicEncounters.calls(s,a).getFirst().responseMinute);
    }
    @Test void everyPendingCallRemainsVisibleToItsListenerInACrowd() {
        State s=new State();Resident listener=resident(s,"listener",0,0);
        for(int i=0;i<25;i++) {
            Resident caller=resident(s,"caller-"+i,2+i*.1,0);
            act(s,caller,ActionType.CALL_OUT,listener.id,"Hello from "+i,"call-"+i);
        }
        assertEquals(25,PublicEncounters.calls(s,listener).size());assertTrue(PublicEncounters.decisionNeeded(s,listener));
        assertTrue(PublicEncounters.calls(s,listener).stream().anyMatch(c->c.id.equals("call-0")));
        act(s,listener,ActionType.DECLINE_CALL,"caller-0",null,"decline-oldest");
        assertEquals("declined",call(s,"call-0").status);assertTrue(PublicEncounters.decisionNeeded(s,listener));
    }
    @Test void lookingWhileRestingSilentlyIgnoresACallAndPreservesTheChosenRest() {
        State s=State.prototypeSeed();Building park=s.buildings.get("park");
        Resident a=resident(s,"a",park.x+2,park.z),b=resident(s,"b",park.x,park.z);a.location=b.location=park.id;
        act(s,b,ActionType.REST,null,null,"rest");Pending rest=b.pending;
        act(s,a,ActionType.CALL_OUT,b.id,"Hello from the park","call");assertTrue(PublicEncounters.decisionNeeded(s,b));
        Engine.Effects look=act(s,b,ActionType.LOOK,null,null,"look");
        assertSame(rest,b.pending);assertEquals("REST",b.pending.type);assertEquals(510,b.pending.until);
        assertEquals("ignored",call(s,"call").status);assertFalse(PublicEncounters.decisionNeeded(s,b));
        assertTrue(look.facts.isEmpty());assertTrue(look.completions.isEmpty());assertTrue(s.conversations.isEmpty());
        assertEquals("pending",PublicEncounters.calls(s,a).getFirst().status);
    }
    @Test void anUnsolicitedGreetingAfterReviewOffersAResponseAndCanBeIgnoredWithoutFarewell() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);walk(b,"b-trip");
        act(s,b,ActionType.CONTINUE_TRAVEL,null,null,"review");assertFalse(Social.situation(s,b,Map.of()).reconsider());
        act(s,a,ActionType.TALK,b.id,"Hi, do you know this neighborhood?","greet");
        assertTrue(Social.situation(s,b,Map.of()).reconsider());assertFalse(Conversations.active(s,b));
        act(s,b,ActionType.CONTINUE_TRAVEL,null,null,"keep-going");
        assertEquals("b-trip",b.pending.id);assertNull(b.pending.socialWith);assertFalse(Social.situation(s,b,Map.of()).reconsider());
    }
}
