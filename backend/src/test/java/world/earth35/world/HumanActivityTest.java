package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class HumanActivityTest {
    private Resident resident(State s,String id,String location) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=location;
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    private void act(State s,Resident a,ActionType action,String target,String item,Integer minutes) {
        String id=UUID.randomUUID().toString();Engine.apply(s,a,new Command(id,action,target,null,item,minutes,null),id,new Engine.Effects());
    }
    private void minutes(State s,int count) {for(int i=0;i<count;i++)Engine.tick(s);}
    @ParameterizedTest @ValueSource(strings={"WORK","REST","READ_BOOK","DO_ACTIVITY","RESEARCH_PROJECT","BUILD_PROJECT","PRODUCE_PRODUCT","PLACE_OBJECT","MOVE_OBJECT","WAIT_RAIN","PLAN_WAIT"})
    void intentionalBreaksPersistAcrossRestartAndCannotCreateWorkLearningOrCompletion(String type) throws Exception {
        State s=State.prototypeSeed();s.minute=600;Resident a=resident(s,"alex","office");a.job="job-office";
        Pending p=Engine.pending("task",type,"office",660);p.startedAt=600;p.workMinutes=7;p.readingMinutes=4;p.lastWorkedMinute=600;
        p.workDurationMinutes=60;p.trackedWork="WORK".equals(type);p.regularWorkMinutes=7;a.pending=p;
        act(s,a,ActionType.PAUSE_ACTIVITY,p.id,null,null);long money=a.money;
        var json=new ObjectMapper();State saved=json.readValue(json.writeValueAsString(s),State.class);Resident restored=saved.agents.get(a.id);
        minutes(saved,10);assertNotNull(restored.pending,type);assertTrue(restored.pending.activityPaused);
        assertEquals(7,restored.pending.workMinutes);assertEquals(4,restored.pending.readingMinutes);
        assertEquals(0,restored.pending.activeMinutes);assertEquals(money,restored.money);assertTrue(restored.knowledge.isEmpty());
        assertEquals("paused",ActionPerformance.view(restored).phase());assertEquals("idle",ActionPerformance.view(restored).gesture());
        assertEquals(type,restored.pending.type);assertEquals("task",restored.pending.id);
    }
    @Test void jobBreaksEarnNothingAndStoppingRetainsJobAndOnlyActualEarnedWages() {
        State s=State.prototypeSeed();s.minute=600;Resident a=resident(s,"alex","office");a.job="job-office";
        long money=a.money,employer=s.accounts.get("office");act(s,a,ActionType.WORK,null,null,null);String id=a.pending.id;
        minutes(s,20);act(s,a,ActionType.PAUSE_ACTIVITY,id,null,null);minutes(s,10);assertEquals(20,a.pending.workMinutes);
        act(s,a,ActionType.RESUME_ACTIVITY,id,null,null);minutes(s,15);act(s,a,ActionType.STOP_ACTIVITY,id,null,null);
        assertNull(a.pending);assertEquals("job-office",a.job);assertEquals(35,a.workedMinutes);assertEquals(money,a.money);
        long earned=2000L*35/60;assertEquals(earned,WorkPayroll.view(a).getFirst().earnedCents());assertEquals(employer-earned,s.accounts.get("office"));
        assertFalse(s.accounts.containsKey("escrow:"+id));minutes(s,(int)(1020-s.minute));assertEquals(money+earned,a.money);
    }
    @Test void aPausedJobBlockEndsOnItsOriginalScheduleWithoutPayingForBreaks() {
        State s=State.prototypeSeed();s.minute=990;Resident a=resident(s,"alex","office");a.job="job-office";
        act(s,a,ActionType.WORK,null,null,null);assertEquals(1020,a.pending.until);minutes(s,12);
        act(s,a,ActionType.PAUSE_ACTIVITY,null,null,null);minutes(s,18);assertNull(a.pending);assertEquals(12,a.workedMinutes);
        assertEquals(100000+400,a.money);assertTrue(a.workPayroll.isEmpty());
    }
    @Test void workersChooseWhetherToRespondAndCanEatAndConverseOnTheirOwnBreaks() {
        State s=State.prototypeSeed();s.minute=600;Resident a=resident(s,"alex","office"),b=resident(s,"sam","office");a.job="job-office";b.job="job-office";
        act(s,a,ActionType.WORK,null,null,null);act(s,b,ActionType.WORK,null,null,null);minutes(s,8);
        int alexWorked=a.pending.workMinutes,samWorked=b.pending.workMinutes;assertTrue(alexWorked>0);assertTrue(samWorked>0);
        assertFalse(Social.conversationAvailable(a));assertThrows(Engine.Invalid.class,()->Engine.apply(s,b,new Command("talk",ActionType.TALK,a.id,"Time for lunch?",null,null,null),"talk",new Engine.Effects()));
        act(s,a,ActionType.PAUSE_ACTIVITY,null,null,null);act(s,b,ActionType.PAUSE_ACTIVITY,null,null,null);
        act(s,a,ActionType.EAT,null,"meal",null);assertEquals("eat",ActionPerformance.view(a).gesture());assertEquals(alexWorked,a.pending.workMinutes);
        Engine.apply(s,b,new Command("talk",ActionType.TALK,a.id,"How is the project going?",null,null,null),"talk",new Engine.Effects());
        Engine.apply(s,a,new Command("reply",ActionType.TALK,b.id,"It is going well. I needed a short break.",null,null,null),"reply",new Engine.Effects());
        assertEquals("listen",ActionPerformance.view(b).gesture());minutes(s,3);assertEquals(alexWorked,a.pending.workMinutes);assertEquals(samWorked,b.pending.workMinutes);
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.RESUME_ACTIVITY,null,null,null));
        Engine.apply(s,a,new Command("bye",ActionType.END_CHAT,b.id,"Back to it. See you later!",null,null,null),"bye",new Engine.Effects());
        act(s,a,ActionType.RESUME_ACTIVITY,null,null,null);minutes(s,2);assertEquals(alexWorked+2,a.pending.workMinutes);assertEquals(samWorked,b.pending.workMinutes);
    }
    @Test void removingAJobCannotCrashTheWorldOrLoseTheEmployeesEarnedPay() {
        State s=State.prototypeSeed();s.minute=600;Resident a=resident(s,"alex","office");a.job="job-office";act(s,a,ActionType.WORK,null,null,null);minutes(s,15);
        s.jobs.remove(a.job);assertDoesNotThrow(()->Engine.tick(s));assertNull(a.pending);assertEquals(500,WorkPayroll.view(a).getFirst().earnedCents());
        minutes(s,(int)(1020-s.minute));assertEquals(100500,a.money);
    }
    @Test void lateWorkAfterABreakUsesActualOvertimeAndWaitsForTheAccessibleClosingPayment() {
        State s=State.prototypeSeed();s.minute=990;Resident a=resident(s,"alex","office");a.job="job-office";
        act(s,a,ActionType.WORK,null,"overtime",null);act(s,a,ActionType.PAUSE_ACTIVITY,null,null,null);minutes(s,30);
        act(s,a,ActionType.RESUME_ACTIVITY,null,null,null);minutes(s,30);assertNull(a.pending);assertEquals(100000,a.money);
        WorkPayroll.Due due=WorkPayroll.view(a).getFirst();assertEquals(30,due.overtimeMinutes());assertEquals(1500,due.earnedCents());assertEquals(1440,due.payAtMinute());
    }
    @Test void outdoorReadingRequiresWalkingToTheReservedBenchAndBreaksRetainOnlyActualStudy() throws Exception {
        State s=State.prototypeSeed();s.minute=720;Resident a=resident(s,"reader","library");
        act(s,a,ActionType.BORROW_BOOK,"garden-basics",null,null);Building park=s.buildings.get("park");a.location=park.id;a.x=park.x;a.z=park.z;
        act(s,a,ActionType.READ_BOOK,"garden-basics",null,10);double x=a.x,z=a.z;
        Engine.tick(s);assertEquals(0,a.pending.readingMinutes);assertFalse(a.activityMotion.isEmpty());assertTrue(Math.hypot(a.x-x,a.z-z)>0);
        for(int i=0;i<12&&!a.pending.seatReady;i++)Engine.tick(s);assertTrue(a.pending.seatReady);
        var seat=Seating.position(park,a.pending.seatIndex);assertEquals(seat.x(),a.x,1e-6);assertEquals(seat.z(),a.z,1e-6);
        assertEquals("seated",ActionPerformance.view(a).posture());minutes(s,4);assertEquals(4,a.pending.readingMinutes);
        act(s,a,ActionType.PAUSE_ACTIVITY,null,null,null);minutes(s,12);assertEquals(4,a.pending.readingMinutes);assertEquals(4,a.bookStudies.get("garden-basics").readingMinutes);
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);Resident reader=restored.agents.get(a.id);
        act(restored,reader,ActionType.RESUME_ACTIVITY,null,null,null);minutes(restored,6);assertNull(reader.pending);assertEquals(10,reader.bookStudies.get("garden-basics").readingMinutes);
    }
    @Test void aReviewOffersAChoiceWithoutChangingTheTaskAndSleepAndBusRemainProtected() {
        State s=State.prototypeSeed();s.minute=600;Resident a=resident(s,"alex","office");a.job="job-office";
        act(s,a,ActionType.WORK,null,null,null);Cognition.action(a,600,a.pending.id,"WORK",null,"accepted","Working");
        minutes(s,15);assertTrue(Cognition.view(a,s.minute).reconsider());Pending task=a.pending;
        Cognition.update(s,a,new Cognition.Update(null,false,"Concentrating",null,null));
        act(s,a,ActionType.CONTINUE_ACTIVITY,task.id,null,null);assertSame(task,a.pending);assertEquals(15,task.workMinutes);
        for(String type:List.of("SLEEP","RIDE_BUS","BOARD_BUS")){task.type=type;assertFalse(Cognition.view(a,s.minute+30).reconsider());}
    }
    @Test void successfulInstantActionsExposeTheirRealGestureWithoutInventingExtraInventoryOrEffects() {
        State s=State.prototypeSeed();s.minute=720;Resident a=resident(s,"alex","park");double hunger=a.hunger;
        act(s,a,ActionType.EAT,null,"meal",null);assertNull(a.pending);assertEquals(1,a.inventory.get("meal"));assertTrue(a.hunger<hunger);
        assertEquals("eat",WorldStore.publicAgent(a).performance().gesture());assertEquals("meal",ActionPerformance.view(a).heldItem());
        minutes(s,2);assertNull(ActionPerformance.view(a));assertEquals(1,a.inventory.get("meal"));
        act(s,a,ActionType.JUMP,null,null,null);assertEquals("jump",ActionPerformance.view(a).gesture());
        Engine.tick(s);assertNull(a.pending);
    }
    @Test void exportRealConstructionAndReadingPhasesAndActualJavaSchemasForBrowserVerification() throws Exception {
        State s=State.seed();s.minute=720;s.agents.clear();
        Resident worker=resident(s,"builder","park");worker.location="road";
        var gate=Places.entrance(s.buildings.get("construction-201"));worker.x=gate.x();worker.z=gate.z();
        act(s,worker,ActionType.BUILD_CONSTRUCTION,"construction-201","rear-right",null);
        Resident reader=resident(s,"reader","library");act(s,reader,ActionType.BORROW_BOOK,"garden-basics",null,null);
        Resident employee=resident(s,"employee","office");employee.job="job-office";employee.occupation="City analyst";employee.z+=2.5;act(s,employee,ActionType.WORK,null,null,null);
        Building meadow=s.buildings.values().stream().filter(b->"garden".equals(b.type)).findFirst().orElse(s.buildings.get("park"));
        reader.location=meadow.id;reader.x=meadow.x;reader.z=meadow.z;act(s,reader,ActionType.READ_BOOK,"garden-basics",null,20);
        List<World> frames=new ArrayList<>();frames.add(frame(s));
        for(int i=0;i<32;i++) {
            if(i==10)act(s,worker,ActionType.PAUSE_ACTIVITY,null,null,null);
            if(i==15)act(s,worker,ActionType.RESUME_ACTIVITY,null,null,null);
            if(i==2){Resident eater=resident(s,"eater",meadow.id);eater.x+=3;act(s,eater,ActionType.EAT,null,"meal",null);}
            Engine.tick(s);frames.add(frame(s));
        }
        assertTrue(frames.stream().flatMap(f->f.agents().stream()).anyMatch(a->a.performance()!=null&&"paused".equals(a.performance().phase())));
        Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/human-activities-preview.json"),new ObjectMapper().writeValueAsString(Map.of("frames",frames)));
        Map<String,io.swagger.v3.oas.models.media.Schema> schemas=new LinkedHashMap<>();
        for(Class<?> c:List.of(Resident.class,PublicAgent.class,world.earth35.protocol.AgentService.Observation.class,Construction.SiteView.class,Command.class,StateBuilding.class))schemas.putAll(io.swagger.v3.core.converter.ModelConverters.getInstance().readAll(c));
        Files.writeString(Path.of("target/human-activities-schemas.json"),io.swagger.v3.core.util.Json.mapper().writeValueAsString(Map.of("openapi","3.0.1","info",Map.of("title","Human activity schema","version","1"),"paths",Map.of(),"components",Map.of("schemas",schemas))));
    }
    private World frame(State s) {
        return new World(s.minute,s.version,Engine.weather(s.minute),Engine.temperature(s.minute),1000,
            s.buildings.values().stream().map(b->new StateBuilding(b.id,b.name,b.type,b.district,b.x,b.z,b.capacity,b.description,b.social,b.restAllowed,b.objects,Places.entrance(b),Places.hours(b,s.minute),b.outdoorRadius,b.plannedType,Workspaces.stations(b))).toList(),
            s.agents.values().stream().map(WorldStore::publicAgent).toList(),List.of(),Traffic.network(s),Economy.summary(s),CityLayout.of(s),Projects.publicView(s),Construction.view(s),WorldObjects.view(s));
    }
}
