package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class ConversationClosingTest {
    private Resident resident(State s,String id) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location="park";
        a.x=s.buildings.get("park").x;a.z=s.buildings.get("park").z;s.agents.put(id,a);return a;
    }
    private Engine.Effects act(State s,Resident a,ActionType type,String target,String message) {
        Engine.Effects e=new Engine.Effects();
        Engine.apply(s,a,new Command(UUID.randomUUID().toString(),type,target,message,null,null,null),"action",e);return e;
    }
    private Conversation chat(State s,Resident a,Resident b) {return s.conversations.get(Conversations.key(a.id,b.id));}

    @Test void closingWhileWaitingIsSpokenOnceBeforeDepartureAndSurvivesRestart() throws Exception {
        State s=State.seed();Resident a=resident(s,"Alex"),b=resident(s,"Maya");
        act(s,a,ActionType.TALK,b.id,"How was your day?");
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.TALK,b.id,"Another question"));
        String goodbye="I've got some things to do. Catch up with you later! Bye!";
        Engine.Effects closing=act(s,a,ActionType.END_CHAT,null,goodbye);
        assertEquals(List.of(new Engine.Fact("conversation",a.id,b.id,"Alex to Maya: "+goodbye)),closing.facts);
        assertEquals(goodbye,chat(s,a,b).turns.getLast().message());
        assertEquals("park",chat(s,a,b).turns.getLast().location());assertTrue(chat(s,a,b).closed);
        act(s,a,ActionType.END_CHAT,null,null);assertEquals(2,chat(s,a,b).turns.size());
        ObjectMapper json=new ObjectMapper();State saved=json.readValue(json.writeValueAsString(s),State.class);
        Resident savedA=saved.agents.get("Alex"),savedB=saved.agents.get("Maya");
        assertEquals(goodbye,chat(saved,savedA,savedB).turns.getLast().message());assertTrue(chat(saved,savedA,savedB).closed);
        act(saved,savedA,ActionType.MOVE,"office",null);assertEquals("MOVE",savedA.pending.type);
    }
    @Test void anEmptyClosingAndAbruptActivitiesLeaveTheChatAndWorldUntouched() {
        State s=State.seed();Resident a=resident(s,"Alex"),b=resident(s,"Maya");
        act(s,a,ActionType.TALK,b.id,"Hello!");
        for(String empty:new String[]{null,"","   "})
            assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.END_CHAT,null,empty));
        for(ActionType type:List.of(ActionType.MOVE,ActionType.RUN,ActionType.JOIN_PLAN,ActionType.TRAVEL_BUS,
            ActionType.WAIT_BUS,ActionType.BOARD_BUS,ActionType.CONTINUE_TRAVEL,ActionType.JUMP,ActionType.WORK,
            ActionType.REST,ActionType.SLEEP,ActionType.USE_OBJECT,ActionType.READ_BOOK,ActionType.WAIT_RAIN)) {
            var error=assertThrows(Engine.Invalid.class,()->act(s,a,type,"office",null));
            assertTrue(error.getMessage().contains("END_CHAT"));
            assertNull(a.pending);assertEquals("park",a.location);assertFalse(chat(s,a,b).closed);
            assertEquals(1,chat(s,a,b).turns.size());
        }
        act(s,a,ActionType.LOOK,null,null);assertFalse(chat(s,a,b).closed);
        assertNull(NightRoutine.defaultAction(s,a,"fallback"));
        act(s,b,ActionType.END_CHAT,null,"It was nice talking. See you around! Take care.");
        act(s,a,ActionType.REST,null,null);assertEquals("REST",a.pending.type);
    }
    @Test void aFarewellAcknowledgmentStaysClosedAndCannotBecomeAGoodbyeLoop() {
        State s=State.seed();Resident a=resident(s,"Alex"),b=resident(s,"Maya");
        act(s,a,ActionType.TALK,b.id,"Hello!");
        act(s,a,ActionType.END_CHAT,b.id,"See you later! Bye!");
        act(s,b,ActionType.END_CHAT,a.id,"Talk soon, Alex. Take care!");
        assertTrue(chat(s,a,b).closed);assertEquals(3,chat(s,a,b).turns.size());
        assertThrows(Engine.Invalid.class,()->act(s,b,ActionType.END_CHAT,a.id,"Goodbye again"));
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.END_CHAT,b.id,"Goodbye again from Alex"));
        b.x+=11;
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.END_CHAT,b.id,"Take care again"));
        assertEquals(3,chat(s,a,b).turns.size());
    }
    @Test void aTargetedClosingDoesNotReleaseAnotherConversation() {
        State s=State.seed();Resident a=resident(s,"Alex"),b=resident(s,"Maya"),c=resident(s,"Sam");
        act(s,a,ActionType.TALK,b.id,"Hello Maya!");act(s,a,ActionType.TALK,c.id,"Hello Sam!");
        Engine.Effects e=act(s,a,ActionType.END_CHAT,b.id,"See you soon, Maya. Bye!");
        assertEquals(1,e.facts.size());assertTrue(chat(s,a,b).closed);assertFalse(chat(s,a,c).closed);
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.MOVE,"office",null));
        act(s,a,ActionType.END_CHAT,null,"I should leave now. See you around! Take care.");
        assertTrue(chat(s,a,c).closed);act(s,a,ActionType.MOVE,"office",null);
    }
    @Test void closingResumesOnlyTheSpeakersSavedJourneyWithoutReplacingItsReceipt() {
        State s=State.seed();Resident a=resident(s,"Alex"),b=resident(s,"Maya");
        a.location=b.location="road";a.x=2.4;a.z=2.4;b.x=4.4;b.z=2.4;
        a.pending=Engine.pending("original-trip","MOVE","office",0);a.pending.route.add(new double[]{10,2.4});
        b.pending=Engine.pending("other-trip","MOVE","office",0);b.pending.route.add(new double[]{10,2.4});
        act(s,a,ActionType.TALK,b.id,"Good to see you!");act(s,b,ActionType.TALK,a.id,"You too!");
        Engine.Effects e=act(s,a,ActionType.END_CHAT,null,"I need to keep going. Talk soon! Bye!");
        assertEquals("original-trip",a.pending.id);assertNull(a.pending.socialWith);
        assertEquals(a.id,b.pending.socialWith);assertTrue(e.completions.isEmpty());
        assertEquals(1,e.facts.size());assertTrue(chat(s,a,b).closed);
    }
    @Test void unknownTargetsAndExpiredAcknowledgmentsNeverAddDialogue() {
        State s=State.seed();Resident a=resident(s,"Alex"),b=resident(s,"Maya");
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.END_CHAT,b.id,"Bye!"));
        assertTrue(s.conversations.isEmpty());
        act(s,a,ActionType.TALK,b.id,"Hello!");act(s,a,ActionType.END_CHAT,null,"Talk soon! Bye!");
        s.minute+=60;
        assertThrows(Engine.Invalid.class,()->act(s,b,ActionType.END_CHAT,a.id,"Take care!"));
        assertEquals(2,chat(s,a,b).turns.size());
    }
}
