package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class PlanExecutionTest {
    private Resident resident(State s,String id,String place) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=place;
        Building b=s.buildings.get(place);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    private Engine.Effects act(State s,Resident a,ActionType type,String target,String message,String item,Integer minutes,String id) {
        Engine.Effects e=new Engine.Effects();Engine.apply(s,a,new Command(id,type,target,message,item,minutes,null),id,e);return e;
    }
    private SocialPlan proposed(State s,Resident a,Resident b,String destination,int delay) {
        act(s,a,ActionType.PROPOSE_PLAN,b.id,"Search for wildflowers",destination,delay,"outing");return s.socialPlans.get("outing");
    }
    private void accept(State s,Resident invited,SocialPlan plan) {
        act(s,invited,ActionType.ACCEPT_PLAN,plan.id,null,null,null,"accept");
    }
    private void at(State s,Resident a,String place) {
        Building b=s.buildings.get(place);a.location=place;a.x=b.x;a.z=b.z;a.pending=null;
    }
    private void untilFinished(State s,SocialPlan plan,List<Engine.Fact> facts) {
        for(int minute=0;minute<600&&!List.of("completed","expired","cancelled").contains(plan.status);minute++)
            facts.addAll(Engine.tick(s).facts);
    }

    @Test void departureTimeAndMeetingAllowForALongActualRoute() {
        State s=State.prototypeSeed();Resident a=resident(s,"a","apt-101"),b=resident(s,"b","apt-101");
        int walking=Social.walkingMinutes(s,a,"park");assertTrue(walking>60);
        SocialPlan p=proposed(s,a,b,"park",1);accept(s,b,p);
        assertTrue(p.meetingMinute>=s.minute+walking+5);
        assertTrue(Social.commitments(s,a).getFirst().due(),"A long route becomes due well before the last fifteen minutes");
        Engine.Effects departure=Engine.tick(s);
        assertEquals("MOVE",a.pending.type);assertEquals(p.id,a.pending.socialPlanId);assertEquals("park",a.pending.target);
        assertEquals(2,departure.starts.size());assertTrue(departure.starts.stream().allMatch(start->start.command().action()==ActionType.JOIN_PLAN));
        List<Engine.Fact> facts=new ArrayList<>();untilFinished(s,p,facts);
        assertEquals("completed",p.status);assertEquals(Set.of(a.id,b.id),p.finished);
        assertTrue(facts.stream().anyMatch(f->f.message().contains("began their agreed activity")));
    }

    @Test void anActualLateJoinGetsBoundedTravelGraceOnlyOnce() {
        State s=State.prototypeSeed();Resident a=resident(s,"a","apt-101"),b=resident(s,"b","apt-101");
        SocialPlan p=proposed(s,a,b,"park",1);accept(s,b,p);
        p.meetingMinute=s.minute+1;p.expires=s.minute+61;
        act(s,a,ActionType.JOIN_PLAN,p.id,null,null,null,"late-departure");
        assertTrue(p.expires>s.minute+61);long extended=p.expires;
        s.minute+=10;Social.joined(s,a,p);assertEquals(extended,p.expires,"Repeated reviews cannot indefinitely renew a plan");
    }

    @Test void theFirstArrivalWaitsForTheRealPartnerAndScheduledMeeting() {
        State s=State.prototypeSeed();Resident a=resident(s,"a","apt-101"),b=resident(s,"b","apt-101");
        SocialPlan p=proposed(s,a,b,"park",1);accept(s,b,p);at(s,a,"park");
        Engine.tick(s);assertEquals("PLAN_WAIT",a.pending.type);assertEquals(p.id,a.pending.socialPlanId);
        assertEquals("accepted",p.status);assertNull(p.startedMinute);assertEquals("MOVE",b.pending.type);
        double x=a.x,z=a.z;
        for(int minute=0;minute<10;minute++)Engine.tick(s);
        assertEquals("PLAN_WAIT",a.pending.type);assertEquals(x,a.x);assertEquals(z,a.z);
        List<Engine.Fact> facts=new ArrayList<>();untilFinished(s,p,facts);assertEquals("completed",p.status);
    }

    @Test void participantsMeetBeforeTheirDistinctMovementsAndTimedActivitiesExecute() {
        State s=State.prototypeSeed();Resident a=resident(s,"diego","park"),b=resident(s,"mei","park");
        SocialPlan p=proposed(s,a,b,"park",1);
        p.tasks.put(a.id,new PlanTask(a.id,"courtyard","Search near the trailhead",4));
        p.tasks.put(b.id,new PlanTask(b.id,"community-garden","Search the open areas",7));accept(s,b,p);
        Engine.tick(s);assertEquals("accepted",p.status);assertEquals("PLAN_WAIT",a.pending.type);
        while(s.minute<p.meetingMinute)Engine.tick(s);
        assertEquals("in_progress",p.status);assertEquals("courtyard",a.pending.target);assertEquals("community-garden",b.pending.target);
        assertEquals("MOVE",a.pending.type);assertEquals("MOVE",b.pending.type);assertTrue(p.finished.isEmpty());
        List<Engine.Fact> facts=new ArrayList<>();untilFinished(s,p,facts);
        assertEquals("completed",p.status);assertEquals("courtyard",a.location);assertEquals("community-garden",b.location);
        assertEquals(Set.of(a.id,b.id),p.finished);assertNotNull(p.startedMinute);assertTrue(p.completedMinute>p.startedMinute);
        assertEquals(1,a.sharedExperiences.size());assertEquals(p.id,a.sharedExperiences.getFirst().plan());
        assertEquals(b.id,a.sharedExperiences.getFirst().other());assertEquals(1,a.sharedExperienceCounts.get(b.id));
        assertTrue(a.relationships.isEmpty(),"Doing a shared activity does not invent a friendship label");
        assertTrue(facts.stream().anyMatch(f->f.actor().equals(a.id)&&f.message().contains("minutes of activity")));
        assertTrue(facts.stream().anyMatch(f->f.actor().equals(b.id)&&f.message().contains("minutes of activity")));
        assertTrue(facts.stream().noneMatch(f->f.message().toLowerCase(Locale.ROOT).contains("found wildflowers")));
    }

    @Test void acceptedStepsAndRealTimedProgressSurviveARestart() throws Exception {
        State s=State.prototypeSeed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        SocialPlan p=proposed(s,a,b,"park",1);accept(s,b,p);
        while(s.minute<p.meetingMinute)Engine.tick(s);
        assertEquals("DO_ACTIVITY",a.pending.type);Engine.tick(s);
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);
        SocialPlan saved=restored.socialPlans.get(p.id);long until=restored.agents.get(a.id).pending.until;
        assertEquals(a.pending.id,restored.agents.get(a.id).pending.id);assertEquals(2,saved.tasks.size());
        List<Engine.Fact> facts=new ArrayList<>();untilFinished(restored,saved,facts);
        assertEquals("completed",saved.status);assertTrue(saved.completedMinute>=until);assertEquals(Set.of(a.id,b.id),saved.finished);
        assertEquals(1,restored.agents.get(a.id).sharedExperiences.size());
        assertEquals(1,Social.situation(restored,restored.agents.get(a.id),Map.of()).sharedExperienceCounts().get(b.id));
        Engine.tick(restored);assertEquals(1,restored.agents.get(a.id).sharedExperienceCounts.get(b.id));
    }

    @Test void cancellationStopsOnlyTheParticipantsOwnedPlanActions() {
        State s=State.prototypeSeed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        Resident other=resident(s,"other","park");SocialPlan p=proposed(s,a,b,"park",1);accept(s,b,p);
        while(s.minute<p.meetingMinute)Engine.tick(s);
        assertEquals("DO_ACTIVITY",a.pending.type);assertEquals("DO_ACTIVITY",b.pending.type);
        other.pending=Engine.pending("other-rest","REST","park",s.minute+30);
        Engine.Effects cancelled=act(s,a,ActionType.CANCEL_PLAN,p.id,null,null,null,"cancel");
        assertEquals("cancelled",p.status);assertNull(a.pending);assertNull(b.pending);assertEquals("other-rest",other.pending.id);
        assertEquals(2,cancelled.completions.size());assertTrue(cancelled.completions.stream().allMatch(c->c.status().equals("cancelled")));
        Engine.tick(s);assertNull(a.pending);assertNull(b.pending);assertTrue(p.finished.isEmpty());
    }

    @Test void noConsentOrExistingWorkRestSleepAndTravelAreNeverOverridden() {
        for(String activity:List.of("WORK","REST","SLEEP","MOVE")) {
            State s=State.prototypeSeed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
            SocialPlan p=proposed(s,a,b,"courtyard",1);Engine.tick(s);assertNull(a.pending);assertNull(b.pending);
            accept(s,b,p);
            a.pending=Engine.pending("existing-"+activity,activity,activity.equals("MOVE")?"community-garden":"park",s.minute+30);
            if(activity.equals("WORK")) {
                a.job="job-office";a.location="office";Building office=s.buildings.get("office");a.x=office.x;a.z=office.z;
                a.pending.target=office.id;a.pending.workDurationMinutes=30;a.pending.startedAt=s.minute;a.pending.trackedWork=true;
                Engine.pay(s,"office","escrow:"+a.pending.id,1000,"wage_reservation",new Engine.Effects());WorkPayroll.begin(s,a,s.jobs.get(a.job));
            }
            if(activity.equals("MOVE")) {
                a.pending.route=Travel.path(s,a,s.buildings.get("community-garden"),"shortest");a.pending.frontDoorRoute=true;a.pending.socialUntil=s.minute+30;
            }
            Pending original=a.pending;
            Engine.tick(s);assertSame(original,a.pending,activity+" remains authoritative");assertEquals("existing-"+activity,a.pending.id);
            assertNull(a.pending.socialPlanId);assertEquals(p.id,b.pending.socialPlanId);
        }
    }

    @Test void aCancelledActivityEndsTheAgreementWithoutClaimingCompletionOrAutomaticallyRestartingIt() {
        State s=State.prototypeSeed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        SocialPlan p=proposed(s,a,b,"park",1);accept(s,b,p);
        while(s.minute<p.meetingMinute)Engine.tick(s);
        String activity=a.pending.id;a.pending=null;
        Engine.Effects effects=new Engine.Effects();effects.completions.add(new Engine.Completion(activity,"cancelled","Venue closed"));
        PlanExecution.tick(s,effects);
        assertTrue(p.finished.isEmpty());assertEquals("cancelled",p.status);assertNull(a.pending);assertNull(b.pending);
        assertTrue(effects.starts.isEmpty());assertTrue(a.sharedExperiences.isEmpty());assertTrue(b.sharedExperiences.isEmpty());
        assertTrue(effects.facts.stream().anyMatch(f->f.kind().equals("social_plan")&&f.message().contains("Venue closed")));
        Engine.tick(s);assertNull(a.pending);assertNull(b.pending);
    }

    private SocialPlan sharedStroll(State s,Resident a,Resident b,int firstMinutes,int secondMinutes) {
        act(s,a,ActionType.PROPOSE_PLAN,b.id,"Stroll around Meadow Commons",a.location,1,"shared-stroll");
        SocialPlan plan=s.socialPlans.get("shared-stroll");
        plan.tasks.put(a.id,new PlanTask(a.id,a.location,plan.activity,firstMinutes));
        plan.tasks.put(b.id,new PlanTask(b.id,b.location,plan.activity,secondMinutes));
        accept(s,b,plan);
        while(s.minute<plan.meetingMinute)Engine.tick(s);
        return plan;
    }
    @Test void engineDoesNotRestartASharedStrollAfterOneParticipantsChosenDurationFinishes() {
        for(boolean reverseOrder:List.of(false,true)) {
            State s=State.seed();s.minute=720;s.agents.clear();
            Resident a=resident(s,"Aisha","meadow-commons"),b=resident(s,"Oliver","meadow-commons");
            if(reverseOrder) {s.agents.clear();s.agents.put(b.id,b);s.agents.put(a.id,a);}
            SocialPlan plan=sharedStroll(s,a,b,2,4);
            String aAction=a.pending.id,bAction=b.pending.id;
            Engine.tick(s);Engine.Effects finished=Engine.tick(s);
            assertNull(a.pending);assertNull(b.pending);assertEquals("cancelled",plan.status);
            assertEquals(Set.of(a.id),plan.finished,"The resident who actually finished keeps their completed task");
            assertEquals(1,a.activityHistory.size());assertEquals(2,a.activityHistory.getFirst().activeMinutes());
            assertTrue(b.activityHistory.isEmpty());assertTrue(a.sharedExperiences.isEmpty());assertTrue(b.sharedExperiences.isEmpty());
            assertTrue(finished.completions.stream().anyMatch(c->c.id().equals(aAction)&&c.status().equals("completed")));
            assertTrue(finished.completions.stream().anyMatch(c->c.id().equals(bAction)&&c.status().equals("cancelled")));
            assertTrue(finished.facts.stream().anyMatch(f->f.kind().equals("social_plan")&&f.message().contains("chosen walking duration")));
            double ax=a.x,az=a.z,bx=b.x,bz=b.z;
            for(int minute=0;minute<5;minute++)assertTrue(Engine.tick(s).starts.isEmpty());
            assertNull(a.pending);assertNull(b.pending);assertEquals(ax,a.x);assertEquals(az,a.z);assertEquals(bx,b.x);assertEquals(bz,b.z);
            act(s,b,ActionType.DO_ACTIVITY,b.location,"Take a quiet solo walk",null,3,"new-choice");
            assertNull(b.pending.socialPlanId);Engine.tick(s);assertEquals("new-choice",b.pending.id);
            assertEquals(1,b.pending.activityExecution.activeMinutes);
        }
    }
    @Test void engineInterruptsASharedStrollWhenAPeerLeavesAndPreservesTheirNewIndependentActivity() {
        for(boolean keepNewActivity:List.of(false,true)) {
            State s=State.seed();s.minute=720;s.agents.clear();
            Resident a=resident(s,"Aisha","meadow-commons"),b=resident(s,"Oliver","meadow-commons");
            SocialPlan plan=sharedStroll(s,a,b,8,8);Engine.tick(s);
            assertEquals(List.of(a.id,b.id),a.pending.activityExecution.walkCompanions);
            double ax=a.x,az=a.z,bx=b.x,bz=b.z;
            b.pending=keepNewActivity?Engine.pending("independent-rest","REST",b.location,s.minute+20):null;
            Engine.Effects stopped=Engine.tick(s);
            assertEquals("cancelled",plan.status);assertTrue(plan.finished.isEmpty());assertNull(a.pending);
            if(keepNewActivity)assertEquals("independent-rest",b.pending.id);else assertNull(b.pending);
            assertTrue(stopped.starts.isEmpty());assertTrue(stopped.facts.stream().anyMatch(f->f.kind().equals("social_plan")&&f.message().contains("interrupted")));
            for(int minute=0;minute<4;minute++)assertTrue(Engine.tick(s).starts.isEmpty());
            assertEquals(ax,a.x);assertEquals(az,a.z);assertEquals(bx,b.x);assertEquals(bz,b.z);
            assertTrue(a.activityHistory.isEmpty());assertTrue(b.activityHistory.isEmpty());
            assertTrue(a.sharedExperiences.isEmpty());assertTrue(b.sharedExperiences.isEmpty());
        }
    }

    @Test void legacyPlansRemainMeetingOnlyAndReceiveNoInventedTask() throws Exception {
        State s=State.prototypeSeed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        SocialPlan p=new SocialPlan();p.id="legacy";p.proposer=a.id;p.invited=b.id;p.destination="park";p.activity="Meet";
        p.status="accepted";p.meetingMinute=s.minute+1;p.expires=s.minute+61;s.socialPlans.put(p.id,p);
        Engine.tick(s);assertEquals("completed",p.status);assertTrue(p.tasks.isEmpty());assertNull(a.pending);assertNull(b.pending);
        ObjectMapper json=new ObjectMapper();SocialPlan old=json.readValue("{\"id\":\"old\",\"status\":\"accepted\"}",SocialPlan.class);
        assertTrue(old.tasks.isEmpty());assertTrue(old.finished.isEmpty());assertTrue(old.joined.isEmpty());
    }
    @Test void aBlockedDepartureReportsTheActualConstraintAndRetriesAtABoundedCadence() {
        State s=State.prototypeSeed();s.minute=1201;Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        SocialPlan p=proposed(s,a,b,"coffee",1);accept(s,b,p);
        Engine.Effects blocked=Engine.tick(s);
        assertNull(a.pending);assertNull(b.pending);assertTrue(blocked.starts.isEmpty());assertTrue(p.finished.isEmpty());
        assertTrue(p.blocked.get(a.id).contains("closed"));assertEquals(s.minute+15,p.retryAfter.get(a.id));
        assertEquals(2,blocked.facts.stream().filter(f->f.kind().equals("social_plan")&&f.message().contains("could not start")).count());
        for(int minute=0;minute<10;minute++)
            assertTrue(Engine.tick(s).facts.stream().noneMatch(f->f.kind().equals("social_plan")&&f.message().contains("could not start")));
        assertEquals("accepted",p.status);assertTrue(a.sharedExperiences.isEmpty());
    }

    private SocialPlan constructionPlan(State s,Resident a,Resident b) {
        Building site=new Building("site","Shared workshop construction","construction","Test",30,30,20);
        s.buildings.put(site.id,site);
        Building entrance=new Building("work-entrance","Workshop entrance","wilderness","Test",30,33.6,20);
        entrance.social=true;entrance.restAllowed=true;s.buildings.put(entrance.id,entrance);at(s,a,entrance.id);at(s,b,entrance.id);
        Construction.Site construction=new Construction.Site();construction.id=site.id;construction.plannedType="bakery";construction.requiredHours=2;
        s.construction.put(site.id,construction);s.accounts.put(Construction.account(site.id),6000L);
        SocialPlan p=proposed(s,a,b,entrance.id,1);
        p.tasks.put(a.id,new PlanTask(a.id,entrance.id,"Build the workshop",1,"BUILD_CONSTRUCTION",site.id,null));
        p.tasks.put(b.id,new PlanTask(b.id,entrance.id,"Build the workshop",1,"BUILD_CONSTRUCTION",site.id,null));
        accept(s,b,p);return p;
    }
    @Test void typedAgreedConstructionCreatesRealWorldProgressAndPaysOnlyCompletedWork() {
        State s=State.prototypeSeed();s.minute=600;Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        SocialPlan p=constructionPlan(s,a,b);long beforeA=a.money,beforeB=b.money;
        while(s.minute<p.meetingMinute)Engine.tick(s);
        assertEquals("BUILD_CONSTRUCTION",a.pending.type);assertEquals("BUILD_CONSTRUCTION",b.pending.type);
        assertEquals(2,s.construction.get("site").reservedHours);assertEquals(beforeA,a.money);
        assertTrue(a.pending.until>s.minute+60,"Reaching the actual work area precedes sixty labor minutes; the prose duration cannot skip approach or work");
        assertEquals("approach",ActionPerformance.view(a).phase());assertEquals(0,a.pending.workMinutes);
        assertTrue(Construction.view(s).sites().stream().filter(site->site.id().equals("site")).findFirst().orElseThrow().workers().isEmpty());
        for(int minute=0;minute<20&&(!ConstructionWork.atStation(a,a.pending)||!ConstructionWork.atStation(b,b.pending));minute++)Engine.tick(s);
        assertTrue(ConstructionWork.atStation(a,a.pending));assertTrue(ConstructionWork.atStation(b,b.pending));
        var workers=Construction.view(s).sites().stream().filter(site->site.id().equals("site")).findFirst().orElseThrow().workers();
        assertEquals(Set.of(a.id,b.id),workers.stream().map(Construction.WorkerView::id).collect(java.util.stream.Collectors.toSet()));
        for(var worker:workers)assertEquals(s.agents.get(worker.id()).pending.workMinutes,worker.workMinutes());
        List<Engine.Fact> facts=new ArrayList<>();untilFinished(s,p,facts);
        assertEquals("completed",p.status);assertEquals(2,s.construction.get("site").completedHours);
        assertEquals("bakery",s.buildings.get("site").type);assertEquals(beforeA+2000,a.money);assertEquals(beforeB+2000,b.money);
        assertEquals(1,a.sharedExperienceCounts.get(b.id));assertEquals(0,s.construction.get("site").reservedHours);
        assertTrue(s.conversations.isEmpty());
        act(s,a,ActionType.MOVE,"work-entrance",null,null,null,"leave-finished-work-a");
        act(s,b,ActionType.MOVE,"work-entrance",null,null,null,"leave-finished-work-b");
        for(int minute=0;minute<30&&(a.pending!=null||b.pending!=null);minute++)Engine.tick(s);
        assertNull(a.pending);assertNull(b.pending);
        assertTrue(Social.situation(s,a,Map.of()).encounters().stream().filter(encounter->encounter.other().equals(b.id)).findFirst().orElseThrow().metBefore());
        assertTrue(a.relationships.isEmpty());
    }
    @Test void cancellingAnAgreedConstructionHourReturnsEscrowWithoutInventingProgressOrPay() {
        State s=State.prototypeSeed();s.minute=600;Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        SocialPlan p=constructionPlan(s,a,b);long beforeA=a.money;
        while(s.minute<p.meetingMinute)Engine.tick(s);
        String aWork=a.pending.id,bWork=b.pending.id;for(int minute=0;minute<10;minute++)Engine.tick(s);
        act(s,a,ActionType.CANCEL_PLAN,p.id,null,null,null,"cancel-building");
        assertEquals(6000L,s.accounts.get(Construction.account("site")));assertEquals(0,s.construction.get("site").reservedHours);
        assertEquals(0,s.construction.get("site").completedHours);assertEquals(beforeA,a.money);
        assertFalse(s.accounts.containsKey("escrow:construction:"+aWork));assertFalse(s.accounts.containsKey("escrow:construction:"+bWork));
        assertNull(a.pending);assertNull(b.pending);assertTrue(a.sharedExperiences.isEmpty());assertEquals("cancelled",p.status);
    }
    @Test void aMatchingResidentAuthoredActivityFulfillsThePlanWithoutAutomaticRepetition() {
        State s=State.prototypeSeed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        SocialPlan p=proposed(s,a,b,"park",1);accept(s,b,p);
        p.status="in_progress";p.startedMinute=s.minute;
        act(s,a,ActionType.DO_ACTIVITY,null,"Search for wildflowers",null,15,"manual-search");
        assertEquals(p.id,a.pending.socialPlanId);assertEquals("manual-search",p.activityActions.get(a.id));
        List<Engine.Fact> facts=new ArrayList<>();untilFinished(s,p,facts);
        assertEquals("completed",p.status);assertEquals("manual-search",p.activityActions.get(a.id));
        assertTrue(facts.stream().noneMatch(f->f.actor().equals(a.id)&&f.message().contains("began an activity")));
        assertEquals(1,a.sharedExperienceCounts.get(b.id));
        for(int minute=0;minute<20;minute++)assertTrue(Engine.tick(s).starts.stream().noneMatch(start->start.resident().equals(a.id)));
    }
    @Test void residentAuthoredConstructionContributesOnlyOneBilledHourToTheAssignedTask() {
        State s=State.prototypeSeed();s.minute=600;Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        SocialPlan p=constructionPlan(s,a,b);Construction.Site site=s.construction.get("site");site.requiredHours=4;
        s.accounts.put(Construction.account(site.id),12000L);long before=a.money;
        p.status="in_progress";p.startedMinute=s.minute;
        act(s,a,ActionType.BUILD_CONSTRUCTION,site.id,null,null,null,"manual-building");
        assertEquals(p.id,a.pending.socialPlanId);assertEquals("manual-building",p.activityActions.get(a.id));
        List<Engine.Fact> facts=new ArrayList<>();untilFinished(s,p,facts);
        assertEquals("completed",p.status);assertEquals(2,site.completedHours);assertEquals(before+2000,a.money);
        assertEquals(6000L,s.accounts.get(Construction.account(site.id)));assertEquals("manual-building",p.activityActions.get(a.id));
        assertTrue(facts.stream().noneMatch(f->f.actor().equals(a.id)&&f.message().contains("chose an hour")));
        for(int minute=0;minute<65;minute++)Engine.tick(s);
        assertEquals(2,site.completedHours);assertEquals(before+2000,a.money);assertEquals(1,a.sharedExperienceCounts.get(b.id));
    }
    @Test void aShorterOrDifferentResidentActivityDoesNotFalselySatisfyTheAssignedTask() {
        State s=State.prototypeSeed();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        SocialPlan p=proposed(s,a,b,"park",1);accept(s,b,p);p.status="in_progress";p.startedMinute=s.minute;
        act(s,a,ActionType.DO_ACTIVITY,"park","Search for wildflowers",null,3,"brief-search");
        assertNull(a.pending.socialPlanId);assertFalse(p.activityActions.containsKey(a.id));
        for(int minute=0;minute<3;minute++)Engine.tick(s);
        assertFalse(p.finished.contains(a.id));assertNotEquals("brief-search",p.activityActions.get(a.id));
        assertEquals("DO_ACTIVITY",a.pending.type);assertEquals(p.id,a.pending.socialPlanId);
    }
}
