package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class ConversationConsentTest {
    private Resident resident(State s,String id) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location="park";
        a.x=s.buildings.get("park").x;a.z=s.buildings.get("park").z;s.agents.put(id,a);return a;
    }
    private void act(State s,Resident a,ActionType type,String target,String message) {
        Engine.apply(s,a,new Command(UUID.randomUUID().toString(),type,target,message,null,null,null),"action",new Engine.Effects());
    }
    @Test void anUnsolicitedGreetingNeverRequiresTheListenerToSayGoodbye() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        act(s,a,ActionType.TALK,b.id,"Hello! Do you have a minute?");
        assertTrue(Conversations.active(s,a));assertFalse(Conversations.active(s,b));
        act(s,b,ActionType.MOVE,"office",null);
        assertNotNull(b.pending);assertEquals("MOVE",b.pending.type);
        assertEquals(1,s.conversations.get(Conversations.key(a.id,b.id)).turns.size());
    }
    @Test void aWalkingListenerCanKeepTheirJourneyAndReceiptWithoutAnswering() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        a.location=b.location="road";a.x=b.x=2.4;a.z=b.z=2.4;
        b.pending=Engine.pending("original-trip","MOVE","office",0);
        b.pending.route.add(new double[]{10,2.4});
        act(s,a,ActionType.TALK,b.id,"Nice weather for a walk!");
        act(s,b,ActionType.CONTINUE_TRAVEL,null,null);
        assertEquals("original-trip",b.pending.id);assertNull(b.pending.socialWith);
        assertEquals(1,s.conversations.get(Conversations.key(a.id,b.id)).turns.size());
    }
    @Test void choosingToReplyEngagesBothResidentsAndPersists() throws Exception {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        act(s,a,ActionType.TALK,b.id,"Hello! Do you have a minute?");
        act(s,b,ActionType.TALK,a.id,"Yes, I can stop for a chat.");
        assertTrue(Conversations.active(s,a));assertTrue(Conversations.active(s,b));
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);
        assertThrows(Engine.Invalid.class,()->act(restored,restored.agents.get("b"),ActionType.MOVE,"office",null));
        act(restored,restored.agents.get("b"),ActionType.END_CHAT,a.id,"I need to go. See you around!");
        act(restored,restored.agents.get("b"),ActionType.MOVE,"office",null);
    }
    @Test void legacyUnilateralGreetingsDoNotInventListenerConsent() throws Exception {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        act(s,a,ActionType.TALK,b.id,"Hello! Do you have a minute?");
        ObjectMapper json=new ObjectMapper();var data=json.valueToTree(s);
        var legacy=(com.fasterxml.jackson.databind.node.ObjectNode)data.path("conversations").path(Conversations.key(a.id,b.id));
        legacy.remove(List.of("initiator","engaged","turnSequence"));
        State restored=json.treeToValue(data,State.class);
        assertTrue(Conversations.active(restored,restored.agents.get(a.id)));
        assertFalse(Conversations.active(restored,restored.agents.get(b.id)));
        act(restored,restored.agents.get(b.id),ActionType.MOVE,"office",null);
    }
    @Test void privateDeparturesBehindWallsNeverBecomeWitnessedTravel() {
        State s=new State();Resident a=new Resident(),b=new Resident();
        a.id="a";b.id="b";a.location="road";b.location="home";a.x=0;b.x=5;
        s.agents.put(a.id,a);s.agents.put(b.id,b);
        s.buildings.put("home",new Building("home","Home","house","Residential",5,0,1));
        Conversation chat=new Conversation();chat.a=a.id;chat.b=b.id;chat.lastMinute=s.minute;
        s.conversations.put(Conversations.key(a.id,b.id),chat);
        b.pending=Engine.pending("private-trip","MOVE","office",0);b.location="road";
        Conversations.startedTravel(s,b,"home");assertTrue(chat.travel.isEmpty());
        a.location="home";a.x=5;
        Conversations.startedTravel(s,b,"home");assertEquals(1,chat.travel.size());
    }
    @Test void oldRepliesNeverImplyConsentToANewSession() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");
        act(s,a,ActionType.TALK,b.id,"Hello!");act(s,b,ActionType.TALK,a.id,"Good morning!");
        act(s,a,ActionType.END_CHAT,b.id,"See you later!");
        act(s,a,ActionType.TALK,b.id,"How is the afternoon going?");
        assertTrue(Conversations.active(s,a));assertFalse(Conversations.active(s,b));
        act(s,b,ActionType.REST,null,null);assertEquals("REST",b.pending.type);
    }
}
