package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.protocol.Contracts.*;
import world.earth35.world.State.*;

class CompanionWalkTest {
    private State world() {State s=State.seed();s.minute=720;s.agents.clear();return s;}
    private Resident resident(State s,String id,double offset) {
        Resident a=new Resident();a.id=id;a.name=id;a.home="apt-101";a.location="meadow-commons";
        Building place=s.buildings.get(a.location);a.x=place.x+offset;a.z=place.z;s.agents.put(id,a);return a;
    }
    private void start(State s,Resident a,String label,int minutes) {
        ActivityExecution.start(s,a,"walk-"+a.id,a.location,label,"walk",minutes,new Engine.Effects());
    }
    private Engine.Effects tick(State s) {return tick(s,List.of());}
    private Engine.Effects tick(State s,List<Traffic.Vehicle> vehicles) {
        s.minute++;Engine.Effects effects=new Engine.Effects();
        for(Resident a:s.agents.values())if(a.pending!=null&&"DO_ACTIVITY".equals(a.pending.type))
            ActivityExecution.tick(s,a,effects,vehicles,List.of());
        return effects;
    }
    private double separation(Resident a,Resident b) {return Math.hypot(a.x-b.x,a.z-b.z);}
    private double traceDistance(List<Traffic.Pose> points) {
        double total=0;for(int i=1;i<points.size();i++)total+=Math.hypot(points.get(i).x()-points.get(i-1).x(),points.get(i).z()-points.get(i-1).z());return total;
    }
    private double traceEffort(List<Traffic.Pose> points) {
        double total=0;for(int i=1;i<points.size();i++)total+=TerrainElevation.segmentEffort(points.get(i-1).x(),points.get(i-1).z(),points.get(i).x(),points.get(i).z());return total;
    }
    @Test void independentlyChosenSharedStrollKeepsOneCircuitAndWalksBesideItsCompanion() {
        State s=world();Resident a=resident(s,"Aisha",0),b=resident(s,"Oliver",.8);
        start(s,a,"Stroll with Oliver",24);start(s,b,"Stroll with Aisha",24);
        double maxRadius=0;int sideBySide=0;
        for(int minute=1;minute<=20;minute++) {
            tick(s);
            assertNotNull(a.pending);assertNotNull(b.pending);
            ActivityExecution.Progress ap=a.pending.activityExecution,bp=b.pending.activityExecution;
            assertEquals("travel",ap.phase,"Using the walking effort budget does not mean the route is blocked");
            assertEquals("travel",bp.phase);assertNull(a.pending.waitingReason);assertNull(b.pending.waitingReason);
            assertEquals(minute,ap.activeMinutes,"A group member must not tick again when its map entry is visited");
            assertEquals(minute,bp.activeMinutes);assertEquals(ap.nextStop,bp.nextStop);assertEquals(ap.walkLeader,bp.walkLeader);
            assertEquals(List.of("Aisha","Oliver"),ap.walkCompanions);assertEquals(ap.walkCompanions,bp.walkCompanions);
            assertTrue(separation(a,b)<3.2,"Walking partners stay close through the circuit's corners");
            if(separation(a,b)>.8&&separation(a,b)<2.1)sideBySide++;
            assertTrue(traceEffort(a.activityMotion)<=CityLayout.of(s).walkingSpeed()+1e-6);
            assertTrue(traceEffort(b.activityMotion)<=CityLayout.of(s).walkingSpeed()+1e-6);
            assertEquals(traceDistance(a.activityMotion),ap.distance-(minute==1?0:previousDistance),1e-5);
            previousDistance=ap.distance;
            maxRadius=Math.max(maxRadius,Math.hypot(a.x-ap.originX,a.z-ap.originZ));
        }
        assertTrue(sideBySide>=15,"A stroll keeps human-scale separation rather than stacking companions at one marker");
        assertTrue(maxRadius>18,"Walking together must still explore the selected grounds");
    }
    private double previousDistance;

