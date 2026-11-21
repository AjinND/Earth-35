package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class WorkspacesHumanActivityTest {
    private State world(String type) {
        State s=State.prototypeSeed();s.minute=600;Building place=s.buildings.get("office");
        Resident a=new Resident();a.id="worker";a.name="Worker";a.home="apt-101";a.location=place.id;a.x=place.x;a.z=place.z+2.5;a.job="job-office";a.occupation="City analyst";s.agents.put(a.id,a);
        if("WORK".equals(type))Engine.apply(s,a,new Command("job",ActionType.WORK,null,null,null,null,null),"job",new Engine.Effects());
        else {
            Projects.Project project=new Projects.Project();project.id="project";project.founder=a.id;project.title="Recorded design";project.subject="business";project.recipe="nourishment";project.description="A previously recorded design";project.problem="A recorded need";project.location=place.id;project.members.add(a.id);s.projects.put(project.id,project);
            Ventures.Business business=new Ventures.Business();business.id="business";business.name="Workshop";business.location=place.id;business.owners.add(a.id);s.businesses.put(business.id,business);
            Pending p=Engine.pending("task",type,"PRODUCE_PRODUCT".equals(type)?business.id:project.id,s.minute+("BUILD_PROJECT".equals(type)?60:30));p.startedAt=s.minute;p.projectMethod="PRODUCE_PRODUCT".equals(type)?"1":"observation";a.pending=p;
            if("BUILD_PROJECT".equals(type)){p.reservedCost=1200;Engine.pay(s,a.id,"escrow:"+p.id,p.reservedCost,"prototype_material_reservation",new Engine.Effects());}
            if("PRODUCE_PRODUCT".equals(type)){p.reservedCost=600;s.accounts.put(business.id,10000L);Engine.pay(s,business.id,"escrow:manufacture:"+p.id,p.reservedCost,"production_material_reservation",new Engine.Effects());}
        }
        return s;
    }
    @ParameterizedTest @ValueSource(strings={"WORK","RESEARCH_PROJECT","BUILD_PROJECT","PRODUCE_PRODUCT"})
    void everyWorkFamilyActuallyApproachesFiniteFurnitureBeforeCreditAndPersistsAcrossRestart(String type) throws Exception {
        State s=world(type);Resident a=s.agents.get("worker");long duration=a.pending.until-a.pending.startedAt;
        assertEquals("approach",ActionPerformance.view(a).phase());
        Engine.tick(s);assertNotNull(a.pending);assertEquals(0,a.pending.workMinutes);assertFalse(a.activityMotion.isEmpty());
        ObjectMapper json=new ObjectMapper();s=json.readValue(json.writeValueAsString(s),State.class);a=s.agents.get("worker");
        for(int i=0;i<15&&!a.pending.workspaceReady;i++)Engine.tick(s);
        assertTrue(a.pending.workspaceReady,type);assertNotNull(a.pending.workspaceId);
        assertEquals(a.pending.workspaceX,a.x,1e-7);assertEquals(a.pending.workspaceZ,a.z,1e-7);
        String workspace=a.pending.workspaceId;
        assertTrue(Workspaces.stations(s.buildings.get(a.location)).stream().anyMatch(station->station.id().equals(workspace)));
        assertEquals("perform",ActionPerformance.view(a).phase());assertEquals(0,a.pending.workMinutes);
        if(!"WORK".equals(type))assertEquals(duration,a.pending.until-a.pending.startedAt);
        Engine.tick(s);assertEquals(1,a.pending.workMinutes);
    }
    @Test void workstationCapacityAndPhysicalBodiesCannotGenerateGhostLabor() {
        State s=world("WORK");Resident a=s.agents.get("worker");Building b=s.buildings.get("office");
        for(var station:Workspaces.stations(b)) {
            Resident blocker=new Resident();blocker.id=station.id();blocker.name="Occupant";blocker.home="apt-101";blocker.location=b.id;blocker.x=station.x();blocker.z=station.z();s.agents.put(blocker.id,blocker);
        }
        Engine.tick(s);assertEquals(0,a.pending.workMinutes);assertEquals("blocked",ActionPerformance.view(a).phase());assertNull(a.pending.workspaceId);
        assertEquals(0,WorkPayroll.view(a).getFirst().earnedCents());
        s.agents.remove(Workspaces.stations(b).getFirst().id());
        for(int i=0;i<12&&!a.pending.workspaceReady;i++)Engine.tick(s);assertTrue(a.pending.workspaceReady);
        assertEquals(0,a.pending.workMinutes);Engine.tick(s);assertEquals(1,a.pending.workMinutes);
    }
    @Test void twoWorkersUseDifferentRealStationsAndTheirBreaksNeverMoveOneAnother() {
        State s=world("WORK");Resident a=s.agents.get("worker"),b=new Resident();b.id="second";b.name="Second";b.home="apt-101";b.location=a.location;b.x=a.x+1;b.z=a.z;b.job=a.job;s.agents.put(b.id,b);
        Engine.apply(s,b,new Command("second-job",ActionType.WORK,null,null,null,null,null),"second-job",new Engine.Effects());
        for(int i=0;i<12&&(!a.pending.workspaceReady||!b.pending.workspaceReady);i++)Engine.tick(s);
        assertTrue(a.pending.workspaceReady);assertTrue(b.pending.workspaceReady);assertNotEquals(a.pending.workspaceId,b.pending.workspaceId);assertTrue(Math.hypot(a.x-b.x,a.z-b.z)>.65);
        int before=a.pending.workMinutes;double bx=b.x,bz=b.z;
        Engine.apply(s,a,new Command("break",ActionType.PAUSE_ACTIVITY,null,null,null,null,null),"break",new Engine.Effects());
        for(int i=0;i<5;i++)Engine.tick(s);assertEquals(before,a.pending.workMinutes);assertEquals(bx,b.x);assertEquals(bz,b.z);
    }
    @Test void ordinaryEntryAndDepartureGoAroundActualTablesAndChairBacks() {
        State s=world("WORK");Resident a=s.agents.get("worker");a.pending=null;Building b=s.buildings.get("office");var door=Places.entrance(b);
        a.x=door.x();a.z=door.z();List<double[]> entry=Workspaces.entryRoute(s,a,b);
        assertTrue(entry.size()>=3,"A desk chair must be approached from its side");checkTables(b,a.x,a.z,entry);
        for(var station:Workspaces.stations(b)) {
            a.x=station.x();a.z=station.z();List<double[]> exit=Workspaces.exitRoute(s,a,b);assertFalse(exit.isEmpty());
            checkTables(b,a.x,a.z,exit);assertEquals(door.x(),exit.getLast()[0],1e-7);assertEquals(door.z(),exit.getLast()[1],1e-7);
        }
    }
    private void checkTables(Building b,double x,double z,List<double[]> route) {
        for(double[] end:route) {
            int samples=Math.max(1,(int)Math.ceil(Math.hypot(end[0]-x,end[1]-z)/.025));
            for(int i=0;i<=samples;i++)for(var station:Workspaces.stations(b)) {
                double px=x+(end[0]-x)*i/samples,pz=z+(end[1]-z)*i/samples;
                double dx=px-station.x()-.7*Math.sin(station.heading()),dz=pz-station.z()-.7*Math.cos(station.heading());
                double localX=dx*Math.cos(station.heading())-dz*Math.sin(station.heading()),localZ=dx*Math.sin(station.heading())+dz*Math.cos(station.heading());
                assertFalse(Math.abs(localX)<.975-1e-6&&Math.abs(localZ)<.625-1e-6,"The received path must clear the real table and body clearance");
            }
            x=end[0];z=end[1];
        }
    }
}
