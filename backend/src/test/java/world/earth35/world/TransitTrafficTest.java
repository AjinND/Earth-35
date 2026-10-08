package world.earth35.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class TransitTrafficTest {
    @Test void perimeterCarsKeepMovingAcrossUnsignalledJunctions() {
        assertFalse(Traffic.hasSignal(-60,-60));assertFalse(Traffic.hasSignal(0,-60));
        assertTrue(Traffic.hasSignal(24,-24));
        State s=State.seed();assertEquals(8,Traffic.network(s).signals().size());
        for(long minute=480;minute<540;minute++) {
            var motion=TrafficFlow.schedule(0).motion.get(minute);
            for(var entry:motion.entrySet())if(entry.getKey().startsWith("car-")) {
                double distance=0;var poses=entry.getValue();
                for(int i=1;i<poses.size();i++)distance+=Math.hypot(poses.get(i).x()-poses.get(i-1).x(),poses.get(i).z()-poses.get(i-1).z());
                assertTrue(distance>5,"An empty unsignalled road must not cause a signal wait for "+entry.getKey()+" at "+minute);
            }
        }
    }
    @Test void entireDayHasNoOverlappingVehiclesAndEveryScheduledBusCompletes() {
        State s=State.seed();
        for(int minute=0;minute<1440;minute++) {
            s.minute=minute;var vehicles=Traffic.vehicles(s);
            for(int i=0;i<vehicles.size();i++)for(int j=i+1;j<vehicles.size();j++) {
                var a=vehicles.get(i);var b=vehicles.get(j);
                assertFalse(TrafficFlow.bodiesOverlap(new Traffic.Pose(a.x(),a.z(),a.heading()),a.type().equals("bus")?2.55:1.425,
                    new Traffic.Pose(b.x(),b.z(),b.heading()),b.type().equals("bus")?2.55:1.425),"Collision at "+minute+" between "+a.id()+" and "+b.id());
            }
        }
        // The map plays these shared substeps, so verify those as well as minute endpoints.
        var timeline=TrafficFlow.schedule(0,CityLayout.of(s));
        for(var entry:timeline.motion.entrySet())for(int step=0;step<=12;step++) {
            var ids=new java.util.ArrayList<>(entry.getValue().keySet());
            for(int i=0;i<ids.size();i++)for(int j=i+1;j<ids.size();j++) {
                String aid=ids.get(i),bid=ids.get(j);var a=entry.getValue().get(aid).get(step);var b=entry.getValue().get(bid).get(step);
                double al=aid.startsWith("bus:")?2.55:1.425,bl=bid.startsWith("bus:")?2.55:1.425;
                assertFalse(TrafficFlow.bodiesOverlap(IslandGeography.pose(a),al,IslandGeography.pose(b),bl),"Substep collision "+entry.getKey()+":"+step);
            }
        }
        for(var route:Traffic.ROUTES)for(int dep=route.firstDeparture();dep<=route.lastDeparture();dep+=route.headway()) {
            var trip=Traffic.trip(s,"bus:"+route.id()+":0:"+dep);assertNotNull(trip,"Unfinished service "+route.id()+":"+dep);
            assertEquals(6,trip.arrivals().size());assertEquals(6,trip.departures().size());
            for(int i=1;i<6;i++)assertTrue(trip.arrivals().get(i)>trip.departures().get(i-1));
        }
    }
    @Test void agentChoosesBusToAnIntermediateStopAndCompletesAtTheActualBuilding() {
        State s=State.seed();Resident a=new Resident();a.id="commuter";a.name="Commuter";a.home="apt-101";a.location=a.home;
        Building home=s.buildings.get(a.home);a.x=home.x;a.z=home.z;s.agents.put(a.id,a);s.minute=560;
        var destination=Travel.situation(s,a).destinations().stream().filter(d->d.destination().equals("office")).findFirst().orElseThrow();
        assertFalse(destination.transit().isEmpty());var plan=destination.transit().getFirst();assertNotEquals("office",plan.alightingStop());
        long money=a.money;Engine.Effects all=new Engine.Effects();
        Engine.apply(s,a,new Command("commute",ActionType.TRAVEL_BUS,"office",null,plan.id(),null,null),"journey",all);
        assertEquals("MOVE",a.pending.type);boolean boarded=false,lastWalk=false;
        for(int i=0;i<500&&a.pending!=null;i++) {
            var effects=Engine.tick(s);all.payments.addAll(effects.payments);all.completions.addAll(effects.completions);
            if(a.pending!=null&&a.pending.type.equals("RIDE_BUS"))boarded=true;
            if(boarded&&a.pending!=null&&a.pending.type.equals("MOVE"))lastWalk=true;
        }
        assertTrue(boarded);assertTrue(lastWalk);assertEquals("office",a.location);assertNull(a.pending);
        assertEquals(money-250,a.money);assertEquals(1,all.payments.size());assertEquals(1,all.completions.size());assertEquals("completed",all.completions.getFirst().status());
    }
    @Test void nonexistentFarFutureBusIsRejectedWithoutBuildingItsForecast() {
        State s=State.seed();s.minute=600;Resident a=new Resident();a.id="a";a.location="stop-residential";
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,new Command("bad",ActionType.BOARD_BUS,"bus:R1:999999999:600",null,"stop-park",null,null),"bad",new Engine.Effects()));
        assertNull(a.pending);
    }
    @Test void staleAndUnlistedItinerariesAreRejectedBeforeChangingAnExistingJourney() {
        State s=State.seed();Resident a=new Resident();a.id="a";a.home="apt-101";a.location=a.home;a.x=-54;a.z=-54;s.agents.put(a.id,a);s.minute=560;
        Engine.apply(s,a,new Command("walk",ActionType.MOVE,"office",null,null,null,null),"walk",new Engine.Effects());
        assertThrows(Engine.Invalid.class,()->Engine.apply(s,a,new Command("bus",ActionType.TRAVEL_BUS,"office",null,"R1|stop-market|stop-downtown",null,null),"bus",new Engine.Effects()));
        assertEquals("walk",a.pending.id);assertEquals(100000,a.money);
    }
}
