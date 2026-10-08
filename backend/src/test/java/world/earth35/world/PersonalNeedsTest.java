package world.earth35.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;
import world.earth35.protocol.Contracts.*;

class PersonalNeedsTest {
    Resident resident(State s,String place) {
        Resident a=new Resident();a.id="test";a.name="Test";a.home="apt-101";a.location=place;
        var door=Places.entrance(s.buildings.get(place));a.x=door.x();a.z=door.z();s.agents.put(a.id,a);return a;
    }
    Command command(ActionType type,String target,String item,Integer quantity) {return new Command("request",type,target,null,item,quantity,null);}
    void act(State s,Resident a,ActionType type,String target,String item,Integer quantity) {Engine.apply(s,a,command(type,target,item,quantity),"action",new Engine.Effects());}
    @Test void everyIndoorPlaceHasAFrontDoorOnTheFrontSidewalk() {
        State s=State.seed();
        for(var b:s.buildings.values()) {
            var door=Places.entrance(b);assertEquals(b.x,door.x(),.000001);
            assertEquals(b.z+(Places.enclosed(b)?3.6:0),door.z(),.000001);
            assertEquals(Places.enclosed(b)?"front":"open",door.side());
        }
    }
    @Test void allRouteChoicesUseFrontEntrancesAndAvoidOtherBuildingInteriors() {
        State s=State.prototypeSeed();IslandCity.upgrade(s);
        for(String origin:List.of("apt-101","office","park","stop-market","library","house-150")) {
            Resident a=resident(s,origin);
            for(var b:s.buildings.values())for(String choice:List.of("shortest","east-west-first","north-south-first")) {
                var route=Travel.path(s,a,b,choice);double x=a.x,z=a.z;
                for(var p:route) {
                    assertTrue(Math.abs(x-p[0])<.000001||Math.abs(z-p[1])<.000001,"Orthogonal segments");
                    for(var obstacle:s.buildings.values())if(Places.enclosed(obstacle)) {
                        boolean vertical=Math.abs(x-p[0])<.000001;
                        boolean crosses=vertical?Math.abs(x-obstacle.x)<3.35&&Math.max(z,p[1])>obstacle.z-3.35&&Math.min(z,p[1])<obstacle.z+3.35:
                            Math.abs(z-obstacle.z)<3.35&&Math.max(x,p[0])>obstacle.x-3.35&&Math.min(x,p[0])<obstacle.x+3.35;
                        assertFalse(crosses,origin+" -> "+b.id+" through "+obstacle.id+" on "+choice);
                    }
                    x=p[0];z=p[1];
                }
                var door=Places.entrance(b);assertEquals(door.x(),x,.000001);assertEquals(door.z(),z,.000001);
            }
        }
    }
    @Test void shortestRouteMatchesIndependentSidewalkGraph() {
        State s=State.prototypeSeed();Resident a=resident(s,"apt-101");
        // Dijkstra over all sidewalk intersections; doors connect along their
        // front sidewalk. This checks the router against a separate algorithm.
        List<Double> lanes=new ArrayList<>();for(int road=-60;road<=60;road+=12){lanes.add(road-2.4);lanes.add(road+2.4);}
        int n=lanes.size();double[] dist=new double[n*n];Arrays.fill(dist,Double.POSITIVE_INFINITY);
        PriorityQueue<double[]> queue=new PriorityQueue<>(Comparator.comparingDouble(p->p[1]));
        for(int i=0;i<n;i++)for(int j=0;j<n;j++)if(Math.abs(lanes.get(j)-a.z)<.000001) {
            int node=i*n+j;dist[node]=Math.abs(lanes.get(i)-a.x);queue.add(new double[]{node,dist[node]});
        }
        while(!queue.isEmpty()) {
            var entry=queue.remove();int node=(int)entry[0],i=node/n,j=node%n;if(entry[1]>dist[node])continue;
            for(int[] next:new int[][]{{i-1,j},{i+1,j},{i,j-1},{i,j+1}}) {
                if(next[0]<0||next[0]>=n||next[1]<0||next[1]>=n)continue;
                int id=next[0]*n+next[1];double cost=Math.abs(lanes.get(i)-lanes.get(next[0]))+Math.abs(lanes.get(j)-lanes.get(next[1]));
                if(dist[node]+cost<dist[id]){dist[id]=dist[node]+cost;queue.add(new double[]{id,dist[id]});}
            }
        }
        for(var b:s.buildings.values())if(Places.enclosed(b)) {
            var door=Places.entrance(b);double expected=Double.POSITIVE_INFINITY;
            for(int i=0;i<n;i++)for(int j=0;j<n;j++)if(Math.abs(lanes.get(j)-door.z())<.000001)
                expected=Math.min(expected,dist[i*n+j]+Math.abs(lanes.get(i)-door.x()));
            if(b.id.equals(a.location))expected=0;
            assertEquals(expected,Travel.routeDistance(a,Travel.path(s,a,b,"shortest")),.000001,b.id);
        }
        act(s,a,ActionType.MOVE,"office",null,null);assertEquals("shortest",a.pending.routeChoice);
    }
    @Test void interruptedLegacyTripsReplanWithoutChangingTheirActionIdentity() {
        State s=State.prototypeSeed();Resident a=resident(s,"park");a.location="road";
        a.pending=Engine.pending("saved","MOVE","office",0);a.pending.route.add(new double[]{18,-30});
        Engine.tick(s);assertEquals("saved",a.pending.id);assertTrue(a.pending.frontDoorRoute);
        assertArrayEquals(new double[]{18,-26.4},a.pending.route.getLast(),.000001);
    }
    @Test void closedDestinationsCannotCancelAnExistingJourneyOrTrade() {
        State s=State.seed();s.minute=1200;Resident a=resident(s,"park");
        act(s,a,ActionType.MOVE,a.home,null,null);Pending existing=a.pending;
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.MOVE,"coffee",null,null));assertSame(existing,a.pending);
        a.pending=null;a.location="coffee";long balance=a.money;int meals=a.inventory.get("meal");
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.BUY,"coffee","meal",1));
        assertEquals(balance,a.money);assertEquals(meals,a.inventory.get("meal"));
    }
    @Test void aPlaceThatClosesDuringTheWalkLeavesTheResidentOutsideItsDoor() {
        State s=State.seed();s.minute=1199;Resident a=resident(s,"park");var door=Places.entrance(s.buildings.get("coffee"));
        a.location="road";a.x=door.x()-1;a.z=door.z();act(s,a,ActionType.MOVE,"coffee",null,null);
        var result=Engine.tick(s);assertNull(a.pending);assertEquals("road",a.location);
        assertEquals("rejected",result.completions.getFirst().status());assertEquals(door.x(),a.x,.000001);
    }
    @Test void overnightVenuesUseTheOpeningDaysCalendarAndExactClosingBoundary() {
        Building b=new Building("night","Night Cafe","coffee_shop","Downtown",6,6,20);
        b.openMinute=1320;b.closeMinute=120;b.openDays=List.of(0);
        assertFalse(Places.open(b,1319));assertTrue(Places.open(b,1320));assertTrue(Places.open(b,1440+119));
        assertFalse(Places.open(b,1440+120));assertFalse(Places.open(b,1440+1320));
        assertEquals(7*1440+1320,Places.nextOpen(b,1440+120));
        State s=State.seed();s.minute=1440+60;s.buildings.put(b.id,b);Resident a=resident(s,"park");
        act(s,a,ActionType.MOVE,b.id,null,null);assertEquals(b.id,a.pending.target);
    }
    @Test void aVenueClosingDuringABusRideIsDiscoveredOnlyAfterTheFinalWalkToItsDoor() {
        State s=State.seed();Resident a=resident(s,"stop-residential");var trip=Traffic.trip("bus:R1:0:600");
        long arrival=trip.arrivals().get(2);s.minute=arrival-1;
        a.pending=Engine.pending("journey","RIDE_BUS","stop-market",arrival);a.pending.vehicleId=trip.id();a.pending.finalDestination="coffee";
        s.buildings.get("coffee").closeMinute=(int)Math.floorMod(arrival,1440);
        var result=assertDoesNotThrow(()->Engine.tick(s));assertNotNull(a.pending);assertEquals("MOVE",a.pending.type);
        assertEquals("journey",a.pending.id);assertTrue(result.completions.isEmpty());
        var unknown=Travel.situation(s,a).destinations().stream().filter(d->d.destination().equals("coffee")).findFirst().orElseThrow().hours();
        assertNull(unknown.openNow());assertNull(unknown.nextOpenMinute());
        for(int i=0;i<200&&a.pending!=null;i++)result=Engine.tick(s);
        assertNull(a.pending);assertEquals("road",a.location);
        var door=Places.entrance(s.buildings.get("coffee"));assertEquals(door.x(),a.x,.000001);assertEquals(door.z(),a.z,.000001);
        assertEquals("rejected",result.completions.getFirst().status());assertDoesNotThrow(()->Engine.tick(s));
    }
    @Test void changingPaceOnAnAcceptedCommuteDoesNotRevealClosureBeforeReachingTheDoor() {
        State s=State.seed();s.minute=1190;Resident a=resident(s,"park");act(s,a,ActionType.MOVE,"coffee",null,null);
        s.minute=1200;assertDoesNotThrow(()->act(s,a,ActionType.RUN,"coffee",null,null));
        assertEquals("RUN",a.pending.type);assertNull(Places.travelHours(s,a,s.buildings.get("coffee")).openNow());
        for(int i=0;i<200&&a.pending!=null;i++)Engine.tick(s);
        assertNull(a.pending);assertEquals("road",a.location);assertFalse(Places.travelHours(s,a,s.buildings.get("coffee")).openNow());
    }
    @Test void commuteAdviceOffersRunningOrAnAffordableFasterBusWithoutOverridingChoice() {
        State s=State.seed();s.minute=570;Resident a=resident(s,"apt-101");a.job="job-office";
        var travel=Travel.situation(s,a);assertEquals("RUN",travel.commute().action());assertTrue(travel.commute().lateByMinutes()>0);
        for(var d:travel.destinations()) {assertTrue(d.routes().getFirst().shortest());assertTrue(d.routes().getFirst().distance()<=d.routes().getLast().distance());}
        act(s,a,ActionType.MOVE,"office",null,null);assertEquals("MOVE",a.pending.type);a.pending=null;
        a.energy=0;a.money=0;assertEquals("MOVE",Travel.situation(s,a).commute().action());
        a=resident(s,"stop-residential");a.job="job-office";a.energy=0;s.minute=599;
        travel=Travel.situation(s,a);assertEquals("TRAVEL_BUS",travel.commute().action());assertNotNull(travel.commute().item());
        a.money=0;assertEquals("MOVE",Travel.situation(s,a).commute().action());
    }
    @Test void owningRaincoatIsDifferentFromWearingItAndClothingDoesNotCancelTravel() {
        State s=State.seed();s.minute=10;assertEquals("Rain",Engine.weather(s.minute));Resident a=resident(s,"park");
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.WEAR,null,"raincoat",null));
        a.inventory.put("raincoat",1);Engine.tick(s);assertTrue(a.wetness>0);assertFalse(Travel.situation(s,a).rainProtected());
        act(s,a,ActionType.MOVE,a.home,null,null);Pending trip=a.pending;
        act(s,a,ActionType.WEAR,null,"raincoat",null);assertSame(trip,a.pending);assertTrue(Travel.situation(s,a).rainProtected());
        Engine.tick(s);assertEquals(0,a.wetness);assertEquals(1,a.inventory.get("raincoat"));
        act(s,a,ActionType.REMOVE_CLOTHING,null,"raincoat",null);Engine.tick(s);assertTrue(a.wetness>0);assertSame(trip,a.pending);
    }
    @Test void dryWeatherRemovesExistingRaincoatsEvenWhileRestingOrPaused() {
        State s=State.seed();s.minute=360;assertNotEquals("Rain",Engine.weather(s.minute));
        Resident a=resident(s,"park");a.inventory.put("raincoat",1);a.raincoatWorn=true;
        a.pending=Engine.pending("rest","REST","park",s.minute+30);a.paused=true;Pending rest=a.pending;
        var effects=Engine.tick(s);
        assertFalse(a.raincoatWorn);assertEquals(1,a.inventory.get("raincoat"));assertSame(rest,a.pending);
        assertTrue(effects.facts.stream().anyMatch(f->f.message().contains("no longer raining")));
        assertFalse(Engine.tick(s).facts.stream().anyMatch(f->f.message().contains("removed their raincoat")));
    }
    @Test void raincoatComesOffAtTheWeatherTransitionWithoutCancellingTravel() {
        State s=State.seed();s.minute=359;assertEquals("Rain",Engine.weather(s.minute));
        Resident a=resident(s,"park");a.inventory.put("raincoat",1);
        act(s,a,ActionType.MOVE,a.home,null,null);act(s,a,ActionType.WEAR,null,"raincoat",null);Pending trip=a.pending;
        Engine.tick(s);assertFalse(a.raincoatWorn);assertSame(trip,a.pending);assertEquals(1,a.inventory.get("raincoat"));
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.WEAR,null,"raincoat",null));
    }
    @Test void lateTiredSleepExtendsBeyondPreferredWakeAndRecordsRecovery() {
        State s=State.seed();s.minute=1740;Resident a=resident(s,"apt-101");a.routine=new DailyRoutine();a.routine.wakeMinute=360;a.routine.sleepMinute=1320;a.energy=25;
        act(s,a,ActionType.SLEEP,null,null,null);assertEquals(2220,a.pending.until);assertTrue(a.pending.until>1800);
        while(a.pending!=null)Engine.tick(s);assertEquals(2220,a.lastWakeMinute);assertEquals(100,a.energy);assertEquals(0,a.sleepDebtMinutes);
    }
    @Test void shortNightCreatesSleepDebtAndDaytimeFatigueCanTriggerRecovery() {
        State s=State.seed();s.minute=1680;Resident a=resident(s,"apt-101");a.routine=new DailyRoutine();a.routine.wakeMinute=360;a.routine.sleepMinute=1320;
        act(s,a,ActionType.SLEEP,null,null,2);while(a.pending!=null)Engine.tick(s);
        assertEquals(360,a.sleepDebtMinutes);assertEquals(1800,a.lastWakeMinute);
        a.energy=15;s.minute=2000;assertTrue(DailyRoutines.needsSleep(a,s.minute));assertEquals(ActionType.SLEEP,NightRoutine.defaultAction(s,a,"recovery").action());
    }
    @Test void nightPlansAndExplicitRestRemainChoicesForTiredResidents() {
        State s=State.seed();s.minute=1400;Resident a=resident(s,"park");a.energy=40;a.nightPlan=new NightPlan("Watch the stars",1800);
        assertNull(NightRoutine.defaultAction(s,a,"sleep"));
        var rest=command(ActionType.REST,null,null,null);assertSame(rest,NightRoutine.resolve(s,a,rest));
        a.nightPlan=null;a.energy=100;assertNull(NightRoutine.defaultAction(s,a,"rested"));
        a.lastWakeMinute=400;assertNotNull(NightRoutine.defaultAction(s,a,"long-day"));
    }
    @Test void recoverySleepIsBoundedWithoutRoutineHungerWakeups() {
        State s=State.seed();s.minute=720;Resident a=resident(s,"apt-101");a.energy=0;a.sleepDebtMinutes=1440;a.wetness=100;
        act(s,a,ActionType.SLEEP,null,null,null);assertEquals(1320,a.pending.until);
        assertThrows(Engine.Invalid.class,()->DailyRoutines.sleepUntil(a,s.minute,11));
        a.hunger=97;for(int i=0;i<120;i++)Engine.tick(s);assertNotNull(a.pending);
    }
    @Test void hungerWakeEatsAtHomeAndResumesOriginalSleepWithoutAnotherInterruption() {
        State s=State.seed();s.minute=1320;Resident a=resident(s,"apt-101");
        a.routine=new DailyRoutine();a.routine.sleepMinute=1320;a.routine.wakeMinute=360;
        a.energy=25;a.hunger=97;a.lastMealMinute=600;
        act(s,a,ActionType.SLEEP,null,null,null);a.pending.hungerWakeThreshold=98;
        Engine.Effects wake=null;while(a.pending!=null)wake=Engine.tick(s);
        assertTrue(s.minute<1440);assertTrue(a.hungerWakeNeedsFood);
        assertTrue(wake.facts.stream().anyMatch(f->f.message().contains("because of severe hunger")));
        assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.SLEEP,null,null,8));
        Command snack=NightRoutine.defaultAction(s,a,"snack");assertEquals(ActionType.EAT,snack.action());
        int meals=a.inventory.get("meal");Engine.apply(s,a,snack,"snack",new Engine.Effects());
        assertEquals(meals-1,a.inventory.get("meal"));assertEquals(s.minute,a.lastMealMinute);
        assertFalse(a.hungerWakeNeedsFood);assertEquals(a.home,a.location);
        a.energy=100;assertEquals(ActionType.SLEEP,NightRoutine.defaultAction(s,a,"resume").action());
        act(s,a,ActionType.SLEEP,null,null,null);assertEquals(1800,a.pending.until);
        a.hunger=100;while(s.minute<1799)Engine.tick(s);assertNotNull(a.pending);
        Engine.tick(s);assertNull(a.pending);assertEquals(1800,a.lastWakeMinute);
        assertEquals(0,a.sleepDebtMinutes);
    }
    @Test void severeHungerBlocksBothDefaultSleepAndExplicitNapsAtTheBoundary() {
        State s=State.seed();s.minute=720;Resident a=resident(s,"apt-101");a.energy=0;a.hunger=98;
        assertNull(NightRoutine.defaultAction(s,a,"hungry"));
        for(Integer hours:Arrays.asList(null,1,10))
            assertThrows(Engine.Invalid.class,()->act(s,a,ActionType.SLEEP,null,null,hours));
        assertNull(a.pending);
        a.hunger=97.9;assertDoesNotThrow(()->act(s,a,ActionType.SLEEP,null,null,null));assertNotNull(a.pending);
    }
}
