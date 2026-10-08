package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class SocialTest {
    private Resident resident(State s,String id,double x,double z) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location="road";a.x=x;a.z=z;
        s.agents.put(id,a);return a;
    }
    private Command command(ActionType type,String target,String message,String item,Integer quantity) {
        return new Command(UUID.randomUUID().toString(),type,target,message,item,quantity,null);
    }
    private Engine.Effects act(State s,Resident a,ActionType type,String target,String message,String item,Integer quantity,String id) {
        Engine.Effects e=new Engine.Effects();Engine.apply(s,a,command(type,target,message,item,quantity),id,e);return e;
    }
    private void walk(Resident a,String id) {
        a.pending=Engine.pending(id,"MOVE","office",0);a.pending.frontDoorRoute=true;a.pending.route.add(new double[]{10,2.4});
        a.pending.travelWeather=Engine.weather(480);a.pending.reviewAfter=495;
    }
    @Test void familiarResidentsAndStrangersOfferAChoiceWithoutAutomaticallyStoppingAnyone() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4),stranger=resident(s,"stranger",6.4,2.4);
        a.relationships.put(b.id,"close friend");walk(a,"trip");walk(b,"other-trip");
        var encounter=Social.situation(s,a,Map.of()).encounters().getFirst();
        assertEquals("b",encounter.other());assertEquals("close friend",encounter.relationship());assertFalse(encounter.metBefore());
        assertTrue(Social.situation(s,a,Map.of()).reconsider());assertEquals(2,Social.encounters(s,a,Map.of()).size());
        Engine.tick(s);assertEquals(4.4,a.x,.001);assertEquals(6.4,b.x,.001);assertNull(stranger.pending);
        act(s,a,ActionType.CONTINUE_TRAVEL,null,null,null,null,"keep-going");
        assertFalse(Social.situation(s,a,Map.of()).reconsider());assertEquals("trip",a.pending.id);
        assertTrue(s.conversations.isEmpty());
        assertFalse(Social.situation(s,a,Map.of("b",false)).reconsider());
    }
    @Test void talkPausesOnlyTheSpeakerAndEitherResidentCanChooseToResume() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4),c=resident(s,"c",6.4,2.4);
        walk(a,"a-trip");walk(b,"b-trip");walk(c,"c-trip");
        act(s,a,ActionType.TALK,b.id,"Good to see you!",null,null,"hello");
        assertEquals("a-trip",a.pending.id);assertEquals("CHAT",Engine.activity(a));assertEquals("MOVE",Engine.activity(b));
        Engine.tick(s);assertEquals(2.4,a.x,.001);assertEquals(6.4,b.x,.001);assertEquals(8.4,c.x,.001);
        act(s,b,ActionType.TALK,a.id,"Let's catch up.",null,null,"reply");
        assertEquals(2,s.conversations.get(Conversations.key(a.id,b.id)).turns.size());
        double bx=b.x;Engine.tick(s);assertEquals(2.4,a.x,.001);assertEquals(bx,b.x,.001);
        act(s,a,ActionType.END_CHAT,null,"I need to keep walking. See you around! Bye!",null,null,"leave");Engine.tick(s);
        assertEquals(4.4,a.x,.001);assertEquals(bx,b.x,.001);assertEquals("a-trip",a.pending.id);
        assertEquals("closed",Conversations.views(s,b,Map.of()).getFirst().status());
        act(s,b,ActionType.TALK,a.id,"A fresh topic",null,null,"fresh");
        act(s,a,ActionType.END_CHAT,null,"Let's talk another time. Take care!",null,null,"closing");
        act(s,a,ActionType.CONTINUE_TRAVEL,null,null,null,null,"continue");
        assertEquals("closed",Conversations.views(s,b,Map.of()).getFirst().status());
        act(s,b,ActionType.TALK,a.id,"Another encounter",null,null,"fresh-again");
        assertEquals(6,s.conversations.values().iterator().next().turns.size());
    }
    @Test void silentChatsTimeOutWithoutAReplyAndPreserveTheSavedRoute() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);walk(a,"journey");
        act(s,a,ActionType.TALK,b.id,"Hello",null,null,"hello");
        for(int i=0;i<5;i++)Engine.tick(s);
        assertEquals(2.4,a.x,.001);
        Engine.tick(s);assertEquals(4.4,a.x,.001);assertNull(a.pending.socialWith);assertEquals("journey",a.pending.id);
        assertEquals(1,s.conversations.values().iterator().next().turns.size());
        assertEquals("closed",Conversations.views(s,a,Map.of()).getFirst().status());
        act(s,a,ActionType.TALK,b.id,"New conversation",null,null,"fresh");
        assertEquals(2,s.conversations.values().iterator().next().turns.size());
    }
    @Test void stoppingInTheRoadAndTalkingToUnavailableResidentsHaveNoEffect() {
        State s=State.prototypeSeed();Resident a=resident(s,"a",.8,2.4),b=resident(s,"b",4.4,2.4);walk(a,"journey");
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.TALK,b.id,"Hi",null,null,"bad"));
        assertTrue(s.conversations.isEmpty());assertNull(a.pending.socialWith);
        a.x=2.4;b.paused=true;
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.PAUSE_TRAVEL,b.id,null,null,6,"bad"));
        assertNull(a.pending.socialWith);assertEquals("journey",a.pending.id);
    }
    @Test void previousConversationsRecognizeAcquaintancesAndRelationshipsStayOneSided() {
        State s=State.seed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);
        act(s,a,ActionType.TALK,b.id,"Hello",null,null,"talk");
        assertTrue(Social.encounters(s,b,Map.of()).getFirst().metBefore());
        act(s,a,ActionType.RELATE,b.id,"friend",null,null,"relate");
        assertEquals("friend",a.relationships.get(b.id));assertTrue(b.relationships.isEmpty());
        Resident stranger=resident(s,"stranger",6.4,2.4);
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.RELATE,stranger.id,"friend",null,null,"bad"));
    }
    @Test void plansRequireMutualConsentAndEachResidentTravelsIndependently() {
        State s=State.seed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4),outsider=resident(s,"outsider",6.4,2.4);
        walk(a,"old-trip");
        act(s,a,ActionType.PROPOSE_PLAN,b.id,"Discuss weekend ideas","coffee",15,"plan");
        SocialPlan p=s.socialPlans.get("plan");assertEquals("proposed",p.status);assertNull(b.pending);
        assertEquals(1,Social.plans(s,b).size());assertTrue(Social.plans(s,outsider).isEmpty());
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.JOIN_PLAN,p.id,null,null,null,"too-soon"));
        assertEquals("old-trip",a.pending.id);
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.ACCEPT_PLAN,p.id,null,null,null,"bad"));
        assertThrows(Engine.Invalid.class,()->act(s,outsider,ActionType.ACCEPT_PLAN,p.id,null,null,null,"bad"));
        act(s,b,ActionType.ACCEPT_PLAN,p.id,null,null,null,"accept");assertEquals("accepted",p.status);assertNull(b.pending);
        var e=act(s,a,ActionType.JOIN_PLAN,p.id,null,null,null,"join");
        assertEquals("cancelled",e.completions.getFirst().status());assertEquals("coffee",a.pending.target);assertEquals("plan",a.pending.socialPlanId);
        assertNull(b.pending);act(s,b,ActionType.JOIN_PLAN,p.id,null,null,null,"b-join");
        for(int i=0;i<300&&(a.pending!=null||b.pending!=null);i++)Engine.tick(s);
        assertEquals("coffee",a.location);assertEquals("coffee",b.location);
        while(s.minute<p.meetingMinute)Engine.tick(s);
        assertEquals("completed",p.status);
    }
    @Test void decliningCancellingAndExpiryDoNotMoveEitherParticipant() {
        State s=State.seed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);
        act(s,a,ActionType.PROPOSE_PLAN,b.id,"Walk in the park","park",10,"p1");
        act(s,b,ActionType.DECLINE_PLAN,"p1",null,null,null,"decline");assertEquals("declined",s.socialPlans.get("p1").status);
        act(s,a,ActionType.PROPOSE_PLAN,b.id,"Coffee","coffee",10,"p2");
        act(s,b,ActionType.ACCEPT_PLAN,"p2",null,null,null,"accept");
        act(s,a,ActionType.CANCEL_PLAN,"p2",null,null,null,"cancel");assertEquals("cancelled",s.socialPlans.get("p2").status);
        act(s,a,ActionType.PROPOSE_PLAN,b.id,"Meet at the park","park",1,"p3");
        s.minute=s.socialPlans.get("p3").expires-1;Engine.tick(s);
        assertEquals("expired",s.socialPlans.get("p3").status);assertNull(a.pending);assertNull(b.pending);
        assertEquals(2.4,a.x,.001);assertEquals(4.4,b.x,.001);
    }
    @Test void encountersChatPausesAndAgreedPlansSurviveRestartAndOldWorldsLoad() throws Exception {
        State s=State.prototypeSeed();Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);walk(a,"journey");
        act(s,a,ActionType.TALK,b.id,"Hello",null,null,"talk");
        act(s,a,ActionType.PROPOSE_PLAN,b.id,"Catch up over coffee","coffee",15,"plan");
        act(s,b,ActionType.ACCEPT_PLAN,"plan",null,null,null,"accept");
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);
        assertEquals("accepted",restored.socialPlans.get("plan").status);
        assertEquals(b.id,restored.agents.get(a.id).pending.socialWith);
        assertEquals(480,restored.agents.get(a.id).encounterReviewed.get(b.id));
        for(int i=0;i<6;i++)Engine.tick(restored);
        assertTrue(restored.agents.get(a.id).x>2.4);
        State old=json.readValue("{\"agents\":{\"old\":{\"id\":\"old\"}}}",State.class);
        assertTrue(old.socialPlans.isEmpty());assertTrue(old.agents.get("old").encounterReviewed.isEmpty());
    }
    @Test void chatPausesStillApplyRentAtMidnight() {
        State s=State.seed();s.minute=1439;Resident a=resident(s,"a",2.4,2.4),b=resident(s,"b",4.4,2.4);walk(a,"journey");
        act(s,a,ActionType.PAUSE_TRAVEL,b.id,null,null,6,"pause");long balance=a.money;
        Engine.tick(s);assertEquals(balance-2000,a.money);assertEquals(2.4,a.x,.001);
    }
}