    @Test void coLocatedArrivalsWalkIntoASideBySideFormationDuringTheirFirstMovingMinute() {
        State s=world();Resident a=resident(s,"Aisha",0),b=resident(s,"Oliver",0);
        start(s,a,"Stroll with Oliver",8);start(s,b,"Stroll with Aisha",8);
        double entryX=a.x,entryZ=a.z;
        tick(s);
        assertTrue(separation(a,b)>.8&&separation(a,b)<2.1,()->"Companions physically form up near the entry rather than overlapping until a distant waypoint: gap="
            +separation(a,b)+"; first approach="+a.pending.activityExecution.walkApproach.stream().map(Arrays::toString).toList()
            +"; second approach="+b.pending.activityExecution.walkApproach.stream().map(Arrays::toString).toList());
        for(Resident person:List.of(a,b)) {
            assertFalse(person.activityMotion.isEmpty());
            assertEquals(entryX,person.activityMotion.getFirst().x());assertEquals(entryZ,person.activityMotion.getFirst().z());
            assertTrue(traceEffort(person.activityMotion)<=CityLayout.of(s).walkingSpeed()+1e-6);
            assertTrue(person.pending.activityExecution.distance>4);
            for(int i=1;i<person.activityMotion.size();i++) {
                Traffic.Pose from=person.activityMotion.get(i-1),to=person.activityMotion.get(i);
                assertTrue(Math.hypot(to.x()-from.x(),to.z()-from.z())<=.5+1e-6,"Formation develops through continuous movement");
            }
        }
        assertFalse(a.pending.activityExecution.walkApproach.isEmpty());
        assertEquals(a.pending.activityExecution.nextStop,b.pending.activityExecution.nextStop);
        for(int minute=2;minute<=4;minute++) {
            tick(s);
            assertTrue(separation(a,b)>.7&&separation(a,b)<3.2,"The shared entry detour must keep companions together through minute "+minute+"; gap="+separation(a,b));
        }
    }

    @Test void pausedCompanionMakesBothWaitWithoutCountingAStationaryMinute() {
        State s=world();Resident a=resident(s,"Aisha",0),b=resident(s,"Oliver",.8);
        start(s,a,"Stroll with Oliver",6);start(s,b,"Stroll with Aisha",6);tick(s);
        double ax=a.x,az=a.z,bx=b.x,bz=b.z;b.paused=true;
        tick(s);tick(s);
        assertEquals(ax,a.x);assertEquals(az,a.z);assertEquals(bx,b.x);assertEquals(bz,b.z);
        assertEquals(1,a.pending.activityExecution.activeMinutes);assertEquals(1,b.pending.activityExecution.activeMinutes);
        assertEquals("blocked",a.pending.activityExecution.phase);assertTrue(a.pending.waitingReason.contains("Oliver"));
        b.paused=false;tick(s);assertEquals(2,a.pending.activityExecution.activeMinutes);assertEquals(2,b.pending.activityExecution.activeMinutes);
    }

    @Test void trafficBlockingOnePartnerStopsTheWholePairBeforeEitherTakesAStep() {
        State s=world();Resident a=resident(s,"Aisha",0),b=resident(s,"Oliver",.8);
        start(s,a,"Stroll with Oliver",6);start(s,b,"Stroll with Aisha",6);tick(s);
        double ax=a.x,az=a.z,bx=b.x,bz=b.z;
        Traffic.Vehicle obstacle=new Traffic.Vehicle("blocked","car",null,"Stopped vehicle",b.x,b.z,0,null,0,List.of());
        tick(s,List.of(obstacle));
        assertEquals(ax,a.x,1e-9);assertEquals(az,a.z,1e-9);assertEquals(bx,b.x,1e-9);assertEquals(bz,b.z,1e-9);
        assertEquals(1,a.pending.activityExecution.activeMinutes);assertEquals(1,b.pending.activityExecution.activeMinutes);
        assertEquals("blocked",a.pending.activityExecution.phase);assertTrue(a.pending.waitingReason.contains("traffic"));
        tick(s);assertEquals(2,a.pending.activityExecution.activeMinutes);assertEquals(2,b.pending.activityExecution.activeMinutes);
    }

