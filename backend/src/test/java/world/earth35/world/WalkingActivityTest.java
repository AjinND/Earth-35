package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class WalkingActivityTest {
    private Resident walker(State s) {
        Resident a=new Resident();a.id="stroller";a.name="Stroller";a.home="apt-101";a.location="meadow-commons";
        Building b=s.buildings.get(a.location);a.x=b.x;a.z=b.z;s.agents.put(a.id,a);return a;
    }
    private void start(State s,Resident a,int minutes) {
        Engine.apply(s,a,new Command("stroll",ActionType.DO_ACTIVITY,a.location,"Stroll around the meadow","walk",minutes,null),"stroll",new Engine.Effects());
    }
    @Test void aStrollTraversesTheGroundsAtWalkingPaceAndPublishesItsActualCorners() {
        State s=State.seed();s.minute=720;Resident a=walker(s);double originX=a.x,originZ=a.z,maxRadius=0;
        start(s,a,30);boolean turns=false;int movingMinutes=0;
        for(int minute=0;minute<30;minute++) {
            double previousX=a.x,previousZ=a.z;Engine.tick(s);
            List<Traffic.Pose> motion=WorldStore.publicAgent(a).motion();
            assertFalse(motion.isEmpty());assertEquals(previousX,motion.getFirst().x(),1e-7);assertEquals(previousZ,motion.getFirst().z(),1e-7);
            assertEquals(a.x,motion.getLast().x(),1e-7);assertEquals(a.z,motion.getLast().z(),1e-7);
            double effort=0;
            for(int i=1;i<motion.size();i++) {
                var before=motion.get(i-1);var point=motion.get(i);
                assertTrue(WalkingRoute.allowedFrom(s,a.location,originX,originZ,38,before.x(),before.z(),point.x(),point.z()),"Every replayed step stays off furniture, water and roads");
                assertTrue(Math.hypot(point.x()-before.x(),point.z()-before.z())<=.50001);
                effort+=TerrainElevation.segmentEffort(before.x(),before.z(),point.x(),point.z());
                if(i>1&&Math.abs(Math.atan2(Math.sin(point.heading()-before.heading()),Math.cos(point.heading()-before.heading())))>.05)turns=true;
            }
            assertTrue(effort<=CityLayout.of(s).walkingSpeed()+.03,"A tick cannot teleport through multiple corners");
            if(effort>CityLayout.of(s).walkingSpeed()*.8)movingMinutes++;
            maxRadius=Math.max(maxRadius,Math.hypot(a.x-originX,a.z-originZ));
        }
        assertNull(a.pending);assertTrue(maxRadius>18,"A stroll explores the meadow rather than its arrival point");
        assertTrue(movingMinutes>=25,"Ordinary waypoints do not stop the walker each minute");assertTrue(turns);
        assertEquals(30,a.activityHistory.getLast().activeMinutes());assertTrue(a.activityHistory.getLast().distance()>150);
        assertFalse(WorldStore.publicAgent(a).motion().isEmpty(),"The final completion still publishes its physical trace");
        Engine.tick(s);assertTrue(WorldStore.publicAgent(a).motion().isEmpty(),"A subsequent idle tick never replays an old walk");
    }
    @Test void routeProgressAndExactPhysicalPlaybackSurviveRestart() throws Exception {
        State original=State.seed();original.minute=720;Resident a=walker(original);start(original,a,18);
        for(int minute=0;minute<6;minute++)Engine.tick(original);
        ObjectMapper json=new ObjectMapper();State restored=json.readValue(json.writeValueAsString(original),State.class);Resident b=restored.agents.get(a.id);
        for(int minute=0;minute<12;minute++) {
            Engine.tick(original);Engine.tick(restored);
            assertEquals(a.x,b.x,1e-8);assertEquals(a.z,b.z,1e-8);
            assertEquals(WorldStore.publicAgent(a).motion(),WorldStore.publicAgent(b).motion());
        }
        assertNull(a.pending);assertNull(b.pending);assertEquals(a.activityHistory,b.activityHistory);
    }
    @Test void anExistingTinyLoopUpgradesFromItsRealPositionAndKeepsEarnedProgress() {
        State s=State.seed();s.minute=720;Resident a=walker(s);start(s,a,20);
        ActivityExecution.Progress old=a.pending.activityExecution;old.walkVersion=0;old.activeMinutes=4;old.distance=9;
        old.radius=6;old.originX=a.x;old.originZ=a.z;old.stops=new ArrayList<>(List.of(new double[]{a.x+1,a.z},new double[]{a.x,a.z+1}));
        double x=a.x,z=a.z;Engine.tick(s);
        assertNotNull(a.pending);assertEquals(1,a.pending.activityExecution.walkVersion);assertEquals(4,a.pending.activityExecution.activeMinutes);
        assertEquals(9,a.pending.activityExecution.distance);assertEquals(x,a.x);assertEquals(z,a.z);
        assertEquals(38,a.pending.activityExecution.radius);
        Engine.tick(s);assertEquals(5,a.pending.activityExecution.activeMinutes);assertTrue(a.pending.activityExecution.distance>9);
        assertTrue(Math.hypot(a.x-x,a.z-z)<=CityLayout.of(s).walkingSpeed()+.01);
        assertEquals(x,a.activityMotion.getFirst().x(),1e-7);assertEquals(z,a.activityMotion.getFirst().z(),1e-7);
    }
    @Test void exportAnActualSharedMeadowStrollForBrowserReplay() throws Exception {
        State s=State.seed();s.minute=720;Resident a=walker(s);a.name="Nadia Petrova";a.fixture=true;
        Resident b=new Resident();b.id="companion";b.name="Omar Farouk";b.home="apt-102";b.location=a.location;b.x=a.x;b.z=a.z;b.fixture=true;s.agents.put(b.id,b);
        Engine.apply(s,a,new Command("shared-stroll",ActionType.PROPOSE_PLAN,b.id,"Stroll around Meadow Commons",a.location,1,null),"shared-stroll",new Engine.Effects());
        SocialPlan plan=s.socialPlans.get("shared-stroll");
        for(String id:List.of(a.id,b.id))plan.tasks.put(id,new PlanTask(id,a.location,plan.activity,30));
        Engine.apply(s,b,new Command("accept-stroll",ActionType.ACCEPT_PLAN,plan.id,null,null,null,null),"accept-stroll",new Engine.Effects());
        while(s.minute<plan.meetingMinute)Engine.tick(s);
        List<World> frames=new ArrayList<>();frames.add(frame(s));
        for(int i=0;i<45&&!"completed".equals(plan.status);i++){Engine.tick(s);frames.add(frame(s));}
        assertEquals("completed",plan.status);assertTrue(a.activityHistory.getLast().distance()>100);assertTrue(b.activityHistory.getLast().distance()>100);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/natural-stroll-preview.json"),new ObjectMapper().writeValueAsString(Map.of("frames",frames)));
        Files.writeString(Path.of("target/walking-schemas.json"),io.swagger.v3.core.util.Json.mapper().writeValueAsString(Map.of(
            "openapi","3.0.1","info",Map.of("title","Walking state schema","version","1"),"paths",Map.of(),
            "components",Map.of("schemas",io.swagger.v3.core.converter.ModelConverters.getInstance().readAll(Resident.class)))));
    }
    private World frame(State s) {
        return new World(s.minute,s.version,Engine.weather(s.minute),Engine.temperature(s.minute),1000,
            s.buildings.values().stream().map(b->new StateBuilding(b.id,b.name,b.type,b.district,b.x,b.z,b.capacity,b.description,b.social,b.restAllowed,b.objects,Places.entrance(b),Places.hours(b,s.minute),b.outdoorRadius,b.plannedType)).toList(),
            s.agents.values().stream().map(WorldStore::publicAgent).toList(),List.of(),Traffic.network(s),Economy.summary(s),CityLayout.of(s));
    }
}
