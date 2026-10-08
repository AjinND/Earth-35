package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class BusBoardingTest {
    private Resident resident(State s,String id,String stop,Traffic.Platform platform) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location=stop;a.x=platform.x();a.z=platform.z();s.agents.put(id,a);return a;
    }
    @Test void eachRouteHasACurbAlignedPlatformBesideItsOwnLaneAtEveryStop() {
        for(State s:List.of(State.prototypeSeed(),State.seed()))for(Traffic.Stop stop:Traffic.stops(s)) {
            assertEquals(2,stop.platforms().size());
            for(Traffic.Platform platform:stop.platforms()) {
                assertEquals(2.4,Math.hypot(platform.x()-stop.roadX(),platform.z()-stop.roadZ()),1e-7);
                assertEquals(2.4,(platform.x()-stop.roadX())*Math.cos(platform.heading())-(platform.z()-stop.roadZ())*Math.sin(platform.heading()),1e-7);
                if(s.geographyVersion>=2)assertTrue(IslandGeography.roadDistance(platform.x(),platform.z())>2.3,"The whole platform must clear the road");
            }
            var a=stop.platforms().getFirst();var b=stop.platforms().getLast();
            assertEquals(4.8,Math.hypot(a.x()-b.x(),a.z()-b.z()),1e-7);
        }
    }
    @Test void boardingWalkIsVisibleBeforeTheFareAndRidingStateBegin() {
        State s=State.seed();Traffic.Trip trip=Traffic.trip(s,"bus:R2:0:615");s.minute=trip.arrivals().getFirst();
        Resident a=resident(s,"passenger","stop-residential",Traffic.platform(s,"stop-residential","R2"));
        double x=a.x,z=a.z;long money=a.money;Engine.Effects accepted=new Engine.Effects();
        Engine.apply(s,a,new Command("board",ActionType.BOARD_BUS,trip.id(),null,"stop-garden",null,null),"boarding",accepted);
        assertEquals("BOARD_BUS",a.pending.type);assertEquals("stop-residential",a.location);
        assertEquals(x,a.x);assertEquals(z,a.z);assertEquals(money,a.money);assertTrue(accepted.payments.isEmpty());
        Engine.Effects boarded=Engine.tick(s);
        assertEquals("RIDE_BUS",a.pending.type);assertTrue(a.location.startsWith("bus:"));
        assertEquals(money-250,a.money);assertEquals(1,boarded.payments.size());
        var publicAgent=WorldStore.publicAgent(a);assertEquals(trip.id(),publicAgent.vehicleId());
        assertTrue(publicAgent.motion().size()>3);assertEquals(x,publicAgent.motion().getFirst().x());assertEquals(z,publicAgent.motion().getFirst().z());
        var last=publicAgent.motion().getLast();assertEquals(a.x,last.x());assertEquals(a.z,last.z());
        double previousX=x,previousZ=z;
        for(var pose:publicAgent.motion()){assertTrue(Math.hypot(pose.x()-previousX,pose.z()-previousZ)<=.500001);previousX=pose.x();previousZ=pose.z();}
        var bus=Traffic.vehicles(s).stream().filter(v->v.id().equals(publicAgent.vehicleId())).findFirst().orElseThrow();
        assertEquals("stop-residential",bus.stop());
        assertEquals(.25,(last.x()-bus.x())*Math.cos(bus.heading())-(last.z()-bus.z())*Math.sin(bus.heading()),1e-7);
        Engine.tick(s);assertTrue(WorldStore.publicAgent(a).motion().isEmpty());
    }
    @Test void passengersWalkToTheChosenDirectionAndFinishBoardingBeforeDeparture() {
        for(State s:List.of(State.prototypeSeed(),State.seed())) {
            Traffic.Trip trip=Traffic.trip(s,"bus:R1:0:600");s.minute=trip.arrivals().getFirst()-10;
            Traffic.Platform correct=Traffic.platform(s,"stop-residential","R1"),wrong=Traffic.platform(s,"stop-residential","R2");
            List<Resident> passengers=new ArrayList<>();
            for(int i=0;i<20;i++) {
                Resident a=resident(s,"passenger-"+i,"stop-residential",wrong);passengers.add(a);
                Engine.apply(s,a,new Command("wait-"+i,ActionType.WAIT_BUS,"R1",null,"stop-downtown",null,null),"waiting-"+i,new Engine.Effects());
                assertEquals(wrong.x(),a.x);assertEquals(wrong.z(),a.z);
                assertEquals(trip.id(),a.pending.vehicleId);
            }
            Set<Integer> slots=new HashSet<>();for(Resident a:passengers)slots.add(a.pending.queueIndex);assertEquals(20,slots.size());
            Set<String> waitedCorrectly=new HashSet<>(),boarding=new HashSet<>(),rode=new HashSet<>();
            for(int minute=0;minute<40&&rode.size()<20;minute++) {
                Engine.tick(s);
                for(Resident a:passengers) {
                    assertNotNull(a.pending);
                    if(a.pending.type.equals("WAIT_BUS")&&a.pending.route.isEmpty()) {
                        assertTrue((a.x-correct.x())*Math.cos(correct.heading())-(a.z-correct.z())*Math.sin(correct.heading())>=-1e-7);
                        waitedCorrectly.add(a.id);
                    }
                    if(a.pending.type.equals("BOARD_BUS")) {
                        boarding.add(a.id);
                        var bus=Traffic.vehicles(s).stream().filter(v->v.id().equals(a.pending.vehicleId)).findFirst().orElseThrow();
                        assertEquals("stop-residential",bus.stop(),"A bus must stay parked while people board");
                    }
                    if(a.pending.type.equals("RIDE_BUS")){rode.add(a.id);assertTrue(s.minute<=trip.departures().getFirst());}
                }
            }
            assertEquals(20,waitedCorrectly.size());assertEquals(20,boarding.size());assertEquals(20,rode.size());
            for(Resident a:passengers)assertEquals(99750,a.money);
        }
    }
    @Test void savedStopsAreRepairedOnceWithoutLosingPrivateExtensions() throws Exception {
        State s=State.seed();s.transitVersion=0;Building stop=s.buildings.get("stop-market");stop.x+=.8;stop.z-=.8;
        Resident a=resident(s,"waiting","stop-market",Traffic.platform(s,"stop-market","R1"));
        s.minute=500;Engine.apply(s,a,new Command("wait",ActionType.WAIT_BUS,"R1",null,"stop-park",null,null),"saved-wait",new Engine.Effects());
        a.pending.waitingPoint=null;a.pending.route.clear();
        ObjectMapper json=new ObjectMapper();ObjectNode saved=json.valueToTree(s);
        ((ObjectNode)saved.path("agents").path(a.id)).put("privateExtension","keep");
        ((ObjectNode)saved.path("agents").path(a.id).path("pending")).put("privateExtension","keep pending");
        Traffic.upgradeStops(saved);
        assertEquals("keep",saved.path("agents").path(a.id).path("privateExtension").asText());
        assertEquals("keep pending",saved.path("agents").path(a.id).path("pending").path("privateExtension").asText());
        assertEquals("saved-wait",saved.path("agents").path(a.id).path("pending").path("id").asText());
        assertFalse(saved.path("agents").path(a.id).path("pending").path("waitingPoint").isNull());
        long version=saved.path("version").asLong();Traffic.upgradeStops(saved);assertEquals(version,saved.path("version").asLong());
    }
}