    @Test void mentioningAPersonNeverEnrollsThemOrOverridesTheirOwnSoloWalk() {
        State s=world();Resident a=resident(s,"Aisha",0),b=resident(s,"Oliver",.8),bystander=resident(s,"Mei",1.1);
        start(s,a,"Stroll with Oliver",6);start(s,b,"Take a quiet solo walk",6);
        tick(s);
        assertTrue(a.pending.activityExecution.walkCompanions.isEmpty());assertTrue(b.pending.activityExecution.walkCompanions.isEmpty());
        assertNull(bystander.pending);assertTrue(bystander.activityHistory.isEmpty());
    }

    @Test void acceptedPlanCoordinatesOnlyTheActualWalkingParticipants() {
        State s=world();Resident a=resident(s,"Aisha",0),b=resident(s,"Oliver",.8),c=resident(s,"Mei",1.1);
        start(s,a,"Take a stroll",8);start(s,b,"Walk through the commons",8);
        ActivityExecution.start(s,c,"observe-Mei",c.location,"Watch the birds","observe",8,new Engine.Effects());
        SocialPlan plan=new SocialPlan();plan.id="agreed-walk";plan.proposer=a.id;plan.invited=b.id;
        plan.status="accepted";plan.expires=s.minute+100;plan.destination=a.location;s.socialPlans.put(plan.id,plan);
        a.pending.socialPlanId=plan.id;b.pending.socialPlanId=plan.id;c.pending.socialPlanId=plan.id;
        tick(s);
        assertEquals(List.of("Aisha","Oliver"),a.pending.activityExecution.walkCompanions);
        assertEquals(a.pending.activityExecution.walkLeader,b.pending.activityExecution.walkLeader);
        assertTrue(c.pending.activityExecution.walkCompanions.isEmpty());assertEquals(0,c.pending.activityExecution.distance);
    }

    @Test void savedSharedItineraryResumesWithoutReplanningTeleportingOrCreditingDowntime() throws Exception {
        State s=world();Resident a=resident(s,"Aisha",0),b=resident(s,"Oliver",.8);
        start(s,a,"Stroll with Oliver",8);start(s,b,"Stroll with Aisha",8);tick(s);
        ObjectMapper json=new ObjectMapper();State saved=json.readValue(json.writeValueAsString(s),State.class);
        Resident restored=saved.agents.get(a.id),partner=saved.agents.get(b.id);double x=restored.x,z=restored.z;
        List<double[]> circuit=ActivityExecution.copy(restored.pending.activityExecution.stops);saved.minute+=20;tick(saved);
        assertEquals(x,restored.x);assertEquals(z,restored.z);assertEquals(1,restored.pending.activityExecution.activeMinutes);
        tick(saved);assertEquals(2,restored.pending.activityExecution.activeMinutes);assertEquals(2,partner.pending.activityExecution.activeMinutes);
        for(int i=0;i<circuit.size();i++)assertArrayEquals(circuit.get(i),restored.pending.activityExecution.stops.get(i),1e-9);
        assertTrue(Math.hypot(restored.x-x,restored.z-z)<=8+1e-6);assertTrue(separation(restored,partner)<3.2);
    }

    @Test void aTaskEntryInSomebodyElsesPlanDoesNotEstablishInvitationOrConsent() {
        State s=world();Resident a=resident(s,"Aisha",0),b=resident(s,"Oliver",.8),other=resident(s,"Mei",1.1);
        for(Resident resident:List.of(a,b,other))start(s,resident,"Take a stroll",8);
        SocialPlan plan=new SocialPlan();plan.id="agreed-walk";plan.proposer=a.id;plan.invited=b.id;
        plan.status="accepted";plan.expires=s.minute+100;plan.destination=a.location;
        plan.tasks.put(other.id,new PlanTask(other.id,other.location,"Take a stroll",8));s.socialPlans.put(plan.id,plan);
        for(Resident resident:List.of(a,b,other))resident.pending.socialPlanId=plan.id;
        tick(s);
        assertEquals(List.of("Aisha","Oliver"),a.pending.activityExecution.walkCompanions);
        assertEquals(a.pending.activityExecution.walkCompanions,b.pending.activityExecution.walkCompanions);
        assertTrue(other.pending.activityExecution.walkCompanions.isEmpty());
    }

