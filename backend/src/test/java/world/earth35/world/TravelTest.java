package world.earth35.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;
import world.earth35.protocol.Contracts.*;

class TravelTest {
    Resident resident(State s,String id,String location) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=location;
        Building b=s.buildings.get(location);a.x=b.x;a.z=b.z;s.agents.put(id,a);return a;
    }
    Command command(ActionType action,String target,String route) {return new Command("test",action,target,null,route,null,null);}
    @Test void sidewalksReachTheFrontEntranceRatherThanTheBuildingCenter() {
        State s=State.prototypeSeed();Resident a=resident(s,"a","apt-101");
        var path=Travel.path(s,a,s.buildings.get("office"),"east-west-first");
        assertArrayEquals(new double[]{-54,-50.4},path.getFirst(),.000001);
        assertArrayEquals(new double[]{18,-26.4},path.getLast(),.000001);
    }
    @Test void approachesAlongEitherSidewalkStopAtEachBusPlatformFromBothDirections() {
        State s=State.prototypeSeed();
        for(var stop:Traffic.STOPS)for(String choice:java.util.List.of("east-west-first","north-south-first"))for(int direction:new int[]{-1,1}) {
            Resident a=new Resident();a.x=stop.x();a.z=stop.z();
            // Platforms lie on either a north/south or an east/west sidewalk.
            if(Math.abs((stop.x()-2.4)/12-Math.rint((stop.x()-2.4)/12))<.000001)a.z+=direction*10;
            else a.x+=direction*10;
            var path=Travel.path(a,s.buildings.get(stop.id()),choice);
            assertEquals(1,path.size(),stop.id()+" should have no overshoot or reversal on "+choice);
            assertArrayEquals(new double[]{stop.x(),stop.z()},path.getFirst(),.000001);
        }
    }
    @Test void savedWalkingAndRunningRoutesFinishOnTheFirstArrivalWithoutTurningBack() {
        for(String type:java.util.List.of("MOVE","RUN"))for(int direction:new int[]{-1,1}) {
            State s=State.prototypeSeed();Resident a=resident(s,"a","stop-park");
            a.location="road";a.z+=direction*1.5;
            a.pending=Engine.pending("saved-trip",type,"stop-park",0);
            a.pending.route.add(new double[]{50.4,30-direction*8.4});
            a.pending.route.add(new double[]{50.4,30});
            Engine.Effects effects=Engine.tick(s);
            assertEquals("stop-park",a.location);assertNull(a.pending);
            assertEquals(50.4,a.x,.000001);assertEquals(30,a.z,.000001);
            assertEquals(1,effects.completions.size());assertEquals("saved-trip",effects.completions.getFirst().id());
            assertEquals("completed",effects.completions.getFirst().status());
            Engine.tick(s);assertEquals(50.4,a.x,.000001);assertEquals(30,a.z,.000001);
        }
    }
    @Test void savedRouteAlreadyAtTheDestinationCompletesWithoutMovingAway() {
        State s=State.prototypeSeed();Resident a=resident(s,"a","stop-market");a.location="road";
        a.pending=Engine.pending("saved-trip","MOVE","stop-market",0);
        a.pending.route.add(new double[]{50.4,-21.6});a.pending.route.add(new double[]{42,-21.6});
        Engine.tick(s);
        assertEquals("stop-market",a.location);assertNull(a.pending);
        assertEquals(42,a.x,.000001);assertEquals(-21.6,a.z,.000001);
    }
    @Test void residentsCanChooseDistinctRoutesToTheSamePlace() {
        State s=State.prototypeSeed();IslandCity.upgrade(s);Resident a=resident(s,"a","apt-101"),b=resident(s,"b","apt-101");
        Engine.apply(s,a,command(ActionType.MOVE,"office","east-west-first"),"a",new Engine.Effects());
        Engine.apply(s,b,command(ActionType.MOVE,"office","north-south-first"),"b",new Engine.Effects());
        assertFalse(java.util.Arrays.equals(a.pending.route.get(2),b.pending.route.get(2)));
        for(int i=0;i<500&&(a.pending!=null||b.pending!=null);i++)Engine.tick(s);
        assertEquals("office",a.location);assertEquals("office",b.location);
    }
    @Test void changingPaceCancelsOnlyThePreviousTripAndStartsFromCurrentPosition() {
        State s=State.seed();Resident a=resident(s,"a","apt-101");Engine.Effects e=new Engine.Effects();
        Engine.apply(s,a,command(ActionType.MOVE,"office","east-west-first"),"old",e);
        Engine.tick(s);double x=a.x,z=a.z;e=new Engine.Effects();
        Engine.apply(s,a,command(ActionType.RUN,"library","north-south-first"),"new",e);
        assertEquals(x,a.x);assertEquals(z,a.z);assertEquals("library",a.pending.target);assertEquals("RUN",a.pending.type);
        assertEquals("old",e.completions.getFirst().id());assertEquals("cancelled",e.completions.getFirst().status());
        Pending existing=a.pending;a.energy=0;
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,command(ActionType.RUN,"office","bad"),"invalid",new Engine.Effects()));
        assertSame(existing,a.pending);
    }
    @Test void weatherAndProgressOfferAReviewWithoutForcingAnAction() {
        State s=State.seed();Resident a=resident(s,"a","apt-101");
        Engine.apply(s,a,command(ActionType.MOVE,"office","east-west-first"),"trip",new Engine.Effects());
        assertFalse(Travel.reconsider(s,a));a.pending.travelWeather="different";assertTrue(Travel.reconsider(s,a));
        Engine.apply(s,a,command(ActionType.CONTINUE_TRAVEL,null,null),"continue",new Engine.Effects());
        assertFalse(Travel.reconsider(s,a));assertEquals("trip",a.pending.id);
        s.minute+=15;assertTrue(Travel.reconsider(s,a));assertEquals("MOVE",a.pending.type);
    }
    @Test void shelterAndRaincoatProtectWhileOutdoorWaitingIsRejected() {
        State s=State.seed();for(long m=0;m<10000;m+=360)if(Engine.weather(m).equals("Rain")){s.minute=m+10;break;}
        assertEquals("Rain",Engine.weather(s.minute));
        Resident outside=resident(s,"outside","park"),coat=resident(s,"coat","park"),inside=resident(s,"inside","coffee");
        coat.inventory.put("raincoat",1);
        Engine.apply(s,coat,new Command("wear",ActionType.WEAR,null,null,"raincoat",null,null),"wear",new Engine.Effects());
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,outside,command(ActionType.WAIT_RAIN,null,null),"bad",new Engine.Effects()));
        Engine.apply(s,inside,command(ActionType.WAIT_RAIN,null,null),"shelter",new Engine.Effects());
        Engine.tick(s);assertTrue(outside.wetness>0);assertEquals(0,coat.wetness);assertEquals(0,inside.wetness);
        for(int i=0;i<30;i++)Engine.tick(s);assertNull(inside.pending);
    }
    @Test void rainWaitEndsWhenWeatherClearsAndWorkUrgencyDoesNotForceRunning() {
        State s=State.seed();Resident a=resident(s,"a","coffee");
        for(long m=360;m<10000;m+=360)if(Engine.weather(m-1).equals("Rain")&&!Engine.weather(m).equals("Rain")){s.minute=m-1;break;}
        Engine.apply(s,a,command(ActionType.WAIT_RAIN,null,null),"rain",new Engine.Effects());Engine.tick(s);assertNull(a.pending);
        s.minute=570;a.job="job-office";assertEquals(-30,Travel.situation(s,a).minutesUntilShift());
        Engine.apply(s,a,command(ActionType.MOVE,"office","north-south-first"),"walk",new Engine.Effects());assertEquals("MOVE",a.pending.type);
        a.workDay=0;a.workedMinutes=60;assertNull(Travel.situation(s,a).minutesUntilShift());
        a.workedMinutes=0;s.minute=990;assertEquals(1980L,Travel.situation(s,a).shiftStart());
    }
    @Test void alternativesExposeLandmarksAndStopsClearBothRoads() {
        State s=State.seed();Resident a=resident(s,"a","apt-101");
        var routes=Travel.situation(s,a).destinations().stream().filter(d->d.destination().equals("office")).findFirst().orElseThrow().routes();
        assertEquals(1,routes.size());assertEquals("shortest",routes.getFirst().id());assertTrue(routes.stream().allMatch(r->r.walkingMinutes()>=r.runningMinutes()));
        for(var stop:Traffic.STOPS) {assertTrue(Math.hypot(stop.x()-stop.roadX(),stop.z()-stop.roadZ())-.325>1.6);assertTrue(Math.abs(stop.roadX()/12-Math.rint(stop.roadX()/12))>.1||Math.abs(stop.roadZ()/12-Math.rint(stop.roadZ()/12))>.1);}
        assertEquals(3500,s.buildings.get("market").prices.get("raincoat"));
    }
}
