package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class SharedCommitmentTest {
    private Resident resident(State s,String id) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location="road";a.x=2.4;a.z=2.4;
        s.agents.put(id,a);return a;
    }
    private Engine.Effects act(State s,Resident a,ActionType type,String target,String message,String item,Integer quantity,String id) {
        Engine.Effects effects=new Engine.Effects();
        Engine.apply(s,a,new Command(id,type,target,message,item,quantity,null),id,effects);return effects;
    }
    private void talk(State s,Resident a,Resident b,String message) {
        act(s,a,ActionType.TALK,b.id,message,null,null,UUID.randomUUID().toString());
    }
    private SocialPlan plan(State s,Resident a,Resident b,int delay,String id) {
        act(s,a,ActionType.PROPOSE_PLAN,b.id,"Catch up over coffee","coffee",delay,id);
        act(s,b,ActionType.ACCEPT_PLAN,id,null,null,null,id+"-accepted");return s.socialPlans.get(id);
    }
    private Social.Commitment commitment(State s,Resident a) {
        return Social.situation(s,a,Map.of()).commitments().getFirst();
    }
    private void interruptTwice(State s,Resident a,Resident c) {
        talk(s,c,a,"Do you have a moment?");
        talk(s,a,c,"Briefly, I have already agreed to meet someone.");
        talk(s,c,a,"Can you tell me about the neighborhood?");
        talk(s,a,c,"The park is lovely; let's catch up properly later.");
        talk(s,c,a,"What about the cafes?");
    }
    private void at(State s,String destination,Resident... residents) {
        Building building=s.buildings.get(destination);
        for(Resident a:residents) {a.location=destination;a.x=building.x;a.z=building.z;a.pending=null;}
    }

    @Test void anAcceptedPlanRemainsVisibleWhileTheResidentChoosesHowLongToTalk() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c");
        talk(s,a,b,"Shall we leave for coffee together?");
        talk(s,b,a,"Yes, let's go.");SocialPlan p=plan(s,a,b,1,"coffee-plan");
        interruptTwice(s,a,c);
        assertEquals("accepted",p.status);assertNull(a.pending);assertNull(b.pending);
        var view=commitment(s,a);
        assertEquals(p.id,view.plan());assertEquals(b.id,view.other());assertTrue(view.due());assertTrue(view.returnDue());
        assertEquals("END_CHAT",view.nextAction());assertEquals(2,view.interruptionTurns());assertEquals(0,view.remainingInterruptionTurns());
        String key=Conversations.key(a.id,c.id);int turns=s.conversations.get(key).turns.size();
        Engine.Effects continuedEffects=new Engine.Effects();
        Engine.apply(s,a,new Command("third-reply",ActionType.TALK,c.id,"Let me explain another thing.",null,null,null),"third-reply",continuedEffects);
        assertEquals(turns+1,s.conversations.get(key).turns.size());assertFalse(continuedEffects.facts.isEmpty());
        assertEquals(3,commitment(s,a).interruptionTurns());assertEquals("accepted",p.status);
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.JOIN_PLAN,p.id,null,null,null,"leave-without-closing"));
        assertNull(a.pending,"An observation about a delayed agreement must not move the resident");
        act(s,a,ActionType.END_CHAT,null,"I promised to head out for coffee. Let's catch up later!",null,null,"farewell");
        assertEquals("JOIN_PLAN",commitment(s,a).nextAction());
        act(s,a,ActionType.JOIN_PLAN,p.id,null,null,null,"join-a");
        assertEquals("join-a",a.pending.id);assertEquals(p.id,a.pending.socialPlanId);assertEquals("coffee",a.pending.target);
        assertNull(b.pending,"Agreeing to a plan must not move the other participant automatically");
    }

    @Test void aGreetingAloneDoesNotRecordAnInterruptionByTheListener() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c");
        SocialPlan p=plan(s,a,b,1,"coffee-plan");talk(s,c,a,"Hello! Do you have a minute?");
        var view=commitment(s,a);
        assertNull(view.interruptionStartedMinute());assertEquals(0,view.interruptionTurns());assertFalse(Conversations.active(s,a));
        act(s,a,ActionType.JOIN_PLAN,p.id,null,null,null,"join-a");assertEquals(p.id,a.pending.socialPlanId);
    }

    @Test void timeSpentTalkingIsAdvisoryAndDoesNotRejectALaterReply() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c");
        plan(s,a,b,1,"coffee-plan");talk(s,c,a,"May I ask something?");talk(s,a,c,"I can spare a moment before leaving.");
        long began=s.minute;assertEquals(began+3,commitment(s,a).interruptionUntil());
        talk(s,c,a,"Where should I go?");s.minute=began+3;
        talk(s,a,c,"Let's discuss it for longer.");
        assertTrue(commitment(s,a).returnDue());
        assertEquals(2,commitment(s,a).interruptionTurns());assertNull(a.pending);
    }

    @Test void timeAwayFromAnAgreementAccumulatesAcrossVoluntaryConversations() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c"),d=resident(s,"d"),e=resident(s,"e");
        plan(s,a,b,1,"coffee-plan");talk(s,c,a,"A quick question?");talk(s,a,c,"Briefly; I have plans with b.");
        act(s,a,ActionType.END_CHAT,c.id,"I need to leave soon. Catch up later!",null,null,"close-c");
        talk(s,d,a,"Could you help me too?");talk(s,a,d,"Just a moment, then I must go.");
        act(s,a,ActionType.END_CHAT,d.id,"I must honor my coffee plan now. See you later!",null,null,"close-d");
        talk(s,e,a,"One more question?");talk(s,a,e,"Let's start another chat.");
        assertEquals(3,commitment(s,a).interruptionTurns());assertEquals("END_CHAT",commitment(s,a).nextAction());
        assertEquals(2,s.conversations.get(Conversations.key(a.id,e.id)).turns.size());
        assertNull(a.pending);assertNull(b.pending);
    }

    @Test void aWalkingConversationMayContinueWhilePreservingTheSavedJourney() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c");
        SocialPlan p=plan(s,a,b,1,"coffee-plan");act(s,a,ActionType.JOIN_PLAN,p.id,null,null,null,"agreed-trip");
        talk(s,c,a,"Can we chat before you leave?");talk(s,a,c,"For a moment; I am on my way to meet b.");
        long firstPause=s.minute+6;assertEquals(firstPause,a.pending.socialUntil);assertEquals("agreed-trip",a.pending.id);
        Engine.tick(s);talk(s,c,a,"What is your favorite cafe?");talk(s,a,c,"Common Ground. Let's talk there another time.");
        long deadline=s.minute+6;assertTrue(deadline>firstPause);
        assertEquals(deadline,a.pending.socialUntil);assertEquals(p.id,a.pending.socialPlanId);
        assertEquals("travelling",commitment(s,a).progress());assertTrue(commitment(s,a).returnDue());
        while(s.minute<deadline)Engine.tick(s);
        assertEquals("agreed-trip",a.pending.id);assertNull(a.pending.socialWith);assertEquals(p.id,a.pending.socialPlanId);
        assertFalse(Conversations.active(s,a));assertEquals("CONTINUE_TRAVEL",commitment(s,a).nextAction());
    }

    @Test void explicitTravelPausesHonorTheResidentsChosenDurationDespiteADueAgreement() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c"),d=resident(s,"d");
        SocialPlan p=plan(s,a,b,1,"coffee-plan");act(s,a,ActionType.JOIN_PLAN,p.id,null,null,null,"agreed-trip");
        act(s,a,ActionType.PAUSE_TRAVEL,c.id,null,null,15,"pause-c");long deadline=s.minute+15;
        assertEquals(deadline,a.pending.socialUntil);assertEquals(0,commitment(s,a).interruptionTurns());
        Engine.tick(s);act(s,a,ActionType.PAUSE_TRAVEL,d.id,null,null,15,"pause-d");
        Pending partnerJourney=b.pending;
        assertNotNull(partnerJourney,"The partner's accepted executable plan starts their own journey when due");
        assertEquals(p.id,partnerJourney.socialPlanId);assertEquals(p.destination,partnerJourney.target);
        assertEquals(s.minute+15,a.pending.socialUntil);assertEquals("agreed-trip",a.pending.id);
        s.minute=deadline;act(s,a,ActionType.PAUSE_TRAVEL,c.id,null,null,15,"later-pause");
        assertEquals(s.minute+15,a.pending.socialUntil);assertEquals(c.id,a.pending.socialWith);
        assertEquals("accepted",p.status);assertSame(partnerJourney,b.pending);
        assertNull(b.pending.socialWith,"Extending one resident's pause must not pause the partner's journey");
    }

    @Test void proposalsAndFutureMeetingsDoNotArtificiallyLimitOrdinaryConversations() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c");
        act(s,a,ActionType.PROPOSE_PLAN,b.id,"Coffee tomorrow morning","coffee",100,"future-plan");
        assertTrue(Social.situation(s,a,Map.of()).commitments().isEmpty());
        act(s,b,ActionType.ACCEPT_PLAN,"future-plan",null,null,null,"accept");
        assertFalse(commitment(s,a).due());
        for(int i=0;i<4;i++) {talk(s,c,a,"Question "+i);talk(s,a,c,"Answer "+i);}
        assertEquals(0,commitment(s,a).interruptionTurns());assertNull(commitment(s,a).interruptionUntil());
        assertFalse(commitment(s,a).returnDue());
    }

    @Test void theAgreedPartnersConversationIsNeverTreatedAsAThirdPartyInterruption() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");plan(s,a,b,1,"coffee-plan");
        for(int i=0;i<4;i++) {talk(s,a,b,"Let's coordinate detail "+i);talk(s,b,a,"Agreed on detail "+i);}
        var view=commitment(s,a);assertEquals(0,view.interruptionTurns());assertNull(view.interruptionUntil());assertFalse(view.returnDue());
    }

    @Test void interruptionObservationsSurviveRestartWithoutBecomingAConversationLimit() throws Exception {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c");
        plan(s,a,b,1,"coffee-plan");talk(s,c,a,"Can I interrupt?");talk(s,a,c,"Briefly, before my coffee plan.");
        long deadline=commitment(s,a).interruptionUntil();ObjectMapper json=new ObjectMapper();
        State restored=json.readValue(json.writeValueAsString(s),State.class);Resident savedA=restored.agents.get(a.id),savedC=restored.agents.get(c.id);
        assertEquals(deadline,commitment(restored,savedA).interruptionUntil());assertEquals(1,commitment(restored,savedA).interruptionTurns());
        talk(restored,savedC,savedA,"One follow-up question?");talk(restored,savedA,savedC,"Then I really must join b.");
        talk(restored,savedC,savedA,"Another follow-up?");talk(restored,savedA,savedC,"A third answer.");
        assertEquals(3,commitment(restored,savedA).interruptionTurns());
        assertEquals(deadline,commitment(restored,savedA).interruptionUntil());
        State legacy=json.readValue("{\"agents\":{\"old\":{\"id\":\"old\"}}}",State.class);
        assertNotNull(legacy.agents.get("old").commitmentInterruptions);assertTrue(legacy.agents.get("old").commitmentInterruptions.isEmpty());
        assertTrue(Social.situation(legacy,legacy.agents.get("old"),Map.of()).commitments().isEmpty());
    }

    @Test void cancelledCompletedAndExpiredPlansReleaseTheCommitmentObservation() {
        for(String status:List.of("cancelled","completed","expired")) {
            State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c");
            SocialPlan p=plan(s,a,b,1,"coffee-plan");interruptTwice(s,a,c);
            if(status.equals("cancelled"))act(s,a,ActionType.CANCEL_PLAN,p.id,null,null,null,"cancel");
            else if(status.equals("completed")) {
                at(s,"coffee",a,b,c);Conversations.end(s,a);s.minute=p.meetingMinute;Engine.tick(s);
                assertEquals("in_progress",p.status,"Meeting starts the agreed activity; it does not complete it");
                for(int minute=0;minute<30&&!p.status.equals("completed");minute++)Engine.tick(s);
            }
            else {s.minute=p.expires;Engine.tick(s);}
            assertEquals(status,p.status);assertTrue(Social.situation(s,a,Map.of()).commitments().isEmpty());
            talk(s,a,c,"Now that my earlier plan has ended, we can continue.");
        }
    }

    @Test void reachingTheDestinationStopsRecordingDelayWhileWaitingForThePartner() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c");
        SocialPlan p=plan(s,a,b,1,"coffee-plan");at(s,"coffee",a,c);
        assertEquals("accepted",p.status);assertEquals("at_destination",commitment(s,a).progress());
        for(int i=0;i<4;i++) {talk(s,c,a,"At the cafe question "+i);talk(s,a,c,"At the cafe answer "+i);}
        assertEquals(0,commitment(s,a).interruptionTurns());assertFalse(commitment(s,a).returnDue());assertNull(b.pending);
    }

    @Test void activeCommitmentsRemainVisibleBeyondTheLimitedPlanHistoryAndSortByMeetingTime() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c");
        SocialPlan later=plan(s,a,b,15,"later"),earlier=plan(s,a,c,1,"earlier");
        for(int i=0;i<25;i++) {
            SocialPlan old=new SocialPlan();old.id="old-"+i;old.proposer=a.id;old.invited="past-contact-"+i;
            old.destination="coffee";old.activity="Past coffee";old.status="completed";old.meetingMinute=s.minute+50+i;old.expires=s.minute+150+i;
            s.socialPlans.put(old.id,old);
        }
        var visiblePlans=Social.plans(s,a);
        assertEquals(20,visiblePlans.stream().filter(p->p.status.equals("completed")).count());
        assertTrue(visiblePlans.stream().anyMatch(p->p.id.equals(earlier.id)));
        assertTrue(visiblePlans.stream().anyMatch(p->p.id.equals(later.id)));
        var commitments=Social.situation(s,a,Map.of()).commitments();
        assertEquals(List.of(earlier.id,later.id),commitments.stream().map(Social.Commitment::plan).toList());
        assertEquals(1,commitments.getFirst().priority());assertEquals(2,commitments.getLast().priority());
    }

    @Test void aLaterJourneyDoesNotRecordADelayForAnEarlierMeetingThatIsNotYetDue() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c"),d=resident(s,"d");
        SocialPlan earlier=plan(s,a,b,80,"earlier");
        act(s,a,ActionType.PROPOSE_PLAN,c.id,"Meet at the park","park",100,"later");
        act(s,c,ActionType.ACCEPT_PLAN,"later",null,null,null,"accept-later");
        act(s,a,ActionType.JOIN_PLAN,"later",null,null,null,"later-trip");
        for(int i=0;i<4;i++) {talk(s,d,a,"Journey question "+i);talk(s,a,d,"Journey answer "+i);}
        var primary=commitment(s,a);assertEquals(earlier.id,primary.plan());assertEquals(1,primary.priority());assertFalse(primary.due());
        assertEquals(0,primary.interruptionTurns());assertNull(primary.interruptionUntil());
        assertEquals("later-trip",a.pending.id);assertEquals("later",a.pending.socialPlanId);
    }

    @Test void anEarlierDueAgreementRemainsVisibleDuringConversationAboutALaterPlan() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b"),c=resident(s,"c");
        SocialPlan earlier=plan(s,a,b,1,"earlier");plan(s,a,c,15,"later");
        interruptTwice(s,a,c);
        assertEquals(earlier.id,commitment(s,a).plan());assertEquals(2,commitment(s,a).interruptionTurns());
        talk(s,a,c,"Another answer for the later plan.");
        assertEquals(3,commitment(s,a).interruptionTurns());assertEquals("accepted",earlier.status);
        assertEquals(0,Social.situation(s,a,Map.of()).commitments().getLast().interruptionTurns());
    }

    @Test void restingAtTheAgreedDestinationDoesNotRepeatedlyWakeForAnAlreadyHonoredDeparture() {
        State s=State.prototypeSeed();Resident a=resident(s,"a"),b=resident(s,"b");plan(s,a,b,1,"coffee-plan");
        at(s,"coffee",a);a.pending=Engine.pending("rest","REST",a.location,s.minute+60);
        Social.reviewed(s,a);s.minute+=16;
        var situation=Social.situation(s,a,Map.of());assertTrue(situation.encounters().isEmpty());
        assertEquals("at_destination",situation.commitments().getFirst().progress());
        assertFalse(situation.reconsider());assertFalse(situation.commitments().getFirst().returnDue());
        assertEquals("rest",a.pending.id);assertNull(b.pending);
    }
}