    @Test void aCompanionStoppingCancelsTheSharedWalkWithoutDraggingOrCompletingThem() {
        State s=world();Resident a=resident(s,"Aisha",0),b=resident(s,"Oliver",.8);
        start(s,a,"Stroll with Oliver",8);start(s,b,"Stroll with Aisha",8);tick(s);
        b.pending=null;double bx=b.x,bz=b.z;Engine.Effects effects=tick(s);
        assertNull(a.pending);assertNull(b.pending);assertEquals(bx,b.x);assertEquals(bz,b.z);
        assertEquals(1,effects.completions.size());assertEquals("cancelled",effects.completions.getFirst().status());
        assertTrue(a.activityHistory.isEmpty());assertTrue(b.activityHistory.isEmpty());
    }

    @Test void shorterDurationFinishesOnlyThatResidentAndReturnsTheRemainingChoiceToTheirCompanion() {
        for(boolean acceptedPlan:List.of(false,true)) {
            State s=world();Resident a=resident(s,"Aisha",0),b=resident(s,"Oliver",.8);
            start(s,a,acceptedPlan?"Take a stroll":"Stroll with Oliver",2);
            start(s,b,acceptedPlan?"Walk around the grounds":"Stroll with Aisha",4);
            if(acceptedPlan) {
                SocialPlan plan=new SocialPlan();plan.id="agreed-walk";plan.proposer=a.id;plan.invited=b.id;
                plan.status="accepted";plan.expires=s.minute+100;plan.destination=a.location;s.socialPlans.put(plan.id,plan);
                a.pending.socialPlanId=plan.id;b.pending.socialPlanId=plan.id;
            }
            tick(s);Engine.Effects completed=tick(s);
            assertNull(a.pending);assertNull(b.pending);
            assertEquals(1,a.activityHistory.size());assertEquals(2,a.activityHistory.getFirst().activeMinutes());
            assertTrue(b.activityHistory.isEmpty());assertEquals(2,completed.completions.size());
            assertTrue(completed.completions.stream().anyMatch(c->c.id().equals("walk-Aisha")&&c.status().equals("completed")));
            assertTrue(completed.completions.stream().anyMatch(c->c.id().equals("walk-Oliver")&&c.status().equals("cancelled")&&c.result().contains("chosen walking duration")));
            double bx=b.x,bz=b.z;tick(s);assertEquals(bx,b.x);assertEquals(bz,b.z);
        }
    }

    @Test void everyCompanionsPresenceRequirementsAreCheckedBeforeEitherMoves() {
        State s=world();Resident a=resident(s,"Aisha",0),b=resident(s,"Oliver",.8),other=resident(s,"Mei",1.1);
        start(s,a,"Stroll with Oliver",4);start(s,b,"Stroll with Aisha and Mei",4);
        other.x+=50;double ax=a.x,az=a.z,bx=b.x,bz=b.z;
        Engine.Effects result=tick(s);
        assertNull(a.pending);assertNull(b.pending);assertEquals(2,result.completions.size());
        assertTrue(result.completions.stream().allMatch(c->c.status().equals("cancelled")&&c.result().contains("Mei")));
        assertEquals(ax,a.x);assertEquals(az,a.z);assertEquals(bx,b.x);assertEquals(bz,b.z);
        assertTrue(a.activityHistory.isEmpty());assertTrue(b.activityHistory.isEmpty());
    }

