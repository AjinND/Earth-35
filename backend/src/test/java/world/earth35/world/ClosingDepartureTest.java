package world.earth35.world;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class ClosingDepartureTest {
    private Resident customer(State s,String id,Building coffee) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=coffee.id;
        a.x=coffee.x;a.z=coffee.z;s.agents.put(id,a);return a;
    }
    private void act(State s,Resident a,ActionType action,String target,String message,String id) {
        Engine.apply(s,a,new Command(id,action,target,message,null,null,null),id,new Engine.Effects());
    }
    private boolean inside(Resident a,Building b) {
        return Math.abs(a.x-b.x)<3.35&&Math.abs(a.z-b.z)<3.35;
    }

    @Test void closingResumesIndoorDepartureChatsOutsideWithoutCancellingEitherJourney() {
        for(ActionType pace:List.of(ActionType.MOVE,ActionType.RUN)) {
            State s=State.prototypeSeed();s.minute=1199;Building coffee=s.buildings.get("coffee");
            Resident first=customer(s,"first",coffee),second=customer(s,"second",coffee);
            act(s,first,pace,"park",null,"first-trip");act(s,second,pace,"park",null,"second-trip");
            Pending firstTrip=first.pending,secondTrip=second.pending;
            act(s,first,ActionType.TALK,second.id,"Let's finish this conversation while heading out.","hello");
            act(s,second,ActionType.TALK,first.id,"See you at the park.","reply");
            Conversation chat=s.conversations.get(Conversations.key(first.id,second.id));
            List<Turn> turns=List.copyOf(chat.turns);long turnSequence=chat.turnSequence;
            for(Resident a:List.of(first,second)) {
                assertEquals("road",a.location);assertTrue(inside(a,coffee));assertNotNull(a.pending.exitDoor);
                assertTrue(a.pending.socialUntil>1200);assertNotNull(a.pending.socialWith);
                assertTrue(Conversations.active(s,a));
            }

            Engine.Effects closed=Engine.tick(s);

            assertEquals(1200,s.minute);assertFalse(Places.open(coffee,s.minute));assertTrue(closed.completions.isEmpty());
            assertSame(firstTrip,first.pending);assertSame(secondTrip,second.pending);
            var door=Places.entrance(coffee);
            for(Resident a:List.of(first,second)) {
                assertEquals("road",a.location);assertEquals(door.x(),a.x,.000001);assertEquals(door.z(),a.z,.000001);
                assertFalse(inside(a,coffee));assertEquals(pace.name(),a.pending.type);assertEquals("park",a.pending.target);
                assertEquals(a==first?"first-trip":"second-trip",a.pending.id);
                assertNull(a.pending.exitDoor);assertNull(a.pending.socialWith);assertEquals(0,a.pending.socialUntil);
                assertNull(a.pending.waitingReason);assertFalse(Conversations.active(s,a));
            }
            assertTrue(chat.closed);assertEquals(turns,chat.turns);assertEquals(turnSequence,chat.turnSequence);
            assertTrue(closed.facts.stream().noneMatch(f->"conversation".equals(f.kind())));

            List<Engine.Completion> completions=new ArrayList<>();
            for(int i=0;i<500&&(first.pending!=null||second.pending!=null);i++) {
                completions.addAll(Engine.tick(s).completions);
                assertFalse(inside(first,coffee),"First traveller returned into the closed venue");
                assertFalse(inside(second,coffee),"Second traveller returned into the closed venue");
            }
            assertNull(first.pending);assertNull(second.pending);assertEquals("park",first.location);assertEquals("park",second.location);
            assertEquals(2,completions.size());assertTrue(completions.stream().allMatch(c->"completed".equals(c.status())));
            assertEquals(List.of("first-trip","second-trip"),completions.stream().map(Engine.Completion::id).sorted().toList());
            assertTrue(chat.closed);assertEquals(turns,chat.turns);
        }
    }
}
