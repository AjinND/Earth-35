package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConstructionHumanActivityTest {
    private State world() {
        State s=State.seed();s.minute=600;s.agents.clear();
        State.Resident a=new State.Resident();a.id="builder";a.name="Builder";a.home="apt-101";a.location="road";
        var gate=Places.entrance(s.buildings.get("construction-201"));a.x=gate.x();a.z=gate.z();s.agents.put(a.id,a);return s;
    }
    private void start(State s,String area) {Construction.build(s,s.agents.get("builder"),"construction-201","hour",area,new Engine.Effects());}
    private void arrive(State s) {
        State.Resident a=s.agents.get("builder");
        for(int i=0;i<12&&!ConstructionWork.atStation(a,a.pending);i++)Engine.tick(s);
        assertNotNull(a.pending);assertTrue(ConstructionWork.atStation(a,a.pending),"Worker must physically reach the assigned work area");
    }
    @Test void workerWalksThroughTheGateAroundSiteObjectsBeforeAnyLaborIsCredited() {
        for(String area:List.of("front-left","front-right","rear-left","rear-right")) {
            State s=world();State.Resident a=s.agents.get("builder");State.Building site=s.buildings.get("construction-201");
            double startX=a.x,startZ=a.z;start(s,area);assertEquals("approach",ConstructionWork.view(a).phase());
            for(int i=0;i<12&&!ConstructionWork.atStation(a,a.pending);i++) {
                double x=a.x,z=a.z;Engine.tick(s);assertNotNull(a.pending);assertEquals(0,a.pending.workMinutes,"Moving to a workstation is not labor");
                assertFalse(a.activityMotion.isEmpty());assertEquals(x,a.activityMotion.getFirst().x(),1e-8);assertEquals(z,a.activityMotion.getFirst().z(),1e-8);
                assertEquals(a.x,a.activityMotion.getLast().x(),1e-8);assertEquals(a.z,a.activityMotion.getLast().z(),1e-8);
                double effort=0;
                for(int j=1;j<a.activityMotion.size();j++) {
                    var previous=a.activityMotion.get(j-1);var next=a.activityMotion.get(j);
                    assertTrue(Math.hypot(next.x()-previous.x(),next.z()-previous.z())<=.50001);
                    assertTrue(ConstructionWork.allowed(s,site,next.x(),next.z()),"Motion must clear fences, foundations, core, crane and materials");
                    effort+=Travel.segmentEffort(s,previous.x(),previous.z(),next.x(),next.z());
                }
                assertTrue(effort<=CityLayout.of(s).walkingSpeed()+.02);
            }
            assertTrue(ConstructionWork.atStation(a,a.pending));assertTrue(Math.hypot(a.x-startX,a.z-startZ)>2);
            assertEquals(area,ConstructionWork.view(a).area());assertEquals("perform",ConstructionWork.view(a).phase());
            Engine.tick(s);assertEquals(1,a.pending.workMinutes);assertEquals(1,ConstructionWork.view(a).activeMinutes());assertTrue(a.activityMotion.isEmpty());
            assertFalse(Places.canEnter(s,a,site,s.minute),"Worker access must not open public construction entry");
        }
    }
    @Test void occupancyAndPhysicalObstructionsCannotGenerateConstructionProgress() {
        State s=world();start(s,"front-right");State.Resident a=s.agents.get("builder");arrive(s);
        State.Resident obstacle=new State.Resident();obstacle.id="visitor";obstacle.name="Visitor";obstacle.location="road";obstacle.x=a.x;obstacle.z=a.z;s.agents.put(obstacle.id,obstacle);
        Engine.tick(s);assertEquals(0,a.pending.workMinutes);assertEquals("blocked",ConstructionWork.view(a).phase());
        obstacle.x+=2;Engine.tick(s);assertEquals(1,a.pending.workMinutes);
        State.Resident second=new State.Resident();second.id="second";second.name="Second";second.location="road";second.home="apt-101";
        var gate=Places.entrance(s.buildings.get("construction-201"));second.x=gate.x();second.z=gate.z();s.agents.put(second.id,second);
        long funds=s.accounts.get(Construction.account("construction-201"));
        assertThrows(Engine.Invalid.class,()->Construction.build(s,second,"construction-201","racing","front-right",new Engine.Effects()));
        assertEquals(funds,s.accounts.get(Construction.account("construction-201")));assertNull(second.pending);
    }
    @Test void sixtyActualWorkMinutesSurviveRestartAndPayOnlyOnce() throws Exception {
        State s=world();start(s,"rear-left");arrive(s);State.Resident a=s.agents.get("builder");long money=a.money;
        for(int i=0;i<19;i++)Engine.tick(s);assertEquals(19,a.pending.workMinutes);
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(s),State.class);State.Resident saved=restored.agents.get(a.id);
        for(int i=0;i<40;i++)Engine.tick(restored);assertEquals(59,saved.pending.workMinutes);assertEquals(money,saved.money);
        Engine.tick(restored);assertNull(saved.pending);assertEquals(money+Construction.HOURLY_WAGE,saved.money);
        assertEquals(1,restored.construction.get("construction-201").completedHours);
        Engine.tick(restored);assertEquals(money+Construction.HOURLY_WAGE,saved.money);
    }
    @Test void pausedActivityDoesNotWorkAndVoluntaryStopReleasesOnlyTheUnfinishedReservation() {
        State s=world();start(s,"front-left");arrive(s);State.Resident a=s.agents.get("builder");
        for(int i=0;i<9;i++)Engine.tick(s);long money=a.money;long funds=s.accounts.get(Construction.account("construction-201"));
        a.pending.activityPaused=true;
        for(int i=0;i<5;i++)Engine.tick(s);
        assertEquals(9,a.pending.workMinutes);assertEquals("paused",ConstructionWork.view(a).phase());assertTrue(a.activityMotion.isEmpty());
        a.pending.activityPaused=false;Engine.tick(s);assertEquals(10,a.pending.workMinutes);
        Engine.Effects effects=new Engine.Effects();Construction.stop(s,a,effects);
        assertNull(a.pending);assertEquals(money,a.money);assertEquals(funds+3000,s.accounts.get(Construction.account("construction-201")));
        assertEquals(0,s.construction.get("construction-201").completedHours);assertEquals(0,s.construction.get("construction-201").reservedHours);
        assertTrue(effects.completions.stream().anyMatch(c->"cancelled".equals(c.status())));
    }
    @Test void stageDescriptionUsesRecordedSiteProgressAndInvalidAreaDoesNotReserveMoney() {
        State s=world();State.Resident a=s.agents.get("builder");long funds=s.accounts.get(Construction.account("construction-201"));
        assertThrows(Engine.Invalid.class,()->start(s,"imaginary-rooftop"));assertEquals(funds,s.accounts.get(Construction.account("construction-201")));
        start(s,"front-right");arrive(s);Engine.tick(s);assertEquals("Setting foundations",ConstructionWork.view(a).task());
        Construction.Site site=s.construction.get("construction-201");site.completedHours=site.requiredHours/2;Engine.tick(s);
        assertEquals("Assembling the structural frame",ConstructionWork.view(a).task());
        assertTrue(Construction.view(s).sites().stream().filter(v->site.id.equals(v.id())).findFirst().orElseThrow().workAreas().stream().anyMatch(v->v.id().equals("front-right")));
    }
    @Test void stoppingAndFinishingWorkersLeaveThroughTheActualGateWithoutCuttingSiteObjects() {
        for(boolean completed:List.of(false,true)) {
            State s=world();State.Resident a=s.agents.get("builder");State.Building site=s.buildings.get("construction-201");
            if(completed)s.construction.get(site.id).requiredHours=1;
            start(s,"rear-left");arrive(s);
            if(completed)for(int i=0;i<60;i++)Engine.tick(s);else Construction.stop(s,a,new Engine.Effects());
            assertNull(a.pending);
            var gate=Places.entrance(site);State.Building destination=new State.Building("outside-worker-gate","Outside worker gate","wilderness","Development",gate.x()+2,gate.z()+2,1);
            List<double[]> route=Travel.path(s,a,destination,"shortest");assertFalse(route.isEmpty());
            double x=a.x,z=a.z;boolean reachedGate=false;
            for(double[] point:route) {
                if(!reachedGate) {
                    int samples=Math.max(1,(int)Math.ceil(Math.hypot(point[0]-x,point[1]-z)/.1));
                    for(int j=0;j<=samples;j++)assertTrue(ConstructionWork.allowed(s,site,x+(point[0]-x)*j/samples,z+(point[1]-z)*j/samples),"Egress must clear physical site objects");
                }
                if(Math.hypot(point[0]-gate.x(),point[1]-gate.z())<.001)reachedGate=true;
                x=point[0];z=point[1];
            }
            assertTrue(reachedGate,"Every former worker must use the front gate");
            assertEquals(destination.x,x,1e-8);assertEquals(destination.z,z,1e-8);
        }
    }
}