    @Test void aPeersSavedSunlightOrInspectionRequirementCannotBeBypassedByGroupMovement() {
        for(String invalidLabel:List.of("Stroll in sunlight with Aisha","Inspect the plants with Aisha")) {
            State s=world();Resident a=resident(s,"Aisha",0),b=resident(s,"Oliver",.8);
            start(s,a,"Stroll with Oliver",4);start(s,b,"Stroll with Aisha",4);
            b.pending.activityExecution.label=invalidLabel;s.minute=1080;
            double ax=a.x,az=a.z,bx=b.x,bz=b.z;Engine.Effects result=tick(s);
            assertNull(a.pending);assertNull(b.pending);assertEquals(2,result.completions.size());
            assertTrue(result.completions.stream().allMatch(c->c.status().equals("cancelled")));
            assertEquals(ax,a.x);assertEquals(az,a.z);assertEquals(bx,b.x);assertEquals(bz,b.z);
        }
    }

    @Test void legacyPartnerProgressResumesTogetherWithoutAnOrderDependentSoloStep() {
        for(boolean absentProgress:List.of(false,true))for(boolean reverseOrder:List.of(false,true)) {
            State s=world();Resident a=resident(s,"Aisha",0),b=resident(s,"Oliver",.8);
            start(s,a,"Stroll with Oliver",4);start(s,b,"Stroll with Aisha",4);
            if(absentProgress)b.pending.activityExecution=null;else b.pending.activityExecution.walkVersion=0;
            if(reverseOrder) {s.agents.clear();s.agents.put(b.id,b);s.agents.put(a.id,a);}
            double ax=a.x,az=a.z,bx=b.x,bz=b.z;
            tick(s);
            assertEquals(ax,a.x);assertEquals(az,a.z);assertEquals(bx,b.x);assertEquals(bz,b.z);
            assertEquals(0,a.pending.activityExecution.activeMinutes);assertEquals(0,b.pending.activityExecution.activeMinutes);
            tick(s);
            assertEquals(1,a.pending.activityExecution.activeMinutes);assertEquals(1,b.pending.activityExecution.activeMinutes);
            assertEquals(List.of("Aisha","Oliver"),a.pending.activityExecution.walkCompanions);
            assertEquals(a.pending.activityExecution.walkCompanions,b.pending.activityExecution.walkCompanions);
            assertTrue(separation(a,b)<3.2);
        }
    }

