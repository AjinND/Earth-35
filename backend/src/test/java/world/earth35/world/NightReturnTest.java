package world.earth35.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class NightReturnTest {
    private State state(long minute) {
        State s=State.prototypeSeed();s.minute=minute;
        resident(s,"alex","apt-101");return s;
    }
    private Resident resident(State s,String id,String home) {
        Resident a=new Resident();a.id=id;a.name=id;a.home=home;a.location="park";a.energy=100;
        Building park=s.buildings.get(a.location);a.x=park.x;a.z=park.z;s.agents.put(id,a);return a;
    }
    private Command command(ActionType action,String target,String message) {
        return new Command(UUID.randomUUID().toString(),action,target,message,null,null,null);
    }
    private Engine.Effects act(State s,Resident a,ActionType action,String target,String message) {
        Engine.Effects e=new Engine.Effects();Engine.apply(s,a,command(action,target,message),UUID.randomUUID().toString(),e);return e;
    }
    private SocialPlan plan(State s,String id,String proposer,String invited,String status,long expires) {
        SocialPlan p=new SocialPlan();p.id=id;p.proposer=proposer;p.invited=invited;p.destination="park";
        p.activity="Watch the evening stars";p.meetingMinute=s.minute+15;p.expires=expires;p.status=status;
        s.socialPlans.put(id,p);return p;
    }

    @Test void preferredNightIsAnAdvisoryCueWithoutHomewardMovement() {
        State s=state(1200);Resident a=s.agents.get("alex");
        assertFalse(DailyRoutines.needsSleep(a,s.minute));assertTrue(NightRoutine.returnHomeDue(s,a));
        assertNull(NightRoutine.defaultAction(s,a,"home"));
        assertNull(a.pending);assertEquals("park",a.location);
        a.pending=null;a.location=a.home;
        assertFalse(NightRoutine.returnHomeDue(s,a));assertNull(NightRoutine.defaultAction(s,a,"awake-at-home"));
    }

    @Test void severeHungerDoesNotAutomaticallyMoveOrFeedAResident() {
        State s=state(1440+240);Resident a=s.agents.get("alex");a.hunger=100;
        assertTrue(NightRoutine.returnHomeDue(s,a));
        assertNull(NightRoutine.defaultAction(s,a,"hungry-home"));assertNull(a.pending);
        a.location=a.home;a.energy=40;
        int meals=a.inventory.get("meal");assertNull(NightRoutine.defaultAction(s,a,"meal-before-sleep"));
        assertEquals(meals,a.inventory.get("meal"));
        a.inventory.clear();assertNull(NightRoutine.defaultAction(s,a,"no-invented-food"));
    }

    @Test void everyActiveChatGetsAnAuthoredFarewellBeforeHomewardTravel() {
        State s=state(1199);Resident a=s.agents.get("alex"),b=resident(s,"maya","apt-102"),c=resident(s,"sam","apt-103");
        act(s,a,ActionType.TALK,b.id,"How was your day?");act(s,b,ActionType.TALK,a.id,"I enjoyed tending the garden.");
        act(s,a,ActionType.TALK,c.id,"Did your reading go well?");act(s,c,ActionType.TALK,a.id,"Yes, I finished my lesson.");
        s.minute=1200;assertTrue(NightRoutine.returnHomeDue(s,a));
        assertNull(NightRoutine.defaultAction(s,a,"no-synthetic-goodbye"));
        assertEquals(2,s.conversations.size());
        assertTrue(s.conversations.values().stream().noneMatch(chat->chat.closed));
        String farewell="I'm heading home for the night. See you both tomorrow! Take care.";
        Engine.Effects closing=act(s,a,ActionType.END_CHAT,null,farewell);
        assertEquals(2,closing.facts.size());
        assertTrue(closing.facts.stream().allMatch(f->f.kind().equals("conversation")&&f.message().equals(a.name+" to "+s.agents.get(f.target()).name+": "+farewell)));
        assertTrue(s.conversations.values().stream().allMatch(chat->chat.closed&&chat.turns.size()==3&&chat.turns.getLast().message().equals(farewell)));
        assertNull(NightRoutine.defaultAction(s,a,"after-farewell"));assertNull(a.pending);
        Command home=command(ActionType.MOVE,a.home,null);
        assertEquals(ActionType.MOVE,home.action());assertEquals(a.home,home.target());
        Engine.Effects moving=new Engine.Effects();Engine.apply(s,a,home,"home-trip",moving);
        assertTrue(moving.facts.stream().noneMatch(f->f.kind().equals("conversation")));
        assertTrue(s.conversations.values().stream().allMatch(chat->chat.turns.size()==3));
    }

    @Test void onlyAnAcceptedUnexpiredPlanWithThisResidentPostponesReturn() {
        State s=state(1200);Resident a=s.agents.get("alex");
        SocialPlan p=plan(s,"outing",a.id,"maya","accepted",1300);
        assertFalse(NightRoutine.returnHomeDue(s,a));assertNull(NightRoutine.defaultAction(s,a,"accepted"));
        assertFalse(NightRoutine.returnHomeDue(a,s.minute,s.socialPlans.values()));
        for(String status:List.of("proposed","declined","cancelled","completed","expired")) {
            p.status=status;assertTrue(NightRoutine.returnHomeDue(s,a),status);
            assertNull(NightRoutine.defaultAction(s,a,status));assertNull(a.pending);
        }
        p.status="accepted";p.expires=s.minute;assertTrue(NightRoutine.returnHomeDue(s,a));
        p.expires=s.minute+100;p.proposer="someone-else";assertTrue(NightRoutine.returnHomeDue(s,a));
        p.invited=a.id;assertFalse(NightRoutine.returnHomeDue(s,a));
    }

    @Test void anExplicitNightActivityPostponesReturnUntilItsActualExpiry() {
        State s=state(1200);Resident a=s.agents.get("alex");
        a.nightPlan=new NightPlan("Observe the meteor shower with a friend",1260);
        assertFalse(NightRoutine.returnHomeDue(s,a));assertNull(NightRoutine.defaultAction(s,a,"plan"));
        s.minute=1260;assertTrue(NightRoutine.returnHomeDue(s,a));
        assertNull(NightRoutine.defaultAction(s,a,"expired"));assertNull(a.pending);
    }

    @Test void preferredNightWindowIncludesLateMorningAndRespectsLaterBedtimes() {
        State s=state(1200);Resident a=s.agents.get("alex");
        a.routine=new DailyRoutine();a.routine.sleepMinute=1380;a.routine.wakeMinute=480;
        assertFalse(NightRoutine.returnHomeDue(s,a));
        s.minute=1380;assertTrue(NightRoutine.returnHomeDue(s,a));
        s.minute=1440+420;assertTrue(NightRoutine.returnHomeDue(s,a));
        s.minute=1440+480;assertFalse(NightRoutine.returnHomeDue(s,a));
        a.bedtimeRoutine=true;assertTrue(NightRoutine.returnHomeDue(s,a));
    }

    @Test void alreadyChosenRestAndTravelKeepTheirReceiptsAndDestinations() {
        State s=state(1199);Resident a=s.agents.get("alex");
        act(s,a,ActionType.REST,null,null);Pending rest=a.pending;long restUntil=rest.until;
        s.minute=1200;assertFalse(NightRoutine.returnHomeDue(s,a));assertNull(NightRoutine.defaultAction(s,a,"during-rest"));
        assertSame(rest,a.pending);assertEquals(restUntil,a.pending.until);
        a.pending=null;s.minute=1199;act(s,a,ActionType.MOVE,"office",null);Pending journey=a.pending;
        List<double[]> route=new ArrayList<>(journey.route);
        s.minute=1200;assertFalse(NightRoutine.returnHomeDue(s,a));assertNull(NightRoutine.defaultAction(s,a,"during-travel"));
        assertSame(journey,a.pending);assertEquals("office",a.pending.target);assertEquals(route,a.pending.route);
    }

    @Test void crossingPreferredWakeKeepsTheChosenTripWithoutBanningNewConversation() {
        State s=state(1440+479);Resident a=s.agents.get("alex");resident(s,"maya","apt-102");
        a.routine=new DailyRoutine();a.routine.sleepMinute=1380;a.routine.wakeMinute=480;
        Command home=command(ActionType.MOVE,a.home,null);
        assertEquals(ActionType.MOVE,home.action());
        Engine.apply(s,a,home,"bedtime-trip",new Engine.Effects());a.bedtimeRoutine=true;
        Pending journey=a.pending;double x=a.x,z=a.z;String routeChoice=journey.routeChoice;
        Engine.tick(s);
        assertEquals(1440+480,s.minute);assertFalse(DailyRoutines.sleepingTime(a,s.minute));assertTrue(a.bedtimeRoutine);
        assertSame(journey,a.pending);assertEquals("bedtime-trip",a.pending.id);assertEquals(a.home,a.pending.target);
        assertEquals(routeChoice,a.pending.routeChoice);assertTrue(Math.hypot(a.x-x,a.z-z)>0);
        Command chat=command(ActionType.TALK,"maya","Could we stop and chat again?");
        assertDoesNotThrow(()->NightRoutine.requireNightChoice(s,a,chat));
        assertNull(a.pending.socialWith);assertTrue(s.conversations.isEmpty());
        plan(s,"accepted-evening-plan",a.id,"maya","accepted",s.minute+60);
        assertDoesNotThrow(()->NightRoutine.requireNightChoice(s,a,chat));assertSame(journey,a.pending);
    }

    @Test void daytimePausedAndResidentsWithoutAnAvailableHomeHaveNoNightReturn() {
        State s=state(720);Resident a=s.agents.get("alex");
        assertFalse(NightRoutine.returnHomeDue(s,a));assertNull(NightRoutine.defaultAction(s,a,"day"));
        s.minute=1200;a.paused=true;assertFalse(NightRoutine.returnHomeDue(s,a));assertNull(NightRoutine.defaultAction(s,a,"paused"));
        a.paused=false;a.home=null;assertFalse(NightRoutine.returnHomeDue(s,a));assertNull(NightRoutine.defaultAction(s,a,"unhoused"));
        a.home="missing-home";assertFalse(NightRoutine.returnHomeDue(s,a));assertNull(NightRoutine.defaultAction(s,a,"missing"));
    }
}
