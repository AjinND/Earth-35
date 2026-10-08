package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;
import world.earth35.protocol.Contracts.*;

class TerrainTravelTest {
    private State world() {State s=State.seed();s.geographyVersion=4;s.minute=720;return s;}
    private Resident walker(State s,double x,double z) {
        Resident a=new Resident();a.id="terrain-walker";a.ownerId="owner";a.name="Explorer";a.home="apt-101";
        a.location="point:"+x+","+z;a.x=x;a.z=z;a.energy=100;s.agents.put(a.id,a);return a;
    }
    private int directJourney(State s,Resident a,String type,double x,double z) {
        a.pending=Engine.pending("saved-terrain-trip",type,"point:"+x+","+z,0);a.pending.frontDoorRoute=true;
        a.pending.route.add(new double[]{x,z});a.location="road";
        int ticks=0;while(a.pending!=null&&ticks++<400) {Engine.tick(s);assertTrue(IslandGeography.dryLand(a.x,a.z));}
        assertNull(a.pending);assertEquals(x,a.x,1e-6);assertEquals(z,a.z,1e-6);return ticks;
    }
    @Test void ordinaryCityStreetsKeepTheirExistingSpeedAndEnergyCost() {
        for(String type:List.of("MOVE","RUN")) {
            State s=world();Resident a=walker(s,-425,-280);
            assertEquals(0,TerrainElevation.naturalElevation(a.x,a.z),1e-12);
            a.pending=Engine.pending("flat",type,"point:-390,-280",0);a.pending.frontDoorRoute=true;a.pending.route.add(new double[]{-390,-280});a.location="road";
            Engine.tick(s);
            assertEquals(type.equals("RUN")?16:8,a.x+425,1e-6);
            assertEquals(type.equals("RUN")?99.775:99.975,a.energy,1e-9);
        }
    }
    @Test void uphillProgressAndRunningEnergyMatchAuthoritativeRouteCosts() {
        double x=-250,lowZ=-350,highZ=-380;
        var up=TerrainElevation.segment(x,lowZ,x,highZ);var down=TerrainElevation.segment(x,highZ,x,lowZ);
        assertTrue(TerrainElevation.walkElevation(x,highZ)>TerrainElevation.walkElevation(x,lowZ)+5);
        assertTrue(up.walkingEffort()>down.walkingEffort());assertTrue(up.walkingEffort()>30);
        assertTrue(up.runningEnergyEffort()>up.walkingEffort());
        for(String type:List.of("MOVE","RUN")) {
            State s=world();Resident a=walker(s,x,lowZ);double speed=type.equals("RUN")?16:8;
            int ticks=directJourney(s,a,type,x,highZ);
            assertEquals((int)Math.ceil(up.walkingEffort()/speed),ticks,1);
            double expectedEnergy=.025*ticks;
            if(type.equals("RUN"))expectedEnergy+=.2*ticks+.2*(up.runningEnergyEffort()-up.walkingEffort())/speed;
            assertEquals(100-expectedEnergy,a.energy,.035);
        }
        State uphill=world(),downhill=world();Resident a=walker(uphill,x,lowZ),b=walker(downhill,x,highZ);
        a.pending=Engine.pending("up","MOVE","point:"+x+","+highZ,0);b.pending=Engine.pending("down","MOVE","point:"+x+","+lowZ,0);
        a.pending.frontDoorRoute=b.pending.frontDoorRoute=true;a.pending.route.add(new double[]{x,highZ});b.pending.route.add(new double[]{x,lowZ});a.location=b.location="road";
        Engine.tick(uphill);Engine.tick(downhill);
        assertTrue(Math.abs(a.z-lowZ)<Math.abs(b.z-highZ),"A climb must make less progress than the same descent");
    }
    @Test void gentleDescentCanBeFasterButSteepDescentsAreSlowerThanLevelGround() {
        assertEquals(1,TerrainElevation.effortMultiplier(0),1e-12);
        assertTrue(TerrainElevation.effortMultiplier(-.05)<1);
        assertTrue(TerrainElevation.effortMultiplier(-.8)>1);
        assertTrue(TerrainElevation.effortMultiplier(.8)>TerrainElevation.effortMultiplier(-.8));
        for(int i=-100;i<=100;i++)assertTrue(TerrainElevation.effortMultiplier(i/100.0)>=TerrainElevation.MIN_EFFORT_MULTIPLIER);
    }
    @Test void exactFlatShortcutExcludesEveryChannelAndBridgeRamp() {
        assertTrue(TerrainElevation.flatSegment(-425,-280,-390,-280));
        assertFalse(TerrainElevation.flatSegment(-250,-350,-250,-380));
        for(var bridge:IslandLandscapes.SPEC.bridges())
            assertFalse(TerrainElevation.flatSegment(bridge.x()-bridge.length()/2-4,bridge.z(),bridge.x()+bridge.length()/2+4,bridge.z()));
        for(int x=-425;x<=125;x+=50)for(int z=-275;z<=275;z+=50) {
            double nx=x+10,nz=z+10;if(!TerrainElevation.flatSegment(x,z,nx,nz))continue;
            for(int i=0;i<=40;i++)assertEquals(0,TerrainElevation.walkElevation(x+(nx-x)*i/40,z+(nz-z)*i/40),1e-12);
            var cost=TerrainElevation.segment(x,z,nx,nz);assertEquals(Math.hypot(nx-x,nz-z),cost.walkingEffort(),1e-12);assertEquals(cost.walkingEffort(),cost.runningEnergyEffort(),1e-12);
        }
    }
    @Test void historicalWorldsRetainTheirHorizontalTravelSemanticsUntilUpgrade() {
        State s=world();Resident a=walker(s,-250,-350);s.geographyVersion=3;
        a.pending=Engine.pending("old","MOVE","point:-250,-380",0);a.pending.frontDoorRoute=true;a.pending.route.add(new double[]{-250,-380});a.location="road";
        Engine.tick(s);assertEquals(-358,a.z,1e-6);
        assertEquals(30,Travel.routeEffort(s,walker(s,-250,-350),List.of(new double[]{-250,-380})),1e-12);
    }
    @Test void routeSearchKeepsAnEasierHillDetourInsteadOfRestoringTheVisibleStraightClimb() {
        State s=world();s.buildings.clear();Resident a=walker(s,-520,-350);
        Building goal=IslandGeography.coordinate("point:-250,-350");assertNotNull(goal);
        for(int i=0;i<=540;i++)assertTrue(IslandGeography.dryLand(a.x+(goal.x-a.x)*i/540,a.z+(goal.z-a.z)*i/540));
        double direct=TerrainElevation.segmentEffort(a.x,a.z,goal.x,goal.z);
        List<double[]> route=Travel.path(s,a,goal,"shortest");
        assertTrue(Travel.routeDistance(a,route)>270.5,"The lower-effort route needs a visible detour");
        assertTrue(Travel.routeEffort(s,a,route)<direct*.98,"A* and smoothing must retain a meaningful hill-cost improvement");
    }
    @Test void declaredCliffEdgesCannotBeCrossedByAVisibleShortcutOrSavedContinuation() throws Exception {
        State s=world();Resident a=walker(s,-715,5);Building goal=IslandGeography.coordinate("point:-715,65");assertNotNull(goal);
        assertFalse(IslandGeography.dryLand(-715,38));
        Engine.apply(s,a,new Command("chosen",ActionType.MOVE,goal.id,null,"shortest",null,null),"accepted",new Engine.Effects());
        assertTrue(Travel.routeDistance(a,a.pending.route)>61);
        a.money=12345;a.relationships.put("friend","close friend");Engine.tick(s);
        State loaded=new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(s),State.class);Resident saved=loaded.agents.get(a.id);
        assertEquals("accepted",saved.pending.id);assertEquals(12345,saved.money);assertEquals(a.relationships,saved.relationships);
        for(int i=0;i<400&&saved.pending!=null;i++){Engine.tick(loaded);assertTrue(IslandGeography.dryLand(saved.x,saved.z));}
        assertNull(saved.pending);assertEquals(goal.id,saved.location);
    }
    @Test void constantBridgeDecksJoinTheGroundThroughContinuousRamps() {
        for(var bridge:IslandLandscapes.SPEC.bridges()) {
            double center=TerrainElevation.walkElevation(bridge.x(),bridge.z());
            assertTrue(center>TerrainElevation.islandElevation(bridge.x(),bridge.z()));
            for(int side:new int[]{-1,1}) {
                double edge=bridge.x()+side*bridge.length()/2,outer=edge+side*4;
                assertEquals(center,TerrainElevation.walkElevation(edge,bridge.z()),1e-10);
                assertEquals(TerrainElevation.walkElevation(edge-side*1e-5,bridge.z()),TerrainElevation.walkElevation(edge+side*1e-5,bridge.z()),1e-4);
                assertEquals(TerrainElevation.walkElevation(outer-side*1e-5,bridge.z()),TerrainElevation.walkElevation(outer+side*1e-5,bridge.z()),1e-4);
                assertEquals(TerrainElevation.islandElevation(outer,bridge.z()),TerrainElevation.walkElevation(outer,bridge.z()),1e-12);
            }
        }
    }
    @Test void routeObservationsIncludeSlopeTimeAndRemainResponsive() {
        State s=world();Resident a=walker(s,-250,-350);Building goal=new Building("hill-destination","Hill destination","wilderness","Highlands",-250,-380,100);
        s.buildings.put(goal.id,goal);
        long start=System.nanoTime();var situation=Travel.situation(s,a);
        var option=situation.destinations().stream().filter(d->d.destination().equals(goal.id)).findFirst().orElseThrow().routes().getFirst();
        var route=Travel.path(s,a,goal,"shortest");double effort=Travel.routeEffort(s,a,route);
        assertEquals((int)Math.ceil(effort/8),option.walkingMinutes());assertEquals((int)Math.ceil(effort/16),option.runningMinutes());
        assertEquals(effort,option.walkingEffort(),.0051);
        assertTrue(option.runningEnergy()>.225*option.runningMinutes(),"The route fact must include uphill energy cost");
        assertTrue(option.walkingMinutes()>Math.ceil(option.distance()/8));
        assertTrue((System.nanoTime()-start)/1e9<12,"A complete travel observation should remain responsive");
    }
    @Test void exportIndependentTerrainAndDirectedCostSurveyForFrontendParity() throws Exception {
        Set<IslandGeography.Point> samples=new LinkedHashSet<>();
        for(int x=-775;x<=-425;x+=10)for(int z=-465;z<=120;z+=10)samples.add(new IslandGeography.Point(x,z));
        for(int x=-184;x<=-60;x+=4)for(int z=-434;z<=-316;z+=4)samples.add(new IslandGeography.Point(x,z));
        for(int x=-425;x<=125;x+=25)for(int z=-275;z<=275;z+=25)samples.add(new IslandGeography.Point(x,z));
        for(var trail:IslandLandscapes.SPEC.trails())addTrail(samples,trail.points());
        addTrail(samples,IslandLandscapes.SPEC.trail());addTrail(samples,IslandLandscapes.SPEC.fallsTrail());
        for(var bridge:IslandLandscapes.SPEC.bridges())for(double dx:new double[]{-bridge.length()/2-4,-bridge.length()/2-2,-bridge.length()/2,-1,0,1,bridge.length()/2,bridge.length()/2+2,bridge.length()/2+4})
            for(double dz:new double[]{-bridge.width()/2-.01,-bridge.width()/2+.01,0,bridge.width()/2-.01,bridge.width()/2+.01})samples.add(new IslandGeography.Point(bridge.x()+dx,bridge.z()+dz));
        List<Map<String,Object>> points=new ArrayList<>(),segments=new ArrayList<>();
        for(var p:samples)points.add(Map.of("x",p.x(),"z",p.z(),"dry",IslandGeography.dryLand(p.x(),p.z()),"naturalElevation",TerrainElevation.naturalElevation(p.x(),p.z()),"islandElevation",TerrainElevation.islandElevation(p.x(),p.z()),"walkElevation",TerrainElevation.walkElevation(p.x(),p.z())));
        for(double[] p:List.of(new double[]{-425,-280,-390,-280},new double[]{-250,-350,-250,-380},new double[]{-250,-380,-250,-350},new double[]{-520,-350,-250,-350}))addSegment(segments,p[0],p[1],p[2],p[3]);
        for(var bridge:IslandLandscapes.SPEC.bridges()) {double half=bridge.length()/2+4;addSegment(segments,bridge.x()-half,bridge.z(),bridge.x()+half,bridge.z());addSegment(segments,bridge.x()+half,bridge.z(),bridge.x()-half,bridge.z());}
        Files.writeString(Path.of("target/phase3-terrain-survey.json"),new ObjectMapper().writeValueAsString(Map.of("points",points,"segments",segments)));
    }
    private static void addTrail(Set<IslandGeography.Point> samples,List<List<Double>> trail) {
        for(int i=1;i<trail.size();i++) {var a=trail.get(i-1);var b=trail.get(i);int steps=Math.max(1,(int)Math.ceil(Math.hypot(b.get(0)-a.get(0),b.get(1)-a.get(1))/2));
            for(int j=0;j<=steps;j++)samples.add(new IslandGeography.Point(a.get(0)+(b.get(0)-a.get(0))*j/steps,a.get(1)+(b.get(1)-a.get(1))*j/steps));}
    }
    private static void addSegment(List<Map<String,Object>> segments,double x,double z,double nx,double nz) {
        var cost=TerrainElevation.segment(x,z,nx,nz);
        segments.add(Map.of("from",Map.of("x",x,"z",z),"to",Map.of("x",nx,"z",nz),"walkingEffort",cost.walkingEffort(),"runningEnergyEffort",cost.runningEnergyEffort(),"runningEnergy",(.025*cost.walkingEffort()+.2*cost.runningEnergyEffort())/16,"maxGrade",cost.maxGrade()));
    }
}
