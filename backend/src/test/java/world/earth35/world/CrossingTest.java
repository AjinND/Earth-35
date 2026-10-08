package world.earth35.world;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;

class CrossingTest {
    @Test void junctionsHaveIndependentStablePhases() {
        State s=State.prototypeSeed();
        for(long minute=480;minute<492;minute++) {
            s.minute=minute;
            var signals=Traffic.network(s).signals();
            assertTrue(signals.stream().map(Traffic.Signal::vehicleGreen).distinct().count()>1);
            for(var signal:signals) {
                assertEquals(Traffic.green(minute,signal.x(),signal.z()),signal.vehicleGreen());
                assertEquals(signal.vehicleGreen().equals("pedestrians"),signal.pedestrianGreen());
                assertEquals(signal.vehicleGreen(),Traffic.green(minute+1440,signal.x(),signal.z()));
            }
        }
        assertNotEquals(Traffic.green(480,0,0),Traffic.green(480,12,0));
    }
    @Test void emptyAndUnrelatedRoadsDoNotBlockEitherCrossingDirection() {
        var remote=new Traffic.RoadMotion(false,List.of(new Traffic.Pose(24.8,2.4,0)));
        assertTrue(Traffic.canWalk(-2,2.4,-1.5,2.4,List.of(remote)));
        assertTrue(Traffic.canWalk(2.4,-2,2.4,-1.5,List.of(remote)));
        assertTrue(Traffic.canWalk(2,2.4,1.5,2.4,List.of()));
        assertTrue(Traffic.canWalk(2.4,2,2.4,1.5,List.of()));
    }
    @Test void approachingVehiclesBlockTheWholeCrossingIncludingTheFarLane() {
        var approaching=new Traffic.RoadMotion(false,List.of(
            new Traffic.Pose(.8,-4,0),new Traffic.Pose(.8,8,0)));
        assertFalse(Traffic.canWalk(-2,2.4,-1.5,2.4,List.of(approaching)));
        assertTrue(Traffic.canWalk(-2,2.4,-1.5,2.4,List.of()));
        var horizontal=new Traffic.RoadMotion(false,List.of(
            new Traffic.Pose(-4,-.8,Math.PI/2),new Traffic.Pose(8,-.8,Math.PI/2)));
        assertFalse(Traffic.canWalk(2.4,-2,2.4,-1.5,List.of(horizontal)));
        // Bus tails need more clearance than car tails at the same position.
        var pose=List.of(new Traffic.Pose(.8,5.4,0));
        assertTrue(Traffic.canWalk(-2,2.4,-1.5,2.4,List.of(new Traffic.RoadMotion(false,pose))));
        assertFalse(Traffic.canWalk(-2,2.4,-1.5,2.4,List.of(new Traffic.RoadMotion(true,pose))));
    }
    private Resident walker(State s,String id,double x,double z,double nx,double nz) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location="road";a.x=x;a.z=z;
        a.pending=Engine.pending(id,"MOVE","office",0);a.pending.frontDoorRoute=true;a.pending.route.add(new double[]{nx,nz});
        s.agents.put(id,a);return a;
    }
    @Test void aWaitingResidentDoesNotStopOtherResidentsAndResumesWhenTrafficClears() {
        State s=State.prototypeSeed();
        long blocked=480;
        while(blocked<600&&Traffic.canWalk(blocked,-62,-57.6,-61.5,-57.6))blocked++;
        assertTrue(blocked<600,"Perimeter traffic should approach this crossing");
        s.minute=blocked-1;
        Resident waiting=walker(s,"waiting",-62,-57.6,-57.6,-57.6);
        Resident walking=walker(s,"walking",2.4,2.4,10,2.4);
        Engine.tick(s);
        assertEquals(-62,waiting.x,.001);assertNotNull(waiting.pending.waitingReason);
        assertEquals(4.4,walking.x,.001);assertNull(walking.pending.waitingReason);
        for(int i=0;i<60&&waiting.x==-62;i++)Engine.tick(s);
        assertTrue(waiting.x>-62,"The waiting resident should resume after a safe gap");
    }
    @Test void emptyCrossingsAtDifferentJunctionsDoNotStopResidentsTogether() {
        State s=State.prototypeSeed();
        Resident a=walker(s,"a",-2,2.4,2.4,2.4);
        Resident b=walker(s,"b",10,14.4,14.4,14.4);
        Engine.tick(s);
        assertEquals(0,a.x,.001);assertEquals(12,b.x,.001);
        assertNull(a.pending.waitingReason);assertNull(b.pending.waitingReason);
    }
}
