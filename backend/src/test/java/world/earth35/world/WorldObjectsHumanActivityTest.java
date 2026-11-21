package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;
import world.earth35.world.IslandGeography.Point;

class WorldObjectsHumanActivityTest {
    private State world() {
        State s=new State();s.geographyVersion=2;s.minute=600;
        Resident a=new Resident();a.id="maker";a.name="Maker";a.home="home";a.location="road";s.agents.put(a.id,a);
        double[] plot=plot(s);a.x=plot[0]-5.5;a.z=plot[1];return s;
    }
    private double[] plot(State s) {
        for(int x=-500;x<200;x+=19)for(int z=-470;z<300;z+=19) {
            try {WorldObjects.requireClear(s,x,z,null,"maker");
                if(IslandGeography.dryLand(x-8,z)&&IslandGeography.dryLand(x+8,z)&&IslandGeography.dryLand(x,z+8))return new double[]{x,z};
            }catch(Engine.Invalid ignored) {}
        }
        throw new AssertionError("No safe sign plot");
    }
    private String point(double x,double z) {return "point:"+x+","+z;}
    private void place(State s,double x,double z) {WorldObjects.place(s,s.agents.get("maker"),point(x,z),"signboard","Workshop ahead","install",new Engine.Effects());}
    private void arrive(State s) {
        Resident a=s.agents.get("maker");
        for(int i=0;i<20&&a.pending!=null&&!"perform".equals(WorldObjects.workView(a).phase());i++)Engine.tick(s);
        assertNotNull(a.pending);assertEquals("perform",WorldObjects.workView(a).phase());assertEquals(0,a.pending.workMinutes);
    }
    @Test void installationActuallyApproachesTheSignAroundExistingObjectsBeforeWorkStarts() {
        State s=world();Resident a=s.agents.get("maker");double x=a.x+5.5,z=a.z;
        WorldObjects.PlacedObject obstruction=new WorldObjects.PlacedObject();obstruction.id="old-sign";obstruction.owner="other";obstruction.kind="signboard";obstruction.text="Existing sign";obstruction.x=x-3.2;obstruction.z=z;s.worldObjects.put(obstruction.id,obstruction);
        Building footprint=new Building();footprint.x=obstruction.x;footprint.z=obstruction.z;
        place(s,x,z);assertEquals("approach",WorldObjects.workView(a).phase());double travelled=0;
        for(int i=0;i<20&&!"perform".equals(WorldObjects.workView(a).phase());i++) {
            double previousX=a.x,previousZ=a.z;Engine.tick(s);assertNotNull(a.pending);assertEquals(0,a.pending.workMinutes);
            assertEquals(previousX,a.activityMotion.getFirst().x(),1e-8);assertEquals(previousZ,a.activityMotion.getFirst().z(),1e-8);
            double effort=0;
            for(int j=1;j<a.activityMotion.size();j++) {
                var from=a.activityMotion.get(j-1);var to=a.activityMotion.get(j);
                double distance=Math.hypot(to.x()-from.x(),to.z()-from.z());assertTrue(distance<=.50001);travelled+=distance;
                assertFalse(IslandNavigation.intersects(new Point(from.x(),from.z()),new Point(to.x(),to.z()),footprint,1.35-1e-6));
                effort+=Travel.segmentEffort(s,from.x(),from.z(),to.x(),to.z());
            }
            assertTrue(effort<=CityLayout.of(s).walkingSpeed()+.02);
        }
        assertEquals("perform",WorldObjects.workView(a).phase());assertTrue(travelled>3.5);assertEquals(1.65,Math.hypot(a.x-x,a.z-z),1e-6);
        Engine.tick(s);assertEquals(1,a.pending.workMinutes);assertFalse(s.worldObjects.containsKey("object:install"));
        for(int i=0;i<14;i++)Engine.tick(s);assertTrue(s.worldObjects.containsKey("object:install"));assertNull(a.pending);
    }
    @Test void repositioningVisitsTheOriginalSignAndNewWorkPositionWithoutInventingCarrying() {
        State s=world();Resident a=s.agents.get("maker");double x=a.x+5.5,z=a.z;
        WorldObjects.PlacedObject original=new WorldObjects.PlacedObject();original.id="sign";original.owner=a.id;original.kind="signboard";original.text="Directions";original.x=x;original.z=z;s.worldObjects.put(original.id,original);
        a.x=x-5;
        WorldObjects.move(s,a,original.id,point(x,z+3),"move",new Engine.Effects());assertTrue(WorldObjects.workView(a).task().contains("original"));
        boolean visitedOriginal=false;
        for(int i=0;i<20&&!"perform".equals(WorldObjects.workView(a).phase());i++) {
            Engine.tick(s);assertNotNull(a.pending);assertEquals(0,a.pending.workMinutes);assertEquals(x,original.x);assertEquals(z,original.z);
            visitedOriginal|=a.activityMotion.stream().anyMatch(pose->Math.abs(Math.hypot(pose.x()-x,pose.z()-z)-1.65)<.001);
            assertNull(ActionPerformance.view(a).heldItem(),"The sign remains installed until completion; no carrying is reported");
        }
        assertTrue(visitedOriginal);assertEquals("perform",WorldObjects.workView(a).phase());assertEquals(1.65,Math.hypot(a.x-x,a.z-z-3),1e-6);
        for(int i=0;i<9;i++)Engine.tick(s);assertEquals(9,a.pending.workMinutes);assertEquals(z,original.z);
        Engine.tick(s);assertNull(a.pending);assertEquals(z+3,original.z);assertEquals(x,original.x);
    }
    @Test void blockedApproachCannotEarnLaborAndCanResumeWhenThePathIsClear() {
        State s=world();Resident a=s.agents.get("maker");double x=a.x+5.5,z=a.z;
        Resident other=new Resident();other.id="other";other.name="Other";other.location="road";other.x=x-3;other.z=z;s.agents.put(other.id,other);
        place(s,x,z);Engine.tick(s);assertNotNull(a.pending);assertEquals(0,a.pending.workMinutes);assertEquals("blocked",WorldObjects.workView(a).phase());
        double stoppedX=a.x,stoppedZ=a.z;for(int i=0;i<3;i++)Engine.tick(s);
        assertEquals(0,a.pending.workMinutes);assertEquals(stoppedX,a.x,1e-8);assertEquals(stoppedZ,a.z,1e-8);assertTrue(s.worldObjects.isEmpty());
        other.x=x+5;arrive(s);Engine.tick(s);assertEquals(1,a.pending.workMinutes);
    }
    @Test void pausedAndRestartedInstallationPreservesOnlyActualWorkAndStopRefundsTheReservation() throws Exception {
        State s=world();Resident a=s.agents.get("maker");double x=a.x+5.5,z=a.z;long before=a.money;
        place(s,x,z);arrive(s);for(int i=0;i<4;i++)Engine.tick(s);a.pending.activityPaused=true;
        for(int i=0;i<5;i++)Engine.tick(s);assertEquals(4,a.pending.workMinutes);assertTrue(s.worldObjects.isEmpty());
        ObjectMapper json=new ObjectMapper();s=json.readValue(json.writeValueAsString(s),State.class);a=s.agents.get("maker");
        assertEquals("paused",WorldObjects.workView(a).phase());a.pending.activityPaused=false;Engine.tick(s);assertEquals(5,a.pending.workMinutes);
        WorldObjects.interrupt(s,a,new Engine.Effects(),"Resident chose to stop");assertNull(a.pending);assertEquals(before,a.money);
        assertFalse(s.accounts.containsKey("escrow:object:install"));assertTrue(s.worldObjects.isEmpty());
    }
}
