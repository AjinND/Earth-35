package world.earth35.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import world.earth35.world.State.*;

class WalkingRouteTest {
    @Test void compactGardensAndCourtyardsAvoidTablesBedsAndPosts() {
        for(String type:List.of("garden","hangout")) {
            State state=new State();state.geographyVersion=0;
            Building place=new Building("grounds","Grounds",type,"",30,30,10);state.buildings.put(place.id,place);
            assertFalse(WalkingRoute.allowed(state,place.id,30,30,8,30,30),"The relocated table is solid");
            assertTrue(WalkingRoute.allowed(state,place.id,30,30,8,30,32),"The table no longer fills the bench aisle");
            if(type.equals("garden"))assertFalse(WalkingRoute.allowed(state,place.id,30,30,8,27.3,27.2));
            else assertFalse(WalkingRoute.allowed(state,place.id,30,30,8,26.5,33));
        }
    }
    private Resident resident(State state,String id,String location) {
        Resident resident=new Resident();resident.id=id;resident.location=location;
        Building place=state.buildings.get(location);resident.x=place.x;resident.z=place.z;return resident;
    }
    private void assertCircuit(State state,String location,WalkingRoute.Plan plan) {
        assertTrue(plan.points().size()>=3,"A walk needs a connected circuit");
        double[] previous=plan.points().getLast();
        for(double[] point:plan.points()) {
            assertTrue(WalkingRoute.allowed(state,location,plan.centerX(),plan.centerZ(),plan.radius(),point[0],point[1]));
            assertTrue(WalkingRoute.clearSegment(state,location,plan.centerX(),plan.centerZ(),plan.radius(),previous[0],previous[1],point[0],point[1]),"Every leg, including lap closure, is physically clear");
            previous=point;
        }
    }
    @Test void meadowStrollUsesTheGroundsAndFollowsOneOrderedCircuit() {
        State state=State.seed();Resident resident=resident(state,"walker","meadow-commons");
        WalkingRoute.Plan plan=WalkingRoute.plan(state,resident,resident.location,30);
        assertCircuit(state,resident.location,plan);
        assertFalse(plan.approach().isEmpty());assertArrayEquals(plan.points().getFirst(),plan.approach().getLast(),1e-9);
        assertTrue(plan.points().stream().mapToDouble(p->Math.hypot(p[0]-resident.x,p[1]-resident.z)).max().orElse(0)>20,"A spacious park must not collapse to a six-unit arrival circle");
        assertTrue(plan.points().stream().mapToDouble(p->p[0]).max().orElse(0)-plan.points().stream().mapToDouble(p->p[0]).min().orElse(0)>30);
        assertTrue(plan.points().stream().mapToDouble(p->p[1]).max().orElse(0)-plan.points().stream().mapToDouble(p->p[1]).min().orElse(0)>30);
        // A perimeter itinerary has substantial enclosed area; a radial
        // alternation through arrival would enclose very little.
        double area=0;double[] previous=plan.points().getLast();
        for(double[] point:plan.points()){area+=(previous[0]-plan.centerX())*(point[1]-plan.centerZ())-(point[0]-plan.centerX())*(previous[1]-plan.centerZ());previous=point;}
        assertTrue(Math.abs(area)/2>700);
        assertTrue(plan.points().stream().filter(p->Math.hypot(p[0]-plan.centerX(),p[1]-plan.centerZ())<8).count()<2);
    }
    @Test void parksAndLongNarrowDestinationsRemainOnTheirActualGrounds() {
        State state=State.seed();
        for(String location:List.of("meadow-commons","south-gardens","park","promenade","riverside-walk")) {
            Resident resident=resident(state,"walker",location);WalkingRoute.Plan plan=WalkingRoute.plan(state,resident,location,25);
            assertCircuit(state,location,plan);
            for(double[] point:plan.points()) {
                assertTrue(IslandGeography.dryLand(point[0],point[1]),location);
                assertTrue(IslandGeography.roadDistance(point[0],point[1])>=3.5,location);
            }
        }
    }
    @Test void tinyHistoricalParkStillHasALegalWalkAndOnlyExitsItsExistingFountainOverlap() {
        State state=State.prototypeSeed();Resident resident=resident(state,"walker","park");
        WalkingRoute.Plan plan=WalkingRoute.plan(state,resident,resident.location,10);
        assertCircuit(state,resident.location,plan);assertFalse(plan.approach().isEmpty());
        assertTrue(WalkingRoute.canStart(state,resident.location,plan.centerX(),plan.centerZ(),plan.radius(),resident.x,resident.z));
        assertFalse(WalkingRoute.allowed(state,resident.location,plan.centerX(),plan.centerZ(),plan.radius(),resident.x,resident.z),"The initial fountain-center arrival is historical, not a legal later waypoint");
        double x=resident.x,z=resident.z;
        for(double[] point:plan.approach()) {
            int count=Math.max(1,(int)Math.ceil(Math.hypot(point[0]-x,point[1]-z)/.1));double previousX=x,previousZ=z;
            for(int i=1;i<=count;i++) {
                double nx=x+(point[0]-x)*i/count,nz=z+(point[1]-z)*i/count;
                assertTrue(WalkingRoute.allowedFrom(state,resident.location,plan.centerX(),plan.centerZ(),plan.radius(),previousX,previousZ,nx,nz));
                previousX=nx;previousZ=nz;
            }
            x=point[0];z=point[1];
        }
        assertFalse(WalkingRoute.allowedFrom(state,resident.location,plan.centerX(),plan.centerZ(),plan.radius(),x,z,resident.x,resident.z),"After egress a walk cannot re-enter the fountain");
    }
    @Test void shortWalksFindClearGroundBeyondTheCentralFountain() {
        State state=State.prototypeSeed();Resident resident=resident(state,"walker","park");
        for(int minutes:List.of(1,4)) {
            WalkingRoute.Plan plan=WalkingRoute.plan(state,resident,resident.location,minutes);
            assertCircuit(state,resident.location,plan);
            assertFalse(plan.approach().isEmpty(),"Short outings still need a physical exit from the historical fountain-center arrival");
            assertTrue(Math.hypot(plan.points().getFirst()[0]-resident.x,plan.points().getFirst()[1]-resident.z)>=2.55,
                "Duration cannot shrink all possible walking points into the fountain");
        }
    }
    @Test void furnitureSignsAndBuildingsAreAvoidedEvenBetweenWaypoints() {
        State state=new State();state.geographyVersion=0;
        Building grounds=new Building("grounds","Park","park","",30,30,10);state.buildings.put(grounds.id,grounds);
        // Exercise local detouring rather than checking only chosen endpoints.
        Resident resident=resident(state,"walker",grounds.id);resident.x=28.5;resident.z=27;
        WorldObjects.PlacedObject sign=new WorldObjects.PlacedObject();sign.id="sign";sign.x=30;sign.z=27;state.worldObjects.put(sign.id,sign);
        List<double[]> detour=WalkingRoute.path(state,grounds.id,30,30,8,resident.x,resident.z,new double[]{31.5,27});
        assertFalse(detour.isEmpty());assertTrue(detour.size()>1);
        double x=resident.x,z=resident.z;
        for(double[] point:detour) {assertTrue(WalkingRoute.clearSegment(state,grounds.id,30,30,8,x,z,point[0],point[1]));x=point[0];z=point[1];}
        assertFalse(WalkingRoute.allowed(state,grounds.id,30,30,8,grounds.x-3.2,grounds.z),"Do not walk through the visible gathering bench");
        Building enclosed=new Building("shed","Shed","house","",28,28,1);state.buildings.put(enclosed.id,enclosed);
        assertFalse(WalkingRoute.allowed(state,grounds.id,30,30,8,enclosed.x,enclosed.z));
    }
    @Test void deterministicItinerariesAndTheirAccessLegSurviveSerialization() throws Exception {
        State state=State.seed();Resident resident=resident(state,"walker","meadow-commons");
        WalkingRoute.Plan first=WalkingRoute.plan(state,resident,resident.location,20),second=WalkingRoute.plan(state,resident,resident.location,20);
        ObjectMapper json=new ObjectMapper();assertEquals(json.valueToTree(first),json.valueToTree(second));
        WalkingRoute.Plan restored=json.readValue(json.writeValueAsString(first),WalkingRoute.Plan.class);
        assertEquals(json.valueToTree(first),json.valueToTree(restored));assertCircuit(state,resident.location,restored);
    }
    @Test void aMeadowWalkDetoursAroundTheActualBirchAndCannotStepThroughItsTrunk() {
        State state=State.seed();Building place=state.buildings.get("meadow-commons");
        var birch=OutdoorPlanting.layout(state,place).trees().stream().filter(t->t.id()==439067).findFirst().orElseThrow();
        assertEquals(-422.16088075026926,birch.x(),1e-8);assertEquals(-16.281968493256137,birch.z(),1e-8);
        assertFalse(WalkingRoute.allowed(state,place.id,place.x,place.z,place.outdoorRadius,birch.x(),-16.56076105679499),
            "The prior recorded stroll passed inside this rendered birch's trunk plus the resident's body");
        double fromZ=birch.z()-.8,toZ=birch.z()+.8;
        assertTrue(WalkingRoute.allowed(state,place.id,place.x,place.z,place.outdoorRadius,birch.x(),fromZ));
        assertTrue(WalkingRoute.allowed(state,place.id,place.x,place.z,place.outdoorRadius,birch.x(),toZ));
        assertFalse(WalkingRoute.allowedFrom(state,place.id,place.x,place.z,place.outdoorRadius,birch.x(),fromZ,birch.x(),toZ),
            "Clear endpoints cannot hide a swept collision between them");
        double tangentX=birch.x()+birch.trunkRadius()+.35-.02;
        assertTrue(WalkingRoute.allowed(state,place.id,place.x,place.z,place.outdoorRadius,tangentX,birch.z()-.24));
        assertTrue(WalkingRoute.allowed(state,place.id,place.x,place.z,place.outdoorRadius,tangentX,birch.z()+.24));
        assertFalse(WalkingRoute.allowedFrom(state,place.id,place.x,place.z,place.outdoorRadius,tangentX,birch.z()-.24,tangentX,birch.z()+.24),
            "Even one normal half-unit walking step must not cut a trunk corner");
        var detour=WalkingRoute.path(state,place.id,place.x,place.z,place.outdoorRadius,birch.x(),fromZ,new double[]{birch.x(),toZ});
        assertTrue(detour.size()>1);double x=birch.x(),z=fromZ;
        for(var point:detour) {
            assertTrue(WalkingRoute.clearSegment(state,place.id,place.x,place.z,place.outdoorRadius,x,z,point[0],point[1]));
            x=point[0];z=point[1];
        }
    }
    @Test void gardenWalksAvoidRaisedPlantersAsPhysicalObstacles() {
        State state=State.seed();Building garden=state.buildings.get("community-garden");
        var beds=OutdoorPlanting.layout(state,garden).beds();assertFalse(beds.isEmpty());
        for(var bed:beds)assertFalse(WalkingRoute.allowed(state,garden.id,garden.x,garden.z,garden.outdoorRadius,bed.x(),bed.z()));
        WalkingRoute.Plan plan=WalkingRoute.plan(state,resident(state,"gardener",garden.id),garden.id,20);
        assertCircuit(state,garden.id,plan);
    }
    @Test void unsafeWaterRoadAndEnclosedStartsNeverInventAnAccessRoute() {
        State state=State.seed();Resident resident=resident(state,"walker","meadow-commons");
        resident.x=IslandGeography.ROADS.getFirst().getFirst().x();resident.z=IslandGeography.ROADS.getFirst().getFirst().z();
        assertTrue(WalkingRoute.plan(state,resident,resident.location,15).points().isEmpty());
        IslandLandscapes.Lake lake=IslandLandscapes.SPEC.lake();
        Building water=new Building("water","Lake","wilderness","",lake.x(),lake.z(),10);water.outdoorRadius=12;state.buildings.put(water.id,water);
        Resident onWater=resident(state,"on-water",water.id);assertTrue(WalkingRoute.plan(state,onWater,water.id,15).points().isEmpty());
        Resident indoors=resident(state,"indoors","apt-101");assertTrue(WalkingRoute.plan(state,indoors,indoors.location,15).points().isEmpty());
    }
}
