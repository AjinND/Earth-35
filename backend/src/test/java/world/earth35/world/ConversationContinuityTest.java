package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class ConversationContinuityTest {
    @Test void repeatedIntroductionAfterAFarewellIsRejectedWithoutChangingTheThread() throws Exception {
        State s=State.seed();s.minute=17*1440+15*60+30;
        Resident host=resident(s,"Test","bistro"),guest=resident(s,"Maya","bistro");
        String introduction="Hi Maya! Nice to meet you too. I work here as a bistro host. What brings you to the bistro today?";
        act(s,guest,ActionType.TALK,host.id,"Hello! I'm Maya. Is the Garden Quarter family-friendly?",null,"question");
        act(s,host,ActionType.TALK,guest.id,introduction,null,"intro");s.minute+=6;
        String farewell="I appreciate your insights. I should head to the office soon. It was great chatting with you!";
        act(s,guest,ActionType.TALK,host.id,farewell,null,"goodbye");
        var failure=assertThrows(Engine.Invalid.class,()->act(s,host,ActionType.TALK,guest.id,introduction,null,"repeat"));
        assertTrue(failure.getMessage().contains("latest turn"));
        assertEquals(farewell,Conversations.views(s,host,Map.of()).getFirst().turns().getLast().message());
        // Closing, cosmetic edits and a restart must not bypass the loop protection.
        Conversations.end(s,host);
        State saved=new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(s),State.class);
        assertThrows(Engine.Invalid.class,()->act(saved,saved.agents.get(host.id),ActionType.TALK,guest.id,introduction.toUpperCase().replace(" ","  ")+"!!",null,"repeat-after-restart"));
        act(s,guest,ActionType.TALK,host.id,"Thanks! I will head out now.",null,"resume");
        act(s,host,ActionType.END_CHAT,guest.id,"Glad I could help, Maya. Have a good shift! See you around. Take care!",null,"farewell");
        assertEquals("closed",Conversations.views(s,host,Map.of()).getFirst().status());
    }
    @Test void duplicateProtectionIsPerListenerAndAllowsAMessageOnALaterDay() {
        State s=State.seed();Resident a=resident(s,"a","bistro"),b=resident(s,"b","bistro"),c=resident(s,"c","bistro");
        act(s,a,ActionType.TALK,b.id,"Good morning!",null,"one");
        act(s,a,ActionType.TALK,c.id,"Good morning!",null,"two");
        s.minute+=1440;act(s,a,ActionType.TALK,b.id,"Good morning!",null,"tomorrow");
        assertEquals(2,Conversations.views(s,b,Map.of()).getFirst().turns().size());
    }
    private Resident resident(State s,String id,String place) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=place;
        a.x=s.buildings.get(place).x;a.z=s.buildings.get(place).z;s.agents.put(id,a);return a;
    }
    private void act(State s,Resident a,ActionType type,String target,String message,String item,String id) {
        Engine.apply(s,a,new Command(id,type,target,message,item,null,null),id,new Engine.Effects());
    }
    private void arrive(State s,Resident... residents) {
        for(int i=0;i<500&&Arrays.stream(residents).anyMatch(a->a.pending!=null);i++)Engine.tick(s);
        for(Resident a:residents)assertNull(a.pending,"Journey must actually finish");
    }
    @Test void bistroExchangeAndBothJourneysRemainContextAfterLongTravelAndRestart() throws Exception {
        State s=State.seed();s.minute=16*1440+18*60+14;
        Resident a=resident(s,"Test1","bistro"),b=resident(s,"Test","bistro");
        act(s,a,ActionType.TALK,b.id,"Here are the coffees! Ready to head to the Lantern Courtyard?",null,"coffee");
        s.minute+=2;
        act(s,b,ActionType.TALK,a.id,"Ready! Let's head to the Lantern Courtyard.",null,"agreement");
        act(s,a,ActionType.END_CHAT,null,"Let's continue this at the courtyard. See you there!",null,"transition");
        act(s,a,ActionType.MOVE,"courtyard",null,null,"walk-a");
        act(s,b,ActionType.MOVE,"courtyard",null,null,"walk-b");
        assertEquals("closed",Conversations.views(s,a,Map.of()).getFirst().status());
        // A long interruption in travel must not make the earlier exchange disappear.
        s.minute+=65;
        ObjectMapper json=new ObjectMapper();s=json.readValue(json.writeValueAsString(s),State.class);
        a=s.agents.get("Test1");b=s.agents.get("Test");arrive(s,a,b);
        var context=Conversations.views(s,a,Map.of()).getFirst();
        assertTrue(s.minute-context.lastMinute()>60);assertEquals("courtyard",a.location);assertEquals("courtyard",b.location);
        assertEquals(3,context.turns().size());assertEquals("bistro",context.turns().getFirst().location());
        assertTrue(context.turns().get(1).message().contains("Let's head"));
        assertEquals(2,context.travel().size());
        for(var trip:context.travel()) {
            assertEquals("bistro",trip.origin());assertEquals("courtyard",trip.destination());
            assertEquals("completed",trip.status());assertNotNull(trip.arrivedMinute());
            assertTrue(trip.arrivedMinute()>trip.departedMinute());
        }
        // The last speaker can resume after travel, but a second unreplied turn is still rejected.
        act(s,b,ActionType.TALK,a.id,"We've made it! Shall we sit by the lanterns?",null,"resume");
        assertEquals("courtyard",Conversations.views(s,a,Map.of()).getFirst().turns().getLast().location());
        final State arrived=s;final Resident speaker=b,listener=a;
        assertThrows(Engine.Invalid.class,()->act(arrived,speaker,ActionType.TALK,listener.id,"Invented continuation",null,"duplicate"));
        assertEquals(4,Conversations.views(s,a,Map.of()).getFirst().turns().size());
    }
    @Test void rejectedTravelAddsNoJourneyAndChangingDestinationRecordsCancellation() {
        State s=State.seed();Resident a=resident(s,"a","bistro"),b=resident(s,"b","bistro");
        act(s,a,ActionType.TALK,b.id,"Shall we go to the courtyard?",null,"offer");
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.MOVE,"unknown",null,null,"invalid"));
        assertTrue(Conversations.views(s,a,Map.of()).getFirst().travel().isEmpty());
        act(s,a,ActionType.END_CHAT,null,"I should get going. See you later! Bye!",null,"closing");
        act(s,a,ActionType.MOVE,"courtyard",null,null,"courtyard-trip");
        act(s,a,ActionType.MOVE,"park",null,null,"changed-mind");
        var trips=Conversations.views(s,b,Map.of()).getFirst().travel();
        assertEquals("cancelled",trips.getFirst().status());assertNull(trips.getFirst().arrivedMinute());
        assertEquals("park",trips.getLast().destination());assertNull(trips.getLast().arrivedMinute());
        assertEquals(2,Conversations.views(s,b,Map.of()).getFirst().turns().size());
        arrive(s,a);
        trips=Conversations.views(s,b,Map.of()).getFirst().travel();
        assertEquals("cancelled",trips.getFirst().status());assertNull(trips.getFirst().arrivedMinute());
        assertEquals("completed",trips.getLast().status());assertNotNull(trips.getLast().arrivedMinute());
    }
    @Test void remoteMovementsAreNotAddedToOldContactsAndHistoryIsBounded() {
        State s=State.seed();Resident a=resident(s,"a","bistro"),b=resident(s,"b","bistro");
        act(s,a,ActionType.TALK,b.id,"Hello",null,"hello");
        b.x+=20;
        act(s,a,ActionType.MOVE,"courtyard",null,null,"remote");
        assertTrue(Conversations.views(s,b,Map.of()).getFirst().travel().isEmpty());
        b.x=a.x;
        for(int i=0;i<12;i++)act(s,a,ActionType.MOVE,i%2==0?"park":"courtyard",null,null,"trip-"+i);
        assertEquals(8,Conversations.views(s,b,Map.of()).getFirst().travel().size());
        s.minute+=60;
        act(s,a,ActionType.MOVE,"park",null,null,"later");
        assertEquals("trip-11",Conversations.views(s,b,Map.of()).getFirst().travel().getLast().actionId());
    }
    @Test void oldSavedTurnsLoadWithUnknownLocationAndNoInventedTravel() throws Exception {
        var json=new ObjectMapper();
        Conversation old=json.readValue("{\"a\":\"a\",\"b\":\"b\",\"lastSpeaker\":\"a\",\"lastMinute\":480,\"turns\":[{\"speaker\":\"a\",\"message\":\"Hello\",\"minute\":480}]}",Conversation.class);
        assertTrue(old.travel.isEmpty());assertNull(old.turns.getFirst().location());
        assertEquals("Hello",old.turns.getFirst().message());
    }
    @Test void completedSharedPlanStillAccompaniesTheConversationContext() {
        State s=State.seed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        act(s,a,ActionType.TALK,b.id,"Let's continue over coffee",null,"talk");
        act(s,a,ActionType.PROPOSE_PLAN,b.id,"Discuss the weekend","coffee","plan");
        act(s,b,ActionType.ACCEPT_PLAN,"plan",null,null,"accept");
        act(s,a,ActionType.END_CHAT,null,"See you at the coffee shop!",null,"transition");
        act(s,a,ActionType.JOIN_PLAN,"plan",null,null,"join-a");
        act(s,b,ActionType.JOIN_PLAN,"plan",null,null,"join-b");
        arrive(s,a,b);while(s.minute<s.socialPlans.get("plan").meetingMinute)Engine.tick(s);
        assertEquals("completed",Social.situation(s,a,Map.of()).plans().getFirst().status);
        assertEquals(2,Conversations.views(s,a,Map.of()).getFirst().travel().size());
        assertEquals("coffee",Conversations.views(s,a,Map.of()).getFirst().travel().getFirst().destination());
    }
}