    @Test void acceptedStrollTraceUsesOneSharedPlaybackClockThroughEveryCornerAndWait() {
        State s=world();Resident a=resident(s,"stroller",0),b=resident(s,"companion",0);
        a.name="Nadia Petrova";b.name="Omar Farouk";
        Engine.Effects accepted=new Engine.Effects();
        Engine.apply(s,a,new Command("shared-stroll",ActionType.PROPOSE_PLAN,b.id,"Stroll around Meadow Commons",a.location,1,null),"shared-stroll",accepted);
        SocialPlan plan=s.socialPlans.get("shared-stroll");
        for(Resident person:List.of(a,b))plan.tasks.put(person.id,new PlanTask(person.id,person.location,plan.activity,30));
        Engine.apply(s,b,new Command("accept-stroll",ActionType.ACCEPT_PLAN,plan.id,null,null,null,null),"accept-stroll",accepted);
        while(s.minute<plan.meetingMinute)Engine.tick(s);
        int movingMinutes=0;double playbackTime=0,jointHoldTime=0;
        for(int minute=0;minute<60&&!"completed".equals(plan.status);minute++) {
            Engine.tick(s);
            if(a.activityMotion.size()<2&&b.activityMotion.size()<2)continue;
            movingMinutes++;
            assertEquals(a.activityMotion.size(),b.activityMotion.size(),"Walking companions share every playback time sample");
            assertEquals(a.x,a.activityMotion.getLast().x(),1e-9);assertEquals(a.z,a.activityMotion.getLast().z(),1e-9);
            assertEquals(b.x,b.activityMotion.getLast().x(),1e-9);assertEquals(b.z,b.activityMotion.getLast().z(),1e-9);
            for(Resident person:List.of(a,b)) {
                assertEquals(0.0,person.activityMotion.getFirst().fraction());
                assertEquals(1.0,person.activityMotion.getLast().fraction());
                double previous=-1;
                for(Traffic.Pose pose:person.activityMotion) {
                    assertNotNull(pose.fraction());assertTrue(Double.isFinite(pose.fraction()));
                    assertTrue(pose.fraction()>=0&&pose.fraction()<=1&&pose.fraction()>=previous,"Within-tick timestamps stay ordered");
                    previous=pose.fraction();
                }
            }
            for(int vertex=1;vertex<a.activityMotion.size();vertex++) {
                Traffic.Pose ap=a.activityMotion.get(vertex-1),an=a.activityMotion.get(vertex);
                Traffic.Pose bp=b.activityMotion.get(vertex-1),bn=b.activityMotion.get(vertex);
                assertEquals(ap.fraction(),bp.fraction());assertEquals(an.fraction(),bn.fraction());
                double time=an.fraction()-ap.fraction();playbackTime+=time;
                if(Math.hypot(ap.x()-an.x(),ap.z()-an.z())<1e-4&&Math.hypot(bp.x()-bn.x(),bp.z()-bn.z())<1e-4)jointHoldTime+=time;
            }
            if(movingMinutes>1)for(int frame=0;frame<=100;frame++) {
                double fraction=frame/100.0;Traffic.Pose ap=replay(a.activityMotion,fraction),bp=replay(b.activityMotion,fraction);
                double gap=Math.hypot(ap.x()-bp.x(),ap.z()-bp.z());
                assertTrue(gap>=.7-1e-7,"Companions' real playback bodies stay separate at minute "+s.minute+", fraction "+fraction+"; gap="+gap);
            }
        }
        assertEquals("completed",plan.status);assertEquals(30,movingMinutes);
        assertTrue(jointHoldTime<playbackTime*.05,"Walking playback must use real movement time, rather than pausing for each tiny numerical cut: "+jointHoldTime+"/"+playbackTime);
        assertEquals(30,a.activityHistory.getFirst().activeMinutes());assertEquals(30,b.activityHistory.getFirst().activeMinutes());
    }
    private Traffic.Pose replay(List<Traffic.Pose> points,double fraction) {
        if(points.stream().allMatch(p->p.fraction()!=null)) {
            for(int index=1;index<points.size();index++) {
                Traffic.Pose a=points.get(index-1),b=points.get(index);
                if(fraction>b.fraction()||b.fraction()<=a.fraction())continue;
                double t=Math.max(0,Math.min(1,(fraction-a.fraction())/(b.fraction()-a.fraction())));
                return new Traffic.Pose(a.x()+(b.x()-a.x())*t,a.z()+(b.z()-a.z())*t,b.heading());
            }
            return points.getLast();
        }
        double position=fraction*(points.size()-1);int index=Math.min(points.size()-2,(int)Math.floor(position));
        double t=position-index;Traffic.Pose a=points.get(index),b=points.get(index+1);
        return new Traffic.Pose(a.x()+(b.x()-a.x())*t,a.z()+(b.z()-a.z())*t,b.heading());
    }

    @Test void optionalMotionTimestampsPreserveTheExistingTrafficWireFormat() throws Exception {
        ObjectMapper json=new ObjectMapper();Traffic.Pose legacy=new Traffic.Pose(1,2,3);
        assertFalse(json.writeValueAsString(legacy).contains("fraction"));
        assertNull(json.readValue("{\"x\":1,\"z\":2,\"heading\":3}",Traffic.Pose.class).fraction());
        Traffic.Pose timed=new Traffic.Pose(1,2,3,.25);
        assertTrue(json.writeValueAsString(timed).contains("\"fraction\":0.25"));
        assertEquals(timed,json.readValue(json.writeValueAsString(timed),Traffic.Pose.class));
    }
}
