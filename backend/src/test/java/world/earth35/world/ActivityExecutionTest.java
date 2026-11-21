package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class ActivityExecutionTest {
    private State world() {State s=State.prototypeSeed();s.minute=720;return s;}
    private Resident resident(State s,String id,String place) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=place;
        Building b=s.buildings.get(place);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    private Engine.Effects start(State s,Resident a,String label,String mode,int minutes) {
        Engine.Effects e=new Engine.Effects();
        Engine.apply(s,a,new Command("activity-"+s.minute,ActionType.DO_ACTIVITY,a.location,label,mode,minutes,null),"activity-"+s.minute,e);
        return e;
    }
    @Test void nativePlantSearchMovesThenInspectsAndReportsOnlyExecutedEvidence() {
        State s=world();Resident a=resident(s,"mei","park");double x=a.x,z=a.z;Map<String,Integer> inventory=new LinkedHashMap<>(a.inventory);
        start(s,a,"Search for native plants like purple coneflowers and black-eyed Susans",null,15);
        assertEquals("search",ActivityExecution.view(a).mode());assertEquals("travel",ActivityExecution.view(a).phase());
        Engine.Effects first=Engine.tick(s);
        assertTrue(Math.hypot(a.x-x,a.z-z)>.1);assertTrue(Math.hypot(a.x-x,a.z-z)<=CityLayout.of(s).walkingSpeed()+1e-7);
        assertEquals("park",a.location);assertEquals(1,ActivityExecution.view(a).activeMinutes());assertEquals(0,ActivityExecution.view(a).inspectedStops());
        assertTrue(first.completions.isEmpty());assertEquals("inspect",ActivityExecution.view(a).phase());
        double stopX=a.x,stopZ=a.z;Engine.tick(s);
        assertEquals(stopX,a.x);assertEquals(stopZ,a.z);assertEquals(1,ActivityExecution.view(a).inspectedStops());
        List<Engine.Fact> facts=new ArrayList<>();List<Engine.Completion> completions=new ArrayList<>();
        for(int i=0;i<30&&a.pending!=null;i++) {
            double px=a.x,pz=a.z;Engine.Effects e=Engine.tick(s);facts.addAll(e.facts);completions.addAll(e.completions);
            assertTrue(Math.hypot(a.x-px,a.z-pz)<=CityLayout.of(s).walkingSpeed()+1e-7,"Movement cannot teleport between inspection stops");
            assertTrue(Math.hypot(a.x-x,a.z-z)<=4+1e-7,"Search stays on bounded local grounds");
            assertFalse(CityLayout.of(s).onRoad(a.x,a.z));
        }
        assertNull(a.pending);assertEquals(1,completions.size());assertEquals("completed",completions.getFirst().status());
        ActivityExecution.Outcome outcome=a.activityHistory.getFirst();assertEquals("search",outcome.mode());assertEquals(15,outcome.activeMinutes());
        assertTrue(outcome.distance()>0);assertTrue(outcome.inspectedStops()>1);assertEquals(inventory,a.inventory);
        assertTrue(completions.getFirst().result().contains("No species discoveries, inventory changes"));
        assertTrue(facts.stream().anyMatch(f->f.message().contains("finished 15 minutes of activity")&&f.message().contains("Measured execution")));
        assertTrue(facts.stream().noneMatch(f->f.message().toLowerCase(Locale.ROOT).contains("found purple")));
    }
    @Test void explicitStationaryModeRecordsTimeWithoutClaimingTheLabelOccurred() {
        State s=world();Resident a=resident(s,"artist","park");double x=a.x,z=a.z;
        start(s,a,"Sketch an idea for a building","stationary",3);
        Engine.tick(s);Engine.tick(s);Engine.Effects finish=Engine.tick(s);
        assertNull(a.pending);assertEquals(x,a.x);assertEquals(z,a.z);
        assertEquals(0,a.activityHistory.getFirst().distance());assertEquals(0,a.activityHistory.getFirst().inspectedStops());
        assertTrue(finish.completions.getFirst().result().contains("Only time spent was recorded"));
        assertTrue(a.creations.isEmpty());
    }
    @Test void modeInferenceRecognizesActualDurationSentenceAndExplicitModeWins() {
        assertEquals("search",ActivityExecution.mode(null,"Spend 15 minutes searching for purple coneflowers, then reassess"));
        assertEquals("search",ActivityExecution.mode(null,"Look for black-eyed Susans"));
        assertEquals("walk",ActivityExecution.mode(null,"Spend time strolling through the park"));
        assertEquals("observe",ActivityExecution.mode(null,"Watch the birds"));
        assertEquals("stationary",ActivityExecution.mode("stationary","Search notes for an old idea"));
        assertThrows(Engine.Invalid.class,()->ActivityExecution.mode("teleport","Go somewhere"));
    }
    @Test void walkActuallyTravelsWhileObservationKeepsItsOrientationWithoutInventingMotion() {
        State s=world();Resident walker=resident(s,"walker","park"),observer=resident(s,"observer","park");
        double x=walker.x,z=walker.z,ox=observer.x,oz=observer.z;
        start(s,walker,"Stroll around the park","walk",4);start(s,observer,"Watch the birds","observe",4);
        Double firstHeading=observer.facingHeading;Engine.tick(s);
        assertTrue(Math.hypot(walker.x-x,walker.z-z)>.1);assertEquals("travel",ActivityExecution.view(walker).phase());
        assertEquals("perform",ActivityExecution.view(observer).phase());Engine.tick(s);assertEquals(firstHeading,observer.facingHeading);
        Engine.tick(s);Engine.tick(s);
        assertNull(walker.pending);assertNull(observer.pending);assertEquals(ox,observer.x);assertEquals(oz,observer.z);
        assertTrue(walker.activityHistory.getFirst().distance()>1);assertEquals(0,walker.activityHistory.getFirst().inspectedStops());
        assertEquals(0,observer.activityHistory.getFirst().distance());assertEquals(4,observer.activityHistory.getFirst().activeMinutes());
    }
    @Test void searchRejectsRoadsEnclosedPlacesAndUnsupportedOperationsAtomically() {
        State s=world();Resident a=resident(s,"a","park");
        for(String label:List.of("Plant native flowers","Build a workshop","Harvest wildflowers","Buy a meal","Research a new device")) {
            assertThrows(Engine.Invalid.class,()->start(s,a,label,null,15));assertNull(a.pending);assertTrue(a.activityHistory.isEmpty());
        }
        assertThrows(Engine.Invalid.class,()->start(s,a,"Search for flowers",null,1));assertNull(a.pending);
        a.location="road";a.x=24;a.z=24;
        assertThrows(Engine.Invalid.class,()->start(s,a,"Search for flowers",null,15));assertNull(a.pending);
        Building home=s.buildings.get(a.home);a.location=a.home;a.x=home.x;a.z=home.z;
        assertThrows(Engine.Invalid.class,()->start(s,a,"Search for flowers",null,15));assertNull(a.pending);
    }
    @Test void blockedPhysicalStepsDoNotEarnActiveMinutesOrCompleteOnTheOldTimer() {
        State s=world();Resident a=resident(s,"a","park");start(s,a,"Search for flowers",null,2);
        long oldTimer=a.pending.until;double x=a.x,z=a.z;
        Traffic.Vehicle blocker=new Traffic.Vehicle("blocker","bus","","",x,z,0,"",0,List.of());
        for(int i=0;i<8;i++) {
            s.minute++;Engine.Effects e=new Engine.Effects();ActivityExecution.tick(s,a,e,List.of(blocker),List.of());
            assertTrue(e.completions.isEmpty());assertEquals(0,ActivityExecution.view(a).activeMinutes());assertEquals(x,a.x);assertEquals(z,a.z);
        }
        assertTrue(s.minute>oldTimer);assertEquals("blocked",ActivityExecution.view(a).phase());assertTrue(a.pending.until>s.minute);
        assertTrue(a.activityHistory.isEmpty());
        s.minute++;ActivityExecution.tick(s,a,new Engine.Effects(),List.of(),List.of());
        assertEquals(1,ActivityExecution.view(a).activeMinutes());assertEquals("inspect",ActivityExecution.view(a).phase());
        s.minute++;Engine.Effects completed=new Engine.Effects();ActivityExecution.tick(s,a,completed,List.of(),List.of());
        assertNull(a.pending);assertEquals(1,completed.completions.size());assertEquals(2,a.activityHistory.getFirst().activeMinutes());
    }
    @Test void physicalSearchProgressAndBoundedHistorySurviveRestart() throws Exception {
        State original=world();Resident a=resident(original,"a","park");start(original,a,"Search the open areas",null,7);
        Engine.tick(original);Engine.tick(original);Engine.tick(original);
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(original),State.class);Resident b=restored.agents.get(a.id);
        assertEquals(ActivityExecution.view(a),ActivityExecution.view(b));assertEquals(a.pending.id,b.pending.id);
        for(int i=0;i<20&&a.pending!=null;i++) {
            Engine.Effects before=Engine.tick(original),after=Engine.tick(restored);
            assertEquals(a.x,b.x,1e-9);assertEquals(a.z,b.z,1e-9);assertEquals(ActivityExecution.view(a),ActivityExecution.view(b));
            assertEquals(before.completions,after.completions);
        }
        assertNull(a.pending);assertEquals(a.activityHistory,b.activityHistory);assertEquals(1,b.activityHistory.size());
        for(int i=0;i<14;i++) {start(restored,b,"Reflect on the day","stationary",1);Engine.tick(restored);}
        assertEquals(12,b.activityHistory.size());assertTrue(b.activityHistory.stream().allMatch(o->o.mode().equals("stationary")));
    }
    @Test void pausingAtAReachedStopPreservesTheInspectionAndNeverCreditsPausedMinutes() {
        State s=world();Resident a=resident(s,"a","park");start(s,a,"Search the park",null,2);Engine.tick(s);
        assertEquals("inspect",ActivityExecution.view(a).phase());double x=a.x,z=a.z;
        a.paused=true;for(int i=0;i<5;i++)Engine.tick(s);
        assertEquals("blocked",ActivityExecution.view(a).phase());assertEquals(1,ActivityExecution.view(a).activeMinutes());
        assertEquals(x,a.x);assertEquals(z,a.z);assertTrue(a.activityHistory.isEmpty());
        a.paused=false;Engine.Effects completed=Engine.tick(s);
        assertNull(a.pending);assertEquals(1,completed.completions.size());assertEquals(1,a.activityHistory.getFirst().inspectedStops());
        assertEquals(2,a.activityHistory.getFirst().activeMinutes());
    }
    @Test void oldSavedTimersStartWithZeroExecutionAndCannotInventCompletion() {
        State s=world();Resident a=resident(s,"legacy","park");
        a.pending=Engine.pending("saved","DO_ACTIVITY","park",s.minute-1);a.pending.startedAt=s.minute-16;a.pending.detail="Search for native plants";
        Engine.Effects resumed=Engine.tick(s);
        assertNotNull(a.pending);assertEquals(0,ActivityExecution.view(a).activeMinutes());assertTrue(resumed.completions.isEmpty());assertTrue(a.activityHistory.isEmpty());
        assertTrue(resumed.facts.stream().anyMatch(f->f.message().contains("progress starts now")));
        for(int i=0;i<15;i++)Engine.tick(s);
        assertNull(a.pending);assertEquals(15,a.activityHistory.getFirst().activeMinutes());assertTrue(a.activityHistory.getFirst().distance()>0);
    }
    @Test void aSharedSearchCompletesOnlyAfterBothResidentsActuallyExecuteIt() {
        State s=world();Resident a=resident(s,"a","park"),b=resident(s,"b","park");
        Engine.apply(s,a,new Command("plan",ActionType.PROPOSE_PLAN,b.id,"Search for native plants","park",1,null),"plan",new Engine.Effects());
        SocialPlan p=s.socialPlans.get("plan");p.tasks.put(a.id,new PlanTask(a.id,"park","Search for native plants",2));
        p.tasks.put(b.id,new PlanTask(b.id,"park","Search for native plants",2));
        Engine.apply(s,b,new Command("accept",ActionType.ACCEPT_PLAN,p.id,null,null,null,null),"accept",new Engine.Effects());
        while(s.minute<p.meetingMinute)Engine.tick(s);
        assertEquals("in_progress",p.status);double x=a.x,z=a.z;
        a.paused=true;for(int i=0;i<8;i++)Engine.tick(s);
        assertEquals("in_progress",p.status);assertEquals(Set.of(b.id),p.finished);assertEquals(x,a.x);assertEquals(z,a.z);assertTrue(a.activityHistory.isEmpty());
        a.paused=false;Engine.tick(s);assertTrue(Math.hypot(a.x-x,a.z-z)>.1);assertEquals("in_progress",p.status);
        Engine.tick(s);assertEquals("completed",p.status);assertEquals(Set.of(a.id,b.id),p.finished);
        assertEquals(1,a.activityHistory.size());assertEquals(1,a.sharedExperienceCounts.get(b.id));
    }
    @Test void dryIslandAndSlopeRulesConstrainEveryLocalSearchStep() {
        State s=State.seed();s.geographyVersion=4;s.minute=720;s.buildings.clear();
        Building grounds=new Building("grounds","Hillside","wilderness","",-250,-350,5);grounds.outdoorRadius=8;s.buildings.put(grounds.id,grounds);
        Resident a=resident(s,"walker",grounds.id);start(s,a,"Search the hillside",null,12);
        int inspected=0;double distance=0;
        for(int i=0;i<30&&a.pending!=null;i++) {
            double x=a.x,z=a.z;Engine.tick(s);
            assertTrue(IslandGeography.dryLand(a.x,a.z));assertTrue(IslandLandscapes.clear(a.x,a.z));assertTrue(IslandGeography.roadDistance(a.x,a.z)>=2.2);
            assertTrue(TerrainElevation.segmentEffort(x,z,a.x,a.z)<=CityLayout.of(s).walkingSpeed()+.03);
            assertTrue(Math.hypot(a.x-grounds.x,a.z-grounds.z)<=4+1e-7);
            if(a.pending!=null) {inspected=ActivityExecution.view(a).inspectedStops();distance=ActivityExecution.view(a).distance();}
        }
        assertNull(a.pending);assertTrue(inspected>0);assertTrue(distance>0);assertTrue(a.activityHistory.getFirst().inspectedStops()>0);
    }
    @Test void gardenInspectionRequiresMeasuredTravelEvenWhenStationaryWasRequested() {
        State s=world();Resident a=resident(s,"oliver","park");double x=a.x,z=a.z;
        String label="Check sunlight, water access, drainage, and shade to assess starting a garden";
        start(s,a,label,"stationary",3);
        assertEquals("search",ActivityExecution.view(a).mode());
        Engine.tick(s);Engine.tick(s);Engine.tick(s);
        assertNull(a.pending);ActivityExecution.Outcome outcome=a.activityHistory.getFirst();
        assertTrue(outcome.distance()>0);assertTrue(outcome.inspectedStops()>0);assertTrue(Math.hypot(a.x-x,a.z-z)>0);
        for(String verb:List.of("Check","Checking","Inspect","Assess","Evaluate","Survey","Examine","Measure"))
            assertEquals("search",ActivityExecution.mode("stationary",verb+" sunlight and shade at the garden site"));
        assertEquals("stationary",ActivityExecution.mode("stationary","Discuss sunlight and shade requirements for a garden"));
    }
    @Test void sunlightInspectionsRejectNightAndWindowsThatCannotFinishBeforeSunsetAtomically() {
        for(long minute:List.of(359L,1080L,1140L,1179L,1439L,1440L+1179)) {
            State s=world();s.minute=minute;Resident a=resident(s,"oliver","park");
            Engine.Invalid invalid=assertThrows(Engine.Invalid.class,()->start(s,a,"Check sunlight and shade","stationary",2));
            assertTrue(invalid.getMessage().contains("daylight"));assertNull(a.pending);assertTrue(a.activityHistory.isEmpty());
        }
        State s=world();s.minute=1079;Resident a=resident(s,"oliver","park");
        assertThrows(Engine.Invalid.class,()->start(s,a,"Inspect sunlight and drainage",null,2));assertNull(a.pending);
        s.minute=1179;start(s,a,"Discuss sunlight and drainage requirements for a future garden","stationary",2);
        assertNotNull(a.pending,"Planning sunlight requirements remains possible after sunset");
        Resident b=resident(s,"b","park");start(s,b,"Spend 15 minutes discussing sunlight and drainage requirements","stationary",2);
        assertEquals("stationary",ActivityExecution.view(b).mode());
    }
    @Test void daylightEndingDuringDelayedInspectionCancelsWithoutInventingCompletion() {
        State s=world();s.minute=1077;Resident a=resident(s,"oliver","park");start(s,a,"Check sunlight and shade",null,2);
        a.paused=true;Engine.tick(s);Engine.tick(s);Engine.tick(s);a.paused=false;
        Engine.Effects ended=Engine.tick(s);
        assertNull(a.pending);assertTrue(a.activityHistory.isEmpty());assertEquals(1,ended.completions.size());
        assertEquals("cancelled",ended.completions.getFirst().status());assertTrue(ended.completions.getFirst().result().contains("Daylight ended"));
        assertTrue(ended.facts.stream().noneMatch(f->f.message().contains("finished")));
    }
    @Test void namedCompanionsMustActuallyBeNearbyBeforeTheActivityStarts() {
        State s=world();Resident oliver=resident(s,"oliver","park"),aisha=resident(s,"aisha","park"),diego=resident(s,"diego","library");
        oliver.name="Oliver Reed";aisha.name="Aisha Khan";diego.name="Diego Ruiz";
        String label="Check sunlight, water access, drainage, and shade with Aisha and Diego to assess starting a garden together";
        Engine.Invalid invalid=assertThrows(Engine.Invalid.class,()->start(s,oliver,label,"stationary",3));
        assertTrue(invalid.getMessage().contains("Diego Ruiz is not physically present"));assertNull(oliver.pending);assertTrue(oliver.activityHistory.isEmpty());
        diego.location=oliver.location;diego.x=oliver.x+20;diego.z=oliver.z;
        assertThrows(Engine.Invalid.class,()->start(s,oliver,label,"stationary",3),"A matching place label does not establish nearby presence");
        diego.x=oliver.x;start(s,oliver,label,"stationary",3);assertNotNull(oliver.pending);assertEquals("search",ActivityExecution.view(oliver).mode());
        assertNull(aisha.pending);assertNull(diego.pending,"Another resident is never enrolled by a free-text activity label");
    }
    @Test void aCompanionLeavingCancelsInsteadOfAccumulatingAnImaginedSharedActivity() {
        State s=world();Resident oliver=resident(s,"oliver","park"),aisha=resident(s,"aisha","park");
        aisha.name="Aisha Khan";start(s,oliver,"Check drainage with Aisha",null,4);Engine.tick(s);
        assertEquals(1,ActivityExecution.view(oliver).activeMinutes());aisha.x+=30;
        Engine.Effects ended=Engine.tick(s);
        assertNull(oliver.pending);assertTrue(oliver.activityHistory.isEmpty());assertEquals(1,ended.completions.size());
        assertEquals("cancelled",ended.completions.getFirst().status());assertTrue(ended.completions.getFirst().result().contains("Aisha Khan is no longer physically present"));
        assertTrue(ended.facts.stream().noneMatch(f->f.message().contains("finished")));
    }
    @Test void theFinalWalkingStepCannotCompleteAfterLeavingACompanionsRange() {
        State s=world();Resident oliver=resident(s,"oliver","park"),aisha=resident(s,"aisha","park");aisha.name="Aisha Khan";
        start(s,oliver,"Walk with Aisha","walk",1);
        double[] first=oliver.pending.activityExecution.stops.getFirst();double dx=first[0]-oliver.x,dz=first[1]-oliver.z,length=Math.hypot(dx,dz);
        aisha.x=oliver.x-dx/length*9.9;aisha.z=oliver.z-dz/length*9.9;
        assertTrue(ActivityGrounding.present(s,oliver,aisha));
        Engine.Effects ended=Engine.tick(s);
        assertNull(oliver.pending);assertTrue(oliver.activityHistory.isEmpty());assertEquals(1,ended.completions.size());
        assertEquals("cancelled",ended.completions.getFirst().status());assertFalse(ActivityGrounding.present(s,oliver,aisha));
        assertTrue(ended.facts.stream().noneMatch(f->f.message().contains("finished")));
    }
    @Test void persistedStationaryInspectionCannotFinishUsingItsOldUnmeasuredTimer() throws Exception {
        State s=world();Resident a=resident(s,"legacy","park");Pending pending=Engine.pending("saved","DO_ACTIVITY","park",s.minute+1);
        ActivityExecution.Progress p=new ActivityExecution.Progress();p.mode="stationary";p.phase="perform";p.location=a.location;
        p.label="Check sunlight, drainage, and shade";p.requestedMinutes=3;p.activeMinutes=2;p.lastMinute=s.minute;
        pending.activityExecution=p;pending.detail=p.label;a.pending=pending;
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);Resident saved=restored.agents.get(a.id);
        Engine.Effects ended=Engine.tick(restored);
        assertNull(saved.pending);assertTrue(saved.activityHistory.isEmpty());assertEquals(1,ended.completions.size());
        assertEquals("cancelled",ended.completions.getFirst().status());assertTrue(ended.completions.getFirst().result().contains("physical inspection route"));
        assertTrue(ended.facts.stream().noneMatch(f->f.message().contains("finished")));
    }
    @Test void companionNameListsUseActualResidentsAndStopBeforeTheDiscussionTopic() {
        State s=world();Resident oliver=resident(s,"oliver","park"),aisha=resident(s,"aisha","park"),diego=resident(s,"diego","library");
        oliver.name="Oliver Reed";aisha.name="Aisha Khan";diego.name="Diego Ruiz";
        assertEquals(List.of(aisha,diego),ActivityGrounding.companions(s,oliver,"Inspect the plot with Aisha Khan, and Diego Ruiz to assess shade"));
        assertEquals(List.of(aisha),ActivityGrounding.companions(s,oliver,"Discuss with Aisha about Diego's garden proposal"));
        assertEquals(List.of(aisha,diego),ActivityGrounding.companions(s,oliver,"I'm here with Aisha and Diego"));
        assertTrue(ActivityGrounding.companions(s,oliver,"Think about Aisha and Diego's garden proposal").isEmpty());
        assertTrue(ActivityGrounding.companions(s,oliver,"Review the proposal with Aisha's notes").isEmpty());
        assertTrue(ActivityGrounding.present(s,oliver,aisha));assertFalse(ActivityGrounding.present(s,oliver,diego));
        aisha.location="apt-101";aisha.x=oliver.x;aisha.z=oliver.z;
        assertFalse(ActivityGrounding.present(s,oliver,aisha),"Matching coordinates cannot establish presence across different indoor spaces");
    }
    @Test void ambiguousCompanionFirstNamesRequireAFullNameAndCannotSilentlyGrantAttendance() {
        State s=world();Resident oliver=resident(s,"oliver","park"),first=resident(s,"aisha-one","park"),second=resident(s,"aisha-two","library");
        first.name="Aisha Khan";second.name="Aisha Patel";
        Engine.Invalid invalid=assertThrows(Engine.Invalid.class,()->start(s,oliver,"Check drainage with Aisha",null,3));
        assertTrue(invalid.getMessage().contains("ambiguous"));assertNull(oliver.pending);assertTrue(oliver.activityHistory.isEmpty());
        start(s,oliver,"Check drainage with Aisha Khan",null,3);assertNotNull(oliver.pending);
        assertEquals(List.of(second),ActivityGrounding.companions(s,oliver,"Discuss the garden with aisha-two"));
    }
    @Test void soloReflectionCanReferToPastConversationsAndFutureMeetingsWithoutTheirParticipantsPresent() {
        for(String label:List.of("Reflect on yesterday's conversation with Aisha","Think about meeting with Aisha tomorrow")) {
            State s=world();Resident oliver=resident(s,"oliver","park"),aisha=resident(s,"aisha","library");aisha.name="Aisha Khan";
            start(s,oliver,label,null,2);
            assertEquals("stationary",ActivityExecution.view(oliver).mode());
            Engine.tick(s);Engine.Effects completed=Engine.tick(s);
            assertNull(oliver.pending);assertEquals(1,oliver.activityHistory.size());
            assertEquals("completed",completed.completions.getFirst().status());
            assertEquals(0,oliver.activityHistory.getFirst().distance());
            assertNull(aisha.pending);
        }
    }
    @Test void actualJointReflectionDiscussionAndInspectionStillRequireTheNamedResidentNow() {
        for(String label:List.of("Reflect with Aisha","Discuss garden with Aisha","Check drainage with Aisha",
            "Reflect with Aisha on yesterday's conversation","Reflect on yesterday's conversation with Aisha together with Diego")) {
            State s=world();Resident oliver=resident(s,"oliver","park"),aisha=resident(s,"aisha","library"),diego=resident(s,"diego","park");
            aisha.name="Aisha Khan";diego.name="Diego Ruiz";
            assertThrows(Engine.Invalid.class,()->start(s,oliver,label,"stationary",2),label);
            assertNull(oliver.pending);assertTrue(oliver.activityHistory.isEmpty());
        }
    }
}
